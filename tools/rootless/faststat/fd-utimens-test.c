/* SPDX-License-Identifier: GPL-2.0-or-later */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#define CHECK(expression) do { if (!(expression)) { \
    fprintf(stderr, "FAIL fd-utimens line %d: %s (errno=%d)\n", __LINE__, #expression, errno); \
    exit(1); } } while (0)

static void check_time(int fd, const struct timespec times[2])
{
    struct stat info;
    CHECK(fstat(fd, &info) == 0);
    CHECK(info.st_atim.tv_sec == times[0].tv_sec && info.st_atim.tv_nsec == times[0].tv_nsec);
    CHECK(info.st_mtim.tv_sec == times[1].tv_sec && info.st_mtim.tv_nsec == times[1].tv_nsec);
}

static void denied_update(int fd, const struct timespec times[2])
{
    pid_t child = fork();
    CHECK(child >= 0);
    if (child == 0) {
        struct sock_filter filter[] = {
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_utimensat, 0, 1),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EPERM),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
        };
        struct sock_fprog program = { .len = sizeof(filter) / sizeof(filter[0]), .filter = filter };
        CHECK(prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) == 0);
        CHECK(prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &program) == 0);
        errno = 0;
        CHECK(syscall(SYS_utimensat, fd, NULL, times, 0) == -1 && errno == EPERM);
        _exit(0);
    }
    int status;
    CHECK(waitpid(child, &status, 0) == child);
    CHECK(WIFEXITED(status) && WEXITSTATUS(status) == 0);
}

int main(int argc, char **argv)
{
    alarm(30);
    char directory[] = "/tmp/rootless-fd-utimens.XXXXXX";
    CHECK(mkdtemp(directory) != NULL);
    char path[256];
    CHECK(snprintf(path, sizeof(path), "%s/file", directory) < (int)sizeof(path));
    int fd = open(path, O_CREAT | O_EXCL | O_RDWR | O_CLOEXEC, 0640);
    CHECK(fd >= 0);
    CHECK(write(fd, "timestamp", 9) == 9);
    const struct timespec times[2] = { { 1700000000, 123456789 }, { 1700000001, 987654321 } };

    if (argc == 3 && strcmp(argv[1], "bench") == 0) {
        long count = strtol(argv[2], NULL, 10);
        CHECK(count > 0 && count <= 1000000);
        struct timespec begin, end;
        CHECK(clock_gettime(CLOCK_MONOTONIC, &begin) == 0);
        for (long i = 0; i < count; ++i)
            CHECK(syscall(SYS_utimensat, fd, NULL, times, 0) == 0);
        CHECK(clock_gettime(CLOCK_MONOTONIC, &end) == 0);
        check_time(fd, times);
        double ms = (end.tv_sec - begin.tv_sec) * 1000.0 + (end.tv_nsec - begin.tv_nsec) / 1000000.0;
        printf("{\"case\":\"fd-utimens\",\"count\":%ld,\"milliseconds\":%.6f}\n", count, ms);
    } else {
        CHECK(argc == 1);
        CHECK(syscall(SYS_utimensat, fd, NULL, times, 0) == 0);
        check_time(fd, times);
        CHECK(futimens(fd, times) == 0);
        check_time(fd, times);

        const struct timespec omit[2] = { { 0, UTIME_OMIT }, { 0, UTIME_OMIT } };
        CHECK(syscall(SYS_utimensat, fd, NULL, omit, 0) == 0);
        check_time(fd, times);
        errno = 0;
        CHECK(syscall(SYS_utimensat, -1, NULL, times, 0) == -1 && errno == EBADF);
        errno = 0;
        CHECK(syscall(SYS_utimensat, fd, NULL, (void *)1, 0) == -1 && errno == EFAULT);
        errno = 0;
        CHECK(syscall(SYS_utimensat, fd, (void *)0x1234567800000000ULL, times, 0) == -1 && errno == EFAULT);
        errno = 0;
        CHECK(syscall(SYS_utimensat, fd, NULL, times, 0x40000000) == -1 && errno == EINVAL);
        check_time(fd, times);

        int path_fd = open(path, O_PATH | O_CLOEXEC);
        CHECK(path_fd >= 0);
        errno = 0;
        CHECK(syscall(SYS_utimensat, path_fd, NULL, times, 0) == -1 && errno == EBADF);
        CHECK(close(path_fd) == 0);
        CHECK(syscall(SYS_utimensat, AT_FDCWD, path, times, 0) == 0);
        check_time(fd, times);

        int duplicate = dup(fd);
        CHECK(duplicate >= 0);
        CHECK(close(fd) == 0);
        fd = duplicate;
        CHECK(unlink(path) == 0);
        CHECK(syscall(SYS_utimensat, fd, NULL, times, 0) == 0);
        check_time(fd, times);
        denied_update(fd, omit);
        check_time(fd, times);

        time_t before = time(NULL);
        CHECK(syscall(SYS_utimensat, fd, NULL, NULL, 0) == 0);
        struct stat info;
        CHECK(fstat(fd, &info) == 0);
        CHECK(info.st_mtim.tv_sec >= before && info.st_mtim.tv_sec <= time(NULL) + 1);
        CHECK((info.st_mode & 0777) == 0640 && info.st_size == 9);
        char content[9];
        CHECK(pread(fd, content, sizeof(content), 0) == 9 && memcmp(content, "timestamp", 9) == 0);
        puts("PASS fd-utimens: raw/libc, nanoseconds, omit/now, errors, 64-bit pointer, pathname, dup/unlink, seccomp");
    }
    CHECK(close(fd) == 0);
    if (access(path, F_OK) == 0) CHECK(unlink(path) == 0);
    CHECK(rmdir(directory) == 0);
    return 0;
}
