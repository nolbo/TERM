/* SPDX-License-Identifier: Apache-2.0 */
/* Run unchanged under native Linux, Direct Linux and a reference rootfs. */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/auxv.h>
#include <sys/resource.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>

#define CHECK(x) do { if (!(x)) { dprintf(2, "FAIL execfd line=%d errno=%d: %s\n", __LINE__, errno, #x); exit(90); } } while (0)
static char self[4096], directory[4096], script[4096], linkname[4096], copyname[4096];
static volatile sig_atomic_t handled;
static void handler(int signal) { handled = signal; }

static void copy_executable(void)
{
    int source = open(self, O_RDONLY), destination = open(copyname, O_WRONLY | O_CREAT | O_TRUNC, 0700);
    CHECK(source >= 0 && destination >= 0);
    char bytes[16384]; ssize_t n;
    while ((n = read(source, bytes, sizeof(bytes))) > 0) CHECK(write(destination, bytes, (size_t)n) == n);
    CHECK(!n && !close(source) && !close(destination));
}

static int target(int argc, char **argv)
{
    int offset = 1;
    if (!strcmp(argv[1], "script-target")) {
        CHECK(argc == 10);
        CHECK(!strcmp(argv[2], argv[9]));
        int fd = open(argv[2], O_RDONLY); CHECK(fd >= 0);
        char bytes[512] = {0}; CHECK(read(fd, bytes, sizeof(bytes) - 1) > 0 && strstr(bytes, "execfd-script-body"));
        CHECK(!close(fd)); offset = 3;
    }
    CHECK(argc == offset + 7 && !strcmp(argv[offset], "target"));
    CHECK(getpid() == atoi(argv[offset + 1]) && syscall(SYS_gettid) == getpid());
    CHECK(getenv("EXECFD_SENTINEL") && !strcmp(getenv("EXECFD_SENTINEL"), "ok"));
    int fd = atoi(argv[offset + 2]), cloexec = atoi(argv[offset + 3]);
    if (cloexec) CHECK(fcntl(fd, F_GETFD) == -1 && errno == EBADF);
    else {
        CHECK(fcntl(fd, F_GETFD) >= 0);
        if (strcmp(argv[offset + 4], "-1")) CHECK(lseek(fd, 0, SEEK_CUR) == atol(argv[offset + 4]));
    }
    CHECK(!strcmp((char *)getauxval(AT_EXECFN), argv[offset + 5]));
    CHECK(!strcmp(argv[offset + 6], argv[offset + 5]));
    return 0;
}

static const char *cases[] = {"empty-read", "empty-path", "empty-cloexec", "deleted",
    "relative", "relative-cloexec", "cwd", "absolute", "symlink", "nofollow", "empty-nofollow", "fexecve", "proc-fallback",
    "script-fd", "script-relative", "script-deleted", "script-proc", "shell-script"};

static void *execute(void *opaque)
{
    const char *name = opaque;
    int is_script = !strncmp(name, "script-", 7) || !strcmp(name, "shell-script");
    int relative = !strncmp(name, "relative", 8) || !strcmp(name, "script-relative");
    int cloexec = !strcmp(name, "empty-cloexec") || !strcmp(name, "relative-cloexec");
    int opath = !strcmp(name, "empty-path") || !strcmp(name, "script-fd");
    const char *source = is_script ? script : self;
    if (!strcmp(name, "deleted")) { copy_executable(); source = copyname; }
    int fd = open(relative ? (is_script ? directory : "/") : source,
        (relative || opath ? O_PATH : O_RDONLY) | (cloexec ? O_CLOEXEC : 0));
    CHECK(fd >= 0);
    long offset = relative || opath ? -1 : 7;
    if (offset >= 0) CHECK(lseek(fd, offset, SEEK_SET) == offset);
    if (!strcmp(name, "deleted") || !strcmp(name, "script-deleted")) CHECK(!unlink(source));
    char expected[8192], pid[32], descriptor[32], position[32];
    snprintf(pid, sizeof(pid), "%ld", (long)getpid());
    snprintf(descriptor, sizeof(descriptor), "%d", fd);
    snprintf(position, sizeof(position), "%ld", offset);
    const char *path = ""; int base = fd, flags = AT_EMPTY_PATH;
    if (!strcmp(name, "empty-nofollow")) flags |= AT_SYMLINK_NOFOLLOW;
    if (relative) { path = is_script ? "script" : self + 1; flags = 0; }
    if (!strcmp(name, "cwd")) { CHECK(!chdir("/")); path = self + 1; base = AT_FDCWD; flags = 0; }
    if (!strcmp(name, "absolute")) { path = self; base = -1; flags = 0; }
    if (!strcmp(name, "symlink")) { path = linkname; base = AT_FDCWD; flags = 0; }
    if (!strcmp(name, "nofollow")) { path = self; base = AT_FDCWD; flags = AT_SYMLINK_NOFOLLOW; }
    if (base == AT_FDCWD || path[0] == '/') snprintf(expected, sizeof(expected), "%s", path);
    else snprintf(expected, sizeof(expected), "/dev/fd/%d%s%s", fd, path[0] ? "/" : "", path);
    if (!strcmp(name, "proc-fallback") || !strcmp(name, "script-proc"))
        snprintf(expected, sizeof(expected), "/proc/self/fd/%d", fd);
    char *args[] = {"custom-name", "target", pid, descriptor, cloexec ? "1" : "0", position, expected, expected, NULL};
    char *env[] = {"EXECFD_SENTINEL=ok", NULL};
    if (!strcmp(name, "shell-script")) {
        char *shell[] = {"shell", expected, "sentinel", NULL};
        syscall(SYS_execveat, fd, "", shell, env, AT_EMPTY_PATH);
    } else if (!strcmp(name, "fexecve")) fexecve(fd, args, env);
    else if (!strcmp(name, "proc-fallback") || !strcmp(name, "script-proc")) execve(expected, args, env);
    else syscall(SYS_execveat, base, path, args, env, flags);
    dprintf(2, "FAIL execfd case=%s returned errno=%d\n", name, errno); exit(91);
}

static void make_script(int shell)
{
    int fd = open(script, O_WRONLY | O_CREAT | O_TRUNC, 0700); CHECK(fd >= 0);
    if (shell) CHECK(dprintf(fd, "#!/bin/dash\ntest \"$0\" = \"$1\" && test \"$2\" = sentinel\n") > 0);
    else CHECK(dprintf(fd, "#!%s script-target\nexecfd-script-body\n", self) > 0);
    CHECK(!close(fd));
}

static void errors(void)
{
    make_script(0);
    int fd = open(self, O_RDONLY | O_CLOEXEC), dir = open(directory, O_PATH | O_CLOEXEC);
    int text = open(script, O_PATH | O_CLOEXEC); CHECK(fd >= 0 && dir >= 0 && text >= 0);
    // Check an O_PATH descriptor referring to the symlink itself, without
    // following it, independently of pathname AT_SYMLINK_NOFOLLOW.
    int symlink_fd = open(linkname, O_PATH | O_NOFOLLOW | O_CLOEXEC); CHECK(symlink_fd >= 0);
    CHECK(lseek(fd, 13, SEEK_SET) == 13 && signal(SIGUSR1, handler) != SIG_ERR);
    struct rlimit original; CHECK(!getrlimit(RLIMIT_NOFILE, &original));
    struct rlimit limit = original; limit.rlim_cur = 16; CHECK(!setrlimit(RLIMIT_NOFILE, &limit));
    char *args[] = {"test", NULL}, *env[] = {NULL};
    struct { int base; const char *path; int flags, error; } checks[] = {
        {fd, "", AT_EMPTY_PATH | 1, EINVAL}, {-1, "", AT_EMPTY_PATH, EBADF},
        {fd, "", 0, ENOENT}, {fd, "child", 0, ENOTDIR}, {dir, "", AT_EMPTY_PATH, EACCES},
        {AT_FDCWD, linkname, AT_SYMLINK_NOFOLLOW, ELOOP}, {text, "", AT_EMPTY_PATH, ENOENT},
        {dir, "script", 0, ENOENT}, {fd, (void *)1, AT_EMPTY_PATH, EFAULT},
        {fd, NULL, AT_EMPTY_PATH, EFAULT}, {-1, "missing", 0, EBADF},
        {symlink_fd, "", AT_EMPTY_PATH, ELOOP},
        {symlink_fd, "", AT_EMPTY_PATH | AT_SYMLINK_NOFOLLOW, ELOOP},
        {AT_FDCWD, "", AT_EMPTY_PATH, EACCES},
    };
    for (int repeat = 0; repeat < 100; repeat++) {
        for (unsigned i = 0; i < sizeof(checks) / sizeof(checks[0]); i++) {
            errno = 0;
            long result = syscall(SYS_execveat, checks[i].base, checks[i].path, args, env, checks[i].flags);
            if (result != -1 || errno != checks[i].error)
                dprintf(2, "FAIL execfd error-case=%u expected=%d actual=%d result=%ld\n", i, checks[i].error, errno, result);
            CHECK(result == -1 && errno == checks[i].error);
        }
        CHECK(syscall(SYS_execveat, fd, "", (void *)1, env, AT_EMPTY_PATH) == -1 && errno == EFAULT);
        CHECK(syscall(SYS_execveat, fd, "", args, (void *)1, AT_EMPTY_PATH) == -1 && errno == EFAULT);
        CHECK(fcntl(fd, F_GETFD) == FD_CLOEXEC && lseek(fd, 0, SEEK_CUR) == 13);
        handled = 0; CHECK(!raise(SIGUSR1) && handled == SIGUSR1);
        int spare[16], count = 0;
        while ((spare[count] = dup(fd)) >= 0) CHECK(++count < 16);
        CHECK(errno == EMFILE && count == 9);
        while (count) CHECK(!close(spare[--count]));
    }
    CHECK(!setrlimit(RLIMIT_NOFILE, &original) && !close(fd) && !close(dir) && !close(text) && !close(symlink_fd));
    puts("PASS execfd errno, original image/signal/FD state and 100 low-NOFILE failure cycles");
}

int main(int argc, char **argv)
{
    if (argc > 1 && (!strcmp(argv[1], "target") || !strcmp(argv[1], "script-target"))) return target(argc, argv);
    CHECK(argc == 2 && argv[0][0] == '/');
    sigset_t empty; sigemptyset(&empty); CHECK(!pthread_sigmask(SIG_SETMASK, &empty, NULL));
    snprintf(self, sizeof(self), "%s", argv[0]);
    strcpy(directory, "/tmp/execfd-XXXXXX"); CHECK(mkdtemp(directory));
    CHECK(snprintf(script, sizeof(script), "%s/script", directory) < (int)sizeof(script));
    CHECK(snprintf(linkname, sizeof(linkname), "%s/link", directory) < (int)sizeof(linkname));
    CHECK(snprintf(copyname, sizeof(copyname), "%s/copy", directory) < (int)sizeof(copyname));
    CHECK(!symlink(self, linkname));
    if (!strcmp(argv[1], "errors")) errors();
    else {
        int worker = !strcmp(argv[1], "worker"); CHECK(worker || !strcmp(argv[1], "single"));
        for (unsigned i = 0; i < sizeof(cases) / sizeof(cases[0]); i++) {
            make_script(!strcmp(cases[i], "shell-script"));
            pid_t child = fork(); CHECK(child >= 0);
            if (!child) {
                if (worker) { pthread_t thread; CHECK(!pthread_create(&thread, NULL, execute, (void *)cases[i])); pthread_join(thread, NULL); _exit(92); }
                execute((void *)cases[i]); _exit(93);
            }
            int status; CHECK(waitpid(child, &status, 0) == child);
            if (!WIFEXITED(status) || WEXITSTATUS(status)) dprintf(2, "FAIL execfd child case=%s status=%d\n", cases[i], status);
            CHECK(WIFEXITED(status) && !WEXITSTATUS(status));
            printf("PASS execfd %s %s\n", argv[1], cases[i]); fflush(stdout);
        }
    }
    CHECK(!unlink(linkname));
    if (unlink(script)) CHECK(errno == ENOENT);
    CHECK(!rmdir(directory)); return 0;
}
