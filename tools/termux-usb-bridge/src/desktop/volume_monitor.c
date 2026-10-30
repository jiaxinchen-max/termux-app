/* SPDX-License-Identifier: MIT */
#define _GNU_SOURCE
#include <gio/gio.h>
#include <gio/gunixsocketaddress.h>
#include <gmodule.h>
#include <errno.h>
#include <signal.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <unistd.h>

typedef struct _TusbMonitor TusbMonitor;
typedef struct _TusbVolume TusbVolume;
typedef struct _TusbMount TusbMount;
typedef GObjectClass TusbVolumeClass;
typedef GObjectClass TusbMountClass;
typedef GVolumeMonitorClass TusbMonitorClass;

struct _TusbVolume {
    GObject parent;
    TusbMonitor *monitor; /* weak: monitor owns volumes */
    char *id,*name,*device,*uri,*runtime,*uuid;
    gboolean present;
    TusbMount *mount;
};
struct _TusbMount {
    GObject parent;
    TusbVolume *volume; /* weak */
    char *id,*name,*runtime,*uuid;
    GFile *root;
    GMount *underlying;
    gboolean underlying_gone;
};
struct _TusbMonitor {
    GVolumeMonitor parent;
    char *runtime;
    GHashTable *volumes;
    GFileMonitor *watch;
    guint timer;
    guint shadow_idle;
    gboolean refreshing;
};
static void volume_iface_init(GVolumeIface *iface);
static void mount_iface_init(GMountIface *iface);
static void refresh(TusbMonitor *monitor);
G_DEFINE_DYNAMIC_TYPE_EXTENDED(TusbVolume,tusb_volume,G_TYPE_OBJECT,0,
    G_IMPLEMENT_INTERFACE_DYNAMIC(G_TYPE_VOLUME,volume_iface_init))
G_DEFINE_DYNAMIC_TYPE_EXTENDED(TusbMount,tusb_mount,G_TYPE_OBJECT,0,
    G_IMPLEMENT_INTERFACE_DYNAMIC(G_TYPE_MOUNT,mount_iface_init))
G_DEFINE_DYNAMIC_TYPE(TusbMonitor,tusb_monitor,G_TYPE_VOLUME_MONITOR)

typedef struct {
    char *runtime,*id,*command;
    GAsyncReadyCallback callback;
    gpointer user_data;
} Operation;
static void operation_free(gpointer data)
{
    Operation *op=data;g_free(op->runtime);g_free(op->id);g_free(op->command);g_free(op);
}
static gboolean same_kernel_user(GSocket *socket)
{
    int pair[2];if(socketpair(AF_UNIX,SOCK_STREAM,0,pair)) return FALSE;
    gboolean same=FALSE;
#ifdef __linux__
    struct ucred local,remote;socklen_t size=sizeof(local);
    if(!getsockopt(pair[0],SOL_SOCKET,SO_PEERCRED,&local,&size)) {
        size=sizeof(remote);
        same=!getsockopt(g_socket_get_fd(socket),SOL_SOCKET,SO_PEERCRED,&remote,&size) && local.uid==remote.uid;
    }
#else
    uid_t local,remote;gid_t group;
    same=!getpeereid(pair[0],&local,&group) && !getpeereid(g_socket_get_fd(socket),&remote,&group) && local==remote;
#endif
    close(pair[0]);close(pair[1]);return same;
}
static void operation_worker(GTask *task,gpointer source,gpointer task_data,GCancellable *cancel)
{
    (void)source;Operation *op=task_data;GError *error=NULL;
    char *path=g_build_filename(op->runtime,"control.sock",NULL);
    GSocketAddress *address=g_unix_socket_address_new(path);g_free(path);
    GSocketClient *client=g_socket_client_new();g_socket_client_set_timeout(client,115);
    GSocketConnection *connection=g_socket_client_connect(client,G_SOCKET_CONNECTABLE(address),cancel,&error);
    if(connection && !same_kernel_user(g_socket_connection_get_socket(connection)))
        g_set_error_literal(&error,G_IO_ERROR,G_IO_ERROR_PERMISSION_DENIED,"Unexpected desktop daemon UID");
    if(connection && !error) {
        char *request=g_strdup_printf("%s %s\n",op->command,op->id);
        GOutputStream *output=g_io_stream_get_output_stream(G_IO_STREAM(connection));
        g_output_stream_write_all(output,request,strlen(request),NULL,cancel,&error);g_free(request);
        if(!error) {
            GInputStream *input=g_io_stream_get_input_stream(G_IO_STREAM(connection));
            char response[2048];gsize length=0;
            while(length<sizeof(response)-1) {
                gssize n=g_input_stream_read(input,response+length,1,cancel,&error);
                if(n<=0 || response[length++]=='\n') break;
            }
            response[length]=0;
            if(!error && (!length || response[length-1]!='\n' || !g_str_has_prefix(response,"OK")))
                g_set_error(&error,G_IO_ERROR,G_IO_ERROR_FAILED,"%s",g_str_has_prefix(response,"ERR ")?response+4:"Invalid desktop daemon response");
        }
    }
    g_clear_object(&connection);g_object_unref(client);g_object_unref(address);
    if(error) g_task_return_error(task,error);else g_task_return_boolean(task,TRUE);
}
static void operation_done(GObject *source,GAsyncResult *result,gpointer data)
{
    (void)data;Operation *op=g_task_get_task_data(G_TASK(result));
    TusbVolume *volume=G_TYPE_CHECK_INSTANCE_TYPE(source,tusb_volume_get_type())?(TusbVolume *)source:((TusbMount *)source)->volume;
    if(volume && volume->monitor) refresh(volume->monitor);
    if(op->callback) op->callback(source,result,op->user_data);
}
static void operation_start(GObject *source,const char *runtime,const char *id,const char *command,
                            GCancellable *cancel,GAsyncReadyCallback callback,gpointer data)
{
    Operation *op=g_new0(Operation,1);op->runtime=g_strdup(runtime);op->id=g_strdup(id);
    op->command=g_strdup(command);op->callback=callback;op->user_data=data;
    GTask *task=g_task_new(source,cancel,operation_done,NULL);
    g_task_set_task_data(task,op,operation_free);g_task_run_in_thread(task,operation_worker);g_object_unref(task);
}
static gboolean operation_finish(gpointer object,GAsyncResult *result,GError **error)
{
    g_return_val_if_fail(g_task_is_valid(result,object),FALSE);
    return g_task_propagate_boolean(G_TASK(result),error);
}

static char *volume_name(GVolume *v){return g_strdup(((TusbVolume *)v)->name);}
static char *volume_uuid(GVolume *v){return g_strdup(((TusbVolume *)v)->uuid);}
static GIcon *volume_icon(GVolume *v){(void)v;return g_themed_icon_new_with_default_fallbacks("drive-removable-media-usb");}
static GIcon *volume_symbolic(GVolume *v){(void)v;return g_themed_icon_new_with_default_fallbacks("drive-removable-media-usb-symbolic");}
static GDrive *volume_drive(GVolume *v){(void)v;return NULL;}
static GMount *volume_mount(GVolume *v){TusbMount *m=((TusbVolume *)v)->mount;return m?G_MOUNT(g_object_ref(m)):NULL;}
static gboolean volume_can_mount(GVolume *v){return ((TusbVolume *)v)->present && !((TusbVolume *)v)->mount;}
static gboolean volume_can_eject(GVolume *v){return ((TusbVolume *)v)->present;}
static gboolean volume_automount(GVolume *v){(void)v;return FALSE;} /* coordinator owns auto-mount policy */
static GFile *volume_root(GVolume *v)
{TusbVolume *volume=(TusbVolume *)v;return volume->mount?g_object_ref(volume->mount->root):NULL;}
static const char *volume_sort(GVolume *v){return ((TusbVolume *)v)->id;}
static char *volume_identifier(GVolume *v,const char *kind)
{
    TusbVolume *volume=(TusbVolume *)v;
    if(!g_strcmp0(kind,"uuid")) return g_strdup(volume->uuid);
    if(!g_strcmp0(kind,"label")) return g_strdup(volume->name);
    if(!g_strcmp0(kind,"class")) return g_strdup("device");
    if(!g_strcmp0(kind,"tusb-device")) return g_strdup(volume->device);
    return NULL;
}
static char **volume_identifiers(GVolume *v)
{(void)v;const char *values[]={"uuid","label","class","tusb-device",NULL};return g_strdupv((char **)values);}
static void volume_mount_async(GVolume *v,GMountMountFlags flags,GMountOperation *operation,GCancellable *cancel,GAsyncReadyCallback cb,gpointer data)
{
    (void)flags;(void)operation;TusbVolume *volume=(TusbVolume *)v;
    operation_start(G_OBJECT(v),volume->runtime,volume->id,"mount",cancel,cb,data);
}
static void volume_eject_async(GVolume *v,GMountUnmountFlags flags,GCancellable *cancel,GAsyncReadyCallback cb,gpointer data)
{
    (void)flags;TusbVolume *volume=(TusbVolume *)v;
    operation_start(G_OBJECT(v),volume->runtime,volume->id,"eject",cancel,cb,data);
}
static void volume_eject_operation(GVolume *v,GMountUnmountFlags flags,GMountOperation *operation,GCancellable *cancel,GAsyncReadyCallback cb,gpointer data)
{(void)operation;volume_eject_async(v,flags,cancel,cb,data);}
static gboolean volume_finish(GVolume *v,GAsyncResult *result,GError **error){return operation_finish(v,result,error);}
static void volume_iface_init(GVolumeIface *iface)
{
    iface->get_name=volume_name;iface->get_uuid=volume_uuid;iface->get_icon=volume_icon;iface->get_symbolic_icon=volume_symbolic;
    iface->get_drive=volume_drive;iface->get_mount=volume_mount;iface->can_mount=volume_can_mount;iface->can_eject=volume_can_eject;
    iface->mount_fn=volume_mount_async;iface->mount_finish=volume_finish;iface->eject=volume_eject_async;iface->eject_finish=volume_finish;
    iface->eject_with_operation=volume_eject_operation;iface->eject_with_operation_finish=volume_finish;
    iface->should_automount=volume_automount;iface->get_activation_root=volume_root;iface->get_sort_key=volume_sort;
    iface->get_identifier=volume_identifier;iface->enumerate_identifiers=volume_identifiers;
}

static GFile *mount_root(GMount *m){return g_object_ref(((TusbMount *)m)->root);}
static char *mount_name(GMount *m){return g_strdup(((TusbMount *)m)->name);}
static char *mount_uuid(GMount *m){return g_strdup(((TusbMount *)m)->uuid);}
static GIcon *mount_icon(GMount *m){(void)m;return g_themed_icon_new_with_default_fallbacks("drive-removable-media-usb");}
static GIcon *mount_symbolic(GMount *m){(void)m;return g_themed_icon_new_with_default_fallbacks("drive-removable-media-usb-symbolic");}
static GVolume *mount_volume(GMount *m){TusbVolume *v=((TusbMount *)m)->volume;return v?G_VOLUME(g_object_ref(v)):NULL;}
static GDrive *mount_drive(GMount *m){(void)m;return NULL;}
static gboolean mount_can_unmount(GMount *m){return ((TusbMount *)m)->volume && ((TusbMount *)m)->volume->mount==(TusbMount *)m;}
static const char *mount_sort(GMount *m){return ((TusbMount *)m)->id;}
static void mount_unmount(GMount *m,GMountUnmountFlags flags,GCancellable *cancel,GAsyncReadyCallback cb,gpointer data)
{
    (void)flags;TusbMount *mount=(TusbMount *)m;
    g_signal_emit_by_name(m,"pre-unmount");
    if(mount->volume && mount->volume->monitor) g_signal_emit_by_name(mount->volume->monitor,"mount-pre-unmount",m);
    operation_start(G_OBJECT(m),mount->runtime,mount->id,"unmount",cancel,cb,data);
}
static void mount_eject(GMount *m,GMountUnmountFlags flags,GCancellable *cancel,GAsyncReadyCallback cb,gpointer data)
{
    (void)flags;TusbMount *mount=(TusbMount *)m;
    operation_start(G_OBJECT(m),mount->runtime,mount->id,"eject",cancel,cb,data);
}
static void mount_unmount_operation(GMount *m,GMountUnmountFlags f,GMountOperation *op,GCancellable *c,GAsyncReadyCallback cb,gpointer d)
{(void)op;mount_unmount(m,f,c,cb,d);}
static void mount_eject_operation(GMount *m,GMountUnmountFlags f,GMountOperation *op,GCancellable *c,GAsyncReadyCallback cb,gpointer d)
{(void)op;mount_eject(m,f,c,cb,d);}
static gboolean mount_finish(GMount *m,GAsyncResult *result,GError **error){return operation_finish(m,result,error);}
static char **mount_content_sync(GMount *m,gboolean force,GCancellable *cancel,GError **error)
{(void)m;(void)force;(void)cancel;(void)error;return g_new0(char *,1);}
static void mount_content(GMount *m,gboolean force,GCancellable *cancel,GAsyncReadyCallback cb,gpointer data)
{
    (void)force;GTask *task=g_task_new(m,cancel,cb,data);
    g_task_return_pointer(task,g_new0(char *,1),(GDestroyNotify)g_strfreev);g_object_unref(task);
}
static char **mount_content_finish(GMount *m,GAsyncResult *result,GError **error)
{g_return_val_if_fail(g_task_is_valid(result,m),NULL);return g_task_propagate_pointer(G_TASK(result),error);}
static void mount_iface_init(GMountIface *iface)
{
    iface->get_root=mount_root;iface->get_default_location=mount_root;iface->get_name=mount_name;iface->get_uuid=mount_uuid;
    iface->get_icon=mount_icon;iface->get_symbolic_icon=mount_symbolic;iface->get_volume=mount_volume;iface->get_drive=mount_drive;
    iface->can_unmount=mount_can_unmount;iface->can_eject=mount_can_unmount;iface->get_sort_key=mount_sort;
    iface->unmount=mount_unmount;iface->unmount_finish=mount_finish;iface->unmount_with_operation=mount_unmount_operation;
    iface->unmount_with_operation_finish=mount_finish;iface->eject=mount_eject;iface->eject_finish=mount_finish;
    iface->eject_with_operation=mount_eject_operation;iface->eject_with_operation_finish=mount_finish;
    iface->guess_content_type=mount_content;iface->guess_content_type_finish=mount_content_finish;iface->guess_content_type_sync=mount_content_sync;
}
static void underlying_removed(GMount *underlying,gpointer data)
{
    (void)underlying;TusbMount *mount=data;mount->underlying_gone=TRUE;
    char *uri=g_file_get_uri(mount->root);
    char *key=g_strdup_printf("%s %s",mount->id,uri);
    /* A delayed notification from a previous mount must not unmount a new one
     * or turn a deliberately ejected device back into an available volume. */
    operation_start(G_OBJECT(mount),mount->runtime,key,"detached",NULL,NULL,NULL);
    g_free(key);g_free(uri);
}
static void release_shadow(TusbMount *mount)
{
    if(!mount->underlying) return;
    g_signal_handlers_disconnect_by_func(mount->underlying,underlying_removed,mount);
    g_mount_unshadow(mount->underlying);
    if(!mount->underlying_gone) g_signal_emit_by_name(mount->underlying,"changed");
    g_clear_object(&mount->underlying);
}
static void tusb_mount_finalize(GObject *object)
{
    TusbMount *mount=(TusbMount *)object;
    if(mount->volume) g_object_remove_weak_pointer(G_OBJECT(mount->volume),(gpointer *)&mount->volume);
    release_shadow(mount);
    g_clear_object(&mount->root);g_free(mount->id);g_free(mount->name);g_free(mount->uuid);g_free(mount->runtime);
    G_OBJECT_CLASS(tusb_mount_parent_class)->finalize(object);
}
static void tusb_mount_init(TusbMount *mount){(void)mount;}
static void tusb_mount_class_init(TusbMountClass *klass){G_OBJECT_CLASS(klass)->finalize=tusb_mount_finalize;}
static void tusb_mount_class_finalize(TusbMountClass *klass){(void)klass;}
static void tusb_volume_finalize(GObject *object)
{
    TusbVolume *volume=(TusbVolume *)object;g_clear_object(&volume->mount);
    g_free(volume->id);g_free(volume->name);g_free(volume->device);g_free(volume->uri);g_free(volume->runtime);g_free(volume->uuid);
    G_OBJECT_CLASS(tusb_volume_parent_class)->finalize(object);
}
static void tusb_volume_init(TusbVolume *volume){(void)volume;}
static void tusb_volume_class_init(TusbVolumeClass *klass){G_OBJECT_CLASS(klass)->finalize=tusb_volume_finalize;}
static void tusb_volume_class_finalize(TusbVolumeClass *klass){(void)klass;}

static void detach_mount(TusbMonitor *monitor,TusbVolume *volume)
{
    if(!volume->mount) return;
    TusbMount *mount=volume->mount;volume->mount=NULL;
    g_signal_emit_by_name(mount,"unmounted");g_signal_emit_by_name(monitor,"mount-removed",mount);g_object_unref(mount);
}
static void attach_mount(TusbMonitor *monitor,TusbVolume *volume)
{
    TusbMount *mount=g_object_new(tusb_mount_get_type(),NULL);
    mount->volume=volume;g_object_add_weak_pointer(G_OBJECT(volume),(gpointer *)&mount->volume);
    mount->id=g_strdup(volume->id);mount->name=g_strdup(volume->name);mount->runtime=g_strdup(volume->runtime);mount->uuid=g_strdup(volume->uuid);
    mount->root=g_file_new_for_uri(volume->uri);
    /* Shadow the union monitor's canonical mount later. During construction GVfs
     * may still return a temporary GMount which is not the desktop's object. */
    volume->mount=mount;g_signal_emit_by_name(monitor,"mount-added",mount);
}
static gboolean safe_id(const char *id)
{
    if(!id || strlen(id)!=16) return FALSE;
    for(int i=0;i<16;i++) if(!g_ascii_isxdigit(id[i])) return FALSE;
    return TRUE;
}
static void refresh(TusbMonitor *monitor)
{
    if(monitor->refreshing) return;
    monitor->refreshing=TRUE;
    GKeyFile *file=g_key_file_new();char *path=g_build_filename(monitor->runtime,"volumes.ini",NULL);
    struct stat info;gboolean valid=!lstat(path,&info) && S_ISREG(info.st_mode) && !(info.st_mode&077) && info.st_size<1048576;
    valid=valid && g_key_file_load_from_file(file,path,G_KEY_FILE_NONE,NULL);
    gint64 pid=valid?g_key_file_get_int64(file,"desktop","pid",NULL):0;
    valid=valid && pid>1 && !(kill((pid_t)pid,0)<0 && errno==ESRCH);
    gsize count=0;char **groups=valid?g_key_file_get_groups(file,&count):NULL;
    GHashTable *seen=g_hash_table_new_full(g_str_hash,g_str_equal,g_free,NULL);
    for(gsize i=0;i<count;i++) {
        if(!g_str_has_prefix(groups[i],"volume:")) continue;
        char *id=g_key_file_get_string(file,groups[i],"id",NULL);
        char *name=g_key_file_get_string(file,groups[i],"name",NULL);
        char *device=g_key_file_get_string(file,groups[i],"device",NULL);
        char *uri=g_key_file_get_string(file,groups[i],"uri",NULL);
        gboolean mounted=g_key_file_get_boolean(file,groups[i],"mounted",NULL);
        if(!safe_id(id) || !name || !device || !uri || (mounted && !g_str_has_prefix(uri,"dav://tusb@127.0.0.1:"))) {
            g_free(id);g_free(name);g_free(device);g_free(uri);continue;
        }
        g_hash_table_add(seen,g_strdup(id));
        TusbVolume *volume=g_hash_table_lookup(monitor->volumes,id);
        gboolean added=!volume,changed=FALSE;
        if(added) {
            volume=g_object_new(tusb_volume_get_type(),NULL);volume->monitor=monitor;volume->present=TRUE;
            volume->id=g_strdup(id);volume->uuid=g_strconcat("tusb-",id,NULL);volume->runtime=g_strdup(monitor->runtime);
            g_hash_table_insert(monitor->volumes,g_strdup(id),volume);
        }
        changed=g_strcmp0(volume->name,name) || g_strcmp0(volume->uri,uri) || (!!volume->mount!=mounted);
        if(volume->mount && (!mounted || g_strcmp0(volume->uri,uri))) detach_mount(monitor,volume);
        g_free(volume->name);volume->name=name;g_free(volume->device);volume->device=device;g_free(volume->uri);volume->uri=uri;
        if(added) g_signal_emit_by_name(monitor,"volume-added",volume);
        if(mounted && !volume->mount) attach_mount(monitor,volume);
        if(changed) {g_signal_emit_by_name(volume,"changed");g_signal_emit_by_name(monitor,"volume-changed",volume);}
        g_free(id);
    }
    GHashTableIter iter;gpointer key,value;g_hash_table_iter_init(&iter,monitor->volumes);
    while(g_hash_table_iter_next(&iter,&key,&value)) if(!g_hash_table_contains(seen,key)) {
        TusbVolume *volume=value;detach_mount(monitor,volume);volume->present=FALSE;
        g_signal_emit_by_name(volume,"removed");g_signal_emit_by_name(monitor,"volume-removed",volume);
        volume->monitor=NULL;g_hash_table_iter_remove(&iter);
    }
    g_hash_table_unref(seen);g_strfreev(groups);g_key_file_unref(file);g_free(path);monitor->refreshing=FALSE;
}
static void reconcile_shadows(TusbMonitor *monitor)
{
    GVolumeMonitor *all=g_volume_monitor_get();
    GList *mounts=g_volume_monitor_get_mounts(all);
    GHashTableIter iter;gpointer value;g_hash_table_iter_init(&iter,monitor->volumes);
    while(g_hash_table_iter_next(&iter,NULL,&value)) {
        TusbMount *mount=((TusbVolume *)value)->mount;if(!mount) continue;
        for(GList *p=mounts;p;p=p->next) {
            GMount *candidate=p->data;
            if(G_TYPE_CHECK_INSTANCE_TYPE(candidate,tusb_mount_get_type())) continue;
            GFile *root=g_mount_get_root(candidate);gboolean equal=g_file_equal(root,mount->root);g_object_unref(root);
            if(equal && mount->underlying!=candidate) {
                release_shadow(mount);mount->underlying=g_object_ref(candidate);mount->underlying_gone=FALSE;
                g_signal_connect(candidate,"unmounted",G_CALLBACK(underlying_removed),mount);
                g_mount_shadow(candidate);
                g_signal_emit_by_name(candidate,"changed");
                g_signal_emit_by_name(all,"mount-changed",candidate);
                /* Thunar checks shadowing on mount-added, but does not remove
                 * an existing row on mount-changed. Withdraw the visible mount. */
                g_signal_emit_by_name(all,"mount-removed",candidate);
                break;
            }
        }
    }
    g_list_free_full(mounts,g_object_unref);g_object_unref(all);
}
static gboolean shadow_idle(gpointer data)
{TusbMonitor *monitor=data;monitor->shadow_idle=0;reconcile_shadows(monitor);return G_SOURCE_REMOVE;}
static gboolean tick(gpointer data){refresh(data);reconcile_shadows(data);return G_SOURCE_CONTINUE;}
static void state_changed(GFileMonitor *watch,GFile *file,GFile *other,GFileMonitorEvent event,gpointer data)
{(void)watch;(void)file;(void)other;(void)event;refresh(data);}
static gboolean supported(void){return !g_getenv("TUSB_DISABLE_VOLUME_MONITOR");}
static GList *monitor_volumes(GVolumeMonitor *base)
{
    TusbMonitor *monitor=(TusbMonitor *)base;GList *values=g_hash_table_get_values(monitor->volumes);
    for(GList *p=values;p;p=p->next) g_object_ref(p->data);
    return values;
}
static GList *monitor_mounts(GVolumeMonitor *base)
{
    TusbMonitor *monitor=(TusbMonitor *)base;GList *result=NULL;GHashTableIter iter;gpointer value;
    g_hash_table_iter_init(&iter,monitor->volumes);
    while(g_hash_table_iter_next(&iter,NULL,&value)) {TusbVolume *v=value;if(v->mount) result=g_list_prepend(result,g_object_ref(v->mount));}
    return result;
}
static GList *monitor_drives(GVolumeMonitor *base){(void)base;return NULL;}
static GVolume *monitor_volume_uuid(GVolumeMonitor *base,const char *uuid)
{
    if(!g_str_has_prefix(uuid,"tusb-")) return NULL;
    TusbVolume *volume=g_hash_table_lookup(((TusbMonitor *)base)->volumes,uuid+5);
    return volume?G_VOLUME(g_object_ref(volume)):NULL;
}
static GMount *monitor_mount_uuid(GVolumeMonitor *base,const char *uuid)
{
    GVolume *volume=monitor_volume_uuid(base,uuid);if(!volume) return NULL;
    GMount *mount=volume_mount(volume);g_object_unref(volume);return mount;
}
static void tusb_monitor_init(TusbMonitor *monitor)
{
    const char *runtime=g_getenv("TERMUX_USB_DESKTOP_DIR");
    monitor->runtime=runtime?g_strdup(runtime):g_build_filename(g_get_tmp_dir(),"termux-usb-bridge","desktop",NULL);
    monitor->volumes=g_hash_table_new_full(g_str_hash,g_str_equal,g_free,g_object_unref);
    GFile *directory=g_file_new_for_path(monitor->runtime);
    monitor->watch=g_file_monitor_directory(directory,G_FILE_MONITOR_NONE,NULL,NULL);g_object_unref(directory);
    if(monitor->watch) g_signal_connect(monitor->watch,"changed",G_CALLBACK(state_changed),monitor);
    monitor->timer=g_timeout_add_seconds(1,tick,monitor);refresh(monitor);
    monitor->shadow_idle=g_idle_add(shadow_idle,monitor);
}
static void tusb_monitor_finalize(GObject *object)
{
    TusbMonitor *monitor=(TusbMonitor *)object;
    if(monitor->timer) g_source_remove(monitor->timer);
    if(monitor->shadow_idle) g_source_remove(monitor->shadow_idle);
    g_clear_object(&monitor->watch);
    GHashTableIter iter;gpointer value;g_hash_table_iter_init(&iter,monitor->volumes);
    while(g_hash_table_iter_next(&iter,NULL,&value)) ((TusbVolume *)value)->monitor=NULL;
    g_hash_table_unref(monitor->volumes);g_free(monitor->runtime);
    G_OBJECT_CLASS(tusb_monitor_parent_class)->finalize(object);
}
static void tusb_monitor_class_init(TusbMonitorClass *klass)
{
    G_OBJECT_CLASS(klass)->finalize=tusb_monitor_finalize;klass->is_supported=supported;
    klass->get_connected_drives=monitor_drives;klass->get_volumes=monitor_volumes;klass->get_mounts=monitor_mounts;
    klass->get_volume_for_uuid=monitor_volume_uuid;klass->get_mount_for_uuid=monitor_mount_uuid;
}
static void tusb_monitor_class_finalize(TusbMonitorClass *klass){(void)klass;}
G_MODULE_EXPORT void g_io_module_load(GIOModule *module)
{
    tusb_volume_register_type(G_TYPE_MODULE(module));tusb_mount_register_type(G_TYPE_MODULE(module));tusb_monitor_register_type(G_TYPE_MODULE(module));
    g_io_extension_point_implement(G_VOLUME_MONITOR_EXTENSION_POINT_NAME,tusb_monitor_get_type(),"termux-usb",10);
}
G_MODULE_EXPORT void g_io_module_unload(GIOModule *module){(void)module;}
G_MODULE_EXPORT char **g_io_module_query(void)
{const char *points[]={G_VOLUME_MONITOR_EXTENSION_POINT_NAME,NULL};return g_strdupv((char **)points);}
