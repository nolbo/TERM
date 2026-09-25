/* SPDX-License-Identifier: Apache-2.0 */
/* Address-bearing calls cannot bypass guest filesystem policy. */
#include "runtime.h"
#include "threads.h"
#include <errno.h>
#include <signal.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/un.h>

static size_t root_alias(char *out)
{
    return dl_fd_path(out, dl_state.root_fd);
}

static long address(struct sockaddr_storage *copy, const void *source, unsigned *size, int binding)
{
    if (*size < sizeof(sa_family_t) || *size > sizeof(*copy)) return -EINVAL;
    long r = dl_read_memory(copy, source, *size); if (r) return r;
    if (copy->ss_family == AF_UNIX) {
        struct sockaddr_un *local = (void *)copy;
        size_t offset = offsetof(struct sockaddr_un, sun_path);
        if (*size > sizeof(*local)) return -EINVAL;
        if (*size == offset || !local->sun_path[0]) return -EOPNOTSUPP;
        char path[DL_PATH], canonical[DL_PATH];
        size_t count = *size - offset;
        dl_copy(path, local->sun_path, count); path[count] = 0;
        dl_lock(&dl_files_lock);
        r = dl_socket_path(path, canonical, binding);
        dl_unlock(&dl_files_lock);
        if (r) return r;
        size_t prefix = root_alias(local->sun_path), length = dl_length(canonical);
        // Full-length Linux names still need a shared alias/binding design.
        // Never truncate a socket name into a different endpoint.
        if (prefix + length + 1 > sizeof(local->sun_path)) return -ENAMETOOLONG;
        dl_copy(local->sun_path + prefix, canonical, length + 1);
        *size = offset + prefix + length + 1;
        return 0;
    }
    if (copy->ss_family != AF_INET && copy->ss_family != AF_INET6 && copy->ss_family != AF_UNSPEC)
        return -EAFNOSUPPORT;
    return 0;
}

static long output_address(struct sockaddr_storage *value, unsigned *size, void *destination, unsigned capacity)
{
    if (*size > sizeof(*value)) return -EOVERFLOW;
    if (*size > sizeof(sa_family_t) && value->ss_family == AF_UNIX) {
        struct sockaddr_un *local = (void *)value;
        if (local->sun_path[0]) {
            const char prefix[] = "/proc/";
            size_t offset = sizeof(prefix) - 1, bytes = *size - offsetof(struct sockaddr_un, sun_path);
            if (bytes > sizeof(local->sun_path) || bytes <= offset ||
                    !dl_equal_memory(local->sun_path, prefix, offset)) return -EXDEV;
            if (bytes >= offset + 8 && dl_equal_memory(local->sun_path + offset, "self/fd/", 8)) offset += 8;
            else {
                size_t start = offset;
                while (offset < bytes && local->sun_path[offset] >= '0' && local->sun_path[offset] <= '9') offset++;
                if (start == offset || bytes < offset + 4 || !dl_equal_memory(local->sun_path + offset, "/fd/", 4))
                    return -EXDEV;
                offset += 4;
            }
            size_t digits = offset;
            while (offset < bytes && local->sun_path[offset] >= '0' && local->sun_path[offset] <= '9') offset++;
            if (offset == digits || offset >= bytes || local->sun_path[offset] != '/') return -EXDEV;
            // A peer may have been launched independently with a different
            // root-FD number. The kernel name is a root-relative alias in either
            // case; never return an Android app-private pathname to the guest.
            dl_copy(local->sun_path, local->sun_path + offset, bytes - offset);
            *size -= offset;
        }
    }
    size_t count = *size < capacity ? *size : capacity;
    return count ? dl_write_memory(destination, value, count) : 0;
}

long dl_network(long nr, unsigned long a[6], void *context)
{
    struct sockaddr_storage peer;
    struct msghdr message;
    long r; unsigned length;
    switch (nr) {
    case SYS_socket:
        if (a[0] != AF_INET && a[0] != AF_INET6 && a[0] != AF_UNIX) return -EAFNOSUPPORT;
        return DL_CALL(nr, a[0], a[1], a[2], 0, 0, 0);
    case SYS_socketpair:
        if (a[0] != AF_UNIX) return -EAFNOSUPPORT;
        return DL_CALL(nr, a[0], a[1], a[2], a[3], 0, 0);
    case SYS_bind: case SYS_connect:
        length = (unsigned)a[2];
        r = address(&peer, (void *)a[1], &length, nr == SYS_bind); if (r) return r;
        a[1] = (unsigned long)&peer; a[2] = length;
        break;
    case SYS_sendto:
        if (a[4]) {
            length = (unsigned)a[5];
            r = address(&peer, (void *)a[4], &length, 0); if (r) return r;
            a[4] = (unsigned long)&peer; a[5] = length;
        }
        break;
    case SYS_sendmsg:
        r = dl_read_memory(&message, (void *)a[1], sizeof(message)); if (r) return r;
        if (message.msg_name) {
            r = address(&peer, message.msg_name, &message.msg_namelen, 0); if (r) return r;
            message.msg_name = &peer;
        }
        a[1] = (unsigned long)&message;
        break;
    case SYS_getsockname: case SYS_getpeername: case SYS_accept: case SYS_accept4: case SYS_recvfrom:
        {
            unsigned index = nr == SYS_recvfrom ? 4 : 1;
            void *destination = (void *)a[index], *size_pointer = (void *)a[index + 1];
            if (!destination && nr != SYS_getsockname && nr != SYS_getpeername) break;
            unsigned capacity;
            r = dl_read_memory(&capacity, size_pointer, sizeof(capacity)); if (r) return r;
            if (capacity > 0x7fffffffU) return -EINVAL;
            length = sizeof(peer);
            a[index] = (unsigned long)&peer; a[index + 1] = (unsigned long)&length;
            r = dl_blocking(nr, a, context);
            if (r < 0) return r;
            long copied = output_address(&peer, &length, destination, capacity);
            if (!copied) copied = dl_write_memory(size_pointer, &length, sizeof(length));
            if (copied && (nr == SYS_accept || nr == SYS_accept4)) DL_CALL(SYS_close, r, 0, 0, 0, 0, 0);
            return copied ? copied : r;
        }
    case SYS_recvmsg:
        {
            void *header = (void *)a[1];
            r = dl_read_memory(&message, header, sizeof(message)); if (r) return r;
            if (!message.msg_name) break;
            void *destination = message.msg_name; unsigned capacity = message.msg_namelen;
            message.msg_name = &peer; message.msg_namelen = sizeof(peer);
            a[1] = (unsigned long)&message;
            r = dl_blocking(nr, a, context); if (r < 0) return r;
            long copied = output_address(&peer, &message.msg_namelen, destination, capacity);
            if (!copied) copied = dl_write_memory((char *)header + offsetof(struct msghdr, msg_namelen),
                &message.msg_namelen, sizeof(message.msg_namelen));
            if (!copied) copied = dl_write_memory((char *)header + offsetof(struct msghdr, msg_controllen),
                &message.msg_controllen, sizeof(message.msg_controllen));
            if (!copied) copied = dl_write_memory((char *)header + offsetof(struct msghdr, msg_flags),
                &message.msg_flags, sizeof(message.msg_flags));
            return copied ? copied : r;
        }
    case SYS_recvmmsg:
        {
            int family; length = sizeof(family);
            r = DL_CALL(SYS_getsockopt, a[0], SOL_SOCKET, SO_DOMAIN, &family, &length, 0);
            if (r < 0) return r;
            // Batched Unix address translation is still required. Do not leak
            // untranslated root aliases through a newly supported socket kind.
            if (family == AF_UNIX) {
                length = sizeof(peer);
                r = DL_CALL(SYS_getsockname, a[0], &peer, &length, 0, 0, 0);
                if (r < 0) return r;
                if (length != sizeof(sa_family_t)) return -EOPNOTSUPP;
                length = sizeof(peer);
                r = DL_CALL(SYS_getpeername, a[0], &peer, &length, 0, 0, 0);
                if (r < 0) return r;
                if (length != sizeof(sa_family_t)) return -EOPNOTSUPP;
            }
            break;
        }
    default: return -ENOSYS;
    }
    return dl_blocking(nr, a, context);
}
