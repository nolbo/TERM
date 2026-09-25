/* SPDX-License-Identifier: Apache-2.0 */
/* Shared dispositions and per-thread masks/waits for the SIGSYS transport. */
#define _GNU_SOURCE
#include "threads.h"
#include <errno.h>
#include <signal.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <ucontext.h>

#define SYS_BIT (1UL << (SIGSYS - 1))
#define UNBLOCKABLE ((1UL << (SIGKILL - 1)) | (1UL << (SIGSTOP - 1)))
struct action { uintptr_t handler, flags, restorer; unsigned long mask; };
// Kernel CLONE_VM does not imply CLONE_SIGHAND. Keep the emulated handler,
// transport mask bits and exec-reset candidates in the same sharing domain as
// the kernel's dispositions, independently of the shared address space.
struct dl_sighand {
    unsigned lock, references;
    struct action actions[65];
    unsigned char wrapped[65];
    unsigned long exec_candidates;
};
// Inspect inherited kernel dispositions once; later exec scans only candidates
// added by guest handler updates. A one-shot reset may leave an extra bit.
static struct dl_sighand initial_sighand = {.references = 1, .exec_candidates = ~0UL};
struct dl_sighand *dl_sighand_initial(void) { return &initial_sighand; }
unsigned *dl_sighand_lock(struct dl_sighand *table) { return &table->lock; }
void dl_sighand_retain(struct dl_sighand *table)
{
    __atomic_add_fetch(&table->references, 1, __ATOMIC_RELAXED);
}
void dl_sighand_release(struct dl_sighand *table)
{
    if (!__atomic_sub_fetch(&table->references, 1, __ATOMIC_ACQ_REL) && table != &initial_sighand)
        DL_CALL(SYS_munmap, table, sizeof(*table), 0, 0, 0, 0);
}
long dl_sighand_copy(struct dl_sighand *source, struct dl_sighand **result)
{
    long mapping = DL_CALL(SYS_mmap, 0, sizeof(struct dl_sighand), PROT_READ | PROT_WRITE,
        MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (mapping < 0) return mapping;
    struct dl_sighand *copy = (void *)mapping;
    copy->references = 1;
    dl_copy(copy->actions, source->actions, sizeof(copy->actions));
    dl_copy(copy->wrapped, source->wrapped, sizeof(copy->wrapped));
    copy->exec_candidates = source->exec_candidates;
    *result = copy; return 0;
}

long dl_signal_reset_exec(void)
{
    struct dl_sighand *table = dl_thread_self()->sighand;
    dl_lock(&table->lock);
    long r = 0;
    for (int signal = 1; signal <= 64; signal++) {
        unsigned long bit = 1UL << (signal - 1);
        if (!(table->exec_candidates & bit) || signal == SIGKILL || signal == SIGSTOP || signal == SIGSYS) continue;
        struct action action;
        r = DL_CALL(SYS_rt_sigaction, signal, 0, &action, 8, 0, 0); if (r) break;
        if (action.handler > 1) {
            action = (struct action){0};
            r = DL_CALL(SYS_rt_sigaction, signal, &action, 0, 8, 0, 0); if (r) break;
            table->actions[signal] = action; table->wrapped[signal] = 0;
        }
    }
    if (!r) table->exec_candidates = 0;
    dl_unlock(&table->lock); return r;
}
struct frame { siginfo_t info; ucontext_t context; };

static int runtime_stack(struct dl_thread *thread, uintptr_t stack)
{
    return stack >= thread->stack_begin && stack < thread->stack_end;
}
void dl_signal_collect(uintptr_t stack)
{
    struct dl_thread *thread = dl_thread_self();
    struct dl_signal_state *state = &thread->signals;
    // Records are outside abandoned frames. AArch64 stacks grow down and
    // have no red zone. A guest longjmp can discard several nested waits.
    dl_io_collect(stack);
    int inside = runtime_stack(thread, stack);
    while (state->wait_count && (!inside || stack > state->waits[state->wait_count - 1].stack))
        state->wait_count--;
    while (state->delivery_count) {
        uintptr_t frame = state->deliveries[state->delivery_count - 1].frame;
        int frame_inside = runtime_stack(thread, frame);
        if (frame_inside ? (inside && stack <= frame) : (inside || stack <= frame)) break;
        state->delivery_count--;
    }
}

static _Noreturn void signal_failure(void)
{
    DL_CALL(SYS_exit_group, 126, 0, 0, 0, 0, 0); __builtin_unreachable();
}

static void guest_signal(int signal, siginfo_t *info, void *pointer)
{
    ucontext_t *context = pointer;
    struct dl_thread *thread = dl_thread_self();
    struct dl_signal_state *state = &thread->signals;
    struct dl_sighand *table = thread->sighand;
    dl_lock(&table->lock);
    struct action action = table->actions[signal];
    dl_unlock(&table->lock);
    if (action.handler <= 1) {
        if (action.handler == (uintptr_t)SIG_DFL)
            DL_CALL(SYS_tgkill, DL_CALL(SYS_getpid, 0, 0, 0, 0, 0, 0),
                DL_CALL(SYS_gettid, 0, 0, 0, 0, 0, 0), signal, 0, 0, 0);
        return;
    }
    unsigned long kernel_mask, restore_mask, delivery_mask;
    dl_copy(&kernel_mask, &context->uc_sigmask, 8);
    dl_signal_collect(context->uc_mcontext.sp);
    struct dl_waiting *wait = state->wait_count && !state->waits[state->wait_count - 1].suspended
        ? &state->waits[state->wait_count - 1] : NULL;
    if (state->delivery_count == DL_WAIT_LIMIT) signal_failure();
    struct dl_delivery *delivery = &state->deliveries[state->delivery_count++];
    delivery->frame = (uintptr_t)context - offsetof(struct frame, context);
    delivery->wait = wait ? (int)state->wait_count - 1 : -1;
    delivery->host_mask = kernel_mask;
    dl_copy(delivery->host_gp, &context->uc_mcontext, DL_GP_SIZE);
    if (wait) {
        dl_copy(&restore_mask, &wait->guest->uc_sigmask, 8);
        restore_mask = (restore_mask & ~SYS_BIT) | wait->return_bit;
        delivery_mask = wait->mask;
        // The host continuation may be anywhere between unmask and remask,
        // including the syscall that unblocks an already pending signal.
        // Expose the guest call site for unwind/cancellation and SA_SIGINFO.
        uintptr_t pc = context->uc_mcontext.pc;
        long result = -EINTR;
        int in_call = context->uc_mcontext.regs[8] == (unsigned long)wait->syscall &&
            (pc == (uintptr_t)dl_host_return || pc == (uintptr_t)dl_host_return - 4);
        if (in_call) result = (long)context->uc_mcontext.regs[0];
        dl_copy(&context->uc_mcontext, &wait->guest->uc_mcontext, DL_GP_SIZE);
        if (in_call && pc == (uintptr_t)dl_host_return - 4) context->uc_mcontext.pc -= 4;
        else context->uc_mcontext.regs[0] = result;
        wait->suspended = 1;
    } else {
        restore_mask = (kernel_mask & ~SYS_BIT) | state->virtual_sigsys_mask;
        delivery_mask = restore_mask;
    }
    dl_copy(delivery->exposed_gp, &context->uc_mcontext, DL_GP_SIZE);
    dl_copy(&context->uc_sigmask, &restore_mask, 8);
    delivery_mask |= action.mask;
    if (!(action.flags & SA_NODEFER)) delivery_mask |= 1UL << (signal - 1);
    delivery_mask &= ~UNBLOCKABLE;
    state->virtual_sigsys_mask = delivery_mask & SYS_BIT;
    delivery_mask &= ~SYS_BIT;

    // Enter the guest directly from a kernel signal frame. A C call here
    // would hide its original frame behind runtime frames with no guest
    // unwind information, breaking pthread cancellation through a wrapper.
    // rt_sigreturn also changes the mask and PC atomically.
    size_t size = sizeof(struct frame), extra_offset = 0;
    uintptr_t extra_pointer = 0;
    for (size_t offset = 0; offset + sizeof(struct _aarch64_ctx) <= sizeof(context->uc_mcontext.__reserved);) {
        struct _aarch64_ctx *head = (void *)(context->uc_mcontext.__reserved + offset);
        if (!head->magic && !head->size) break;
        if (head->size < sizeof(*head) || head->size % 16 || head->size > sizeof(context->uc_mcontext.__reserved) - offset)
            signal_failure();
        if (head->magic == EXTRA_MAGIC) {
            struct extra_context *extra = (void *)head;
            if (extra_offset || head->size != sizeof(*extra) || extra->datap < delivery->frame ||
                    extra->datap - delivery->frame > DL_SIGNAL_STACK_SIZE / 2 ||
                    extra->size > DL_SIGNAL_STACK_SIZE / 2 - (extra->datap - delivery->frame)) signal_failure();
            extra_offset = (uintptr_t)extra - delivery->frame; extra_pointer = extra->datap;
            if (extra->datap + extra->size - delivery->frame > size) size = extra->datap + extra->size - delivery->frame;
        }
        offset += head->size;
    }
    void *storage = __builtin_alloca(size + 15);
    struct frame *activation = (void *)(((uintptr_t)storage + 15) & ~15UL);
    dl_copy(activation, (void *)delivery->frame, size);
    if (extra_offset) ((struct extra_context *)((char *)activation + extra_offset))->datap =
        (uintptr_t)activation + extra_pointer - delivery->frame;
    activation->context.uc_mcontext.pc = action.handler;
    activation->context.uc_mcontext.sp = delivery->frame;
    activation->context.uc_mcontext.regs[0] = signal;
    activation->context.uc_mcontext.regs[1] = (uintptr_t)info;
    activation->context.uc_mcontext.regs[2] = (uintptr_t)context;
    activation->context.uc_mcontext.regs[30] = (uintptr_t)__builtin_return_address(0);
    dl_copy(&activation->context.uc_sigmask, &delivery_mask, 8);
    dl_resume(activation);
}

static void copy_fpsimd(ucontext_t *destination, const ucontext_t *source)
{
    // The runtime uses only GP registers. Keep explicit handler FPSIMD edits
    // when an outer SIGSYS frame subsequently restores the guest state.
    struct fpsimd_context *to = (void *)destination->uc_mcontext.__reserved;
    const struct fpsimd_context *from = (const void *)source->uc_mcontext.__reserved;
    if (to->head.magic == FPSIMD_MAGIC && from->head.magic == FPSIMD_MAGIC &&
            to->head.size == sizeof(*to) && from->head.size == sizeof(*from)) dl_copy(to, from, sizeof(*to));
}

_Noreturn void dl_signal_return(void *pointer)
{
    ucontext_t *trap = pointer;
    struct dl_signal_state *state = &dl_thread_self()->signals;
    struct frame *frame = (void *)trap->uc_mcontext.sp;
    struct dl_delivery *delivery = state->delivery_count ? &state->deliveries[state->delivery_count - 1] : NULL;
    if (!delivery || delivery->frame != (uintptr_t)frame) {
        // A manually constructed frame still receives the kernel's normal
        // validation; only the virtual transport bit needs interception.
        unsigned long mask;
        if (!dl_read_memory(&mask, &frame->context.uc_sigmask, 8)) {
            state->virtual_sigsys_mask = mask & SYS_BIT; mask &= ~SYS_BIT;
            dl_write_memory(&frame->context.uc_sigmask, &mask, 8);
        }
        dl_resume(frame);
    }
    unsigned long mask;
    dl_copy(&mask, &frame->context.uc_sigmask, 8);
    state->virtual_sigsys_mask = mask & SYS_BIT;
    mask &= ~(SYS_BIT | UNBLOCKABLE);
    state->delivery_count--;
    if (delivery->wait >= 0) {
        struct dl_waiting *wait = &state->waits[delivery->wait];
        wait->suspended = 0; wait->return_bit = state->virtual_sigsys_mask;
        dl_copy(&wait->guest->uc_sigmask, &mask, 8);
        copy_fpsimd(wait->guest, &frame->context);
        if (!dl_equal_memory(&frame->context.uc_mcontext, delivery->exposed_gp, DL_GP_SIZE)) {
            // setcontext-like edits explicitly leave the emulated syscall.
            // Resume its original guest frame without completing stale I/O.
            ucontext_t *guest = wait->guest;
            dl_copy(&guest->uc_mcontext, &frame->context.uc_mcontext, DL_GP_SIZE);
            dl_signal_collect(guest->uc_mcontext.sp);
            dl_resume((char *)guest - offsetof(struct frame, context));
        }
        dl_copy(&frame->context.uc_mcontext, delivery->host_gp, DL_GP_SIZE);
        mask = delivery->host_mask & ~SYS_BIT;
    }
    dl_copy(&frame->context.uc_sigmask, &mask, 8);
    dl_resume(frame);
}

long dl_blocking(long nr, unsigned long a[6], void *pointer)
{
    ucontext_t *context = pointer;
    struct dl_signal_state *state = &dl_thread_self()->signals;
    if (state->wait_count == DL_WAIT_LIMIT) return -EAGAIN;
    unsigned long mask;
    dl_copy(&mask, &context->uc_sigmask, 8);
    mask = (mask & ~SYS_BIT) | state->virtual_sigsys_mask;
    uintptr_t stack; __asm__ volatile("mov %0, sp" : "=r"(stack));
    unsigned index = state->wait_count++;
    state->waits[index] = (struct dl_waiting){context, stack, mask, state->virtual_sigsys_mask, 0, nr};
    unsigned long physical = mask & ~SYS_BIT;
    DL_CALL(SYS_rt_sigprocmask, SIG_SETMASK, &physical, NULL, 8, 0, 0);
    long result = DL_CALL(nr, a[0], a[1], a[2], a[3], a[4], a[5]);
    unsigned long all = ~0UL;
    DL_CALL(SYS_rt_sigprocmask, SIG_SETMASK, &all, NULL, 8, 0, 0);
    state->virtual_sigsys_mask = state->waits[index].return_bit;
    state->wait_count = index;
    return result;
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
    struct dl_sighand *table = dl_thread_self()->sighand;
    dl_lock(&table->lock);
    long r = DL_CALL(SYS_rt_sigaction, a[0], a[1] ? &installed : NULL, &previous, 8, 0, 0);
    if (r) { dl_unlock(&table->lock); return r; }
    if (previous.handler == (uintptr_t)guest_signal ||
            (table->wrapped[a[0]] && previous.handler == 0 && (previous.flags & SA_RESETHAND))) {
        struct action saved = table->actions[a[0]];
        if (previous.handler > 1) previous.handler = saved.handler;
        previous.mask = saved.mask & ~UNBLOCKABLE;
        previous.flags = (previous.flags & ~SA_SIGINFO) | (saved.flags & SA_SIGINFO);
    } else if (previous.handler <= 1 && previous.handler == table->actions[a[0]].handler)
        previous.mask |= table->actions[a[0]].mask & SYS_BIT;
    if (a[1]) {
        table->actions[a[0]] = requested; table->wrapped[a[0]] = requested.handler > 1;
        if (requested.handler > 1) table->exec_candidates |= 1UL << (a[0] - 1);
    }
    dl_unlock(&table->lock);
    return a[2] ? dl_write_memory((void *)a[2], &previous, sizeof(previous)) : 0;
}

long dl_signal_wait(long nr, unsigned long a[6], void *pointer)
{
    ucontext_t *context = pointer;
    struct dl_signal_state *state = &dl_thread_self()->signals;
    unsigned long mask, set = 0, address = 0, size = 8;
    struct { unsigned long address, size; } packed = {0, 0};
    dl_copy(&mask, &context->uc_sigmask, 8);
    unsigned long original_bit = state->virtual_sigsys_mask;
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
    if (state->wait_count == DL_WAIT_LIMIT) return -EAGAIN;
    uintptr_t stack; __asm__ volatile("mov %0, sp" : "=r"(stack));
    unsigned index = state->wait_count++;
    state->waits[index] = (struct dl_waiting){context, stack, mask, original_bit, 0, nr};
    state->virtual_sigsys_mask = mask & SYS_BIT;
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
    state->virtual_sigsys_mask = state->waits[index].return_bit;
    state->wait_count = index;
    return result;
}
