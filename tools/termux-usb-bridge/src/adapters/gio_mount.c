/* SPDX-License-Identifier: MIT */
#include <gio/gio.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

static GMainLoop *loop;
static GError *failure;
static const char *password;
static gboolean unmounting;
static void ask(GMountOperation *operation,const char *message,const char *user,const char *domain,
                GAskPasswordFlags flags,gpointer data)
{
    (void)message;(void)user;(void)domain;(void)flags;(void)data;
    g_mount_operation_set_username(operation,"tusb");
    g_mount_operation_set_password(operation,password);
    g_mount_operation_set_anonymous(operation,FALSE);
    g_mount_operation_set_password_save(operation,G_PASSWORD_SAVE_NEVER);
    g_mount_operation_reply(operation,G_MOUNT_OPERATION_HANDLED);
}
static void done(GObject *source,GAsyncResult *result,gpointer data)
{
    (void)data;
    if(unmounting) g_mount_unmount_with_operation_finish(G_MOUNT(source),result,&failure);
    else g_file_mount_enclosing_volume_finish(G_FILE(source),result,&failure);
    if(failure && (g_error_matches(failure,G_IO_ERROR,G_IO_ERROR_ALREADY_MOUNTED) ||
                   (unmounting && g_error_matches(failure,G_IO_ERROR,G_IO_ERROR_NOT_MOUNTED))))
        g_clear_error(&failure);
    g_main_loop_quit(loop);
}
static gboolean timed_out(gpointer data)
{g_cancellable_cancel(data);return G_SOURCE_REMOVE;}
int main(int argc,char **argv)
{
    /* Operations must reach the real GVfs mount, never recurse into our wrapper. */
    g_setenv("TUSB_DISABLE_VOLUME_MONITOR","1",TRUE);
    if(argc<3 || (g_strcmp0(argv[1],"mount") && g_strcmp0(argv[1],"unmount"))) {
        fprintf(stderr,"Usage: tusb-gio-mount mount URI TOKEN_FILE | unmount URI\n");return 2;
    }
    unmounting=!g_strcmp0(argv[1],"unmount");
    char *secret=NULL;
    if(!unmounting) {
        struct stat info;
        if(argc!=4 || lstat(argv[3],&info) || !S_ISREG(info.st_mode) || info.st_uid!=getuid() || (info.st_mode&077) ||
           !g_file_get_contents(argv[3],&secret,NULL,&failure)) {
            fprintf(stderr,"Invalid private token file\n");return 1;
        }
        g_strchomp(secret);password=secret;
    }
    GFile *file=g_file_new_for_uri(argv[2]);
    GMountOperation *operation=g_mount_operation_new();
    GCancellable *cancel=g_cancellable_new();
    loop=g_main_loop_new(NULL,FALSE);
    g_signal_connect(operation,"ask-password",G_CALLBACK(ask),NULL);
    guint timer=g_timeout_add_seconds(30,timed_out,cancel);
    if(unmounting) {
        GMount *mount=g_file_find_enclosing_mount(file,cancel,&failure);
        if(mount) {
            g_mount_unmount_with_operation(mount,G_MOUNT_UNMOUNT_NONE,operation,cancel,done,NULL);
            g_main_loop_run(loop);g_object_unref(mount);
        } else if(g_error_matches(failure,G_IO_ERROR,G_IO_ERROR_NOT_MOUNTED)) g_clear_error(&failure);
    } else {
        g_file_mount_enclosing_volume(file,G_MOUNT_MOUNT_NONE,operation,cancel,done,NULL);
        g_main_loop_run(loop);
    }
    if(g_main_context_find_source_by_id(NULL,timer)) g_source_remove(timer);
    if(failure) fprintf(stderr,"%s\n",failure->message);
    int result=failure?1:0;
    if(secret) {gsize length=strlen(secret);volatile char *p=secret;for(gsize i=0;i<length;i++) p[i]=0;g_free(secret);}
    g_clear_error(&failure);g_object_unref(file);g_object_unref(operation);g_object_unref(cancel);g_main_loop_unref(loop);
    return result;
}
