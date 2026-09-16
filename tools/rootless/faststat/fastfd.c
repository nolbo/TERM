/* SPDX-License-Identifier: GPL-2.0-or-later */
/* Tracer side of the experimental per-thread FD identity page. */
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#include <talloc.h>
#include "tracee/tracee.h"
#include "tracee/faststat.h"
#include "extension/extension.h"
#include "extension/fake_id0/config.h"
#include "arch.h"

struct fastfd_state {
    struct proot_fastfd_identity *page;
    uint32_t host_uid, host_gid, guest_uid, guest_gid, active;
};

static int release_page(struct fastfd_state *state)
{
    munmap(state->page, PROOT_FASTFD_MAPPING_SIZE);
    return 0;
}

static bool compatible(Tracee *tracee)
{
    if (tracee->seccomp != ENABLED || is_32on64_mode(tracee)) return false;
    Extension *extension;
    if (tracee->extensions)
        LIST_FOREACH(extension, tracee->extensions, link)
            if (extension->callback != fake_id0_callback && extension->callback != mountinfo_callback)
                return false;
    return true;
}

void fastfd_reset(Tracee *tracee)
{
    TALLOC_FREE(tracee->fastfd);
}

void fastfd_sync(Tracee *tracee)
{
    struct fastfd_state *state = tracee->fastfd;
    if (!state) return;
    uint32_t uid = state->host_uid, gid = state->host_gid;
    uint32_t active = compatible(tracee);
    Extension *identity = get_extension(tracee, fake_id0_callback);
    if (identity) {
        Config *config = identity->config;
        uid = config->suid;
        gid = config->sgid;
    }
    if (state->active == active && state->guest_uid == uid && state->guest_gid == gid) return;
    struct proot_fastfd_identity *page = state->page;
    atomic_fetch_add_explicit(&page->sequence, 1, memory_order_seq_cst);
    atomic_store_explicit(&page->host_uid, state->host_uid, memory_order_seq_cst);
    atomic_store_explicit(&page->host_gid, state->host_gid, memory_order_seq_cst);
    atomic_store_explicit(&page->guest_uid, uid, memory_order_seq_cst);
    atomic_store_explicit(&page->guest_gid, gid, memory_order_seq_cst);
    atomic_store_explicit(&page->active, active, memory_order_seq_cst);
    atomic_fetch_add_explicit(&page->sequence, 1, memory_order_seq_cst);
    state->guest_uid = uid;
    state->guest_gid = gid;
    state->active = active;
}

int fastfd_attach(Tracee *tracee, int guest_fd, unsigned version)
{
    const char *enabled = getenv("PROOT_FASTFD");
    if (!enabled || strcmp(enabled, "1") || version != PROOT_FASTFD_VERSION || !compatible(tracee))
        return -EOPNOTSUPP;
    if (guest_fd < 0) return -EBADF;
    char path[64];
    snprintf(path, sizeof(path), "/proc/%d/fd/%d", tracee->pid, guest_fd);
    int fd = open(path, O_RDWR | O_CLOEXEC);
    if (fd < 0) return -errno;
    int seals = fcntl(fd, F_GET_SEALS);
    struct stat info;
    int error = 0;
    /* Check seals before size: another thread cannot truncate the mapping
     * after validation and cause SIGBUS in the tracer. */
    if (seals < 0 || (seals & (F_SEAL_GROW | F_SEAL_SHRINK)) != (F_SEAL_GROW | F_SEAL_SHRINK))
        error = -EINVAL;
    else if (fstat(fd, &info)) error = -errno;
    else if (!S_ISREG(info.st_mode) || info.st_size != PROOT_FASTFD_MAPPING_SIZE) error = -EINVAL;
    void *page = MAP_FAILED;
    if (!error) {
        page = mmap(NULL, PROOT_FASTFD_MAPPING_SIZE, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
        if (page == MAP_FAILED) error = -errno;
    }
    close(fd);
    if (error) return error;
    struct fastfd_state *state = talloc_zero(tracee, struct fastfd_state);
    if (!state) { munmap(page, PROOT_FASTFD_MAPPING_SIZE); return -ENOMEM; }
    state->page = page;
    state->host_uid = getuid();
    state->host_gid = getgid();
    talloc_set_destructor(state, release_page);
    fastfd_reset(tracee);
    tracee->fastfd = state;
    atomic_store(&state->page->sequence, 0);
    fastfd_sync(tracee);
    return 0;
}
