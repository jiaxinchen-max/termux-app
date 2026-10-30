#define _GNU_SOURCE
#include <unistd.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <errno.h>
int main(int argc,char **argv) {
    if(argc!=2)return 2;
    int fd=open(argv[1],O_PATH|O_NOFOLLOW|O_CLOEXEC);
    if(fd<0){perror("open O_PATH symlink");return 3;}
    char buf[4096];ssize_t size=readlinkat(fd,"",buf,sizeof(buf)-1);
    if(size<0){perror("readlinkat fd empty path");return 4;}
    buf[size]=0;printf("readlinkat(fd,empty)=%s\n",buf);close(fd);return 0;
}
