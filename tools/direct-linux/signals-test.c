/* SPDX-License-Identifier: Apache-2.0 */
#define _GNU_SOURCE
#include <arpa/inet.h>
#include <errno.h>
#include <poll.h>
#include <setjmp.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/epoll.h>
#include <sys/select.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <time.h>
#include <ucontext.h>
#include <unistd.h>

#define CHECK(x) do { if (!(x)) { dprintf(2, "FAIL signal line %d: %s errno=%d\n", __LINE__, #x, errno); _exit(1); } } while (0)
static volatile sig_atomic_t delivered, nested, mode, saved_sys, saved_usr1, delivery_sys = 1;
static sigjmp_buf escape, inner_escape;
static sigset_t temporary;

static void handler(int signal, siginfo_t *info, void *pointer)
{
    int old_errno = errno;
    sigset_t current; struct stat st;
    CHECK(info->si_signo == signal);
    CHECK(sigprocmask(SIG_SETMASK, NULL, &current) == 0);
    CHECK(sigismember(&current, SIGSYS) == delivery_sys);
    CHECK(stat("/input", &st) == 0 && st.st_size == 8);
    if (signal == SIGUSR2) {
        nested++;
        CHECK(sigismember(&current, SIGUSR2) == 1);
        if (mode == 5) CHECK(sigismember(&((ucontext_t *)pointer)->uc_sigmask, SIGSYS) == 1);
        if (mode == 3) siglongjmp(inner_escape, 1);
        errno = old_errno; return;
    }
    ucontext_t *context = pointer;
    CHECK(sigismember(&context->uc_sigmask, SIGSYS) == saved_sys);
    CHECK(sigismember(&context->uc_sigmask, SIGUSR1) == saved_usr1);
    CHECK(sigismember(&current, SIGUSR1) == 1 && sigismember(&current, SIGUSR2) == 1);
    delivered++;
    if (mode == 4) {
        sigdelset(&context->uc_sigmask, SIGSYS);
        sigaddset(&context->uc_sigmask, SIGUSR2);
    }
    if (mode == 1) siglongjmp(escape, 1);
    if (mode == 5) {
        // An outer wait is suspended while its handler performs a different
        // translated blocking syscall. Its mask must not leak into that call.
        sigdelset(&current, SIGUSR2);
        CHECK(sigprocmask(SIG_SETMASK, &current, NULL) == 0);
        int listener = socket(AF_INET, SOCK_STREAM, 0); CHECK(listener >= 0);
        struct sockaddr_in address = {.sin_family = AF_INET, .sin_addr.s_addr = htonl(INADDR_LOOPBACK)};
        CHECK(bind(listener, (void *)&address, sizeof(address)) == 0 && listen(listener, 1) == 0);
        pid_t child = fork(); CHECK(child >= 0);
        if (!child) {
            struct timespec delay = {.tv_nsec = 20000000}; nanosleep(&delay, NULL);
            CHECK(kill(getppid(), SIGUSR2) == 0); _exit(0);
        }
        socklen_t length = sizeof(address);
        CHECK(accept(listener, (void *)&address, &length) == -1 && errno == EINTR);
        int status; CHECK(waitpid(child, &status, 0) == child && WIFEXITED(status) && WEXITSTATUS(status) == 0);
        CHECK(close(listener) == 0);
    }
    if (mode == 2 || mode == 3) {
        sigset_t waitmask = current; sigdelset(&waitmask, SIGUSR2);
        CHECK(raise(SIGUSR2) == 0);
        struct timespec timeout = {.tv_nsec = 100000000};
        if (sigsetjmp(inner_escape, 1) == 0) {
            CHECK(ppoll(NULL, 0, &timeout, &waitmask) == -1 && errno == EINTR);
            CHECK(mode == 2);
        } else CHECK(mode == 3);
        CHECK(sigprocmask(SIG_SETMASK, NULL, &current) == 0);
        CHECK(sigismember(&current, SIGSYS) == 1 && sigismember(&current, SIGUSR2) == 1);
        CHECK(stat("/input", &st) == 0);
    }
    errno = old_errno;
}

static int depth, maximum;
static void recursive(int signal)
{
    sigset_t current; struct stat st;
    CHECK(signal == SIGUSR1 && sigprocmask(SIG_SETMASK, NULL, &current) == 0);
    CHECK(sigismember(&current, SIGSYS) == 1 && sigismember(&current, SIGUSR1) == 0);
    CHECK(stat("/input", &st) == 0);
    depth++; if (depth > maximum) maximum = depth;
    if (depth < 3) CHECK(raise(SIGUSR1) == 0);
    depth--;
}

static void install(void)
{
    struct sigaction action = {.sa_sigaction = handler, .sa_flags = SA_SIGINFO};
    sigfillset(&action.sa_mask);
    CHECK(sigaction(SIGUSR1, &action, NULL) == 0);
    CHECK(sigaction(SIGUSR2, &action, NULL) == 0);
    struct sigaction queried;
    CHECK(sigaction(SIGUSR1, NULL, &queried) == 0 && queried.sa_sigaction == handler);
    CHECK((queried.sa_flags & SA_SIGINFO) && sigismember(&queried.sa_mask, SIGSYS) == 1);
}

static void check_restored(void)
{
    sigset_t current;
    CHECK(sigprocmask(SIG_SETMASK, NULL, &current) == 0);
    CHECK(sigismember(&current, SIGSYS) == saved_sys && sigismember(&current, SIGUSR1) == 1);
}

int main(int argc, char **argv)
{
    CHECK(argc == 2);
    sigset_t empty, blocked;
    sigemptyset(&empty); CHECK(sigprocmask(SIG_SETMASK, &empty, NULL) == 0);
    install(); saved_sys = saved_usr1 = 0;
    if (!strcmp(argv[1], "actions")) {
        CHECK(raise(SIGUSR1) == 0 && delivered == 1);
        sigset_t current; CHECK(sigprocmask(SIG_SETMASK, NULL, &current) == 0);
        CHECK(sigismember(&current, SIGSYS) == 0);
        struct sigaction action = {.sa_handler = recursive, .sa_flags = SA_NODEFER};
        sigemptyset(&action.sa_mask); sigaddset(&action.sa_mask, SIGSYS);
        CHECK(sigaction(SIGUSR1, &action, NULL) == 0);
        CHECK(raise(SIGUSR1) == 0 && maximum == 3 && depth == 0);
        action.sa_handler = SIG_IGN;
        CHECK(sigaction(SIGUSR1, &action, NULL) == 0);
        CHECK(sigaction(SIGUSR1, NULL, &action) == 0 && action.sa_handler == SIG_IGN);
        CHECK(sigismember(&action.sa_mask, SIGSYS) == 1);
        install(); CHECK(sigaction(SIGUSR1, NULL, &action) == 0);
        action.sa_flags |= SA_RESETHAND; CHECK(sigaction(SIGUSR1, &action, NULL) == 0);
        CHECK(raise(SIGUSR1) == 0 && delivered == 2);
        CHECK(sigaction(SIGUSR1, NULL, &action) == 0 && action.sa_handler == SIG_DFL);
        CHECK(sigismember(&action.sa_mask, SIGSYS) == 1);
        puts("PASS full signal-action mask, recursive syscalls, NODEFER, RESETHAND and action roundtrip");
        return 0;
    }
    CHECK(!strcmp(argv[1], "waits"));
    sigemptyset(&blocked); sigaddset(&blocked, SIGUSR1);
    CHECK(sigprocmask(SIG_SETMASK, &blocked, NULL) == 0); saved_usr1 = 1;
    sigfillset(&temporary); sigdelset(&temporary, SIGUSR1);
    int epoll = epoll_create1(EPOLL_CLOEXEC); CHECK(epoll >= 0);
    for (int kind = 0; kind < 5; kind++) {
        CHECK(raise(SIGUSR1) == 0);
        struct timespec timeout = {.tv_nsec = 100000000}; struct epoll_event event;
        int before = delivered; long result;
        if (kind == 0) result = ppoll(NULL, 0, &timeout, &temporary);
        else if (kind == 1) result = pselect(0, NULL, NULL, NULL, &timeout, &temporary);
        else if (kind == 2) result = epoll_pwait(epoll, &event, 1, 100, &temporary);
        else if (kind == 3) result = sigsuspend(&temporary);
        else result = syscall(SYS_epoll_pwait2, epoll, &event, 1, &timeout, &temporary, 8);
        if (kind == 4 && result == -1 && errno == ENOSYS) {
            siginfo_t info; struct timespec zero = {0};
            CHECK(sigtimedwait(&blocked, &info, &zero) == SIGUSR1);
            puts("SKIP epoll_pwait2 unavailable in kernel");
        } else CHECK(result == -1 && errno == EINTR && delivered == before + 1);
        check_restored();
    }
    puts("PASS ppoll/pselect/epoll/sigsuspend temporary masks, saved ucontext mask and EINTR");
    for (mode = 1; mode <= 3; mode++) {
        for (int i = 0; i < 80; i++) {
            int before = delivered;
            if (sigsetjmp(escape, 1) == 0) {
                CHECK(raise(SIGUSR1) == 0);
                struct timespec timeout = {.tv_nsec = 100000000};
                CHECK(ppoll(NULL, 0, &timeout, &temporary) == -1 && errno == EINTR);
                CHECK(mode != 1);
            } else CHECK(mode == 1);
            CHECK(delivered == before + 1); check_restored();
        }
    }
    CHECK(nested == 160); mode = 0;
    puts("PASS repeated siglongjmp, nested waits and jumps back into a signal handler");
    mode = 5;
    CHECK(raise(SIGUSR1) == 0);
    struct timespec network_timeout = {.tv_sec = 1};
    CHECK(ppoll(NULL, 0, &network_timeout, &temporary) == -1 && errno == EINTR);
    CHECK(nested == 161); check_restored(); mode = 0;
    puts("PASS signal handler blocks in translated accept with its own mask");
    struct sigaction action;
    CHECK(sigaction(SIGUSR1, NULL, &action) == 0);
    sigdelset(&action.sa_mask, SIGSYS);
    CHECK(sigaction(SIGUSR1, &action, NULL) == 0);
    CHECK(raise(SIGUSR1) == 0);
    struct timespec brief = {.tv_nsec = 100000000};
    CHECK(ppoll(NULL, 0, &brief, &temporary) == -1 && errno == EINTR);
    check_restored(); // The temporary mask supplies SIGSYS, not the action.
    sigaddset(&blocked, SIGSYS);
    CHECK(sigprocmask(SIG_SETMASK, &blocked, NULL) == 0); saved_sys = 1;
    sigdelset(&temporary, SIGSYS); delivery_sys = 0;
    CHECK(raise(SIGUSR1) == 0);
    CHECK(ppoll(NULL, 0, &brief, &temporary) == -1 && errno == EINTR);
    check_restored(); // The saved mask has SIGSYS while the handler mask does not.
    mode = 4;
    CHECK(raise(SIGUSR1) == 0);
    CHECK(ppoll(NULL, 0, &brief, &temporary) == -1 && errno == EINTR);
    saved_sys = 0; check_restored();
    sigset_t changed; CHECK(sigprocmask(SIG_SETMASK, NULL, &changed) == 0);
    CHECK(sigismember(&changed, SIGUSR2) == 1);
    mode = 0; delivery_sys = 1; install();
    puts("PASS distinct action/wait/saved masks and guest ucontext mask edits");
    struct timespec zero = {0};
    CHECK(ppoll(NULL, 0, &zero, &temporary) == 0); check_restored();
    CHECK(syscall(SYS_ppoll, NULL, 0, &zero, &temporary, 7) == -1 && errno == EINVAL);
    CHECK(syscall(SYS_ppoll, NULL, 0, &zero, (void *)1, 8) == -1 && errno == EFAULT);
    struct { void *set; size_t size; } bad = {&temporary, 7};
    CHECK(syscall(SYS_pselect6, 0, NULL, NULL, NULL, &zero, &bad) == -1 && errno == EINVAL);
    CHECK(syscall(SYS_pselect6, 0, NULL, NULL, NULL, &zero, (void *)1) == -1 && errno == EFAULT);
    struct epoll_event event;
    CHECK(syscall(SYS_epoll_pwait, epoll, &event, 1, 0, &temporary, 7) == -1 && errno == EINVAL);
    CHECK(syscall(SYS_epoll_pwait, epoll, &event, 1, 0, (void *)1, 8) == -1 && errno == EFAULT);
    CHECK(syscall(SYS_rt_sigsuspend, NULL, 7) == -1 && errno == EINVAL);
    CHECK(syscall(SYS_rt_sigsuspend, NULL, 8) == -1 && errno == EFAULT);
    CHECK(close(epoll) == 0);
    sigemptyset(&blocked); sigaddset(&blocked, SIGUSR2);
    CHECK(sigprocmask(SIG_SETMASK, &blocked, NULL) == 0); saved_usr1 = 0;
    pid_t child = fork(); CHECK(child >= 0);
    if (!child) {
        struct timespec delay = {.tv_nsec = 20000000}; nanosleep(&delay, NULL);
        CHECK(kill(getppid(), SIGUSR1) == 0); _exit(0);
    }
    struct timespec timeout = {.tv_sec = 1}; siginfo_t info;
    int before = delivered;
    CHECK(sigtimedwait(&blocked, &info, &timeout) == -1 && errno == EINTR && delivered == before + 1);
    int status; CHECK(waitpid(child, &status, 0) == child && WIFEXITED(status) && WEXITSTATUS(status) == 0);
    CHECK(raise(SIGUSR2) == 0 && sigtimedwait(&blocked, &info, &zero) == SIGUSR2 && info.si_signo == SIGUSR2);
    CHECK(sigtimedwait(&blocked, &info, &zero) == -1 && errno == EAGAIN);
    CHECK(sigprocmask(SIG_SETMASK, &empty, NULL) == 0);
    puts("PASS wait errors/timeouts and sigtimedwait selection/interruption");
    return 0;
}
