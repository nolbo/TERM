/* SPDX-License-Identifier: GPL-2.0-or-later */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>
#define CHECK(test) do { if (!(test)) { fprintf(stderr, "FAIL %d: %s errno=%d\n", __LINE__, #test, errno); exit(1); } } while (0)
int main(void)
{
    alarm(30);
    char name[] = "/tmp/close-fallback-XXXXXX";
    int fd = mkstemp(name);
    CHECK(fd >= 0 && unlink(name) == 0);
    struct stat st;
    CHECK(fstat(fd, &st) == 0);
    unsigned long (*hits)(void) = dlsym(RTLD_DEFAULT, "proot_fastfd_hits");
    CHECK(hits && hits() > 0);
    for (int control = 1; control >= 0; control--) {
        pid_t child = fork();
        CHECK(child >= 0);
        if (!child) {
            /* Initialize the child before forbidding further signal masking. */
            CHECK(fstat(fd, &st) == 0);
            struct sock_filter filter[] = {
                BPF_STMT(BPF_LD | BPF_W | BPF_ABS, 0),
                BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_rt_sigprocmask, 0, 1),
                BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_KILL_PROCESS),
                BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW)
            };
            struct sock_fprog program = { .len = 4, .filter = filter };
            CHECK(prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) == 0);
            CHECK(prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &program) == 0);
            if (control) {
                unsigned long empty_mask = 0;
                syscall(SYS_rt_sigprocmask, SIG_BLOCK, &empty_mask, NULL, sizeof(empty_mask));
                _exit(99);
            }
            CHECK(close(fd) == 0);
            CHECK(syscall(SYS_fstat, fd, &st) == -1 && errno == EBADF);
            _exit(0);
        }
        int status;
        CHECK(waitpid(child, &status, 0) == child);
        if (control) CHECK(WIFSIGNALED(status) && WTERMSIG(status) == SIGSYS);
        else CHECK(WIFEXITED(status) && WEXITSTATUS(status) == 0);
    }
    CHECK(close(fd) == 0);
    puts("PASS close fallback: verified filter, shared ancestry closes without signal-mask syscalls");
    return 0;
}
