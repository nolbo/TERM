/* SPDX-License-Identifier: Apache-2.0 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/auxv.h>
#include <sys/mman.h>
#include <sys/wait.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#define CHECK(x) do { if (!(x)) { fprintf(stderr, "PROCESS FAIL %d errno=%d\n", __LINE__, errno); return 1; } } while (0)
static _Thread_local unsigned long value = 1234;
static volatile sig_atomic_t delivered;
static void caught(int sig) {
    struct stat st;
    if (sig == SIGUSR1 && stat("/input", &st) == 0 && st.st_size == 8) delivered++;
}

int main(int argc, char **argv)
{
    if (argc == 2 && !strcmp(argv[1], "shell-benchmark")) {
        struct timespec before, after;
        CHECK(clock_gettime(CLOCK_MONOTONIC, &before) == 0);
        pid_t child = fork(); CHECK(child >= 0);
        if (!child) {
            char *args[] = {"/bin/sh", "-c", "i=0; while [ \"$i\" -lt 30 ]; do /bin/true || exit; i=$((i+1)); done; printf 'PASS shell-exec\\n'", NULL};
            execve(args[0], args, environ); _exit(99);
        }
        int status;
        CHECK(waitpid(child, &status, 0) == child && WIFEXITED(status) && WEXITSTATUS(status) == 0);
        CHECK(clock_gettime(CLOCK_MONOTONIC, &after) == 0);
        double ms = (after.tv_sec - before.tv_sec) * 1000.0 + (after.tv_nsec - before.tv_nsec) / 1000000.0;
        printf("BENCH {\"case\":\"shell-exec-30\",\"count\":30,\"elapsed_ms\":%.6f}\n", ms);
        return 0;
    }
    if (argc >= 2 && !strcmp(argv[1], "after")) {
        CHECK(argc == 4 && !strcmp(argv[0], "custom argv zero") && !strcmp(argv[2], "space argument"));
        CHECK(getpid() == atoi(argv[3]) && value == 1234);
        CHECK(getenv("DL_TEST") && !strcmp(getenv("DL_TEST"), "value with spaces"));
        CHECK(getenv("PATH") == NULL);
        CHECK(!strcmp((char *)getauxval(AT_EXECFN), "../bin/process-dynamic"));
        char cwd[64], content[8];
        CHECK(getcwd(cwd, sizeof(cwd)) && !strcmp(cwd, "/tmp"));
        CHECK(fcntl(180, F_GETFD) == -1 && errno == EBADF);
        CHECK(read(181, content, 8) == 8 && !memcmp(content, "fixture\n", 8));
        struct sigaction action;
        CHECK(sigaction(SIGUSR1, NULL, &action) == 0 && action.sa_handler == SIG_DFL);
        CHECK(sigaction(SIGUSR2, NULL, &action) == 0 && action.sa_handler == SIG_IGN);
        sigset_t mask;
        CHECK(sigprocmask(SIG_SETMASK, NULL, &mask) == 0 && sigismember(&mask, SIGUSR1) == 1);
        CHECK(sigpending(&mask) == 0 && sigismember(&mask, SIGUSR1) == 1);
        ssize_t path_size = readlink("/proc/self/exe", cwd, sizeof(cwd));
        CHECK(path_size == (ssize_t)strlen("/bin/process-dynamic") && !memcmp(cwd, "/bin/process-dynamic", path_size));
        unsigned char residency;
        CHECK(mincore((void *)0x620000000000UL, 4096, &residency) == -1 && errno == ENOMEM);
        puts("PASS exec PID/argv/env/cwd/CLOEXEC/signals/mapping/TLS"); return 0;
    }
    if (argc == 3 && !strcmp(argv[1], "repeat")) {
        int remaining = atoi(argv[2]);
        CHECK(value == 1234); value = 999;
        // Exec's private source descriptors must close after mapping, including
        // every iteration of repeated static/dynamic image replacement.
        for (int fd = 3; fd < 256; fd++) CHECK(fcntl(fd, F_GETFD) == -1 && errno == EBADF);
        // A deterministic mapping must be available again after every exec.
        CHECK(mmap((void *)0x620000000000UL, 4096, PROT_READ | PROT_WRITE,
            MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED_NOREPLACE, -1, 0) == (void *)0x620000000000UL);
        if (remaining) {
            char next[16]; snprintf(next, sizeof(next), "%d", remaining - 1);
            char *args[] = {argv[0], "repeat", next, NULL};
            char *env[] = {NULL};
            execve(argv[0], args, env); CHECK(0);
        }
        puts("PASS repeated exec memory/TLS cleanup"); return 0;
    }
    CHECK(argc == 2 && !strcmp(argv[1], "processes"));
    int pipes[2], status;
    CHECK(pipe2(pipes, O_CLOEXEC) == 0);
    pid_t child = fork(); CHECK(child >= 0);
    if (!child) {
        value = 77; close(pipes[0]);
        char *args[] = {"/bin/printf", "%s", "pipe-child", NULL};
        char *env[] = {NULL};
        CHECK(dup2(pipes[1], STDOUT_FILENO) == STDOUT_FILENO);
        close(pipes[1]); execve(args[0], args, env); _exit(90);
    }
    CHECK(close(pipes[1]) == 0);
    char output[64] = {0}; ssize_t n = read(pipes[0], output, sizeof(output));
    CHECK(n == 10 && !memcmp(output, "pipe-child", 10));
    CHECK(read(pipes[0], output, sizeof(output)) == 0 && close(pipes[0]) == 0);
    CHECK(waitpid(child, &status, 0) == child && WIFEXITED(status) && WEXITSTATUS(status) == 0);
    CHECK(value == 1234);
    child = fork(); CHECK(child >= 0);
    if (!child) { raise(SIGSTOP); _exit(91); }
    CHECK(waitpid(child, &status, WUNTRACED) == child && WIFSTOPPED(status));
    CHECK(waitpid(child, &status, WNOHANG) == 0);
    CHECK(kill(child, SIGKILL) == 0);
    CHECK(waitpid(child, &status, 0) == child && WIFSIGNALED(status) && WTERMSIG(status) == SIGKILL);
    struct timespec before, after;
    CHECK(clock_gettime(CLOCK_MONOTONIC, &before) == 0);
    child = vfork(); CHECK(child >= 0);
    if (!child) {
        struct timespec delay = {.tv_nsec = 100000000};
        nanosleep(&delay, NULL); _exit(37);
    }
    CHECK(clock_gettime(CLOCK_MONOTONIC, &after) == 0);
    CHECK((after.tv_sec - before.tv_sec) * 1000000000L + after.tv_nsec - before.tv_nsec >= 80000000L);
    CHECK(waitpid(child, &status, 0) == child && WIFEXITED(status) && WEXITSTATUS(status) == 37);
    child = vfork(); CHECK(child >= 0);
    if (!child) {
        char *missing[] = {"/missing-program", NULL};
        execve(missing[0], missing, missing + 1); _exit(errno == ENOENT ? 39 : 98);
    }
    CHECK(waitpid(child, &status, 0) == child && WIFEXITED(status) && WEXITSTATUS(status) == 39);
    struct sigaction handler = {.sa_handler = caught}; sigemptyset(&handler.sa_mask);
    CHECK(sigaction(SIGUSR1, &handler, NULL) == 0);
    sigset_t blocked, oldmask;
    sigemptyset(&blocked); sigaddset(&blocked, SIGUSR1);
    CHECK(sigprocmask(SIG_BLOCK, &blocked, &oldmask) == 0);
    child = fork(); CHECK(child >= 0);
    if (!child) { kill(getppid(), SIGUSR1); _exit(0); }
    sigdelset(&oldmask, SIGUSR1);
    CHECK(sigsuspend(&oldmask) == -1 && errno == EINTR && delivered == 1);
    CHECK(waitpid(child, &status, 0) == child && WIFEXITED(status) && WEXITSTATUS(status) == 0);
    int fd = open("/input", O_RDONLY | O_CLOEXEC); CHECK(fd >= 0);
    CHECK(dup3(fd, 180, O_CLOEXEC) == 180 && dup2(fd, 181) == 181 && close(fd) == 0);
    char *args[] = {"true", NULL}, *env[] = {NULL};
    CHECK(execve("/missing-program", args, env) == -1 && errno == ENOENT);
    CHECK(execve("/bad/wrong-machine", args, env) == -1 && errno == ENOEXEC);
    CHECK(execve("/bin/true", (void *)1, env) == -1 && errno == EFAULT);
    char *invalid[] = {(void *)1, NULL};
    CHECK(execve("/bin/true", args, invalid) == -1 && errno == EFAULT);
    char *excess[130]; for (int i = 0; i < 129; i++) excess[i] = "a"; excess[129] = NULL;
    CHECK(execve("/bin/true", excess, env) == -1 && errno == E2BIG);
    CHECK(execve("/script-loop", args, env) == -1 && errno == ELOOP);
    CHECK(execve("/no-execute", args, env) == -1 && errno == EACCES);
    CHECK(fcntl(180, F_GETFD) == FD_CLOEXEC && value == 1234);
    CHECK(mmap((void *)0x620000000000UL, 4096, PROT_READ | PROT_WRITE,
        MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED_NOREPLACE, -1, 0) == (void *)0x620000000000UL);
    value = 4321;
    struct sigaction action = {.sa_handler = caught}; sigemptyset(&action.sa_mask);
    CHECK(sigaction(SIGUSR1, &action, NULL) == 0);
    action.sa_handler = SIG_IGN; CHECK(sigaction(SIGUSR2, &action, NULL) == 0);
    sigset_t mask; sigemptyset(&mask); sigaddset(&mask, SIGUSR1);
    CHECK(sigprocmask(SIG_BLOCK, &mask, NULL) == 0 && chdir("/tmp") == 0);
    CHECK(raise(SIGUSR1) == 0);
    char pid[32]; snprintf(pid, sizeof(pid), "%ld", (long)getpid());
    char *final[] = {"custom argv zero", "after", "space argument", pid, NULL};
    char *environment[] = {"DL_TEST=value with spaces", NULL};
    execve("../bin/process-dynamic", final, environment);
    CHECK(0);
}
