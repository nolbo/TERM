/* SPDX-License-Identifier: Apache-2.0 */
/* Guest signal masks must not disable the private SIGSYS syscall transport.
 * This state is per-process while shared-VM/thread clone remains unsupported. */
#define _GNU_SOURCE
#include "runtime.h"
#include <errno.h>
#include <signal.h>
#include <sys/syscall.h>
#include <ucontext.h>

#define SYS_BIT (1UL << (SIGSYS - 1))
#define UNBLOCKABLE ((1UL << (SIGKILL - 1)) | (1UL << (SIGSTOP - 1)))
#define WAIT_LIMIT 64
struct action { uintptr_t handler, flags, restorer; unsigned long mask; };
static struct action actions[65];
static unsigned char wrapped[65];
// Unknown inherited Bionic dispositions are scanned on the first replacement.
// Subsequent replacements only inspect signals which may have a handler.
// Guest action updates are serialized with exec (and with the action lock in
// the thread implementation). A one-shot reset may leave a harmless extra bit.
static unsigned long exec_candidates = ~0UL;

long dl_signal_reset_exec(void)
{
    for (int signal = 1; signal <= 64; signal++) {
        unsigned long bit = 1UL << (signal - 1);
        if (!(exec_candidates & bit) || signal == SIGKILL || signal == SIGSTOP || signal == SIGSYS) continue;
        struct action action;
        long r = DL_CALL(SYS_rt_sigaction, signal, 0, &action, 8, 0, 0); if (r) return r;
        if (action.handler > 1) {
            action = (struct action){0};
            r = DL_CALL(SYS_rt_sigaction, signal, &action, 0, 8, 0, 0); if (r) return r;
            actions[signal] = action; wrapped[signal] = 0;
        }
    }
    exec_candidates = 0;
    return 0;
}
struct waiting {
    ucontext_t *guest;
    uintptr_t stack;
    unsigned long mask, return_bit;
    int suspended;
};
static struct waiting waits[WAIT_LIMIT];
static unsigned wait_count;
static uintptr_t runtime_begin, runtime_end;

void dl_signal_stack(void *stack, size_t size)
{
    runtime_begin = (uintptr_t)stack; runtime_end = runtime_begin + size;
}

void dl_signal_collect(uintptr_t stack)
{
    // siglongjmp may abandon any number of blocked calls. Records live in
    // static storage, not abandoned signal frames, so collecting cannot read
    // an overwritten frame. AArch64 stacks grow down and have no red zone.
    if (stack < runtime_begin || stack >= runtime_end) { wait_count = 0; return; }
    while (wait_count && stack > waits[wait_count - 1].stack) wait_count--;
}

static void guest_signal(int signal, siginfo_t *info, void *pointer)
{
    ucontext_t *context = pointer;
    struct action action = actions[signal];
    if (action.flags & SA_RESETHAND) actions[signal].handler = (uintptr_t)SIG_DFL;
    unsigned long kernel_mask, restore_mask, delivery_mask;
    dl_copy(&kernel_mask, &context->uc_sigmask, 8);
    dl_signal_collect(context->uc_mcontext.sp);
    uintptr_t pc = context->uc_mcontext.pc;
    struct waiting *wait = wait_count && !waits[wait_count - 1].suspended &&
        (pc == (uintptr_t)dl_host_return || pc == (uintptr_t)dl_host_return - 4)
        ? &waits[wait_count - 1] : NULL;
    if (wait) {
        dl_copy(&restore_mask, &wait->guest->uc_sigmask, 8);
        restore_mask = (restore_mask & ~SYS_BIT) | wait->return_bit;
        delivery_mask = wait->mask;
    } else {
        restore_mask = (kernel_mask & ~SYS_BIT) | dl_state.virtual_sigsys_mask;
        delivery_mask = restore_mask;
    }
    delivery_mask |= action.mask;
    if (!(action.flags & SA_NODEFER)) delivery_mask |= 1UL << (signal - 1);
    delivery_mask &= ~UNBLOCKABLE;
    dl_state.virtual_sigsys_mask = delivery_mask & SYS_BIT;
    unsigned long physical = delivery_mask & ~SYS_BIT;
    dl_copy(&context->uc_sigmask, &restore_mask, 8);
    // A handler can enter a different translated blocking call at the same
    // raw syscall site. That call must not inherit this suspended wait's mask.
    if (wait) wait->suspended = 1;
    // No locks, allocated state or temporary FDs survive this boundary. The
    // guest may recursively trap, wait, replace its action, exec or longjmp.
    DL_CALL(SYS_rt_sigprocmask, SIG_SETMASK, &physical, NULL, 8, 0, 0);
    if (action.flags & SA_SIGINFO)
        ((void (*)(int, siginfo_t *, void *))action.handler)(signal, info, context);
    else ((void (*)(int))action.handler)(signal);
    unsigned long all = ~0UL;
    DL_CALL(SYS_rt_sigprocmask, SIG_SETMASK, &all, NULL, 8, 0, 0);
    dl_copy(&restore_mask, &context->uc_sigmask, 8);
    dl_state.virtual_sigsys_mask = restore_mask & SYS_BIT;
    restore_mask &= ~(SYS_BIT | UNBLOCKABLE);
    if (wait) {
        wait->suspended = 0;
        wait->return_bit = dl_state.virtual_sigsys_mask;
        dl_copy(&wait->guest->uc_sigmask, &restore_mask, 8);
        // The enclosing runtime transition must resume with its own mask.
        dl_copy(&context->uc_sigmask, &kernel_mask, 8);
    } else dl_copy(&context->uc_sigmask, &restore_mask, 8);
}

long dl_signal_action(unsigned long a[6])
{
    if (a[3] != 8 || a[0] < 1 || a[0] > 64) return -EINVAL;
    if (a[0] == SIGSYS) return -EOPNOTSUPP;
    struct action requested, installed, previous;
    if (a[1]) {
        long r = dl_read_memory(&requested, (void *)a[1], sizeof(requested)); if (r) return r;
        installed = requested;
        if (requested.handler > 1) {
            installed.handler = (uintptr_t)guest_signal;
            installed.flags |= SA_SIGINFO;
            installed.mask = ~0UL;
        } else installed.mask &= ~SYS_BIT;
    }
    long r = DL_CALL(SYS_rt_sigaction, a[0], a[1] ? &installed : NULL, &previous, 8, 0, 0);
    if (r) return r;
    if (previous.handler == (uintptr_t)guest_signal ||
            (wrapped[a[0]] && previous.handler <= 1 && previous.handler == actions[a[0]].handler)) {
        struct action saved = actions[a[0]];
        previous.handler = saved.handler; previous.mask = saved.mask & ~UNBLOCKABLE;
        previous.flags = (previous.flags & ~SA_SIGINFO) | (saved.flags & SA_SIGINFO);
    } else if (previous.handler <= 1 && previous.handler == actions[a[0]].handler)
        previous.mask |= actions[a[0]].mask & SYS_BIT;
    if (a[1]) {
        actions[a[0]] = requested; wrapped[a[0]] = requested.handler > 1;
        if (requested.handler > 1) exec_candidates |= 1UL << (a[0] - 1);
    }
    return a[2] ? dl_write_memory((void *)a[2], &previous, sizeof(previous)) : 0;
}

long dl_signal_wait(long nr, unsigned long a[6], void *pointer)
{
    ucontext_t *context = pointer;
    unsigned long mask, set = 0, address = 0, size = 8;
    struct { unsigned long address, size; } packed = {0, 0};
    dl_copy(&mask, &context->uc_sigmask, 8);
    unsigned long original_bit = dl_state.virtual_sigsys_mask;
    mask = (mask & ~SYS_BIT) | original_bit;
    if (nr == SYS_pselect6) {
        if (a[5]) { long r = dl_read_memory(&packed, (void *)a[5], sizeof(packed)); if (r) return r; }
        address = packed.address; size = packed.size;
    } else if (nr == SYS_ppoll) { address = a[3]; size = a[4]; }
    else if (nr == SYS_epoll_pwait || nr == SYS_epoll_pwait2) { address = a[4]; size = a[5]; }
    else if (nr == SYS_rt_sigsuspend) {
        if (a[1] != 8) return -EINVAL;
        address = a[0];
    }
    else if (nr == SYS_rt_sigtimedwait) {
        if (a[3] != 8) return -EINVAL;
        long r = dl_read_memory(&set, (void *)a[0], 8); if (r) return r;
        set &= ~SYS_BIT;
    }
    if (address) {
        if (size != 8) return -EINVAL;
        long r = dl_read_memory(&mask, (void *)address, 8); if (r) return r;
    } else if (nr == SYS_rt_sigsuspend) return -EFAULT;
    mask &= ~UNBLOCKABLE;
    if (wait_count == WAIT_LIMIT) return -EAGAIN;
    uintptr_t stack; __asm__ volatile("mov %0, sp" : "=r"(stack));
    unsigned index = wait_count++;
    waits[index] = (struct waiting){context, stack, mask, original_bit, 0};
    dl_state.virtual_sigsys_mask = mask & SYS_BIT;
    unsigned long physical = mask & ~SYS_BIT;
    long result;
    if (nr == SYS_ppoll) result = DL_CALL(nr, a[0], a[1], a[2], &physical, 8, 0);
    else if (nr == SYS_pselect6) {
        packed.address = (uintptr_t)&physical; packed.size = 8;
        result = DL_CALL(nr, a[0], a[1], a[2], a[3], a[4], &packed);
    } else if (nr == SYS_epoll_pwait || nr == SYS_epoll_pwait2)
        result = DL_CALL(nr, a[0], a[1], a[2], a[3], &physical, 8);
    else if (nr == SYS_rt_sigsuspend) result = DL_CALL(nr, &physical, 8, 0, 0, 0, 0);
    else {
        // sigtimedwait takes a selection set, not a temporary mask. Restore
        // the actual guest mask so unrelated unblocked handlers can interrupt.
        DL_CALL(SYS_rt_sigprocmask, SIG_SETMASK, &physical, NULL, 8, 0, 0);
        result = DL_CALL(nr, &set, a[1], a[2], 8, 0, 0);
        unsigned long all = ~0UL;
        DL_CALL(SYS_rt_sigprocmask, SIG_SETMASK, &all, NULL, 8, 0, 0);
    }
    dl_state.virtual_sigsys_mask = waits[index].return_bit;
    wait_count = index;
    return result;
}
