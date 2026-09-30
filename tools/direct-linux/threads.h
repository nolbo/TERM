/* SPDX-License-Identifier: Apache-2.0 */
#ifndef DIRECT_LINUX_THREADS_H
#define DIRECT_LINUX_THREADS_H
#include "runtime.h"
#include <signal.h>
#include <ucontext.h>

#define DL_THREAD_LIMIT 128
#define DL_SIGNAL_STACK_SIZE (1024UL * 1024)
#define DL_WAIT_LIMIT 64
#define DL_GP_SIZE offsetof(mcontext_t, __reserved)
struct dl_delivery {
    uintptr_t frame;
    int wait;
    unsigned long host_mask;
    unsigned char host_gp[DL_GP_SIZE], exposed_gp[DL_GP_SIZE];
};
struct dl_waiting {
    ucontext_t *guest;
    uintptr_t stack;
    unsigned long mask, return_bit;
    int suspended;
    long syscall;
};
struct dl_signal_state {
    unsigned long virtual_sigsys_mask;
    struct dl_waiting waits[DL_WAIT_LIMIT];
    unsigned wait_count;
    struct dl_delivery deliveries[DL_WAIT_LIMIT];
    unsigned delivery_count;
};
struct dl_sighand;
struct dl_sighand *dl_sighand_initial(void);
void dl_sighand_retain(struct dl_sighand *table);
void dl_sighand_release(struct dl_sighand *table);
unsigned *dl_sighand_lock(struct dl_sighand *table);
// Caller holds the source lock through the kernel clone that copies sighand.
// The new table starts unlocked and is owned by one runtime task record.
long dl_sighand_copy(struct dl_sighand *source, struct dl_sighand **result);
struct dl_thread {
    unsigned state;
    unsigned files_id;
    int tid;
    struct dl_fd_snapshot *copied_fds;
    struct { int fd; uintptr_t stack; } io[DL_WAIT_LIMIT];
    unsigned io_count;
    uintptr_t stack_begin, stack_end;
    void *allocation;
    size_t allocation_size;
    void *frame;
    struct dl_sighand *sighand;
    struct dl_scratch *scratch;
    struct dl_signal_state signals;
};
struct dl_thread *dl_thread_self(void);
void dl_thread_init(void *allocation, size_t size, void *stack, size_t stack_size);
long dl_thread_clone(unsigned long args[6], ucontext_t *context);
long dl_fork_begin(void);
void dl_fork_end(int child);
long dl_exec_begin(void);
void dl_exec_end(void);
int dl_files_may_be_shared(void);
long dl_files_unshare(void);
int dl_internal_fd(int fd);
unsigned dl_next_internal_fd(unsigned first);
long dl_io_hold(int fd, uintptr_t stack);
int dl_io_take(unsigned slot);
void dl_io_release(unsigned slot);
void dl_io_collect(uintptr_t stack);
extern _Noreturn void dl_resume(void *frame);
_Noreturn void dl_thread_exit(int code);
void dl_lock(unsigned *lock);
void dl_unlock(unsigned *lock);
extern unsigned dl_memory_lock, dl_files_lock;
#endif
