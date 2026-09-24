/* SPDX-License-Identifier: Apache-2.0 */
/* Unmodified Linux ABI regression fixture, compiled against glibc. */
#define _GNU_SOURCE
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/fs.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/statfs.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <sys/xattr.h>
#include <unistd.h>

#define CHECK(x) do { if (!(x)) { fprintf(stderr, "FILES FAIL %d errno=%d\n", __LINE__, errno); return 1; } } while (0)
static int create(const char *path, const char *bytes)
{
    int fd = open(path, O_CREAT | O_EXCL | O_RDWR | O_CLOEXEC, 0666);
    if (fd < 0) return -1;
    if (write(fd, bytes, strlen(bytes)) != (ssize_t)strlen(bytes)) { close(fd); return -1; }
    return fd;
}
static int contains(int directory, const char *path, const char *expected)
{
    int fd = openat(directory, path, O_RDONLY);
    if (fd < 0) return 0;
    char bytes[32]; ssize_t n = read(fd, bytes, sizeof(bytes)); close(fd);
    return n == (ssize_t)strlen(expected) && !memcmp(bytes, expected, n);
}

int main(void)
{
    char work[128], cwd[4096], expected[160];
    snprintf(work, sizeof(work), "/tmp/files-%ld", (long)getpid());
    CHECK(mkdir(work, 0700) == 0 && chdir(work) == 0);
    int root = open("/", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    CHECK(root >= 0);
    mode_t previous = umask(0027);
    CHECK(mkdir("origin/", 0777) == 0);
    struct stat st, original;
    CHECK(stat("origin", &st) == 0 && (st.st_mode & 0777) == 0750);
    int dir = open("origin", O_PATH | O_DIRECTORY | O_CLOEXEC);
    CHECK(dir >= 0 && fchdir(dir) == 0);
    int file = create("identity", "original");
    CHECK(file >= 0 && fstat(file, &st) == 0 && (st.st_mode & 0777) == 0640);
    CHECK(close(file) == 0 && rename("../origin", "../moved") == 0);
    CHECK(mkdir("../origin", 0700) == 0);
    file = create("../origin/identity", "replacement");
    CHECK(file >= 0 && close(file) == 0);
    CHECK(contains(dir, "identity", "original") && contains(AT_FDCWD, "identity", "original"));
    snprintf(expected, sizeof(expected), "%s/moved", work);
    CHECK(getcwd(cwd, sizeof(cwd)) && !strcmp(cwd, expected));
    CHECK(mkdir("../parent", 0700) == 0 && stat("../parent", &original) == 0);
    CHECK(rename("../moved", "../parent/child") == 0);
    CHECK(fstatat(dir, "..", &st, 0) == 0 && st.st_ino == original.st_ino && st.st_dev == original.st_dev);
    CHECK(mkdirat(dir, "new", 0700) == 0 && unlinkat(dir, "new", AT_REMOVEDIR) == 0);
    CHECK(fchdir(dir) == 0 && contains(AT_FDCWD, "identity", "original"));
    CHECK(contains(root, "../../../../input", "fixture\n"));
    CHECK(chdir(work) == 0 && close(dir) == 0);

    CHECK(symlink("parent/child", "alias") == 0 && mkdir("alias/through", 0700) == 0);
    CHECK(stat("parent/child/through", &st) == 0 && S_ISDIR(st.st_mode));
    CHECK(rename("alias/through", "relocated") == 0 && rmdir("relocated/") == 0);
    CHECK(symlink("/input", "absolute") == 0 && contains(AT_FDCWD, "absolute", "fixture\n"));
    CHECK(open("parent/child/identity/not-a-directory", O_RDONLY) == -1 && errno == ENOTDIR);
    CHECK(open("absolute/not-a-directory", O_RDONLY) == -1 && errno == ENOTDIR);
    CHECK(contains(AT_FDCWD, "alias/identity", "original"));
    CHECK(symlink("/outside-never-created", "dangling") == 0);
    CHECK(unlink("dangling/") == -1 && errno == ENOTDIR);
    CHECK(rename("dangling", "dangling-moved") == 0);
    CHECK(lstat("dangling-moved", &st) == 0 && S_ISLNK(st.st_mode));
    CHECK(unlink("dangling-moved") == 0);
    CHECK(mkdir("absolute/", 0700) == -1 && errno == EEXIST);
    CHECK(unlink("absolute/") == -1 && errno == ENOTDIR);
    CHECK(rmdir("alias") == -1 && errno == ENOTDIR);
    CHECK(rename("absolute/", "invalid") == -1 && errno == ENOTDIR);
    CHECK(unlink("absolute") == 0 && stat("/input", &st) == 0 && st.st_size == 8);
    CHECK(unlink("alias") == 0 && stat("parent/child", &st) == 0);

    file = create("first", "first"); CHECK(file >= 0);
    int second = create("second", "second"); CHECK(second >= 0 && close(second) == 0);
    CHECK(syscall(SYS_renameat2, AT_FDCWD, "first", AT_FDCWD, "second", RENAME_NOREPLACE) == -1 && errno == EEXIST);
    CHECK(contains(AT_FDCWD, "first", "first") && contains(AT_FDCWD, "second", "second"));
    CHECK(syscall(SYS_renameat2, AT_FDCWD, "first", AT_FDCWD, "second", RENAME_EXCHANGE) == 0);
    CHECK(contains(AT_FDCWD, "first", "second") && contains(AT_FDCWD, "second", "first"));
    CHECK(unlink("second") == 0 && fstat(file, &st) == 0 && st.st_nlink == 0);
    char bytes[16]; CHECK(pread(file, bytes, sizeof(bytes), 0) == 5 && !memcmp(bytes, "first", 5));
    CHECK(ftruncate(file, 2) == 0 && fstat(file, &st) == 0 && st.st_size == 2);
    CHECK(fchmod(file, 0600) == 0 && close(file) == 0);
    CHECK(truncate("first", 2) == 0 && contains(AT_FDCWD, "first", "se"));
    CHECK(chmod("first", 0400) == 0 && stat("first", &st) == 0 && (st.st_mode & 0777) == 0400);
    CHECK(open("first", O_WRONLY) == -1 && errno == EACCES);
    CHECK(chmod("first", 0600) == 0);
    CHECK(chown("first", getuid(), getgid()) == 0);
    CHECK(chown("first", 0, 0) == -1 && errno == EPERM);
    CHECK(stat("first", &st) == 0 && st.st_uid == getuid() && st.st_gid == getgid());
    struct timespec times[2] = {{1234567000, 123456789}, {1234567890, 987654321}};
    CHECK(utimensat(AT_FDCWD, "first", times, 0) == 0);
    CHECK(stat("first", &st) == 0 && st.st_mtim.tv_sec == times[1].tv_sec && st.st_mtim.tv_nsec == times[1].tv_nsec);
    CHECK(symlink("first", "time-link") == 0);
    times[1].tv_sec++;
    int changed = utimensat(AT_FDCWD, "time-link", times, AT_SYMLINK_NOFOLLOW);
    if (changed) { CHECK(errno == EINVAL || errno == ENOENT); puts("LIMIT symlink timestamps require Linux 5.8"); }
    else CHECK(lstat("time-link", &st) == 0 && st.st_mtim.tv_sec == times[1].tv_sec);
    CHECK(stat("first", &st) == 0 && st.st_mtim.tv_sec == times[1].tv_sec - 1);
    times[0].tv_nsec = times[1].tv_nsec = UTIME_OMIT;
    CHECK(utimensat(AT_FDCWD, "absent", times, 0) == 0);
    struct statfs fs;
    CHECK(statfs("first", &fs) == 0 && fs.f_bsize > 0);
    CHECK(mkdir("gone", 0700) == 0);
    dir = open("gone", O_PATH | O_DIRECTORY); CHECK(dir >= 0);
    CHECK(chdir("gone") == 0 && rmdir("../gone") == 0);
    CHECK(!getcwd(cwd, sizeof(cwd)) && errno == ENOENT);
    CHECK(fstatat(dir, ".", &st, 0) == 0 && st.st_nlink == 0);
    CHECK(fchdir(dir) == 0 && chdir("..") == 0 && close(dir) == 0);
    CHECK(getcwd(cwd, sizeof(cwd)) && !strcmp(cwd, work));
    // Exchange live names in another process while this process repeatedly uses
    // the pinned directory. Every open must see the same inode's content.
    CHECK(mkdir("race-a", 0700) == 0 && mkdir("race-b", 0700) == 0);
    file = create("race-a/identity", "pinned"); CHECK(file >= 0 && close(file) == 0);
    file = create("race-b/identity", "other"); CHECK(file >= 0 && close(file) == 0);
    dir = open("race-a", O_PATH | O_DIRECTORY); CHECK(dir >= 0);
    pid_t child = fork(); CHECK(child >= 0);
    if (!child) {
        for (int i = 0; i < 500; i++)
            if (syscall(SYS_renameat2, AT_FDCWD, "race-a", AT_FDCWD, "race-b", RENAME_EXCHANGE)) _exit(2);
        _exit(0);
    }
    for (int i = 0; i < 500; i++) CHECK(contains(dir, "identity", "pinned"));
    int status;
    CHECK(waitpid(child, &status, 0) == child && WIFEXITED(status) && WEXITSTATUS(status) == 0);
    CHECK(close(dir) == 0);
    CHECK(mkdirat(-1, "relative", 0700) == -1 && errno == EBADF);
    CHECK(syscall(SYS_mkdirat, AT_FDCWD, (void *)1, 0700) == -1 && errno == EFAULT);
    CHECK(syscall(SYS_renameat2, AT_FDCWD, "first", AT_FDCWD, (void *)1, 0) == -1 && errno == EFAULT);
    CHECK(contains(AT_FDCWD, "first", "se"));
    const char attr[] = "user.direct-linux-test";
    CHECK(setxattr("first", attr, "a\0b", 3, XATTR_CREATE) == 0);
    CHECK(setxattr("first", attr, "x", 1, XATTR_CREATE) == -1 && errno == EEXIST);
    CHECK(getxattr("first", attr, NULL, 0) == 3);
    CHECK(getxattr("first", attr, bytes, 1) == -1 && errno == ERANGE);
    CHECK(getxattr("first", attr, bytes, sizeof(bytes)) == 3 && !memcmp(bytes, "a\0b", 3));
    CHECK(lgetxattr("time-link", attr, bytes, sizeof(bytes)) == -1 && errno == ENODATA);
    CHECK(lsetxattr("time-link", attr, "link", 4, 0) == -1 && (errno == EPERM || errno == EOPNOTSUPP));
    CHECK(getxattr("first", attr, bytes, sizeof(bytes)) == 3 && !memcmp(bytes, "a\0b", 3));
    file = open("first", O_RDONLY); CHECK(file >= 0);
    CHECK(flistxattr(file, NULL, 0) >= (ssize_t)sizeof(attr));
    CHECK(fsetxattr(file, attr, "fd", 2, XATTR_REPLACE) == 0);
    CHECK(unlink("first") == 0 && fgetxattr(file, attr, bytes, sizeof(bytes)) == 2 && !memcmp(bytes, "fd", 2));
    CHECK(fremovexattr(file, attr) == 0 && fgetxattr(file, attr, bytes, sizeof(bytes)) == -1 && errno == ENODATA);
    CHECK(close(file) == 0);
    file = open("/dev/null", O_RDWR | O_CLOEXEC); CHECK(file >= 0);
    CHECK(fstat(file, &st) == 0 && S_ISCHR(st.st_mode) && st.st_rdev == 0x103);
    CHECK(write(file, "discard", 7) == 7 && read(file, bytes, sizeof(bytes)) == 0 && close(file) == 0);
    file = open("/dev/zero", O_RDONLY); CHECK(file >= 0);
    memset(bytes, 0x5a, sizeof(bytes));
    CHECK(read(file, bytes, sizeof(bytes)) == sizeof(bytes) && close(file) == 0);
    for (unsigned i = 0; i < sizeof(bytes); i++) CHECK(bytes[i] == 0);
    file = open("/dev/urandom", O_RDONLY); CHECK(file >= 0);
    CHECK(read(file, bytes, sizeof(bytes)) == sizeof(bytes) && close(file) == 0);
    CHECK(open("/dev/binder", O_RDWR) == -1 && errno == ENOENT);
    CHECK(open("/dev/null", O_CREAT | O_EXCL | O_RDWR, 0600) == -1 && errno == EEXIST);
    CHECK(unlink("/dev/null") == -1 && errno == EROFS);
    CHECK(rename("/dev", "moved-dev") == -1 && errno == EBUSY);
    CHECK(mkdir("/dev/new", 0700) == -1 && errno == EROFS);
    CHECK(open("/dev/new", O_CREAT | O_RDWR, 0600) == -1 && errno == EROFS);
    CHECK(symlink("/dev/null", "null-alias") == 0);
    file = open("null-alias", O_RDWR); CHECK(file >= 0 && read(file, bytes, sizeof(bytes)) == 0 && close(file) == 0);
    DIR *device_directory = opendir("/dev"); CHECK(device_directory);
    unsigned found = 0; struct dirent *entry;
    while ((entry = readdir(device_directory))) {
        if (!strcmp(entry->d_name, ".") || !strcmp(entry->d_name, "..")) continue;
        CHECK(entry->d_type == DT_CHR && fstatat(dirfd(device_directory), entry->d_name, &st, 0) == 0);
        CHECK(entry->d_ino == st.st_ino && S_ISCHR(st.st_mode)); found++;
    }
    CHECK(found == 5 && closedir(device_directory) == 0);
    CHECK(chmod("/dev", 0000) == 0);
    file = open("/dev/null", O_RDONLY); int search_error = errno;
    CHECK(chmod("/dev", 0755) == 0);
    CHECK(file == -1 && search_error == EACCES);
    CHECK(fchdir(root) == 0 && close(root) == 0);
    for (int fd = 3; fd < 256; fd++) CHECK(fcntl(fd, F_GETFD) == -1 && errno == EBADF);
    umask(previous);
    puts("PASS filesystem rename/dirfd/cwd/symlinks/modes/timestamps/unlinked-FDs");
    return 0;
}
