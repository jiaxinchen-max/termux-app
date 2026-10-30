#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/sysmacros.h>
#include <unistd.h>

#define REQUIRE(value) do { if (!(value)) { fprintf(stderr, "FAIL line %d: %s errno=%d\n", __LINE__, #value, errno); exit(1); } } while (0)
typedef int (*statx_function)(int, const char *, int, unsigned int, struct statx *);
static statx_function call_statx;

static unsigned long long mount_id(int fd)
{
    char path[64], line[512];
    snprintf(path, sizeof(path), "/proc/self/fdinfo/%d", fd);
    FILE *file = fopen(path, "re");
    REQUIRE(file);
    unsigned long long value = 0;
    while (fgets(line, sizeof(line), file))
        if (sscanf(line, "mnt_id:%llu", &value) == 1) break;
    REQUIRE(fclose(file) == 0);
    REQUIRE(value > 0);
    return value;
}

static void check(int dirfd, const char *path, int flags, int reference)
{
    struct statx value;
    struct stat reference_value;
    REQUIRE(fstat(reference, &reference_value) == 0);
    REQUIRE(call_statx(dirfd, path, flags, STATX_BASIC_STATS | 0x1000U | 0x4000U, &value) == 0);
    REQUIRE(value.stx_mask & 0x1000U);
    REQUIRE(!(value.stx_mask & 0x4000U));
    REQUIRE(value.stx_mnt_id == mount_id(reference));
    REQUIRE(value.stx_ino == reference_value.st_ino);
    REQUIRE(value.stx_dev_major == major(reference_value.st_dev));
    REQUIRE(value.stx_dev_minor == minor(reference_value.st_dev));
    REQUIRE(value.stx_size == (unsigned long long)reference_value.st_size);
}

int main(int argc, char **argv)
{
    call_statx = (statx_function)dlsym(RTLD_DEFAULT, "statx");
    REQUIRE(call_statx);
    if (argc == 2 && strcmp(argv[1], "--expect-no-fdinfo") == 0) {
        struct statx value;
        REQUIRE(call_statx(AT_FDCWD, "/", 0, 0x1000U, &value) == -1);
        REQUIRE(errno == EACCES);
        puts("PASS unavailable proc-fdinfo preserves EACCES despite kernel mount-ID support");
        return 0;
    }
    REQUIRE(argc == 1);
    char directory[] = "/tmp/proot-statx-test.XXXXXX";
    REQUIRE(mkdtemp(directory));
    int root = open(directory, O_PATH | O_CLOEXEC);
    REQUIRE(root >= 0);
    int file = openat(root, "file", O_RDWR | O_CREAT | O_EXCL | O_CLOEXEC, 0600);
    REQUIRE(file >= 0);
    REQUIRE(write(file, "test", 4) == 4);
    REQUIRE(symlinkat("file", root, "link") == 0);
    int link = openat(root, "link", O_PATH | O_NOFOLLOW | O_CLOEXEC);
    REQUIRE(link >= 0);
    check(root, "file", 0, file);
    check(root, "link", 0, file);
    check(root, "link", AT_SYMLINK_NOFOLLOW, link);
    check(file, "", AT_EMPTY_PATH, file);
    check(file, NULL, AT_EMPTY_PATH, file);
    int cwd = open(".", O_PATH | O_CLOEXEC);
    REQUIRE(cwd >= 0);
    check(AT_FDCWD, "", AT_EMPTY_PATH, cwd);
    int proc = open("/proc/version", O_PATH | O_CLOEXEC);
    REQUIRE(proc >= 0);
    check(AT_FDCWD, "/proc/version", 0, proc);
    REQUIRE(unlinkat(root, "file", 0) == 0);
    check(file, "", AT_EMPTY_PATH, file);
    struct statx value;
    REQUIRE(call_statx(root, "file", 0, 0x1000U, &value) == -1 && errno == ENOENT);
    REQUIRE(call_statx(-1, "", AT_EMPTY_PATH, 0x1000U, &value) == -1 && errno == EBADF);
    REQUIRE(call_statx(AT_FDCWD, NULL, 0, 0x1000U, &value) == -1 && errno == EFAULT);
    REQUIRE(call_statx(root, "link", 0, 0x1000U, NULL) == -1 && errno == EFAULT);
    REQUIRE(unlinkat(root, "link", 0) == 0);
    REQUIRE(close(proc) == 0 && close(cwd) == 0 && close(link) == 0 && close(file) == 0 && close(root) == 0);
    REQUIRE(rmdir(directory) == 0);
    puts("PASS relative paths, symlinks, open/unlinked FDs, NULL AT_EMPTY_PATH, proc mount, real IDs, no UNIQUE and errors");
    return 0;
}
