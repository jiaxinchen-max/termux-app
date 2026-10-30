#define _GNU_SOURCE
#include <unistd.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <errno.h>
int main(int argc, char **argv) {
    char b[4096]; ssize_t n; int e;
    if (argc != 3) return 2;
    int fd = open(argv[1], O_PATH|O_NOFOLLOW|O_CLOEXEC);
    if (fd < 0) return 3;
    errno=0; n=readlinkat(-1,"",b,sizeof(b)); e=errno;
    printf("badfd=%zd errno=%d\n",n,e); if(n!=-1||e!=EBADF)return 4;
    errno=0; n=readlinkat(fd,"",b,0); e=errno;
    printf("zero_size=%zd errno=%d\n",n,e); if(n!=-1||e!=EINVAL)return 5;
    n=readlinkat(fd,"",b,3);
    printf("truncated=%zd %.3s\n",n,b); if(n!=3||memcmp(b,"/ru",3))return 6;
    close(fd); fd=open(argv[2],O_PATH|O_CLOEXEC); if(fd<0)return 7;
    errno=0; n=readlinkat(fd,"",b,sizeof(b)); e=errno;
    printf("regular=%zd errno=%d\n",n,e); if(n!=-1||e!=ENOENT)return 8;
    close(fd); return 0;
}
