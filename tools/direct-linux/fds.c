/* SPDX-License-Identifier: Apache-2.0 */
#include "runtime.h"
#include "threads.h"
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <linux/close_range.h>
#include <sys/syscall.h>

enum fd_action { CLOSE_ALL, MARK_EXEC, CLOSE_EXEC };

static long sweep(unsigned first, unsigned last, enum fd_action action, int keep_a, int keep_b)
{
    if (first > INT_MAX) return 0;
    // Caller holds the runtime filesystem lock, excluding transient path FDs.
    // Inspect actual open FDs, not every integer below RLIMIT.
    // This host-only directory is never exposed in the guest filesystem.
    char path[40]; dl_fd_path(path, -1);
    long scan = DL_CALL(SYS_openat, AT_FDCWD, path, O_RDONLY | O_DIRECTORY | O_CLOEXEC, 0, 0, 0);
    if (scan < 0) return scan;
    char buffer[4096]; long result = 0;
    struct dent { uint64_t ino; int64_t off; unsigned short size; unsigned char type; char name[]; };
    for (;;) {
        long n = DL_CALL(SYS_getdents64, scan, buffer, sizeof(buffer), 0, 0, 0);
        if (n == -EINTR) continue;
        if (n <= 0) { result = n; break; }
        for (long offset = 0; offset < n;) {
            struct dent *d = (void *)(buffer + offset);
            if (d->size < offsetof(struct dent, name) + 2 || d->size > n - offset) { result = -EIO; goto done; }
            unsigned value = 0; size_t i = 0;
            while (i < d->size - offsetof(struct dent, name) && d->name[i] >= '0' && d->name[i] <= '9') {
                unsigned digit = d->name[i++] - '0';
                if (value > ((unsigned)INT_MAX - digit) / 10) { result = -EIO; goto done; }
                value = value * 10 + digit;
            }
            if (i && i < d->size - offsetof(struct dent, name) && !d->name[i] &&
                    value >= first && value <= last && value != (unsigned)scan &&
                    !dl_internal_fd((int)value) &&
                    value != (unsigned)keep_a && value != (unsigned)keep_b) {
                if (action == CLOSE_ALL) {
                    // Linux close_range ignores individual close errors.
                    DL_CALL(SYS_close, value, 0, 0, 0, 0, 0);
                } else {
                    long flags = DL_CALL(SYS_fcntl, value, F_GETFD, 0, 0, 0, 0);
                    if (flags < 0 && flags != -EBADF) { result = flags; goto done; }
                    if (flags >= 0) {
                        if (action == MARK_EXEC) {
                            long r = DL_CALL(SYS_fcntl, value, F_SETFD, flags | FD_CLOEXEC, 0, 0, 0);
                            if (r < 0) { result = r; goto done; }
                        } else if (flags & FD_CLOEXEC) DL_CALL(SYS_close, value, 0, 0, 0, 0, 0);
                    }
                }
            }
            offset += d->size;
        }
    }
done:
    DL_CALL(SYS_close, scan, 0, 0, 0, 0, 0); return result;
}

long dl_close_range(unsigned first, unsigned last, unsigned flags)
{
    if (first > last || (flags & ~(CLOSE_RANGE_UNSHARE | CLOSE_RANGE_CLOEXEC))) return -EINVAL;
    dl_lock(&dl_files_lock);
    long r = 0;
    if ((flags & CLOSE_RANGE_UNSHARE) && dl_files_may_be_shared()) {
        // Unshare without changing any valid descriptor. Never silently treat
        // UNSHARE as a no-op once a CLONE_FILES child may exist.
        r = dl_files_unshare();
    }
    if (!r && first <= INT_MAX) {
        unsigned begin = first, end = last > INT_MAX ? INT_MAX : last;
        unsigned kernel_flags = flags & CLOSE_RANGE_CLOEXEC;
        for (;;) {
            unsigned keep = dl_next_internal_fd(begin);
            unsigned stop = keep <= end ? keep - 1 : end;
            if (keep != begin) r = DL_CALL(SYS_close_range, begin, stop, kernel_flags, 0, 0, 0);
            if (r || keep >= end) break;
            begin = keep + 1;
        }
        // Older kernels may lack close_range or CLOEXEC. Preserve the existing
        // proc enumerator there; supported kernels need no spare FD, even at
        // EMFILE, and keep their atomic per-range descriptor-table behavior.
        if (r == -ENOSYS || r == -EINVAL)
            r = sweep(first, last, kernel_flags ? MARK_EXEC : CLOSE_ALL, -1, -1);
    }
    dl_unlock(&dl_files_lock); return r;
}

long dl_close_exec_fds(int main_fd, int interpreter_fd)
{
    dl_lock(&dl_files_lock);
    long r = sweep(0, UINT_MAX, CLOSE_EXEC, main_fd, interpreter_fd);
    dl_unlock(&dl_files_lock); return r;
}
