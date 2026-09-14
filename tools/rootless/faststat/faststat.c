/* SPDX-License-Identifier: GPL-2.0-or-later
 * Experimental glibc/AArch64 dirfd-relative stat dispatcher. Unsupported cases
 * execute the original syscall at an ordinary (PRoot-traced) call site.
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <linux/openat2.h>
#include <stdint.h>
#include <stdatomic.h>
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
static _Atomic unsigned long hits;
static _Atomic unsigned long fallbacks;

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
    const char *disabled = getenv("PROOT_FASTSTAT_DISABLE");
    if (disabled && strcmp(disabled, "1") == 0) { initialization_status = 2; goto out; }
    /* Requires the matching experimental tracer, seccomp acceleration,
     * no fake-root/link2symlink, and exactly the root binding. */
    long version = syscall(SYS_prctl, PROOT_FASTSTAT_QUERY, 0, 0, 0, 0);
    initialization_status = version < 0 ? -errno : 3;
    if (version != PROOT_FASTSTAT_VERSION) goto out;
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
    enabled = 1;
    initialization_status = 1;
out:
    errno = saved_errno;
}

/* Exported for the test driver; not an application-facing runtime API. */
int proot_faststat_enabled(void) { return enabled; }
int proot_faststat_initialization_status(void) { return initialization_status; }
unsigned long proot_faststat_hits(void) { return atomic_load_explicit(&hits, memory_order_relaxed); }
unsigned long proot_faststat_fallbacks(void) { return atomic_load_explicit(&fallbacks, memory_order_relaxed); }

static int copy_path(char output[PATH_MAX], const char *input)
{
    struct iovec local = { output, PATH_MAX };
    struct iovec remote = { (void *)input, PATH_MAX };
    /* A faulting pointer must produce the original syscall's EFAULT, not a
     * SIGSEGV from interposition. Partial reads up to a mapped boundary work. */
    long length = syscall(SYS_process_vm_readv, getpid(), &local, 1, &remote, 1, 0);
    return length > 0 && memchr(output, '\0', (size_t)length) != NULL;
}

int fstatat(int dirfd, const char *path, struct stat *output, int flags)
{
    int saved_errno = errno;
    char copied[PATH_MAX];
    struct stat result;
    /* PRoot emulates cwd without changing the kernel's cwd. A real directory
     * FD is mandatory; AT_FDCWD must never reach our raw openat2. */
    if (!enabled || dirfd < 0 || (flags != 0 && flags != AT_SYMLINK_NOFOLLOW)) goto slow;
    if (!copy_path(copied, path) || !copied[0] || copied[0] == '/') goto slow;

    /* Every component is checked by the kernel at resolution time. Reject
     * all symlinks (including a link2symlink object), and escape via '..'.
     * Rejections fall back; they are never exposed as new guest errors. */
    struct open_how how = { .flags = O_PATH | O_CLOEXEC,
        .resolve = RESOLVE_BENEATH | RESOLVE_NO_SYMLINKS };
    long fd = fast_syscall(SYS_openat2, dirfd, (long)copied, (long)&how, sizeof(how));
    if (fd < 0) goto slow;
    long status = fast_syscall(SYS_fstat, fd, (long)&result, 0, 0);
    fast_syscall(SYS_close, fd, 0, 0, 0);
    if (status < 0) goto slow;
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
