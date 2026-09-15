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
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>

#define CHECK(x) do { if (!(x)) { fprintf(stderr, "FAIL line %d: %s errno=%d\n", __LINE__, #x, errno); exit(1); } } while (0)
static int query(int fd, const char *path, void *out, int flags)
{ return syscall(SYS_newfstatat, fd, path, out, flags); }

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
    CHECK(symlinkat("file", dir, "sym") == 0);
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
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
        };
        struct sock_fprog program = { .len = sizeof(filter) / sizeof(filter[0]), .filter = filter };
        CHECK(prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) == 0);
        CHECK(prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &program) == 0);
        CHECK(query(dir, "file", &result, 0) == -1 && errno == EPERM);
        _exit(0);
    }
    int status; CHECK(waitpid(child, &status, 0) == child && WIFEXITED(status) && WEXITSTATUS(status) == 0);
    CHECK(query(dir, "file", &result, 0) == 0 && result.st_uid == 0);
    CHECK(unlinkat(dir, "sym", 0) == 0 && unlinkat(dir, "hard", 0) == 0 && unlinkat(dir, "file", 0) == 0);
    CHECK(close(file) == 0 && close(dir) == 0 && rmdir(root) == 0);
    puts("PASS raw stat entry, links, flags, pointers, identity and seccomp");
    return 0;
}
