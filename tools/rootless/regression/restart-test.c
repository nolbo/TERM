/* SPDX-License-Identifier: Apache-2.0 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <linux/openat2.h>
#include <pthread.h>
#include <signal.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <ucontext.h>
#include <unistd.h>

#define CHECK(x) do { if (!(x)) { dprintf(2, "FAIL restart line %d: %s errno=%d\n", __LINE__, #x, errno); _exit(1); } } while (0)
static const char fifo_path[] = "/tmp/thread-fifo";
static const char nested_path[] = "/tmp/restart-nested-fifo";
static atomic_int ready, handled, nested_handled;
static pthread_t target;
static int interrupt_only, ignored, nested, signal_number, expect_altstack;
static long call_number;
static uintptr_t path_argument, how_argument;
static int directory_fd, pipe_fds[2];
static char *alternate_stack;
static const size_t alternate_size = 65536;

static void check_context(void *context, int inner)
{
#if defined(__aarch64__)
    ucontext_t *uc = context;
    CHECK(uc->uc_mcontext.regs[8] == (uint64_t)(inner ? SYS_openat : call_number));
    if (inner || call_number != SYS_read) {
        CHECK(uc->uc_mcontext.regs[1] == (inner ? (uintptr_t)nested_path : path_argument));
        if (!inner && call_number == SYS_openat2)
            CHECK(uc->uc_mcontext.regs[2] == how_argument && uc->uc_mcontext.regs[3] == sizeof(struct open_how));
    } else {
        CHECK(uc->uc_mcontext.regs[1] == path_argument && uc->uc_mcontext.regs[2] == 1);
    }
#else
    (void)context; (void)inner;
#endif
}

static void handler(int signal, siginfo_t *information, void *context)
{
    (void)information;
    int saved_errno = errno;
    int inner = signal == SIGUSR2;
    check_context(context, inner);
    volatile char scratch[8192];
    for (unsigned i = 0; i < sizeof(scratch); i++) scratch[i] = (char)0xa5;
    if (expect_altstack)
        CHECK((uintptr_t)&scratch[0] >= (uintptr_t)alternate_stack &&
              (uintptr_t)&scratch[sizeof(scratch) - 1] < (uintptr_t)alternate_stack + alternate_size);
    /* These intercepted syscalls replace the tracer's latest snapshots. The
     * interrupted call must already be saved as guest registers in ucontext. */
    int fd = syscall(SYS_openat, AT_FDCWD, "/input", O_RDONLY, 0);
    CHECK(fd >= 0);
    struct stat st; CHECK(syscall(SYS_fstat, fd, &st) == 0 && st.st_size == 8);
    CHECK(syscall(SYS_close, fd) == 0);
    if (inner) {
        atomic_fetch_add(&nested_handled, 1);
    } else {
        atomic_fetch_add(&handled, 1);
        if (nested) {
            fd = syscall(SYS_openat, AT_FDCWD, nested_path, O_RDONLY, 0);
            CHECK(fd >= 0);
            char byte; CHECK(syscall(SYS_read, fd, &byte, 1) == 1 && byte == 'n');
            CHECK(syscall(SYS_close, fd) == 0);
        }
    }
    errno = saved_errno;
}

static void write_fifo(const char *path, char byte)
{
    int fd = syscall(SYS_openat, AT_FDCWD, path, O_WRONLY, 0);
    CHECK(fd >= 0 && syscall(SYS_write, fd, &byte, 1) == 1 && syscall(SYS_close, fd) == 0);
}

static void *sender(void *unused)
{
    (void)unused;
    while (!atomic_load(&ready)) sched_yield();
    usleep(30000);
    CHECK(pthread_kill(target, signal_number) == 0);
    if (ignored) {
        usleep(30000);
    } else {
        while (!atomic_load(&handled)) sched_yield();
        if (nested) {
            usleep(30000);
            CHECK(pthread_kill(target, SIGUSR2) == 0);
            while (!atomic_load(&nested_handled)) sched_yield();
            write_fifo(nested_path, 'n');
        }
    }
    if (!interrupt_only) {
        if (call_number == SYS_read) CHECK(write(pipe_fds[1], "x", 1) == 1);
        else write_fifo(fifo_path, 'x');
    }
    return NULL;
}

static void run_case(const char *name)
{
    interrupt_only = !strcmp(name, "eintr");
    ignored = !strcmp(name, "ignored");
    nested = !strcmp(name, "nested");
    expect_altstack = !strcmp(name, "altstack");
    signal_number = !strcmp(name, "trap") ? SIGTRAP : SIGUSR1;
    call_number = !strcmp(name, "openat2") ? SYS_openat2 : !strcmp(name, "read") ? SYS_read : SYS_openat;
    directory_fd = !strcmp(name, "relative") ? open("/tmp", O_RDONLY | O_DIRECTORY) : AT_FDCWD;
    CHECK(directory_fd == AT_FDCWD || directory_fd >= 0);
    const char *path = directory_fd == AT_FDCWD ? fifo_path : "thread-fifo";
    char byte = 0;
    path_argument = call_number == SYS_read ? (uintptr_t)&byte : (uintptr_t)path;
    struct open_how how = {.flags = O_RDONLY};
    how_argument = (uintptr_t)&how;
    atomic_store(&ready, 0); atomic_store(&handled, 0); atomic_store(&nested_handled, 0);
    struct sigaction action = {.sa_sigaction = handler, .sa_flags = SA_SIGINFO};
    if (!interrupt_only) action.sa_flags |= SA_RESTART;
    if (expect_altstack) action.sa_flags |= SA_ONSTACK;
    sigemptyset(&action.sa_mask);
    if (ignored) { action.sa_handler = SIG_IGN; action.sa_flags = 0; }
    CHECK(sigaction(signal_number, &action, NULL) == 0);
    action.sa_sigaction = handler; action.sa_flags = SA_SIGINFO | SA_RESTART;
    CHECK(sigaction(SIGUSR2, &action, NULL) == 0);
    if (call_number == SYS_read) CHECK(pipe(pipe_fds) == 0);
    pthread_t worker; target = pthread_self();
    CHECK(pthread_create(&worker, NULL, sender, NULL) == 0);
    /* Use the same generic syscall instruction for this completed open and
     * the untraced read below, to reject stale snapshots of another syscall. */
    int completed = syscall(SYS_openat, AT_FDCWD, "/input", O_RDONLY, 0);
    CHECK(completed >= 0);
    atomic_store(&ready, 1);
    int result;
    errno = 0;
    if (call_number == SYS_read) result = syscall(SYS_read, pipe_fds[0], &byte, 1);
    else if (call_number == SYS_openat2) result = syscall(SYS_openat2, directory_fd, path, &how, sizeof(how));
    else result = syscall(SYS_openat, directory_fd, path, O_RDONLY, 0);
    if (interrupt_only) CHECK(result == -1 && errno == EINTR);
    else if (call_number == SYS_read) CHECK(result == 1 && byte == 'x');
    else {
        CHECK(result >= 0);
        CHECK(read(result, &byte, 1) == 1 && byte == 'x' && close(result) == 0);
    }
    CHECK(pthread_join(worker, NULL) == 0);
    CHECK(syscall(SYS_close, completed) == 0);
    CHECK(atomic_load(&handled) == (ignored ? 0 : 1));
    CHECK(atomic_load(&nested_handled) == (nested ? 1 : 0));
    if (call_number == SYS_read) CHECK(close(pipe_fds[0]) == 0 && close(pipe_fds[1]) == 0);
    if (directory_fd != AT_FDCWD) CHECK(close(directory_fd) == 0);
    dprintf(1, "CASE restart %s PASS\n", name);
}

int main(void)
{
    sigset_t mask; sigemptyset(&mask); CHECK(pthread_sigmask(SIG_SETMASK, &mask, NULL) == 0);
    alternate_stack = mmap(NULL, alternate_size, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    CHECK(alternate_stack != MAP_FAILED);
    stack_t stack = {.ss_sp = alternate_stack, .ss_size = alternate_size};
    CHECK(sigaltstack(&stack, NULL) == 0);
    CHECK(mkfifo(nested_path, 0600) == 0 || errno == EEXIST);
    const char *cases[] = {"restart", "eintr", "ignored", "relative", "openat2", "altstack", "nested", "trap", "read"};
    for (unsigned i = 0; i < sizeof(cases) / sizeof(cases[0]); i++) run_case(cases[i]);
    CHECK(unlink(nested_path) == 0);
    stack.ss_flags = SS_DISABLE; CHECK(sigaltstack(&stack, NULL) == 0);
    CHECK(munmap(alternate_stack, alternate_size) == 0);
    puts("PASS syscall restart: guest signal context, EINTR, ignored/trap signals, relative/openat2 paths, altstack, nested handlers and unrelated read");
    return 0;
}
