/* SPDX-License-Identifier: GPL-2.0-or-later */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/filter.h>
#include <linux/io_uring.h>
#include <linux/seccomp.h>
#include <signal.h>
#include <pthread.h>
#include <sys/wait.h>
#include <sched.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>
#define CHECK(c) do { if (!(c)) { fprintf(stderr,"FAIL %d: %s errno=%d\n",__LINE__,#c,errno); exit(1); } } while(0)
static volatile sig_atomic_t delivered;
static void handler(int signal) { (void)signal; delivered=1; }
static void *worker(void *unused) { (void)unused; return NULL; }
int main(int argc, char **argv) {
    CHECK(argc==2);
    int high = !strncmp(argv[1], "high-", 5);
    const char *operation = argv[1] + (high ? 5 : 0);
    unsigned long high_mask = high ? 1UL << 32 : 0;
    unsigned (*single)(void)=dlsym(RTLD_DEFAULT,"proot_fastfd_single_safe");CHECK(single);
    int fd=open("/tmp",O_RDONLY|O_DIRECTORY);CHECK(fd>=0);
    struct stat actual, expected;
    CHECK(fstat(fd,&actual)==0);
    if (!strcmp(operation, "after-on") || !strcmp(operation, "after-off")) {
        CHECK(single && single() == (unsigned)!strcmp(operation, "after-on"));
        puts("PASS exec eligibility"); CHECK(close(fd)==0); return 0;
    }
    if(single) CHECK(single()==1);
    long result=0; int error=0;
    if(!strcmp(operation,"signal") || !strcmp(operation,"signal-exec")) {
        struct sigaction action={.sa_handler=handler};CHECK(sigemptyset(&action.sa_mask)==0);
        CHECK(sigaction(SIGUSR1,&action,NULL)==0);CHECK(raise(SIGUSR1)==0);CHECK(delivered);
    } else if((!strcmp(operation,"fork-exec") || !strcmp(operation,"clone-exec"))) {
        pid_t child = !strcmp(operation,"clone-exec") ? syscall(SYS_clone | high_mask, CLONE_FILES | SIGCHLD, 0, 0, 0, 0) : fork();CHECK(child>=0);
        if(!child) _exit(single && single()!=0 ? 91 : 0);
        int status;CHECK(waitpid(child,&status,0)==child && status==0);
    } else if(!strcmp(operation,"thread-exec")) {
        pthread_t thread;CHECK(pthread_create(&thread,NULL,worker,NULL)==0);
        CHECK(pthread_join(thread,NULL)==0);
    } else if(!strcmp(operation,"prctl") || !strcmp(operation,"prctl-exec")) {
        struct sock_filter filter=BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW);
        struct sock_fprog program={1,&filter};CHECK(prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0)==0);
        result=syscall(SYS_prctl, PR_SET_SECCOMP | high_mask, SECCOMP_MODE_FILTER, &program, 0, 0);error=errno;CHECK(result==0);
    } else {
        long number=strtol(operation,NULL,10);
        if(number==425) {
            struct io_uring_params parameters={0};result=syscall(425 | high_mask,1,&parameters);error=errno;
            if(result>=0) CHECK(close(result)==0);
        } else if(number==426) { result=syscall(426 | high_mask,-1,0,0,0,NULL,0);error=errno;CHECK(result==-1); }
        else if(number==427) { result=syscall(427 | high_mask,-1,0,NULL,0);error=errno;CHECK(result==-1); }
        else if(number==277) { unsigned action=SECCOMP_RET_ALLOW; result=syscall(277 | high_mask,SECCOMP_GET_ACTION_AVAIL,0,&action);error=errno;CHECK(result==0); }
        else CHECK(0);
    }
    printf("operation=%s result=%ld errno=%d single_safe=%u\n",argv[1],result,error,single ? single() : 0);
    if(single) CHECK(single()==0);
    CHECK(fstat(fd,&actual)==0);CHECK(syscall(SYS_fstat,fd,&expected)==0);
    CHECK(actual.st_ino==expected.st_ino && actual.st_mode==expected.st_mode && actual.st_uid==expected.st_uid);
    if(single) CHECK(single()==0);CHECK(close(fd)==0);
    if(strstr(operation,"-exec")) {
        const char *next = (!strcmp(operation,"425-exec") || !strcmp(operation,"prctl-exec")) ? "after-off" : "after-on";
        execl("/single-guard-test", "/single-guard-test", next, NULL); CHECK(0);
    }
    puts("PASS single-check revocation and metadata fallback");return 0;
}
