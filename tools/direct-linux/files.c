/* SPDX-License-Identifier: Apache-2.0 */
/* Guest filesystem compatibility; Android permissions remain authoritative. */
#define _GNU_SOURCE
#include "runtime.h"
#include "threads.h"
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <time.h>
#include <unistd.h>

static size_t decimal(char *out, unsigned value)
{
    char digits[16]; size_t count = 0;
    do { digits[count++] = '0' + value % 10; value /= 10; } while (value);
    size_t n = 0; while (count) out[n++] = digits[--count]; return n;
}

size_t dl_fd_path(char *out, int fd)
{
    // /proc/self/fd can disappear after the group leader's pthread_exit and
    // would select the wrong table after another thread unshares CLONE_FILES.
    // /proc/TID directly names this live task, including nonleader tasks.
    const char prefix[] = "/proc/", suffix[] = "/fd";
    size_t n = sizeof(prefix) - 1; dl_copy(out, prefix, n);
    n += decimal(out + n, (unsigned)DL_CALL(SYS_gettid, 0, 0, 0, 0, 0, 0));
    dl_copy(out + n, suffix, sizeof(suffix) - 1); n += sizeof(suffix) - 1;
    if (fd >= 0) { out[n++] = '/'; n += decimal(out + n, (unsigned)fd); }
    out[n] = 0; return n;
}

static long virtual_path(int fd, char out[DL_PATH], int directory)
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
        if (directory && !S_ISDIR(st.st_mode)) return -ENOTDIR;
        char link[40]; dl_fd_path(link, fd);
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
long dl_virtual_cwd(char out[DL_PATH]) { return virtual_path(AT_FDCWD, out, 1); }

long dl_file_path(int fd, char out[DL_PATH]) { return virtual_path(fd, out, 0); }
long dl_executable(int fd)
{
    char path[40]; dl_fd_path(path, fd);
    return DL_CALL(SYS_faccessat, AT_FDCWD, path, X_OK, 0, 0, 0);
}

long dl_reopen(int fd, int flags)
{
    if (fd < 0 || dl_internal_fd(fd)) return -EBADF;
    // A descriptor can outlive unlink/rename. Reopen its inode, not the text
    // returned by readlink. Only guest-root files are eligible for this alias.
    char path[DL_PATH];
    long r = dl_file_path(fd, path); if (r) return r;
    dl_fd_path(path, fd);
    return DL_CALL(SYS_openat, AT_FDCWD, path, flags, 0, 0, 0);
}

// Bounded descriptor aliases required by execveat scripts and libc's older
// fexecve fallback. This does not mount or expose the host proc filesystem.
static int fd_alias(const char *path, int *fd, const char **tail)
{
    const char *prefixes[] = {"/dev/fd/", "/proc/self/fd/"};
    for (unsigned i = 0; i < 2; i++) {
        size_t n = 0;
        while (prefixes[i][n] && prefixes[i][n] == path[n]) n++;
        if (prefixes[i][n]) continue;
        const char *start = path + n; unsigned value = 0;
        *fd = -1; *tail = start;
        if (*start < '0' || *start > '9' || (*start == '0' && start[1] >= '0' && start[1] <= '9')) return 1;
        while (path[n] >= '0' && path[n] <= '9') {
            unsigned digit = (unsigned)(path[n++] - '0');
            if (value > (INT_MAX - digit) / 10) return 1;
            value = value * 10 + digit;
        }
        if (path[n] && path[n] != '/') return 1;
        *fd = (int)value; *tail = path + n; return 1;
    }
    return 0;
}

static void release(int fd)
{
    if (fd >= 0 && fd != dl_state.root_fd) DL_CALL(SYS_close, fd, 0, 0, 0, 0, 0);
}

// Consume an owned pin (or borrow the fixed root). No filesystem lock may
// survive a guest handler, cancellation, or a nonlocal return from that handler.
static long blocking_open(int pin, const char *name, int flags, unsigned mode, void *context)
{
    uintptr_t stack; __asm__ volatile("mov %0, sp" : "=r"(stack));
    long slot = dl_io_hold(pin == dl_state.root_fd ? -1 : pin, stack);
    if (slot < 0) { release(pin); return slot; }
    unsigned long args[6] = {pin, (uintptr_t)name, flags, mode, 0, 0};
    dl_unlock(&dl_files_lock);
    long result = dl_blocking(SYS_openat, args, context);
    dl_lock(&dl_files_lock);
    dl_io_release((unsigned)slot);
    return result;
}

static long directory(int fd)
{
    // Keep the actual directory, including an unlinked cwd. Its printed path
    // only checks root membership; it must never become the lookup starting point.
    long pin = DL_CALL(SYS_openat, fd, ".", O_PATH | O_DIRECTORY | O_CLOEXEC, 0, 0, 0);
    if (pin < 0) return pin;
    char path[DL_PATH];
    long r = virtual_path((int)pin, path, 0);
    if (r) { release((int)pin); return r; }
    return pin;
}

static long parent_directory(int fd)
{
    struct stat current, root;
    long r = DL_CALL(SYS_fstat, fd, &current, 0, 0, 0, 0); if (r) return r;
    r = DL_CALL(SYS_fstat, dl_state.root_fd, &root, 0, 0, 0, 0); if (r) return r;
    if (current.st_dev == root.st_dev && current.st_ino == root.st_ino) return dl_state.root_fd;
    long pin = DL_CALL(SYS_openat, fd, "..", O_PATH | O_DIRECTORY | O_CLOEXEC, 0, 0, 0);
    if (pin < 0) return pin;
    char path[DL_PATH];
    r = virtual_path((int)pin, path, 0);
    if (r) { release((int)pin); return r; }
    return pin;
}

/* Follow prefixes with pinned no-follow handles. Absolute symlink targets
 * restart at the guest root; '..' uses the current inode's live parent.
 * A non-null leaf requests a pinned parent and an unresolved final component,
 * including its trailing slash, for a single atomic kernel namespace operation.
 * This compatibility walk is not an openat2-equivalent security sandbox against
 * a same-UID host process concurrently moving directories outside the root. */
static long walk(int dirfd, const char *path, int flags, unsigned mode, char *leaf, void *context)
{
    char pending[DL_PATH], component[DL_PATH], target[DL_PATH];
    size_t length = dl_length(path);
    if (!length) return -ENOENT;
    if (length >= DL_PATH) return -ENAMETOOLONG;
    dl_copy(pending, path, length + 1);
    long current = path[0] == '/' ? dl_state.root_fd : directory(dirfd);
    if (current < 0) return current;
    int links = 0;
    size_t position = 0;
    long result = -ENOENT;
    for (;;) {
        if (!position && pending[0] == '/') {
            int alias; const char *tail;
            if (fd_alias(pending, &alias, &tail)) {
                if (alias < 0 || dl_internal_fd(alias)) { result = -ENOENT; break; }
                long present = DL_CALL(SYS_fcntl, alias, F_GETFD, 0, 0, 0, 0);
                if (present < 0) { result = present == -EBADF ? -ENOENT : present; break; }
                if (!*tail) {
                    if (leaf) { result = -EROFS; break; }
                    if (flags & O_NOFOLLOW) {
                        // A real magic-link O_PATH handle needs a guest proc
                        // inode model (C23); never return a host proc handle.
                        result = flags & O_PATH ? -EOPNOTSUPP : -ELOOP; break;
                    }
                    if ((flags & (O_CREAT | O_EXCL)) == (O_CREAT | O_EXCL)) { result = -EEXIST; break; }
                    if (context && !(flags & O_PATH)) {
                        long pin = dl_reopen(alias, O_PATH | O_CLOEXEC);
                        if (pin < 0) { result = pin == -EBADF ? -ENOENT : pin; break; }
                        char host[40]; dl_fd_path(host, (int)pin);
                        release((int)current); current = dl_state.root_fd;
                        result = blocking_open((int)pin, host, flags, mode, context);
                    } else result = dl_reopen(alias, flags);
                    if (result == -EBADF) result = -ENOENT;
                    break;
                }
                long pin = directory(alias);
                if (pin < 0) { result = pin == -EBADF ? -ENOENT : pin; break; }
                release((int)current); current = pin; position = (size_t)(tail - pending);
            }
        }
        while (pending[position] == '/') position++;
        size_t start = position;
        while (pending[position] && pending[position] != '/') position++;
        size_t size = position - start;
        int trailing = pending[position] == '/';
        while (pending[position] == '/') position++;
        int last = !pending[position];
        if (!size) { component[0] = '.'; component[1] = 0; size = 1; }
        else { dl_copy(component, pending + start, size); component[size] = 0; }
        if (leaf && last) {
            if (size + (unsigned)trailing + 1 > DL_PATH) { result = -ENAMETOOLONG; break; }
            dl_copy(leaf, component, size);
            if (trailing) leaf[size++] = '/';
            leaf[size] = 0;
            if (current != dl_state.root_fd) return current;
            return DL_CALL(SYS_fcntl, current, F_DUPFD_CLOEXEC, 0, 0, 0, 0);
        }
        if (dl_equal(component, "..")) {
            long parent = parent_directory((int)current);
            if (parent < 0) { result = parent; break; }
            release((int)current); current = parent;
            if (!last) continue;
            component[0] = '.'; component[1] = 0;
        }
        if (dl_equal(component, ".") && !last) continue;
        int device = dl_device_index((int)current, component);
        // O_DIRECTORY proves a successful prefix handle is a directory; no
        // second fstat is needed. A symlink returns ENOTDIR with O_NOFOLLOW,
        // then uses the existing pinned-link expansion below.
        if (!last && device < 0) {
            long prefix = DL_CALL(SYS_openat, current, component,
                O_PATH | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC, 0, 0, 0);
            if (prefix >= 0) { release((int)current); current = prefix; continue; }
            if (prefix != -ENOTDIR) { result = prefix; break; }
        }
        long pin = device >= 0 ? dl_device_open(device, (int)current, O_PATH | O_CLOEXEC, 0)
            : DL_CALL(SYS_openat, current, component, O_PATH | O_NOFOLLOW | O_CLOEXEC, 0, 0, 0);
        if (pin < 0) {
            if (pin == -ENOENT && last && !trailing && (flags & O_CREAT)) {
                result = dl_device_change((int)current, component, 0);
                if (!result && context) {
                    result = blocking_open((int)current, component, flags | O_NOFOLLOW, mode, context);
                    current = dl_state.root_fd;
                } else if (!result) result = DL_CALL(SYS_openat, current, component, flags | O_NOFOLLOW, mode, 0, 0);
            } else result = pin;
            break;
        }
        struct stat st;
        result = DL_CALL(SYS_fstat, pin, &st, 0, 0, 0, 0);
        if (result < 0) { release((int)pin); break; }
        if (last && S_ISLNK(st.st_mode) && (flags & (O_CREAT | O_EXCL)) == (O_CREAT | O_EXCL)) {
            result = -EEXIST; release((int)pin); break;
        }
        if (S_ISLNK(st.st_mode) && (!last || trailing || !(flags & O_NOFOLLOW))) {
            if (++links > 40) { result = -ELOOP; release((int)pin); break; }
            long n = DL_CALL(SYS_readlinkat, pin, "", target, DL_PATH - 1, 0, 0);
            release((int)pin);
            if (n < 0) { result = n; break; }
            if (!n || n == DL_PATH - 1) { result = n ? -ENAMETOOLONG : -ENOENT; break; }
            size_t rest = dl_length(pending + position);
            if ((size_t)n + 1 + rest + 1 > DL_PATH) { result = -ENAMETOOLONG; break; }
            target[n++] = '/'; dl_copy(target + n, pending + position, rest + 1);
            if (!rest && !trailing) target[n - 1] = 0;
            dl_copy(pending, target, dl_length(target) + 1); position = 0;
            if (target[0] == '/') { release((int)current); current = dl_state.root_fd; }
            continue;
        }
        if ((!last || trailing || (flags & O_DIRECTORY)) && !S_ISDIR(st.st_mode)) {
            result = -ENOTDIR; release((int)pin); break;
        }
        if (!last) { release((int)current); current = pin; continue; }
        if (flags & O_PATH) {
            if (!(flags & O_CLOEXEC)) DL_CALL(SYS_fcntl, pin, F_SETFD, 0, 0, 0, 0);
            result = pin;
        } else if (context && !S_ISLNK(st.st_mode)) {
            if ((flags & (O_CREAT | O_EXCL)) == (O_CREAT | O_EXCL)) {
                result = -EEXIST; release((int)pin); break;
            }
            // Reopen the pinned, approved inode without holding a lock while
            // waiting for a FIFO peer, device or regular-file lease. Only this descriptor survives
            // the wait, and destructive guest FD calls explicitly protect it.
            char host[64]; dl_fd_path(host, (int)pin);
            release((int)current); current = dl_state.root_fd;
            // The name is absolute, so the pin can also supply dirfd while
            // remaining owned by blocking_open until completion or collection.
            result = blocking_open((int)pin, host, flags & ~O_NOFOLLOW, mode, context);
        } else {
            release((int)pin);
            int final_flags = flags | O_NOFOLLOW | (trailing ? O_DIRECTORY : 0);
            result = device >= 0 ? dl_device_open(device, (int)current, final_flags, mode)
                : DL_CALL(SYS_openat, current, component, final_flags, mode, 0, 0);
        }
        break;
    }
    release((int)current);
    return result;
}

long dl_open(int dirfd, const char *path, int flags, unsigned mode)
{
    if (flags & O_PATH) flags &= O_PATH | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC;
    return walk(dirfd, path, flags, mode, NULL, NULL);
}

long dl_socket_path(const char *path, char out[DL_PATH], int binding)
{
    // Snapshot a root-relative name before entering an interruptible socket
    // call. No temporary descriptor survives a guest handler or siglongjmp.
    // This is pathname translation, not an atomic lookup across concurrent
    // renames: the kernel resolves the resulting name when the socket call runs.
    char leaf[DL_PATH];
    long fd = binding ? walk(AT_FDCWD, path, 0, 0, leaf, NULL)
        : dl_open(AT_FDCWD, path, O_PATH | O_CLOEXEC, 0);
    if (fd < 0) return fd;
    long r = binding ? dl_device_change((int)fd, leaf, 0) : 0;
    if (!r) r = dl_file_path((int)fd, out);
    if (!r && binding) {
        size_t n = dl_length(out), length = dl_length(leaf);
        if (n + 1 + length + 1 > DL_PATH) r = -ENAMETOOLONG;
        else {
            if (n > 1) out[n++] = '/';
            dl_copy(out + n, leaf, length + 1);
        }
    }
    release((int)fd); return r;
}

static long name_change(long nr, unsigned long *a)
{
    char path[DL_PATH], first[DL_PATH], second[DL_PATH];
    const char *source = (void *)a[1];
    int dirfd = (int)a[0];
    if (nr == SYS_symlinkat) { source = (void *)a[2]; dirfd = (int)a[1]; }
    long r = dl_string(path, source); if (r) return r;
    long parent = walk(dirfd, path, 0, 0, first, NULL);
    if (parent < 0) return parent;
    r = dl_device_change((int)parent, first, nr != SYS_mkdirat && nr != SYS_symlinkat);
    if (r) { release((int)parent); return r; }
    if (nr == SYS_mkdirat || nr == SYS_unlinkat) r = DL_CALL(nr, parent, first, a[2], 0, 0, 0);
    else if (nr == SYS_symlinkat) {
        r = dl_string(path, (void *)a[0]);
        if (!r) r = DL_CALL(nr, path, parent, first, 0, 0, 0);
    } else {
        r = dl_string(path, (void *)a[3]);
        if (!r) {
            long destination = walk((int)a[2], path, 0, 0, second, NULL);
            if (destination < 0) r = destination;
            else {
                r = dl_device_change((int)destination, second, 1);
                if (!r) r = DL_CALL(nr, parent, first, destination, second, nr == SYS_renameat2 ? a[4] : 0, 0);
                release((int)destination);
            }
        }
    }
    release((int)parent); return r;
}

static long attributes(long nr, unsigned long *a)
{
    long plain = nr;
    switch (nr) {
    case SYS_lgetxattr: plain = SYS_getxattr; break;
    case SYS_lsetxattr: plain = SYS_setxattr; break;
    case SYS_llistxattr: plain = SYS_listxattr; break;
    case SYS_lremovexattr: plain = SYS_removexattr; break;
    }
    char path[DL_PATH];
    long r = dl_string(path, (void *)a[0]); if (r) return r;
    long pin = dl_open(AT_FDCWD, path, O_PATH | O_CLOEXEC | (plain != nr ? O_NOFOLLOW : 0), 0);
    if (pin < 0) return pin;
    struct stat st;
    r = DL_CALL(SYS_fstat, pin, &st, 0, 0, 0, 0);
    if (!r && S_ISLNK(st.st_mode)) {
        // Older Android kernels cannot operate on an O_PATH symlink through
        // f*xattr. Pin its parent and use a no-follow leaf operation instead.
        // Like the original pathname API, concurrent leaf replacement may select
        // either inode; it must never follow the leaf to a host absolute target.
        char leaf[DL_PATH];
        long parent = walk(AT_FDCWD, path, 0, 0, leaf, NULL);
        if (parent < 0) r = parent;
        else {
            dl_fd_path(path, (int)parent);
            size_t prefix = dl_length(path), length = dl_length(leaf);
            if (prefix + 1 + length + 1 > DL_PATH) r = -ENAMETOOLONG;
            else {
                path[prefix++] = '/'; dl_copy(path + prefix, leaf, length + 1);
                r = DL_CALL(nr, path, a[1], a[2], a[3], a[4], 0);
            }
            release((int)parent);
        }
    } else if (!r) {
        dl_fd_path(path, (int)pin);
        r = DL_CALL(plain, path, a[1], a[2], a[3], a[4], 0);
    }
    release((int)pin); return r;
}

long dl_filesystem(long nr, unsigned long *a, void *context)
{
    char path[DL_PATH]; long r, fd;
    switch (nr) {
    case SYS_getxattr: case SYS_lgetxattr: case SYS_setxattr: case SYS_lsetxattr:
    case SYS_listxattr: case SYS_llistxattr: case SYS_removexattr: case SYS_lremovexattr:
        return attributes(nr, a);
    case SYS_mkdirat: case SYS_unlinkat: case SYS_renameat: case SYS_renameat2: case SYS_symlinkat:
        return name_change(nr, a);
    case SYS_openat:
        r = dl_string(path, (void *)a[1]); if (r) return r;
        if ((a[2] & O_TMPFILE) == O_TMPFILE) return -EOPNOTSUPP;
        int flags = (int)a[2];
        if (flags & O_PATH) flags &= O_PATH | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC;
        return walk((int)a[0], path, flags, (unsigned)a[3], NULL, context);
    case SYS_newfstatat:
    case SYS_statx:
        r = dl_string(path, (void *)a[1]); if (r) return r;
        {
            unsigned flags = nr == SYS_statx ? a[2] : a[3];
            unsigned allowed = AT_SYMLINK_NOFOLLOW | AT_EMPTY_PATH | AT_NO_AUTOMOUNT;
            if (nr == SYS_statx) allowed |= 0x6000;
            if (flags & ~allowed) return -EINVAL;
            if (!path[0] && (flags & AT_EMPTY_PATH)) return DL_CALL(nr, a[0], a[1], a[2], a[3], a[4], a[5]);
            fd = dl_open((int)a[0], path, O_PATH | O_CLOEXEC | ((flags & AT_SYMLINK_NOFOLLOW) ? O_NOFOLLOW : 0), 0);
            if (fd < 0) return fd;
            r = nr == SYS_statx ? DL_CALL(SYS_statx, fd, "", (flags | AT_EMPTY_PATH) & ~AT_SYMLINK_NOFOLLOW, a[3], a[4], 0)
                : DL_CALL(SYS_fstat, fd, a[2], 0, 0, 0, 0);
            release((int)fd); return r;
        }
    case SYS_faccessat: case SYS_fchmodat:
        r = dl_string(path, (void *)a[1]); if (r) return r;
        fd = dl_open((int)a[0], path, O_PATH | O_CLOEXEC, 0); if (fd < 0) return fd;
        dl_fd_path(path, (int)fd);
        r = DL_CALL(nr, AT_FDCWD, path, a[2], 0, 0, 0);
        release((int)fd); return r;
    case SYS_fchownat:
        if (a[4] & ~(AT_EMPTY_PATH | AT_SYMLINK_NOFOLLOW)) return -EINVAL;
        r = dl_string(path, (void *)a[1]); if (r) return r;
        if (!path[0] && (a[4] & AT_EMPTY_PATH)) return DL_CALL(nr, a[0], path, a[2], a[3], a[4], 0);
        fd = dl_open((int)a[0], path, O_PATH | O_CLOEXEC | ((a[4] & AT_SYMLINK_NOFOLLOW) ? O_NOFOLLOW : 0), 0);
        if (fd < 0) return fd;
        r = DL_CALL(nr, fd, "", a[2], a[3], AT_EMPTY_PATH | AT_SYMLINK_NOFOLLOW, 0);
        release((int)fd); return r;
    case SYS_utimensat:
        if (a[3] & ~(AT_EMPTY_PATH | AT_SYMLINK_NOFOLLOW)) return -EINVAL;
        if (!a[1]) return DL_CALL(nr, a[0], 0, a[2], a[3], 0, 0); // futimens
        if (a[2]) {
            struct timespec times[2];
            r = dl_read_memory(times, (void *)a[2], sizeof(times)); if (r) return r;
            // Linux performs no pathname lookup when both fields are omitted.
            if (times[0].tv_nsec == UTIME_OMIT && times[1].tv_nsec == UTIME_OMIT)
                return DL_CALL(nr, dl_state.root_fd, "", times, a[3], 0, 0);
        }
        r = dl_string(path, (void *)a[1]); if (r) return r;
        if (!path[0] && (a[3] & AT_EMPTY_PATH)) return DL_CALL(nr, a[0], path, a[2], a[3], 0, 0);
        fd = dl_open((int)a[0], path, O_PATH | O_CLOEXEC | ((a[3] & AT_SYMLINK_NOFOLLOW) ? O_NOFOLLOW : 0), 0);
        if (fd < 0) return fd;
        {
            struct stat st;
            r = DL_CALL(SYS_fstat, fd, &st, 0, 0, 0, 0);
            if (!r) {
                if (S_ISLNK(st.st_mode)) {
                    // Requires Linux 5.8 AT_EMPTY_PATH support for a pinned
                    // symlink. Older kernels report the real unsupported error.
                    r = DL_CALL(nr, fd, "", a[2], AT_EMPTY_PATH | AT_SYMLINK_NOFOLLOW, 0, 0);
                } else {
                    dl_fd_path(path, (int)fd);
                    r = DL_CALL(nr, AT_FDCWD, path, a[2], 0, 0, 0);
                }
            }
        }
        release((int)fd); return r;
    case SYS_statfs: case SYS_truncate:
        r = dl_string(path, (void *)a[0]); if (r) return r;
        fd = dl_open(AT_FDCWD, path, O_PATH | O_CLOEXEC, 0); if (fd < 0) return fd;
        dl_fd_path(path, (int)fd);
        r = DL_CALL(nr, path, a[1], 0, 0, 0, 0);
        release((int)fd); return r;
    case SYS_readlinkat:
        r = dl_string(path, (void *)a[1]); if (r) return r;
        {
            int alias; const char *tail;
            if (fd_alias(path, &alias, &tail) && !*tail) {
                if (!a[3]) return -EINVAL;
                if (alias < 0 || dl_internal_fd(alias)) return -ENOENT;
                r = dl_file_path(alias, path); if (r) return r == -EBADF ? -ENOENT : r;
                size_t size = dl_length(path); if (size > a[3]) size = a[3];
                r = dl_write_memory((void *)a[2], path, size); return r ? r : (long)size;
            }
        }
        if (dl_equal(path, "/proc/self/exe")) {
            if (!a[3]) return -EINVAL;
            size_t size = dl_length(dl_state.executable); if (size > a[3]) size = a[3];
            r = dl_write_memory((void *)a[2], dl_state.executable, size); return r ? r : (long)size;
        }
        if (!path[0]) return DL_CALL(SYS_readlinkat, a[0], a[1], a[2], a[3], 0, 0);
        fd = dl_open((int)a[0], path, O_PATH | O_CLOEXEC | O_NOFOLLOW, 0); if (fd < 0) return fd;
        r = DL_CALL(SYS_readlinkat, fd, "", a[2], a[3], 0, 0);
        release((int)fd); return r;
    case SYS_getcwd:
        r = dl_virtual_cwd(path); if (r) return r;
        { size_t size = dl_length(path) + 1; if (size > a[1]) return -ERANGE;
          r = dl_write_memory((void *)a[0], path, size); return r ? r : (long)size; }
    case SYS_chdir:
        r = dl_string(path, (void *)a[0]); if (r) return r;
        fd = dl_open(AT_FDCWD, path, O_PATH | O_DIRECTORY | O_CLOEXEC, 0); if (fd < 0) return fd;
        r = DL_CALL(SYS_fchdir, fd, 0, 0, 0, 0, 0); release((int)fd); return r;
    case SYS_fchdir:
        fd = directory((int)a[0]); if (fd < 0) return fd;
        r = DL_CALL(SYS_fchdir, fd, 0, 0, 0, 0, 0); release((int)fd); return r;
    default: return -ENOSYS;
    }
}
