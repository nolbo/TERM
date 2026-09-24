/* SPDX-License-Identifier: Apache-2.0 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <linux/close_range.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#define CHECK(x) do { if (!(x)) { fprintf(stderr, "FD FAIL %d errno=%d\n", __LINE__, errno); return 1; } } while (0)
static long range(unsigned first, unsigned last, unsigned flags)
{
    return syscall(SYS_close_range, first, last, flags);
}
static int closed(int fd) { return fcntl(fd, F_GETFD) == -1 && errno == EBADF; }

int main(int argc, char **argv)
{
    if (argc == 2 && !strcmp(argv[1], "after")) {
        for (int fd = 3; fd < 1024; fd++) CHECK(closed(fd));
        int fd = open("/input", O_RDONLY); CHECK(fd >= 0 && close(fd) == 0);
        puts("PASS close_range exec cleanup"); return 0;
    }
    CHECK(argc == 1);
    int fd = open("/input", O_RDONLY); CHECK(fd >= 3 && fd < 100);
    for (int i = 100; i <= 102; i++) CHECK(dup2(fd, i) == i);
    CHECK(range(101, 101, 0) == 0 && closed(101));
    CHECK(fcntl(100, F_GETFD) == 0 && fcntl(102, F_GETFD) == 0);
    CHECK(range(102, 100, 0) == -1 && errno == EINVAL);
    CHECK(range(100, 102, 1) == -1 && errno == EINVAL);
    CHECK(range(100, 102, 8) == -1 && errno == EINVAL);
    CHECK(fcntl(100, F_GETFD) == 0 && fcntl(102, F_GETFD) == 0);
    CHECK(range(UINT_MAX, UINT_MAX, 0) == 0);
    CHECK(range(INT_MAX, UINT_MAX, CLOSE_RANGE_UNSHARE | CLOSE_RANGE_CLOEXEC) == 0);
    // More than one getdents buffer, including holes and inclusive endpoints.
    for (int i = 300; i < 520; i++) CHECK(fcntl(fd, F_DUPFD, i) == i);
    CHECK(close(401) == 0);
    CHECK(range(300, 519, 0) == 0);
    for (int i = 300; i < 520; i++) CHECK(closed(i));
    CHECK(fcntl(100, F_GETFD) == 0 && fcntl(102, F_GETFD) == 0);
    CHECK(range(100, 101, CLOSE_RANGE_CLOEXEC) == 0);
    CHECK(fcntl(100, F_GETFD) == FD_CLOEXEC && fcntl(102, F_GETFD) == 0);
    int status;
    pid_t child = fork(); CHECK(child >= 0);
    if (!child) {
        CHECK(range(3, UINT_MAX, CLOSE_RANGE_CLOEXEC | CLOSE_RANGE_UNSHARE) == 0);
        CHECK(fcntl(102, F_GETFD) == FD_CLOEXEC);
        char *missing[] = {"/missing-program", NULL};
        CHECK(execve(missing[0], missing, environ) == -1 && errno == ENOENT);
        CHECK(fcntl(102, F_GETFD) == FD_CLOEXEC);
        char *args[] = {argv[0], "after", NULL};
        execve(args[0], args, environ); _exit(90);
    }
    CHECK(waitpid(child, &status, 0) == child && WIFEXITED(status) && WEXITSTATUS(status) == 0);
    CHECK(fcntl(100, F_GETFD) == FD_CLOEXEC && fcntl(102, F_GETFD) == 0);
    child = fork(); CHECK(child >= 0);
    if (!child) {
        // Closing even stdio must leave the private root descriptor usable.
        struct stat st;
        if (range(0, UINT_MAX, CLOSE_RANGE_UNSHARE) || stat("/input", &st) || st.st_size != 8) _exit(91);
        for (int i = 0; i < 1024; i++) if (!closed(i)) _exit(92);
        _exit(0);
    }
    CHECK(waitpid(child, &status, 0) == child && WIFEXITED(status) && WEXITSTATUS(status) == 0);
    struct timespec before, after;
    CHECK(clock_gettime(CLOCK_MONOTONIC, &before) == 0);
    child = vfork(); CHECK(child >= 0);
    if (!child) {
        if (range(3, UINT_MAX, 0)) _exit(93);
        struct timespec delay = {.tv_nsec = 100000000};
        if (nanosleep(&delay, NULL)) _exit(94);
        char *args[] = {"/bin/true", NULL};
        execve(args[0], args, environ); _exit(95);
    }
    CHECK(clock_gettime(CLOCK_MONOTONIC, &after) == 0);
    CHECK((after.tv_sec - before.tv_sec) * 1000000000L + after.tv_nsec - before.tv_nsec >= 80000000L);
    CHECK(waitpid(child, &status, 0) == child && WIFEXITED(status) && WEXITSTATUS(status) == 0);
    CHECK(fcntl(100, F_GETFD) == FD_CLOEXEC && fcntl(102, F_GETFD) == 0);
    CHECK(close(fd) == 0 && close(100) == 0 && close(102) == 0);
    puts("PASS close_range bounds/flags/sparse/CLOEXEC/fork/vfork/private FDs");
    return 0;
}
