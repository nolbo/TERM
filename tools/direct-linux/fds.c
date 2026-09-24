/* SPDX-License-Identifier: Apache-2.0 */
#include "runtime.h"
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <linux/close_range.h>
#include <sys/syscall.h>

enum fd_action { CLOSE_ALL, MARK_EXEC, CLOSE_EXEC };

static long sweep(unsigned first, unsigned last, enum fd_action action, int keep_a, int keep_b)
{
    if (first > INT_MAX) return 0;
    // Signals are blocked by the dispatcher and supported clone variants never
    // share FD tables. Inspect actual open FDs, not every integer below RLIMIT.
    // This host-only directory is never exposed in the guest filesystem.
    long scan = DL_CALL(SYS_openat, AT_FDCWD, "/proc/self/fd", O_RDONLY | O_DIRECTORY | O_CLOEXEC, 0, 0, 0);
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
                    value != (unsigned)dl_state.root_fd && value != (unsigned)dl_state.vfork_fd &&
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
    // The APK launcher starts with a private FD table, and CLONE_FILES/threads
    // are rejected. UNSHARE therefore has no additional work in this runtime.
    // Revisit this invariant before adding shared-table clone or guest threads.
    return sweep(first, last, flags & CLOSE_RANGE_CLOEXEC ? MARK_EXEC : CLOSE_ALL, -1, -1);
}

long dl_close_exec_fds(int main_fd, int interpreter_fd)
{
    return sweep(0, UINT_MAX, CLOSE_EXEC, main_fd, interpreter_fd);
}
