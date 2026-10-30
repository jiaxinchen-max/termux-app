#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

/* Isolated-rootfs fault injection only. Reproduce the device's missing statx mount-ID bits. */
__attribute__((constructor)) static void require_explicit_test_mode(void)
{
    const char *mode = getenv("TERMUX_TEST_STATX_MODE");
    if (!mode || strcmp(mode, "omit-mount-id") != 0) {
        static const char message[] = "statx test requires TERMUX_TEST_STATX_MODE=omit-mount-id\n";
        write(2, message, sizeof(message) - 1);
        _exit(64);
    }
}

int statx(int dirfd, const char *path, int flags, unsigned int mask, struct statx *result)
{
    typedef int (*statx_function)(int, const char *, int, unsigned int, struct statx *);
    statx_function real_statx = (statx_function)dlsym(RTLD_NEXT, "statx");
    if (!real_statx) {
        errno = ENOSYS;
        return -1;
    }
    int code = real_statx(dirfd, path, flags, mask, result);
    if (code == 0)
        result->stx_mask &= ~(0x1000U | 0x4000U); /* STATX_MNT_ID | STATX_MNT_ID_UNIQUE */
    return code;
}
