/* SPDX-License-Identifier: Apache-2.0 */
/* The same byte/count/errno contract runs on native Linux and Direct Linux. */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <spawn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/resource.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>

#define CHECK(c) do { if (!(c)) { fprintf(stderr, "FAIL arguments line %d: %s errno=%d\n", __LINE__, #c, errno); return 1; } } while (0)
static const char *executable;
static size_t page;
static unsigned cases;

static int run(char *const *argv, char *const *env, int error, int method)
{
    pid_t child;
    if (method == 2) {
        int result = posix_spawn(&child, executable, NULL, NULL, argv, env);
        if (error) { CHECK(result == error); cases++; return 0; }
        CHECK(!result);
    } else {
        child = method ? vfork() : fork(); CHECK(child >= 0);
        if (!child) {
            syscall(SYS_execve, executable, argv, env);
            _exit(error && errno == error ? 77 : 91);
        }
    }
    int status; CHECK(waitpid(child, &status, 0) == child);
    int expected = error ? 77 : 0;
    if (!WIFEXITED(status) || WEXITSTATUS(status) != expected)
        fprintf(stderr, "arguments child method=%d error=%d status=%d\n", method, error, status);
    CHECK(WIFEXITED(status) && WEXITSTATUS(status) == expected); cases++; return 0;
}

static size_t budget(rlim_t stack)
{
    size_t n = stack / 4;
    if (n > 6 * 1024 * 1024) n = 6 * 1024 * 1024;
    return n < 131072 ? 131072 : n;
}

static int limits(rlim_t stack)
{
    struct rlimit previous; CHECK(!getrlimit(RLIMIT_STACK, &previous));
    struct rlimit current = previous; current.rlim_cur = stack;
    CHECK(!setrlimit(RLIMIT_STACK, &current));
    size_t limit = budget(stack), filename = strlen(executable) + 1;
    CHECK((size_t)sysconf(_SC_ARG_MAX) == limit);
    // One byte per empty string plus one pointer. argv[0] is four nonempty
    // bytes; the executable pathname is also charged by the kernel.
    size_t count = (limit - filename - 4) / (sizeof(char *) + 1);
    char **args = calloc(count + 2, sizeof(*args)); CHECK(args);
    args[0] = "bulk";
    for (size_t i = 1; i <= count; i++) args[i] = "";
    args[count] = NULL;
    char *env[] = {NULL};
    for (int method = 0; method < 3; method++) {
        CHECK(!run(args, env, 0, method));
        args[count] = "";
        CHECK(!run(args, env, E2BIG, method));
        args[count] = NULL;
    }
    char *normal[] = {"bulk", NULL};
    args[0] = ""; args[count - 1] = NULL;
    for (int method = 0; method < 3; method++) {
        CHECK(!run(normal, args, 0, method));
        args[count - 1] = "";
        CHECK(!run(normal, args, E2BIG, method));
        args[count - 1] = NULL;
    }
    free(args);
    // The exact byte limit is independent from count: fill the remaining
    // budget using maximum-sized strings and one final partial string.
    size_t maximum = page * 32, entries = limit / maximum + 2;
    char **large = calloc(entries + 2, sizeof(*large)); CHECK(large);
    char *text = malloc(maximum + 1), *tail = malloc(maximum + 1); CHECK(text && tail);
    memset(text, 'x', maximum); text[maximum - 1] = 0;
    large[0] = "text";
    size_t argc = 2;
    while (filename + 5 + (argc - 1) * maximum + argc * sizeof(char *) < limit) argc++;
    size_t remaining = limit - filename - 5 - argc * sizeof(char *) - (argc - 2) * maximum;
    CHECK(remaining && remaining <= maximum);
    for (size_t i = 1; i + 1 < argc; i++) large[i] = text;
    memset(tail, 'x', maximum + 1); tail[remaining - 1] = 0;
    large[argc - 1] = tail;
    for (int method = 0; method < 3; method++) {
        CHECK(!run(large, env, 0, method));
        tail[remaining - 1] = 'x'; tail[remaining] = 0;
        CHECK(!run(large, env, E2BIG, method));
        tail[remaining - 1] = 0;
    }
    free(large); free(text); free(tail);
    CHECK(!setrlimit(RLIMIT_STACK, &previous));
    printf("PASS arguments stack=%lu budget=%zu empty-count=%zu exact-byte limit across fork/vfork/spawn\n",
        (unsigned long)stack, limit, count);
    return 0;
}

static int vectors(void)
{
    const size_t count = 8192;
    char **args = calloc(count + 1, sizeof(*args)), **env = calloc(count + 1, sizeof(*env));
    CHECK(args && env); args[0] = "vectors";
    for (size_t i = 1; i < count; i++) args[i] = "argument with spaces";
    for (size_t i = 0; i < count; i++) env[i] = "DUPLICATE=value with spaces";
    for (int method = 0; method < 3; method++) CHECK(!run(args, env, 0, method));
    free(args); free(env);
    char *empty[] = {NULL}, *normal[] = {"bulk", NULL};
    for (int method = 0; method < 2; method++) {
        CHECK(!run(NULL, NULL, 0, method));
        CHECK(!run(empty, NULL, 0, method));
        CHECK(!run((void *)1, empty, EFAULT, method));
        CHECK(!run(normal, (void *)1, EFAULT, method));
        char *invalid[] = {(void *)1, NULL};
        CHECK(!run(invalid, empty, EFAULT, method));
        CHECK(!run(normal, invalid, EFAULT, method));
    }
    size_t maximum = 32 * page;
    char *text = malloc(maximum + 1); CHECK(text);
    memset(text, 'x', maximum + 1); text[maximum - 1] = 0;
    char *longargs[] = {"text", text, NULL};
    char *longenv[] = {text, NULL};
    for (int method = 0; method < 3; method++) {
        CHECK(!run(longargs, empty, 0, method));
        CHECK(!run(normal, longenv, 0, method));
        text[maximum - 1] = 'x'; text[maximum] = 0;
        CHECK(!run(longargs, empty, E2BIG, method));
        CHECK(!run(normal, longenv, E2BIG, method));
        text[maximum - 1] = 0;
    }
    free(text);
    char *guard = mmap(NULL, 3 * page, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    CHECK(guard != MAP_FAILED && !mprotect(guard + page, page, PROT_READ | PROT_WRITE));
    char **last = (void *)(guard + 2 * page - sizeof(char *)); *last = NULL;
    CHECK(!run(last, empty, 0, 0));
    *last = "bulk"; CHECK(!run(last, empty, EFAULT, 0));
    char *end = guard + 2 * page - 5; memcpy(end, "bulk", 5);
    char *endargs[] = {end, NULL}; CHECK(!run(endargs, empty, 0, 0));
    end[4] = 'x'; CHECK(!run(endargs, empty, EFAULT, 0));
    // The first table entry spans pages; neither libc nor the runtime can
    // assume argv itself is naturally aligned.
    char *unaligned = guard + page + 1; char *bulk = "bulk";
    memcpy(unaligned, &bulk, sizeof(bulk)); memset(unaligned + sizeof(bulk), 0, sizeof(bulk));
    CHECK(!run((void *)unaligned, empty, 0, 0));
    CHECK(!munmap(guard, 3 * page));
    puts("PASS arguments 8192 argv + 8192 env, NULL vectors, per-string cap, EFAULT, guarded and unaligned tables");
    return 0;
}

int main(int argc, char **argv)
{
    page = (size_t)sysconf(_SC_PAGESIZE);
    if (argc == 1 && !argv[0][0]) return environ[0] != NULL;
    if (!strcmp(argv[0], "bulk")) {
        for (int i = 1; i < argc; i++) CHECK(!argv[i][0]);
        return 0;
    }
    if (!strcmp(argv[0], "text")) {
        for (int i = 1; i < argc; i++) {
            size_t n = strlen(argv[i]); CHECK(n < 32 * page);
            for (size_t j = 0; j < n; j++) CHECK(argv[i][j] == 'x');
        }
        return 0;
    }
    if (!strcmp(argv[0], "vectors")) {
        CHECK(argc == 8192);
        for (int i = 1; i < argc; i++) CHECK(!strcmp(argv[i], "argument with spaces"));
        size_t n = 0;
        while (environ[n]) { CHECK(!strcmp(environ[n], "DUPLICATE=value with spaces")); n++; }
        CHECK(n == 8192); return 0;
    }
    executable = argv[0]; CHECK(!vectors());
    const rlim_t limits_to_test[] = {256 * 1024, 1024 * 1024, 8 * 1024 * 1024, 32 * 1024 * 1024};
    struct rlimit current; CHECK(!getrlimit(RLIMIT_STACK, &current));
    for (unsigned i = 0; i < sizeof(limits_to_test) / sizeof(limits_to_test[0]); i++) {
        CHECK(current.rlim_max == RLIM_INFINITY || limits_to_test[i] <= current.rlim_max);
        CHECK(!limits(limits_to_test[i]));
    }
    printf("PASS arguments %u native-contract exec cases\n", cases); return 0;
}
