/* SPDX-License-Identifier: GPL-2.0-or-later */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/resource.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>
#include "protocol.h"

#define CHECK(x) do { if (!(x)) { fprintf(stderr, "FAIL line %d: %s errno=%d\n", __LINE__, #x, errno); exit(1); } } while (0)
static int (*test_statat)(int, const char *, struct stat *, int);
static unsigned long (*hits)(void), (*fallbacks)(void);
static int directory_fd;

static void compare(int fd, const char *path, int flags)
{
    struct stat actual = {0}, expected = {0};
    errno = 123;
    int a = test_statat(fd, path, &actual, flags), ae = errno;
    errno = 123;
    int b = syscall(SYS_newfstatat, fd, path, &expected, flags), be = errno;
    CHECK(a == b && ae == be);
    if (a == 0) {
        CHECK(actual.st_dev == expected.st_dev && actual.st_ino == expected.st_ino);
        CHECK(actual.st_mode == expected.st_mode && actual.st_nlink == expected.st_nlink);
        CHECK(actual.st_uid == expected.st_uid && actual.st_gid == expected.st_gid);
        CHECK(actual.st_size == expected.st_size && actual.st_blocks == expected.st_blocks);
        CHECK(actual.st_rdev == expected.st_rdev && actual.st_blksize == expected.st_blksize);
        CHECK(actual.st_atim.tv_sec == expected.st_atim.tv_sec);
        CHECK(actual.st_atim.tv_nsec == expected.st_atim.tv_nsec);
        CHECK(actual.st_mtim.tv_sec == expected.st_mtim.tv_sec);
        CHECK(actual.st_mtim.tv_nsec == expected.st_mtim.tv_nsec);
        CHECK(actual.st_ctim.tv_sec == expected.st_ctim.tv_sec);
        CHECK(actual.st_ctim.tv_nsec == expected.st_ctim.tv_nsec);
    }
}

static void *thread_test(void *unused)
{
    (void)unused;
    for (int i = 0; i < 200; i++) compare(directory_fd, "file", 0);
    return NULL;
}

static double now(void)
{
    struct timespec t;
    CHECK(clock_gettime(CLOCK_MONOTONIC, &t) == 0);
    return t.tv_sec * 1e3 + t.tv_nsec / 1e6;
}

static long parse_count(const char *text)
{
    long count = 0;
    CHECK(*text);
    for (; *text; text++) {
        CHECK(*text >= '0' && *text <= '9' && count <= 1000000);
        count = count * 10 + (*text - '0');
    }
    CHECK(count <= 10000000);
    return count;
}

int main(int argc, char **argv)
{
    CHECK(argc == 3 || argc == 4);
    int (*enabled)(void) = dlsym(RTLD_DEFAULT, "proot_faststat_enabled");
    int (*mode)(void) = dlsym(RTLD_DEFAULT, "proot_faststat_mode");
    test_statat = dlsym(RTLD_DEFAULT, "fstatat");
    hits = dlsym(RTLD_DEFAULT, "proot_faststat_hits");
    fallbacks = dlsym(RTLD_DEFAULT, "proot_faststat_fallbacks");
    CHECK(enabled && test_statat && hits && fallbacks);
    if (enabled() != atoi(argv[1])) {
        int (*status)(void) = dlsym(RTLD_DEFAULT, "proot_faststat_initialization_status");
        fprintf(stderr, "initialization status=%d\n", status ? status() : -9999);
    }
    CHECK(enabled() == atoi(argv[1]));
    char temporary[] = "faststat-test-XXXXXX";
    CHECK(mkdtemp(temporary));
    int parent = open(".", O_RDONLY | O_DIRECTORY);
    CHECK(parent >= 0 && chdir(temporary) == 0);
    int file = open("file", O_CREAT | O_RDWR | O_EXCL, 0600);
    CHECK(file >= 0 && write(file, "sample", 6) == 6);
    directory_fd = open(".", O_PATH | O_DIRECTORY);
    CHECK(directory_fd >= 0);

    if (strcmp(argv[2], "bench") == 0 || strcmp(argv[2], "bench-cwd") == 0) {
        CHECK(argc == 4);
        long count = parse_count(argv[3]);
        CHECK(count >= 0 && count <= 10000000);
        struct stat result;
        int bench_fd = strcmp(argv[2], "bench-cwd") == 0 ? AT_FDCWD : directory_fd;
        for (int i = 0; i < 100; i++) CHECK(test_statat(bench_fd, "file", &result, 0) == 0);
        unsigned long start_hits = hits(), start_fallbacks = fallbacks();
        double start = now();
        for (long i = 0; i < count; i++) CHECK(test_statat(bench_fd, "file", &result, 0) == 0);
        double elapsed = now() - start;
        printf("{\"enabled\":%d,\"iterations\":%ld,\"ms\":%.3f,\"hits\":%lu,\"fallbacks\":%lu}\n",
               enabled(), count, elapsed, hits() - start_hits, fallbacks() - start_fallbacks);
        CHECK(hits() - start_hits == (enabled() ? (unsigned long)count : 0));
    } else {
        CHECK(strcmp(argv[2], "test") == 0);
        int directory = open(".", O_PATH | O_DIRECTORY);
        CHECK(directory >= 0);
        compare(directory, "file", 0);
        compare(AT_FDCWD, "file", AT_SYMLINK_NOFOLLOW);
        compare(file, "", AT_EMPTY_PATH);
        compare(-123, "file", 0);
        compare(AT_FDCWD, "file/", 0);
        compare(AT_FDCWD, "missing", 0);
        compare(AT_FDCWD, "file", 0x40000000);
        compare(AT_FDCWD, "", 0);
        compare(AT_FDCWD, NULL, 0);
        compare(AT_FDCWD, (const char *)1, 0);
        if (mode && mode() == PROOT_FASTSTAT_CONTEXT_VERSION) {
            errno = 0;
            CHECK(syscall(SYS_prctl, PROOT_FASTSTAT_RESOLVE, 1, sizeof(struct proot_faststat_context), 2, 0) == -1 && errno == EFAULT);
            errno = 0;
            CHECK(syscall(SYS_prctl, PROOT_FASTSTAT_RESOLVE, 1, 1, 2, 0) == -1 && errno == EOPNOTSUPP);
            struct proot_faststat_context malformed;
            memset(&malformed, 'x', sizeof(malformed));
            malformed.dirfd = directory;
            malformed.flags = 0;
            errno = 0;
            CHECK(syscall(SYS_prctl, PROOT_FASTSTAT_RESOLVE, &malformed, sizeof(malformed), 2, 0) == -1 && errno == EOPNOTSUPP);
        }
        struct stat *invalid = (struct stat *)1;
        errno = 0;
        CHECK(test_statat(directory, "file", invalid, 0) == -1 && errno == EFAULT);
        CHECK(link("file", "hard") == 0);
        compare(directory, "hard", 0);
        CHECK(symlink("file", "relative") == 0);
        CHECK(symlink("missing", "dangling") == 0);
        char absolute[PATH_MAX], cwd[PATH_MAX];
        CHECK(getcwd(cwd, sizeof(cwd)));
        CHECK(snprintf(absolute, sizeof(absolute), "%s/file", cwd) < PATH_MAX);
        CHECK(symlink(absolute, "absolute") == 0);
        compare(directory, "relative", 0);
        compare(directory, "relative", AT_SYMLINK_NOFOLLOW);
        compare(directory, "dangling", 0);
        compare(directory, "dangling", AT_SYMLINK_NOFOLLOW);
        compare(directory, "absolute", 0);
        compare(directory, absolute, 0);
        compare(directory, "../.", 0);
        CHECK(mkdir("nested", 0700) == 0);
        CHECK(symlink("..", "nested/up") == 0);
        compare(directory, "nested/up/file", 0);
        const char *bound_path = getenv("FASTSTAT_TEST_BIND");
        if (bound_path) {
            compare(AT_FDCWD, bound_path, 0);
            compare(parent, "alias/file", 0);
            CHECK(symlink(bound_path, "cross-bind") == 0);
            compare(directory, "cross-bind", 0);
            compare(directory, "cross-bind", AT_SYMLINK_NOFOLLOW);
            CHECK(unlink("cross-bind") == 0);
            compare(AT_FDCWD, "/proc/self/status", 0);
            compare(AT_FDCWD, "/dev/null", 0);
        }
        int nested_file = open("nested/file", O_CREAT | O_WRONLY | O_EXCL, 0600);
        CHECK(nested_file >= 0 && write(nested_file, "different-directory", 19) == 19);
        CHECK(close(nested_file) == 0 && chdir("nested") == 0);
        compare(AT_FDCWD, "file", 0);
        compare(directory, "file", 0);
        CHECK(fchdir(directory) == 0);
        compare(AT_FDCWD, "file", 0);
        CHECK(rename("file", "renamed") == 0);
        compare(directory, "renamed", 0);
        compare(directory, "relative", 0);
        CHECK(rename("renamed", "file") == 0);

        /* Readable string ending at a page boundary; following page faults. */
        long page = sysconf(_SC_PAGESIZE);
        char *memory = mmap(NULL, (size_t)page * 2, PROT_READ | PROT_WRITE,
                            MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
        CHECK(memory != MAP_FAILED && mprotect(memory + page, page, PROT_NONE) == 0);
        memcpy(memory + page - 5, "file", 5);
        compare(directory, memory + page - 5, 0);
        CHECK(munmap(memory, (size_t)page * 2) == 0);

        pthread_t threads[4];
        for (int i = 0; i < 4; i++) CHECK(pthread_create(&threads[i], NULL, thread_test, NULL) == 0);
        for (int i = 0; i < 4; i++) CHECK(pthread_join(threads[i], NULL) == 0);
        pid_t child = fork();
        CHECK(child >= 0);
        if (child == 0) { compare(directory, "file", 0); _exit(0); }
        int child_status;
        CHECK(waitpid(child, &child_status, 0) == child);
        CHECK(WIFEXITED(child_status) && WEXITSTATUS(child_status) == 0);

        if (mode && mode() == 2 && getuid() == 0) {
            child = fork();
            CHECK(child >= 0);
            if (child == 0) {
                /* Raw syscalls deliberately bypass libc UID wrappers. */
                CHECK(syscall(SYS_setresgid, 1201, 1202, 1203) == 0);
                CHECK(syscall(SYS_setresuid, 1301, 1302, 1303) == 0);
                /* Ordinary file avoids l2s fallback masking stale identity. */
                unsigned long before = hits();
                compare(directory, "nested/file", 0);
                CHECK(!enabled() || hits() > before);
                compare(AT_FDCWD, "nested/file", 0);
                _exit(0);
            }
            CHECK(waitpid(child, &child_status, 0) == child);
            CHECK(WIFEXITED(child_status) && WEXITSTATUS(child_status) == 0);
            compare(directory, "nested/file", 0);
        }

        child = fork();
        CHECK(child >= 0);
        if (child == 0) {
            execl(argv[0], argv[0], argv[1], "bench", "0", (char *)NULL);
            _exit(99);
        }
        CHECK(waitpid(child, &child_status, 0) == child);
        CHECK(WIFEXITED(child_status) && WEXITSTATUS(child_status) == 0);

        /* An inherited/additional kernel restriction must outrank our ALLOW
         * site. Probe in a child because seccomp filters cannot be removed. */
        child = fork();
        CHECK(child >= 0);
        if (child == 0) {
            struct sock_filter filter[] = {
                BPF_STMT(BPF_LD | BPF_W | BPF_ABS, 0),
                BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_openat2, 0, 1),
                BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EPERM),
                BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
            };
            struct sock_fprog program = { .len = 4, .filter = filter };
            CHECK(prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) == 0);
            CHECK(prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &program) == 0);
            unsigned long before = hits();
            compare(directory, "file", 0);
            CHECK(hits() == before);
            _exit(0);
        }
        CHECK(waitpid(child, &child_status, 0) == child);
        CHECK(WIFEXITED(child_status) && WEXITSTATUS(child_status) == 0);

        /* O_PATH needs an extra FD; exhaustion must still fall back to stat. */
        struct rlimit limit, reduced;
        CHECK(getrlimit(RLIMIT_NOFILE, &limit) == 0);
        reduced = limit;
        if (reduced.rlim_cur > 64) reduced.rlim_cur = 64;
        CHECK(setrlimit(RLIMIT_NOFILE, &reduced) == 0);
        int descriptors[64], n = 0, fd;
        while (n < 64 && (fd = open("file", O_RDONLY)) >= 0) descriptors[n++] = fd;
        CHECK(n < 64 && errno == EMFILE);
        compare(directory, "file", 0);
        for (int i = 0; i < n; i++) CHECK(close(descriptors[i]) == 0);
        CHECK(setrlimit(RLIMIT_NOFILE, &limit) == 0);

        CHECK(unlink("hard") == 0 && unlink("relative") == 0);
        CHECK(unlink("absolute") == 0 && unlink("dangling") == 0);
        CHECK(unlink("nested/up") == 0 && unlink("nested/file") == 0 && rmdir("nested") == 0);
        CHECK(close(directory) == 0);
        printf("PASS enabled=%d hits=%lu fallbacks=%lu\n", enabled(), hits(), fallbacks());
        CHECK(!enabled() || hits() > 0);
    }
    CHECK(unlink("file") == 0 && close(file) == 0);
    CHECK(close(directory_fd) == 0);
    CHECK(fchdir(parent) == 0 && close(parent) == 0 && rmdir(temporary) == 0);
    return 0;
}
