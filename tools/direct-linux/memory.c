/* SPDX-License-Identifier: Apache-2.0 */
/* Guest mappings are owned independently of the surviving APK runtime. */
#define _GNU_SOURCE
#include "runtime.h"
#include <errno.h>
#include <sys/mman.h>
#include <sys/syscall.h>

#define MAP_LIMIT 2048
static struct region { uintptr_t begin, end; } regions[MAP_LIMIT];
static size_t count;

static void remove_range(uintptr_t begin, uintptr_t end)
{
    for (size_t i = 0; i < count;) {
        struct region old = regions[i];
        if (end <= old.begin || begin >= old.end) { i++; continue; }
        if (begin <= old.begin && end >= old.end) { regions[i] = regions[--count]; continue; }
        if (begin > old.begin && end < old.end) regions[count++] = (struct region){end, old.end};
        if (begin > old.begin) regions[i].end = begin;
        else regions[i].begin = end;
        i++;
    }
}

long dl_track(uintptr_t address, size_t size)
{
    if (count >= MAP_LIMIT) return -ENOMEM;
    size = (size + dl_state.page - 1) & ~(dl_state.page - 1);
    uintptr_t end = address + size;
    // Ownership does not depend on current protection or file backing. Merge
    // adjacent owned intervals so exec can unmap each contiguous span once.
    // An untracked hole is never crossed (it may hold surviving runtime data).
    for (size_t i = 0; i < count;) {
        if (end < regions[i].begin || address > regions[i].end) { i++; continue; }
        if (regions[i].begin < address) address = regions[i].begin;
        if (regions[i].end > end) end = regions[i].end;
        regions[i] = regions[--count]; i = 0;
    }
    regions[count++] = (struct region){address, end};
    return 0;
}

long dl_mmap(uintptr_t address, size_t size, int prot, int flags, int fd, unsigned long offset)
{
    if (!size || size > UINTPTR_MAX - dl_state.page || address > UINTPTR_MAX - size) return -EINVAL;
    size_t rounded = (size + dl_state.page - 1) & ~(dl_state.page - 1);
    if (count > MAP_LIMIT - 2) return -ENOMEM;
    if (flags & MAP_FIXED) {
        // A guest MAP_FIXED may replace its reservations, not APK runtime state.
        uintptr_t covered = address;
        for (;;) {
            uintptr_t next = covered;
            for (size_t i = 0; i < count; i++)
                if (regions[i].begin <= covered && regions[i].end > next) next = regions[i].end;
            if (next == covered) break;
            covered = next;
        }
        if (covered < address + rounded) return -EACCES;
    }
    long r = DL_CALL(SYS_mmap, address, size, prot, flags, fd, offset);
    if (r >= 0) {
        remove_range((uintptr_t)r, r + rounded);
        dl_track((uintptr_t)r, rounded);
    }
    return r;
}

long dl_munmap(uintptr_t address, size_t size)
{
    if (!size || size > UINTPTR_MAX - dl_state.page || address > UINTPTR_MAX - size) return -EINVAL;
    if (count == MAP_LIMIT) return -ENOMEM;
    long r = DL_CALL(SYS_munmap, address, size, 0, 0, 0, 0);
    if (!r) remove_range(address, address + ((size + dl_state.page - 1) & ~(dl_state.page - 1)));
    return r;
}

void dl_unmap_all(void)
{
    for (size_t i = 0; i < count; i++)
        DL_CALL(SYS_munmap, regions[i].begin, regions[i].end - regions[i].begin, 0, 0, 0, 0);
    count = 0;
}

// AArch64 cache maintenance with no compiler-rt/libc dependency.
void dl_clear_cache(uintptr_t begin, uintptr_t end)
{
    unsigned long ctr;
    __asm__ volatile("mrs %0, ctr_el0" : "=r"(ctr));
    size_t dline = 4UL << ((ctr >> 16) & 15), iline = 4UL << (ctr & 15);
    if (!(ctr & (1UL << 28)))
        for (uintptr_t p = begin & ~(dline - 1); p < end; p += dline)
            __asm__ volatile("dc cvau, %0" :: "r"(p) : "memory");
    __asm__ volatile("dsb ish" ::: "memory");
    if (!(ctr & (1UL << 29)))
        for (uintptr_t p = begin & ~(iline - 1); p < end; p += iline)
            __asm__ volatile("ic ivau, %0" :: "r"(p) : "memory");
    __asm__ volatile("dsb ish\nisb" ::: "memory");
}
