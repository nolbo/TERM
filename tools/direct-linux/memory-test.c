/* SPDX-License-Identifier: Apache-2.0 */
#define _GNU_SOURCE
#include "runtime.h"
#include <assert.h>
#include <errno.h>
#include <stdio.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <unistd.h>

struct dl_state dl_state;
static unsigned unmaps;
long dl_host_syscall(long n, long a, long b, long c, long d, long e, long f)
{
    if (n == SYS_munmap) unmaps++;
    long result = syscall(n, a, b, c, d, e, f);
    return result < 0 ? -errno : result;
}
static int mapped(uintptr_t address)
{
    unsigned char page;
    return mincore((void *)address, dl_state.page, &page) == 0;
}
int main(void)
{
    size_t page = dl_state.page = sysconf(_SC_PAGESIZE);
    long memory = dl_mmap(0, page * 6, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    assert(memory > 0);
    // Repeated segmented ELF population must remain a single owned span, even
    // when protections/backing differ; this avoids repeated exec TLB teardown.
    for (unsigned i = 0; i < 100; i++) {
        assert(dl_mmap(memory + page, page, PROT_READ | PROT_WRITE,
            MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED, -1, 0) == memory + (long)page);
        assert(dl_mmap(memory + page * 4, page, PROT_READ,
            MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED, -1, 0) == memory + (long)page * 4);
    }
    unmaps = 0; dl_unmap_all();
    assert(unmaps == 1 && !mapped(memory) && !mapped(memory + page * 5));
    // Remove each possible page, let an unrelated host mapping occupy the hole,
    // then dispose all guest memory. No coalescing may swallow the host mapping.
    for (unsigned hole = 0; hole < 6; hole++) {
        memory = dl_mmap(0, page * 6, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
        assert(memory > 0);
        uintptr_t address = memory + page * hole;
        assert(!dl_munmap(address, page));
        void *host = mmap((void *)address, page, PROT_READ | PROT_WRITE,
            MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED_NOREPLACE, -1, 0);
        assert(host == (void *)address);
        *(unsigned char *)host = 0x69;
        assert(dl_mmap(address, page, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED, -1, 0) == -EACCES);
        dl_unmap_all();
        for (unsigned i = 0; i < 6; i++) assert(mapped(memory + page * i) == (i == hole));
        assert(*(unsigned char *)host == 0x69 && !munmap(host, page));
    }
    puts("PASS guest mapping coalescing, replacement, unmap splits and host holes");
    return 0;
}
