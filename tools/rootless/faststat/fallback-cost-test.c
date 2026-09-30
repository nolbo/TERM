/* SPDX-License-Identifier: GPL-2.0-or-later */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>

#define CHECK(x) do { if (!(x)) { dprintf(2, "FAIL fallback-cost line %d: %s errno=%d\n", __LINE__, #x, errno); _exit(1); } } while (0)
static unsigned long (*probe)(long);
static unsigned long (*hits)(void);
static struct stat expected;

static void paths(void)
{
    struct stat info;
    CHECK(stat("/input", &info) == 0);
    CHECK(info.st_ino == expected.st_ino && info.st_size == expected.st_size &&
          info.st_uid == expected.st_uid && info.st_gid == expected.st_gid);
    CHECK(lstat("/input", &info) == 0 && info.st_ino == expected.st_ino);
    struct statx extended;
    CHECK(statx(AT_FDCWD, "/input", 0, STATX_BASIC_STATS, &extended) == 0);
    CHECK(extended.stx_ino == (uint64_t)expected.st_ino && extended.stx_uid == expected.st_uid);
    int fd = open("/input", O_RDONLY | O_CLOEXEC);
    CHECK(fd >= 0);
    char content[8];
    CHECK(read(fd, content, sizeof(content)) == expected.st_size);
    CHECK(syscall(SYS_close, fd) == 0);
    errno = 0;
    CHECK(stat("/missing-fallback-cost", &info) == -1 && errno == ENOENT);
    char *invalid = (char *)(uintptr_t)1;
    errno = 0;
    CHECK(stat(invalid, &info) == -1 && errno == EFAULT);
}

static void check_no_attempt(const char *label)
{
    unsigned long masks = probe(SYS_rt_sigprocmask), pids = probe(SYS_getpid), initial = hits();
    for (int i = 0; i < 20; i++) paths();
    unsigned long mask_delta = probe(SYS_rt_sigprocmask) - masks;
    unsigned long pid_delta = probe(SYS_getpid) - pids;
    dprintf(1, "%s dispatcher helpers: masks=%lu getpid=%lu\n", label, mask_delta, pid_delta);
    CHECK(mask_delta == 0 && pid_delta == 0 && hits() == initial);
}

static void *worker(void *unused)
{
    (void)unused;
    paths(); /* First query explicitly denies a new thread's identity pin. */
    check_no_attempt("new-thread");
    return NULL;
}

int main(void)
{
    probe = dlsym(RTLD_DEFAULT, "proot_faststat_probe_count");
    hits = dlsym(RTLD_DEFAULT, "proot_faststat_hits");
    CHECK(probe && hits);
    CHECK(syscall(SYS_newfstatat, AT_FDCWD, "/input", &expected, 0) == 0);
    struct stat info;
    unsigned long initial = hits();
    CHECK(stat("/input", &info) == 0 && hits() == initial + 1);
    pthread_t thread;
    CHECK(pthread_create(&thread, NULL, worker, NULL) == 0);
    CHECK(pthread_join(thread, NULL) == 0);
    paths(); /* Context-only dispatch learns its denial on this first call. */
    check_no_attempt("existing-page");
    pid_t child = fork();
    CHECK(child >= 0);
    if (!child) { check_no_attempt("fork-child"); _exit(0); }
    int status;
    CHECK(waitpid(child, &status, 0) == child && WIFEXITED(status) && WEXITSTATUS(status) == 0);
    puts("PASS disabled path dispatch: correct metadata/content/ENOENT/EFAULT, zero mask/PID helpers");
    return 0;
}
