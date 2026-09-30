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
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>

#define CHECK(x) do { if (!(x)) { fprintf(stderr, "FAIL line %d: %s errno=%d\n", __LINE__, #x, errno); exit(1); } } while (0)

static ino_t check_file(int directory, const char *path, const char *contents, nlink_t links)
{
    struct stat st;
    CHECK(syscall(SYS_newfstatat, directory, path, &st, AT_SYMLINK_NOFOLLOW) == 0);
    CHECK(S_ISREG(st.st_mode) && st.st_nlink == links);
    int fd = openat(directory, path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    CHECK(fd >= 0);
    char buffer[32] = {0};
    ssize_t length = read(fd, buffer, sizeof(buffer));
    CHECK(length == (ssize_t)strlen(contents) && !memcmp(buffer, contents, length));
    CHECK(close(fd) == 0);
    return st.st_ino;
}

static void create_file(int directory, const char *path, const char *contents)
{
    int fd = openat(directory, path, O_CREAT | O_EXCL | O_RDWR, 0600);
    CHECK(fd >= 0 && write(fd, contents, strlen(contents)) == (ssize_t)strlen(contents));
    CHECK(close(fd) == 0);
}

static void wait_success(pid_t child)
{
    int status;
    CHECK(waitpid(child, &status, 0) == child && WIFEXITED(status) && WEXITSTATUS(status) == 0);
}

static void replace_link_from_plain_file(int directory)
{
    /* dpkg upgrade links the old file to .dpkg-tmp, then renames a new
     * ordinary file over the old name.  The entry acceleration sees an
     * ordinary source, but the destination still needs l2s exit accounting. */
    CHECK(mkdirat(directory, "package-upgrade", 0700) == 0);
    int upgrade = openat(directory, "package-upgrade", O_RDONLY | O_DIRECTORY);
    CHECK(upgrade >= 0);
    for (int operation = 0; operation < 2; operation++) {
        for (int version = 0; version < 8; version++) {
            create_file(upgrade, "installed", "old package");
            CHECK(linkat(upgrade, "installed", upgrade, "backup", 0) == 0);
            ino_t old = check_file(upgrade, "backup", "old package", 2);
            create_file(upgrade, "new", "new package");
            long result = operation
                ? syscall(SYS_renameat2, upgrade, "new", upgrade, "installed", 0)
                : syscall(SYS_renameat, upgrade, "new", upgrade, "installed");
            CHECK(result == 0);
            CHECK(check_file(upgrade, "backup", "old package", 1) == old);
            CHECK(check_file(upgrade, "installed", "new package", 1) != old);
            CHECK(unlinkat(upgrade, "backup", 0) == 0);
            CHECK(unlinkat(upgrade, "installed", 0) == 0);
        }
    }
    CHECK(close(upgrade) == 0);
    /* Directory removal also detects invisible leaked intermediate/backing
     * entries, even if all visible package files were removed successfully. */
    CHECK(unlinkat(directory, "package-upgrade", AT_REMOVEDIR) == 0);
}

int main(int argc, char **argv)
{
    alarm(60);
    CHECK(argc == 2);
    char root[PATH_MAX];
    CHECK(snprintf(root, sizeof(root), "%s/link-mutation-XXXXXX", argv[1]) < (int)sizeof(root));
    CHECK(mkdtemp(root) != NULL);
    int directory = open(root, O_RDONLY | O_DIRECTORY);
    CHECK(directory >= 0);
    replace_link_from_plain_file(directory);
    create_file(directory, "old", "old package");
    CHECK(linkat(directory, "old", directory, "hard", 0) == 0);
    ino_t old_inode = check_file(directory, "old", "old package", 2);
    CHECK(check_file(directory, "hard", "old package", 2) == old_inode);
    CHECK(syscall(SYS_renameat, directory, "old", directory, "hard") == 0);
    CHECK(check_file(directory, "old", "old package", 2) == old_inode);
    CHECK(check_file(directory, "hard", "old package", 2) == old_inode);
    CHECK(syscall(SYS_renameat2, directory, "old", directory, "hard", 0) == 0);
    CHECK(check_file(directory, "old", "old package", 2) == old_inode);
    CHECK(check_file(directory, "hard", "old package", 2) == old_inode);
    CHECK(syscall(SYS_unlinkat, directory, "old", 0x40000000) == -1 && errno == EINVAL);
    CHECK(check_file(directory, "old", "old package", 2) == old_inode);
    CHECK(syscall(SYS_renameat2, directory, "old", directory, "hard", 1) == -1 && errno == EEXIST);
    CHECK(check_file(directory, "old", "old package", 2) == old_inode);
    CHECK(check_file(directory, "hard", "old package", 2) == old_inode);
    CHECK(syscall(SYS_renameat2, directory, "old", directory, "hard", 0x40000000) == -1 && errno == EINVAL);
    CHECK(check_file(directory, "hard", "old package", 2) == old_inode);
    CHECK(syscall(SYS_renameat, directory, "missing", directory, "hard") == -1 && errno == ENOENT);
    CHECK(check_file(directory, "hard", "old package", 2) == old_inode);
    CHECK(mkdirat(directory, "folder", 0700) == 0);
    CHECK(syscall(SYS_renameat, directory, "folder", directory, "hard") == -1 && errno == ENOTDIR);
    CHECK(check_file(directory, "hard", "old package", 2) == old_inode);

    /* Failed guest seccomp calls must leave both namespace and backing intact,
     * including when PRoot uses ordinary syscall stops instead of seccomp. */
    pid_t child = fork(); CHECK(child >= 0);
    if (!child) {
        struct sock_filter filter[] = {
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_unlinkat, 0, 1),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EPERM),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_renameat, 0, 1),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EPERM),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
        };
        struct sock_fprog program = { .len = sizeof(filter) / sizeof(filter[0]), .filter = filter };
        CHECK(prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) == 0);
        CHECK(prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &program) == 0);
        CHECK(syscall(SYS_unlinkat, directory, "old", 0) == -1 && errno == EPERM);
        CHECK(syscall(SYS_renameat, directory, "old", directory, "hard") == -1 && errno == EPERM);
        _exit(0);
    }
    wait_success(child);
    CHECK(check_file(directory, "old", "old package", 2) == old_inode);
    CHECK(check_file(directory, "hard", "old package", 2) == old_inode);

    create_file(directory, "new", "new package");
    CHECK(linkat(directory, "new", directory, "new-hard", 0) == 0);
    ino_t new_inode = check_file(directory, "new", "new package", 2);
    CHECK(syscall(SYS_renameat2, directory, "old", directory, "new", 2) == 0);
    CHECK(check_file(directory, "new", "old package", 2) == old_inode);
    CHECK(check_file(directory, "old", "new package", 2) == new_inode);
    CHECK(check_file(directory, "hard", "old package", 2) == old_inode);
    CHECK(check_file(directory, "new-hard", "new package", 2) == new_inode);
    /* Simulate replacing a package file while a previous hard link survives. */
    CHECK(syscall(SYS_renameat, directory, "old", directory, "new") == 0);
    CHECK(check_file(directory, "new", "new package", 2) == new_inode);
    CHECK(check_file(directory, "hard", "old package", 1) == old_inode);
    CHECK(symlinkat("new", directory, "symbolic") == 0);
    struct stat st;
    CHECK(fstatat(directory, "symbolic", &st, AT_SYMLINK_NOFOLLOW) == 0 && S_ISLNK(st.st_mode));
    CHECK(unlinkat(directory, "symbolic", 0) == 0);
    CHECK(check_file(directory, "new", "new package", 2) == new_inode);
    CHECK(syscall(SYS_renameat2, directory, "new-hard", directory, "moved", 1) == 0);
    CHECK(check_file(directory, "moved", "new package", 2) == new_inode);
    CHECK(unlinkat(directory, "moved", 0) == 0);
    CHECK(check_file(directory, "new", "new package", 1) == new_inode);

    /* A directory FD remains authoritative after its parent is renamed. */
    CHECK(mkdirat(directory, "before", 0700) == 0);
    int subdir = openat(directory, "before", O_RDONLY | O_DIRECTORY);
    CHECK(subdir >= 0 && linkat(directory, "new", subdir, "linked", 0) == 0);
    CHECK(renameat(directory, "before", directory, "after") == 0);
    CHECK(unlinkat(subdir, "linked", 0) == 0 && close(subdir) == 0);
    CHECK(check_file(directory, "new", "new package", 1) == new_inode);

    /* Several children remove different aliases of the same backing inode. */
    enum { CHILDREN = 4, LINKS = 24 };
    char name[32];
    for (int i = 0; i < CHILDREN * LINKS; i++) {
        CHECK(snprintf(name, sizeof(name), "parallel-%d", i) < (int)sizeof(name));
        CHECK(linkat(directory, "new", directory, name, 0) == 0);
    }
    pid_t children[CHILDREN];
    for (int i = 0; i < CHILDREN; i++) {
        children[i] = fork(); CHECK(children[i] >= 0);
        if (!children[i]) {
            for (int j = 0; j < LINKS; j++) {
                snprintf(name, sizeof(name), "parallel-%d", i * LINKS + j);
                CHECK(syscall(SYS_unlinkat, directory, name, 0) == 0);
            }
            _exit(0);
        }
    }
    for (int i = 0; i < CHILDREN; i++) wait_success(children[i]);
    CHECK(check_file(directory, "new", "new package", 1) == new_inode);
    int fd = openat(directory, "new", O_RDONLY);
    CHECK(fd >= 0 && unlinkat(directory, "new", 0) == 0);
    CHECK(syscall(SYS_fstat, fd, &st) == 0 && st.st_nlink == 0);
    CHECK(close(fd) == 0 && unlinkat(directory, "hard", 0) == 0);
    CHECK(unlinkat(directory, "folder", AT_REMOVEDIR) == 0);
    CHECK(unlinkat(directory, "after", AT_REMOVEDIR) == 0);
    CHECK(close(directory) == 0 && rmdir(root) == 0);
    puts("PASS link mutation failures, exchange, package replacement, dirfd, fork and final unlink");
    return 0;
}
