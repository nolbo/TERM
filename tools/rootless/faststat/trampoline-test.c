/* SPDX-License-Identifier: GPL-2.0-or-later */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>
#include "protocol.h"

#define CHECK(x) do { if (!(x)) { fprintf(stderr, "FAIL line %d: %s errno=%d\n", __LINE__, #x, errno); exit(1); } } while (0)

static long private_call(long number, long first, long second)
{
    register long x0 __asm__("x0") = first;
    register long x1 __asm__("x1") = second;
    register long x8 __asm__("x8") = number;
    register uintptr_t x16 __asm__("x16") = PROOT_FASTSTAT_ADDRESS;
    __asm__ volatile("blr x16" : "+r"(x0), "+r"(x1), "+r"(x8), "+r"(x16)
        : : "x2", "x3", "x4", "x5", "x6", "x7", "x9", "x10", "x11",
            "x12", "x13", "x14", "x15", "x17", "x30", "cc", "memory");
    return x0;
}

int main(void)
{
    alarm(20);
    int (*enabled)(void) = dlsym(RTLD_DEFAULT, "proot_faststat_enabled");
    CHECK(enabled && enabled() == 1);
    char path[] = "/tmp/terminal-trampoline-XXXXXX";
    int fd = mkstemp(path);
    CHECK(fd >= 0);
    struct proot_faststat_context context = { .dirfd = AT_FDCWD, .result_errno = -1 };
    strcpy(context.path, path);
    CHECK(syscall(SYS_prctl, PROOT_FASTSTAT_RESOLVE, &context, sizeof(context),
                  PROOT_FASTSTAT_CONTEXT_VERSION, 0) == 0);
    CHECK(context.result_errno == 0 && context.fake_root);
    CHECK(context.host_uid != context.guest_uid);
    CHECK(unlink(path) == 0);  /* No leftover fixture even on a failed assertion. */
    struct stat result;
    CHECK(syscall(SYS_fstat, fd, &result) == 0);
    CHECK(result.st_uid == context.guest_uid && result.st_gid == context.guest_gid);
    CHECK(private_call(SYS_getuid, 0, 0) == context.guest_uid);
    for (int i = 0; i < 128; i++) {
        CHECK(ftruncate(fd, i) == 0);
        CHECK(private_call(SYS_fstat, fd, (long)&result) == 0);
        CHECK(result.st_uid == context.host_uid && result.st_gid == context.host_gid);
        CHECK(result.st_size == i && result.st_nlink == 0);
    }
    pid_t child = fork();
    CHECK(child >= 0);
    if (!child) {
        struct sock_filter filter[] = {
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS, 0),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_fstat, 0, 1),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EPERM),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
        };
        struct sock_fprog program = { .len = sizeof(filter) / sizeof(filter[0]), .filter = filter };
        CHECK(prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) == 0);
        CHECK(prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &program) == 0);
        CHECK(private_call(SYS_fstat, fd, (long)&result) == -EPERM);
        CHECK(syscall(SYS_fstat, fd, &result) == -1 && errno == EPERM);
        _exit(0);
    }
    int status;
    CHECK(waitpid(child, &status, 0) == child && WIFEXITED(status) && WEXITSTATUS(status) == 0);
    CHECK(close(fd) == 0);
    puts("PASS: private fstat raw metadata, ordinary fstat/getuid mapping, fresh inode state and guest seccomp");
    return 0;
}
