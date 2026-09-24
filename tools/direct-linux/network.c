/* SPDX-License-Identifier: Apache-2.0 */
/* Address-bearing calls cannot bypass guest filesystem policy. */
#include "runtime.h"
#include <errno.h>
#include <signal.h>
#include <sys/socket.h>
#include <sys/syscall.h>

static long address(struct sockaddr_storage *copy, const void *source, unsigned size)
{
    if (size < sizeof(sa_family_t) || size > sizeof(*copy)) return -EINVAL;
    long r = dl_read_memory(copy, source, size); if (r) return r;
    if (copy->ss_family == AF_UNIX) return -EOPNOTSUPP;
    if (copy->ss_family != AF_INET && copy->ss_family != AF_INET6 && copy->ss_family != AF_UNSPEC)
        return -EAFNOSUPPORT;
    return 0;
}

static long interruptible(long nr, unsigned long a[6], unsigned long mask)
{
    // No runtime lock, temporary FD, or allocated memory is live here. Guest
    // handlers may reenter the dispatcher or siglongjmp out of this call.
    // In particular, never leave SIGSYS blocked while a guest handler runs.
    mask &= ~(1UL << (SIGSYS - 1));
    unsigned long previous;
    long r = DL_CALL(SYS_rt_sigprocmask, SIG_SETMASK, &mask, &previous, 8, 0, 0);
    if (r) return r;
    r = DL_CALL(nr, a[0], a[1], a[2], a[3], a[4], a[5]);
    long restored = DL_CALL(SYS_rt_sigprocmask, SIG_SETMASK, &previous, 0, 8, 0, 0);
    return restored ? restored : r;
}

long dl_network(long nr, unsigned long a[6], unsigned long mask)
{
    struct sockaddr_storage peer;
    struct msghdr message;
    long r;
    switch (nr) {
    case SYS_socket:
        // Named local sockets need rootfs translation and address lifetime
        // handling. Do not accidentally expose Android's host socket paths.
        if (a[0] != AF_INET && a[0] != AF_INET6) return -EAFNOSUPPORT;
        return DL_CALL(nr, a[0], a[1], a[2], 0, 0, 0);
    case SYS_socketpair:
        if (a[0] != AF_UNIX) return -EAFNOSUPPORT;
        return DL_CALL(nr, a[0], a[1], a[2], a[3], 0, 0);
    case SYS_bind: case SYS_connect:
        r = address(&peer, (void *)a[1], (unsigned)a[2]); if (r) return r;
        a[1] = (unsigned long)&peer;
        break;
    case SYS_sendto:
        if (a[4]) {
            r = address(&peer, (void *)a[4], (unsigned)a[5]); if (r) return r;
            a[4] = (unsigned long)&peer;
        }
        break;
    case SYS_sendmsg:
        r = dl_read_memory(&message, (void *)a[1], sizeof(message)); if (r) return r;
        if (message.msg_name) {
            r = address(&peer, message.msg_name, message.msg_namelen); if (r) return r;
            message.msg_name = &peer;
        }
        a[1] = (unsigned long)&message;
        break;
    default: return -ENOSYS;
    }
    return interruptible(nr, a, mask);
}
