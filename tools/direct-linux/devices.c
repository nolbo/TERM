/* SPDX-License-Identifier: Apache-2.0 */
/* Fixed, ordinary-UID byte devices. Never a bind of Android's whole /dev. */
#define _GNU_SOURCE
#include "runtime.h"
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

static const struct { const char *name, *host; unsigned device; } devices[] = {
    {"null", "/dev/null", 0x103}, {"zero", "/dev/zero", 0x105},
    {"random", "/dev/random", 0x108}, {"urandom", "/dev/urandom", 0x109},
    {"tty", "/dev/tty", 0x500},
};
static unsigned long directory_device, directory_inode;

static int matches(const struct stat *st)
{
    return directory_inode && st->st_dev == directory_device && st->st_ino == directory_inode;
}
static int is_directory(int fd)
{
    struct stat st;
    return !DL_CALL(SYS_fstat, fd, &st, 0, 0, 0, 0) && matches(&st);
}
static int index_of(const char *name)
{
    for (unsigned i = 0; i < sizeof(devices) / sizeof(devices[0]); i++)
        if (dl_equal(name, devices[i].name)) return (int)i;
    return -1;
}

long dl_devices_init(void)
{
    long r = DL_CALL(SYS_mkdirat, dl_state.root_fd, "dev", 0755, 0, 0, 0);
    if (r && r != -EEXIST) return r;
    long dir = DL_CALL(SYS_openat, dl_state.root_fd, "dev", O_PATH | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC, 0, 0, 0);
    if (dir < 0) return dir;
    struct stat st;
    r = DL_CALL(SYS_fstat, dir, &st, 0, 0, 0, 0);
    if (!r) { directory_device = st.st_dev; directory_inode = st.st_ino; }
    for (unsigned i = 0; !r && i < sizeof(devices) / sizeof(devices[0]); i++) {
        r = DL_CALL(SYS_newfstatat, AT_FDCWD, devices[i].host, &st, AT_SYMLINK_NOFOLLOW, 0, 0);
        if (r) break;
        if (!S_ISCHR(st.st_mode) || st.st_rdev != devices[i].device) { r = -ENODEV; break; }
        // Directory entries are only placeholders for enumeration. Never
        // truncate an existing file or follow a rootfs-provided symlink.
        long file = DL_CALL(SYS_openat, dir, devices[i].name, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600, 0, 0);
        if (file >= 0) DL_CALL(SYS_close, file, 0, 0, 0, 0, 0);
        else if (file != -EEXIST) { r = file; break; }
        r = DL_CALL(SYS_newfstatat, dir, devices[i].name, &st, AT_SYMLINK_NOFOLLOW, 0, 0);
        if (!r && !S_ISREG(st.st_mode)) r = -EINVAL;
    }
    DL_CALL(SYS_close, dir, 0, 0, 0, 0, 0); return r;
}

int dl_device_index(int dirfd, const char *name)
{
    int index = index_of(name);
    return index >= 0 && is_directory(dirfd) ? index : -1;
}

long dl_device_open(int index, int dirfd, int flags, unsigned mode)
{
    // The guest parent still has normal search-permission semantics.
    long r = DL_CALL(SYS_faccessat, dirfd, ".", X_OK, 0, 0, 0); if (r) return r;
    return DL_CALL(SYS_openat, AT_FDCWD, devices[index].host, flags | O_NOFOLLOW, mode, 0, 0);
}

long dl_device_change(int parent, const char *name, int removing)
{
    if (is_directory(parent)) return -EROFS;
    if (!removing) return 0;
    // Do not follow a leaf symlink while checking whether the fixed /dev
    // directory itself is being unlinked or renamed.
    char leaf[DL_PATH]; size_t length = dl_length(name);
    while (length && name[length - 1] == '/') length--;
    dl_copy(leaf, name, length); leaf[length] = 0;
    struct stat st;
    long r = DL_CALL(SYS_newfstatat, parent, leaf, &st, AT_SYMLINK_NOFOLLOW, 0, 0);
    return !r && matches(&st) ? -EBUSY : 0;
}

long dl_getdents(int fd, void *buffer, unsigned count)
{
    int translated = is_directory(fd);
    long n = DL_CALL(SYS_getdents64, fd, buffer, count, 0, 0, 0);
    if (n <= 0 || !translated) return n;
    size_t offset = 0;
    while (offset < (size_t)n) {
        // Kernel linux_dirent64 wire layout, not sizeof(libc struct dirent).
        unsigned char entry[280];
        size_t bytes = (size_t)n - offset;
        if (bytes > sizeof(entry)) bytes = sizeof(entry);
        long r = dl_read_memory(entry, (char *)buffer + offset, bytes); if (r) return r;
        if (bytes < 20) return -EIO;
        unsigned size = entry[16] | ((unsigned)entry[17] << 8);
        if (size < 20 || size > bytes) return -EIO;
        entry[size - 1] = 0;
        int index = index_of((char *)entry + 19);
        if (index >= 0) {
            struct stat st;
            r = DL_CALL(SYS_newfstatat, AT_FDCWD, devices[index].host, &st, AT_SYMLINK_NOFOLLOW, 0, 0);
            if (r) return r;
            dl_copy(entry, &st.st_ino, 8); entry[18] = DT_CHR;
            r = dl_write_memory((char *)buffer + offset, entry, 19); if (r) return r;
        }
        offset += size;
    }
    return n;
}
