/* SPDX-License-Identifier: Apache-2.0 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <linux/close_range.h>
#include <linux/futex.h>
#include <pthread.h>
#include <sched.h>
#include <signal.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/resource.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>

#define CHECK(x) do { if (!(x)) { dprintf(2, "FAIL fork line %d: %s errno=%d\n", __LINE__, #x, errno); _exit(1); } } while (0)
static atomic_int started, iterations, separated;
static int keep, drop;
#define FD_LIMIT 16
static unsigned initial_available = FD_LIMIT;
static struct {
    int present, descriptor_flags, status_flags;
    struct stat identity;
} inherited[FD_LIMIT];

static void remember_inherited_descriptors(void)
{
    for (int fd = 0; fd < FD_LIMIT; fd++) {
        int flags = syscall(SYS_fcntl, fd, F_GETFD);
        if (flags < 0) { CHECK(errno == EBADF); continue; }
        inherited[fd].present = 1;
        inherited[fd].descriptor_flags = flags;
        inherited[fd].status_flags = syscall(SYS_fcntl, fd, F_GETFL);
        CHECK(inherited[fd].status_flags >= 0);
        CHECK(syscall(SYS_fstat, fd, &inherited[fd].identity) == 0);
        initial_available--;
    }
    CHECK(inherited[STDIN_FILENO].present && initial_available >= 3);
}

static void check_inherited_descriptors(void)
{
    for (int fd = 0; fd < FD_LIMIT; fd++) {
        if (!inherited[fd].present) continue;
        CHECK(syscall(SYS_fcntl, fd, F_GETFD) == inherited[fd].descriptor_flags);
        CHECK(syscall(SYS_fcntl, fd, F_GETFL) == inherited[fd].status_flags);
        struct stat actual;
        CHECK(syscall(SYS_fstat, fd, &actual) == 0);
        CHECK(actual.st_dev == inherited[fd].identity.st_dev && actual.st_ino == inherited[fd].identity.st_ino);
        CHECK(actual.st_mode == inherited[fd].identity.st_mode);
    }
}

static void capacity(unsigned fixture_descriptors)
{
    CHECK(initial_available >= fixture_descriptors);
    unsigned expected = initial_available - fixture_descriptors;
    check_inherited_descriptors();
    int fds[FD_LIMIT]; unsigned count = 0;
    while (count < FD_LIMIT) {
        int fd = dup(0);
        if (fd < 0) { CHECK(errno == EMFILE); break; }
        fds[count++] = fd;
    }
    if (count != expected) {
        dprintf(2, "FD_CAPACITY available=%u expected=%u\n", count, expected);
        for (int fd = 0; fd < FD_LIMIT; fd++) {
            unsigned i; for (i = 0; i < count && fds[i] != fd; i++) {}
            if (i != count) continue;
            struct stat st; int flags = fcntl(fd, F_GETFL), found = fstat(fd, &st) == 0;
            dprintf(2, "FD_RETAINED fd=%d flags=%d mode=%o\n", fd, flags, found ? st.st_mode : 0);
        }
    }
    CHECK(count == expected);
    while (count) CHECK(close(fds[--count]) == 0);
    check_inherited_descriptors();
}
static void unshare_handler(int signal)
{
    (void)signal;
    CHECK(syscall(SYS_close_range, UINT_MAX, UINT_MAX, CLOSE_RANGE_UNSHARE) == 0);
    atomic_store(&separated, 1);
}
static void *fifo(void *argument)
{
    int unsharing = argument != NULL;
    if (unsharing) {
        struct sigaction action = {.sa_handler = unshare_handler, .sa_flags = SA_RESTART};
        sigemptyset(&action.sa_mask); CHECK(sigaction(SIGUSR1, &action, NULL) == 0);
    }
    atomic_fetch_add(&started, 1);
    int fd = open("/tmp/thread-fifo", O_RDONLY);
    CHECK(fd >= 0);
    char byte; CHECK(read(fd, &byte, 1) == 1 && byte == 'x'); CHECK(close(fd) == 0);
    if (unsharing) { CHECK(close(keep) == 0 && close(drop) == 0); capacity(0); }
    return NULL;
}
static void *busy(void *unused)
{
    (void)unused; atomic_fetch_add(&started, 1);
    for (;;) {
        struct stat st; CHECK(stat("/input", &st) == 0);
        void *p = mmap(NULL, 4096, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
        CHECK(p != MAP_FAILED && munmap(p, 4096) == 0); atomic_fetch_add(&iterations, 1);
    }
}
static void *child_thread(void *unused) { return unused; }
static _Noreturn void fork_loop(void)
{
    for (int i = 0; i < 30; i++) {
        pid_t child = fork(); CHECK(child >= 0);
        if (!child) {
            CHECK(syscall(SYS_gettid) == getpid());
            CHECK(close(keep) == 0 && close(drop) == 0); capacity(0);
            pthread_t thread; void *result = NULL;
            CHECK(pthread_create(&thread, NULL, child_thread, (void *)17) == 0);
            CHECK(pthread_join(thread, &result) == 0 && result == (void *)17);
            char *args[] = {"/bin/true", NULL}; char *env[] = {NULL};
            execve(args[0], args, env); CHECK(0);
        }
        int status; CHECK(waitpid(child, &status, 0) == child);
        CHECK(WIFEXITED(status) && WEXITSTATUS(status) == 0);
        CHECK(fcntl(keep, F_GETFD) == 0 && fcntl(drop, F_GETFD) == FD_CLOEXEC);
        int before = atomic_load(&iterations);
        while (atomic_load(&iterations) == before) sched_yield();
    }
    puts("PASS 30 concurrent forks: child runtime locks/FD cleanup/thread creation/exec, surviving parent workers");
    fflush(stdout); _exit(0);
}
static void *fork_worker(void *unused) { (void)unused; usleep(30000); fork_loop(); }
static int private_table(void *unused)
{
    (void)unused;
    CHECK(close(keep) == 0 && close(drop) == 0); capacity(0); return 0;
}
int main(int argc, char **argv)
{
    CHECK(argc == 2);
    // Android ProcessBuilder inherits ART's blocked SIGUSR1. This fixture
    // explicitly requests delivery; the runtime must preserve inherited masks.
    sigset_t mask; sigemptyset(&mask); CHECK(pthread_sigmask(SIG_SETMASK, &mask, NULL) == 0);
    remember_inherited_descriptors();
    keep = open("/input", O_RDONLY); CHECK(keep >= 0);
    drop = fcntl(keep, F_DUPFD_CLOEXEC, 0); CHECK(drop >= 0);
    struct rlimit limit; CHECK(getrlimit(RLIMIT_NOFILE, &limit) == 0);
    limit.rlim_cur = FD_LIMIT; CHECK(setrlimit(RLIMIT_NOFILE, &limit) == 0);
    int unsharing = !strcmp(argv[1], "unshare-wait");
    pthread_t reader; CHECK(pthread_create(&reader, NULL, fifo, unsharing ? (void *)1 : NULL) == 0);
    while (!atomic_load(&started)) sched_yield(); usleep(30000);
    if (unsharing) {
        CHECK(pthread_kill(reader, SIGUSR1) == 0);
        while (!atomic_load(&separated)) sched_yield();
        // The reader still holds its new-table pin. Old-table duplicate pins
        // must already be gone, and equal descriptor numbers must be usable.
        capacity(2);
        int fd = open("/tmp/thread-fifo", O_WRONLY); CHECK(fd >= 0);
        CHECK(write(fd, "x", 1) == 1 && close(fd) == 0 && pthread_join(reader, NULL) == 0);
        CHECK(close(keep) == 0 && close(drop) == 0); capacity(0);
        puts("PASS unshare during restarted FIFO wait: both tables release pins and preserve guest FD independence"); return 0;
    }
    if (!strcmp(argv[1], "private-clone")) {
        size_t size = 1024 * 1024; void *stack = mmap(NULL, size, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
        CHECK(stack != MAP_FAILED);
        for (int i = 0; i < 160; i++) {
            int tid = 0;
            int flags = CLONE_VM | CLONE_SIGHAND | CLONE_THREAD | CLONE_FS | CLONE_PARENT_SETTID | CLONE_CHILD_CLEARTID;
            CHECK(clone(private_table, (char *)stack + size, flags, NULL, &tid, NULL, &tid) > 0);
            int observed;
            while ((observed = __atomic_load_n(&tid, __ATOMIC_ACQUIRE))) syscall(SYS_futex, &tid, FUTEX_WAIT, observed, NULL, NULL, 0);
            CHECK(fcntl(keep, F_GETFD) == 0 && fcntl(drop, F_GETFD) == FD_CLOEXEC);
        }
        puts("PASS 160 private-FD-table clone lifecycles with another thread blocked on a runtime pin"); fflush(stdout); _exit(0);
    }
    pthread_t worker; CHECK(pthread_create(&worker, NULL, busy, NULL) == 0);
    while (!atomic_load(&iterations)) sched_yield();
    if (!strcmp(argv[1], "fork-leader")) fork_loop();
    CHECK(!strcmp(argv[1], "fork-worker") || !strcmp(argv[1], "fork-exited-leader"));
    pthread_t forker; CHECK(pthread_create(&forker, NULL, fork_worker, NULL) == 0);
    if (!strcmp(argv[1], "fork-exited-leader")) pthread_exit(NULL);
    CHECK(pthread_join(forker, NULL) == 0); CHECK(0); return 1;
}
