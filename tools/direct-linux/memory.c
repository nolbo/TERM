/* SPDX-License-Identifier: Apache-2.0 */
/* Guest mappings are owned independently of the surviving APK runtime. */
#define _GNU_SOURCE
#include "threads.h"
#include <errno.h>
#include <sys/mman.h>
#include <sys/syscall.h>

#define MAP_LIMIT 2048
enum inheritance { INHERIT, OMIT, CHECK_FORK };
static struct region { uintptr_t begin, end; enum inheritance inherit; } regions[MAP_LIMIT];
static struct region fork_regions[MAP_LIMIT];
static size_t count;

static void coalesce(void)
{
    for (size_t i = 0; i < count; i++) {
        for (size_t j = i + 1; j < count;) {
            if (regions[i].inherit != regions[j].inherit || regions[i].end < regions[j].begin ||
                    regions[j].end < regions[i].begin) { j++; continue; }
            if (regions[j].begin < regions[i].begin) regions[i].begin = regions[j].begin;
            if (regions[j].end > regions[i].end) regions[i].end = regions[j].end;
            regions[j] = regions[--count]; j = i + 1;
        }
    }
}

static void remove_range(uintptr_t begin, uintptr_t end)
{
    for (size_t i = 0; i < count;) {
        struct region old = regions[i];
        if (end <= old.begin || begin >= old.end) { i++; continue; }
        if (begin <= old.begin && end >= old.end) { regions[i] = regions[--count]; continue; }
        if (begin > old.begin && end < old.end) regions[count++] = (struct region){end, old.end, old.inherit};
        if (begin > old.begin) regions[i].end = begin;
        else regions[i].begin = end;
        i++;
    }
}

static long track(uintptr_t address, size_t size)
{
    if (count >= MAP_LIMIT) return -ENOMEM;
    size = (size + dl_state.page - 1) & ~(dl_state.page - 1);
    uintptr_t end = address + size;
    // Ownership does not depend on current protection or file backing. Merge
    // adjacent owned intervals so exec can unmap each contiguous span once.
    // An untracked hole is never crossed (it may hold surviving runtime data).
    for (size_t i = 0; i < count;) {
        if (regions[i].inherit != INHERIT || end < regions[i].begin || address > regions[i].end) { i++; continue; }
        if (regions[i].begin < address) address = regions[i].begin;
        if (regions[i].end > end) end = regions[i].end;
        regions[i] = regions[--count]; i = 0;
    }
    regions[count++] = (struct region){address, end, INHERIT};
    return 0;
}

static long map(uintptr_t address, size_t size, int prot, int flags, int fd, unsigned long offset)
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
        track((uintptr_t)r, rounded);
    }
    return r;
}

static long unmap(uintptr_t address, size_t size)
{
    if (!size || size > UINTPTR_MAX - dl_state.page || address > UINTPTR_MAX - size) return -EINVAL;
    if (count == MAP_LIMIT) return -ENOMEM;
    long r = DL_CALL(SYS_munmap, address, size, 0, 0, 0, 0);
    if (!r) remove_range(address, address + ((size + dl_state.page - 1) & ~(dl_state.page - 1)));
    return r;
}

void dl_unmap_all(void)
{
    dl_lock(&dl_memory_lock);
    for (size_t i = 0; i < count; i++)
        DL_CALL(SYS_munmap, regions[i].begin, regions[i].end - regions[i].begin, 0, 0, 0, 0);
    count = 0;
    dl_unlock(&dl_memory_lock);
}

long dl_track(uintptr_t address, size_t size)
{
    dl_lock(&dl_memory_lock); long r = track(address, size); dl_unlock(&dl_memory_lock); return r;
}
long dl_mmap(uintptr_t address, size_t size, int prot, int flags, int fd, unsigned long offset)
{
    dl_lock(&dl_memory_lock); long r = map(address, size, prot, flags, fd, offset); dl_unlock(&dl_memory_lock); return r;
}
long dl_munmap(uintptr_t address, size_t size)
{
    dl_lock(&dl_memory_lock); long r = unmap(address, size); dl_unlock(&dl_memory_lock); return r;
}

long dl_madvise(uintptr_t address, size_t size, int advice)
{
    if ((advice != MADV_DONTFORK && advice != MADV_DOFORK) || !size ||
            (address & (dl_state.page - 1)) || size > UINTPTR_MAX - dl_state.page ||
            address > UINTPTR_MAX - ((size + dl_state.page - 1) & ~(dl_state.page - 1)))
        return DL_CALL(SYS_madvise, address, size, advice, 0, 0, 0);
    uintptr_t end = address + ((size + dl_state.page - 1) & ~(dl_state.page - 1));
    dl_lock(&dl_memory_lock);
    size_t splits = 0;
    for (size_t i = 0; i < count; i++) {
        struct region old = regions[i];
        if (end <= old.begin || address >= old.end) continue;
        splits += address > old.begin;
        splits += end < old.end;
    }
    if (splits > MAP_LIMIT - count) { dl_unlock(&dl_memory_lock); return -ENOMEM; }
    long r = DL_CALL(SYS_madvise, address, size, advice, 0, 0, 0);
    // madvise can change earlier VMAs and still fail on a hole or a later VMA.
    // Do not infer the whole range's state from errno. Resolve uncertain pages
    // in the fork child before an untracked runtime allocation can reuse them.
    enum inheritance inherit = r ? CHECK_FORK : advice == MADV_DONTFORK ? OMIT : INHERIT;
    size_t original = count;
    for (size_t i = 0; i < original; i++) {
        struct region old = regions[i];
        if (end <= old.begin || address >= old.end) continue;
        if (address > old.begin) regions[count++] = (struct region){old.begin, address, old.inherit};
        if (end < old.end) regions[count++] = (struct region){end, old.end, old.inherit};
        regions[i] = (struct region){address > old.begin ? address : old.begin,
            end < old.end ? end : old.end, inherit};
    }
    coalesce();
    dl_unlock(&dl_memory_lock); return r;
}

long dl_memory_fork_begin(void)
{
    dl_lock(&dl_memory_lock);
    size_t needed = 0;
    for (size_t i = 0; i < count; i++) {
        size_t slots = regions[i].inherit == CHECK_FORK
            ? (regions[i].end - regions[i].begin) / dl_state.page : regions[i].inherit != OMIT;
        // Reserve the worst case before fork, while failure can still return
        // normally to the parent. The child never allocates scratch mappings.
        if (slots > MAP_LIMIT - needed) { dl_unlock(&dl_memory_lock); return -ENOMEM; }
        needed += slots;
    }
    return 0;
}

void dl_memory_fork_end(int child)
{
    if (child) {
        size_t kept = 0;
        for (size_t i = 0; i < count; i++) {
            struct region old = regions[i];
            if (old.inherit == OMIT) continue;
            if (old.inherit == INHERIT) { fork_regions[kept++] = old; continue; }
            uintptr_t begin = 0;
            for (uintptr_t p = old.begin; p < old.end; p += dl_state.page) {
                unsigned char vector;
                long r = DL_CALL(SYS_mincore, p, dl_state.page, &vector, 0, 0, 0);
                if (r && r != -ENOMEM) {
                    // Never resume with unverified ownership under a policy
                    // which prevents reconciliation of a partially failed hint.
                    DL_CALL(SYS_exit_group, 126, 0, 0, 0, 0, 0); __builtin_unreachable();
                }
                if (!r) { if (!begin) begin = p; }
                else if (begin) { fork_regions[kept++] = (struct region){begin, p, INHERIT}; begin = 0; }
            }
            if (begin) fork_regions[kept++] = (struct region){begin, old.end, INHERIT};
        }
        for (size_t i = 0; i < kept; i++) regions[i] = fork_regions[i];
        count = kept;
        coalesce();
    }
    dl_unlock(&dl_memory_lock);
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
