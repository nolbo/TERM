/* SPDX-License-Identifier: Apache-2.0 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <setjmp.h>
#include <signal.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <time.h>
#include <ucontext.h>
#include <unistd.h>

#define CHECK(x) do { if (!(x)) { dprintf(2, "FAIL blocking line %d: %s errno=%d\n", __LINE__, #x, errno); _exit(1); } } while (0)
static const char fifo[] = "/tmp/blocking-fifo", inner[] = "/tmp/blocking-inner";
static volatile sig_atomic_t mode, seen, nested, restarting;
static uintptr_t guest_stack;
static long expected_syscall;
static sigjmp_buf escape, inner_escape;
static atomic_int entered, cleaned;
static int reference;
static int listener = -1;

static void delay(void)
{
    struct timespec pause = {.tv_nsec = 30000000};
    while (nanosleep(&pause, &pause) && errno == EINTR) {}
}
static int free_fd(void)
{
    int fd = dup(STDERR_FILENO); CHECK(fd >= 0); CHECK(close(fd) == 0); return fd;
}
static void join_child(pid_t child)
{
    int status; CHECK(waitpid(child, &status, 0) == child && WIFEXITED(status) && WEXITSTATUS(status) == 0);
}
static void caught(int signal, siginfo_t *info, void *pointer)
{
    int saved = errno;
    ucontext_t *context = pointer;
    struct stat st;
    CHECK(info->si_signo == signal && stat("/input", &st) == 0);
    if (signal == SIGUSR2) {
        nested++;
        CHECK(syscall(SYS_close_range, 3U, ~0U, 0) == 0);
        siglongjmp(inner_escape, 1);
    }
    seen++;
    CHECK(context->uc_mcontext.regs[8] == (unsigned long)expected_syscall);
    CHECK(context->uc_mcontext.sp <= guest_stack && guest_stack - context->uc_mcontext.sp < 65536);
    if (restarting) CHECK(*(const uint32_t *)context->uc_mcontext.pc == 0xd4000001);
    else CHECK((long)context->uc_mcontext.regs[0] == -EINTR);
    if (mode == 1) siglongjmp(escape, 1);
    if (mode == 2) {
        pid_t child = fork(); CHECK(child >= 0);
        if (!child) { delay(); CHECK(kill(getppid(), SIGUSR2) == 0); _exit(0); }
        if (!sigsetjmp(inner_escape, 1)) { open(inner, O_RDONLY); CHECK(0); }
        join_child(child);
        CHECK(stat("/input", &st) == 0);
    }
    if (mode == 3) context->uc_mcontext.regs[0] = -EAGAIN;
    errno = saved;
}
static void install(int restart)
{
    struct sigaction action = {.sa_sigaction = caught, .sa_flags = SA_SIGINFO | (restart ? SA_RESTART : 0)};
    sigemptyset(&action.sa_mask);
    CHECK(sigaction(SIGUSR1, &action, NULL) == 0 && sigaction(SIGUSR2, &action, NULL) == 0);
    restarting = restart;
}
static void fifo_signals(void)
{
    char anchor; guest_stack = (uintptr_t)&anchor + 4096; expected_syscall = SYS_openat;
    for (int kind = 0; kind < 5; kind++) {
        mode = kind == 2 ? 1 : kind == 3 ? 2 : kind == 4 ? 3 : 0;
        install(kind == 1);
        for (int count = 0; count < (mode == 1 ? 16 : 1); count++) {
            struct stat st; CHECK(stat(fifo, &st) == 0 && S_ISFIFO(st.st_mode));
            int before = free_fd();
            pid_t child = fork(); CHECK(child >= 0);
            if (!child) {
                delay(); CHECK(kill(getppid(), SIGUSR1) == 0);
                if (kind == 1) {
                    delay(); int fd = open(fifo, O_RDWR | O_NONBLOCK); CHECK(fd >= 0);
                    CHECK(write(fd, "x", 1) == 1); delay(); CHECK(close(fd) == 0);
                }
                _exit(0);
            }
            if (!sigsetjmp(escape, 1)) {
                int fd = open(fifo, O_RDONLY);
                if (kind == 1) {
                    int error = errno;
                    join_child(child); child = 0;
                    if (reference && fd == -1 && error == ENOENT)
                        puts("LIMIT Rootless FIFO SA_RESTART returned ENOENT after a pathname syscall in the handler");
                    else {
                        char byte; CHECK(fd >= 0 && read(fd, &byte, 1) == 1 && byte == 'x'); CHECK(close(fd) == 0);
                        puts("PASS FIFO SA_RESTART after a pathname syscall in the handler");
                    }
                }
                else {
                    if (fd != -1 || errno != (mode == 3 ? EAGAIN : EINTR) || mode == 1)
                        dprintf(2, "FIFO scenario=%d iteration=%d result=%d errno=%d seen=%d\n", kind, count, fd, errno, seen);
                    CHECK(fd == -1 && errno == (mode == 3 ? EAGAIN : EINTR) && mode != 1);
                }
            } else CHECK(mode == 1);
            if (child) join_child(child);
            CHECK(free_fd() == before);
        }
    }
    CHECK(seen == 20 && nested == 1);
    puts("PASS FIFO EINTR, guest context edits, nested waits and repeated siglongjmp FD cleanup");
}
static void lock_signals(void)
{
    char anchor; guest_stack = (uintptr_t)&anchor + 4096; expected_syscall = SYS_fcntl; mode = 0;
    for (int ofd = 0; ofd < 2; ofd++) for (int restart = 0; restart < 2; restart++) {
        install(restart);
        int fd = open("/tmp/blocking-lock", O_CREAT | O_RDWR, 0600); CHECK(fd >= 0);
        int pipefd[2]; CHECK(pipe(pipefd) == 0);
        pid_t child = fork(); CHECK(child >= 0);
        if (!child) {
            CHECK(close(fd) == 0);
            fd = open("/tmp/blocking-lock", O_RDWR); CHECK(fd >= 0);
            struct flock lock = {.l_type = F_WRLCK, .l_whence = SEEK_SET};
            CHECK(fcntl(fd, ofd ? F_OFD_SETLK : F_SETLK, &lock) == 0 && write(pipefd[1], "r", 1) == 1);
            delay(); CHECK(kill(getppid(), SIGUSR1) == 0); delay(); CHECK(close(fd) == 0); _exit(0);
        }
        char byte; CHECK(read(pipefd[0], &byte, 1) == 1);
        struct flock lock = {.l_type = F_WRLCK, .l_whence = SEEK_SET};
        int result = fcntl(fd, ofd ? F_OFD_SETLKW : F_SETLKW, &lock);
        CHECK(restart ? result == 0 : result == -1 && errno == EINTR);
        join_child(child); CHECK(close(fd) == 0 && close(pipefd[0]) == 0 && close(pipefd[1]) == 0);
    }
    puts("PASS POSIX/OFD blocking lock EINTR and SA_RESTART");
}
static void terminal_signals(void)
{
    int signals[] = {SIGINT, SIGTERM};
    for (unsigned i = 0; i < sizeof(signals) / sizeof(signals[0]); i++) for (int handled = 0; handled < 2; handled++) {
        pid_t child = fork(); CHECK(child >= 0);
        if (!child) {
            char anchor; guest_stack = (uintptr_t)&anchor + 4096;
            expected_syscall = SYS_openat; mode = 0; restarting = 0;
            struct sigaction action = {.sa_flags = SA_SIGINFO}; sigemptyset(&action.sa_mask);
            if (handled) action.sa_sigaction = caught; else action.sa_handler = SIG_DFL;
            CHECK(sigaction(signals[i], &action, NULL) == 0);
            CHECK(open(fifo, O_RDONLY) == -1 && errno == EINTR); _exit(0);
        }
        delay(); CHECK(kill(child, signals[i]) == 0);
        int status; CHECK(waitpid(child, &status, 0) == child);
        CHECK(handled ? WIFEXITED(status) && WEXITSTATUS(status) == 0 : WIFSIGNALED(status) && WTERMSIG(status) == signals[i]);
    }
    puts("PASS SIGINT/SIGTERM caught interruption and default termination during FIFO open");
}
static void lease_signal(void)
{
    char anchor; guest_stack = (uintptr_t)&anchor + 4096; expected_syscall = SYS_openat; mode = 0;
    install(0);
    int fd = open("/tmp/blocking-lease", O_CREAT | O_RDWR, 0600); CHECK(fd >= 0 && close(fd) == 0);
    int pipefd[2]; CHECK(pipe(pipefd) == 0);
    pid_t child = fork(); CHECK(child >= 0);
    if (!child) {
        struct sigaction ignore = {.sa_handler = SIG_IGN}; sigemptyset(&ignore.sa_mask);
        CHECK(sigaction(SIGIO, &ignore, NULL) == 0);
        fd = open("/tmp/blocking-lease", O_RDWR); CHECK(fd >= 0);
        if (fcntl(fd, F_SETLEASE, F_WRLCK) == -1) {
            CHECK(errno == EINVAL || errno == EOPNOTSUPP || errno == EPERM);
            CHECK(write(pipefd[1], "s", 1) == 1); _exit(0);
        }
        CHECK(write(pipefd[1], "r", 1) == 1);
        delay(); CHECK(kill(getppid(), SIGUSR1) == 0); delay(); CHECK(close(fd) == 0); _exit(0);
    }
    char byte; CHECK(read(pipefd[0], &byte, 1) == 1);
    if (byte == 'r') CHECK(open("/tmp/blocking-lease", O_RDWR) == -1 && errno == EINTR);
    else CHECK(byte == 's');
    join_child(child); CHECK(close(pipefd[0]) == 0 && close(pipefd[1]) == 0);
    puts(byte == 'r' ? "PASS regular-file lease open interruption" : "SKIP file leases unavailable under the current filesystem/UID policy");
}
static void cleanup(void *unused)
{
    (void)unused; struct stat st; CHECK(stat("/input", &st) == 0);
    atomic_fetch_add(&cleaned, 1);
}
static void *worker(void *unused)
{
    (void)unused;
    pthread_cleanup_push(cleanup, NULL);
    atomic_store(&entered, 1);
    int fd;
    if (listener < 0) fd = open(fifo, O_RDONLY);
    else { struct sockaddr_in peer; socklen_t size = sizeof(peer); fd = accept(listener, (void *)&peer, &size); }
    if (fd >= 0) close(fd);
    pthread_cleanup_pop(0);
    CHECK(0); return NULL;
}
static void cancellation(void)
{
    for (int count = 0; count < 16; count++) {
        int before = free_fd(); pthread_t thread;
        if (count >= 8) {
            listener = socket(AF_INET, SOCK_STREAM, 0); CHECK(listener >= 0);
            struct sockaddr_in address = {.sin_family = AF_INET, .sin_addr.s_addr = htonl(INADDR_LOOPBACK)};
            CHECK(bind(listener, (void *)&address, sizeof(address)) == 0 && listen(listener, 1) == 0);
        }
        atomic_store(&entered, 0);
        CHECK(pthread_create(&thread, NULL, worker, NULL) == 0);
        while (!atomic_load(&entered)) sched_yield(); delay();
        CHECK(pthread_cancel(thread) == 0);
        void *result; CHECK(pthread_join(thread, &result) == 0 && result == PTHREAD_CANCELED);
        if (listener >= 0) CHECK(close(listener) == 0);
        CHECK(atomic_load(&cleaned) == count + 1 && free_fd() == before);
    }
    puts("PASS translated open/accept cancellation, pthread cleanup handlers and FD reclamation");
}
int main(int argc, char **argv)
{
    CHECK(argc == 2);
    sigset_t empty; sigemptyset(&empty); CHECK(sigprocmask(SIG_SETMASK, &empty, NULL) == 0);
    reference = !strcmp(argv[1], "signals-reference");
    if (!strcmp(argv[1], "signals") || reference) { fifo_signals(); lock_signals(); terminal_signals(); lease_signal(); }
    else { CHECK(!strcmp(argv[1], "cancel")); cancellation(); }
    return 0;
}
