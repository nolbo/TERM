#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

/* Identical binary for native/PRoot comparisons. Only disposable files are used.
 * Not an application benchmark: open/close, preparation and verification are
 * reported separately from transfer and final fsync. No durability elision. */
static char *temporary_path;
static void cleanup(void) { if (temporary_path) unlink(temporary_path); }
static void fail(const char *what) { perror(what); exit(1); }
static uint64_t now(clockid_t clock) {
    struct timespec t;
    if (clock_gettime(clock, &t)) fail("clock_gettime");
    return (uint64_t)t.tv_sec * 1000000000 + t.tv_nsec;
}
static size_t number(const char *text, size_t maximum) {
    char *end;
    errno = 0;
    unsigned long n = strtoul(text, &end, 10);
    if (errno || !*text || *end || !n || n > maximum) {
        fprintf(stderr, "invalid bounded positive integer: %s\n", text); exit(2);
    }
    return n;
}
static void row(const char *operation, size_t bytes, size_t block, size_t calls,
                uint64_t wall, uint64_t cpu) {
    printf("{\"operation\":\"%s\",\"bytes\":%zu,\"block_bytes\":%zu,"
           "\"syscalls\":%zu,\"wall_ns\":%llu,\"cpu_ns\":%llu}\n",
           operation, bytes, block, calls,
           (unsigned long long)wall, (unsigned long long)cpu);
}
int main(int argc, char **argv) {
    if (argc != 4) {
        fprintf(stderr, "usage: %s TEMP_DIRECTORY MiB(1..256) KiB(1..1024)\n", argv[0]);
        return 2;
    }
    const size_t total = number(argv[2], 256) * 1024 * 1024;
    const size_t block = number(argv[3], 1024) * 1024;
    unsigned char *data = malloc(block), *buffer = malloc(block);
    if (!data || !buffer) fail("malloc");
    for (size_t i = 0; i < block; i++) data[i] = (unsigned char)(i % 251);
    char *path;
    if (asprintf(&path, "%s/terminal-io-XXXXXX", argv[1]) < 0) fail("asprintf");
    const uint64_t opening = now(CLOCK_MONOTONIC);
    int fd = mkstemp(path);
    if (fd < 0) fail("mkstemp");
    temporary_path = path;
    if (atexit(cleanup)) fail("atexit");
    const uint64_t open_ns = now(CLOCK_MONOTONIC) - opening;
    size_t done = 0, calls = 0;
    uint64_t wall = now(CLOCK_MONOTONIC), cpu = now(CLOCK_PROCESS_CPUTIME_ID);
    while (done < total) {
        const size_t offset = done % block;
        size_t amount = block - offset;
        if (amount > total - done) amount = total - done;
        ssize_t n = write(fd, data + offset, amount);
        calls++;
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) fail("write");
        done += (size_t)n;
    }
    row("write", done, block, calls, now(CLOCK_MONOTONIC)-wall,
        now(CLOCK_PROCESS_CPUTIME_ID)-cpu);
    wall = now(CLOCK_MONOTONIC); cpu = now(CLOCK_PROCESS_CPUTIME_ID);
    int result;
    do { result = fsync(fd); } while (result < 0 && errno == EINTR);
    if (result) fail("fsync");
    row("fsync", total, block, 1, now(CLOCK_MONOTONIC)-wall,
        now(CLOCK_PROCESS_CPUTIME_ID)-cpu);
    if (lseek(fd, 0, SEEK_SET)) fail("lseek");
    done = 0; calls = 0;
    wall = now(CLOCK_MONOTONIC); cpu = now(CLOCK_PROCESS_CPUTIME_ID);
    while (done < total) {
        size_t amount = block < total-done ? block : total-done;
        ssize_t n = read(fd, buffer, amount);
        calls++;
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) fail("read");
        done += (size_t)n;
    }
    row("read", done, block, calls, now(CLOCK_MONOTONIC)-wall,
        now(CLOCK_PROCESS_CPUTIME_ID)-cpu);
    /* Untimed complete content verification, including short reads. */
    if (lseek(fd, 0, SEEK_SET)) fail("verification lseek");
    done = 0;
    while (done < total) {
        size_t offset = done % block, amount = block-offset;
        if (amount > total-done) amount = total-done;
        ssize_t n = read(fd, buffer, amount);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) fail("verification read");
        if (memcmp(buffer, data+offset, (size_t)n)) {
            fprintf(stderr, "content mismatch at %zu\n", done); return 1;
        }
        done += (size_t)n;
    }
    if (read(fd, buffer, 1) != 0) fail("unexpected trailing bytes");
    wall = now(CLOCK_MONOTONIC);
    if (close(fd)) fail("close");
    printf("{\"verified\":true,\"open_ns\":%llu,\"close_ns\":%llu}\n",
           (unsigned long long)open_ns,
           (unsigned long long)(now(CLOCK_MONOTONIC)-wall));
    if (unlink(path)) fail("unlink temporary file");
    temporary_path = NULL;
    free(path); free(data); free(buffer);
    return 0;
}
