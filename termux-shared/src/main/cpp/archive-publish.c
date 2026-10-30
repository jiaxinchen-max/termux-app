#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/syscall.h>
#include <linux/fs.h>

JNIEXPORT jint JNICALL
Java_com_termux_shared_file_AtomicFilePublisher_renameNoReplace(
        JNIEnv *env, jclass type, jbyteArray source, jbyteArray destination) {
    (void)type;
    jbyte *from = (*env)->GetByteArrayElements(env, source, NULL);
    if (from == NULL) return ENOMEM;
    jbyte *to = (*env)->GetByteArrayElements(env, destination, NULL);
    if (to == NULL) {
        (*env)->ReleaseByteArrayElements(env, source, from, JNI_ABORT);
        return ENOMEM;
    }
    int result = syscall(__NR_renameat2, AT_FDCWD, (char *)from,
                         AT_FDCWD, (char *)to, RENAME_NOREPLACE);
    int error = result == 0 ? 0 : errno;
    (*env)->ReleaseByteArrayElements(env, destination, to, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, source, from, JNI_ABORT);
    return error;
}
