/* SPDX-License-Identifier: Apache-2.0 */
/* Unmodified Linux ABI: every tested file/FIFO is created by this guest. */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/sysmacros.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#define CHECK(x) do { if (!(x)) { dprintf(2, "NODE FAIL %d: %s errno=%d\n", __LINE__, #x, errno); _exit(1); } } while (0)
static volatile sig_atomic_t seen;
static void caught(int signal) { CHECK(signal == SIGUSR1); seen++; }
static void delay(void)
{
    struct timespec time = {.tv_nsec = 50000000};
    while (nanosleep(&time, &time) && errno == EINTR) {}
}
static void join(pid_t child)
{
    int status;
    CHECK(waitpid(child, &status, 0) == child && WIFEXITED(status) && WEXITSTATUS(status) == 0);
}
static int same(const struct stat *a, const struct stat *b)
{
    return a->st_ino == b->st_ino && a->st_dev == b->st_dev && a->st_mode == b->st_mode;
}
static void content(const char *path, const char *expected)
{
    char bytes[64]; int fd = open(path, O_RDONLY | O_CLOEXEC); CHECK(fd >= 0);
    ssize_t n = read(fd, bytes, sizeof(bytes)); CHECK(close(fd) == 0);
    CHECK(n == (ssize_t)strlen(expected) && !memcmp(bytes, expected, (size_t)n));
}
static void verify_persistent(void)
{
    struct stat a, b;
    CHECK(stat("persistent-file", &a) == 0 && stat("persistent-link", &b) == 0);
    CHECK(same(&a, &b) && a.st_nlink == 2 && b.st_nlink == 2);
    content("persistent-file", "durable-node-data"); content("persistent-link", "durable-node-data");
    CHECK(lstat("persistent-symlink", &a) == 0 && lstat("persistent-symlink-link", &b) == 0);
    CHECK(same(&a, &b) && S_ISLNK(a.st_mode) && a.st_nlink == 2);
    content("persistent-symlink-link", "durable-node-data");
    CHECK(stat("persistent-fifo", &a) == 0 && stat("persistent-fifo-link", &b) == 0);
    CHECK(same(&a, &b) && S_ISFIFO(a.st_mode) && a.st_nlink == 2);
    int reader = open("persistent-fifo", O_RDONLY | O_NONBLOCK);
    int writer = open("persistent-fifo-link", O_WRONLY | O_NONBLOCK);
    char byte;
    CHECK(reader >= 0 && writer >= 0 && write(writer, "p", 1) == 1 && read(reader, &byte, 1) == 1 && byte == 'p');
    CHECK(close(reader) == 0 && close(writer) == 0);
    puts("PASS persisted inode/nlink, hardlinked symlinks and FIFO I/O");
}
static void create_persistent(void)
{
    int fd = open("persistent-file", O_CREAT | O_EXCL | O_WRONLY, 0600); CHECK(fd >= 0);
    CHECK(write(fd, "durable-node-data", 17) == 17 && fsync(fd) == 0 && close(fd) == 0);
    CHECK(link("persistent-file", "persistent-link") == 0);
    CHECK(symlink("persistent-file", "persistent-symlink") == 0);
    CHECK(link("persistent-symlink", "persistent-symlink-link") == 0);
    CHECK(mkfifo("persistent-fifo", 0600) == 0 && link("persistent-fifo", "persistent-fifo-link") == 0);
    fd = open(".", O_RDONLY | O_DIRECTORY); CHECK(fd >= 0 && fsync(fd) == 0 && close(fd) == 0);
    puts("PASS created persistent hardlinks and FIFO using guest syscalls");
}
static void links(const char *work, int guest)
{
    struct stat a, b;
    mode_t previous = umask(0027);
    CHECK(mkdir("directory", 0700) == 0);
    int dir = open("directory", O_PATH | O_DIRECTORY | O_CLOEXEC); CHECK(dir >= 0);
    CHECK(mknodat(dir, "regular", S_IFREG | 0666, 0) == 0);
    int fd = openat(dir, "regular", O_RDWR | O_CLOEXEC); CHECK(fd >= 0);
    CHECK(write(fd, "original", 8) == 8 && fstat(fd, &a) == 0);
    CHECK(S_ISREG(a.st_mode) && (a.st_mode & 0777) == 0640 && a.st_nlink == 1);
    CHECK(linkat(dir, "regular", AT_FDCWD, "copy", 0) == 0);
    CHECK(stat("copy", &b) == 0 && same(&a, &b) && b.st_nlink == 2);
    CHECK(rename("directory", "moved") == 0 && mkdir("directory", 0700) == 0);
    CHECK(linkat(dir, "regular", AT_FDCWD, "copied-through-pinned-dir", 0) == 0);
    CHECK(stat("copied-through-pinned-dir", &b) == 0 && same(&a, &b) && b.st_nlink == 3);
    CHECK(pwrite(fd, "changed!", 8, 0) == 8); content("copy", "changed!");
    CHECK(chmod("copy", 0600) == 0 && fstat(fd, &a) == 0 && (a.st_mode & 0777) == 0600);
    CHECK(renameat(dir, "regular", dir, "renamed") == 0);

    char absolute[4096]; CHECK(snprintf(absolute, sizeof(absolute), "%s/moved/renamed", work) < (int)sizeof(absolute));
    CHECK(symlink(absolute, "absolute") == 0 && symlink("absolute", "relative") == 0);
    CHECK(linkat(AT_FDCWD, "relative", AT_FDCWD, "followed", AT_SYMLINK_FOLLOW) == 0);
    CHECK(stat("followed", &b) == 0 && same(&a, &b) && b.st_nlink == 4);
    CHECK(link("absolute", "symlink-copy") == 0);
    CHECK(lstat("absolute", &a) == 0 && lstat("symlink-copy", &b) == 0 && same(&a, &b) && S_ISLNK(a.st_mode) && a.st_nlink == 2);
    char target[4096]; ssize_t size = readlink("symlink-copy", target, sizeof(target));
    CHECK(size == (ssize_t)strlen(absolute) && !memcmp(target, absolute, (size_t)size));
    CHECK(symlink("missing", "dangling") == 0 && link("dangling", "dangling-copy") == 0);
    CHECK(lstat("dangling-copy", &b) == 0 && S_ISLNK(b.st_mode) && b.st_nlink == 2);
    CHECK(linkat(AT_FDCWD, "dangling", AT_FDCWD, "bad-follow", AT_SYMLINK_FOLLOW) == -1 && errno == ENOENT);
    CHECK(symlink("loop", "loop") == 0);
    CHECK(linkat(AT_FDCWD, "loop", AT_FDCWD, "bad-loop", AT_SYMLINK_FOLLOW) == -1 && errno == ELOOP);
    CHECK(link("loop", "loop-copy") == 0);
    int symlinkfd = open("absolute", O_PATH | O_NOFOLLOW | O_CLOEXEC); CHECK(symlinkfd >= 0);
    char magic_symlink[64]; snprintf(magic_symlink, sizeof(magic_symlink), "/proc/self/fd/%d", symlinkfd);
    CHECK(linkat(AT_FDCWD, magic_symlink, AT_FDCWD, "pinned-symlink-copy", AT_SYMLINK_FOLLOW) == 0);
    CHECK(lstat("pinned-symlink-copy", &b) == 0 && same(&a, &b) && S_ISLNK(b.st_mode) && b.st_nlink == 3);
    CHECK(unlink("pinned-symlink-copy") == 0 && close(symlinkfd) == 0);
    CHECK(link("copy/", "bad-slash") == -1 && errno == ENOTDIR);
    CHECK(link("absolute/", "bad-slash") == -1 && errno == ENOTDIR);
    CHECK(link("copy", "new/") == -1 && errno == ENOENT);
    CHECK(link("moved", "bad-directory") == -1 && errno == EPERM);
    CHECK(link("copy", "dangling") == -1 && errno == EEXIST);
    CHECK(link("copy", "copy") == -1 && errno == EEXIST);
    CHECK(link("absent", "bad-source") == -1 && errno == ENOENT);
    CHECK(linkat(AT_FDCWD, "copy", AT_FDCWD, "bad-flags", 0x40000000) == -1 && errno == EINVAL);
    CHECK(syscall(SYS_linkat, AT_FDCWD, (void *)1, AT_FDCWD, "bad-pointer", 0) == -1 && errno == EFAULT);
    CHECK(syscall(SYS_linkat, AT_FDCWD, "copy", AT_FDCWD, (void *)1, 0) == -1 && errno == EFAULT);
    CHECK(linkat(-1, "copy", AT_FDCWD, "bad-fd", 0) == -1 && errno == EBADF);
    CHECK(linkat(AT_FDCWD, "copy", -1, "bad-fd", 0) == -1 && errno == EBADF);
    CHECK(linkat(fd, "", AT_FDCWD, "empty-without-flag", 0) == -1 && errno == ENOENT);
    int empty = linkat(fd, "", AT_FDCWD, "empty-link", AT_EMPTY_PATH);
    if (empty == 0) {
        CHECK(fstat(fd, &a) == 0 && stat("empty-link", &b) == 0 && same(&a, &b));
        CHECK(unlink("empty-link") == 0);
        puts("PASS host kernel permits AT_EMPTY_PATH hardlink for owned inode");
    } else {
        CHECK(errno == ENOENT || errno == EPERM || errno == EACCES);
        printf("LIMIT host kernel AT_EMPTY_PATH policy errno=%d\n", errno);
    }
    CHECK(linkat(-1, absolute, AT_FDCWD, "absolute-ignores-fd", 0) == 0);
    CHECK(unlink("absolute-ignores-fd") == 0);
    char magic[64]; snprintf(magic, sizeof(magic), "/proc/self/fd/%d", fd);
    CHECK(linkat(AT_FDCWD, magic, AT_FDCWD, "magic-follow", AT_SYMLINK_FOLLOW) == 0);
    CHECK(fstat(fd, &a) == 0 && stat("magic-follow", &b) == 0 && same(&a, &b));
    CHECK(unlink("magic-follow") == 0);
    CHECK(link(magic, "magic-no-follow") == -1 && errno == EXDEV);
    CHECK(link("/proc/self/fd/2147483647", "magic-invalid") == -1 && errno == ENOENT);
    int before = dup(STDERR_FILENO); CHECK(before >= 0 && close(before) == 0);
    for (int i = 0; i < 128; i++) {
        CHECK(link("copy", "dangling") == -1 && errno == EEXIST);
        CHECK(syscall(SYS_linkat, AT_FDCWD, "copy", AT_FDCWD, (void *)1, 0) == -1 && errno == EFAULT);
    }
    int after = dup(STDERR_FILENO); CHECK(after == before && close(after) == 0);
    if (guest) {
        CHECK(link("/dev/null", "host-device-alias") == -1 && errno == EXDEV);
        CHECK(link("copy", "/dev/created-link") == -1 && errno == EROFS);
        CHECK(mkfifo("/dev/created-fifo", 0600) == -1 && errno == EROFS);
        CHECK(mknod("/dev/null", S_IFCHR | 0666, makedev(1, 3)) == -1 && errno == EROFS);
        puts("PASS fixed device boundary prevents host-device aliases and /dev mutation");
    }
    CHECK(unlinkat(dir, "renamed", 0) == 0 && unlink("copied-through-pinned-dir") == 0 && unlink("followed") == 0);
    CHECK(fstat(fd, &a) == 0 && a.st_nlink == 1); content("copy", "changed!");
    CHECK(unlink("copy") == 0 && fstat(fd, &a) == 0 && a.st_nlink == 0);
    CHECK(linkat(fd, "", AT_FDCWD, "deleted-empty", AT_EMPTY_PATH) == -1 && errno == ENOENT);
    CHECK(close(fd) == 0 && close(dir) == 0);
    umask(previous);
    puts("PASS hardlink identity, writes, chmod, rename/unlink, symlinks, dirfds, flags and errno");
}
static void fifos(void)
{
    struct stat a, b;
    mode_t previous = umask(0027);
    CHECK(mkfifoat(AT_FDCWD, "fifo", 0666) == 0 && stat("fifo", &a) == 0);
    CHECK(S_ISFIFO(a.st_mode) && (a.st_mode & 0777) == 0640 && a.st_nlink == 1);
    umask(previous);
    CHECK(link("fifo", "fifo-link") == 0 && stat("fifo-link", &b) == 0 && same(&a, &b) && b.st_nlink == 2);
    CHECK(open("fifo", O_WRONLY | O_NONBLOCK) == -1 && errno == ENXIO);
    int reader = open("fifo", O_RDONLY | O_NONBLOCK); CHECK(reader >= 0);
    char byte; CHECK(read(reader, &byte, 1) == 0);
    int writer = open("fifo-link", O_WRONLY | O_NONBLOCK); CHECK(writer >= 0);
    CHECK(read(reader, &byte, 1) == -1 && errno == EAGAIN);
    CHECK(write(writer, "x", 1) == 1 && read(reader, &byte, 1) == 1 && byte == 'x');
    CHECK(rename("fifo-link", "fifo-moved") == 0 && unlink("fifo") == 0);
    CHECK(fstat(reader, &a) == 0 && stat("fifo-moved", &b) == 0 && same(&a, &b) && a.st_nlink == 1);
    CHECK(close(writer) == 0 && read(reader, &byte, 1) == 0 && close(reader) == 0);
    CHECK(mkfifo("fifo", 0600) == 0 && stat("fifo", &b) == 0 && (a.st_ino != b.st_ino || a.st_dev != b.st_dev));

    int ready[2]; CHECK(pipe(ready) == 0);
    pid_t child = fork(); CHECK(child >= 0);
    if (!child) {
        CHECK(close(ready[0]) == 0 && write(ready[1], "r", 1) == 1 && close(ready[1]) == 0);
        int out = open("fifo-moved", O_WRONLY); CHECK(out >= 0);
        CHECK(write(out, "b", 1) == 1 && close(out) == 0); _exit(0);
    }
    CHECK(close(ready[1]) == 0 && read(ready[0], &byte, 1) == 1 && close(ready[0]) == 0);
    delay(); CHECK(waitpid(child, NULL, WNOHANG) == 0);
    reader = open("fifo-moved", O_RDONLY); CHECK(reader >= 0);
    CHECK(read(reader, &byte, 1) == 1 && byte == 'b' && read(reader, &byte, 1) == 0 && close(reader) == 0);
    join(child);

    // Android ProcessBuilder can inherit SIGUSR1 blocked. Establish the mask
    // required by the interruption test rather than assuming a shell launch.
    sigset_t original_mask, tested;
    CHECK(sigprocmask(SIG_SETMASK, NULL, &original_mask) == 0);
    sigemptyset(&tested); sigaddset(&tested, SIGUSR1);
    for (int restart = 0; restart < 2; restart++) {
        struct sigaction action = {.sa_handler = caught, .sa_flags = restart ? SA_RESTART : 0};
        sigemptyset(&action.sa_mask); CHECK(sigaction(SIGUSR1, &action, NULL) == 0);
        CHECK(sigprocmask(SIG_UNBLOCK, &tested, NULL) == 0);
        child = fork(); CHECK(child >= 0);
        if (!child) {
            delay(); CHECK(kill(getppid(), SIGUSR1) == 0);
            if (restart) {
                delay(); int out = open("fifo", O_WRONLY); CHECK(out >= 0);
                CHECK(write(out, "s", 1) == 1 && close(out) == 0);
            }
            _exit(0);
        }
        reader = open("fifo", O_RDONLY);
        if (!restart) CHECK(reader == -1 && errno == EINTR);
        else CHECK(reader >= 0 && read(reader, &byte, 1) == 1 && byte == 's' && close(reader) == 0);
        join(child);
    }
    CHECK(seen == 2);
    CHECK(sigprocmask(SIG_SETMASK, &original_mask, NULL) == 0);
    CHECK(unlink("fifo") == 0 && unlink("fifo-moved") == 0);
    puts("PASS guest-created FIFO hardlinks, ENXIO/EAGAIN/EOF, blocking peer, EINTR and SA_RESTART");
    printf("PASS FIFO signal mask setup (inherited SIGUSR1 blocked=%d)\n", sigismember(&original_mask, SIGUSR1));
}
static void nodes(void)
{
    struct stat st;
    CHECK(mknod("untyped-regular", 0600, 0) == 0 && stat("untyped-regular", &st) == 0 && S_ISREG(st.st_mode));
    CHECK(mknod("socket-node", S_IFSOCK | 0600, 0) == 0 && stat("socket-node", &st) == 0 && S_ISSOCK(st.st_mode));
    int socketfd = socket(AF_UNIX, SOCK_STREAM, 0); CHECK(socketfd >= 0);
    struct sockaddr_un address = {.sun_family = AF_UNIX}; strcpy(address.sun_path, "socket-node");
    CHECK(connect(socketfd, (void *)&address, sizeof(address)) == -1);
    if (errno != ECONNREFUSED) {
        CHECK(errno == EPERM || errno == EACCES);
        printf("LIMIT host policy prevents socket-node connect check errno=%d\n", errno);
    }
    CHECK(close(socketfd) == 0);
    CHECK(mknod("bad-directory", S_IFDIR | 0700, 0) == -1 && errno == EPERM);
    CHECK(mknod("bad-symlink", S_IFLNK | 0700, 0) == -1 && errno == EINVAL);
    CHECK(mknod("bad-type", 0170000 | 0600, 0) == -1 && errno == EINVAL);
    CHECK(mkfifo("dangling", 0600) == -1 && errno == EEXIST);
    CHECK(mkfifo("new-fifo/", 0600) == -1 && errno == ENOENT);
    CHECK(mknodat(-1, "bad-fd", S_IFIFO | 0600, 0) == -1 && errno == EBADF);
    CHECK(mknod("", S_IFIFO | 0600, 0) == -1 && errno == ENOENT);
    CHECK(syscall(SYS_mknodat, AT_FDCWD, (void *)1, S_IFIFO | 0600, 0) == -1 && errno == EFAULT);
    mode_t modes[] = {S_IFCHR, S_IFBLK};
    const char *names[] = {"char-device", "block-device"};
    for (unsigned i = 0; i < 2; i++) {
        int result = mknod(names[i], modes[i] | 0600, makedev(1, 3));
        if (!result) {
            CHECK(lstat(names[i], &st) == 0 && (st.st_mode & S_IFMT) == modes[i] && st.st_rdev == makedev(1, 3));
            CHECK(unlink(names[i]) == 0);
            printf("PASS host kernel permitted real %s creation\n", names[i]);
        } else {
            CHECK(errno == EPERM || errno == EACCES);
            printf("LIMIT host kernel %s creation policy errno=%d\n", names[i], errno);
            CHECK(lstat(names[i], &st) == -1 && errno == ENOENT);
        }
    }
    puts("PASS regular/socket mknod, mode/umask, invalid nodes and actual device policy");
}
int main(int argc, char **argv)
{
    CHECK(setvbuf(stdout, NULL, _IONBF, 0) == 0);
    int guest = argc > 1 && !strcmp(argv[1], "--guest");
    if (argc == 3 && (!strcmp(argv[1], "--persist-create") || !strcmp(argv[1], "--persist-check"))) {
        if (!strcmp(argv[1], "--persist-create")) CHECK(mkdir(argv[2], 0700) == 0);
        CHECK(chdir(argv[2]) == 0);
        if (!strcmp(argv[1], "--persist-create")) create_persistent();
        else verify_persistent();
        return 0;
    }
    CHECK(argc <= 2 + guest);
    char work[4096];
    if (argc > 1 + guest) CHECK(snprintf(work, sizeof(work), "%s", argv[1 + guest]) < (int)sizeof(work));
    else CHECK(snprintf(work, sizeof(work), "/tmp/nodes-%ld", (long)getpid()) < (int)sizeof(work));
    CHECK(mkdir(work, 0700) == 0 && chdir(work) == 0);
    CHECK(getcwd(work, sizeof(work)) != NULL);
    links(work, guest); fifos(); nodes(); create_persistent(); verify_persistent();
    puts("PASS node compatibility"); return 0;
}
