/* SPDX-License-Identifier: GPL-2.0-or-later
 * Experimental glibc/AArch64 stat dispatcher. Unsupported cases
 * execute the original syscall at an ordinary (PRoot-traced) call site.
 */
#define _GNU_SOURCE
#include <errno.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <limits.h>
#include <linux/openat2.h>
#include <linux/magic.h>
#include <linux/memfd.h>
#include <pthread.h>
#include <signal.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/vfs.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <unistd.h>
#include "protocol.h"
#include "open-nofollow.h"

#if !defined(__aarch64__) || !defined(__GLIBC__)
#error "The faststat experiment supports glibc on AArch64 only"
#endif
_Static_assert(sizeof(struct stat) == 128, "unsupported stat ABI");
_Static_assert(__builtin_offsetof(struct stat, st_mode) == 16, "stat mode ABI");
_Static_assert(__builtin_offsetof(struct stat, st_uid) == 24, "stat uid ABI");

/* Initialized before guest main/threads; no mutable metadata or FD cache. */
static int enabled;
static int path_enabled = 1;
static int initialization_status;
static int execution_mode;
static int report_on_exit;
static int report_fd = STDERR_FILENO;
static _Atomic unsigned long hits;
static _Atomic unsigned long fallbacks;
static _Atomic unsigned long negative_hits;
static _Atomic unsigned long fd_hits, fd_fallbacks;
static int fd_requested;
static int open_requested;
static int create_requested;
static int unlink_requested;
static int shared_stat_requested;
static int close_requested;
static int (*libc_close)(int);
static int (*libc_statx)(int, const char *, int, unsigned, struct statx *);
static _Atomic unsigned long close_hits;
static _Atomic unsigned long unlink_hits;
static _Atomic unsigned long create_hits;
static int (*libc_openat)(int, const char *, int, ...);
static _Atomic unsigned long open_hits, storage_hits;
static pthread_key_t fd_key;
struct local_identity {
    pid_t pid;
    _Atomic int busy;
    struct proot_fastfd_identity *page;
};
/* This DSO is preloaded at startup. Avoid dynamic TLS allocation in a signal. */
static _Thread_local struct local_identity local_identity
    __attribute__((tls_model("initial-exec")));

static void release_identity(void *pointer)
{
    struct local_identity *local = pointer;
    if (local->page) munmap(local->page, PROOT_FASTFD_MAPPING_SIZE);
    local->page = NULL;
    local->pid = 0;
}

/* A signal arriving after munmap must not reuse the retiring TLS page. */
static void destroy_identity(void *pointer)
{
    struct local_identity *local = pointer;
    atomic_store_explicit(&local->busy, 1, memory_order_relaxed);
    release_identity(local);
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

/* statx has five arguments; retain its mask and output pointer verbatim. */
static long fast_statx(int fd, int flags, unsigned mask, struct statx *output)
{
    register long x0 __asm__("x0") = fd;
    register const char *x1 __asm__("x1") = "";
    register long x2 __asm__("x2") = flags;
    register long x3 __asm__("x3") = mask;
    register struct statx *x4 __asm__("x4") = output;
    register long x8 __asm__("x8") = SYS_statx;
    register uintptr_t x16 __asm__("x16") = PROOT_FASTSTAT_ADDRESS;
    __asm__ volatile("blr x16"
        : "+r"(x0), "+r"(x1), "+r"(x2), "+r"(x3), "+r"(x4), "+r"(x8), "+r"(x16)
        : : "x5", "x6", "x7", "x9", "x10", "x11", "x12", "x13", "x14",
            "x15", "x17", "x30", "cc", "memory");
    return x0;
}

/* Call with signals blocked. Exec gives this task a private descriptor table;
 * the tracer revokes eligibility before any clone can create another user of
 * that table. Thus a temporary pin or identity memfd cannot enter a fork
 * child's table and be abandoned by a vanished sibling thread. */
static int temporary_fds_safe(pid_t pid)
{
    if (local_identity.pid == pid && local_identity.page)
        return atomic_load(&local_identity.page->pin_safe);
    return syscall(SYS_prctl, PROOT_FASTFD_ATTACH, -1L, PROOT_FASTFD_VERSION, 0, 0)
        == PROOT_FASTFD_PIN_ALLOWED;
}

__attribute__((constructor)) static void initialize_faststat(void)
{
    int saved_errno = errno;
    libc_openat = dlsym(RTLD_NEXT, "openat");
    libc_statx = dlsym(RTLD_NEXT, "statx");
    libc_close = dlsym(RTLD_NEXT, "close");
    const char *path_setting = getenv("PROOT_FASTSTAT_PATH");
    path_enabled = !path_setting || strcmp(path_setting, "0") != 0;
    const char *report = getenv("PROOT_FASTSTAT_REPORT");
    report_on_exit = report && strcmp(report, "1") == 0;
    const char *report_target = getenv("PROOT_FASTSTAT_REPORT_FD");
    if (report_on_exit && report_target) {
        char *end;
        long value = strtol(report_target, &end, 10);
        if (*report_target && !*end && value >= 3 && value <= 1023) report_fd = (int)value;
    }
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
    /* Use only Android-allowed syscalls, including this startup probe. */
    struct open_how how = { .flags = O_PATH | O_CLOEXEC,
        .resolve = RESOLVE_BENEATH | RESOLVE_NO_SYMLINKS };
    uint64_t blocked = UINT64_MAX, previous;
    if (syscall(SYS_rt_sigprocmask, SIG_SETMASK, &blocked, &previous, sizeof(previous))) {
        initialization_status = -errno;
        munmap(page, PROOT_FASTSTAT_MAPPING_SIZE);
        goto out;
    }
    if (!temporary_fds_safe(syscall(SYS_getpid))) {
        syscall(SYS_rt_sigprocmask, SIG_SETMASK, &previous, NULL, sizeof(previous));
        initialization_status = 5;
        munmap(page, PROOT_FASTSTAT_MAPPING_SIZE);
        goto out;
    }
    long fd = proot_pin_nofollow(fast_syscall, AT_FDCWD, ".", how.flags);
    if (fd >= 0) fast_syscall(SYS_close, fd, 0, 0, 0);
    syscall(SYS_rt_sigprocmask, SIG_SETMASK, &previous, NULL, sizeof(previous));
    if (fd < 0) {
        initialization_status = (int)fd;
        munmap(page, PROOT_FASTSTAT_MAPPING_SIZE);
        goto out;
    }
    const char *fd_setting = getenv("PROOT_FASTFD");
    if (execution_mode == PROOT_FASTSTAT_CONTEXT_VERSION && fd_setting &&
        strcmp(fd_setting, "1") == 0 && pthread_key_create(&fd_key, destroy_identity) == 0) {
        /* glibc NPTL embeds its first 32 keys in each pthread descriptor.
         * Higher keys can allocate inside pthread_setspecific, which is unsafe
         * if first fstat happens in a handler interrupting the allocator.
         * This implementation-specific condition is documented in README. */
        if (fd_key < 32) fd_requested = 1;
        else pthread_key_delete(fd_key);
    }
    const char *close_setting = getenv("PROOT_FASTCLOSE");
    close_requested = fd_requested && close_setting && !strcmp(close_setting, "1");
    const char *open_setting = getenv("PROOT_FASTOPEN");
    open_requested = fd_requested && open_setting && !strcmp(open_setting, "1");
    const char *create_setting = getenv("PROOT_FASTCREATE");
    create_requested = open_requested && create_setting && !strcmp(create_setting, "1");
    const char *unlink_setting = getenv("PROOT_FASTUNLINK");
    unlink_requested = open_requested && unlink_setting && !strcmp(unlink_setting, "1");
    const char *shared_stat = getenv("PROOT_FASTSTAT_SHARED");
    shared_stat_requested = fd_requested && shared_stat && !strcmp(shared_stat, "1");
    enabled = 1;
    initialization_status = 1;
out:
    errno = saved_errno;
}

/* Exported for the test driver; not an application-facing runtime API. */
int proot_faststat_enabled(void) { return enabled; }
int proot_faststat_path_enabled(void) { return enabled && path_enabled; }
int proot_faststat_mode(void) { return execution_mode; }
int proot_faststat_initialization_status(void) { return initialization_status; }
unsigned long proot_faststat_hits(void) { return atomic_load_explicit(&hits, memory_order_relaxed); }
unsigned long proot_faststat_fallbacks(void) { return atomic_load_explicit(&fallbacks, memory_order_relaxed); }

int proot_fastfd_enabled(void) { return fd_requested; }
unsigned long proot_fastfd_hits(void) { return atomic_load_explicit(&fd_hits, memory_order_relaxed); }
unsigned long proot_fastfd_fallbacks(void) { return atomic_load_explicit(&fd_fallbacks, memory_order_relaxed); }

unsigned long proot_faststat_negative_hits(void) { return atomic_load_explicit(&negative_hits, memory_order_relaxed); }

/* Diagnostic only: keep this off during speed measurements. */
__attribute__((destructor)) static void report_faststat(void)
{
    if (!report_on_exit) return;
    char buffer[384];
    int length = snprintf(buffer, sizeof(buffer),
        "{\"faststat\":{\"enabled\":%d,\"hits\":%lu,\"fallbacks\":%lu,\"negative_hits\":%lu,\"fd_hits\":%lu,\"fd_fallbacks\":%lu,\"open_hits\":%lu,\"create_hits\":%lu,\"unlink_hits\":%lu}}\n",
        enabled, proot_faststat_hits(), proot_faststat_fallbacks(), proot_faststat_negative_hits(),
        proot_fastfd_hits(), proot_fastfd_fallbacks(), atomic_load(&open_hits), atomic_load(&create_hits), atomic_load(&unlink_hits));
    if (length > 0 && (size_t)length < sizeof(buffer))
        (void)syscall(SYS_write, report_fd, buffer, (size_t)length);
}

static int copy_path(char output[PATH_MAX], const char *input, pid_t pid)
{
    /* Most guest names fit in 128 bytes. Avoid copying a full page (and
     * touching the next page) for those names, without dereferencing input.
     * Long names retain PATH_MAX coverage and original EFAULT fallback. */
    const size_t first = 128;
    struct iovec local = { output, first };
    struct iovec remote = { (void *)input, first };
    long length = syscall(SYS_process_vm_readv, pid, &local, 1, &remote, 1, 0);
    if (length <= 0) return 0;
    if (memchr(output, '\0', (size_t)length)) return 1;
    if (length != (long)first || (uintptr_t)input > UINTPTR_MAX - first) return 0;
    local = (struct iovec){ output + first, PATH_MAX - first };
    remote = (struct iovec){ (void *)((uintptr_t)input + first), PATH_MAX - first };
    length = syscall(SYS_process_vm_readv, pid, &local, 1, &remote, 1, 0);
    return length > 0 && memchr(output + first, '\0', (size_t)length) != NULL;
}

/* A separate page per Linux thread matches fake_id0's per-thread saved IDs.
 * The PID check discards a fork child's inherited mapping before first use.
 * The tracer owns its own mapping; guest thread destructors cannot invalidate it. */
static struct proot_fastfd_identity *current_identity(pid_t pid)
{
    if (local_identity.pid == pid) return local_identity.page;
    /* Keep temporary resources out of signal-driven nonlocal exits. The
     * AArch64 kernel signal set is 64 bits, unlike glibc's padded sigset_t.
     * This runs only once per thread/fork, not on the steady-state path. */
    uint64_t blocked = UINT64_MAX, previous;
    if (syscall(SYS_rt_sigprocmask, SIG_SETMASK, &blocked, &previous, sizeof(previous))) return NULL;
    release_identity(&local_identity);
    local_identity.pid = pid;
    if (!temporary_fds_safe(pid)) {
        syscall(SYS_rt_sigprocmask, SIG_SETMASK, &previous, NULL, sizeof(previous));
        return NULL;
    }
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
    /* At this point every resource is closed or owned by the TLS destructor.
     * A pending handler may jump away during mask restoration. */
    syscall(SYS_rt_sigprocmask, SIG_SETMASK, &previous, NULL, sizeof(previous));
    return local_identity.page;
}

/* Diagnostic publication generation; does not initialize or alter state. */
unsigned long proot_fastpath_generation(void)
{
    return local_identity.page ? atomic_load(&local_identity.page->paths.sequence) : 0;
}

/* Snapshot lookup only; the open dispatcher validates the same sequence
 * after its kernel operation. No inode or existence is cached. */
static int lookup_shared_path(struct proot_fastpath_rules *rules, const char *path,
                              char result[PATH_MAX], size_t *anchor_length)
{
    if (path[0] != '/') return 0;
    for (const char *part = path + 1; *part;) {
        const char *end = strchr(part, '/');
        size_t length = end ? (size_t)(end - part) : strlen(part);
        if (!strncmp(part, ".l2s.", 5) || !strncmp(part, ".proot.l2s.", 11)) return 0;
        if (!length || (length == 1 && part[0] == '.') ||
            (length == 2 && part[0] == '.' && part[1] == '.')) return 0;
        if (!end) break;
        part = end + 1;
    }
    uint32_t sequence = atomic_load(&rules->sequence);
    if ((sequence & 1) || !atomic_load(&rules->active)) return 0;
    uint32_t count = atomic_load(&rules->count);
    if (!count || count > PROOT_FASTPATH_BINDINGS) return 0;
    uint32_t selected = count, longest = 0;
    size_t length = strlen(path);
    for (uint32_t i = 0; i < count; i++) {
        const struct proot_fastpath_binding *item = &rules->bindings[i];
        uint32_t offset = atomic_load(&item->guest), size = atomic_load(&item->guest_length);
        if (!size || size >= PATH_MAX || offset >= PROOT_FASTPATH_BYTES ||
            size >= PROOT_FASTPATH_BYTES - offset) return 0;
        char guest[PATH_MAX];
        for (uint32_t j = 0; j <= size; j++) guest[j] = atomic_load_explicit(&rules->paths[offset + j], memory_order_relaxed);
        if (guest[size] || guest[0] != '/') return 0;
        int matches = size == 1 || (length >= size && !memcmp(path, guest, size) &&
                                   (!path[size] || path[size] == '/'));
        int ancestor = length < size && !memcmp(path, guest, length) && guest[length] == '/';
        /* Nested binds require ancestor checks by the canonical translator. */
        if (strchr(guest + 1, '/') && (matches || ancestor)) return 0;
        if (matches && size > longest) { selected = i; longest = size; }
    }
    if (selected == count) return 0;
    const struct proot_fastpath_binding *item = &rules->bindings[selected];
    uint32_t offset = atomic_load(&item->host), size = atomic_load(&item->host_length);
    if (!size || size >= PATH_MAX || offset >= PROOT_FASTPATH_BYTES ||
        size >= PROOT_FASTPATH_BYTES - offset) return 0;
    for (uint32_t j = 0; j <= size; j++) result[j] = atomic_load_explicit(&rules->paths[offset + j], memory_order_relaxed);
    if (result[size] || result[0] != '/') return 0;
    if (anchor_length) *anchor_length = size;
    const char *suffix = path + (longest == 1 ? 1 : longest);
    if (size == 1 && *suffix == '/') suffix++;
    int separator = longest == 1 && size > 1 && *suffix;
    if (size + separator + strlen(suffix) >= PATH_MAX) return 0;
    if (separator) result[size++] = '/';
    strcpy(result + size, suffix);
    return sequence == atomic_load(&rules->sequence) && atomic_load(&rules->active);
}

/* The kernel cwd is not PRoot's virtual cwd. Relative AT_FDCWD calls use
 * the same shared snapshot as the binding lookup; other dirfds fall back. */
static int shared_absolute_path(struct proot_fastpath_rules *rules, int dirfd,
                                char path[PATH_MAX])
{
    if (path[0] == '/') return 1;
    if (!path[0] || dirfd != AT_FDCWD || !atomic_load(&rules->active)) return 0;
    char absolute[PATH_MAX];
    size_t length;
    for (length = 0; length < sizeof(absolute); length++) {
        absolute[length] = atomic_load_explicit(&rules->cwd[length], memory_order_relaxed);
        if (!absolute[length]) break;
    }
    if (!length || length == sizeof(absolute) || absolute[0] != '/') return 0;
    size_t suffix = strlen(path);
    int separator = absolute[length - 1] != '/';
    if (length + separator + suffix >= sizeof(absolute)) return 0;
    if (separator) absolute[length++] = '/';
    memcpy(absolute + length, path, suffix + 1);
    strcpy(path, absolute);
    return 1;
}

/* Collapse slash/dot spelling without resolving parent traversal. Preserve
 * a trailing directory requirement: file/. must never become a regular file. */
static int normalize_simple_path(char path[PATH_MAX])
{
    if (path[0] != '/') return 0;
    size_t read = 1, write = 1;
    int directory = 0;
    while (path[read]) {
        if (path[read] == '/') { directory = 1; read++; continue; }
        size_t start = read;
        while (path[read] && path[read] != '/') read++;
        size_t length = read - start;
        if (length == 2 && path[start] == '.' && path[start + 1] == '.') return 0;
        directory = length == 1 && path[start] == '.';
        if (directory) continue;
        if (write > 1) path[write++] = '/';
        memmove(path + write, path + start, length);
        write += length;
    }
    if (directory && write > 1) path[write++] = '/';
    path[write] = '\0';
    return 1;
}

/* Private diagnostic API used to test rule publication before enabling open. */
int proot_fastpath_translate(const char *path, char *output, size_t capacity)
{
    int saved_errno = errno, success = 0;
    if (!enabled || !fd_requested || atomic_exchange(&local_identity.busy, 1)) return 0;
    pid_t pid = syscall(SYS_getpid);
    struct proot_fastfd_identity *identity = current_identity(pid);
    char copied[PATH_MAX], translated[PATH_MAX];
    size_t anchor_length = 0;
    if (identity && copy_path(copied, path, pid) &&
        lookup_shared_path(&identity->paths, copied, translated, &anchor_length)) {
        size_t length = strlen(translated) + 1;
        if (length <= capacity) {
            struct iovec local = { translated, length }, remote = { output, length };
            success = syscall(SYS_process_vm_writev, pid, &local, 1, &remote, 1, 0) == (long)length;
        }
    }
    atomic_store(&local_identity.busy, 0);
    errno = saved_errno;
    return success;
}

/* O_PATH has no device-open side effects. Accept a full-path lookup only
 * when procfs confirms the pinned object's exact expected host pathname.
 * Intermediate symlinks resolving elsewhere, deleted/renamed objects and
 * unavailable procfs all return to the component-by-component resolver.
 * Neither the descriptor nor this pathname observation survives this call. */
static long pin_shared_path(const char *path, size_t anchor_length, int flags)
{
    size_t length = strlen(path);
    if (length && length < PATH_MAX && path[length - 1] != '/') {
        long fd = fast_syscall(SYS_openat, AT_FDCWD, (long)path,
                              O_PATH | O_CLOEXEC | O_NOFOLLOW | (flags & O_DIRECTORY), 0);
        if (fd >= 0) {
            char descriptor[64], resolved[PATH_MAX];
            snprintf(descriptor, sizeof(descriptor), "/proc/self/fd/%ld", fd);
            long count = fast_syscall(SYS_readlinkat, AT_FDCWD, (long)descriptor,
                                     (long)resolved, sizeof(resolved));
            if (count == (long)length && !memcmp(path, resolved, length)) return fd;
            fast_syscall(SYS_close, fd, 0, 0, 0);
        }
    }
    return proot_pin_bound_nofollow(fast_syscall, path, anchor_length, flags);
}

/* Resolve a relative dirfd against a fresh pinned directory. Reverse mapping
 * must be unique and round-trip through the current guest bindings. A shadowed
 * or ambiguous host alias belongs to the canonical PRoot resolver. */
static long pin_shared_relative(struct proot_fastpath_rules *rules, int dirfd,
                                const char *relative, char translated[PATH_MAX])
{
    if (dirfd < 0 || !*relative || *relative == '/') return -EOPNOTSUPP;
    long parent = fast_syscall(SYS_openat, dirfd, (long)".",
                              O_PATH | O_DIRECTORY | O_CLOEXEC, 0);
    if (parent < 0) return parent;
    long child = -1;
    char descriptor[64], host[PATH_MAX], expected[PATH_MAX];
    snprintf(descriptor, sizeof(descriptor), "/proc/self/fd/%ld", parent);
    long length = fast_syscall(SYS_readlinkat, AT_FDCWD, (long)descriptor,
                              (long)host, sizeof(host) - 1);
    if (length <= 0 || length >= (long)sizeof(host) - 1) goto done;
    host[length] = 0;
    uint32_t count = atomic_load(&rules->count);
    if (!atomic_load(&rules->active) || !count || count > PROOT_FASTPATH_BINDINGS) goto done;
    unsigned matches = 0;
    for (uint32_t i = 0; i < count; i++) {
        const struct proot_fastpath_binding *item = &rules->bindings[i];
        uint32_t offset = atomic_load(&item->host), size = atomic_load(&item->host_length);
        if (!size || size >= PATH_MAX || offset >= PROOT_FASTPATH_BYTES ||
            size >= PROOT_FASTPATH_BYTES - offset) goto done;
        char prefix[PATH_MAX];
        for (uint32_t j = 0; j <= size; j++) prefix[j] = atomic_load_explicit(&rules->paths[offset + j], memory_order_relaxed);
        if (prefix[size] || prefix[0] != '/') goto done;
        if (length < size || memcmp(host, prefix, size) ||
            (size != 1 && host[size] && host[size] != '/')) continue;
        const char *suffix = host + size;
        while (*suffix == '/') suffix++;
        offset = atomic_load(&item->guest); size = atomic_load(&item->guest_length);
        if (!size || size >= PATH_MAX || offset >= PROOT_FASTPATH_BYTES ||
            size >= PROOT_FASTPATH_BYTES - offset) goto done;
        for (uint32_t j = 0; j <= size; j++) prefix[j] = atomic_load_explicit(&rules->paths[offset + j], memory_order_relaxed);
        if (prefix[size] || prefix[0] != '/') goto done;
        char guest[PATH_MAX];
        int written = snprintf(guest, sizeof(guest), "%s/%s/%s", prefix, suffix, relative);
        if (written < 0 || written >= (int)sizeof(guest) || !normalize_simple_path(guest)) goto done;
        size_t anchor;
        if (!lookup_shared_path(rules, guest, translated, &anchor)) goto done;
        written = snprintf(expected, sizeof(expected), "%s/%s", host, relative);
        if (written < 0 || written >= (int)sizeof(expected) || !normalize_simple_path(expected) ||
            strcmp(expected, translated)) goto done;
        if (++matches != 1) goto done;
    }
    if (matches != 1) goto done;
    child = fast_syscall(SYS_openat, parent, (long)relative, O_PATH | O_CLOEXEC | O_NOFOLLOW, 0);
    if (child < 0) goto done;
    snprintf(descriptor, sizeof(descriptor), "/proc/self/fd/%ld", child);
    length = fast_syscall(SYS_readlinkat, AT_FDCWD, (long)descriptor, (long)host, sizeof(host));
    if (length != (long)strlen(expected) || memcmp(host, expected, length)) {
        fast_syscall(SYS_close, child, 0, 0, 0);
        child = -1;
    }
done:
    fast_syscall(SYS_close, parent, 0, 0, 0);
    return child;
}

static int ordinary_storage(int pin)
{
    struct statfs filesystem;
    if (syscall(SYS_fstatfs, pin, &filesystem)) return 0;
    switch (filesystem.f_type) {
    case EXT4_SUPER_MAGIC: case F2FS_SUPER_MAGIC: case TMPFS_MAGIC:
    case XFS_SUPER_MAGIC: case BTRFS_SUPER_MAGIC: case OVERLAYFS_SUPER_MAGIC:
    case EROFS_SUPER_MAGIC_V1: case SQUASHFS_MAGIC:
        return 1;
    default:
        return 0;
    }
}

/* A fresh fstat result identifies the same live superblock held by the
 * tracer. Unknown devices retain the per-call filesystem check. */
static int ordinary_storage_stat(int pin, struct proot_fastfd_identity *identity,
                                 const struct stat *info)
{
    if (atomic_load(&identity->storage_active)) {
        uint64_t device = atomic_load(&identity->storage_dev_low);
        device |= (uint64_t)atomic_load(&identity->storage_dev_high) << 32;
        if (device == (uint64_t)info->st_dev && atomic_load(&identity->storage_active)) {
            atomic_fetch_add_explicit(&storage_hits, 1, memory_order_relaxed);
            return 1;
        }
    }
    return ordinary_storage(pin);
}

unsigned long proot_faststorage_hits(void) { return atomic_load(&storage_hits); }

/* Regular files/directories on ordinary storage. Pin before reopening so a FIFO
 * substitution cannot block and a device substitution cannot have side effects.
 * Ordinary openat remains the fallback and the final fast call uses openat too,
 * so a guest filter denying that syscall still applies. */
static int dispatch_open(int dirfd, const char *path, int flags, mode_t mode)
{
    int saved_errno = errno, owned = 0, masked = 0;
    long pin = -1, fd = -1;
    uint64_t all_signals = UINT64_MAX, previous_signals;
    const int allowed = O_CLOEXEC | O_DIRECTORY | O_NONBLOCK | O_LARGEFILE;
    const int create_allowed = O_ACCMODE | O_CREAT | O_EXCL | O_TRUNC | O_APPEND |
                               O_CLOEXEC | O_LARGEFILE;
    int creating = create_requested && (flags & O_CREAT) &&
        ((flags & O_ACCMODE) == O_WRONLY || (flags & O_ACCMODE) == O_RDWR) &&
        !(flags & ~create_allowed) && !(mode & ~0777);
    if (!enabled || !open_requested || (!creating && (flags & ~allowed))) goto slow_open;
    pthread_testcancel();
    if (atomic_exchange(&local_identity.busy, 1)) goto slow_open;
    owned = 1;
    /* A handler's siglongjmp must not abandon the temporary pinned FD. */
    if (syscall(SYS_rt_sigprocmask, SIG_SETMASK, &all_signals, &previous_signals,
                sizeof(previous_signals))) goto slow_open;
    masked = 1;
    pid_t pid = syscall(SYS_getpid);
    struct proot_fastfd_identity *identity = current_identity(pid);
    char copied[PATH_MAX], translated[PATH_MAX];
    size_t anchor_length = 0;
    if (!identity || !atomic_load(&identity->pin_safe) || !copy_path(copied, path, pid)) goto slow_open;
    uint32_t sequence = atomic_load(&identity->paths.sequence);
    if (!shared_absolute_path(&identity->paths, dirfd, copied) ||
        !normalize_simple_path(copied)) goto slow_open;
    if (!lookup_shared_path(&identity->paths, copied, translated, &anchor_length)) goto slow_open;
    char *basename = NULL;
    if (creating) {
        char *slash = strrchr(translated, '/');
        if (!slash || !slash[1] || slash == translated) goto slow_open;
        *slash = '\0';
        basename = slash + 1;
    }
    struct open_how how = { .flags = O_PATH | O_CLOEXEC | (creating ? O_DIRECTORY : 0),
                            .resolve = RESOLVE_NO_SYMLINKS };
    pin = pin_shared_path(translated, anchor_length, how.flags);
    if (pin < 0) goto slow_open;
    struct stat info;
    if (fast_syscall(SYS_fstat, pin, (long)&info, 0, 0) < 0 ||
        (!S_ISREG(info.st_mode) && !S_ISDIR(info.st_mode))) goto slow_open;
    /* procfs regular files (notably auxv) still need tracer emulation. Check
     * the pinned object, not a cached pathname or binding classification. */
    if (!ordinary_storage_stat(pin, identity, &info)) goto slow_open;
    char pinned[64];
    snprintf(pinned, sizeof(pinned), "/proc/self/fd/%ld", pin);
    if (creating) {
        if (sequence != atomic_load(&identity->paths.sequence) ||
            !atomic_load(&identity->paths.active)) goto slow_open;
        /* Never truncate an object that appeared after parent lookup. A failed
         * exclusive create has no mutation to retry; libc handles existing
         * files, links and permission emulation. After success do not retry,
         * even if a concurrent bind changes the namespace. The pinned parent
         * is the same lookup-before-execution model as the tracer's path. */
        fd = fast_syscall(SYS_openat, pin, (long)basename, flags | O_EXCL, mode);
        if (fd < 0) goto slow_open;
        atomic_fetch_add_explicit(&create_hits, 1, memory_order_relaxed);
    } else {
        fd = fast_syscall(SYS_openat, AT_FDCWD, (long)pinned, flags, 0);
        if (fd < 0 || sequence != atomic_load(&identity->paths.sequence) ||
            !atomic_load(&identity->paths.active)) goto slow_open;
    }
    fast_syscall(SYS_close, pin, 0, 0, 0);
    atomic_fetch_add_explicit(&open_hits, 1, memory_order_relaxed);
    atomic_store(&local_identity.busy, 0);
    syscall(SYS_rt_sigprocmask, SIG_SETMASK, &previous_signals, NULL, sizeof(previous_signals));
    errno = saved_errno;
    return (int)fd;
slow_open:
    if (fd >= 0) fast_syscall(SYS_close, fd, 0, 0, 0);
    if (pin >= 0) fast_syscall(SYS_close, pin, 0, 0, 0);
    if (owned) atomic_store(&local_identity.busy, 0);
    if (masked) syscall(SYS_rt_sigprocmask, SIG_SETMASK, &previous_signals, NULL, sizeof(previous_signals));
    errno = saved_errno;
    return libc_openat ? libc_openat(dirfd, path, flags, mode) :
                        (int)syscall(SYS_openat, dirfd, path, flags, mode);
}

/* Pinned-parent unlink: a successful mutation is never retried. */
int unlinkat(int dirfd, const char *path, int flags)
{
    int saved_errno = errno, owned = 0, masked = 0;
    long pin = -1;
    uint64_t all_signals = UINT64_MAX, previous_signals;
    if (!enabled || !unlink_requested || flags != 0) goto slow_unlink;
    if (atomic_exchange(&local_identity.busy, 1)) goto slow_unlink;
    owned = 1;
    pid_t pid = syscall(SYS_getpid);
    struct proot_fastfd_identity *identity = current_identity(pid);
    char copied[PATH_MAX], translated[PATH_MAX];
    size_t anchor_length = 0;
    /* Emulated unlink updates virtual link counts; even an ordinary name can
     * become an alias concurrently. Keep the entire mutation in the tracer. */
    if (!identity || atomic_load(&identity->active) != 1) goto slow_unlink;
    /* Emulated unlink always belongs to the tracer. Reject it before signal
     * masking: no temporary FD exists yet, and busy protects reentrant calls. */
    if (syscall(SYS_rt_sigprocmask, SIG_SETMASK, &all_signals, &previous_signals,
                sizeof(previous_signals))) goto slow_unlink;
    masked = 1;
    if (!atomic_load(&identity->pin_safe)) goto slow_unlink;
    if (!copy_path(copied, path, pid)) goto slow_unlink;
    uint32_t sequence = atomic_load(&identity->paths.sequence);
    if (!shared_absolute_path(&identity->paths, dirfd, copied) ||
        !normalize_simple_path(copied)) goto slow_unlink;
    if (!lookup_shared_path(&identity->paths, copied, translated, &anchor_length)) goto slow_unlink;
    char *slash = strrchr(translated, '/');
    if (!slash || slash == translated || !slash[1]) goto slow_unlink;
    *slash = '\0';
    struct open_how how = { .flags = O_PATH | O_DIRECTORY | O_CLOEXEC,
                           .resolve = RESOLVE_NO_SYMLINKS };
    pin = pin_shared_path(translated, anchor_length, how.flags);
    if (pin < 0 || !ordinary_storage(pin) ||
        sequence != atomic_load(&identity->paths.sequence) ||
        !atomic_load(&identity->paths.active)) goto slow_unlink;
    if (fast_syscall(SYS_unlinkat, pin, (long)(slash + 1), 0, 0) < 0) goto slow_unlink;
    fast_syscall(SYS_close, pin, 0, 0, 0);
    atomic_fetch_add_explicit(&unlink_hits, 1, memory_order_relaxed);
    atomic_store(&local_identity.busy, 0);
    syscall(SYS_rt_sigprocmask, SIG_SETMASK, &previous_signals, NULL, sizeof(previous_signals));
    errno = saved_errno;
    return 0;
slow_unlink:
    if (pin >= 0) fast_syscall(SYS_close, pin, 0, 0, 0);
    if (owned) atomic_store(&local_identity.busy, 0);
    if (masked) syscall(SYS_rt_sigprocmask, SIG_SETMASK, &previous_signals, NULL, sizeof(previous_signals));
    errno = saved_errno;
    return syscall(SYS_unlinkat, dirfd, path, flags);
}

int unlink(const char *path) { return unlinkat(AT_FDCWD, path, 0); }
unsigned long proot_fastunlink_hits(void) { return atomic_load(&unlink_hits); }

unsigned long proot_fastcreate_hits(void) { return atomic_load(&create_hits); }
unsigned long proot_fastopen_hits(void) { return atomic_load(&open_hits); }

#define OPEN_WRAPPER(name) \
int name(const char *path, int flags, ...) { \
    mode_t mode = 0; \
    if ((flags & O_CREAT) || ((flags & O_TMPFILE) == O_TMPFILE)) { \
        va_list args; va_start(args, flags); mode = va_arg(args, mode_t); va_end(args); \
    } \
    return dispatch_open(AT_FDCWD, path, flags, mode); \
}
#define OPENAT_WRAPPER(name) \
int name(int dirfd, const char *path, int flags, ...) { \
    mode_t mode = 0; \
    if ((flags & O_CREAT) || ((flags & O_TMPFILE) == O_TMPFILE)) { \
        va_list args; va_start(args, flags); mode = va_arg(args, mode_t); va_end(args); \
    } \
    return dispatch_open(dirfd, path, flags, mode); \
}
OPEN_WRAPPER(open)
OPEN_WRAPPER(open64)
OPENAT_WRAPPER(openat)
OPENAT_WRAPPER(openat64)

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
    int owned = 0;
    if (!enabled || !fd_requested) goto slow_fd;
    /* Hold through the whole call, including fork mapping replacement and
     * output copying. A signal handler uses the ordinary syscall instead. */
    if (atomic_exchange_explicit(&local_identity.busy, 1, memory_order_relaxed)) goto slow_fd;
    owned = 1;
    pid_t pid = (pid_t)syscall(SYS_getpid);
    struct proot_fastfd_identity *identity = current_identity(pid);
    if (!identity) goto slow_fd;
    /* A single post-stat name check requires an isolated FD table and no
     * intervening signal/filter/async FD activity. The tracer revokes single_safe
     * before those can change the descriptor; it cannot re-enable it until exec.
     * Otherwise retain both live name checks. Never cache an FD classification. */
    char descriptor[64], before[PATH_MAX], after[PATH_MAX];
    int emulated = atomic_load(&identity->active) == 2;
    int single = emulated && atomic_load(&identity->single_safe);
    long before_length = 0;
    if (emulated) snprintf(descriptor, sizeof(descriptor), "/proc/self/fd/%d", fd);
    if (emulated && !single) {
        before_length = fast_syscall(SYS_readlinkat, AT_FDCWD, (long)descriptor, (long)before, sizeof(before) - 1);
        if (before_length <= 0 || before_length >= (long)sizeof(before) - 1) goto slow_fd;
        before[before_length] = 0;
        if (strstr(before, "/.l2s.") || strstr(before, "/.proot.l2s.")) goto slow_fd;
    }
    struct stat result;
    if (fast_syscall(SYS_fstat, fd, (long)&result, 0, 0) < 0 ||
        (!S_ISREG(result.st_mode) && !S_ISDIR(result.st_mode)) ||
        !map_identity(identity, &result)) goto slow_fd;
    if (emulated) {
        long length = fast_syscall(SYS_readlinkat, AT_FDCWD, (long)descriptor, (long)after, sizeof(after) - 1);
        if (single) {
            if (length <= 0 || length >= (long)sizeof(after) - 1) goto slow_fd;
            after[length] = 0;
            if (strstr(after, "/.l2s.") || strstr(after, "/.proot.l2s.") ||
                !atomic_load(&identity->single_safe)) goto slow_fd;
        } else if (length != before_length || memcmp(before, after, before_length)) goto slow_fd;
    }
    struct iovec local = { &result, sizeof(result) }, remote = { output, sizeof(result) };
    if (syscall(SYS_process_vm_writev, pid, &local, 1, &remote, 1, 0) != sizeof(result)) goto slow_fd;
    atomic_fetch_add_explicit(&fd_hits, 1, memory_order_relaxed);
    atomic_store_explicit(&local_identity.busy, 0, memory_order_relaxed);
    errno = saved_errno;
    return 0;
 slow_fd:
    if (owned) atomic_store_explicit(&local_identity.busy, 0, memory_order_relaxed);
    atomic_fetch_add_explicit(&fd_fallbacks, 1, memory_order_relaxed);
    errno = saved_errno;
    return (int)syscall(SYS_fstat, fd, output);
}

/* Only an exec-isolated descriptor table without tracer-owned FD state.
 * Signals cannot replace the descriptor between classification and close. */
int close(int fd)
{
    int saved_errno = errno, owned = 0, masked = 0;
    uint64_t all_signals = UINT64_MAX, previous_signals;
    long result = 0;
    if (!enabled || !close_requested) goto slow_close;
    pthread_testcancel();
    if (atomic_exchange(&local_identity.busy, 1)) goto slow_close;
    owned = 1;
    struct proot_fastfd_identity *identity = current_identity(syscall(SYS_getpid));
    /* Fork/thread sharing permanently disables this fast path until exec.
     * There is no temporary FD yet, so reject before changing signal masks. */
    if (!identity || !atomic_load(&identity->close_safe)) goto slow_close;
    if (syscall(SYS_rt_sigprocmask, SIG_SETMASK, &all_signals, &previous_signals,
                sizeof(previous_signals))) goto slow_close;
    masked = 1;
    struct stat info;
    if (!atomic_load(&identity->close_safe) ||
        fast_syscall(SYS_fstat, fd, (long)&info, 0, 0) < 0 ||
        (!S_ISREG(info.st_mode) && !S_ISDIR(info.st_mode)) ||
        !atomic_load(&identity->close_safe)) goto slow_close;
    result = fast_syscall(SYS_close, fd, 0, 0, 0);
    /* Never retry close: an error can still mean the FD was released. */
    atomic_fetch_add_explicit(&close_hits, 1, memory_order_relaxed);
    atomic_store(&local_identity.busy, 0);
    syscall(SYS_rt_sigprocmask, SIG_SETMASK, &previous_signals, NULL, sizeof(previous_signals));
    errno = result < 0 ? (int)-result : saved_errno;
    return result < 0 ? -1 : 0;
slow_close:
    if (owned) atomic_store(&local_identity.busy, 0);
    if (masked) syscall(SYS_rt_sigprocmask, SIG_SETMASK, &previous_signals, NULL, sizeof(previous_signals));
    errno = saved_errno;
    return libc_close ? libc_close(fd) : (int)syscall(SYS_close, fd);
}

unsigned long proot_fastclose_hits(void) { return atomic_load(&close_hits); }

int fstat64(int fd, struct stat64 *output) { return fstat(fd, (struct stat *)output); }

/* Fresh metadata using the shared namespace, without a resolve request. */
static int shared_path_stat(int dirfd, const char *path, struct stat *output, int flags)
{
    int saved_errno = errno, success = 0, masked = 0;
    long pin = -1;
    uint64_t all_signals = UINT64_MAX, previous_signals;
    if (!enabled || !shared_stat_requested || (flags != 0 && flags != AT_SYMLINK_NOFOLLOW) ||
        atomic_exchange(&local_identity.busy, 1)) return 0;
    if (syscall(SYS_rt_sigprocmask, SIG_SETMASK, &all_signals, &previous_signals,
                sizeof(previous_signals))) goto done;
    masked = 1;
    pid_t pid = syscall(SYS_getpid);
    struct proot_fastfd_identity *identity = current_identity(pid);
    char copied[PATH_MAX], translated[PATH_MAX];
    size_t anchor_length = 0;
    if (!identity || !atomic_load(&identity->pin_safe) || !copy_path(copied, path, pid)) goto done;
    uint32_t sequence = atomic_load(&identity->paths.sequence);
    if (copied[0] != '/' && dirfd != AT_FDCWD) {
        pin = pin_shared_relative(&identity->paths, dirfd, copied, translated);
    } else {
        if (!shared_absolute_path(&identity->paths, dirfd, copied) ||
            !normalize_simple_path(copied) ||
            !lookup_shared_path(&identity->paths, copied, translated, &anchor_length)) goto done;
        pin = pin_shared_path(translated, anchor_length, 0);
    }
    if (pin < 0 || strstr(translated, "/.l2s.") ||
        strstr(translated, "/.proot.l2s.")) goto done;
    struct stat result;
    /* Use the original syscall number: guest seccomp denial still wins. */
    if (fast_syscall(SYS_newfstatat, pin, (long)"", (long)&result, AT_EMPTY_PATH) < 0 ||
        (!S_ISREG(result.st_mode) && !S_ISDIR(result.st_mode)) ||
        !ordinary_storage_stat(pin, identity, &result) ||
        !map_identity(identity, &result) ||
        sequence != atomic_load(&identity->paths.sequence) ||
        !atomic_load(&identity->paths.active)) goto done;
    struct iovec local = { &result, sizeof(result) }, remote = { output, sizeof(result) };
    if (syscall(SYS_process_vm_writev, pid, &local, 1, &remote, 1, 0) != sizeof(result)) goto done;
    atomic_fetch_add_explicit(&hits, 1, memory_order_relaxed);
    success = 1;
done:
    if (pin >= 0) fast_syscall(SYS_close, pin, 0, 0, 0);
    atomic_store(&local_identity.busy, 0);
    if (masked) syscall(SYS_rt_sigprocmask, SIG_SETMASK, &previous_signals, NULL, sizeof(previous_signals));
    errno = saved_errno;
    return success;
}

/* Preserve the extended kernel fields; do not synthesize statx from stat. */
int statx(int dirfd, const char *path, int flags, unsigned mask, struct statx *output)
{
    _Static_assert(sizeof(struct statx) == 256, "statx ABI");
    int saved_errno = errno, owned = 0, masked = 0, success = 0;
    long pin = -1;
    uint64_t all_signals = UINT64_MAX, previous_signals;
    if (!enabled || !shared_stat_requested ||
        (flags & ~(AT_SYMLINK_NOFOLLOW | AT_NO_AUTOMOUNT | AT_STATX_SYNC_TYPE))) goto done;
    if (atomic_exchange(&local_identity.busy, 1)) goto done;
    owned = 1;
    if (syscall(SYS_rt_sigprocmask, SIG_SETMASK, &all_signals, &previous_signals,
                sizeof(previous_signals))) goto done;
    masked = 1;
    pid_t pid = syscall(SYS_getpid);
    struct proot_fastfd_identity *identity = current_identity(pid);
    char copied[PATH_MAX], translated[PATH_MAX];
    size_t anchor_length = 0;
    if (!identity || !atomic_load(&identity->pin_safe) || !copy_path(copied, path, pid)) goto done;
    uint32_t sequence = atomic_load(&identity->paths.sequence);
    if (copied[0] != '/' && dirfd != AT_FDCWD) {
        pin = pin_shared_relative(&identity->paths, dirfd, copied, translated);
    } else {
        if (!shared_absolute_path(&identity->paths, dirfd, copied) ||
            !normalize_simple_path(copied) ||
            !lookup_shared_path(&identity->paths, copied, translated, &anchor_length)) goto done;
        pin = pin_shared_path(translated, anchor_length, 0);
    }
    /* A guest bind may directly expose an l2s backing inode even though its
     * guest name is ordinary. Its virtual count still belongs to PRoot. */
    if (pin < 0 || strstr(translated, "/.l2s.") ||
        strstr(translated, "/.proot.l2s.") || !ordinary_storage(pin)) goto done;
    struct statx result;
    if (fast_statx(pin, flags | AT_EMPTY_PATH, mask, &result) < 0 ||
        !(result.stx_mask & STATX_TYPE) ||
        (!S_ISREG(result.stx_mode) && !S_ISDIR(result.stx_mode))) goto done;
    struct stat identity_result = { .st_uid = result.stx_uid, .st_gid = result.stx_gid };
    if (!map_identity(identity, &identity_result) ||
        sequence != atomic_load(&identity->paths.sequence) ||
        !atomic_load(&identity->paths.active)) goto done;
    if (result.stx_mask & STATX_UID) result.stx_uid = identity_result.st_uid;
    if (result.stx_mask & STATX_GID) result.stx_gid = identity_result.st_gid;
    struct iovec local = { &result, sizeof(result) }, remote = { output, sizeof(result) };
    if (syscall(SYS_process_vm_writev, pid, &local, 1, &remote, 1, 0) != sizeof(result)) goto done;
    atomic_fetch_add_explicit(&hits, 1, memory_order_relaxed);
    success = 1;
done:
    if (pin >= 0) fast_syscall(SYS_close, pin, 0, 0, 0);
    if (owned) atomic_store(&local_identity.busy, 0);
    if (masked) syscall(SYS_rt_sigprocmask, SIG_SETMASK, &previous_signals, NULL, sizeof(previous_signals));
    errno = saved_errno;
    if (success) return 0;
    return libc_statx ? libc_statx(dirfd, path, flags, mask, output) :
        (int)syscall(SYS_statx, dirfd, path, flags, mask, output);
}

int fstatat(int dirfd, const char *path, struct stat *output, int flags)
{
    int saved_errno = errno;
    int owned = 0, masked = 0, result_status = 0;
    uint64_t blocked = UINT64_MAX, previous;
    char copied[PATH_MAX];
    struct stat result;
    if (shared_path_stat(dirfd, path, output, flags)) return 0;
    /* PRoot's cwd is virtual. Mode 1 requires a real directory FD;
     * mode 2 obtains an absolute host path before a no-follow directory walk. */
    if (!enabled || !path_enabled || (flags != 0 && flags != AT_SYMLINK_NOFOLLOW)) goto slow;
    if (atomic_exchange(&local_identity.busy, 1)) goto slow;
    owned = 1;
    if (syscall(SYS_rt_sigprocmask, SIG_SETMASK, &blocked, &previous, sizeof(previous))) goto slow;
    masked = 1;
    pid_t pid = syscall(SYS_getpid);
    if (!temporary_fds_safe(pid)) goto slow;
    if (!copy_path(copied, path, pid) || !copied[0]) goto slow;
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
             * Never promote resolver errors: a guest filter can invent those. */
            atomic_fetch_add_explicit(&negative_hits, 1, memory_order_relaxed);
            saved_errno = ENOENT;
            result_status = -1;
            goto fast_done;
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
    long fd = proot_pin_nofollow(fast_syscall, kernel_dirfd, kernel_path, how.flags);
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
fast_done:
    if (owned) atomic_store(&local_identity.busy, 0);
    if (masked) syscall(SYS_rt_sigprocmask, SIG_SETMASK, &previous, NULL, sizeof(previous));
    errno = saved_errno;
    atomic_fetch_add_explicit(&hits, 1, memory_order_relaxed);
    return result_status;
slow:
    if (owned) atomic_store(&local_identity.busy, 0);
    if (masked) syscall(SYS_rt_sigprocmask, SIG_SETMASK, &previous, NULL, sizeof(previous));
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

unsigned proot_fastfd_single_safe(void) {
    struct proot_fastfd_identity *page = local_identity.page;
    return page ? atomic_load(&page->single_safe) : 0;
}
