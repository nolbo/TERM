/* SPDX-License-Identifier: Apache-2.0 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <signal.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

#define CHECK(x) do { if (!(x)) { dprintf(2, "FAIL concurrency line %d: %s errno=%d\n", __LINE__, #x, errno); _exit(1); } } while (0)
static __thread int local = 73;
static atomic_int started, iterations;
static atomic_int tids[2];
static int keep, drop;
static pid_t original_pid;
static const char *program;
static uintptr_t old_mapping = 0x3000000000UL;
static void caught(int signal) { (void)signal; }

static void *blocked(void *argument)
{
    int index = (int)(uintptr_t)argument;
    atomic_store(&tids[index], (int)syscall(SYS_gettid));
    atomic_fetch_add(&started, 1);
    if (!index) {
        int fd = open("/tmp/thread-fifo", O_RDONLY); CHECK(fd >= 0); close(fd);
    } else for (;;) {
        struct stat st; CHECK(stat("/input", &st) == 0);
        void *p = mmap(NULL, 4096, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
        CHECK(p != MAP_FAILED); *(volatile int *)p = 9; CHECK(munmap(p, 4096) == 0);
        atomic_fetch_add(&iterations, 1);
    }
    return NULL;
}
static _Noreturn void replace(void)
{
    CHECK(local == 73); local = 99;
    struct sigaction action = {.sa_handler = caught}; sigemptyset(&action.sa_mask);
    CHECK(sigaction(SIGUSR1, &action, NULL) == 0);
    action.sa_handler = SIG_IGN; CHECK(sigaction(SIGUSR2, &action, NULL) == 0);
    char *missing[] = {"/no-such-concurrency-program", NULL};
    int before = atomic_load(&iterations);
    execve(missing[0], missing, missing + 1); CHECK(errno == ENOENT);
    CHECK(sigaction(SIGUSR1, NULL, &action) == 0 && action.sa_handler == caught);
    while (atomic_load(&iterations) == before) sched_yield();
    CHECK(chdir("/tmp") == 0);
    sigset_t mask; sigemptyset(&mask); sigaddset(&mask, SIGUSR1); sigaddset(&mask, SIGSYS);
    CHECK(pthread_sigmask(SIG_SETMASK, &mask, NULL) == 0);
    char pid_text[24], keep_text[24], drop_text[24], tid0[24], tid1[24], mapping[24];
    snprintf(pid_text, sizeof(pid_text), "%d", original_pid);
    snprintf(keep_text, sizeof(keep_text), "%d", keep); snprintf(drop_text, sizeof(drop_text), "%d", drop);
    snprintf(tid0, sizeof(tid0), "%d", atomic_load(&tids[0])); snprintf(tid1, sizeof(tid1), "%d", atomic_load(&tids[1]));
    snprintf(mapping, sizeof(mapping), "%lu", (unsigned long)old_mapping);
    char *arguments[] = {(char *)program, "after", pid_text, keep_text, drop_text, tid0, tid1, mapping, NULL};
    char *environment[] = {"CONCURRENCY_EXEC=preserved", "PATH=/usr/bin:/bin", NULL};
    execve(program, arguments, environment); CHECK(0); __builtin_unreachable();
}
static void *replace_worker(void *unused)
{
    (void)unused; usleep(30000); replace();
}
static void after_exec(int argc, char **argv)
{
    CHECK(argc == 8 && getpid() == atoi(argv[2]) && syscall(SYS_gettid) == getpid());
    old_mapping = strtoul(argv[7], NULL, 10);
    CHECK(local == 73 && getenv("CONCURRENCY_EXEC") && !strcmp(getenv("CONCURRENCY_EXEC"), "preserved"));
    for (int i = 5; i < 7; i++) CHECK(syscall(SYS_tgkill, getpid(), atoi(argv[i]), 0) == -1 && errno == ESRCH);
    keep = atoi(argv[3]); drop = atoi(argv[4]);
    CHECK(fcntl(drop, F_GETFD) == -1 && errno == EBADF);
    char content[8]; CHECK(read(keep, content, sizeof(content)) == 7 && !memcmp(content, "ixture\n", 7));
    CHECK(close(keep) == 0);
    char cwd[32]; CHECK(getcwd(cwd, sizeof(cwd)) && !strcmp(cwd, "/tmp"));
    sigset_t mask; CHECK(pthread_sigmask(SIG_SETMASK, NULL, &mask) == 0);
    CHECK(sigismember(&mask, SIGUSR1) == 1 && sigismember(&mask, SIGSYS) == 1);
    struct sigaction action;
    CHECK(sigaction(SIGUSR1, NULL, &action) == 0 && action.sa_handler == SIG_DFL);
    CHECK(sigaction(SIGUSR2, NULL, &action) == 0 && action.sa_handler == SIG_IGN);
    unsigned char residency; CHECK(mincore((void *)old_mapping, 4096, &residency) == -1 && errno == ENOMEM);
    puts("PASS concurrent exec PID/TID, sibling destruction, precommit failure, FD/CLOEXEC, cwd, masks, dispositions, TLS and mappings");
}
int main(int argc, char **argv)
{
    CHECK(argc >= 2);
    if (!strcmp(argv[1], "after")) { after_exec(argc, argv); return 0; }
    CHECK(argc == 2); program = argv[0]; original_pid = getpid();
    sigset_t mask; sigemptyset(&mask); CHECK(pthread_sigmask(SIG_SETMASK, &mask, NULL) == 0);
    keep = open("/input", O_RDONLY); CHECK(keep >= 0);
    char byte; CHECK(read(keep, &byte, 1) == 1);
    drop = fcntl(keep, F_DUPFD_CLOEXEC, 0); CHECK(drop >= 0);
    // PRoot can occupy the first candidate. Select an empty
    // address without replacing any existing mapping, including on 39-bit VA.
    void *p = MAP_FAILED;
    for (; old_mapping < 0x7000000000UL; old_mapping += 0x1000000000UL) {
        p = mmap((void *)old_mapping, 4096, PROT_READ | PROT_WRITE,
            MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED_NOREPLACE, -1, 0);
        if (p != MAP_FAILED) break;
        CHECK(errno == EEXIST);
    }
    CHECK(p != MAP_FAILED && p == (void *)old_mapping);
    pthread_t threads[2];
    for (int i = 0; i < 2; i++) CHECK(pthread_create(threads + i, NULL, blocked, (void *)(uintptr_t)i) == 0);
    while (atomic_load(&started) != 2 || !atomic_load(&iterations)) sched_yield();
    if (!strcmp(argv[1], "exec-leader")) replace();
    CHECK(!strcmp(argv[1], "exec-worker") || !strcmp(argv[1], "exec-exited-leader"));
    pthread_t worker; CHECK(pthread_create(&worker, NULL, replace_worker, NULL) == 0);
    if (!strcmp(argv[1], "exec-exited-leader")) pthread_exit(NULL);
    CHECK(pthread_join(worker, NULL) == 0); CHECK(0); return 1;
}
