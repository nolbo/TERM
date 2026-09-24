/* SPDX-License-Identifier: Apache-2.0 */
/* Host ARM64 regression for the identical freestanding assembly used on Android. */
#define _GNU_SOURCE
#include <assert.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>
#include "runtime.h"

int main(void)
{
    unsigned char source[66048], destination[66048];
    for (unsigned i = 0; i < sizeof(source); i++) source[i] = (i * 37) ^ (i >> 8);
    // Every short length and pair of source/destination alignments; canaries
    // verify that tails do not clobber adjacent bytes.
    for (size_t size = 0; size <= 257; size++) {
        for (unsigned from = 0; from < 32; from++) for (unsigned to = 0; to < 32; to++) {
            memset(destination, 0xa5, 320);
            dl_copy(destination + 32 + to, source + from, size);
            assert(!memcmp(destination + 32 + to, source + from, size));
            assert(dl_equal_memory(destination + 32 + to, source + from, size));
            if (size) {
                destination[32 + to + size / 2] ^= 1;
                assert(!dl_equal_memory(destination + 32 + to, source + from, size));
                destination[32 + to + size / 2] ^= 1;
            }
            for (unsigned i = 0; i < 32 + to; i++) assert(destination[i] == 0xa5);
            for (unsigned i = 32 + to + size; i < 320; i++) assert(destination[i] == 0xa5);
        }
    }
    const size_t lengths[] = {511, 512, 513, 4095, 4096, 4097, 65535, 65536};
    for (unsigned k = 0; k < sizeof(lengths) / sizeof(lengths[0]); k++) {
        size_t size = lengths[k];
        for (unsigned offset = 0; offset < 32; offset++) {
            memset(destination, 0xa5, sizeof(destination));
            dl_copy(destination + offset, source + 31 - offset, size);
            assert(!memcmp(destination + offset, source + 31 - offset, size));
            for (size_t i = offset + size; i < sizeof(destination); i++) assert(destination[i] == 0xa5);
        }
    }
    size_t page = (size_t)sysconf(_SC_PAGESIZE);
    unsigned char *a = mmap(NULL, page * 3, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    unsigned char *b = mmap(NULL, page * 3, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    assert(a != MAP_FAILED && b != MAP_FAILED);
    assert(!mprotect(a + page, page, PROT_READ | PROT_WRITE));
    assert(!mprotect(b + page, page, PROT_READ | PROT_WRITE));
    memset(a + page, 0x69, page);
    for (size_t size = 0; size <= page; size++) {
        // Includes zero length with both pointers on an inaccessible page.
        dl_copy(b + 2 * page - size, a + 2 * page - size, size);
        assert(!memcmp(b + 2 * page - size, a + 2 * page - size, size));
        assert(dl_equal_memory(b + 2 * page - size, a + 2 * page - size, size));
    }
    assert(!munmap(a, page * 3) && !munmap(b, page * 3));
    puts("PASS ARM64 copy: all short alignments, long tails, guarded page bounds");
    return 0;
}
