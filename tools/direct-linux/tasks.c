/* SPDX-License-Identifier: Apache-2.0 */
/* Bounded host task observation; this does not expose procfs to the guest. */
#define _GNU_SOURCE
#include "runtime.h"
#include <errno.h>
#include <fcntl.h>
#include <sys/syscall.h>

static long parse_status(const char *data, size_t size, int tid, struct dl_task_status *result)
{
    const char *end = data + size, *p = data;
    unsigned long identifier = 0;
    if (p == end || *p < '0' || *p > '9') return -EIO;
    while (p < end && *p >= '0' && *p <= '9') {
        if (identifier > (0x7fffffffUL - (unsigned)(*p - '0')) / 10) return -EIO;
        identifier = identifier * 10 + (unsigned)(*p++ - '0');
    }
    if (identifier != (unsigned)tid) return -EAGAIN;
    if (end - p < 2 || p[0] != ' ' || p[1] != '(') return -EIO;
    // comm may contain spaces, newlines and ')'. Numeric fields after the
    // final ')' cannot, so do not tokenize the name as whitespace-separated.
    const char *close = NULL;
    for (p += 2; p < end; p++) if (*p == ')') close = p;
    if (!close || end - close < 4 || close[1] != ' ' || close[3] != ' ') return -EIO;
    char state = close[2];
    if (state != 'R' && state != 'S' && state != 'D' && state != 'T' && state != 't' &&
            state != 'Z' && state != 'X' && state != 'x' && state != 'I' && state != 'P') return -EIO;
    p = close + 4;
    for (unsigned field = 4; field <= 28; field++) {
        int negative = p < end && *p == '-';
        if (negative) p++;
        if (p == end || *p < '0' || *p > '9') return -EIO;
        uintptr_t value = 0;
        while (p < end && *p >= '0' && *p <= '9') {
            if (value > (UINTPTR_MAX - (unsigned)(*p - '0')) / 10) return -EIO;
            value = value * 10 + (unsigned)(*p++ - '0');
        }
        if (p == end || *p++ != ' ') return -EIO;
        if (field == 28) {
            if (negative) return -EIO;
            *result = (struct dl_task_status){.tid = tid, .state = state, .start_stack = value};
        }
    }
    return 0;
}

long dl_task_status(int tid, struct dl_task_status *result)
{
    if (tid <= 0) return -EINVAL;
    char name[40] = "/proc/", digits[16]; size_t count = 0, used = 6;
    unsigned value = (unsigned)tid;
    do { digits[count++] = (char)('0' + value % 10); value /= 10; } while (value);
    while (count) name[used++] = digits[--count];
    const char suffix[] = "/stat";
    for (size_t i = 0; i < sizeof(suffix); i++) name[used++] = suffix[i];
    long fd = DL_CALL(SYS_openat, AT_FDCWD, name, O_RDONLY | O_CLOEXEC | O_NOFOLLOW, 0, 0, 0);
    if (fd < 0) return fd;
    char data[2048]; size_t size = 0; long r;
    for (;;) {
        r = DL_CALL(SYS_read, fd, data + size, sizeof(data) - size, 0, 0, 0);
        if (r == -EINTR) continue;
        if (r <= 0) break;
        size += (size_t)r;
        if (size == sizeof(data)) { r = -EOVERFLOW; break; }
    }
    DL_CALL(SYS_close, fd, 0, 0, 0, 0, 0);
    return r < 0 ? r : parse_status(data, size, tid, result);
}
