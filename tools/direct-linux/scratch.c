/* SPDX-License-Identifier: Apache-2.0 */
/* Task-owned preparation memory, distinct from committed guest mappings. */
#define _GNU_SOURCE
#include "runtime.h"
#include <errno.h>
#include <sys/mman.h>
#include <sys/syscall.h>

struct dl_scratch {
    struct dl_scratch **owner;
    struct dl_scratch *previous, *next;
    size_t size;
    struct dl_scratch *arena;
    size_t used;
};

long dl_scratch_reserve(struct dl_scratch **owner, size_t size)
{
    size_t page = dl_state.page;
    if (*owner || !size || size > SIZE_MAX - 2 * page) return -EINVAL;
    size = (size + page - 1) & ~(page - 1);
    long address = DL_CALL(SYS_mmap, 0, page + size, PROT_READ | PROT_WRITE,
        MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (address < 0) return address;
    struct dl_scratch *arena = (void *)address;
    arena->owner = owner; arena->size = size; arena->arena = arena;
    *owner = arena; return 0;
}

void dl_scratch_rewind(struct dl_scratch **owner)
{
    if (*owner && (*owner)->arena == *owner) (*owner)->used = 0;
}

long dl_scratch_map(struct dl_scratch **owner, size_t size, int protection)
{
    size_t page = dl_state.page;
    if (!size || size > SIZE_MAX - 2 * page) return -ENOMEM;
    size = (size + page - 1) & ~(page - 1);
    struct dl_scratch *arena = *owner;
    if (arena && arena->arena == arena) {
        if (page + size > arena->size - arena->used) return -ENOMEM;
        long address = (long)arena + page + arena->used;
        long r = DL_CALL(SYS_mprotect, address, page, PROT_READ | PROT_WRITE, 0, 0, 0);
        if (!r) r = DL_CALL(SYS_mprotect, address + page, size, protection, 0, 0, 0);
        if (!r) r = DL_CALL(SYS_madvise, address, page + size, MADV_DONTNEED, 0, 0, 0);
        if (r) return r;
        struct dl_scratch *entry = (void *)address;
        *entry = (struct dl_scratch){.owner = owner, .size = size, .arena = arena};
        // Every extent is already parent-owned. An interrupted bump/update
        // cannot orphan a mapping or corrupt the parent's reclamation list.
        arena->used += page + size;
        return address + page;
    }
    long address = DL_CALL(SYS_mmap, 0, page + size, PROT_READ | PROT_WRITE,
        MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (address < 0) return address;
    // A separate header page leaves both stack guards intact and permits
    // cleanup without reading a PROT_NONE payload. Publish only after success.
    if (protection != (PROT_READ | PROT_WRITE)) {
        long r = DL_CALL(SYS_mprotect, address + page, size, protection, 0, 0, 0);
        if (r) { DL_CALL(SYS_munmap, address, page + size, 0, 0, 0, 0); return r; }
    }
    struct dl_scratch *entry = (void *)address;
    entry->owner = owner; entry->size = size;
    entry->previous = NULL; entry->next = *owner;
    if (*owner) (*owner)->previous = entry;
    *owner = entry;
    return address + page;
}

static void unlink_entry(struct dl_scratch *entry)
{
    if (entry->previous) entry->previous->next = entry->next;
    else *entry->owner = entry->next;
    if (entry->next) entry->next->previous = entry->previous;
}

void dl_scratch_unmap(void *address)
{
    struct dl_scratch *entry = (void *)((char *)address - dl_state.page);
    if (entry->arena) return; // arena reset/owner teardown releases all extents
    size_t size = dl_state.page + entry->size;
    unlink_entry(entry);
    DL_CALL(SYS_munmap, entry, size, 0, 0, 0, 0);
}

void dl_scratch_detach(void *address)
{
    // Commit transfers the payload to the guest mapping registry. Its header
    // is no longer needed; task cleanup must never unmap this guest memory.
    struct dl_scratch *entry = (void *)((char *)address - dl_state.page);
    // A borrowed arena must use kernel exec, never an in-process commit.
    if (entry->arena) { DL_CALL(SYS_exit_group, 126, 0, 0, 0, 0, 0); return; }
    unlink_entry(entry);
    DL_CALL(SYS_munmap, entry, dl_state.page, 0, 0, 0, 0);
}

void dl_scratch_clear(struct dl_scratch **owner)
{
    if (*owner && (*owner)->arena == *owner) {
        struct dl_scratch *arena = *owner;
        *owner = NULL;
        DL_CALL(SYS_munmap, arena, dl_state.page + arena->size, 0, 0, 0, 0); return;
    }
    while (*owner) dl_scratch_unmap((char *)*owner + dl_state.page);
}
