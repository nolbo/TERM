/* SPDX-License-Identifier: Apache-2.0 */
/* Everything reachable after dl_enter is libc/TLS/allocator independent. */
#define _GNU_SOURCE
#include "runtime.h"
#include <errno.h>
#include <fcntl.h>
#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <signal.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <ucontext.h>
#include <unistd.h>

#define DL_TAG 0xd1
#define DL_DEPTH 256
struct dl_state dl_state;

size_t dl_length(const char *s) { size_t n = 0; while (s[n]) n++; return n; }
void dl_copy(void *d, const void *s, size_t n) {
    unsigned char *to = d; const unsigned char *from = s;
    for (size_t i = 0; i < n; i++) to[i] = from[i];
}
int dl_equal(const char *a, const char *b) {
    size_t i = 0; while (a[i] && a[i] == b[i]) i++; return a[i] == b[i];
}

static long memory_copy(void *d, const void *s, size_t n, int write)
{
    struct iovec local = {write ? (void *)s : d, n};
    struct iovec remote = {write ? d : (void *)s, n};
    long pid = DL_CALL(SYS_getpid, 0, 0, 0, 0, 0, 0);
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

static void fd_name(char *out, int fd)
{
    const char prefix[] = "/proc/self/fd/";
    size_t n = sizeof(prefix) - 1;
    dl_copy(out, prefix, n);
    char digits[16]; size_t count = 0;
    unsigned value = (unsigned)fd;
    do { digits[count++] = '0' + value % 10; value /= 10; } while (value);
    while (count) out[n++] = digits[--count];
    out[n] = 0;
}

static long virtual_path(int fd, char out[DL_PATH])
{
    char host[DL_PATH]; long n;
    if (fd == AT_FDCWD) {
        n = DL_CALL(SYS_getcwd, host, DL_PATH, 0, 0, 0, 0);
        if (n < 0) return n;
    } else {
        if (fd < 0) return -EBADF;
        struct stat st;
        n = DL_CALL(SYS_fstat, fd, &st, 0, 0, 0, 0);
        if (n < 0) return n;
        if (!S_ISDIR(st.st_mode)) return -ENOTDIR;
        char link[40]; fd_name(link, fd);
        n = DL_CALL(SYS_readlinkat, AT_FDCWD, link, host, DL_PATH - 1, 0, 0);
        if (n < 0) return n;
        if (n == DL_PATH - 1) return -ENAMETOOLONG;
        host[n] = 0;
    }
    size_t i = 0;
    while (dl_state.root[i] && dl_state.root[i] == host[i]) i++;
    if (dl_state.root[i] || (host[i] && host[i] != '/')) return -EXDEV;
    if (!host[i]) { out[0] = '/'; out[1] = 0; }
    else dl_copy(out, host + i, dl_length(host + i) + 1);
    return 0;
}
long dl_virtual_cwd(char out[DL_PATH]) { return virtual_path(AT_FDCWD, out); }

/* Resolve symlinks against the guest root using no-follow component handles.
 * No openat2, chroot, mount, hardlink or host absolute-symlink traversal.
 * Initial bounded implementation: 255 directory levels; no namespace binds. */
long dl_open(int dirfd, const char *path, int flags, unsigned mode)
{
    char pending[DL_PATH], base[DL_PATH], component[DL_PATH], target[DL_PATH];
    size_t length = dl_length(path);
    if (!length) return -ENOENT;
    if (length >= DL_PATH) return -ENAMETOOLONG;
    if (path[0] == '/') dl_copy(pending, path, length + 1);
    else {
        long r = virtual_path(dirfd, base); if (r) return r;
        size_t b = dl_length(base);
        if (b + length + 2 > DL_PATH) return -ENAMETOOLONG;
        dl_copy(pending, base, b); pending[b] = '/'; dl_copy(pending + b + 1, path, length + 1);
    }
    int parents[DL_DEPTH], depth = 0, links = 0;
    parents[0] = dl_state.root_fd;
    size_t position = 0;
    long result = -ENOENT;
    for (;;) {
        while (pending[position] == '/') position++;
        size_t start = position;
        while (pending[position] && pending[position] != '/') position++;
        size_t size = position - start;
        int trailing = pending[position] == '/';
        while (pending[position] == '/') position++;
        int last = !pending[position];
        if (!size) { component[0] = '.'; component[1] = 0; }
        else { dl_copy(component, pending + start, size); component[size] = 0; }
        if (dl_equal(component, "..")) {
            if (depth) DL_CALL(SYS_close, parents[depth--], 0, 0, 0, 0, 0);
            if (!last) continue;
            component[0] = '.'; component[1] = 0;
        }
        if (dl_equal(component, ".") && !last) continue;
        long pin = DL_CALL(SYS_openat, parents[depth], component, O_PATH | O_NOFOLLOW | O_CLOEXEC, 0, 0, 0);
        if (pin < 0) {
            if (pin == -ENOENT && last && !trailing && (flags & O_CREAT))
                result = DL_CALL(SYS_openat, parents[depth], component, flags | O_NOFOLLOW, mode, 0, 0);
            else result = pin;
            break;
        }
        struct stat st;
        result = DL_CALL(SYS_fstat, pin, &st, 0, 0, 0, 0);
        if (result < 0) { DL_CALL(SYS_close, pin, 0, 0, 0, 0, 0); break; }
        if (last && S_ISLNK(st.st_mode) && (flags & (O_CREAT | O_EXCL)) == (O_CREAT | O_EXCL)) {
            result = -EEXIST; DL_CALL(SYS_close, pin, 0, 0, 0, 0, 0); break;
        }
        if (S_ISLNK(st.st_mode) && (!last || trailing || !(flags & O_NOFOLLOW))) {
            if (++links > 40) { result = -ELOOP; DL_CALL(SYS_close, pin, 0, 0, 0, 0, 0); break; }
            long n = DL_CALL(SYS_readlinkat, pin, "", target, DL_PATH - 1, 0, 0);
            DL_CALL(SYS_close, pin, 0, 0, 0, 0, 0);
            if (n < 0) { result = n; break; }
            if (n == 0 || n == DL_PATH - 1) { result = n ? -ENAMETOOLONG : -ENOENT; break; }
            size_t rest = dl_length(pending + position);
            if ((size_t)n + 1 + rest + 1 > DL_PATH) { result = -ENAMETOOLONG; break; }
            target[n++] = '/'; dl_copy(target + n, pending + position, rest + 1);
            // Preserve the original final trailing-slash requirement, without
            // introducing one for a final symlink to an ordinary file.
            if (!rest && !trailing) target[n - 1] = 0;
            dl_copy(pending, target, dl_length(target) + 1); position = 0;
            if (target[0] == '/') while (depth) DL_CALL(SYS_close, parents[depth--], 0, 0, 0, 0, 0);
            continue;
        }
        if ((!last || trailing || (flags & O_DIRECTORY)) && !S_ISDIR(st.st_mode)) {
            result = -ENOTDIR; DL_CALL(SYS_close, pin, 0, 0, 0, 0, 0); break;
        }
        if (!last) {
            if (depth + 1 == DL_DEPTH) { result = -ENAMETOOLONG; DL_CALL(SYS_close, pin, 0, 0, 0, 0, 0); break; }
            parents[++depth] = (int)pin; continue;
        }
        if ((flags & O_PATH) && !(flags & ~(O_PATH | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC))) {
            if (!(flags & O_CLOEXEC)) DL_CALL(SYS_fcntl, pin, F_SETFD, 0, 0, 0, 0);
            result = pin;
        } else {
            DL_CALL(SYS_close, pin, 0, 0, 0, 0, 0);
            result = DL_CALL(SYS_openat, parents[depth], component,
                flags | O_NOFOLLOW | (trailing ? O_DIRECTORY : 0), mode, 0, 0);
        }
        break;
    }
    while (depth) DL_CALL(SYS_close, parents[depth--], 0, 0, 0, 0, 0);
    return result;
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

static long handle(long nr, unsigned long *a, ucontext_t *context)
{
    char path[DL_PATH]; long r, fd;
    switch (nr) {
    case SYS_openat:
        r = dl_string(path, (void *)a[1]); if (r) return r;
        if ((a[2] & O_TMPFILE) == O_TMPFILE) return -EOPNOTSUPP;
        return dl_open((int)a[0], path, (int)a[2], (unsigned)a[3]);
    case SYS_newfstatat:
    case SYS_statx:
        r = dl_string(path, (void *)a[1]); if (r) return r;
        {
            unsigned flags = nr == SYS_statx ? a[2] : a[3];
            unsigned allowed = AT_SYMLINK_NOFOLLOW | AT_EMPTY_PATH | AT_NO_AUTOMOUNT;
            if (nr == SYS_statx) allowed |= 0x6000; // statx sync flags, validated by kernel
            if (flags & ~allowed) return -EINVAL;
            if (!path[0] && (flags & AT_EMPTY_PATH))
                return DL_CALL(nr, a[0], a[1], a[2], a[3], a[4], a[5]);
            fd = dl_open((int)a[0], path, O_PATH | O_CLOEXEC | ((flags & AT_SYMLINK_NOFOLLOW) ? O_NOFOLLOW : 0), 0);
            if (fd < 0) return fd;
            r = nr == SYS_statx ? DL_CALL(SYS_statx, fd, "", (flags | AT_EMPTY_PATH) & ~AT_SYMLINK_NOFOLLOW, a[3], a[4], 0)
                : DL_CALL(SYS_fstat, fd, a[2], 0, 0, 0, 0);
            DL_CALL(SYS_close, fd, 0, 0, 0, 0, 0); return r;
        }
    case SYS_faccessat:
        r = dl_string(path, (void *)a[1]); if (r) return r;
        fd = dl_open((int)a[0], path, O_PATH | O_CLOEXEC, 0); if (fd < 0) return fd;
        fd_name(path, (int)fd);
        r = DL_CALL(SYS_faccessat, AT_FDCWD, path, a[2], 0, 0, 0);
        DL_CALL(SYS_close, fd, 0, 0, 0, 0, 0); return r;
    case SYS_readlinkat:
        r = dl_string(path, (void *)a[1]); if (r) return r;
        if (dl_equal(path, "/proc/self/exe")) {
            if (!a[3]) return -EINVAL;
            size_t size = dl_length(dl_state.executable); if (size > a[3]) size = a[3];
            r = dl_write_memory((void *)a[2], dl_state.executable, size); return r ? r : (long)size;
        }
        if (!path[0]) return DL_CALL(SYS_readlinkat, a[0], a[1], a[2], a[3], 0, 0);
        fd = dl_open((int)a[0], path, O_PATH | O_CLOEXEC | O_NOFOLLOW, 0); if (fd < 0) return fd;
        r = DL_CALL(SYS_readlinkat, fd, "", a[2], a[3], 0, 0);
        DL_CALL(SYS_close, fd, 0, 0, 0, 0, 0); return r;
    case SYS_getcwd:
        r = dl_virtual_cwd(path); if (r) return r;
        { size_t size = dl_length(path) + 1; if (size > a[1]) return -ERANGE;
          r = dl_write_memory((void *)a[0], path, size); return r ? r : (long)size; }
    case SYS_chdir:
        r = dl_string(path, (void *)a[0]); if (r) return r;
        fd = dl_open(AT_FDCWD, path, O_PATH | O_DIRECTORY | O_CLOEXEC, 0); if (fd < 0) return fd;
        r = DL_CALL(SYS_fchdir, fd, 0, 0, 0, 0, 0); DL_CALL(SYS_close, fd, 0, 0, 0, 0, 0); return r;
    case SYS_close:
        if ((int)a[0] == dl_state.root_fd) return -EBADF;
        return DL_CALL(nr, a[0], 0, 0, 0, 0, 0);
    case SYS_dup3:
        if ((int)a[1] == dl_state.root_fd) return -EBADF;
        return DL_CALL(nr, a[0], a[1], a[2], 0, 0, 0);
    case SYS_brk: return guest_brk(a[0]);
    case SYS_mmap:
        // File mappings preserve lazy paging, EOF/SIGBUS and MAP_SHARED semantics.
        // SELinux/mmap policy is authoritative; no execve of app-data ELF occurs.
        return DL_CALL(nr, a[0], a[1], a[2], a[3], a[4], a[5]);
    case SYS_rt_sigaction:
        if (a[0] == SIGSYS) return -EOPNOTSUPP;
        return DL_CALL(nr, a[0], a[1], a[2], a[3], 0, 0);
    case SYS_rt_sigprocmask:
        {
            if (a[3] != 8) return -EINVAL;
            unsigned long mask = 0, old;
            unsigned long bit = 1UL << (SIGSYS - 1);
            // The signal frame contains the guest mask. Changing the handler's
            // kernel mask alone would be undone by rt_sigreturn.
            dl_copy(&old, &context->uc_sigmask, 8);
            old = (old & ~bit) | dl_state.virtual_sigsys_mask;
            if (a[1]) { r = dl_read_memory(&mask, (void *)a[1], 8); if (r) return r; }
            if (a[1]) {
                if (a[0] == SIG_BLOCK) mask |= old;
                else if (a[0] == SIG_UNBLOCK) mask = old & ~mask;
                else if (a[0] != SIG_SETMASK) return -EINVAL;
                mask &= ~((1UL << (SIGKILL - 1)) | (1UL << (SIGSTOP - 1)));
                dl_state.virtual_sigsys_mask = mask & bit;
                mask &= ~bit;
                dl_copy(&context->uc_sigmask, &mask, 8);
            }
            return a[2] ? dl_write_memory((void *)a[2], &old, 8) : 0;
        }
    // Threads/exec require signal/TLS/state handoff, not a raw forwarding call.
    case SYS_clone: case SYS_execve: case SYS_execveat: case SYS_sigaltstack:
        return -EOPNOTSUPP;
    case SYS_read: case SYS_write: case SYS_readv: case SYS_writev:
    case SYS_pread64: case SYS_pwrite64: case SYS_lseek: case SYS_fstat:
    case SYS_fcntl: case SYS_ioctl: case SYS_dup:
    case SYS_munmap: case SYS_mprotect: case SYS_madvise:
    case SYS_futex: case SYS_set_tid_address: case SYS_set_robust_list:
    case SYS_getpid: case SYS_getppid: case SYS_gettid:
    case SYS_tgkill: case SYS_kill: case SYS_rt_sigpending:
    case SYS_getuid: case SYS_geteuid: case SYS_getgid: case SYS_getegid:
    case SYS_getrandom: case SYS_clock_gettime: case SYS_clock_getres:
    case SYS_prlimit64: case SYS_uname: case SYS_getrusage:
    case SYS_rt_sigreturn: case SYS_exit: case SYS_exit_group:
    case SYS_getdents64: case SYS_fsync: case SYS_fdatasync:
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
    unsigned long args[6];
    for (int i = 0; i < 6; i++) args[i] = state->uc_mcontext.regs[i];
    long result = handle(info->si_syscall, args, state);
    if (dl_state.trace) trace_number(info->si_syscall, result);
    state->uc_mcontext.regs[0] = result;
}

int dl_install(void *signal_stack, size_t size)
{
    stack_t stack = {.ss_sp = signal_stack, .ss_size = size};
    struct { void (*handler)(int, siginfo_t *, void *); unsigned long flags; void (*restorer)(void); unsigned long mask; }
        action = {dispatch, SA_SIGINFO | SA_ONSTACK | 0x04000000UL, dl_sigreturn, 0};
    long r = DL_CALL(SYS_sigaltstack, &stack, NULL, 0, 0, 0, 0); if (r) return (int)r;
    r = DL_CALL(SYS_rt_sigaction, SIGSYS, &action, NULL, 8, 0, 0); if (r) return (int)r;
    unsigned long bit = 1UL << (SIGSYS - 1);
    r = DL_CALL(SYS_rt_sigprocmask, SIG_UNBLOCK, &bit, NULL, 8, 0, 0); if (r) return (int)r;
    uintptr_t pc = (uintptr_t)dl_host_return;
    struct sock_filter filters[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, arch)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, AUDIT_ARCH_AARCH64, 1, 0),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_KILL_PROCESS),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, instruction_pointer) + 4),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, pc >> 32, 0, 3),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, instruction_pointer)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, (uint32_t)pc, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
        // Untranslated I/O stays outside signal handling, retaining normal
        // blocking/EINTR behavior and avoiding one signal per read/write.
#define DIRECT(n) BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, (n), 0, 1), BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW)
        DIRECT(SYS_rt_sigreturn), DIRECT(SYS_read), DIRECT(SYS_write),
        DIRECT(SYS_readv), DIRECT(SYS_writev), DIRECT(SYS_pread64), DIRECT(SYS_pwrite64),
        DIRECT(SYS_lseek), DIRECT(SYS_fstat), DIRECT(SYS_getdents64),
        DIRECT(SYS_fsync), DIRECT(SYS_fdatasync), DIRECT(SYS_futex),
        DIRECT(SYS_clock_gettime), DIRECT(SYS_clock_getres),
        DIRECT(SYS_exit), DIRECT(SYS_exit_group),
#undef DIRECT
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP | DL_TAG),
    };
    struct sock_fprog program = {sizeof(filters) / sizeof(filters[0]), filters};
    r = DL_CALL(SYS_prctl, PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0, 0); if (r) return (int)r;
    return (int)DL_CALL(SYS_prctl, PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &program, 0, 0, 0);
}
