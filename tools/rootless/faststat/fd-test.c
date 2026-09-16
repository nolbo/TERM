/* SPDX-License-Identifier: GPL-2.0-or-later */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/filter.h>
#include <linux/memfd.h>
#include "protocol.h"
#include <linux/seccomp.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>
#define CHECK(x) do { if (!(x)) { fprintf(stderr,"FAIL %d: %s errno=%d\n",__LINE__,#x,errno); exit(1); } } while (0)
static int (*checked_stat)(int, struct stat *);
static unsigned long (*hits)(void);
static void compare(int fd)
{
    struct stat a, b;
    errno = EDOM;
    CHECK(checked_stat(fd, &a) == 0 && errno == EDOM);
    CHECK(syscall(SYS_fstat, fd, &b) == 0);
    CHECK(a.st_uid == b.st_uid && a.st_gid == b.st_gid && a.st_size == b.st_size);
    CHECK(a.st_ino == b.st_ino && a.st_nlink == b.st_nlink && a.st_mode == b.st_mode);
}
static void *worker(void *unused)
{
    (void)unused;
    char path[] = "/tmp/fastfd-XXXXXX";
    int fd = mkstemp(path);
    CHECK(fd >= 0 && unlink(path) == 0);
    for (int i=0; i<64; i++) { CHECK(ftruncate(fd,i)==0); compare(fd); }
    if (getuid()==0) {
    uid_t saved_uid; gid_t saved_gid;
    uid_t ru, eu; gid_t rg, eg;
    CHECK(getresuid(&ru,&eu,&saved_uid)==0 && getresgid(&rg,&eg,&saved_gid)==0);
    CHECK(syscall(SYS_setresgid,-1,-1,2345)==0);
    CHECK(syscall(SYS_setresuid,-1,-1,2345)==0);
    compare(fd);
    CHECK(syscall(SYS_setresgid,-1,-1,saved_gid)==0);
    CHECK(syscall(SYS_setresuid,-1,-1,saved_uid)==0);
    }
    CHECK(close(fd)==0);
    return NULL;
}
static void wait_ok(pid_t child)
{
    int status;
    CHECK(child > 0 && waitpid(child,&status,0)==child);
    CHECK(WIFEXITED(status) && WEXITSTATUS(status)==0);
}
int main(int argc, char **argv)
{
    alarm(30);
    checked_stat=dlsym(RTLD_DEFAULT,"fstat");
    hits=dlsym(RTLD_DEFAULT,"proot_fastfd_hits");
    CHECK(checked_stat && hits);
    worker(NULL);
    CHECK(hits() >= 64);
    if (argc > 1) { puts("PASS exec reinitialization"); return 0; }
    pthread_t threads[8];
    for (int i=0;i<8;i++) CHECK(pthread_create(&threads[i],NULL,worker,NULL)==0);
    for (int i=0;i<8;i++) CHECK(pthread_join(threads[i],NULL)==0);
    int fd=open("/tmp",O_RDONLY|O_DIRECTORY);
    CHECK(fd>=0); compare(fd);
    void *readonly=mmap(NULL,4096,PROT_READ,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    CHECK(readonly!=MAP_FAILED);
    CHECK(checked_stat(fd,readonly)==-1 && errno==EFAULT);
    CHECK(checked_stat(fd,(void *)1)==-1 && errno==EFAULT);
    CHECK(munmap(readonly,4096)==0);
    int pipes[2]; CHECK(pipe(pipes)==0); compare(pipes[0]);
    CHECK(dup2(pipes[0],fd)==fd); compare(fd);
    CHECK(close(fd)==0);
    struct stat st; CHECK(checked_stat(fd,&st)==-1 && errno==EBADF);
    close(pipes[0]); close(pipes[1]);
    pid_t child=fork(); CHECK(child>=0);
    if (!child) {
        worker(NULL);
        CHECK(syscall(SYS_setresgid,1234,1234,1234)==0);
        CHECK(syscall(SYS_setresuid,1234,1234,1234)==0);
        worker(NULL);
        _exit(0);
    }
    wait_ok(child); CHECK(getuid()==0); worker(NULL);
    child=fork(); CHECK(child>=0);
    if (!child) { execl(argv[0],argv[0],"exec",NULL); _exit(99); }
    wait_ok(child);
    child=fork(); CHECK(child>=0);
    if (!child) {
        worker(NULL);
        struct sock_filter filter[]={
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,0),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_fstat,0,1),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EPERM),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)};
        struct sock_fprog program={.len=4,.filter=filter};
        CHECK(prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0)==0);
        CHECK(prctl(PR_SET_SECCOMP,SECCOMP_MODE_FILTER,&program)==0);
        CHECK(checked_stat(STDOUT_FILENO,&st)==-1 && errno==EPERM);
        _exit(0);
    }
    wait_ok(child);
    int invalid=syscall(SYS_memfd_create,"invalid-fastfd",MFD_ALLOW_SEALING|MFD_CLOEXEC);
    CHECK(invalid>=0 && ftruncate(invalid,4096)==0);
    CHECK(syscall(SYS_prctl,PROOT_FASTFD_ATTACH,invalid,PROOT_FASTFD_VERSION,0,0)==-1);
    CHECK(fcntl(invalid,F_ADD_SEALS,F_SEAL_GROW|F_SEAL_SHRINK)==0);
    CHECK(syscall(SYS_prctl,PROOT_FASTFD_ATTACH,invalid,99,0,0)==-1);
    close(invalid);
    child=fork(); CHECK(child>=0);
    if (!child) {
        unsigned long before=hits();
        struct sock_filter filter[]={
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,0),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_memfd_create,0,1),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EPERM),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)};
        struct sock_fprog program={.len=4,.filter=filter};
        CHECK(prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0)==0);
        CHECK(prctl(PR_SET_SECCOMP,SECCOMP_MODE_FILTER,&program)==0);
        worker(NULL); CHECK(hits()==before); _exit(0);
    }
    wait_ok(child);
    printf("PASS fastfd freshness, 8 threads, fork IDs, exec, FD reuse, EFAULT, seccomp; hits=%lu\n",hits());
    return 0;
}
