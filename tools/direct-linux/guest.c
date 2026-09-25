/* SPDX-License-Identifier: Apache-2.0 */
/* Everything reachable after dl_enter is libc/TLS/allocator independent. */
#define _GNU_SOURCE
#include "runtime.h"
#include "threads.h"
#include <errno.h>
#include <fcntl.h>
#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <linux/sched.h>
#include <signal.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <ucontext.h>
#include <unistd.h>

#include "startup-profile.h"

#define DL_TAG 0xd1
struct dl_state dl_state = {.vfork_fd = -1};

size_t dl_length(const char *s) { size_t n = 0; while (s[n]) n++; return n; }
int dl_equal(const char *a, const char *b) {
    size_t i = 0; while (a[i] && a[i] == b[i]) i++; return a[i] == b[i];
}

static long memory_copy(void *d, const void *s, size_t n, int write)
{
    struct iovec local = {write ? (void *)s : d, n};
    struct iovec remote = {write ? d : (void *)s, n};
    // The thread-group leader can have exited while this thread still owns
    // the shared address space. Resolve a live task, not the dead leader's mm.
    long pid = DL_CALL(SYS_gettid, 0, 0, 0, 0, 0, 0);
    long r = DL_CALL(write ? SYS_process_vm_writev : SYS_process_vm_readv, pid, &local, 1, &remote, 1, 0);
    return r < 0 ? r : (size_t)r == n ? 0 : -EFAULT;
}
long dl_read_memory(void *d, const void *s, size_t n) { return memory_copy(d, s, n, 0); }
long dl_write_memory(void *d, const void *s, size_t n) { return memory_copy(d, s, n, 1); }
long dl_string(char *destination, const char *source)
{
    size_t used = 0;
    while (used < DL_PATH) {
        size_t n = dl_state.page - ((uintptr_t)source % dl_state.page);
        if (n > DL_PATH - used) n = DL_PATH - used;
        long r = dl_read_memory(destination + used, source, n);
        if (r) return r;
        for (size_t i = 0; i < n; i++) if (!destination[used + i]) return 0;
        if (UINTPTR_MAX - (uintptr_t)source < n) return -EFAULT;
        used += n; source += n;
    }
    return -ENAMETOOLONG;
}

static long guest_brk(uintptr_t address)
{
    if (!address) return dl_state.heap_end;
    if (address < dl_state.heap_start || address - dl_state.heap_start > DL_HEAP) return dl_state.heap_end;
    uintptr_t rounded = (address + dl_state.page - 1) & ~(dl_state.page - 1);
    if (rounded > dl_state.heap_mapped) {
        if (DL_CALL(SYS_mprotect, dl_state.heap_mapped, rounded - dl_state.heap_mapped,
                PROT_READ | PROT_WRITE, 0, 0, 0) < 0) return dl_state.heap_end;
    } else if (rounded < dl_state.heap_mapped) {
        if (DL_CALL(SYS_mprotect, rounded, dl_state.heap_mapped - rounded, PROT_NONE, 0, 0, 0) < 0)
            return dl_state.heap_end;
        DL_CALL(SYS_madvise, rounded, dl_state.heap_mapped - rounded, MADV_DONTNEED, 0, 0, 0);
    }
    dl_state.heap_mapped = rounded; dl_state.heap_end = address;
    return address;
}

static long locked_brk(uintptr_t address)
{
    dl_lock(&dl_memory_lock); long result = guest_brk(address); dl_unlock(&dl_memory_lock); return result;
}

static long guest_clone(unsigned long *a, uintptr_t guest_sp)
{
    if (dl_state.vfork_fd >= 0) return -EOPNOTSUPP;
    if (a[0] == (CLONE_VM | CLONE_VFORK | SIGCHLD) && (!a[1] || a[1] == guest_sp)) {
        // Copy-on-write vfork compatibility: the parent waits for exec/exit.
        // Shared-VM clone/spawn with a separate stack is explicitly unsupported.
        long r = dl_fork_begin(); if (r) return r;
        int pipes[2];
        r = DL_CALL(SYS_pipe2, pipes, O_CLOEXEC, 0, 0, 0, 0);
        if (r) { dl_fork_end(0); return r; }
        long writer = DL_CALL(SYS_fcntl, pipes[1], F_DUPFD_CLOEXEC, 256, 0, 0, 0);
        DL_CALL(SYS_close, pipes[1], 0, 0, 0, 0, 0);
        if (writer < 0) { DL_CALL(SYS_close, pipes[0], 0, 0, 0, 0, 0); dl_fork_end(0); return writer; }
        r = dl_memory_fork_begin();
        if (r) {
            DL_CALL(SYS_close, writer, 0, 0, 0, 0, 0);
            DL_CALL(SYS_close, pipes[0], 0, 0, 0, 0, 0); dl_fork_end(0); return r;
        }
        r = DL_CALL(SYS_clone, SIGCHLD, 0, 0, 0, 0, 0);
        dl_memory_fork_end(r == 0);
        dl_fork_end(r == 0);
        if (!r) {
            DL_CALL(SYS_close, pipes[0], 0, 0, 0, 0, 0);
            dl_state.vfork_fd = (int)writer; return 0;
        }
        DL_CALL(SYS_close, writer, 0, 0, 0, 0, 0);
        if (r > 0) {
            char byte; long waited;
            do { waited = DL_CALL(SYS_read, pipes[0], &byte, 1, 0, 0, 0); } while (waited == -EINTR);
        }
        DL_CALL(SYS_close, pipes[0], 0, 0, 0, 0, 0); return r;
    }
    // Only fork semantics: no VM, signal-handler, FS, or FD-table sharing.
    if ((a[0] & 255) != SIGCHLD ||
            (a[0] & ~(255UL | CLONE_CHILD_SETTID | CLONE_CHILD_CLEARTID | CLONE_PARENT_SETTID)) || a[1])
        return -EOPNOTSUPP;
    long r = dl_fork_begin(); if (r) return r;
    r = dl_memory_fork_begin(); if (r) { dl_fork_end(0); return r; }
    r = DL_CALL(SYS_clone, a[0], 0, a[2], 0, a[4], 0);
    dl_memory_fork_end(r == 0);
    dl_fork_end(r == 0); return r;
}

static long handle(long nr, unsigned long *a, ucontext_t *context)
{
    long r;
    switch (nr) {
    case SYS_openat: case SYS_newfstatat: case SYS_statx: case SYS_faccessat:
    case SYS_readlinkat: case SYS_getcwd: case SYS_chdir: case SYS_fchdir:
    case SYS_mkdirat: case SYS_unlinkat: case SYS_renameat: case SYS_renameat2:
    case SYS_symlinkat: case SYS_fchmodat: case SYS_fchownat: case SYS_utimensat:
    case SYS_statfs: case SYS_truncate:
    case SYS_getxattr: case SYS_lgetxattr: case SYS_setxattr: case SYS_lsetxattr:
    case SYS_listxattr: case SYS_llistxattr: case SYS_removexattr: case SYS_lremovexattr:
        dl_lock(&dl_files_lock);
        r = dl_filesystem(nr, a, context);
        dl_unlock(&dl_files_lock); return r;
    case SYS_close:
        dl_lock(&dl_files_lock);
        if (dl_internal_fd((int)a[0])) { dl_unlock(&dl_files_lock); return -EBADF; }
        r = DL_CALL(nr, a[0], 0, 0, 0, 0, 0);
        dl_unlock(&dl_files_lock); return r;
    case SYS_close_range: return dl_close_range((unsigned)a[0], (unsigned)a[1], (unsigned)a[2]);
    case SYS_socket: case SYS_socketpair: case SYS_bind: case SYS_connect:
    case SYS_sendto: case SYS_sendmsg:
    case SYS_getsockname: case SYS_getpeername: case SYS_accept: case SYS_accept4:
    case SYS_recvfrom: case SYS_recvmsg: case SYS_recvmmsg:
        return dl_network(nr, a, context);
    case SYS_dup3:
        dl_lock(&dl_files_lock);
        if (dl_internal_fd((int)a[1])) { dl_unlock(&dl_files_lock); return -EBADF; }
        r = DL_CALL(nr, a[0], a[1], a[2], 0, 0, 0);
        dl_unlock(&dl_files_lock); return r;
    case SYS_fcntl: case SYS_dup:
        if (dl_internal_fd((int)a[0])) return -EBADF;
        if (nr == SYS_fcntl && (a[1] == F_SETLKW || a[1] == F_OFD_SETLKW))
            return dl_blocking(nr, a, context);
        if (nr == SYS_fcntl && a[1] != F_DUPFD && a[1] != F_DUPFD_CLOEXEC && a[1] != F_SETFD)
            return DL_CALL(nr, a[0], a[1], a[2], a[3], a[4], a[5]);
        dl_lock(&dl_files_lock);
        if (dl_internal_fd((int)a[0])) { dl_unlock(&dl_files_lock); return -EBADF; }
        r = DL_CALL(nr, a[0], a[1], a[2], a[3], a[4], a[5]);
        dl_unlock(&dl_files_lock); return r;
    case SYS_brk: return locked_brk(a[0]);
    case SYS_mmap:
        // File mappings preserve lazy paging, EOF/SIGBUS and MAP_SHARED semantics.
        // SELinux/mmap policy is authoritative; no execve of app-data ELF occurs.
        return dl_mmap(a[0], a[1], a[2], a[3], a[4], a[5]);
    case SYS_munmap: return dl_munmap(a[0], a[1]);
    case SYS_madvise: return dl_madvise(a[0], a[1], (int)a[2]);
    case SYS_rt_sigaction: return dl_signal_action(a);
    case SYS_rt_sigreturn: dl_signal_return(context);
    case SYS_rt_sigprocmask:
        {
            if (a[3] != 8) return -EINVAL;
            unsigned long mask = 0, old;
            unsigned long bit = 1UL << (SIGSYS - 1);
            // The signal frame contains the guest mask. Changing the handler's
            // kernel mask alone would be undone by rt_sigreturn.
            dl_copy(&old, &context->uc_sigmask, 8);
            struct dl_thread *thread = dl_thread_self();
            old = (old & ~bit) | thread->signals.virtual_sigsys_mask;
            if (a[1]) { r = dl_read_memory(&mask, (void *)a[1], 8); if (r) return r; }
            if (a[1]) {
                if (a[0] == SIG_BLOCK) mask |= old;
                else if (a[0] == SIG_UNBLOCK) mask = old & ~mask;
                else if (a[0] != SIG_SETMASK) return -EINVAL;
                mask &= ~((1UL << (SIGKILL - 1)) | (1UL << (SIGSTOP - 1)));
                thread->signals.virtual_sigsys_mask = mask & bit;
                mask &= ~bit;
                dl_copy(&context->uc_sigmask, &mask, 8);
            }
            return a[2] ? dl_write_memory((void *)a[2], &old, 8) : 0;
        }
    case SYS_rt_sigsuspend: case SYS_rt_sigtimedwait:
    case SYS_ppoll: case SYS_pselect6: case SYS_epoll_pwait: case SYS_epoll_pwait2:
        return dl_signal_wait(nr, a, context);
    case SYS_clone:
        if (a[0] & CLONE_THREAD) {
            if (dl_state.vfork_fd >= 0) return -EOPNOTSUPP;
            return dl_thread_clone(a, context);
        }
        return guest_clone(a, context->uc_mcontext.sp);
    case SYS_execve:
        {
            struct dl_entry entry;
            r = dl_exec_begin(); if (r) return r;
            r = dl_exec((void *)a[0], (void *)a[1], (void *)a[2], &entry, 1);
            dl_exec_end();
            if (r) return r;
            dl_signal_collect(0);
            // The signal frame is on the surviving runtime alternate stack.
            // sigreturn restores the guest mask and enters the new image.
            for (unsigned i = 0; i < 31; i++) context->uc_mcontext.regs[i] = 0;
            context->uc_mcontext.pc = entry.pc; context->uc_mcontext.sp = entry.sp;
            context->uc_mcontext.pstate = 0;
            struct fpsimd_context *fp = (void *)context->uc_mcontext.__reserved;
            if (fp->head.magic == FPSIMD_MAGIC) {
                fp->fpsr = fp->fpcr = 0;
                for (unsigned i = 0; i < sizeof(fp->vregs); i++) ((unsigned char *)fp->vregs)[i] = 0;
            }
            __asm__ volatile("msr tpidr_el0, xzr" ::: "memory");
            return 0;
        }
    case SYS_execveat: case SYS_sigaltstack:
        return -EOPNOTSUPP;
    case SYS_exit: dl_thread_exit((int)a[0]);
    case SYS_getdents64: return dl_getdents((int)a[0], (void *)a[1], (unsigned)a[2]);
    case SYS_read: case SYS_write: case SYS_readv: case SYS_writev:
    case SYS_pread64: case SYS_pwrite64: case SYS_lseek: case SYS_fstat:
    case SYS_ioctl: case SYS_pipe2:
    case SYS_fchmod: case SYS_fchown: case SYS_ftruncate: case SYS_fstatfs:
    case SYS_umask: case SYS_sendfile: case SYS_copy_file_range:
    case SYS_fgetxattr: case SYS_fsetxattr: case SYS_flistxattr: case SYS_fremovexattr:
    case SYS_wait4: case SYS_waitid:
    case SYS_nanosleep: case SYS_clock_nanosleep:
    case SYS_getpgid: case SYS_setpgid: case SYS_getsid: case SYS_setsid:
    case SYS_mprotect: case SYS_mincore:
    case SYS_futex: case SYS_set_tid_address: case SYS_set_robust_list:
    case SYS_getpid: case SYS_getppid: case SYS_gettid:
    case SYS_tgkill: case SYS_kill: case SYS_rt_sigpending:
    case SYS_getuid: case SYS_geteuid: case SYS_getgid: case SYS_getegid:
    case SYS_getrandom: case SYS_clock_gettime: case SYS_clock_getres:
    case SYS_prlimit64: case SYS_uname: case SYS_getrusage:
    case SYS_exit_group:
    case SYS_fsync: case SYS_fdatasync:
        return DL_CALL(nr, a[0], a[1], a[2], a[3], a[4], a[5]);
    default: return -ENOSYS;
    }
}

static void trace_number(long nr, long result)
{
    char out[80]; size_t n = 0;
    const char prefix[] = "DL syscall "; dl_copy(out, prefix, sizeof(prefix) - 1); n = sizeof(prefix) - 1;
    long values[2] = {nr, result};
    for (int i = 0; i < 2; i++) {
        unsigned long value = values[i] < 0 ? 0UL - (unsigned long)values[i] : (unsigned long)values[i];
        if (values[i] < 0) out[n++] = '-';
        char digits[24]; size_t k = 0;
        do { digits[k++] = '0' + value % 10; value /= 10; } while (value);
        while (k) out[n++] = digits[--k];
        out[n++] = i ? '\n' : ' ';
    }
    DL_CALL(SYS_write, 2, out, n, 0, 0, 0);
}

static void dispatch(int signal, siginfo_t *info, void *context)
{
    if (signal != SIGSYS || info->si_code != SYS_SECCOMP || info->si_arch != AUDIT_ARCH_AARCH64 || info->si_errno != DL_TAG) {
        const char text[] = "Direct Linux: unsupported external SIGSYS\n";
        DL_CALL(SYS_write, 2, text, sizeof(text) - 1, 0, 0, 0);
        DL_CALL(SYS_exit_group, 159, 0, 0, 0, 0, 0); return;
    }
    ucontext_t *state = context;
    dl_signal_collect(state->uc_mcontext.sp);
    unsigned long args[6];
    for (int i = 0; i < 6; i++) args[i] = state->uc_mcontext.regs[i];
    unsigned long before = 0;
    if (dl_state.startup_profile) {
        before = startup_clock();
        if (info->si_syscall == SYS_exit || info->si_syscall == SYS_exit_group) startup_report(before);
    }
    long result = handle(info->si_syscall, args, state);
    if (dl_state.startup_profile) {
        unsigned long after = startup_clock();
        if (info->si_syscall == SYS_execve && !result) startup_exec(before, after);
        else if ((unsigned)info->si_syscall < 512) {
            startup_calls[info->si_syscall].count++;
            startup_calls[info->si_syscall].ns += after - before;
        }
    }
    if (dl_state.trace) trace_number(info->si_syscall, result);
    state->uc_mcontext.regs[0] = result;
}

int dl_install(void *signal_stack, size_t size)
{
    stack_t stack = {.ss_sp = signal_stack, .ss_size = size};
    struct { void (*handler)(int, siginfo_t *, void *); unsigned long flags; void (*restorer)(void); unsigned long mask; }
        action = {dispatch, SA_SIGINFO | SA_ONSTACK | 0x04000000UL, dl_sigreturn, ~0UL};
    long r = DL_CALL(SYS_sigaltstack, &stack, NULL, 0, 0, 0, 0); if (r) return (int)r;
    r = DL_CALL(SYS_rt_sigaction, SIGSYS, &action, NULL, 8, 0, 0); if (r) return (int)r;
    unsigned long bit = 1UL << (SIGSYS - 1);
    r = DL_CALL(SYS_rt_sigprocmask, SIG_UNBLOCK, &bit, NULL, 8, 0, 0); if (r) return (int)r;
    struct sock_filter filters[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, arch)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, AUDIT_ARCH_AARCH64, 1, 0),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_KILL_PROCESS),
#define RUNTIME_SITE(site) \
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, instruction_pointer) + 4), \
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, (uintptr_t)(site) >> 32, 0, 3), \
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, instruction_pointer)), \
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, (uint32_t)(uintptr_t)(site), 0, 1), \
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW)
        RUNTIME_SITE(dl_host_return), RUNTIME_SITE(dl_sigreturn_return), RUNTIME_SITE(dl_resume_return),
#undef RUNTIME_SITE
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
        // Only inheritance-changing hints need ownership bookkeeping. Keep
        // ordinary allocator advice on its existing native fast path. The
        // kernel advice argument is an int, so inspect its low 32 bits.
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_madvise, 0, 5),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args[2])),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, MADV_DONTFORK, 1, 0),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, MADV_DOFORK, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP | DL_TAG),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
        // Untranslated I/O stays outside signal handling, retaining normal
        // blocking/EINTR behavior and avoiding one signal per read/write.
#define DIRECT(n) BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, (n), 0, 1), \
    BPF_STMT(BPF_RET | BPF_K, dl_state.startup_profile && ((n) == SYS_exit || (n) == SYS_exit_group) \
        ? SECCOMP_RET_TRAP | DL_TAG : SECCOMP_RET_ALLOW)
        // These calls are already forwarded unchanged by handle(). Keeping
        // them native avoids a SIGSYS frame during every libc startup and
        // preserves the caller's mask for potentially blocking getrandom.
        DIRECT(SYS_mprotect), DIRECT(SYS_mincore),
        DIRECT(SYS_set_tid_address), DIRECT(SYS_set_robust_list),
        DIRECT(SYS_getpid), DIRECT(SYS_getppid), DIRECT(SYS_gettid),
        DIRECT(SYS_getuid), DIRECT(SYS_geteuid), DIRECT(SYS_getgid), DIRECT(SYS_getegid),
        DIRECT(SYS_getrandom), DIRECT(SYS_prlimit64), DIRECT(SYS_uname), DIRECT(SYS_getrusage),
        DIRECT(SYS_read), DIRECT(SYS_write),
        DIRECT(SYS_readv), DIRECT(SYS_writev), DIRECT(SYS_pread64), DIRECT(SYS_pwrite64),
        DIRECT(SYS_lseek), DIRECT(SYS_fstat),
        // Terminal ioctls must retain the guest signal mask: background
        // tcsetpgrp/tcsetattr can stop with SIGTTOU, and drains can block.
        DIRECT(SYS_ioctl),
        // Operations with no socket names retain native signal/blocking behavior.
        // Input/output addresses are translated in dl_network when present.
        DIRECT(SYS_listen),
        DIRECT(SYS_getsockopt), DIRECT(SYS_setsockopt), DIRECT(SYS_shutdown),
        DIRECT(SYS_epoll_create1), DIRECT(SYS_epoll_ctl),
        DIRECT(SYS_eventfd2),
        DIRECT(SYS_fsync), DIRECT(SYS_fdatasync), DIRECT(SYS_futex),
        DIRECT(SYS_sendfile), DIRECT(SYS_copy_file_range),
        DIRECT(SYS_clock_gettime), DIRECT(SYS_clock_getres),
        DIRECT(SYS_exit_group),
        DIRECT(SYS_wait4), DIRECT(SYS_waitid),
        DIRECT(SYS_nanosleep), DIRECT(SYS_clock_nanosleep),
#undef DIRECT
        // Bulk connected I/O and accept without a peer address need no name
        // translation. Check both halves of the pointer before allowing them.
#define DIRECT_NULL(n,index) \
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, (n), 0, 5), \
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args[index])), \
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, 0, 0, 3), \
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args[index]) + 4), \
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, 0, 0, 1), \
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW), \
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr))
        DIRECT_NULL(SYS_recvfrom, 4), DIRECT_NULL(SYS_sendto, 4),
        DIRECT_NULL(SYS_accept, 1), DIRECT_NULL(SYS_accept4, 1),
        // Mask-bearing waits must never block the private SIGSYS transport.
        // Ordinary no-mask waits keep the native fast path and signal delivery.
        DIRECT_NULL(SYS_ppoll, 3), DIRECT_NULL(SYS_pselect6, 5),
        DIRECT_NULL(SYS_epoll_pwait, 4), DIRECT_NULL(SYS_epoll_pwait2, 4),
#undef DIRECT_NULL
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP | DL_TAG),
    };
    struct sock_fprog program = {sizeof(filters) / sizeof(filters[0]), filters};
    r = DL_CALL(SYS_prctl, PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0, 0); if (r) return (int)r;
    return (int)DL_CALL(SYS_prctl, PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &program, 0, 0, 0);
}
