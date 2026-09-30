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
#include <signal.h>
#include <setjmp.h>
#include <stddef.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>
#define CHECK(x) do { if (!(x)) { fprintf(stderr,"FAIL %d: %s errno=%d\n",__LINE__,#x,errno); exit(1); } } while (0)
/* Force the DSO constructor outside glibc's allocation-free key block. */
int pthread_key_create(pthread_key_t *key, void (*destructor)(void *))
{
    int (*real_create)(pthread_key_t *, void (*)(void *)) = dlsym(RTLD_NEXT, "pthread_key_create");
    if (!real_create) _exit(92);
    static int reserved;
    if (!reserved && getenv("PROOT_FASTFD_TEST_HIGHKEY")) {
        reserved = 1;
        for (int i = 0; i < 32; i++) {
            pthread_key_t unused;
            if (real_create(&unused, NULL)) _exit(93);
        }
    }
    return real_create(key, destructor);
}
static int (*checked_stat)(int, struct stat *);
static unsigned long (*hits)(void);
static volatile sig_atomic_t signal_seen;
static int signal_fd;
static void during_signal(int number)
{
    (void)number;
    int saved_errno = errno;
    struct stat actual, expected;
    if (checked_stat(signal_fd, &actual) || syscall(SYS_fstat, signal_fd, &expected) ||
        actual.st_uid != expected.st_uid || actual.st_gid != expected.st_gid ||
        actual.st_ino != expected.st_ino) _exit(91);
    signal_seen = 1;
    errno = saved_errno;
}
static void *signal_worker(void *unused)
{
    (void)unused;
    struct stat result;
    CHECK(checked_stat(signal_fd, &result) == 0);
    CHECK(raise(SIGUSR1) == 0);
    return NULL;
}
static void *first_signal_worker(void *unused)
{
    (void)unused;
    CHECK(raise(SIGUSR1) == 0);
    return NULL;
}
static void check_thread_signals(void)
{
    struct sigaction action = { .sa_handler = during_signal }, old;
    CHECK(sigemptyset(&action.sa_mask) == 0);
    CHECK(sigaction(SIGUSR1, &action, &old) == 0);
    sigset_t signal_set, saved_mask;
    CHECK(sigemptyset(&signal_set) == 0 && sigaddset(&signal_set, SIGUSR1) == 0);
    CHECK(pthread_sigmask(SIG_UNBLOCK, &signal_set, &saved_mask) == 0);
    signal_fd = open("/tmp", O_RDONLY | O_DIRECTORY);
    CHECK(signal_fd >= 0);
    pthread_t thread;
    unsigned long before = hits();
    signal_seen = 0;
    CHECK(pthread_create(&thread, NULL, first_signal_worker, NULL) == 0);
    CHECK(pthread_join(thread, NULL) == 0);
    CHECK(signal_seen == 1 && hits() == before);
    signal_seen = 0;
    CHECK(pthread_create(&thread, NULL, signal_worker, NULL) == 0);
    CHECK(pthread_join(thread, NULL) == 0);
    CHECK(signal_seen == 1 && hits() == before);
    CHECK(close(signal_fd) == 0);
    CHECK(sigaction(SIGUSR1, &old, NULL) == 0);
    CHECK(pthread_sigmask(SIG_SETMASK, &saved_mask, NULL) == 0);
}
static _Thread_local sigjmp_buf jump_target;
static _Thread_local volatile sig_atomic_t jump_on_seal;
/* Inject only at sealing; the combined stdio DSO also queries stream flags. */
int fcntl(int fd, int command, ...)
{
    if (command == F_GETFL || command == F_GETFD)
        return syscall(SYS_fcntl, fd, command);
    CHECK(command == F_ADD_SEALS);
    va_list arguments;
    va_start(arguments, command);
    int value = va_arg(arguments, int);
    va_end(arguments);
    if (jump_on_seal) {
        jump_on_seal = 0;
        CHECK(raise(SIGUSR2) == 0);
    }
    return syscall(SYS_fcntl, fd, command, value);
}
static void jump_handler(int number)
{
    (void)number;
    siglongjmp(jump_target, 1);
}
static void check_initial_identity_signal(void)
{
    if (sigsetjmp(jump_target, 1) == 0) {
        jump_on_seal = 1;
        struct stat result;
        checked_stat(signal_fd, &result);
        CHECK(0); /* The pending signal must have escaped the first call. */
    }
    struct stat actual, expected;
    CHECK(checked_stat(signal_fd, &actual) == 0);
    CHECK(syscall(SYS_fstat, signal_fd, &expected) == 0);
    CHECK(actual.st_uid == expected.st_uid && actual.st_ino == expected.st_ino);
    sigset_t current;
    CHECK(pthread_sigmask(SIG_SETMASK, NULL, &current) == 0);
    CHECK(sigismember(&current, SIGUSR2) == 0);
}
/* Snapshot helpers must not use fopen/fstat wrappers: combined stdio tuning
 * would attach the first identity before the sealing signal can be injected. */
static int fd_count(void)
{
    struct linux_dirent64 {
        uint64_t ino;
        int64_t offset;
        unsigned short length;
        unsigned char type;
        char name[];
    };
    int directory = syscall(SYS_openat, AT_FDCWD, "/proc/self/fd", O_RDONLY | O_DIRECTORY | O_CLOEXEC, 0);
    CHECK(directory >= 0);
    int count = 0;
    char buffer[4096] __attribute__((aligned(8)));
    long length;
    while ((length = syscall(SYS_getdents64, directory, buffer, sizeof(buffer))) > 0) {
        for (long offset = 0; offset < length;) {
            struct linux_dirent64 *entry = (void *)(buffer + offset);
            CHECK(length - offset >= (long)offsetof(struct linux_dirent64, name) + 1);
            CHECK(entry->length >= offsetof(struct linux_dirent64, name) + 1 && entry->length <= length - offset);
            if (entry->name[0] != '.') count++;
            offset += entry->length;
        }
    }
    CHECK(length == 0 && syscall(SYS_close, directory) == 0);
    return count;
}
static int identity_map_count(void)
{
    int maps = syscall(SYS_openat, AT_FDCWD, "/proc/self/maps", O_RDONLY | O_CLOEXEC, 0);
    CHECK(maps >= 0);
    const char needle[] = "memfd:proot-fastfd";
    char buffer[4096 + sizeof(needle)];
    size_t carry = 0;
    int count = 0;
    long length;
    while ((length = syscall(SYS_read, maps, buffer + carry, 4096)) > 0) {
        size_t used = carry + (size_t)length;
        buffer[used] = 0;
        const char *match = buffer;
        while ((match = strstr(match, needle))) { count++; match += sizeof(needle) - 1; }
        carry = used < sizeof(needle) - 2 ? used : sizeof(needle) - 2;
        memmove(buffer, buffer + used - carry, carry);
    }
    CHECK(length == 0 && syscall(SYS_close, maps) == 0);
    return count;
}
static void check_nonlocal_exit(void)
{
    struct sigaction action = { .sa_handler = jump_handler }, old;
    CHECK(sigemptyset(&action.sa_mask) == 0);
    CHECK(sigaction(SIGUSR2, &action, &old) == 0);
    sigset_t signal_set, saved_mask;
    CHECK(sigemptyset(&signal_set) == 0 && sigaddset(&signal_set, SIGUSR2) == 0);
    CHECK(pthread_sigmask(SIG_UNBLOCK, &signal_set, &saved_mask) == 0);
    /* Run before the first identity is attached. The pending signal jumps
     * away as the initializer restores its mask; its memfd must be closed. */
    signal_fd = syscall(SYS_openat, AT_FDCWD, "/tmp", O_RDONLY | O_DIRECTORY, 0);
    CHECK(signal_fd >= 0);
    int before = fd_count();
    int mappings_before = identity_map_count();
    CHECK(mappings_before == 0);
    check_initial_identity_signal();
    CHECK(fd_count() == before);
    CHECK(identity_map_count() == mappings_before + 1);
    CHECK(close(signal_fd) == 0);
    CHECK(sigaction(SIGUSR2, &old, NULL) == 0);
    CHECK(pthread_sigmask(SIG_SETMASK, &saved_mask, NULL) == 0);
}
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
    int (*fd_enabled)(void) = dlsym(RTLD_DEFAULT, "proot_fastfd_enabled");
    CHECK(fd_enabled);
    if (getenv("PROOT_FASTFD_TEST_HIGHKEY")) {
        CHECK(fd_enabled() == 0);
        worker(NULL);
        CHECK(hits() == 0);
        puts("PASS high pthread key disables fastfd, ordinary fstat stays correct");
        return 0;
    }
    CHECK(fd_enabled() == 1);
    if (argc > 1 && !strcmp(argv[1], "signal-init")) {
        check_nonlocal_exit();
        puts("PASS interrupted first identity closes temporary FD and preserves fallback metadata");
        return 0;
    }
    worker(NULL);
    CHECK(hits() >= 64);
    if (argc > 1) { puts("PASS exec reinitialization"); return 0; }
    pid_t signal_child = fork(); CHECK(signal_child >= 0);
    if (!signal_child) { execl(argv[0], argv[0], "signal-init", NULL); _exit(99); }
    wait_ok(signal_child);
    check_thread_signals();
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
    const int unavailable[] = { SYS_memfd_create, SYS_rt_sigprocmask };
    for (unsigned i = 0; i < sizeof(unavailable) / sizeof(unavailable[0]); i++) {
        int denied_syscall = unavailable[i];
    child=fork(); CHECK(child>=0);
    if (!child) {
        unsigned long before=hits();
        struct sock_filter filter[]={
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,0),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,denied_syscall,0,1),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EPERM),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)};
        struct sock_fprog program={.len=4,.filter=filter};
        CHECK(prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0)==0);
        CHECK(prctl(PR_SET_SECCOMP,SECCOMP_MODE_FILTER,&program)==0);
        worker(NULL); CHECK(hits()==before); _exit(0);
    }
    wait_ok(child);
    }
    printf("PASS fastfd freshness, 8 threads, fork IDs, exec, FD reuse, EFAULT, seccomp; hits=%lu\n",hits());
    return 0;
}
