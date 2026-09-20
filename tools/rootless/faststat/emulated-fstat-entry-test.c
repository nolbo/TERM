/* SPDX-License-Identifier: GPL-2.0-or-later */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#define CHECK(x) do { if (!(x)) { fprintf(stderr, "FAIL %d: %s errno=%d\n", __LINE__, #x, errno); exit(1); } } while (0)
static int query(int fd, void *out) { return syscall(SYS_fstat, fd, out); }
static _Atomic int done;
static int first, second, target;
static void *swap_fd(void *unused) {
    (void)unused;
    while (!atomic_load(&done)) {
        CHECK(dup3(first, target, 0) == target);
        CHECK(dup3(second, target, 0) == target);
    }
    return NULL;
}

int main(int argc, char **argv) {
    alarm(30);
    char directory[] = "/tmp/emulated-fstat-XXXXXX";
    CHECK(mkdtemp(directory) && chdir(directory) == 0);
    first = open("a", O_CREAT | O_RDWR | O_EXCL, 0640);
    CHECK(first >= 0 && write(first, "aaaa", 4) == 4);
    struct stat a, b, result;
    CHECK(query(first, &a) == 0 && a.st_size == 4 && a.st_nlink == 1 && a.st_uid == 0);
    if (argc == 2 && !strcmp(argv[1], "bench")) {
        struct timespec start, end;
        CHECK(clock_gettime(CLOCK_MONOTONIC, &start) == 0);
        for (int n = 0; n < 10000; n++)
            CHECK(query(first, &result) == 0 && result.st_size == 4 && result.st_nlink == 1);
        CHECK(clock_gettime(CLOCK_MONOTONIC, &end) == 0);
        printf("raw_fstat_ns=%lld count=10000\n", (long long)(end.tv_sec-start.tv_sec)*1000000000LL+end.tv_nsec-start.tv_nsec);
    } else {
        CHECK(query(-1, &result) == -1 && errno == EBADF);
        CHECK(query(first, (void *)1) == -1 && errno == EFAULT);
        long page = sysconf(_SC_PAGESIZE);
        void *memory = mmap(NULL, page * 2, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
        CHECK(memory != MAP_FAILED && mprotect((char *)memory + page, page, PROT_NONE) == 0);
        CHECK(query(first, (char *)memory + page - sizeof(result)/2) == -1 && errno == EFAULT);
        CHECK(mprotect(memory, page, PROT_READ) == 0);
        CHECK(query(first, memory) == -1 && errno == EFAULT);
        CHECK(munmap(memory, page * 2) == 0);
        CHECK(symlink("a", "symlink") == 0);
        int symbolic = open("symlink", O_PATH | O_NOFOLLOW);
        CHECK(symbolic >= 0 && query(symbolic, &result) == 0 && S_ISLNK(result.st_mode));
        CHECK(close(symbolic) == 0 && unlink("symlink") == 0);
        int pipes[2]; CHECK(pipe(pipes) == 0);
        CHECK(query(pipes[0], &result) == 0 && S_ISFIFO(result.st_mode));
        CHECK(close(pipes[0]) == 0 && close(pipes[1]) == 0);
        int proc = open("/proc/self/status", O_RDONLY);
        CHECK(proc >= 0 && query(proc, &result) == 0 && S_ISREG(result.st_mode));
        CHECK(close(proc) == 0);
        second = open("b", O_CREAT | O_RDWR | O_EXCL, 0600);
        CHECK(second >= 0 && write(second, "bbbbbbbb", 8) == 8 && query(second, &b) == 0);
        target = dup(first); CHECK(target >= 0);
        pthread_t thread;
        CHECK(pthread_create(&thread, NULL, swap_fd, NULL) == 0);
        for (int n = 0; n < 1000; n++) {
            CHECK(query(target, &result) == 0);
            CHECK((result.st_ino == a.st_ino && result.st_size == a.st_size) ||
                  (result.st_ino == b.st_ino && result.st_size == b.st_size));
            CHECK(result.st_nlink == 1);
        }
        atomic_store(&done, 1);
        CHECK(pthread_join(thread, NULL) == 0);
        CHECK(close(target) == 0 && close(second) == 0 && unlink("b") == 0);
        CHECK(link("a", "alias") == 0);
        CHECK(query(first, &result) == 0 && result.st_nlink == 2 && result.st_ino == a.st_ino);
        CHECK(link("alias", "third") == 0);
        CHECK(query(first, &result) == 0 && result.st_nlink == 3);
        CHECK(unlink("alias") == 0 && rename("third", "alias") == 0);
        CHECK(query(first, &result) == 0 && result.st_nlink == 2);
        CHECK(unlink("alias") == 0 && unlink("a") == 0);
        CHECK(query(first, &result) == 0 && result.st_nlink == 0 && result.st_size == 4);
        pid_t child = fork(); CHECK(child >= 0);
        if (!child) {
            struct sock_filter filter[] = {
                BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
                BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_fstat, 0, 1),
                BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EPERM),
                BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
            };
            struct sock_fprog program = {4, filter};
            CHECK(prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) == 0);
            CHECK(prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &program) == 0);
            CHECK(query(first, &result) == -1 && errno == EPERM);
            _exit(0);
        }
        int status;
        CHECK(waitpid(child, &status, 0) == child && status == 0);
        puts("PASS raw fstat: FD replacement, emulated links, memory faults, pseudo files, seccomp");
    }
    CHECK(close(first) == 0);
    if (argc == 2) CHECK(unlink("a") == 0);
    CHECK(chdir("/") == 0 && rmdir(directory) == 0);
    return 0;
}
