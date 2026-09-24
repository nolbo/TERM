/* SPDX-License-Identifier: Apache-2.0 */
/* Real glibc programs, built both static and dynamic; not host ABI callbacks. */
#define _GNU_SOURCE
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/auxv.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <time.h>
#include <unistd.h>

extern unsigned char _end[];
static _Thread_local unsigned long tls_value = 0x11223344;
static unsigned char zeroed[65539];
extern unsigned long proot_faststat_hits(void) __attribute__((weak));
extern unsigned long proot_fastopen_hits(void) __attribute__((weak));
static void bus_handler(int signal) { _exit(signal == SIGBUS ? 77 : 78); }
static volatile sig_atomic_t delivered;
static void user_handler(int signal) { if (signal == SIGUSR1) delivered++; }
#define CHECK(x) do { if (!(x)) { fprintf(stderr, "GUEST FAIL %d errno=%d\n", __LINE__, errno); return 1; } } while (0)

int main(int argc, char **argv)
{
    if (argc == 2 && !strcmp(argv[1], "benchmark")) {
        struct timespec begin, end;
        for (int kind = 0; kind < 2; kind++) {
            unsigned long (*hits)(void) = kind ? proot_fastopen_hits : proot_faststat_hits;
            unsigned long before = hits ? hits() : 0;
            CHECK(clock_gettime(CLOCK_MONOTONIC, &begin) == 0);
            for (int i = 0; i < 5000; i++) {
                struct stat st;
                if (kind == 0) CHECK(stat("/input", &st) == 0 && st.st_size == 8);
                else {
                    char bytes[8];
                    int fd = open("/input", O_RDONLY | O_CLOEXEC);
                    CHECK(fd >= 0 && read(fd, bytes, 8) == 8 && !memcmp(bytes, "fixture\n", 8));
                    CHECK(close(fd) == 0);
                }
            }
            CHECK(clock_gettime(CLOCK_MONOTONIC, &end) == 0);
            double ms = (end.tv_sec - begin.tv_sec) * 1000.0 + (end.tv_nsec - begin.tv_nsec) / 1000000.0;
            unsigned long accelerated = hits ? hits() - before : 0;
            printf("BENCH {\"case\":\"%s\",\"count\":5000,\"elapsed_ms\":%.6f,\"acceleration_hits\":%lu}\n",
                kind ? "open-read-close" : "stat", ms, accelerated);
        }
        return 0;
    }
    if (argc == 2 && !strcmp(argv[1], "sigbus")) {
        struct sigaction action = {.sa_handler = bus_handler};
        sigemptyset(&action.sa_mask);
        CHECK(sigaction(SIGBUS, &action, NULL) == 0);
        int fd = open("/input", O_RDONLY);
        CHECK(fd >= 0);
        size_t page = getauxval(AT_PAGESZ);
        volatile char *data = mmap(NULL, page * 2, PROT_READ | PROT_EXEC, MAP_PRIVATE, fd, 0);
        CHECK(data != MAP_FAILED);
        volatile char beyond = data[page];
        (void)beyond;
        return 79;
    }
    CHECK(argc == 2 && !strcmp(argv[1], "hello guest"));
    CHECK(getauxval(AT_PHNUM) > 0 && getauxval(AT_PHENT) == sizeof(Elf64_Phdr));
    CHECK(getauxval(AT_PHDR) != 0 && getauxval(AT_ENTRY) != 0 && getauxval(AT_RANDOM) != 0);
    CHECK(!strcmp((char *)getauxval(AT_EXECFN), argv[0]));
    CHECK(tls_value == 0x11223344); tls_value++;
    for (unsigned i = 0; i < sizeof(zeroed); i++) CHECK(zeroed[i] == 0);
    // Linux padzero semantics include the page tail beyond p_memsz. Non-load
    // ELF sections on disk must not appear in the initial BSS padding.
    uintptr_t end = (uintptr_t)_end;
    uintptr_t tail = (end + getauxval(AT_PAGESZ) - 1) & ~(getauxval(AT_PAGESZ) - 1);
    for (uintptr_t at = end; at < tail; at++) CHECK(*(unsigned char *)at == 0);
    char cwd[4096], text[64] = {0};
    CHECK(getcwd(cwd, sizeof(cwd)) && !strcmp(cwd, "/"));
    int fd = open("/absolute-link", O_RDONLY | O_CLOEXEC);
    CHECK(fd >= 0 && read(fd, text, sizeof(text)) == 8 && !memcmp(text, "fixture\n", 8));
    CHECK(fcntl(fd, F_GETFD) & FD_CLOEXEC);
    struct stat st;
    CHECK(fstat(fd, &st) == 0 && st.st_size == 8);
    CHECK(close(fd) == 0);
    CHECK(lstat("/absolute-link", &st) == 0 && S_ISLNK(st.st_mode));
    CHECK(stat("/relative-link", &st) == 0 && S_ISREG(st.st_mode));
    CHECK(open("/absolute-link", O_RDONLY | O_NOFOLLOW) == -1 && errno == ELOOP);
    CHECK(open("/absolute-link", O_CREAT | O_EXCL | O_WRONLY, 0600) == -1 && errno == EEXIST);
    CHECK(open("/cycle-a", O_RDONLY) == -1 && errno == ELOOP);
    CHECK(open("/outside-link", O_RDONLY) == -1 && errno == ENOENT);
    CHECK(chdir("/tmp") == 0 && getcwd(cwd, sizeof(cwd)) && !strcmp(cwd, "/tmp"));
    CHECK(chdir("..") == 0);
    fd = open("/tmp", O_RDONLY | O_DIRECTORY);
    CHECK(fd >= 0);
    int input = openat(fd, "../input", O_RDONLY);
    CHECK(input >= 0 && close(input) == 0 && close(fd) == 0);
    CHECK(openat(-1, "input", O_RDONLY) == -1 && errno == EBADF);
    CHECK(openat(1023, "input", O_RDONLY) == -1 && errno == EBADF);
    CHECK(syscall(SYS_openat, AT_FDCWD, (void *)1, O_RDONLY, 0) == -1 && errno == EFAULT);
    CHECK(open("/absent", O_RDONLY) == -1 && errno == ENOENT);
    CHECK(tls_value == 0x11223345); // dispatcher must not use or replace guest TLS
    char *heap = malloc(20000); CHECK(heap != NULL); memset(heap, 0x5a, 20000);
    for (int i = 0; i < 20000; i++) CHECK(heap[i] == 0x5a);
    free(heap);
    ssize_t n = readlink("/proc/self/exe", text, sizeof(text));
    CHECK(n == (ssize_t)strlen(argv[0]) && !memcmp(text, argv[0], n));
    sigset_t all, before, after;
    sigfillset(&all);
    CHECK(sigprocmask(SIG_SETMASK, &all, &before) == 0);
    CHECK(sigprocmask(SIG_SETMASK, NULL, &after) == 0 && sigismember(&after, SIGUSR1) == 1);
    CHECK(sigismember(&after, SIGKILL) == 0 && sigismember(&after, SIGSTOP) == 0);
    CHECK(stat("/input", &st) == 0);
    char created[128];
    snprintf(created, sizeof(created), "/tmp/new-%ld", (long)getpid());
    fd = open(created, O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC, 0600);
    CHECK(fd >= 0 && write(fd, "created", 7) == 7 && fsync(fd) == 0 && close(fd) == 0);
    CHECK(stat(created, &st) == 0 && st.st_size == 7);
    // Non-faulting mapping behavior here; EOF/SIGBUS is a separate invocation.
    fd = open("/input", O_RDONLY);
    CHECK(fd >= 0);
    void *mapped = mmap(NULL, getauxval(AT_PAGESZ) * 2, PROT_READ, MAP_PRIVATE, fd, 0);
    CHECK(mapped != MAP_FAILED && !memcmp(mapped, "fixture\n", 8));
    CHECK(munmap(mapped, getauxval(AT_PAGESZ) * 2) == 0 && close(fd) == 0);
    CHECK(sigprocmask(SIG_SETMASK, &before, &after) == 0 && sigismember(&after, SIGSYS) == 1);
    CHECK(stat("/input", &st) == 0);
    struct sigaction action = {.sa_handler = user_handler};
    sigemptyset(&action.sa_mask);
    CHECK(sigaction(SIGUSR1, &action, NULL) == 0);
    sigemptyset(&all); sigaddset(&all, SIGUSR1);
    // Android's launching thread may already block SIGUSR1. Establish this
    // test's precondition without assuming that exec starts with an empty mask.
    CHECK(sigprocmask(SIG_UNBLOCK, &all, NULL) == 0);
    CHECK(sigprocmask(SIG_BLOCK, &all, &before) == 0);
    CHECK(raise(SIGUSR1) == 0 && delivered == 0);
    CHECK(sigpending(&after) == 0 && sigismember(&after, SIGUSR1) == 1);
    CHECK(sigprocmask(SIG_SETMASK, &before, NULL) == 0 && delivered == 1);
    puts("PASS Linux ELF auxv/BSS/TLS/heap/path/errno/mask");
    return 0;
}
