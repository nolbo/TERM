/* SPDX-License-Identifier: Apache-2.0 */
/* Real kernel threads; all runtime code remains independent of guest TLS. */
#define _GNU_SOURCE
#include "threads.h"
#include <errno.h>
#include <linux/close_range.h>
#include <linux/futex.h>
#include <linux/sched.h>
#include <sys/mman.h>
#include <sys/syscall.h>

enum { FREE, STARTING, RUNNING, EXITING };
static struct dl_thread threads[DL_THREAD_LIMIT];
static unsigned thread_lock;
static unsigned threads_ready;
static uintptr_t kernel_stack;
struct dl_fd_snapshot { size_t count; int fds[]; };
unsigned dl_memory_lock;
struct dl_files { unsigned lock, references; };
static struct dl_files initial_files = {.references = 1};
extern long dl_spawn(unsigned long *, uintptr_t);
extern long dl_spawn_cleanup(unsigned long *, uintptr_t);
extern _Noreturn void dl_resume(void *);

void dl_lock(unsigned *lock)
{
    unsigned owner = (unsigned)DL_CALL(SYS_gettid, 0, 0, 0, 0, 0, 0);
    unsigned expected = 0;
    if (__atomic_compare_exchange_n(lock, &expected, owner, 0, __ATOMIC_ACQUIRE, __ATOMIC_RELAXED)) return;
    for (;;) {
        if (!expected) {
            if (__atomic_compare_exchange_n(lock, &expected, owner | 0x80000000U, 0, __ATOMIC_ACQUIRE, __ATOMIC_RELAXED)) return;
        } else {
            unsigned waiting = expected | 0x80000000U;
            if (__atomic_compare_exchange_n(lock, &expected, waiting, 0, __ATOMIC_RELAXED, __ATOMIC_RELAXED)) {
                DL_CALL(SYS_futex, lock, FUTEX_WAIT_PRIVATE, waiting, NULL, NULL, 0);
                expected = __atomic_load_n(lock, __ATOMIC_RELAXED);
            }
        }
    }
}
void dl_unlock(unsigned *lock)
{
    if (__atomic_exchange_n(lock, 0, __ATOMIC_RELEASE) & 0x80000000U)
        DL_CALL(SYS_futex, lock, FUTEX_WAKE_PRIVATE, 1, NULL, NULL, 0);
}

static void unlock_owned(unsigned *lock, unsigned owner)
{
    unsigned value = __atomic_load_n(lock, __ATOMIC_RELAXED);
    while ((value & 0x7fffffffU) == owner) {
        if (__atomic_compare_exchange_n(lock, &value, 0, 0, __ATOMIC_RELEASE, __ATOMIC_RELAXED)) {
            if (value & 0x80000000U) DL_CALL(SYS_futex, lock, FUTEX_WAKE_PRIVATE, 1, NULL, NULL, 0);
            return;
        }
    }
}

static long files_create(struct dl_files **result)
{
    long p = DL_CALL(SYS_mmap, 0, sizeof(struct dl_files), PROT_READ | PROT_WRITE,
        MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (p < 0) return p;
    struct dl_files *table = (void *)p;
    table->references = 1; *result = table; return 0;
}
static void files_retain(struct dl_files *table)
{
    __atomic_add_fetch(&table->references, 1, __ATOMIC_RELAXED);
}
static void files_release(struct dl_files *table)
{
    if (table && !__atomic_sub_fetch(&table->references, 1, __ATOMIC_ACQ_REL) && table != &initial_files)
        DL_CALL(SYS_munmap, table, sizeof(*table), 0, 0, 0, 0);
}
unsigned *dl_files_lock_current(void)
{
    return &(threads_ready ? dl_thread_self()->files : &initial_files)->lock;
}
int dl_thread_vfork(void) { return threads_ready && dl_thread_self()->vfork_parent != 0; }

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
    t->sighand = dl_sighand_initial();
    t->process = dl_process_initial();
    __atomic_store_n(&t->io_count, 0, __ATOMIC_RELAXED);
    t->allocation = allocation; t->allocation_size = size;
    t->files = &initial_files; t->copied_fds = NULL;
    __atomic_store_n(&t->stack_begin, (uintptr_t)stack, __ATOMIC_RELAXED);
    __atomic_store_n(&t->stack_end, (uintptr_t)stack + stack_size, __ATOMIC_RELAXED);
    __atomic_store_n(&t->tid, (int)DL_CALL(SYS_gettid, 0, 0, 0, 0, 0, 0), __ATOMIC_RELAXED);
    __atomic_store_n(&t->tgid, (int)DL_CALL(SYS_getpid, 0, 0, 0, 0, 0, 0), __ATOMIC_RELAXED);
    __atomic_store_n(&t->executing, 0, __ATOMIC_RELAXED);
    struct dl_task_status status;
    dl_lock(&dl_files_lock);
    if (!dl_task_status(t->tid, &status)) kernel_stack = status.start_stack;
    dl_unlock(&dl_files_lock);
    __atomic_store_n(&t->state, RUNNING, __ATOMIC_RELEASE);
    threads_ready = 1;
}

struct dl_scratch **dl_thread_scratch(void)
{
    // Initial ELF load and kernel reentry run before dl_thread_init. The first
    // record is already their owner, so no list migration is needed at init.
    return &(threads_ready ? dl_thread_self() : threads)->scratch;
}

static void free_snapshot(struct dl_thread *t, int close_fds)
{
    struct dl_fd_snapshot *copy = t->copied_fds;
    if (!copy) return;
    if (close_fds) for (size_t i = 0; i < copy->count; i++) DL_CALL(SYS_close, copy->fds[i], 0, 0, 0, 0, 0);
    // A separately killable vfork child must not unmap parent-owned metadata:
    // death between munmap and clearing the pointer could make parent cleanup
    // unmap an unrelated mapping that reused the address. Its creator frees
    // this immutable snapshot after kernel vfork completion.
    if (close_fds && t->vfork_parent) return;
    DL_CALL(SYS_munmap, copy, sizeof(*copy) + copy->count * sizeof(int), 0, 0, 0, 0);
    t->copied_fds = NULL;
}
static void dispose(struct dl_thread *t)
{
    free_snapshot(t, 0);
    dl_scratch_clear(&t->scratch);
    if (t->sighand) dl_sighand_release(t->sighand);
    t->sighand = NULL;
    dl_process_release(t->process); t->process = NULL;
    files_release(__atomic_exchange_n(&t->files, NULL, __ATOMIC_ACQ_REL));
    DL_CALL(SYS_munmap, t->allocation, t->allocation_size, 0, 0, 0, 0);
    __atomic_store_n(&t->state, FREE, __ATOMIC_RELEASE);
}

// Caller holds this descriptor table's lock. Separate kernel tables have
// independent locks even when their tasks share VM or a thread group.
static long snapshot_fds(struct dl_thread *child, struct dl_files *table)
{
    size_t count = 0;
    for (unsigned i = 0; i < DL_THREAD_LIMIT; i++)
        if (__atomic_load_n(&threads[i].state, __ATOMIC_ACQUIRE) != FREE &&
                __atomic_load_n(&threads[i].files, __ATOMIC_ACQUIRE) == table)
            count += threads[i].io_count;
    if (!count) return 0;
    long mapping = DL_CALL(SYS_mmap, 0, sizeof(struct dl_fd_snapshot) + count * sizeof(int),
        PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (mapping < 0) return mapping;
    struct dl_fd_snapshot *copy = (void *)mapping; copy->count = 0;
    for (unsigned i = 0; i < DL_THREAD_LIMIT; i++)
        if (__atomic_load_n(&threads[i].state, __ATOMIC_ACQUIRE) != FREE &&
                __atomic_load_n(&threads[i].files, __ATOMIC_ACQUIRE) == table)
            for (unsigned j = 0; j < threads[i].io_count; j++) copy->fds[copy->count++] = threads[i].io[j].fd;
    child->copied_fds = copy; return 0;
}
static void close_other_pins(struct dl_thread *self, struct dl_files *table)
{
    for (unsigned i = 0; i < DL_THREAD_LIMIT; i++)
        if (threads + i != self && __atomic_load_n(&threads[i].state, __ATOMIC_ACQUIRE) != FREE &&
                __atomic_load_n(&threads[i].files, __ATOMIC_ACQUIRE) == table)
            for (unsigned j = 0; j < threads[i].io_count; j++)
                DL_CALL(SYS_close, threads[i].io[j].fd, 0, 0, 0, 0, 0);
}

static int foreign_departed(struct dl_thread *t)
{
    int tid = __atomic_load_n(&t->tid, __ATOMIC_RELAXED);
    int tgid = __atomic_load_n(&t->tgid, __ATOMIC_RELAXED);
    unsigned executing = __atomic_load_n(&t->executing, __ATOMIC_ACQUIRE);
    // A nonleader exec exchanges TIDs before replacing its mm. Its old TID
    // disappearing is not enough: observe the group's new mm instead.
    int nonleader_exec = executing && tid != tgid;
    if (!nonleader_exec && DL_CALL(SYS_tgkill, tgid, tid, 0, 0, 0, 0) == -ESRCH) return 1;
    struct dl_task_status status;
    dl_lock(&dl_files_lock);
    long r = dl_task_status(nonleader_exec ? tgid : tid, &status);
    dl_unlock(&dl_files_lock);
    if (r) return 0;
    if (!nonleader_exec && (status.state == 'Z' || status.state == 'X' || status.state == 'x')) return 1;
    // start_stack is stable in a shared mm; guest PR_SET_MM is not allowed.
    // A hidden/zero value, equal value after exec, or read failure is unknown.
    return kernel_stack && status.start_stack && status.start_stack != kernel_stack;
}

static unsigned reap(void)
{
    long pid = DL_CALL(SYS_getpid, 0, 0, 0, 0, 0, 0);
    unsigned count = 0;
    for (unsigned i = 0; i < DL_THREAD_LIMIT; i++) {
        unsigned state = __atomic_load_n(&threads[i].state, __ATOMIC_ACQUIRE);
        int tgid = __atomic_load_n(&threads[i].tgid, __ATOMIC_RELAXED);
        if ((state == RUNNING || state == EXITING) && !threads[i].vfork_parent &&
                (tgid != pid ? foreign_departed(threads + i) : state == EXITING &&
                DL_CALL(SYS_tgkill, tgid, __atomic_load_n(&threads[i].tid, __ATOMIC_RELAXED), 0, 0, 0, 0) == -ESRCH)) {
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
    if (thread->vfork_parent) {
        // The creator is blocked in kernel vfork. Release only its original
        // acquisitions; if we die here, it can finish these same operations
        // without unlocking a lock another parent thread has since acquired.
        unlock_owned(dl_sighand_lock(thread->vfork_sighand), (unsigned)thread->vfork_parent);
        unlock_owned(&thread->vfork_files->lock, (unsigned)thread->vfork_parent);
        unlock_owned(&thread_lock, (unsigned)thread->vfork_parent);
    }
    stack_t stack = {.ss_sp = (void *)thread->stack_begin, .ss_size = DL_SIGNAL_STACK_SIZE};
    if (DL_CALL(SYS_sigaltstack, &stack, NULL, 0, 0, 0, 0)) {
        DL_CALL(SYS_exit_group, 126, 0, 0, 0, 0, 0); __builtin_unreachable();
    }
    if (thread->copied_fds) {
        dl_lock(&thread->files->lock); free_snapshot(thread, 1); dl_unlock(&thread->files->lock);
    }
    __atomic_store_n(&thread->tid, (int)DL_CALL(SYS_gettid, 0, 0, 0, 0, 0, 0), __ATOMIC_RELAXED);
    __atomic_store_n(&thread->tgid, (int)DL_CALL(SYS_getpid, 0, 0, 0, 0, 0, 0), __ATOMIC_RELAXED);
    __atomic_store_n(&thread->state, RUNNING, __ATOMIC_RELEASE);
    dl_resume(thread->frame);
}

long dl_thread_clone(unsigned long a[6], ucontext_t *context)
{
    int vfork = (a[0] & (CLONE_VM | CLONE_VFORK | CLONE_THREAD)) == (CLONE_VM | CLONE_VFORK);
    unsigned long required = vfork ? CLONE_VM | CLONE_VFORK | SIGCHLD : CLONE_VM | CLONE_SIGHAND | CLONE_THREAD;
    unsigned long allowed = required | (vfork ? 0 : CLONE_FS | CLONE_FILES | CLONE_SYSVSEM | CLONE_SETTLS) |
        CLONE_PARENT_SETTID | CLONE_CHILD_SETTID | CLONE_CHILD_CLEARTID;
    uintptr_t guest_sp = a[1] ? a[1] : context->uc_mcontext.sp;
    if ((a[0] & required) != required || (a[0] & ~allowed) || (!vfork && !a[1]) || (guest_sp & 15)) return -EOPNOTSUPP;
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
    child->sighand = NULL;
    child->process = NULL;
    child->files = NULL;
    child->vfork_parent = 0; child->vfork_files = NULL; child->vfork_sighand = NULL;
    __atomic_store_n(&child->tid, 0, __ATOMIC_RELAXED);
    __atomic_store_n(&child->tgid, dl_thread_self()->tgid, __ATOMIC_RELAXED);
    __atomic_store_n(&child->executing, 0, __ATOMIC_RELAXED);
    child->copied_fds = NULL;
    __atomic_store_n(&child->io_count, 0, __ATOMIC_RELAXED);
    __atomic_store_n(&child->stack_begin, allocation + dl_state.page, __ATOMIC_RELAXED);
    __atomic_store_n(&child->stack_end, allocation + dl_state.page + DL_SIGNAL_STACK_SIZE, __ATOMIC_RELAXED);
    for (size_t i = 0; i < sizeof(child->signals); i++) ((char *)&child->signals)[i] = 0;
    child->signals.virtual_sigsys_mask = dl_thread_self()->signals.virtual_sigsys_mask;
    if (!r) r = copy_frame(child, context, guest_sp);
    if (!r && vfork) r = dl_scratch_reserve(&child->scratch, 16UL * 1024 * 1024);
    if (r) { dispose(child); dl_unlock(&thread_lock); return r; }
    struct dl_thread *self = dl_thread_self();
    if (vfork) {
        r = dl_process_new(&child->process);
        if (r) { dispose(child); dl_unlock(&thread_lock); return r; }
    } else { child->process = self->process; dl_process_retain(child->process); }
    if (!vfork) { child->sighand = self->sighand; dl_sighand_retain(child->sighand); }
    dl_lock(&dl_files_lock);
    if (vfork) {
        dl_lock(dl_sighand_lock(self->sighand));
        r = dl_sighand_copy(self->sighand, &child->sighand);
        if (r) {
            dl_unlock(dl_sighand_lock(self->sighand));
            dispose(child); dl_unlock(&dl_files_lock); dl_unlock(&thread_lock); return r;
        }
    }
    if (a[0] & CLONE_FILES) {
        child->files = dl_thread_self()->files; files_retain(child->files);
    } else {
        r = files_create(&child->files);
        if (!r) r = snapshot_fds(child, dl_thread_self()->files);
        if (r) {
            if (vfork) dl_unlock(dl_sighand_lock(self->sighand));
            dispose(child); dl_unlock(&dl_files_lock); dl_unlock(&thread_lock); return r;
        }
    }
    uintptr_t bootstrap = (uintptr_t)child->frame - 16;
    *(struct dl_thread **)bootstrap = child;
    if (vfork) {
        child->vfork_parent = self->tid;
        child->vfork_files = self->files; child->vfork_sighand = self->sighand;
    }
    __atomic_store_n(&child->state, STARTING, __ATOMIC_RELEASE);
    r = dl_spawn(a, bootstrap);
    if (vfork) {
        // Kernel completion proves the child cannot return to the old VM.
        // Its record is reserved until here, so no PID/proc heuristic is needed.
        unlock_owned(dl_sighand_lock(self->sighand), (unsigned)self->tid);
        unlock_owned(&self->files->lock, (unsigned)self->tid);
        unlock_owned(&thread_lock, (unsigned)self->tid);
        dl_lock(&thread_lock); dispose(child); dl_unlock(&thread_lock); return r;
    }
    if (r < 0) dispose(child);
    else {
        // A reused TID identifies an earlier exited task unambiguously while
        // creation/reaping is serialized. Do not leave its stack unreclaimed.
        for (unsigned i = 0; i < DL_THREAD_LIMIT; i++)
            if (threads + i != child && __atomic_load_n(&threads[i].state, __ATOMIC_ACQUIRE) == EXITING &&
                    __atomic_load_n(&threads[i].tid, __ATOMIC_RELAXED) == r) dispose(threads + i);
    }
    dl_unlock(&dl_files_lock); dl_unlock(&thread_lock); return r;
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
    reap();
    // Stabilize every copied runtime lock/state. Guest memory/FD sharing keeps
    // normal kernel fork semantics; only this task's runtime continuation lives.
    dl_lock(&dl_files_lock); dl_lock(dl_sighand_lock(dl_thread_self()->sighand));
    dl_lock(dl_process_lock(dl_thread_self()->process));
    return 0;
}
void dl_fork_end(int child)
{
    if (child) {
        struct dl_thread *self = dl_thread_self();
        close_other_pins(self, self->files);
        for (unsigned i = 0; i < DL_THREAD_LIMIT; i++)
            if (threads + i != self && __atomic_load_n(&threads[i].state, __ATOMIC_ACQUIRE) != FREE) dispose(threads + i);
        __atomic_store_n(&self->tid, (int)DL_CALL(SYS_gettid, 0, 0, 0, 0, 0, 0), __ATOMIC_RELAXED);
        __atomic_store_n(&self->tgid, (int)DL_CALL(SYS_getpid, 0, 0, 0, 0, 0, 0), __ATOMIC_RELAXED);
        __atomic_store_n(&self->executing, 0, __ATOMIC_RELAXED);
    }
    dl_unlock(dl_process_lock(dl_thread_self()->process));
    dl_unlock(dl_sighand_lock(dl_thread_self()->sighand)); dl_unlock(&dl_files_lock);
    dl_unlock(&thread_lock);
}
long dl_exec_begin(void)
{
    if (dl_thread_vfork()) return 1;
    dl_lock(&thread_lock);
    // An in-process replacement is safe only for one actual kernel task.
    // Kernel exec owns thread destruction and nonleader -> leader conversion.
    return reap() != 1 || DL_CALL(SYS_gettid, 0, 0, 0, 0, 0, 0) != DL_CALL(SYS_getpid, 0, 0, 0, 0, 0, 0);
}
void dl_exec_end(void) { if (!dl_thread_vfork()) dl_unlock(&thread_lock); }
void dl_exec_mark(int executing)
{
    __atomic_store_n(&dl_thread_self()->executing, (unsigned)executing, __ATOMIC_RELEASE);
}
int dl_files_may_be_shared(void)
{
    return threads_ready && __atomic_load_n(&dl_thread_self()->files->references, __ATOMIC_ACQUIRE) > 1;
}

struct file_cleanup { unsigned stage; int tid; size_t count; int fds[DL_WAIT_LIMIT]; };
_Noreturn void dl_files_cleanup_start(struct file_cleanup *job)
{
    unsigned stage;
    while (!(stage = __atomic_load_n(&job->stage, __ATOMIC_ACQUIRE)))
        DL_CALL(SYS_futex, &job->stage, FUTEX_WAIT_PRIVATE, 0, NULL, NULL, 0);
    if (stage == 1) for (size_t i = 0; i < job->count; i++) DL_CALL(SYS_close, job->fds[i], 0, 0, 0, 0, 0);
    DL_CALL(SYS_exit, 0, 0, 0, 0, 0, 0); __builtin_unreachable();
}

long dl_files_unshare(void)
{
    struct dl_thread *self = dl_thread_self();
    struct dl_files *table = self->files, *copy;
    long r = files_create(&copy);
    if (r) return r;
    struct file_cleanup *job = NULL; long mapping = 0;
    size_t size = 65536 + 2 * dl_state.page;
    if (self->io_count) {
        // A handler can unshare while its interrupted runtime I/O holds pins.
        // Keep a tiny signal-blocked cleanup task in the OLD table until the
        // copy succeeds, so its duplicate pins are closed promptly as well.
        mapping = DL_CALL(SYS_mmap, 0, size, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
        if (mapping < 0) { files_release(copy); return mapping; }
        long r = DL_CALL(SYS_mprotect, mapping + dl_state.page, 65536, PROT_READ | PROT_WRITE, 0, 0, 0);
        if (r) { DL_CALL(SYS_munmap, mapping, size, 0, 0, 0, 0); files_release(copy); return r; }
        job = (void *)(mapping + dl_state.page); job->count = self->io_count;
        for (size_t i = 0; i < job->count; i++) job->fds[i] = self->io[i].fd;
        uintptr_t stack = (uintptr_t)job + 65536 - 16;
        *(struct file_cleanup **)stack = job;
        unsigned long flags = CLONE_VM | CLONE_FILES | CLONE_SIGHAND | CLONE_THREAD |
            CLONE_PARENT_SETTID | CLONE_CHILD_CLEARTID;
        unsigned long args[] = {flags, 0, (uintptr_t)&job->tid, 0, (uintptr_t)&job->tid, 0};
        r = dl_spawn_cleanup(args, stack);
        if (r < 0) { DL_CALL(SYS_munmap, mapping, size, 0, 0, 0, 0); files_release(copy); return r; }
    }
    r = DL_CALL(SYS_close_range, ~0U, ~0U, CLOSE_RANGE_UNSHARE, 0, 0, 0);
    if (!r) close_other_pins(self, table);
    if (job) {
        __atomic_store_n(&job->stage, r ? 2 : 1, __ATOMIC_RELEASE);
        DL_CALL(SYS_futex, &job->stage, FUTEX_WAKE_PRIVATE, 1, NULL, NULL, 0);
        int tid;
        // Kernel clear-child-TID wakes a shared futex, even for CLONE_THREAD.
        while ((tid = __atomic_load_n(&job->tid, __ATOMIC_ACQUIRE)))
            DL_CALL(SYS_futex, &job->tid, FUTEX_WAIT, tid, NULL, NULL, 0);
        DL_CALL(SYS_munmap, mapping, size, 0, 0, 0, 0);
    }
    if (!r) {
        // Keep the old table locked until its helper has closed the pins;
        // publishing the new identity earlier would allow old-FD reuse.
        dl_lock(&copy->lock);
        __atomic_store_n(&self->files, copy, __ATOMIC_RELEASE);
        dl_unlock(&table->lock); files_release(table);
    } else files_release(copy);
    return r;
}
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
    struct dl_files *table = threads_ready ? dl_thread_self()->files : &initial_files;
    for (unsigned i = 0; i < DL_THREAD_LIMIT; i++) {
        if (__atomic_load_n(&threads[i].state, __ATOMIC_ACQUIRE) == FREE) continue;
        if (__atomic_load_n(&threads[i].files, __ATOMIC_ACQUIRE) != table) continue;
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
int dl_io_take(unsigned slot)
{
    struct dl_thread *t = dl_thread_self();
    int fd = __atomic_exchange_n(&t->io[slot].fd, -1, __ATOMIC_RELAXED);
    __atomic_store_n(&t->io_count, slot, __ATOMIC_RELEASE);
    return fd;
}
void dl_io_release(unsigned slot)
{
    int fd = dl_io_take(slot);
    if (fd >= 0) DL_CALL(SYS_close, fd, 0, 0, 0, 0, 0);
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
