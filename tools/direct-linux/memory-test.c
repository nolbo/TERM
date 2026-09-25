/* SPDX-License-Identifier: Apache-2.0 */
#define _GNU_SOURCE
#include "runtime.h"
#include <assert.h>
#include <errno.h>
#include <stdio.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>

struct dl_state dl_state;
unsigned dl_memory_lock;
void dl_lock(unsigned *lock) { assert(!*lock); *lock = 1; }
void dl_unlock(unsigned *lock) { assert(*lock); *lock = 0; }
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
static void inheritance(unsigned scenario)
{
    size_t page = dl_state.page;
    long memory = dl_mmap(0, page * 5, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    assert(memory > 0);
    *(char *)memory = 42;
    unsigned missing = 1U << 2;
    if (scenario == 0) assert(!dl_madvise(memory + page * 2, page, MADV_DONTFORK));
    if (scenario == 1) {
        assert(!dl_madvise(memory + page, page * 3, MADV_DONTFORK));
        assert(!dl_madvise(memory + page * 2, page, MADV_DOFORK));
        missing = (1U << 1) | (1U << 3);
    }
    if (scenario == 2) {
        assert(!dl_munmap(memory + page * 2, page));
        assert(dl_madvise(memory, page * 5, MADV_DONTFORK) == -ENOMEM);
        missing = 31;
    }
    if (scenario == 3) {
        assert(!dl_madvise(memory, page * 5, MADV_DONTFORK));
        assert(!dl_munmap(memory + page * 2, page));
        assert(dl_madvise(memory, page * 5, MADV_DOFORK) == -ENOMEM);
    }
    if (scenario == 4) {
        assert(!dl_madvise(memory, page * 5, MADV_WIPEONFORK)); missing = 0;
    }
    assert(!dl_memory_fork_begin());
    pid_t child = fork(); assert(child >= 0);
    dl_memory_fork_end(child == 0);
    if (!child) {
        for (unsigned i = 0; i < 5; i++) {
            uintptr_t address = memory + page * i;
            assert(mapped(address) == !(missing & (1U << i)));
            if (missing & (1U << i)) {
                assert(mmap((void *)address, page, PROT_READ | PROT_WRITE,
                    MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED_NOREPLACE, -1, 0) == (void *)address);
                *(char *)address = 69;
            }
        }
        if (scenario == 4) assert(*(char *)memory == 0);
        dl_unmap_all();
        for (unsigned i = 0; i < 5; i++) {
            uintptr_t address = memory + page * i;
            assert(mapped(address) == !!(missing & (1U << i)));
            if (missing & (1U << i)) assert(*(char *)address == 69);
        }
        _exit(0);
    }
    int status; assert(waitpid(child, &status, 0) == child && WIFEXITED(status) && WEXITSTATUS(status) == 0);
    assert(*(char *)memory == 42);
    dl_unmap_all();
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
    for (unsigned scenario = 0; scenario < 5; scenario++) inheritance(scenario);
    puts("PASS DONTFORK/DOFORK/WIPEONFORK, partial errors, child reconciliation and fresh runtime mapping survival");
    return 0;
}
