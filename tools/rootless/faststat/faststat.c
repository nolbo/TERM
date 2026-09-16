/* SPDX-License-Identifier: GPL-2.0-or-later
 * Experimental glibc/AArch64 stat dispatcher. Unsupported cases
 * execute the original syscall at an ordinary (PRoot-traced) call site.
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <linux/openat2.h>
#include <linux/memfd.h>
#include <pthread.h>
#include <stdint.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <unistd.h>
#include "protocol.h"

#if !defined(__aarch64__) || !defined(__GLIBC__)
#error "The faststat experiment supports glibc on AArch64 only"
#endif
_Static_assert(sizeof(struct stat) == 128, "unsupported stat ABI");
_Static_assert(__builtin_offsetof(struct stat, st_mode) == 16, "stat mode ABI");
_Static_assert(__builtin_offsetof(struct stat, st_uid) == 24, "stat uid ABI");

/* Initialized before guest main/threads; no mutable metadata or FD cache. */
static int enabled;
static int initialization_status;
static int execution_mode;
static int report_on_exit;
static _Atomic unsigned long hits;
static _Atomic unsigned long fallbacks;
static _Atomic unsigned long negative_hits;
static _Atomic unsigned long fd_hits, fd_fallbacks;
static int fd_requested;
static pthread_key_t fd_key;
struct local_identity {
    pid_t pid;
    int busy;
    struct proot_fastfd_identity *page;
};
static _Thread_local struct local_identity local_identity;

static void release_identity(void *pointer)
{
    struct local_identity *local = pointer;
    if (local->page) munmap(local->page, PROOT_FASTFD_MAPPING_SIZE);
    local->page = NULL;
    local->pid = 0;
}


static long fast_syscall(long nr, long a, long b, long c, long d)
{
    register long x0 __asm__("x0") = a;
    register long x1 __asm__("x1") = b;
    register long x2 __asm__("x2") = c;
    register long x3 __asm__("x3") = d;
    register long x8 __asm__("x8") = nr;
    register uintptr_t x16 __asm__("x16") = PROOT_FASTSTAT_ADDRESS;
    __asm__ volatile("blr x16"
        : "+r"(x0), "+r"(x1), "+r"(x2), "+r"(x3), "+r"(x8), "+r"(x16)
        : : "x4", "x5", "x6", "x7", "x9", "x10", "x11", "x12",
            "x13", "x14", "x15", "x17", "x30", "cc", "memory");
    return x0;
}

__attribute__((constructor)) static void initialize_faststat(void)
{
    int saved_errno = errno;
    const char *report = getenv("PROOT_FASTSTAT_REPORT");
    report_on_exit = report && strcmp(report, "1") == 0;
    const char *disabled = getenv("PROOT_FASTSTAT_DISABLE");
    if (disabled && strcmp(disabled, "1") == 0) { initialization_status = 2; goto out; }
    /* Requires the matching experimental tracer, seccomp acceleration,
     * mode 1 has only a root binding; mode 2 resolves fresh tracer context. */
    long version = syscall(SYS_prctl, PROOT_FASTSTAT_QUERY, 0, 0, 0, 0);
    initialization_status = version < 0 ? -errno : 3;
    if (version != PROOT_FASTSTAT_VERSION && version != PROOT_FASTSTAT_CONTEXT_VERSION) goto out;
    execution_mode = (int)version;
    initialization_status = 4;
    void *page = mmap((void *)PROOT_FASTSTAT_ADDRESS, PROOT_FASTSTAT_MAPPING_SIZE,
                     PROT_READ | PROT_WRITE,
                     MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED_NOREPLACE, -1, 0);
    if (page == MAP_FAILED) { initialization_status = -errno; goto out; }
    if (page != (void *)PROOT_FASTSTAT_ADDRESS) {
        munmap(page, PROOT_FASTSTAT_MAPPING_SIZE);
        goto out;
    }
    const uint32_t instructions[] = { 0xd4000001, 0xd65f03c0 }; /* svc #0; ret */
    memcpy(page, instructions, sizeof(instructions));
    __builtin___clear_cache(page, (char *)page + sizeof(instructions));
    if (mprotect(page, PROOT_FASTSTAT_MAPPING_SIZE, PROT_READ | PROT_EXEC) != 0) {
        initialization_status = -errno;
        munmap(page, PROOT_FASTSTAT_MAPPING_SIZE);
        goto out;
    }
    /* Read-only availability probe; a denied/absent openat2 disables this DSO. */
    struct open_how how = { .flags = O_PATH | O_CLOEXEC,
        .resolve = RESOLVE_BENEATH | RESOLVE_NO_SYMLINKS };
    long fd = fast_syscall(SYS_openat2, AT_FDCWD, (long)".",
                          (long)&how, sizeof(how));
    if (fd < 0) {
        initialization_status = (int)fd;
        munmap(page, PROOT_FASTSTAT_MAPPING_SIZE);
        goto out;
    }
    fast_syscall(SYS_close, fd, 0, 0, 0);
    const char *fd_setting = getenv("PROOT_FASTFD");
    if (execution_mode == PROOT_FASTSTAT_CONTEXT_VERSION && fd_setting &&
        strcmp(fd_setting, "1") == 0 && pthread_key_create(&fd_key, release_identity) == 0)
        fd_requested = 1;
    enabled = 1;
    initialization_status = 1;
out:
    errno = saved_errno;
}

/* Exported for the test driver; not an application-facing runtime API. */
int proot_faststat_enabled(void) { return enabled; }
int proot_faststat_mode(void) { return execution_mode; }
int proot_faststat_initialization_status(void) { return initialization_status; }
unsigned long proot_faststat_hits(void) { return atomic_load_explicit(&hits, memory_order_relaxed); }
unsigned long proot_faststat_fallbacks(void) { return atomic_load_explicit(&fallbacks, memory_order_relaxed); }

unsigned long proot_fastfd_hits(void) { return atomic_load_explicit(&fd_hits, memory_order_relaxed); }
unsigned long proot_fastfd_fallbacks(void) { return atomic_load_explicit(&fd_fallbacks, memory_order_relaxed); }

unsigned long proot_faststat_negative_hits(void) { return atomic_load_explicit(&negative_hits, memory_order_relaxed); }

/* Diagnostic only: keep this off during speed measurements. */
__attribute__((destructor)) static void report_faststat(void)
{
    if (!report_on_exit) return;
    char buffer[192];
    int length = snprintf(buffer, sizeof(buffer),
        "{\"faststat\":{\"enabled\":%d,\"hits\":%lu,\"fallbacks\":%lu,\"negative_hits\":%lu}}\n",
        enabled, proot_faststat_hits(), proot_faststat_fallbacks(), proot_faststat_negative_hits());
    if (length > 0 && (size_t)length < sizeof(buffer))
        (void)syscall(SYS_write, STDERR_FILENO, buffer, (size_t)length);
}

static int copy_path(char output[PATH_MAX], const char *input)
{
    struct iovec local = { output, PATH_MAX };
    struct iovec remote = { (void *)input, PATH_MAX };
    /* A faulting pointer must produce the original syscall's EFAULT, not a
     * SIGSEGV from interposition. Partial reads up to a mapped boundary work. */
    long length = syscall(SYS_process_vm_readv, getpid(), &local, 1, &remote, 1, 0);
    return length > 0 && memchr(output, '\0', (size_t)length) != NULL;
}

/* A separate page per Linux thread matches fake_id0's per-thread saved IDs.
 * The PID check discards a fork child's inherited mapping before first use.
 * The tracer owns its own mapping; guest thread destructors cannot invalidate it. */
static struct proot_fastfd_identity *current_identity(pid_t pid)
{
    if (local_identity.busy) return NULL;
    if (local_identity.pid == pid) return local_identity.page;
    release_identity(&local_identity);
    local_identity.pid = pid;
    local_identity.busy = 1;
    int fd = syscall(SYS_memfd_create, "proot-fastfd", MFD_CLOEXEC | MFD_ALLOW_SEALING);
    void *page = MAP_FAILED;
    if (fd >= 0 && syscall(SYS_ftruncate, fd, PROOT_FASTFD_MAPPING_SIZE) == 0 &&
        fcntl(fd, F_ADD_SEALS, F_SEAL_GROW | F_SEAL_SHRINK | F_SEAL_SEAL) == 0) {
        page = mmap(NULL, PROOT_FASTFD_MAPPING_SIZE, PROT_READ, MAP_SHARED, fd, 0);
        if (page != MAP_FAILED && syscall(SYS_prctl, PROOT_FASTFD_ATTACH,
                fd, PROOT_FASTFD_VERSION, 0, 0) == 0 &&
                pthread_setspecific(fd_key, &local_identity) == 0)
            local_identity.page = page;
    }
    if (page != MAP_FAILED && !local_identity.page) munmap(page, PROOT_FASTFD_MAPPING_SIZE);
    if (fd >= 0) syscall(SYS_close, fd);
    local_identity.busy = 0;
    return local_identity.page;
}

static int map_identity(struct proot_fastfd_identity *page, struct stat *result)
{
    for (int attempt = 0; attempt < 4; attempt++) {
        uint32_t sequence = atomic_load(&page->sequence);
        if (sequence & 1) continue;
        uint32_t active = atomic_load(&page->active);
        uint32_t host_uid = atomic_load(&page->host_uid), host_gid = atomic_load(&page->host_gid);
        uint32_t guest_uid = atomic_load(&page->guest_uid), guest_gid = atomic_load(&page->guest_gid);
        if (sequence != atomic_load(&page->sequence)) continue;
        if (!active) return 0;
        if (result->st_uid == host_uid) result->st_uid = guest_uid;
        if (result->st_gid == host_gid) result->st_gid = guest_gid;
        return 1;
    }
    return 0;
}

int fstat(int fd, struct stat *output)
{
    int saved_errno = errno;
    if (!enabled || !fd_requested || local_identity.busy) goto slow_fd;
    pid_t pid = (pid_t)syscall(SYS_getpid);
    struct proot_fastfd_identity *identity = current_identity(pid);
    if (!identity) goto slow_fd;
    struct stat result;
    if (fast_syscall(SYS_fstat, fd, (long)&result, 0, 0) < 0 ||
        (!S_ISREG(result.st_mode) && !S_ISDIR(result.st_mode)) ||
        !map_identity(identity, &result)) goto slow_fd;
    struct iovec local = { &result, sizeof(result) }, remote = { output, sizeof(result) };
    if (syscall(SYS_process_vm_writev, pid, &local, 1, &remote, 1, 0) != sizeof(result)) goto slow_fd;
    atomic_fetch_add_explicit(&fd_hits, 1, memory_order_relaxed);
    errno = saved_errno;
    return 0;
 slow_fd:
    atomic_fetch_add_explicit(&fd_fallbacks, 1, memory_order_relaxed);
    errno = saved_errno;
    return (int)syscall(SYS_fstat, fd, output);
}

int fstat64(int fd, struct stat64 *output) { return fstat(fd, (struct stat *)output); }

int fstatat(int dirfd, const char *path, struct stat *output, int flags)
{
    int saved_errno = errno;
    char copied[PATH_MAX];
    struct stat result;
    /* PRoot's cwd is virtual. Mode 1 requires a real directory FD;
     * mode 2 obtains an absolute host path before issuing raw openat2. */
    if (!enabled || (flags != 0 && flags != AT_SYMLINK_NOFOLLOW)) goto slow;
    if (!copy_path(copied, path) || !copied[0]) goto slow;
    struct proot_faststat_context context = {0};
    const char *kernel_path = copied;
    int kernel_dirfd = dirfd;
    if (execution_mode == PROOT_FASTSTAT_CONTEXT_VERSION) {
        context.result_errno = -1; /* Detect a filtered request with no reply. */
        context.dirfd = dirfd;
        context.flags = flags;
        strcpy(context.path, copied);
        if (syscall(SYS_prctl, PROOT_FASTSTAT_RESOLVE, &context, sizeof(context),
                    PROOT_FASTSTAT_CONTEXT_VERSION, 0) != 0) goto slow;
        if (context.path[0] != '/' || !memchr(context.path, '\0', sizeof(context.path))) goto slow;
        if (context.result_errno == ENOENT && !context.emulated_nlink) {
            /* The tracer observed the missing final path in this request.
             * Never promote openat2 errors: a guest filter can invent those. */
            atomic_fetch_add_explicit(&negative_hits, 1, memory_order_relaxed);
            atomic_fetch_add_explicit(&hits, 1, memory_order_relaxed);
            errno = ENOENT;
            return -1;
        }
        if (context.result_errno != 0) goto slow;
        kernel_path = context.path;
        kernel_dirfd = AT_FDCWD; /* Resolved absolute host path, not virtual cwd. */
    } else if (dirfd < 0 || copied[0] == '/') goto slow;

    /* Every component is checked by the kernel at resolution time. Reject
     * symlinks; mode 1 additionally rejects escape from its directory FD.
     * Rejections fall back; they are never exposed as new guest errors. */
    struct open_how how = { .flags = O_PATH | O_CLOEXEC,
        .resolve = RESOLVE_NO_SYMLINKS |
            (execution_mode == PROOT_FASTSTAT_VERSION ? RESOLVE_BENEATH : 0) };
    long fd = fast_syscall(SYS_openat2, kernel_dirfd, (long)kernel_path, (long)&how, sizeof(how));
    if (fd < 0) goto slow;
    long status = fast_syscall(SYS_fstat, fd, (long)&result, 0, 0);
    fast_syscall(SYS_close, fd, 0, 0, 0);
    if (status < 0) goto slow;
    if (!S_ISREG(result.st_mode) && !S_ISDIR(result.st_mode)) goto slow;
    if (execution_mode == PROOT_FASTSTAT_CONTEXT_VERSION && context.emulated_nlink) {
        if (!S_ISREG(result.st_mode)) goto slow;
        result.st_nlink = context.emulated_nlink;
    }
    if (execution_mode == PROOT_FASTSTAT_CONTEXT_VERSION && context.fake_root) {
        if (result.st_uid == context.host_uid) result.st_uid = context.guest_uid;
        if (result.st_gid == context.host_gid) result.st_gid = context.guest_gid;
    }
    struct iovec local = { &result, sizeof(result) };
    struct iovec remote = { output, sizeof(result) };
    if (syscall(SYS_process_vm_writev, getpid(), &local, 1, &remote, 1, 0)
            != sizeof(result)) goto slow;
    errno = saved_errno;
    atomic_fetch_add_explicit(&hits, 1, memory_order_relaxed);
    return 0;
slow:
    atomic_fetch_add_explicit(&fallbacks, 1, memory_order_relaxed);
    errno = saved_errno;
    return (int)syscall(SYS_newfstatat, dirfd, path, output, flags);
}

int stat(const char *path, struct stat *output)
{ return fstatat(AT_FDCWD, path, output, 0); }
int lstat(const char *path, struct stat *output)
{ return fstatat(AT_FDCWD, path, output, AT_SYMLINK_NOFOLLOW); }

_Static_assert(sizeof(struct stat64) == sizeof(struct stat), "stat64 ABI");
int fstatat64(int fd, const char *path, struct stat64 *out, int flags)
{ return fstatat(fd, path, (struct stat *)out, flags); }
int stat64(const char *path, struct stat64 *out)
{ return fstatat(AT_FDCWD, path, (struct stat *)out, 0); }
int lstat64(const char *path, struct stat64 *out)
{ return fstatat(AT_FDCWD, path, (struct stat *)out, AT_SYMLINK_NOFOLLOW); }
