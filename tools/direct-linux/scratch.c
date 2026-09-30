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
};

long dl_scratch_map(struct dl_scratch **owner, size_t size, int protection)
{
    size_t page = dl_state.page;
    if (!size || size > SIZE_MAX - 2 * page) return -ENOMEM;
    size = (size + page - 1) & ~(page - 1);
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
    size_t size = dl_state.page + entry->size;
    unlink_entry(entry);
    DL_CALL(SYS_munmap, entry, size, 0, 0, 0, 0);
}

void dl_scratch_detach(void *address)
{
    // Commit transfers the payload to the guest mapping registry. Its header
    // is no longer needed; task cleanup must never unmap this guest memory.
    struct dl_scratch *entry = (void *)((char *)address - dl_state.page);
    unlink_entry(entry);
    DL_CALL(SYS_munmap, entry, dl_state.page, 0, 0, 0, 0);
}

void dl_scratch_clear(struct dl_scratch **owner)
{
    while (*owner) dl_scratch_unmap((char *)*owner + dl_state.page);
}
