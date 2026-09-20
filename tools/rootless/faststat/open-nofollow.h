/* SPDX-License-Identifier: GPL-2.0-or-later */
#ifndef PROOT_OPEN_NOFOLLOW_H
#define PROOT_OPEN_NOFOLLOW_H
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
/* Walk fresh pinned directory FDs using Android's allowed openat syscall.
 * Never follow a symlink; unsupported spelling returns to PRoot's resolver.
 * The final O_PATH FD may name a symlink: callers MUST validate its type
 * before using it, or request O_DIRECTORY. This lets their required metadata
 * syscall also perform the final type check, without a duplicate fstat.
 * No directory FD, inode classification or metadata is cached across calls.
 * call returns raw Linux results (negative errno), including close.
 */
static long proot_pin_walk_nofollow(long (*call)(long, long, long, long, long),
                              long fd, const char *part, int flags)
{
    while (*part == '/') part++;
    while (*part) {
        const char *end = strchr(part, '/');
        size_t length = end ? (size_t)(end - part) : strlen(part);
        if (length > NAME_MAX || (length == 2 && !memcmp(part, "..", 2))) {
            call(SYS_close, fd, 0, 0, 0);
            return -EOPNOTSUPP;
        }
        char name[NAME_MAX + 1];
        memcpy(name, part, length); name[length] = 0;
        const char *next = end ? end : part + length;
        while (*next == '/') next++;
        int directory = end != NULL || (flags & O_DIRECTORY);
        long child = call(SYS_openat, fd, (long)name,
                         O_PATH | O_CLOEXEC | O_NOFOLLOW | (directory ? O_DIRECTORY : 0), 0);
        call(SYS_close, fd, 0, 0, 0);
        if (child < 0) return child;
        fd = child;
        part = next;
    }
    return fd;
}
static long proot_pin_nofollow(long (*call)(long, long, long, long, long),
                              int dirfd, const char *path, int flags)
{
    if (!path || !*path) return -ENOENT;
    long fd = call(SYS_openat, path[0] == '/' ? AT_FDCWD : dirfd,
                   (long)(path[0] == '/' ? "/" : "."), O_PATH | O_DIRECTORY | O_CLOEXEC, 0);
    if (fd < 0) return fd;
    return proot_pin_walk_nofollow(call, fd, path, flags);
}
/* The published binding is a host-selected namespace root, not guest path
 * components. Open its host ancestry once, then pin each guest suffix component
 * without following links; the caller validates the final object as above.
 * A fresh root FD is opened each time; no stale path cache.
 */
static long proot_pin_bound_nofollow(long (*call)(long, long, long, long, long),
                                    const char *path, size_t anchor_length, int flags)
{
    if (!anchor_length || anchor_length >= PATH_MAX || strlen(path) < anchor_length)
        return -EINVAL;
    char anchor[PATH_MAX];
    memcpy(anchor, path, anchor_length); anchor[anchor_length] = 0;
    const char *suffix = path + anchor_length;
    long fd = call(SYS_openat, AT_FDCWD, (long)anchor,
                   O_PATH | O_CLOEXEC | O_NOFOLLOW | ((*suffix || (flags & O_DIRECTORY)) ? O_DIRECTORY : 0), 0);
    if (fd < 0) return fd;
    return proot_pin_walk_nofollow(call, fd, suffix, flags);
}
#ifdef PROOT_NOFOLLOW_TRACER
#include <unistd.h>
static long proot_nofollow_call(long nr, long a, long b, long c, long d)
{
    long result = syscall(nr, a, b, c, d);
    return result == -1 ? -errno : result;
}
#endif
#endif
