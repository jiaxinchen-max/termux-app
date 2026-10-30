#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <stdio.h>
#include <string.h>

FILE *fopen(const char *path, const char *mode)
{
    if (strncmp(path, "/proc/self/fdinfo/", 18) == 0) {
        errno = EACCES;
        return NULL;
    }
    typedef FILE *(*function)(const char *, const char *);
    function next = (function)dlsym(RTLD_NEXT, "fopen");
    if (!next) {
        errno = ENOSYS;
        return NULL;
    }
    return next(path, mode);
}
