/* SPDX-License-Identifier: MIT */
#include <gio/gio.h>
#include <stdio.h>
#include <string.h>
static GMainLoop *loop;
static int result;
static void volume_event(GVolumeMonitor *monitor,GVolume *volume,gpointer name)
{
    (void)monitor;char *uuid=g_volume_get_uuid(volume),*label=g_volume_get_name(volume);
    if(uuid && g_str_has_prefix(uuid,"tusb-")) {printf("%s %s %s\n",(char *)name,uuid,label);fflush(stdout);}
    g_free(uuid);g_free(label);
}
static void mount_event(GVolumeMonitor *monitor,GMount *mount,gpointer name)
{
    (void)monitor;char *uuid=g_mount_get_uuid(mount);
    if(uuid && g_str_has_prefix(uuid,"tusb-")) {printf("%s %s\n",(char *)name,uuid);fflush(stdout);}
    g_free(uuid);
}
static gboolean timeout(gpointer data){(void)data;g_main_loop_quit(loop);return G_SOURCE_REMOVE;}
static void done(GObject *source,GAsyncResult *res,gpointer operation)
{
    GError *error=NULL;gboolean ok;
    if(!strcmp(operation,"mount")) ok=g_volume_mount_finish(G_VOLUME(source),res,&error);
    else if(!strcmp(operation,"eject")) ok=g_volume_eject_with_operation_finish(G_VOLUME(source),res,&error);
    else ok=g_mount_unmount_with_operation_finish(G_MOUNT(source),res,&error);
    if(!ok) {fprintf(stderr,"%s: %s\n",(char *)operation,error?error->message:"unknown error");result=1;}
    else printf("PASS GIO %s\n",(char *)operation);
    g_clear_error(&error);g_main_loop_quit(loop);
}
int main(int argc,char **argv)
{
    if(argc<2) return 2;
    GVolumeMonitor *monitor=g_volume_monitor_get();loop=g_main_loop_new(NULL,FALSE);
    if(!strcmp(argv[1],"watch")) {
        g_signal_connect(monitor,"volume-added",G_CALLBACK(volume_event),"VOLUME_ADDED");
        g_signal_connect(monitor,"volume-removed",G_CALLBACK(volume_event),"VOLUME_REMOVED");
        g_signal_connect(monitor,"volume-changed",G_CALLBACK(volume_event),"VOLUME_CHANGED");
        g_signal_connect(monitor,"mount-added",G_CALLBACK(mount_event),"MOUNT_ADDED");
        g_signal_connect(monitor,"mount-removed",G_CALLBACK(mount_event),"MOUNT_REMOVED");
        printf("WATCH_READY\n");fflush(stdout);g_timeout_add_seconds(60,timeout,NULL);g_main_loop_run(loop);
    } else if(!strcmp(argv[1],"list")) {
        GList *volumes=g_volume_monitor_get_volumes(monitor);
        for(GList *p=volumes;p;p=p->next) volume_event(monitor,p->data,"VOLUME");
        g_list_free_full(volumes,g_object_unref);
    } else if(!strcmp(argv[1],"visible")) {
        gint64 deadline=g_get_monotonic_time()+1500000;
        while(g_get_monotonic_time()<deadline) {
            while(g_main_context_iteration(NULL,FALSE)) {}
            g_usleep(10000);
        }
        GList *mounts=g_volume_monitor_get_mounts(monitor);
        for(GList *p=mounts;p;p=p->next) {
            GMount *mount=p->data;GFile *root=g_mount_get_root(mount);
            char *uri=g_file_get_uri(root);
            printf("MOUNT shadow=%d %s %s\n",g_mount_is_shadowed(mount),G_OBJECT_TYPE_NAME(mount),uri);
            g_free(uri);g_object_unref(root);
        }
        g_list_free_full(mounts,g_object_unref);
    } else {
        GVolume *volume=argc>2?g_volume_monitor_get_volume_for_uuid(monitor,argv[2]):NULL;
        if(!volume){fprintf(stderr,"USB volume not found\n");return 1;}
        GMount *mount=g_volume_get_mount(volume);
        if(!strcmp(argv[1],"info")) {
            printf("can_mount=%d can_eject=%d mounted=%d\n",g_volume_can_mount(volume),g_volume_can_eject(volume),mount!=NULL);
            if(mount) {
                GVolume *parent=g_mount_get_volume(mount);
                if(parent!=volume || g_mount_is_shadowed(mount)) result=1;
                g_clear_object(&parent);
                GFile *root=g_mount_get_root(mount);GError *error=NULL;
                GFileInfo *info=g_file_query_filesystem_info(root,"filesystem::*",NULL,&error);
                if(!info) {fprintf(stderr,"filesystem info: %s\n",error->message);g_clear_error(&error);result=1;}
                else {
                    printf("size=%"G_GUINT64_FORMAT" free=%"G_GUINT64_FORMAT"\n",
                        g_file_info_get_attribute_uint64(info,G_FILE_ATTRIBUTE_FILESYSTEM_SIZE),
                        g_file_info_get_attribute_uint64(info,G_FILE_ATTRIBUTE_FILESYSTEM_FREE));
                    if(!g_file_info_has_attribute(info,G_FILE_ATTRIBUTE_FILESYSTEM_SIZE) || !g_file_info_has_attribute(info,G_FILE_ATTRIBUTE_FILESYSTEM_FREE)) result=1;
                    g_object_unref(info);
                }
                g_object_unref(root);
            }
        } else if(!strcmp(argv[1],"mount")) {
            g_volume_mount(volume,G_MOUNT_MOUNT_NONE,NULL,NULL,done,"mount");g_main_loop_run(loop);
        } else if(!strcmp(argv[1],"eject")) {
            g_volume_eject_with_operation(volume,G_MOUNT_UNMOUNT_NONE,NULL,NULL,done,"eject");g_main_loop_run(loop);
        } else if(!strcmp(argv[1],"unmount") && mount) {
            g_mount_unmount_with_operation(mount,G_MOUNT_UNMOUNT_NONE,NULL,NULL,done,"unmount");g_main_loop_run(loop);
        } else result=2;
        g_clear_object(&mount);g_object_unref(volume);
    }
    g_main_loop_unref(loop);g_object_unref(monitor);return result;
}
