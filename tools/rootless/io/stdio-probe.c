#define _GNU_SOURCE
#include <assert.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdio_ext.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
static uint64_t now(void) {
    struct timespec t; assert(!clock_gettime(CLOCK_MONOTONIC, &t));
    return (uint64_t)t.tv_sec * 1000000000 + t.tv_nsec;
}
int main(int argc, char **argv) {
    if (argc != 2 || (strcmp(argv[1], "4") && strcmp(argv[1], "64") && strcmp(argv[1], "1024"))) return 2;
    const size_t block = strtoul(argv[1], NULL, 10) * 1024;
    const size_t total = 64 * 1024 * 1024;
    unsigned char *input = malloc(block), *output = malloc(block); assert(input && output);
    for (size_t i = 0; i < block; i++) input[i] = (unsigned char)(i % 251);
    char path[] = "/tmp/terminal-stdio-probe-XXXXXX";
    int fd = mkstemp(path); assert(fd >= 0 && !close(fd));
    FILE *f = fopen(path, "w"); assert(f);
    uint64_t start = now();
    for (size_t done = 0; done < total; done += block) assert(fwrite(input, 1, block, f) == block);
    assert(!fflush(f));
    uint64_t written = now() - start;
    size_t buffer_size = __fbufsize(f);
    start = now(); assert(!fsync(fileno(f))); uint64_t synced = now() - start;
    assert(!fclose(f));
    f = fopen(path, "r"); assert(f);
    start = now();
    for (size_t done = 0; done < total; done += block) assert(fread(output, 1, block, f) == block);
    uint64_t read_ns = now() - start;
    assert(!fseek(f, 0, SEEK_SET));
    for (size_t done = 0; done < total; done += block) {
        assert(fread(output, 1, block, f) == block && !memcmp(input, output, block));
    }
    assert(fgetc(f) == EOF && feof(f) && !ferror(f));
    assert(!fclose(f) && !unlink(path));
    printf("{\"block_kib\":%zu,\"stdio_buffer\":%zu,\"bytes\":%zu,\"write_flush_ns\":%llu,\"fsync_ns\":%llu,\"read_ns\":%llu,\"verified\":true}\n",
        block / 1024, buffer_size, total, (unsigned long long)written,
        (unsigned long long)synced, (unsigned long long)read_ns);
    free(input); free(output);
}
