/* SPDX-License-Identifier: Apache-2.0 */
/* ARM64 host regression using real kernel signal dispositions, without
 * installing the SIGSYS transport. No test handler is actually invoked. */
#define _GNU_SOURCE
#include <assert.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <sys/wait.h>
#include <time.h>
#include "signals.c"

struct dl_state dl_state;
char dl_host_return[8];
static unsigned queries, updates;
static int created_timer;
#ifdef DIRECT_LINUX_THREADS_H
static struct dl_thread thread;
static unsigned files_lock;
struct dl_thread *dl_thread_self(void) { return &thread; }
int dl_thread_vfork(void) { return 0; }
int dl_internal_fd(int fd) { (void)fd; return 0; }
unsigned *dl_files_lock_current(void) { return &files_lock; }
void dl_lock(unsigned *lock) { assert(!*lock); *lock = 1; }
void dl_unlock(unsigned *lock) { assert(*lock); *lock = 0; }
#endif
void dl_copy(void *d, const void *s, size_t n) { memcpy(d, s, n); }
int dl_equal_memory(const void *a, const void *b, size_t n) { return !memcmp(a, b, n); }
void dl_io_collect(uintptr_t stack) { (void)stack; }
_Noreturn void dl_resume(void *frame) { (void)frame; assert(0); __builtin_unreachable(); }
long dl_read_memory(void *d, const void *s, size_t n) { memcpy(d, s, n); return 0; }
long dl_write_memory(void *d, const void *s, size_t n)
{
    if ((uintptr_t)d == 1) return -EFAULT;
    memcpy(d, s, n); return 0;
}
long dl_host_syscall(long n, long a, long b, long c, long d, long e, long f)
{
    if (n == SYS_rt_sigaction) { if (b) updates++; else queries++; }
    long r = syscall(n, a, b, c, d, e, f);
    if (n == SYS_timer_create && r == 0) created_timer = *(int *)c;
    return r < 0 ? -errno : r;
}
static void unused_handler(int signal) { (void)signal; assert(0); }
static struct action query(int signal)
{
    struct action result;
    assert(!syscall(SYS_rt_sigaction, signal, NULL, &result, 8)); return result;
}
static void kernel_action(int signal, uintptr_t handler)
{
    struct action action = {.handler = handler};
    assert(!syscall(SYS_rt_sigaction, signal, &action, NULL, 8));
}
static long guest_action(int signal, uintptr_t handler, uintptr_t output)
{
    struct action action = {.handler = handler, .mask = 1UL << (SIGSYS - 1)};
    unsigned long a[6] = {signal, (uintptr_t)&action, output, 8, 0, 0};
    return dl_signal_action(a);
}
int main(void)
{
    thread.sighand = dl_sighand_initial();
    thread.process = dl_process_initial();
    kernel_action(SIGUSR1, (uintptr_t)unused_handler);
    kernel_action(SIGUSR2, (uintptr_t)SIG_IGN);
    kernel_action(SIGSYS, (uintptr_t)unused_handler);
    queries = updates = 0;
    assert(!dl_signal_reset_exec() && queries == 61 && updates >= 1);
    assert(query(SIGUSR1).handler == (uintptr_t)SIG_DFL);
    assert(query(SIGUSR2).handler == (uintptr_t)SIG_IGN);
    assert(query(SIGSYS).handler == (uintptr_t)unused_handler);
    queries = updates = 0;
    assert(!dl_signal_reset_exec() && !queries && !updates);
    // A post-scan handler, including one inherited through fork, must reset.
    assert(!guest_action(SIGUSR1, (uintptr_t)unused_handler, 0));
    queries = updates = 0;
    assert(!dl_signal_reset_exec() && queries == 1 && updates == 1);
    assert(query(SIGUSR1).handler == (uintptr_t)SIG_DFL);
    struct action old;
    unsigned long args[6] = {SIGUSR1, 0, (uintptr_t)&old, 8, 0, 0};
    assert(!dl_signal_action(args) && !old.handler && !old.mask && !old.flags);
    assert(!guest_action(SIGUSR1, (uintptr_t)unused_handler, 0));
    kernel_action(SIGCHLD, (uintptr_t)SIG_DFL);
    pid_t child = fork(); assert(child >= 0);
    if (!child) {
        queries = updates = 0;
        assert(!dl_signal_reset_exec() && queries == 1 && updates == 1);
        assert(query(SIGUSR1).handler == (uintptr_t)SIG_DFL); _exit(0);
    }
    int status;
    assert(waitpid(child, &status, 0) == child && WIFEXITED(status) && !WEXITSTATUS(status));
    assert(query(SIGUSR1).handler == (uintptr_t)guest_signal);
    assert(!dl_signal_reset_exec());
    // Installing IGN must survive exec even if a previous handler was tracked.
    assert(!guest_action(SIGUSR1, (uintptr_t)unused_handler, 0));
    assert(!guest_action(SIGUSR1, (uintptr_t)SIG_IGN, 0));
    queries = updates = 0;
    assert(!dl_signal_reset_exec() && queries == 1 && !updates);
    assert(query(SIGUSR1).handler == (uintptr_t)SIG_IGN);
    // Model a one-shot handler already reset by the kernel before exec.
    assert(!guest_action(SIGUSR1, (uintptr_t)unused_handler, 0));
    kernel_action(SIGUSR1, (uintptr_t)SIG_DFL);
    queries = updates = 0;
    assert(!dl_signal_reset_exec() && queries == 1 && !updates);
    // Kernel installation succeeds even when returning the old action faults.
    assert(guest_action(SIGUSR1, (uintptr_t)unused_handler, 1) == -EFAULT);
    queries = updates = 0;
    assert(!dl_signal_reset_exec() && queries == 1 && updates == 1);
    assert(guest_action(SIGKILL, (uintptr_t)unused_handler, 0) == -EINVAL);
    queries = updates = 0;
    assert(!dl_signal_reset_exec() && !queries && !updates);
    puts("PASS inherited/changed/ignored/one-shot/error signal reset and bounded kernel queries");
    struct sigevent event = {.sigev_notify = SIGEV_NONE};
    struct itimerspec remaining;
    unsigned long timer_args[6] = {CLOCK_MONOTONIC, (uintptr_t)&event, 1, 0, 0, 0};
    for (unsigned i = 0; i < 64; i++) {
        assert(dl_signal_event(SYS_timer_create, timer_args) == -EFAULT);
        assert(!thread.process->timers);
        assert(syscall(SYS_timer_gettime, created_timer, &remaining) == -1 && errno == EINVAL);
    }
    int timer;
    timer_args[2] = (uintptr_t)&timer;
    assert(!dl_signal_event(SYS_timer_create, timer_args));
    assert(thread.process->timers && thread.process->timers->id == timer);
    child = fork(); assert(child >= 0);
    if (!child) {
        assert(!dl_signal_reset_exec() && !thread.process->timers);
        _exit(0);
    }
    assert(waitpid(child, &status, 0) == child && WIFEXITED(status) && !WEXITSTATUS(status));
    assert(syscall(SYS_timer_gettime, timer, &remaining) == 0);
    assert(!dl_signal_reset_exec() && !thread.process->timers);
    assert(syscall(SYS_timer_gettime, timer, &remaining) == -1 && errno == EINVAL);
    struct dl_process *separate; assert(!dl_process_new(&separate));
    assert(!separate->timers && !separate->timer_owner);
    dl_process_retain(separate); dl_process_release(separate); dl_process_release(separate);
    dl_process_release(NULL);
    puts("PASS timer create-fault rollback, fork owner isolation, exec deletion and process references");
    return 0;
}
