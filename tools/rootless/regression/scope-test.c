/* SPDX-License-Identifier: GPL-2.0-or-later */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <sched.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#define CHECK(x) do { if (!(x)) { dprintf(2, "FAIL scope line %d: %s errno=%d\n", __LINE__, #x, errno); _exit(1); } } while (0)
static void wait_child(pid_t pid)
{
    int status;
    CHECK(waitpid(pid, &status, 0) == pid && WIFEXITED(status) && WEXITSTATUS(status) == 0);
}

static void reader(int ready, int gate, int diagnostic)
{
    unsigned long (*hits)(void) = dlsym(RTLD_DEFAULT, "proot_faststat_hits");
    CHECK(hits);
    struct stat expected, actual;
    CHECK(syscall(SYS_newfstatat, AT_FDCWD, "/input", &expected, 0) == 0);
    unsigned long initial = hits();
    CHECK(stat("/input", &actual) == 0 && hits() == initial + 1);
    char byte = 'x';
    CHECK(write(ready, &byte, 1) == 1 && read(gate, &byte, 1) == 1);
    struct timespec begin, end;
    CHECK(clock_gettime(CLOCK_MONOTONIC, &begin) == 0);
    initial = hits();
    for (unsigned i = 0; i < 5000; i++) {
        CHECK(stat("/input", &actual) == 0);
        CHECK(actual.st_ino == expected.st_ino && actual.st_size == expected.st_size &&
              actual.st_uid == expected.st_uid && actual.st_gid == expected.st_gid);
        CHECK(lstat("/input", &actual) == 0 && actual.st_ino == expected.st_ino);
    }
    CHECK(clock_gettime(CLOCK_MONOTONIC, &end) == 0);
    unsigned long delta = hits() - initial;
    double elapsed = (end.tv_sec - begin.tv_sec) * 1000.0 + (end.tv_nsec - begin.tv_nsec) / 1000000.0;
    dprintf(1, "{\"fd_scope\":{\"queries\":10000,\"hits\":%lu,\"milliseconds\":%.6f,\"diagnostic\":%d}}\n",
            delta, elapsed, diagnostic);
    if (!diagnostic) CHECK(delta == 10000);
}

static void forker(int ready, int gate)
{
    char byte;
    CHECK(read(ready, &byte, 1) == 1);
    for (unsigned i = 0; i < 101; i++) {
        /* CLONE_FILES can share a table across different process IDs. The
         * conservative lineage must cover it without relying on thread IDs. */
        pid_t child = i % 3 == 0 ? syscall(SYS_clone, SIGCHLD | CLONE_FILES, 0, 0, 0, 0) : fork();
        CHECK(child >= 0);
        if (!child) {
            unsigned long (*hits)(void) = dlsym(RTLD_DEFAULT, "proot_faststat_hits");
            CHECK(hits);
            unsigned long initial = hits();
            struct stat info;
            CHECK(stat("/input", &info) == 0 && hits() == initial);
            _exit(0);
        }
        wait_child(child);
        /* The reader starts only after this peer's first fork has completed. */
        if (i == 0) CHECK(write(gate, &byte, 1) == 1);
    }
}

int main(int argc, char **argv)
{
    if (argc == 5 && !strcmp(argv[1], "reader")) {
        reader(atoi(argv[2]), atoi(argv[3]), atoi(argv[4]));
        return 0;
    }
    if (argc == 4 && !strcmp(argv[1], "forker")) {
        forker(atoi(argv[2]), atoi(argv[3]));
        return 0;
    }
    CHECK(argc == 1 || (argc == 2 && !strcmp(argv[1], "diagnostic")));
    int diagnostic = argc == 2;
    int ready[2], gate[2];
    CHECK(pipe(ready) == 0 && pipe(gate) == 0);
    char ready_read[16], ready_write[16], gate_read[16], gate_write[16];
    snprintf(ready_read, sizeof(ready_read), "%d", ready[0]);
    snprintf(ready_write, sizeof(ready_write), "%d", ready[1]);
    snprintf(gate_read, sizeof(gate_read), "%d", gate[0]);
    snprintf(gate_write, sizeof(gate_write), "%d", gate[1]);
    pid_t first = fork();
    CHECK(first >= 0);
    if (!first) {
        execl(argv[0], argv[0], "reader", ready_write, gate_read, diagnostic ? "1" : "0", (char *)NULL);
        CHECK(0);
    }
    pid_t second = fork();
    CHECK(second >= 0);
    if (!second) {
        execl(argv[0], argv[0], "forker", ready_read, gate_write, (char *)NULL);
        CHECK(0);
    }
    wait_child(first);
    wait_child(second);
    if (diagnostic) puts("PASS independent reader metadata and peer fork exits");
    else puts("PASS exec-isolated FD scope: independent reader keeps acceleration while peer forks");
    return 0;
}
