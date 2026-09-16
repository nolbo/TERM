/* SPDX-License-Identifier: GPL-2.0-or-later */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <limits.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>

#define CHECK(x) do { if (!(x)) { fprintf(stderr, "FAIL line %d: %s errno=%d\n", __LINE__, #x, errno); exit(1); } } while (0)
static int query(int fd, const char *path, void *out, int flags)
{ return syscall(SYS_newfstatat, fd, path, out, flags); }

static void check_open(int dir, const char *name, int flags)
{
    int fd = syscall(SYS_openat, dir, name, flags, 0);
    CHECK(fd >= 0);
    CHECK(fcntl(fd, F_GETFD) == ((flags & O_CLOEXEC) ? FD_CLOEXEC : 0));
    CHECK((fcntl(fd, F_GETFL) & (O_ACCMODE | O_NONBLOCK | O_NOFOLLOW)) ==
          (flags & (O_ACCMODE | O_NONBLOCK | O_NOFOLLOW)));
    char data[4];
    CHECK(read(fd, data, sizeof(data)) == 4 && memcmp(data, "data", 4) == 0);
    CHECK(write(fd, "x", 1) == -1 && errno == EBADF);
    CHECK(close(fd) == 0);
}

int main(int argc, char **argv)
{
    alarm(30);
    CHECK(argc == 1 || argc == 2);
    char root[PATH_MAX], absolute[PATH_MAX];
    CHECK(snprintf(root, sizeof(root), "%s/terminal-stat-entry-test-XXXXXX", argc == 2 ? argv[1] : "/tmp") < (int)sizeof(root));
    CHECK(mkdtemp(root));
    int dir = open(root, O_RDONLY | O_DIRECTORY);
    CHECK(dir >= 0);
    int file = openat(dir, "file", O_CREAT | O_EXCL | O_RDWR, 0640);
    CHECK(file >= 0 && write(file, "data", 4) == 4);
    struct stat result;
    CHECK(snprintf(absolute, sizeof(absolute), "%s/file", root) < (int)sizeof(absolute));
    check_open(AT_FDCWD, absolute, O_RDONLY | O_CLOEXEC);
    check_open(dir, "file", O_RDONLY);
    check_open(dir, "./file", O_RDONLY | O_NONBLOCK);
    check_open(dir, "file", O_RDONLY | O_NOFOLLOW);
    CHECK(syscall(SYS_openat, dir, "absent", O_RDONLY, 0) == -1 && errno == ENOENT);
    CHECK(syscall(SYS_openat, -123, "file", O_RDONLY, 0) == -1 && errno == EBADF);
    CHECK(syscall(SYS_openat, dir, "file", O_RDONLY | O_DIRECTORY, 0) == -1 && errno == ENOTDIR);
    CHECK(syscall(SYS_openat, dir, (const char *)1, O_RDONLY, 0) == -1 && errno == EFAULT);
    long page_size = sysconf(_SC_PAGESIZE);
    char *edge = mmap(NULL, page_size * 2, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    CHECK(edge != MAP_FAILED && mprotect(edge + page_size, page_size, PROT_NONE) == 0);
    memcpy(edge + page_size - 5, "file", 5);
    check_open(dir, edge + page_size - 5, O_RDONLY | O_CLOEXEC);
    CHECK(munmap(edge, page_size * 2) == 0);
    pid_t readers[3];
    for (int i = 0; i < 3; i++) {
        readers[i] = fork(); CHECK(readers[i] >= 0);
        if (!readers[i]) {
            for (int j = 0; j < 50; j++) check_open(dir, "file", O_RDONLY | O_CLOEXEC);
            _exit(0);
        }
    }
    for (int i = 0; i < 3; i++) {
        int status;
        CHECK(waitpid(readers[i], &status, 0) == readers[i] && WIFEXITED(status) && WEXITSTATUS(status) == 0);
    }
    for (int i = 0; i < 10; i++) {
        pid_t canceled = fork(); CHECK(canceled >= 0);
        if (!canceled) for (;;) check_open(dir, "file", O_RDONLY | O_CLOEXEC);
        usleep(1000);
        CHECK(kill(canceled, SIGKILL) == 0);
        int status;
        CHECK(waitpid(canceled, &status, 0) == canceled && WIFSIGNALED(status) && WTERMSIG(status) == SIGKILL);
    }
    CHECK(mkdirat(dir, "sub", 0700) == 0);
    CHECK(linkat(dir, "file", dir, "sub/file", 0) == 0);
    CHECK(symlinkat("sub", dir, "subalias") == 0);
    char through_link[PATH_MAX];
    CHECK(snprintf(through_link, sizeof(through_link), "%s/subalias/file", root) < (int)sizeof(through_link));
    CHECK(query(AT_FDCWD, through_link, &result, 0) == 0 && result.st_size == 4);
    CHECK(unlinkat(dir, "subalias", 0) == 0 && unlinkat(dir, "sub/file", 0) == 0 && unlinkat(dir, "sub", AT_REMOVEDIR) == 0);
    CHECK(query(AT_FDCWD, absolute, &result, 0) == 0 && result.st_size == 4 && result.st_uid == 0);
    int original_cwd = open(".", O_RDONLY | O_DIRECTORY);
    CHECK(original_cwd >= 0 && chdir(root) == 0);
    CHECK(query(AT_FDCWD, "file", &result, 0) == 0 && result.st_size == 4);
    CHECK(query(AT_FDCWD, "./file", &result, 0) == 0 && result.st_size == 4);
    CHECK(fchdir(original_cwd) == 0 && close(original_cwd) == 0);
    CHECK(query(dir, "file", &result, 0) == 0);
    fprintf(stderr, "stat mode=%o size=%ld uid=%u gid=%u nlink=%lu\n",
        result.st_mode, (long)result.st_size, result.st_uid, result.st_gid, (unsigned long)result.st_nlink);
    CHECK(S_ISREG(result.st_mode) && result.st_size == 4 && result.st_uid == 0);
    CHECK(linkat(dir, "file", dir, "hard", 0) == 0);
    CHECK(query(dir, "hard", &result, 0) == 0 && result.st_nlink == 2);
    CHECK(syscall(SYS_linkat, dir, "file", dir, "extra", 0) == 0);
    CHECK(syscall(SYS_linkat, dir, "file", dir, "extra", 0) == -1 && errno == EEXIST);
    CHECK(syscall(SYS_renameat, dir, "extra", dir, "renamed") == 0);
    CHECK(syscall(SYS_renameat2, dir, "renamed", dir, "extra", 0) == 0);
    CHECK(syscall(SYS_renameat, dir, "extra", dir, "renamed") == 0);
    CHECK(mkdirat(dir, "occupied", 0700) == 0);
    CHECK(syscall(SYS_renameat, dir, "renamed", dir, "occupied") == -1 && errno == EISDIR);
    CHECK(unlinkat(dir, "occupied", AT_REMOVEDIR) == 0);
    CHECK(syscall(SYS_unlinkat, dir, "renamed", 0) == 0);
    CHECK(syscall(SYS_unlinkat, dir, "renamed", 0) == -1 && errno == ENOENT);
    CHECK(syscall(SYS_unlinkat, dir, "file", 0x40000000) == -1 && errno == EINVAL);
    CHECK(syscall(SYS_linkat, dir, (const char *)1, dir, "bad", 0) == -1 && errno == EFAULT);
    char oversized[PATH_MAX + 1];
    memset(oversized, 'a', sizeof(oversized) - 1);
    oversized[0] = '/';
    oversized[sizeof(oversized) - 1] = '\0';
    CHECK(syscall(SYS_renameat, dir, "file", dir, oversized) == -1 && errno == ENAMETOOLONG);
    CHECK(syscall(SYS_renameat, dir, oversized, dir, "bad") == -1 && errno == ENAMETOOLONG);
    CHECK(syscall(SYS_renameat2, dir, "file", dir, "hard", 1 /* NOREPLACE */) == -1 && errno == EEXIST);
    CHECK(mkdirat(dir, "cwd-old", 0700) == 0);
    int saved_cwd = open(".", O_RDONLY | O_DIRECTORY);
    CHECK(saved_cwd >= 0 && fchdir(dir) == 0 && chdir("cwd-old") == 0);
    CHECK(syscall(SYS_renameat, dir, "cwd-old", dir, "cwd-new") == 0);
    char renamed_cwd[PATH_MAX];
    CHECK(getcwd(renamed_cwd, sizeof(renamed_cwd)) != NULL);
    CHECK(strstr(renamed_cwd, "/cwd-new") != NULL);
    CHECK(fchdir(saved_cwd) == 0 && close(saved_cwd) == 0);
    CHECK(unlinkat(dir, "cwd-new", AT_REMOVEDIR) == 0);
    CHECK(symlinkat("file", dir, "sym") == 0);
    check_open(dir, "sym", O_RDONLY | O_CLOEXEC);
    CHECK(syscall(SYS_openat, dir, "sym", O_RDONLY | O_NOFOLLOW, 0) == -1 && errno == ELOOP);
    CHECK(query(dir, "sym", &result, AT_SYMLINK_NOFOLLOW) == 0 && S_ISLNK(result.st_mode));
    CHECK(query(dir, "sym", &result, 0) == 0 && result.st_size == 4);
    CHECK(snprintf(absolute, sizeof(absolute), "%s/sym", root) < (int)sizeof(absolute));
    CHECK(query(AT_FDCWD, absolute, &result, 0) == 0 && result.st_size == 4);
    CHECK(query(AT_FDCWD, absolute, &result, AT_SYMLINK_NOFOLLOW) == 0 && S_ISLNK(result.st_mode));
    CHECK(snprintf(absolute, sizeof(absolute), "%s/file", root) < (int)sizeof(absolute));
    CHECK(query(file, "", &result, AT_EMPTY_PATH) == 0 && result.st_size == 4);
    CHECK(query(dir, "missing", &result, 0) == -1 && errno == ENOENT);
    CHECK(query(-123, "file", &result, 0) == -1 && errno == EBADF);
    CHECK(query(dir, "file", &result, 0x40000000) == -1 && errno == EINVAL);
    CHECK(query(dir, "file", (void *)1, 0) == -1 && errno == EFAULT);
    CHECK(query(dir, (const char *)1, &result, 0) == -1 && errno == EFAULT);
    long size = sysconf(_SC_PAGESIZE);
    void *readonly = mmap(NULL, size, PROT_READ, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    CHECK(readonly != MAP_FAILED);
    CHECK(query(dir, "file", readonly, 0) == -1 && errno == EFAULT);
    CHECK(query(AT_FDCWD, absolute, readonly, 0) == -1 && errno == EFAULT);
    CHECK(munmap(readonly, size) == 0);
    CHECK(query(AT_FDCWD, "/proc/self", &result, 0) == 0 && S_ISDIR(result.st_mode));
    CHECK(query(AT_FDCWD, "/dev/null", &result, 0) == 0 && S_ISCHR(result.st_mode));
    pid_t child = fork(); CHECK(child >= 0);
    if (child == 0) {
        CHECK(setresuid(1000, 1000, 1000) == 0);
        CHECK(query(dir, "file", &result, 0) == 0 && result.st_uid == 1000);
        CHECK(query(AT_FDCWD, absolute, &result, 0) == 0 && result.st_uid == 1000);
        struct sock_filter filter[] = {
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_newfstatat, 0, 1),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EPERM),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_unlinkat, 0, 1),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EPERM),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_linkat, 0, 1),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EPERM),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_renameat, 0, 1),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EPERM),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_renameat2, 0, 1),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EPERM),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_openat, 0, 1),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EPERM),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
        };
        struct sock_fprog program = { .len = sizeof(filter) / sizeof(filter[0]), .filter = filter };
        CHECK(prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) == 0);
        CHECK(prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &program) == 0);
        CHECK(query(dir, "file", &result, 0) == -1 && errno == EPERM);
        CHECK(syscall(SYS_openat, dir, "file", O_RDONLY | O_CLOEXEC, 0) == -1 && errno == EPERM);
        CHECK(query(AT_FDCWD, absolute, &result, 0) == -1 && errno == EPERM);
        CHECK(syscall(SYS_unlinkat, dir, "file", 0) == -1 && errno == EPERM);
        CHECK(syscall(SYS_linkat, dir, "file", dir, "denied", 0) == -1 && errno == EPERM);
        CHECK(syscall(SYS_renameat, dir, "file", dir, "denied") == -1 && errno == EPERM);
        CHECK(syscall(SYS_renameat2, dir, "file", dir, "denied", 0) == -1 && errno == EPERM);
        _exit(0);
    }
    int status; CHECK(waitpid(child, &status, 0) == child && WIFEXITED(status) && WEXITSTATUS(status) == 0);
    CHECK(query(dir, "file", &result, 0) == 0 && result.st_uid == 0);
    CHECK(unlinkat(dir, "sym", 0) == 0 && unlinkat(dir, "hard", 0) == 0 && unlinkat(dir, "file", 0) == 0);
    CHECK(close(file) == 0 && close(dir) == 0 && rmdir(root) == 0);
    puts("PASS raw stat/mutation/open, cwd, links, flags, pointers, identity, concurrent readers and seccomp");
    return 0;
}
