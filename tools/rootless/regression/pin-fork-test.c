/* SPDX-License-Identifier: GPL-2.0-or-later */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <sched.h>
#include <signal.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/resource.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>

#define CHECK(x) do { if (!(x)) { dprintf(2, "FAIL pin-fork line %d: %s errno=%d\n", __LINE__, #x, errno); _exit(1); } } while (0)
static atomic_int started, stop;
static atomic_uint iterations;
static struct stat expected;
static int user_pin;

static void check_stat(void)
{
    struct stat actual;
    CHECK(stat("/input", &actual) == 0);
    CHECK(actual.st_ino == expected.st_ino && actual.st_dev == expected.st_dev);
    CHECK(actual.st_size == expected.st_size && actual.st_uid == expected.st_uid);
    struct statx extended;
    CHECK(statx(AT_FDCWD, "/input", 0, STATX_BASIC_STATS, &extended) == 0);
    CHECK(extended.stx_ino == (uint64_t)expected.st_ino && extended.stx_uid == expected.st_uid);
    CHECK(fstat(user_pin, &actual) == 0 && actual.st_ino == expected.st_ino);
}

static uint64_t descriptor_set(void)
{
    uint64_t mask = 0;
    for (unsigned fd = 0; fd < 64; fd++) {
        long flags = syscall(SYS_fcntl, fd, F_GETFD);
        if (flags >= 0) mask |= UINT64_C(1) << fd;
        else CHECK(errno == EBADF);
    }
    return mask;
}

static void *worker(void *unused)
{
    (void)unused;
    check_stat(); /* First identity initialization in this new thread. */
    atomic_fetch_add(&started, 1);
    while (!atomic_load(&stop)) {
        check_stat();
        atomic_fetch_add(&iterations, 1);
    }
    return NULL;
}

int main(int argc, char **argv)
{
    CHECK(argc == 1 || (argc == 2 && !strcmp(argv[1], "after-exec")));
    int (*enabled)(void) = dlsym(RTLD_DEFAULT, "proot_faststat_enabled");
    unsigned long (*hits)(void) = dlsym(RTLD_DEFAULT, "proot_faststat_hits");
    CHECK(enabled && hits && enabled());
    struct rlimit limit;
    CHECK(getrlimit(RLIMIT_NOFILE, &limit) == 0 && limit.rlim_max >= 64);
    limit.rlim_cur = 64;
    CHECK(setrlimit(RLIMIT_NOFILE, &limit) == 0);
    CHECK(syscall(SYS_newfstatat, AT_FDCWD, "/input", &expected, 0) == 0);
    unsigned long initial = hits();
    struct stat info;
    CHECK(stat("/input", &info) == 0 && hits() == initial + 1);
    if (argc == 2) { puts("PASS pin acceleration restored after exec"); return 0; }

    /* User-owned O_PATH descriptors must survive. Closing guessed stat pins
     * would delete this indistinguishable descriptor and fail the test. */
    user_pin = open("/input", O_PATH | O_CLOEXEC);
    CHECK(user_pin >= 0);
    uint64_t descriptors = descriptor_set();
    pthread_t threads[3];
    for (unsigned i = 0; i < 3; i++) CHECK(pthread_create(&threads[i], NULL, worker, NULL) == 0);
    while (atomic_load(&started) != 3) sched_yield();
    unsigned long threaded_hits = hits();
    for (unsigned i = 0; i < 100; i++) {
        /* Alternate libc fork and raw clone, outside pthread_atfork hooks. */
        pid_t child = i & 1 ? fork() : syscall(SYS_clone, SIGCHLD, 0, 0, 0, 0);
        CHECK(child >= 0);
        if (!child) {
            CHECK(descriptor_set() == descriptors);
            check_stat();
            CHECK(descriptor_set() == descriptors);
            CHECK(fcntl(user_pin, F_GETFD) == FD_CLOEXEC);
            _exit(0);
        }
        int status;
        CHECK(waitpid(child, &status, 0) == child && WIFEXITED(status) && WEXITSTATUS(status) == 0);
    }
    atomic_store(&stop, 1);
    for (unsigned i = 0; i < 3; i++) CHECK(pthread_join(threads[i], NULL) == 0);
    CHECK(iterations > 0 && hits() == threaded_hits);
    CHECK(descriptor_set() == descriptors && close(user_pin) == 0);
    pid_t child = fork();
    CHECK(child >= 0);
    if (!child) { execl(argv[0], argv[0], "after-exec", (char *)NULL); CHECK(0); }
    int status;
    CHECK(waitpid(child, &status, 0) == child && WIFEXITED(status) && WEXITSTATUS(status) == 0);
    puts("PASS 100 libc/raw forks: no temporary FD inheritance, user O_PATH retained, thread fallback and exec acceleration");
    return 0;
}
