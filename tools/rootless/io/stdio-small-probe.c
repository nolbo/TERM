/* SPDX-License-Identifier: GPL-2.0-or-later
 * Warm small-file whole-operation check; setup and cleanup are not timed.
 */
#define _GNU_SOURCE
#include <assert.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

enum { FILES = 128, PASSES = 8, BYTES = 448 };
static double milliseconds(void) {
    struct timespec now;
    assert(clock_gettime(CLOCK_MONOTONIC, &now) == 0);
    return now.tv_sec * 1000.0 + now.tv_nsec / 1000000.0;
}
int main(void) {
    char directory[] = "/tmp/terminal-stdio-small-XXXXXX";
    char paths[FILES][128];
    unsigned char expected[BYTES], actual[BYTES + 1];
    assert(mkdtemp(directory));
    for (int i = 0; i < BYTES; ++i) expected[i] = (unsigned char)(i * 37);
    for (int i = 0; i < FILES; ++i) {
        assert(snprintf(paths[i], sizeof(paths[i]), "%s/%d", directory, i) > 0);
        int fd = open(paths[i], O_WRONLY | O_CREAT | O_EXCL, 0600);
        assert(fd >= 0);
        assert(write(fd, expected, sizeof(expected)) == sizeof(expected));
        assert(close(fd) == 0);
    }
    /* One untimed warmup uses the same stdio path as the measured reads. */
    double elapsed = 0;
    for (int pass = -1; pass < PASSES; ++pass) {
        double start = milliseconds();
        for (int i = 0; i < FILES; ++i) {
            FILE *stream = fopen(paths[i], "rb");
            assert(stream);
            assert(fread(actual, 1, sizeof(actual), stream) == BYTES);
            assert(feof(stream) && !ferror(stream));
            assert(memcmp(actual, expected, BYTES) == 0);
            assert(fclose(stream) == 0);
        }
        if (pass >= 0) elapsed += milliseconds() - start;
    }
    for (int i = 0; i < FILES; ++i) assert(unlink(paths[i]) == 0);
    assert(rmdir(directory) == 0);
    printf("{\"operations\":%d,\"bytes_per_file\":%d,\"elapsed_ms\":%.6f,"
           "\"verified\":true}\n", FILES * PASSES, BYTES, elapsed);
    return 0;
}
