#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

/* Linux/glibc package-configuration backend. Mount IDs always come from the opened object's
 * /proc/self/fdinfo entry. Never advertise uniqueness or retry another metadata source. */
static int procfdinfo_statx(int, const char *, int, unsigned int, struct statx *);
__attribute__((constructor)) static void require_explicit_mode(void)
{
    const char *mode = getenv("TERMUX_PROOT_STATX_MODE");
    if (!mode || strcmp(mode, "proc-fdinfo") != 0) {
        static const char message[] = "statx backend requires TERMUX_PROOT_STATX_MODE=proc-fdinfo\n";
        write(2, message, sizeof(message) - 1);
        _exit(64);
    }
    const char *probe = getenv("TERMUX_PROOT_STATX_PROBE");
    if (probe) {
        if (strcmp(probe, "1") != 0)
            _exit(64);
        struct statx value;
        if (procfdinfo_statx(AT_FDCWD, "/", AT_NO_AUTOMOUNT, 0x1000U, &value) != 0) {
            dprintf(2, "ERROR: proc-fdinfo statx probe failed: %s\n", strerror(errno));
            _exit(65);
        }
        dprintf(2, "proot statx backend=proc-fdinfo mnt_id=%llu unique=0\n",
                (unsigned long long)value.stx_mnt_id);
    }
}

static int read_mount_id(int fd, unsigned long long *value)
{
    char path[64];
    snprintf(path, sizeof(path), "/proc/self/fdinfo/%d", fd);
    FILE *info = fopen(path, "re");
    if (!info)
        return -1;
    char *line = NULL;
    size_t allocated = 0;
    int error = ENODATA;
    while (getline(&line, &allocated, info) >= 0) {
        if (strncmp(line, "mnt_id:", 7) != 0)
            continue;
        char extra;
        errno = 0;
        int fields = sscanf(line, "mnt_id:%llu %c", value, &extra);
        error = fields == 1 && *value > 0 && errno == 0 ? 0 : EPROTO;
        break;
    }
    if (ferror(info))
        error = errno ? errno : EIO;
    free(line);
    if (fclose(info) != 0 && error == 0)
        error = errno;
    if (error != 0) {
        errno = error;
        return -1;
    }
    return 0;
}

static int procfdinfo_statx(int dirfd, const char *path, int flags, unsigned int mask, struct statx *result)
{
    if (!result || (!path && !(flags & AT_EMPTY_PATH))) {
        errno = EFAULT;
        return -1;
    }
    if (!path)
        path = "";
    typedef int (*statx_function)(int, const char *, int, unsigned int, struct statx *);
    statx_function real_statx = (statx_function)dlsym(RTLD_NEXT, "statx");
    if (!real_statx) {
        errno = ENOSYS;
        return -1;
    }
    if (!(mask & (0x1000U | 0x4000U)))
        return real_statx(dirfd, path, flags, mask, result);
    int fd;
    if (path[0] == '\0' && (flags & AT_EMPTY_PATH))
        fd = dirfd == AT_FDCWD ? open(".", O_PATH | O_CLOEXEC) : fcntl(dirfd, F_DUPFD_CLOEXEC, 0);
    else
        fd = openat(dirfd, path, O_PATH | O_CLOEXEC | ((flags & AT_SYMLINK_NOFOLLOW) ? O_NOFOLLOW : 0));
    if (fd < 0)
        return -1;
    /* The metadata and mount ID must describe the same opened object, including rename races. */
    int code = real_statx(fd, "", flags | AT_EMPTY_PATH, mask, result);
    int error = errno;
    if (code != 0) {
        close(fd);
        errno = error;
        return code;
    }
    int original_errno = errno;
    unsigned long long mount_id = 0;
    code = read_mount_id(fd, &mount_id);
    error = errno;
    if (close(fd) != 0 && code == 0) {
        code = -1;
        error = errno;
    }
    if (code != 0) {
        errno = error;
        return -1;
    }
    result->stx_mnt_id = mount_id;
    result->stx_mask = (result->stx_mask & ~0x4000U) | 0x1000U;
    errno = original_errno;
    return 0;
}

/* Keep glibc's historical nonnull annotation off this definition: Linux permits a NULL path
 * with AT_EMPTY_PATH. The public ELF symbol remains statx. */
int termux_procfdinfo_statx(int, const char *, int, unsigned int, struct statx *) __asm__("statx");
int termux_procfdinfo_statx(int dirfd, const char *path, int flags, unsigned int mask, struct statx *result)
{
    return procfdinfo_statx(dirfd, path, flags, mask, result);
}
