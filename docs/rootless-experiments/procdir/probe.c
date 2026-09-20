#include <time.h>
/* Experiment only: no cache is installed in production fstat. */
int proot_procdir_probe(int fd, unsigned mode, unsigned count, unsigned long long *elapsed) {
    char full[64], number[32], expected[PATH_MAX], actual[PATH_MAX];
    snprintf(full,sizeof(full),"/proc/self/fd/%d",fd);
    snprintf(number,sizeof(number),"%d",fd);
    long directory=fast_syscall(SYS_openat,AT_FDCWD,(long)"/proc/self/fd",O_PATH|O_DIRECTORY|O_CLOEXEC,0);
    if(directory<0)return 1;
    struct stat original, current;
    if(fast_syscall(SYS_fstat,directory,(long)&original,0,0)<0)return 2;
    long length=fast_syscall(SYS_readlinkat,AT_FDCWD,(long)full,(long)expected,sizeof(expected));
    if(length<=0)return 3;
    struct timespec start,end;
    clock_gettime(CLOCK_MONOTONIC,&start);
    for(unsigned i=0;i<count;i++) {
        if(mode==2) {
            if(fast_syscall(SYS_fstat,directory,(long)&current,0,0)<0 ||
                current.st_dev!=original.st_dev || current.st_ino!=original.st_ino ||
                !S_ISDIR(current.st_mode))return 4;
        }
        long n=fast_syscall(SYS_readlinkat,mode ? directory : AT_FDCWD,
                           (long)(mode ? number : full),(long)actual,sizeof(actual));
        if(n!=length || memcmp(expected,actual,length))return 5;
    }
    clock_gettime(CLOCK_MONOTONIC,&end);
    *elapsed=(unsigned long long)(end.tv_sec-start.tv_sec)*1000000000ULL+end.tv_nsec-start.tv_nsec;
    return fast_syscall(SYS_close,directory,0,0,0)==0 ? 0 : 6;
}
