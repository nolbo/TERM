/* SPDX-License-Identifier: Apache-2.0 */
/* Real glibc spawn/vfork; the same fixture runs under native Linux and Direct. */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <sched.h>
#include <signal.h>
#include <spawn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/resource.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#define CHECK(c) do { if (!(c)) { fprintf(stderr, "FAIL spawn line %d: %s errno=%d\n", __LINE__, #c, errno); return 1; } } while (0)
#define CHILD(c) do { if (!(c)) { job->error = __LINE__; return 90; } } while (0)
static volatile sig_atomic_t delivered;
static int inherited_usr1;
static void caught(int sig) { if (sig == SIGUSR1) delivered++; }
static const char *executable;
static char root[4096], root_environment[4120];
static const char *path(const char *guest)
{
    static _Thread_local char buffer[8192];
    if (!root[0]) return guest;
    if (!strcmp(guest, "/")) return root;
    snprintf(buffer, sizeof(buffer), "%s%s", root, guest); return buffer;
}
struct shared {
    int scenario, error, parent, parent_tid, descriptor;
    unsigned ready, go;
    int pid, value;
    unsigned delay;
};

static int callback(void *opaque)
{
    struct shared *job = opaque;
    job->pid = getpid(); job->value = 77;
    __atomic_store_n(&job->ready, 1, __ATOMIC_RELEASE);
    if (job->scenario == 1) {
        CHILD(!syscall(SYS_tgkill, job->parent, job->parent_tid, SIGUSR1));
        struct timespec delay = {.tv_nsec = 20000000};
        CHILD(!nanosleep(&delay, NULL) && !delivered);
        return 37;
    }
    if (job->scenario == 2) {
        while (!__atomic_load_n(&job->go, __ATOMIC_ACQUIRE)) syscall(SYS_sched_yield);
        for (;;) {
            char *args[] = {"missing", NULL}, *env[] = {NULL};
            execve("/missing-spawn-image", args, env);
            CHILD(errno == ENOENT);
        }
    }
    if (job->scenario == 3) {
        int fd = open(path("/tmp/spawn-fifo"), O_RDONLY);
        CHILD(fd >= 0);
        char byte; CHILD(read(fd, &byte, 1) == 1 && byte == 'x' && !close(fd));
        char *args[] = {"true", NULL}, *env[] = {NULL};
        execve("/bin/true", args, env); CHILD(0);
    }
    CHILD(!close(job->descriptor) && !chdir(path("/tmp")));
    struct sigaction ignored = {.sa_handler = SIG_IGN}; sigemptyset(&ignored.sa_mask);
    CHILD(!sigaction(SIGUSR1, &ignored, NULL));
    return 37;
}

static void *killer(void *opaque)
{
    struct shared *job = opaque;
    while (!__atomic_load_n(&job->ready, __ATOMIC_ACQUIRE)) sched_yield();
    __atomic_store_n(&job->go, 1, __ATOMIC_RELEASE);
    // Race failed exec preparation at different stages, including borrowed
    // arena allocation and FD acquisition. No parent runtime lock may remain.
    struct timespec delay = {.tv_nsec = (long)job->delay};
    nanosleep(&delay, NULL);
    if (kill(job->pid, SIGKILL)) job->error = __LINE__;
    return NULL;
}

static void *fifo_peer(void *opaque)
{
    struct shared *job = opaque;
    while (!__atomic_load_n(&job->ready, __ATOMIC_ACQUIRE)) sched_yield();
    int fd = open(path("/tmp/spawn-fifo"), O_WRONLY);
    if (fd < 0 || write(fd, "x", 1) != 1 || close(fd)) job->error = __LINE__;
    return NULL;
}

static int shared_vm(void)
{
    const size_t size = 128 * 1024;
    void *stack = mmap(NULL, size, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    CHECK(stack != MAP_FAILED);
    struct sigaction action = {.sa_handler = caught}; sigemptyset(&action.sa_mask);
    CHECK(!sigaction(SIGUSR1, &action, NULL));
    int fd = open(path("/input"), O_RDONLY | O_CLOEXEC); CHECK(fd >= 0);
    for (int scenario = 0; scenario < 4; scenario++) for (unsigned i = 0; i < (scenario == 2 ? 64U : 8U); i++) {
        struct shared job = {.scenario = scenario, .parent = getpid(), .parent_tid = (int)syscall(SYS_gettid), .descriptor = fd,
            .delay = (i % 8 + 1) * 200000};
        pthread_t thread;
        delivered = 0;
        if (scenario == 2) CHECK(!pthread_create(&thread, NULL, killer, &job));
        if (scenario == 3) CHECK(!pthread_create(&thread, NULL, fifo_peer, &job));
        pid_t pid = clone(callback, (char *)stack + size, CLONE_VM | CLONE_VFORK | SIGCHLD, &job);
        CHECK(pid > 0 && job.ready && job.value == 77 && !job.error);
        int status; CHECK(waitpid(pid, &status, 0) == pid);
        if (scenario == 2) {
            CHECK(!pthread_join(thread, NULL));
            CHECK(WIFSIGNALED(status) && WTERMSIG(status) == SIGKILL && !job.error);
        } else if (scenario == 3) {
            CHECK(!pthread_join(thread, NULL) && !job.error);
            CHECK(WIFEXITED(status) && !WEXITSTATUS(status));
        } else CHECK(WIFEXITED(status) && WEXITSTATUS(status) == 37);
        CHECK(fcntl(fd, F_GETFD) == FD_CLOEXEC);
        char cwd[8192]; CHECK(getcwd(cwd, sizeof(cwd)) && !strcmp(cwd, path("/")));
        struct sigaction current; CHECK(!sigaction(SIGUSR1, NULL, &current) && current.sa_handler == caught);
        if (scenario == 1) CHECK(delivered == 1);
        int probe = open(path("/input"), O_RDONLY); CHECK(probe >= 0 && !close(probe));
    }
    CHECK(!close(fd) && !munmap(stack, size));
    printf("PASS spawn shared VM, private FD/cwd/dispositions, deferred parent signal, FIFO peer and 64 kills during exec preparation (inherited SIGUSR1 blocked=%d)\n", inherited_usr1);
    return 0;
}

static int after(int argc, char **argv)
{
    CHECK(argc == 3 && !strcmp(argv[2], "argument with spaces"));
    CHECK(getenv("DL_SPAWN") && !strcmp(getenv("DL_SPAWN"), "isolated"));
    char cwd[8192]; CHECK(getcwd(cwd, sizeof(cwd)) && !strcmp(cwd, path("/tmp")));
    CHECK(getpgid(0) == getpid() && getuid() == geteuid() && getgid() == getegid());
    sigset_t mask; CHECK(!sigprocmask(SIG_SETMASK, NULL, &mask));
    CHECK(sigismember(&mask, SIGUSR2) == 1 && sigismember(&mask, SIGUSR1) == 0);
    struct sigaction action; CHECK(!sigaction(SIGUSR1, NULL, &action) && action.sa_handler == SIG_DFL);
    CHECK(!sigaction(SIGUSR2, NULL, &action) && action.sa_handler == SIG_IGN);
    CHECK(fcntl(7, F_GETFD) == -1 && errno == EBADF);
    CHECK(sched_getscheduler(0) == SCHED_OTHER);
    puts("spawn child validated"); return 0;
}

static int attributes(void)
{
    posix_spawn_file_actions_t files; posix_spawnattr_t attr;
    CHECK(!posix_spawn_file_actions_init(&files) && !posix_spawnattr_init(&attr));
    CHECK(!posix_spawn_file_actions_addopen(&files, 7, path("/tmp/spawn-output"), O_CREAT | O_TRUNC | O_WRONLY, 0600));
    CHECK(!posix_spawn_file_actions_adddup2(&files, 7, STDOUT_FILENO));
    CHECK(!posix_spawn_file_actions_addclose(&files, 7));
    int directory = open(path("/tmp"), O_RDONLY | O_DIRECTORY | O_CLOEXEC); CHECK(directory >= 0);
    CHECK(!posix_spawn_file_actions_addchdir_np(&files, path("/")));
    CHECK(!posix_spawn_file_actions_addfchdir_np(&files, directory));
    CHECK(!posix_spawn_file_actions_addclosefrom_np(&files, 3));
    struct sigaction action = {.sa_handler = caught}; sigemptyset(&action.sa_mask);
    CHECK(!sigaction(SIGUSR1, &action, NULL));
    action.sa_handler = SIG_IGN; CHECK(!sigaction(SIGUSR2, &action, NULL));
    sigset_t defaults, mask; sigemptyset(&defaults); sigaddset(&defaults, SIGUSR1);
    sigemptyset(&mask); sigaddset(&mask, SIGUSR2);
    CHECK(!posix_spawnattr_setsigdefault(&attr, &defaults) && !posix_spawnattr_setsigmask(&attr, &mask));
    struct sched_param scheduling = {0};
    CHECK(!posix_spawnattr_setschedpolicy(&attr, SCHED_OTHER) && !posix_spawnattr_setschedparam(&attr, &scheduling));
    CHECK(!posix_spawnattr_setpgroup(&attr, 0));
    CHECK(!posix_spawnattr_setflags(&attr, POSIX_SPAWN_SETSIGDEF | POSIX_SPAWN_SETSIGMASK | POSIX_SPAWN_SETPGROUP |
        POSIX_SPAWN_RESETIDS | POSIX_SPAWN_SETSCHEDULER));
    char *args[] = {(char *)executable, "after", "argument with spaces", NULL};
    char *env[] = {"DL_SPAWN=isolated", root_environment, NULL};
    pid_t pid; int status;
    CHECK(!posix_spawn(&pid, executable, &files, &attr, args, env));
    CHECK(waitpid(pid, &status, 0) == pid && WIFEXITED(status) && !WEXITSTATUS(status));
    CHECK(fcntl(directory, F_GETFD) == FD_CLOEXEC && !close(directory));
    CHECK(!posix_spawn_file_actions_destroy(&files) && !posix_spawnattr_destroy(&attr));
    FILE *output = fopen(path("/tmp/spawn-output"), "r"); CHECK(output);
    char text[128]; CHECK(fgets(text, sizeof(text), output) && !strcmp(text, "spawn child validated\n"));
    CHECK(!fclose(output) && !unlink(path("/tmp/spawn-output")));
    char cwd[8192]; CHECK(getcwd(cwd, sizeof(cwd)) && !strcmp(cwd, path("/")));
    CHECK(!sigaction(SIGUSR1, NULL, &action) && action.sa_handler == caught);
    puts("PASS spawn file actions, argv/env, process group, credentials, scheduler and signal attributes");
    return 0;
}

static void *parallel(void *opaque)
{
    unsigned *error = opaque;
    for (unsigned i = 0; i < 40; i++) {
        char *args[] = {"true", NULL}, *env[] = {NULL}; pid_t pid; int status;
        if (posix_spawnp(&pid, "true", NULL, NULL, args, env) ||
                waitpid(pid, &status, 0) != pid || !WIFEXITED(status) || WEXITSTATUS(status)) {
            *error = __LINE__; return NULL;
        }
        int fd = open(path("/input"), O_RDONLY); if (fd < 0 || close(fd)) { *error = __LINE__; return NULL; }
    }
    return NULL;
}

static int repeated(void)
{
    CHECK(!setenv("PATH", "/bin", 1));
    pthread_t workers[4]; unsigned errors[4] = {0};
    for (unsigned i = 0; i < 4; i++) CHECK(!pthread_create(workers + i, NULL, parallel, errors + i));
    for (unsigned i = 0; i < 4; i++) CHECK(!pthread_join(workers[i], NULL) && !errors[i]);
    struct rlimit saved, limit; CHECK(!getrlimit(RLIMIT_NOFILE, &saved)); limit = saved; limit.rlim_cur = 16;
    CHECK(!setrlimit(RLIMIT_NOFILE, &limit));
    for (unsigned i = 0; i < 128; i++) {
        posix_spawn_file_actions_t files; CHECK(!posix_spawn_file_actions_init(&files));
        CHECK(!posix_spawn_file_actions_addopen(&files, 7, "/missing-spawn-directory/input", O_RDONLY, 0));
        char *args[] = {"true", NULL}, *env[] = {NULL}; pid_t pid = -1;
        CHECK(posix_spawn(&pid, "/bin/true", &files, NULL, args, env) == ENOENT && pid == -1);
        CHECK(!posix_spawn_file_actions_destroy(&files));
        CHECK(posix_spawn(&pid, "/missing-spawn-image", NULL, NULL, args, env) == ENOENT && pid == -1);
        int status; CHECK(waitpid(-1, &status, WNOHANG) == -1 && errno == ECHILD);
    }
    CHECK(!setrlimit(RLIMIT_NOFILE, &saved));
    puts("PASS spawn 160 parallel PATH launches and 256 reported/reaped failures at NOFILE 16");
    return 0;
}

int main(int argc, char **argv)
{
    executable = argv[0];
    const char *native_root = getenv("DL_SPAWN_ROOT");
    if (native_root) { CHECK(strlen(native_root) < sizeof(root)); strcpy(root, native_root); }
    snprintf(root_environment, sizeof(root_environment), "DL_SPAWN_ROOT=%s", root);
    if (argc > 1 && !strcmp(argv[1], "after")) return after(argc, argv);
    CHECK(argc == 1);
    if (root[0]) CHECK(!chdir(root));
    sigset_t empty, inherited; sigemptyset(&empty);
    CHECK(!sigprocmask(SIG_SETMASK, &empty, &inherited));
    inherited_usr1 = sigismember(&inherited, SIGUSR1);
    if (shared_vm() || attributes() || repeated()) return 1;
    return 0;
}
