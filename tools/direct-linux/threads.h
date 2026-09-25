/* SPDX-License-Identifier: Apache-2.0 */
#ifndef DIRECT_LINUX_THREADS_H
#define DIRECT_LINUX_THREADS_H
#include "runtime.h"
#include <signal.h>
#include <ucontext.h>

#define DL_THREAD_LIMIT 128
#define DL_SIGNAL_STACK_SIZE (1024UL * 1024)
#define DL_WAIT_LIMIT 64
struct dl_waiting {
    ucontext_t *guest;
    uintptr_t stack;
    unsigned long mask, return_bit;
    int suspended;
};
struct dl_signal_state {
    unsigned long virtual_sigsys_mask;
    struct dl_waiting waits[DL_WAIT_LIMIT];
    unsigned wait_count;
};
struct dl_thread {
    unsigned state;
    int tid;
    int protected_fd;
    uintptr_t stack_begin, stack_end;
    void *allocation;
    size_t allocation_size;
    void *frame;
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
int dl_internal_fd(int fd);
unsigned dl_next_internal_fd(unsigned first);
_Noreturn void dl_thread_exit(int code);
void dl_lock(unsigned *lock);
void dl_unlock(unsigned *lock);
extern unsigned dl_memory_lock, dl_files_lock, dl_actions_lock;
#endif
