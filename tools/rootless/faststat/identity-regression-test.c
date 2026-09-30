/* SPDX-License-Identifier: GPL-2.0-or-later */
/* Native/PRoot ownership and hardlink contract; independent of preload hooks. */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
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

#define CHECK(x) do { if (!(x)) { fprintf(stderr, "FAIL identity:%d %s errno=%d\n", __LINE__, #x, errno); exit(1); } } while (0)
static uid_t wanted_uid;
static gid_t wanted_gid;
static ino_t file_inode;
static int directory, file;
static char absolute[4096];

static void metadata(const struct stat *value, nlink_t links, const char *operation)
{
    if (value->st_uid != wanted_uid || value->st_gid != wanted_gid)
        fprintf(stderr, "%s ownership got=%u:%u expected=%u:%u nlink=%lu inode=%lu\n", operation,
            value->st_uid, value->st_gid, wanted_uid, wanted_gid, (unsigned long)value->st_nlink,
            (unsigned long)value->st_ino);
    CHECK(value->st_uid == wanted_uid && value->st_gid == wanted_gid);
    CHECK(S_ISREG(value->st_mode) && value->st_size == 4 && value->st_ino == file_inode);
    CHECK(value->st_nlink == links);
}

static void check_name(const char *name, nlink_t links)
{
    struct stat value;
    CHECK(syscall(SYS_newfstatat, directory, name, &value, 0) == 0); metadata(&value, links, "raw relative stat");
    CHECK(syscall(SYS_newfstatat, directory, name, &value, AT_SYMLINK_NOFOLLOW) == 0); metadata(&value, links, "raw relative lstat");
    CHECK(fstatat(directory, name, &value, 0) == 0); metadata(&value, links, "libc relative stat");
}

static void check_file(nlink_t links)
{
    struct stat value;
    CHECK(syscall(SYS_newfstatat, AT_FDCWD, absolute, &value, 0) == 0); metadata(&value, links, "raw absolute stat");
    CHECK(stat(absolute, &value) == 0); metadata(&value, links, "libc stat");
    CHECK(lstat(absolute, &value) == 0); metadata(&value, links, "libc lstat");
    CHECK(syscall(SYS_fstat, file, &value) == 0); metadata(&value, links, "raw fstat");
    CHECK(fstat(file, &value) == 0); metadata(&value, links, "libc fstat");
    check_name("file", links);
}

static void reap(pid_t child)
{
    int status; CHECK(waitpid(child, &status, 0) == child);
    CHECK(WIFEXITED(status) && WEXITSTATUS(status) == 0);
}

static void descendants(nlink_t links, int change_ids)
{
    pid_t child = fork(); CHECK(child >= 0);
    if (!child) {
        check_file(links);
        pid_t grandchild = fork(); CHECK(grandchild >= 0);
        if (!grandchild) {
            check_file(links);
            if (change_ids) {
                CHECK(setresgid(2346, 2346, 2346) == 0 && setresuid(1235, 1235, 1235) == 0);
                wanted_uid = 1235; wanted_gid = 2346; check_file(links);
            }
            _exit(0);
        }
        reap(grandchild); check_file(links); _exit(0);
    }
    reap(child); check_file(links);
}

static void faults(void)
{
    struct stat value;
    CHECK(syscall(SYS_newfstatat, directory, "missing", &value, 0) == -1 && errno == ENOENT);
    CHECK(syscall(SYS_newfstatat, -123, "file", &value, 0) == -1 && errno == EBADF);
    CHECK(syscall(SYS_newfstatat, directory, "file", &value, 0x40000000) == -1 && errno == EINVAL);
    CHECK(syscall(SYS_newfstatat, directory, (void *)1, &value, 0) == -1 && errno == EFAULT);
    CHECK(syscall(SYS_newfstatat, directory, "file", (void *)1, 0) == -1 && errno == EFAULT);
    size_t page = (size_t)sysconf(_SC_PAGESIZE);
    void *readonly = mmap(NULL, page, PROT_READ, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0); CHECK(readonly != MAP_FAILED);
    CHECK(syscall(SYS_newfstatat, directory, "file", readonly, 0) == -1 && errno == EFAULT);
    CHECK(syscall(SYS_fstat, file, readonly) == -1 && errno == EFAULT);
    CHECK(munmap(readonly, page) == 0);
    pid_t child = fork(); CHECK(child >= 0);
    if (!child) {
        struct sock_filter filter[] = {
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_newfstatat, 0, 1),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EPERM),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
        };
        struct sock_fprog program = {.len = sizeof(filter) / sizeof(filter[0]), .filter = filter};
        CHECK(prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) == 0 && prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &program) == 0);
        CHECK(syscall(SYS_newfstatat, directory, "file", &value, 0) == -1 && errno == EPERM);
        _exit(0);
    }
    reap(child);
}

int main(int argc, char **argv)
{
    alarm(30);
    CHECK(argc == 6 || argc == 7);
    wanted_uid = (uid_t)strtoul(argv[1], NULL, 10); wanted_gid = (gid_t)strtoul(argv[2], NULL, 10);
    CHECK(getuid() == wanted_uid && getgid() == wanted_gid);
    char temporary[4096];
    CHECK(snprintf(temporary, sizeof(temporary), "%s/identity-XXXXXX", argv[3]) < (int)sizeof(temporary));
    CHECK(mkdtemp(temporary));
    directory = open(temporary, O_RDONLY | O_DIRECTORY); CHECK(directory >= 0);
    file = openat(directory, "file", O_CREAT | O_EXCL | O_RDWR, 0640); CHECK(file >= 0);
    CHECK(write(file, "data", 4) == 4);
    struct stat value; CHECK(syscall(SYS_fstat, file, &value) == 0); file_inode = value.st_ino;
    CHECK(snprintf(absolute, sizeof(absolute), "%s/file", temporary) < (int)sizeof(absolute));
    check_file(1);
    CHECK(linkat(directory, "file", directory, "hard", 0) == 0); check_file(2); check_name("hard", 2);
    CHECK(linkat(directory, "hard", directory, "extra", 0) == 0); check_file(3); check_name("extra", 3);
    descendants(3, argc == 7);
    CHECK(unlinkat(directory, "extra", 0) == 0); check_file(2);
    CHECK(unlinkat(directory, "hard", 0) == 0); check_file(1);
    descendants(1, argc == 7);
    CHECK(symlinkat("file", directory, "symbolic") == 0);
    CHECK(syscall(SYS_newfstatat, directory, "symbolic", &value, 0) == 0); metadata(&value, 1, "follow symlink");
    CHECK(syscall(SYS_newfstatat, directory, "symbolic", &value, AT_SYMLINK_NOFOLLOW) == 0);
    CHECK(S_ISLNK(value.st_mode) && value.st_ino != file_inode && value.st_nlink == 1);
    CHECK(value.st_uid == wanted_uid && value.st_gid == wanted_gid);
    CHECK(syscall(SYS_newfstatat, AT_FDCWD, argv[4], &value, 0) == 0);
    unsigned foreign_uid, foreign_gid;
    CHECK(sscanf(argv[5], "%u:%u", &foreign_uid, &foreign_gid) == 2);
    CHECK(value.st_uid == foreign_uid && value.st_gid == foreign_gid);
    faults(); check_file(1);
    CHECK(unlinkat(directory, "file", 0) == 0);
    CHECK(syscall(SYS_fstat, file, &value) == 0); metadata(&value, 0, "unlinked fstat");
    CHECK(syscall(SYS_newfstatat, directory, "symbolic", &value, 0) == -1 && errno == ENOENT);
    CHECK(unlinkat(directory, "symbolic", 0) == 0 && close(file) == 0 && close(directory) == 0);
    CHECK(rmdir(temporary) == 0);
    puts("PASS identity across raw/libc stat, hardlink lifecycle, fork generations, symlinks, foreign owners and kernel errors");
    return 0;
}
