/* SPDX-License-Identifier: Apache-2.0 */
/* Real kernel threads; all runtime code remains independent of guest TLS. */
#define _GNU_SOURCE
#include "threads.h"
#include <errno.h>
#include <linux/futex.h>
#include <linux/sched.h>
#include <sys/mman.h>
#include <sys/syscall.h>

enum { FREE, STARTING, RUNNING, EXITING };
static struct dl_thread threads[DL_THREAD_LIMIT];
static unsigned thread_lock;
static unsigned files_shared;
unsigned dl_memory_lock, dl_files_lock, dl_actions_lock;
extern long dl_spawn(unsigned long *, uintptr_t);
extern _Noreturn void dl_resume(void *);

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

struct dl_thread *dl_thread_self(void)
{
    uintptr_t sp; __asm__ volatile("mov %0, sp" : "=r"(sp));
    for (unsigned i = 0; i < DL_THREAD_LIMIT; i++) {
        if (__atomic_load_n(&threads[i].state, __ATOMIC_ACQUIRE) != RUNNING) continue;
        uintptr_t begin = __atomic_load_n(&threads[i].stack_begin, __ATOMIC_RELAXED);
        uintptr_t end = __atomic_load_n(&threads[i].stack_end, __ATOMIC_RELAXED);
        if (sp >= begin && sp < end) return threads + i;
    }
    // Native guest signal handlers can run on their ordinary guest stack.
    int tid = (int)DL_CALL(SYS_gettid, 0, 0, 0, 0, 0, 0);
    for (unsigned i = 0; i < DL_THREAD_LIMIT; i++)
        if (__atomic_load_n(&threads[i].state, __ATOMIC_ACQUIRE) == RUNNING &&
                __atomic_load_n(&threads[i].tid, __ATOMIC_RELAXED) == tid) return threads + i;
    DL_CALL(SYS_exit_group, 126, 0, 0, 0, 0, 0);
    __builtin_unreachable();
}

void dl_thread_init(void *allocation, size_t size, void *stack, size_t stack_size)
{
    struct dl_thread *t = threads;
    __atomic_store_n(&t->io_count, 0, __ATOMIC_RELAXED);
    t->allocation = allocation; t->allocation_size = size;
    __atomic_store_n(&t->stack_begin, (uintptr_t)stack, __ATOMIC_RELAXED);
    __atomic_store_n(&t->stack_end, (uintptr_t)stack + stack_size, __ATOMIC_RELAXED);
    __atomic_store_n(&t->tid, (int)DL_CALL(SYS_gettid, 0, 0, 0, 0, 0, 0), __ATOMIC_RELAXED);
    __atomic_store_n(&t->state, RUNNING, __ATOMIC_RELEASE);
}

static void dispose(struct dl_thread *t)
{
    DL_CALL(SYS_munmap, t->allocation, t->allocation_size, 0, 0, 0, 0);
    __atomic_store_n(&t->state, FREE, __ATOMIC_RELEASE);
}

static unsigned reap(void)
{
    long pid = DL_CALL(SYS_getpid, 0, 0, 0, 0, 0, 0);
    unsigned count = 0;
    for (unsigned i = 0; i < DL_THREAD_LIMIT; i++) {
        unsigned state = __atomic_load_n(&threads[i].state, __ATOMIC_ACQUIRE);
        if (state == EXITING && DL_CALL(SYS_tgkill, pid,
                __atomic_load_n(&threads[i].tid, __ATOMIC_RELAXED), 0, 0, 0, 0) == -ESRCH) {
            dispose(threads + i); state = FREE;
        }
        if (state != FREE) count++;
    }
    return count;
}

static long copy_frame(struct dl_thread *child, ucontext_t *context, uintptr_t guest_sp)
{
    struct frame { siginfo_t info; ucontext_t context; };
    uintptr_t source = (uintptr_t)context - offsetof(struct frame, context);
    struct dl_thread *parent = dl_thread_self();
    size_t size = sizeof(struct frame), extra_offset = 0;
    uintptr_t extra_pointer = 0;
    for (size_t offset = 0; offset + sizeof(struct _aarch64_ctx) <= sizeof(context->uc_mcontext.__reserved);) {
        struct _aarch64_ctx *head = (void *)(context->uc_mcontext.__reserved + offset);
        if (!head->magic && !head->size) break;
        if (head->size < sizeof(*head) || head->size % 16 || head->size > sizeof(context->uc_mcontext.__reserved) - offset)
            return -EINVAL;
        if (head->magic == EXTRA_MAGIC) {
            struct extra_context *extra = (void *)head;
            if (extra_offset || head->size != sizeof(*extra) || extra->datap < source ||
                    extra->datap > parent->stack_end || extra->size > parent->stack_end - extra->datap) return -EINVAL;
            extra_offset = (uintptr_t)extra - source; extra_pointer = extra->datap;
            if (extra->datap + extra->size - source > size) size = extra->datap + extra->size - source;
        }
        offset += head->size;
    }
    if (source < parent->stack_begin || source > parent->stack_end || size > parent->stack_end - source ||
            size > DL_SIGNAL_STACK_SIZE / 2) return -E2BIG;
    child->frame = (void *)((child->stack_end - size) & ~15UL);
    dl_copy(child->frame, (void *)source, size);
    if (extra_offset) ((struct extra_context *)((char *)child->frame + extra_offset))->datap =
        (uintptr_t)child->frame + extra_pointer - source;
    struct frame *frame = child->frame;
    frame->context.uc_mcontext.regs[0] = 0;
    frame->context.uc_mcontext.sp = guest_sp;
    frame->context.uc_stack = (stack_t){.ss_sp = (void *)child->stack_begin, .ss_size = DL_SIGNAL_STACK_SIZE};
    return 0;
}

_Noreturn void dl_thread_start(struct dl_thread *thread)
{
    stack_t stack = {.ss_sp = (void *)thread->stack_begin, .ss_size = DL_SIGNAL_STACK_SIZE};
    if (DL_CALL(SYS_sigaltstack, &stack, NULL, 0, 0, 0, 0)) {
        DL_CALL(SYS_exit_group, 126, 0, 0, 0, 0, 0); __builtin_unreachable();
    }
    __atomic_store_n(&thread->tid, (int)DL_CALL(SYS_gettid, 0, 0, 0, 0, 0, 0), __ATOMIC_RELAXED);
    __atomic_store_n(&thread->state, RUNNING, __ATOMIC_RELEASE);
    dl_resume(thread->frame);
}

long dl_thread_clone(unsigned long a[6], ucontext_t *context)
{
    unsigned long required = CLONE_VM | CLONE_SIGHAND | CLONE_THREAD;
    unsigned long allowed = required | CLONE_FS | CLONE_FILES | CLONE_SYSVSEM | CLONE_SETTLS |
        CLONE_PARENT_SETTID | CLONE_CHILD_SETTID | CLONE_CHILD_CLEARTID;
    if ((a[0] & required) != required || (a[0] & ~allowed) || !a[1] || (a[1] & 15)) return -EOPNOTSUPP;
    dl_lock(&thread_lock); reap();
    struct dl_thread *child = NULL;
    for (unsigned i = 0; i < DL_THREAD_LIMIT; i++)
        if (__atomic_load_n(&threads[i].state, __ATOMIC_ACQUIRE) == FREE) { child = threads + i; break; }
    if (!child) { dl_unlock(&thread_lock); return -EAGAIN; }
    size_t size = DL_SIGNAL_STACK_SIZE + 2 * dl_state.page;
    long allocation = DL_CALL(SYS_mmap, 0, size, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (allocation < 0) { dl_unlock(&thread_lock); return allocation; }
    long r = DL_CALL(SYS_mprotect, allocation + dl_state.page, DL_SIGNAL_STACK_SIZE, PROT_READ | PROT_WRITE, 0, 0, 0);
    child->allocation = (void *)allocation; child->allocation_size = size;
    __atomic_store_n(&child->io_count, 0, __ATOMIC_RELAXED);
    __atomic_store_n(&child->stack_begin, allocation + dl_state.page, __ATOMIC_RELAXED);
    __atomic_store_n(&child->stack_end, allocation + dl_state.page + DL_SIGNAL_STACK_SIZE, __ATOMIC_RELAXED);
    for (size_t i = 0; i < sizeof(child->signals); i++) ((char *)&child->signals)[i] = 0;
    child->signals.virtual_sigsys_mask = dl_thread_self()->signals.virtual_sigsys_mask;
    if (!r) r = copy_frame(child, context, a[1]);
    if (r) { dispose(child); dl_unlock(&thread_lock); return r; }
    uintptr_t bootstrap = (uintptr_t)child->frame - 16;
    *(struct dl_thread **)bootstrap = child;
    __atomic_store_n(&child->state, STARTING, __ATOMIC_RELEASE);
    if (a[0] & CLONE_FILES) __atomic_store_n(&files_shared, 1, __ATOMIC_RELEASE);
    r = dl_spawn(a, bootstrap);
    if (r < 0) dispose(child);
    else {
        // A reused TID identifies an earlier exited task unambiguously while
        // creation/reaping is serialized. Do not leave its stack unreclaimed.
        for (unsigned i = 0; i < DL_THREAD_LIMIT; i++)
            if (threads + i != child && __atomic_load_n(&threads[i].state, __ATOMIC_ACQUIRE) == EXITING &&
                    __atomic_load_n(&threads[i].tid, __ATOMIC_RELAXED) == r) dispose(threads + i);
    }
    dl_unlock(&thread_lock); return r;
}

_Noreturn void dl_thread_exit(int code)
{
    struct dl_thread *t = dl_thread_self();
    dl_io_collect(0);
    __atomic_store_n(&t->state, EXITING, __ATOMIC_RELEASE);
    // Keep both kernel clear-child-TID and robust-list cleanup intact. Another
    // live thread reclaims this private stack only after the task is gone.
    DL_CALL(SYS_exit, code, 0, 0, 0, 0, 0); __builtin_unreachable();
}

long dl_fork_begin(void)
{
    dl_lock(&thread_lock);
    // Fork while other threads run needs a coordinated copied-state protocol.
    while (reap() != 1) {
        unsigned active = 0;
        for (unsigned i = 0; i < DL_THREAD_LIMIT; i++) {
            unsigned state = __atomic_load_n(&threads[i].state, __ATOMIC_ACQUIRE);
            if (state == RUNNING || state == STARTING) active++;
        }
        if (active != 1) { dl_unlock(&thread_lock); return -EOPNOTSUPP; }
        // Join's clear-TID wake may precede final kernel task removal. These
        // EXITING tasks no longer run guest/runtime work; wait for actual death.
        DL_CALL(SYS_sched_yield, 0, 0, 0, 0, 0, 0);
    }
    return 0;
}
void dl_fork_end(int child)
{
    if (child) {
        struct dl_thread *self = dl_thread_self();
        __atomic_store_n(&self->tid, (int)DL_CALL(SYS_gettid, 0, 0, 0, 0, 0, 0), __ATOMIC_RELAXED);
        __atomic_store_n(&files_shared, 0, __ATOMIC_RELEASE);
    }
    dl_unlock(&thread_lock);
}
long dl_exec_begin(void)
{
    long r = dl_fork_begin(); if (r) return r;
    if (DL_CALL(SYS_gettid, 0, 0, 0, 0, 0, 0) != DL_CALL(SYS_getpid, 0, 0, 0, 0, 0, 0)) {
        dl_unlock(&thread_lock); return -EOPNOTSUPP;
    }
    return 0;
}
void dl_exec_end(void) { dl_unlock(&thread_lock); }
int dl_files_may_be_shared(void) { return __atomic_load_n(&files_shared, __ATOMIC_ACQUIRE) != 0; }
int dl_internal_fd(int fd)
{
    return fd >= 0 && dl_next_internal_fd((unsigned)fd) == (unsigned)fd;
}

unsigned dl_next_internal_fd(unsigned first)
{
    unsigned result = ~0U;
    int fixed[] = {dl_state.root_fd, dl_state.vfork_fd};
    for (size_t i = 0; i < sizeof(fixed) / sizeof(fixed[0]); i++)
        if (fixed[i] >= 0 && (unsigned)fixed[i] >= first && (unsigned)fixed[i] < result) result = fixed[i];
    for (unsigned i = 0; i < DL_THREAD_LIMIT; i++) {
        if (__atomic_load_n(&threads[i].state, __ATOMIC_ACQUIRE) == FREE) continue;
        unsigned count = __atomic_load_n(&threads[i].io_count, __ATOMIC_ACQUIRE);
        for (unsigned j = 0; j < count; j++) {
            int fd = __atomic_load_n(&threads[i].io[j].fd, __ATOMIC_RELAXED);
            if (fd >= 0 && (unsigned)fd >= first && (unsigned)fd < result) result = fd;
        }
    }
    return result;
}

// Mutations happen with signals blocked and the filesystem lock held. The
// stack marker identifies operations abandoned by cancellation or siglongjmp.
long dl_io_hold(int fd, uintptr_t stack)
{
    struct dl_thread *t = dl_thread_self();
    unsigned slot = __atomic_load_n(&t->io_count, __ATOMIC_RELAXED);
    if (slot == DL_WAIT_LIMIT) return -EAGAIN;
    t->io[slot].stack = stack;
    __atomic_store_n(&t->io[slot].fd, fd, __ATOMIC_RELAXED);
    __atomic_store_n(&t->io_count, slot + 1, __ATOMIC_RELEASE);
    return slot;
}
void dl_io_release(unsigned slot)
{
    struct dl_thread *t = dl_thread_self();
    int fd = __atomic_exchange_n(&t->io[slot].fd, -1, __ATOMIC_RELAXED);
    if (fd >= 0) DL_CALL(SYS_close, fd, 0, 0, 0, 0, 0);
    __atomic_store_n(&t->io_count, slot, __ATOMIC_RELEASE);
}
void dl_io_collect(uintptr_t stack)
{
    struct dl_thread *t = dl_thread_self();
    unsigned count = __atomic_load_n(&t->io_count, __ATOMIC_RELAXED);
    if (!count) return;
    int outside = stack < t->stack_begin || stack >= t->stack_end;
    if (!outside && stack <= t->io[count - 1].stack) return;
    dl_lock(&dl_files_lock);
    do { dl_io_release(--count); }
    while (count && (outside || stack > t->io[count - 1].stack));
    dl_unlock(&dl_files_lock);
}
