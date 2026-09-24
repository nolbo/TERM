/* SPDX-License-Identifier: Apache-2.0 */
/* ARM64 in-process syscall translation feasibility probe. Not an ELF loader.
 * Only /probe/{input,missing,link} is recognized; no general filesystem claims. */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <pthread.h>
#include <signal.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/resource.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <sys/wait.h>
#include <time.h>
#include <ucontext.h>
#include <unistd.h>

#if !defined(__aarch64__)
#error This experiment requires ARM64
#endif

extern long dl_host_syscall(long, long, long, long, long, long, long);
extern long dl_guest_syscall(long, long, long, long, long, long, long);
extern char dl_host_return[];
#define HOST(n,a,b,c,d,e,f) dl_host_syscall(n,(long)(a),(long)(b),(long)(c),(long)(d),(long)(e),(long)(f))
#define GUEST(n,a,b,c,d,e,f) dl_guest_syscall(n,(long)(a),(long)(b),(long)(c),(long)(d),(long)(e),(long)(f))
#define REQUIRE(test) do { if (!(test)) { fprintf(stderr, "FAIL line=%d errno=%d\n", __LINE__, errno); return 1; } } while (0)

static int root_fd;
static size_t page_size;
static const char contents[] = "direct-linux-fixture\n";
static _Thread_local unsigned long trapped;

/* The kernel copies from the untrusted pointer. Never dereference it in the
 * handler. Page-bounded reads preserve a NUL at the last accessible byte. */
static long copy_path(char out[64], uintptr_t source)
{
    size_t used = 0;
    long pid = HOST(SYS_getpid, 0, 0, 0, 0, 0, 0);
    while (used < 64) {
        size_t size = page_size - source % page_size;
        if (size > 64 - used) size = 64 - used;
        struct iovec local = {out + used, size};
        struct iovec remote = {(void *)source, size};
        long n = HOST(SYS_process_vm_readv, pid, &local, 1, &remote, 1, 0);
        if (n < 0) return n;
        if (n == 0) return -EFAULT;
        for (long i = 0; i < n; i++) if (!out[used + (size_t)i]) return 0;
        used += (size_t)n;
        if (UINTPTR_MAX - source < (uintptr_t)n) return -EFAULT;
        source += (uintptr_t)n;
    }
    return -ENAMETOOLONG;
}

/* libc, heap allocation, logging and process-wide locks are forbidden here. */
static void dispatch(int signal, siginfo_t *info, void *context)
{
    ucontext_t *state = context;
    if (signal != SIGSYS || info->si_code != SYS_SECCOMP ||
            info->si_arch != AUDIT_ARCH_AARCH64 || info->si_syscall != SYS_openat) {
        HOST(SYS_exit_group, 120, 0, 0, 0, 0, 0);
        return;
    }
    trapped++;
    char path[64];
    long result = copy_path(path, state->uc_mcontext.regs[1]);
    if (result == 0) {
        /* The fixture recognizes only one-component paths. It deliberately
         * refuses everything else, including writes and symlink traversal. */
        const char *prefix = "/probe/";
        size_t i = 0;
        for (; prefix[i] && prefix[i] == path[i]; i++) {}
        if (prefix[i]) result = -EOPNOTSUPP;
        else {
            const char *name = path + i;
            size_t j = 0;
            for (; name[j] && name[j] != '/' && name[j] != '.'; j++) {}
            if (!j || name[j]) result = -EOPNOTSUPP;
            else if (state->uc_mcontext.regs[2] & ~(O_CLOEXEC | O_NOFOLLOW)) result = -EOPNOTSUPP;
            else result = HOST(SYS_openat, root_fd, name,
                state->uc_mcontext.regs[2] | O_NOFOLLOW, 0, 0, 0);
        }
    }
    state->uc_mcontext.regs[0] = (unsigned long)result;
}

static int install_dispatcher(void)
{
    struct sigaction action = {.sa_sigaction = dispatch, .sa_flags = SA_SIGINFO | SA_ONSTACK};
    sigemptyset(&action.sa_mask);
    if (sigaction(SIGSYS, &action, NULL)) return -1;
    uintptr_t pc = (uintptr_t)dl_host_return;
    struct sock_filter filter[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, arch)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, AUDIT_ARCH_AARCH64, 1, 0),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_KILL_PROCESS),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_openat, 1, 0),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, instruction_pointer) + 4),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, pc >> 32, 0, 3),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, instruction_pointer)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, (uint32_t)pc, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),
    };
    struct sock_fprog program = {sizeof(filter) / sizeof(filter[0]), filter};
    return prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) ||
        prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &program);
}

static void *alternate_stack(void)
{
    size_t size = 128 * 1024;
    void *mapping = mmap(NULL, size + 2 * page_size, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (mapping == MAP_FAILED) return NULL;
    void *start = (char *)mapping + page_size;
    stack_t stack = {.ss_sp = start, .ss_size = size};
    if (mprotect(start, size, PROT_READ | PROT_WRITE) || sigaltstack(&stack, NULL)) {
        munmap(mapping, size + 2 * page_size);
        return NULL;
    }
    return mapping;
}

static void free_stack(void *mapping)
{
    stack_t stack = {.ss_flags = SS_DISABLE};
    sigaltstack(&stack, NULL);
    munmap(mapping, 128 * 1024 + 2 * page_size);
}

static int read_fixture(int dirfd, const char *path)
{
    long fd = GUEST(SYS_openat, dirfd, path, O_CLOEXEC, 0, 0, 0);
    if (fd < 0) return 1;
    char buffer[sizeof(contents)];
    long size = GUEST(SYS_read, fd, buffer, sizeof(buffer), 0, 0, 0);
    long flags = GUEST(SYS_fcntl, fd, F_GETFD, 0, 0, 0, 0);
    long closed = GUEST(SYS_close, fd, 0, 0, 0, 0, 0);
    return size != sizeof(contents) - 1 || memcmp(buffer, contents, sizeof(contents) - 1) ||
        !(flags & FD_CLOEXEC) || closed;
}

static int checks(void)
{
    REQUIRE(read_fixture(AT_FDCWD, "/probe/input") == 0);
    REQUIRE(trapped > 0);
    REQUIRE(GUEST(SYS_openat, AT_FDCWD, "/probe/missing", O_CLOEXEC, 0, 0, 0) == -ENOENT);
    REQUIRE(GUEST(SYS_openat, AT_FDCWD, "/probe/link", O_NOFOLLOW, 0, 0, 0) == -ELOOP);
    REQUIRE(GUEST(SYS_openat, AT_FDCWD, "/probe/../input", 0, 0, 0, 0) == -EOPNOTSUPP);
    REQUIRE(GUEST(SYS_openat, AT_FDCWD, "/probe/input", O_TRUNC | O_WRONLY, 0, 0, 0) == -EOPNOTSUPP);
    REQUIRE(GUEST(SYS_openat, AT_FDCWD, 1, 0, 0, 0, 0) == -EFAULT);
    char *memory = mmap(NULL, page_size * 2, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    REQUIRE(memory != MAP_FAILED);
    REQUIRE(mprotect(memory + page_size, page_size, PROT_NONE) == 0);
    char *edge = memory + page_size - sizeof("/probe/input");
    memcpy(edge, "/probe/input", sizeof("/probe/input"));
    REQUIRE(read_fixture(AT_FDCWD, edge) == 0);
    memory[page_size - 1] = 'x';
    REQUIRE(GUEST(SYS_openat, AT_FDCWD, edge, 0, 0, 0, 0) == -EFAULT);
    REQUIRE(munmap(memory, page_size * 2) == 0);
    return 0;
}

static void *thread_checks(void *unused)
{
    (void)unused;
    void *stack = alternate_stack();
    if (!stack) return (void *)1;
    int failed = checks();
    free_stack(stack);
    return (void *)(intptr_t)failed;
}

static int inherited_denial(void)
{
    /* Lower-precedence inherited ERRNO must still apply to the host trampoline.
     * A stricter inherited KILL cannot be overridden by our filter either. */
    struct sock_filter filters[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_openat, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EACCES),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    };
    struct sock_fprog program = {sizeof(filters) / sizeof(filters[0]), filters};
    REQUIRE(prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &program) == 0);
    REQUIRE(GUEST(SYS_openat, AT_FDCWD, "/probe/input", 0, 0, 0, 0) == -EACCES);
    return 0;
}

static int child_check(int (*check)(void))
{
    pid_t child = fork();
    REQUIRE(child >= 0);
    if (!child) _exit(check());
    int status;
    REQUIRE(waitpid(child, &status, 0) == child);
    REQUIRE(WIFEXITED(status) && WEXITSTATUS(status) == 0);
    return 0;
}

static int executable_mapping(void)
{
    /* Copy-as-data -> RX, then RW -> RX, without a writable+executable mapping.
     * This only tests our eight-byte fixture, not arbitrary guest ELF/JITs. */
    uint32_t *code = mmap(NULL, page_size, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    REQUIRE(code != MAP_FAILED);
    code[0] = 0x52800540; /* mov w0, #42 */
    code[1] = 0xd65f03c0; /* ret */
    __builtin___clear_cache((char *)code, (char *)(code + 2));
    REQUIRE(mprotect(code, page_size, PROT_READ | PROT_EXEC) == 0);
    int (*function)(void) = (int (*)(void))code;
    REQUIRE(function() == 42);
    REQUIRE(mprotect(code, page_size, PROT_READ | PROT_WRITE) == 0);
    code[0] = 0x52800560; /* mov w0, #43 */
    __builtin___clear_cache((char *)code, (char *)(code + 2));
    REQUIRE(mprotect(code, page_size, PROT_READ | PROT_EXEC) == 0);
    REQUIRE(function() == 43);
    REQUIRE(munmap(code, page_size) == 0);
    return 0;
}

static int policy(void)
{
    FILE *status = fopen("/proc/self/status", "r");
    REQUIRE(status != NULL);
    char line[256];
    int untraced = 0, unprivileged = 0;
    while (fgets(line, sizeof(line), status)) {
        unsigned long long value;
        if (sscanf(line, "TracerPid: %llu", &value) == 1) untraced = value == 0;
        if (sscanf(line, "CapEff: %llx", &value) == 1) unprivileged = value == 0;
        if (!strncmp(line, "TracerPid:", 10) || !strncmp(line, "CapEff:", 7) ||
            !strncmp(line, "NoNewPrivs:", 11) || !strncmp(line, "Seccomp:", 8))
            printf("POLICY %s", line);
    }
    fclose(status);
    REQUIRE(getuid() != 0 && unprivileged && untraced);
    return 0;
}

static double milliseconds(struct timespec time)
{
    return time.tv_sec * 1000.0 + time.tv_nsec / 1000000.0;
}

int main(int argc, char **argv)
{
    if (argc != 4) {
        fprintf(stderr, "usage: probe DIRECTORY check|direct|trap|guest COUNT\n");
        return 2;
    }
    char *end;
    long count = strtol(argv[3], &end, 10);
    if (*end || count < 1 || count > 100000) return 2;
    int check = !strcmp(argv[2], "check"), trap = check || !strcmp(argv[2], "trap");
    int guest = !strcmp(argv[2], "guest");
    if (!trap && !guest && strcmp(argv[2], "direct")) return 2;
    page_size = (size_t)sysconf(_SC_PAGESIZE);
    REQUIRE(page_size >= 4096 && page_size <= 65536);
    if (!guest) REQUIRE(policy() == 0);
    root_fd = open(argv[1], O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    REQUIRE(root_fd >= 0);
    if (check) REQUIRE(child_check(executable_mapping) == 0);
    void *stack = NULL;
    if (trap) {
        stack = alternate_stack();
        REQUIRE(stack != NULL);
        REQUIRE(install_dispatcher() == 0);
        REQUIRE(prctl(PR_GET_NO_NEW_PRIVS, 0, 0, 0, 0) == 1);
        REQUIRE(prctl(PR_GET_SECCOMP, 0, 0, 0, 0) == 2);
    }
    if (check) {
        REQUIRE(checks() == 0);
        pthread_t threads[4];
        for (int i = 0; i < 4; i++) REQUIRE(pthread_create(&threads[i], NULL, thread_checks, NULL) == 0);
        for (int i = 0; i < 4; i++) {
            void *result;
            REQUIRE(pthread_join(threads[i], &result) == 0 && result == NULL);
        }
        REQUIRE(child_check(checks) == 0);
        REQUIRE(child_check(inherited_denial) == 0);
        puts("PASS direct-linux policy, W^X, trap, EFAULT, fork, threads, inherited denial");
    }
    const char *path = trap || guest ? "/probe/input" : "input";
    struct timespec start, finish;
    struct rusage before, after;
    getrusage(RUSAGE_SELF, &before);
    clock_gettime(CLOCK_MONOTONIC, &start);
    unsigned long initial = trapped;
    for (long i = 0; i < count; i++) REQUIRE(read_fixture(root_fd, path) == 0);
    clock_gettime(CLOCK_MONOTONIC, &finish);
    getrusage(RUSAGE_SELF, &after);
    if (trap) REQUIRE(trapped - initial == (unsigned long)count);
    printf("RESULT {\"mode\":\"%s\",\"count\":%ld,\"elapsed_ms\":%.6f,\"traps\":%lu,\"voluntary_switches\":%ld,\"involuntary_switches\":%ld}\n",
        argv[2], count, milliseconds(finish) - milliseconds(start), trapped - initial,
        after.ru_nvcsw - before.ru_nvcsw, after.ru_nivcsw - before.ru_nivcsw);
    if (stack) free_stack(stack);
    close(root_fd);
    return 0;
}
