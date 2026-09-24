/* SPDX-License-Identifier: Apache-2.0 */
/* Same CLOCK_MONOTONIC timeline across fork/exec. Emit only after reaping. */
#define _GNU_SOURCE
#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#define CHECK(x) do { if (!(x)) { fprintf(stderr, "STARTUP FAIL %d errno=%d\n", __LINE__, errno); exit(1); } } while (0)
static uint64_t now(void)
{
    struct timespec time;
    CHECK(clock_gettime(CLOCK_MONOTONIC, &time) == 0);
    return (uint64_t)time.tv_sec * 1000000000ULL + time.tv_nsec;
}
static void transfer(int fd, void *data, size_t size, int sending)
{
    while (size) {
        ssize_t n = sending ? write(fd, data, size) : read(fd, data, size);
        if (n < 0 && errno == EINTR) continue;
        CHECK(n > 0); data = (char *)data + n; size -= (size_t)n;
    }
}
int main(int argc, char **argv)
{
    uint64_t main_enter = now();
    if (argc == 3 && !strcmp(argv[1], "child")) {
        int fd = atoi(argv[2]);
        uint64_t times[] = {main_enter, now()};
        transfer(fd, times, sizeof(times), 1);
        _exit(0);
    }
    CHECK(argc == 2 && (!strcmp(argv[1], "fork-exit") || !strcmp(argv[1], "exec-static") || !strcmp(argv[1], "exec-dynamic")));
    int execute = strcmp(argv[1], "fork-exit") != 0;
    const char *path = !strcmp(argv[1], "exec-static") ? "/bin/startup-static" : "/bin/startup-dynamic";
    for (int round = 0; round < 30; round++) {
        int pipes[2]; CHECK(pipe(pipes) == 0);
        char fd[24]; snprintf(fd, sizeof(fd), "%d", pipes[1]);
        char *args[] = {(char *)path, "child", fd, NULL};
        uint64_t before = now();
        pid_t child = fork(); CHECK(child >= 0);
        uint64_t returned = now();
        if (!child) {
            close(pipes[0]);
            // Publish pre-exec observations before the old image disappears.
            uint64_t times[] = {returned, now()};
            transfer(pipes[1], times, sizeof(times), 1);
            if (execute) { execve(path, args, environ); _exit(99); }
            uint64_t end[] = {0, now()};
            transfer(pipes[1], end, sizeof(end), 1); _exit(0);
        }
        close(pipes[1]);
        int status; pid_t waited;
        do { waited = waitpid(child, &status, 0); } while (waited < 0 && errno == EINTR);
        uint64_t after = now();
        CHECK(waited == child && WIFEXITED(status) && WEXITSTATUS(status) == 0);
        uint64_t times[4]; transfer(pipes[0], times, sizeof(times), 0); close(pipes[0]);
        CHECK(times[0] >= before && times[1] >= times[0] && times[3] >= times[1] && after >= times[3]);
        CHECK(!execute || (times[2] >= times[1] && times[3] >= times[2]));
        printf("STARTUP_SAMPLE {\"case\":\"%s\",\"round\":%d,\"pid\":%d,\"before_fork_ns\":%llu,"
            "\"parent_return_ns\":%llu,\"child_return_ns\":%llu,\"before_exec_ns\":%llu,"
            "\"main_ns\":%llu,\"before_exit_ns\":%llu,\"reaped_ns\":%llu}\n",
            argv[1], round, child, (unsigned long long)before, (unsigned long long)returned,
            (unsigned long long)times[0], (unsigned long long)times[1], (unsigned long long)times[2],
            (unsigned long long)times[3], (unsigned long long)after);
        CHECK(fflush(stdout) == 0);
    }
    puts("PASS startup timeline"); return 0;
}
