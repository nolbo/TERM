/* SPDX-License-Identifier: Apache-2.0 */
/* Real CLONE_VM processes exercise the runtime's sighand component. This is
 * not an end-to-end guest clone/spawn test; the SIGSYS dispatcher is absent. */
#define _GNU_SOURCE
#include <assert.h>
#include <linux/futex.h>
#include <sched.h>
#include <stdio.h>
#include <string.h>
#include <sys/resource.h>
#include <sys/wait.h>
#include <unistd.h>
#include "signals.c"

struct dl_state dl_state;
static pid_t owner;
static struct dl_thread parent_thread, child_thread;
static unsigned completed;
static int child_error;
struct dl_thread *dl_thread_self(void)
{
    return DL_CALL(SYS_getpid, 0, 0, 0, 0, 0, 0) == owner ? &parent_thread : &child_thread;
}
void dl_copy(void *d, const void *s, size_t n) { memcpy(d, s, n); }
int dl_equal_memory(const void *a, const void *b, size_t n) { return !memcmp(a, b, n); }
long dl_read_memory(void *d, const void *s, size_t n) { memcpy(d, s, n); return 0; }
long dl_write_memory(void *d, const void *s, size_t n) { memcpy(d, s, n); return 0; }
void dl_io_collect(uintptr_t stack) { (void)stack; }
_Noreturn void dl_resume(void *frame) { (void)frame; __builtin_trap(); }
void dl_lock(unsigned *lock)
{
    unsigned expected = 0;
    if (__atomic_compare_exchange_n(lock, &expected, 1, 0, __ATOMIC_ACQUIRE, __ATOMIC_RELAXED)) return;
    while (__atomic_exchange_n(lock, 2, __ATOMIC_ACQUIRE))
        DL_CALL(SYS_futex, lock, FUTEX_WAIT_PRIVATE, 2, NULL, NULL, 0);
}
void dl_unlock(unsigned *lock)
{
    if (__atomic_exchange_n(lock, 0, __ATOMIC_RELEASE) == 2)
        DL_CALL(SYS_futex, lock, FUTEX_WAKE_PRIVATE, 1, NULL, NULL, 0);
}
static void parent_handler(int signal) { (void)signal; __builtin_trap(); }
static void child_handler(int signal) { (void)signal; __builtin_trap(); }
static long action(int signal, struct action *input, struct action *output)
{
    unsigned long args[] = {signal, (uintptr_t)input, (uintptr_t)output, 8, 0, 0};
    return dl_signal_action(args);
}
static struct action parent_action(void)
{
    return (struct action){.handler = (uintptr_t)parent_handler, .flags = SA_RESTART,
        .mask = SYS_BIT | (1UL << (SIGUSR2 - 1))};
}
static struct action child_action(void)
{
    return (struct action){.handler = (uintptr_t)child_handler, .flags = SA_RESETHAND,
        .mask = SYS_BIT | (1UL << (SIGTERM - 1))};
}
static int matches(const struct action *a, const struct action *b)
{
    return a->handler == b->handler && a->flags == b->flags && a->mask == b->mask;
}
static void seed(void)
{
    struct action requested = parent_action();
    assert(!action(SIGUSR1, &requested, NULL));
    requested = (struct action){.handler = (uintptr_t)SIG_IGN, .mask = SYS_BIT};
    assert(!action(SIGUSR2, &requested, NULL));
}

#define CHILD_CHECK(condition) do { if (!(condition)) { child_error = __LINE__; return 90; } } while (0)
static int child(void *opaque)
{
    int shared = (int)(uintptr_t)opaque;
    struct action observed, expected = parent_action();
    CHILD_CHECK(!action(SIGUSR1, NULL, &observed) && matches(&observed, &expected));
    expected = child_action();
    CHILD_CHECK(!action(SIGUSR1, &expected, NULL));
    CHILD_CHECK(!action(SIGUSR1, NULL, &observed) && matches(&observed, &expected));
    if (!shared) {
        CHILD_CHECK(!dl_signal_reset_exec());
        CHILD_CHECK(!action(SIGUSR1, NULL, &observed) && !observed.handler && !observed.mask);
        CHILD_CHECK(!action(SIGUSR2, NULL, &observed) && observed.handler == (uintptr_t)SIG_IGN && observed.mask == SYS_BIT);
    }
    // Parent observes this write only when the actual kernel clone shares VM.
    __atomic_add_fetch(&completed, 1, __ATOMIC_RELEASE);
    return 0;
}

static void one_clone(void *stack, int flags)
{
    int shared = !!(flags & CLONE_SIGHAND);
    seed(); child_error = 0;
    unsigned before = __atomic_load_n(&completed, __ATOMIC_ACQUIRE);
    if (shared) {
        child_thread.sighand = parent_thread.sighand;
        dl_sighand_retain(child_thread.sighand);
    } else {
        // Keep the logical snapshot and kernel sighand copy in one transaction.
        // For VFORK the child uses its unlocked private table while the caller
        // remains blocked in clone. The host adapter has no guest thread table.
        dl_lock(dl_sighand_lock(parent_thread.sighand));
        assert(!dl_sighand_copy(parent_thread.sighand, &child_thread.sighand));
    }
    pid_t pid = clone(child, stack, flags | SIGCHLD, (void *)(uintptr_t)shared);
    if (!shared) dl_unlock(dl_sighand_lock(parent_thread.sighand));
    assert(pid >= 0);
    int status; assert(waitpid(pid, &status, 0) == pid);
    if (child_error) fprintf(stderr, "child failed at line %d\n", child_error);
    assert(WIFEXITED(status) && WEXITSTATUS(status) == 0 && !child_error);
    assert(__atomic_load_n(&completed, __ATOMIC_ACQUIRE) == before + 1);
    struct action observed, expected = shared ? child_action() : parent_action();
    assert(!action(SIGUSR1, NULL, &observed) && matches(&observed, &expected));
    // A private child's reset must not clear the parent's exec candidate set.
    assert(!dl_signal_reset_exec());
    assert(!action(SIGUSR1, NULL, &observed) && !observed.handler && !observed.mask);
    void *mapping = child_thread.sighand;
    dl_sighand_release(child_thread.sighand); child_thread.sighand = NULL;
    if (!shared) {
        unsigned char residency;
        assert(DL_CALL(SYS_mincore, mapping, dl_state.page, &residency, 0, 0, 0) == -ENOMEM);
    }
}

int main(void)
{
#ifdef __ANDROID__
    assert(geteuid() >= 10000);
#endif
    FILE *policy = fopen("/proc/self/status", "r"); assert(policy);
    char line[256];
    while (fgets(line, sizeof(line), policy))
        if (!strncmp(line, "TracerPid:", 10) || !strncmp(line, "CapEff:", 7) ||
                !strncmp(line, "Seccomp:", 8) || !strncmp(line, "NoNewPrivs:", 11)) printf("POLICY %s", line);
    assert(!fclose(policy));
    owner = getpid(); dl_state.page = (size_t)sysconf(_SC_PAGESIZE);
    parent_thread.sighand = dl_sighand_initial();
    void *allocation = mmap(NULL, 128 * 1024, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    assert(allocation != MAP_FAILED);
    void *stack = (char *)allocation + 128 * 1024;
    for (unsigned i = 0; i < 64; i++) {
        one_clone(stack, CLONE_VM);
        one_clone(stack, CLONE_VM | CLONE_VFORK);
        one_clone(stack, CLONE_VM | CLONE_SIGHAND);
    }
    assert(completed == 192);
    // Allocation failure cannot publish a table or mutate the source.
    seed(); struct rlimit previous;
    assert(!getrlimit(RLIMIT_AS, &previous));
    struct rlimit bounded = previous; bounded.rlim_cur = 0;
    dl_lock(dl_sighand_lock(parent_thread.sighand));
    assert(!setrlimit(RLIMIT_AS, &bounded));
    long result = dl_sighand_copy(parent_thread.sighand, &child_thread.sighand);
    assert(!setrlimit(RLIMIT_AS, &previous));
    dl_unlock(dl_sighand_lock(parent_thread.sighand));
    assert(result == -ENOMEM && !child_thread.sighand);
    // A failed kernel clone leaves no live user of the prepared private table.
    dl_lock(dl_sighand_lock(parent_thread.sighand));
    assert(!dl_sighand_copy(parent_thread.sighand, &child_thread.sighand));
    errno = 0; pid_t failed = clone(child, stack, CLONE_SIGHAND | SIGCHLD, NULL);
    int error = errno;
    dl_unlock(dl_sighand_lock(parent_thread.sighand));
    assert(failed == -1 && error == EINVAL);
    void *mapping = child_thread.sighand;
    dl_sighand_release(child_thread.sighand); child_thread.sighand = NULL;
    unsigned char residency;
    assert(DL_CALL(SYS_mincore, mapping, dl_state.page, &residency, 0, 0, 0) == -ENOMEM);
    struct action observed, expected = parent_action();
    assert(!action(SIGUSR1, NULL, &observed) && matches(&observed, &expected));
    assert(!munmap(allocation, 128 * 1024));
    puts("PASS 192 real shared-VM clones: independent/shared sighand, vfork, masks/flags, exec-reset isolation and reclamation");
    puts("PASS allocation and clone failure preserve parent dispositions and reclaim private tables");
    return 0;
}
