/* SPDX-License-Identifier: GPL-2.0-or-later */
/* Tracer side of the experimental per-thread FD identity page. */
#include <errno.h>
#include <fcntl.h>
#include <linux/magic.h>
#include <sys/vfs.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#include <talloc.h>
#include "tracee/tracee.h"
#include "tracee/reg.h"
#include "tracee/abi.h"
#include "tracee/faststat.h"
#include "extension/extension.h"
#include "extension/fake_id0/config.h"
#include "arch.h"
#include "path/binding.h"
#include "syscall/sysnum.h"

struct fastfd_state {
    struct proot_fastfd_identity *page;
    uint32_t host_uid, host_gid, guest_uid, guest_gid, active;
    uint64_t path_epoch;
    bool path_requested;
    int storage_fd;
    const Bindings *bindings;
};

static uint64_t path_epoch = 1;
/* Conservative across the whole traced tree, including later execs. */
static bool async_fd_seen;
static bool guest_exec_seen;

/* Ignore only PRoot bootstrap filter installation before the first guest exec. */
void fastfd_guest_exec(void) { guest_exec_seen = true; }

/* Binding lists may be shared by distinct FileSystemNameSpace objects.
 * Invalidate before mutation, including pages used by running siblings.
 * The existing event-loop/notification lock serializes all writers. */
void fastpath_invalidate_all(void)
{
    path_epoch++;
    Tracees *head = get_tracees_list_head();
    if (!head) return;
    Tracee *tracee;
    LIST_FOREACH(tracee, head, link) {
        if (!tracee->fastfd || !tracee->fastfd->path_requested) continue;
        struct proot_fastpath_rules *page = &tracee->fastfd->page->paths;
        atomic_store(&page->active, 0);
        atomic_fetch_add(&page->sequence, 2);
    }
}

/* Preserve the link mode (1 native, 2 emulated) when comparing publication
 * state. Converting it to bool republishes every emulated syscall resume. */
static void publish_paths(Tracee *tracee, struct fastfd_state *state, uint32_t active)
{
    const Bindings *bindings = tracee->fs->bindings.guest;
    if (!state->path_requested || (state->path_epoch == path_epoch && state->bindings == bindings &&
                                  state->active == active)) return;
    state->path_epoch = path_epoch;
    state->bindings = bindings;
    struct proot_fastpath_rules *page = &state->page->paths;
    atomic_fetch_add(&page->sequence, 1);
    atomic_store(&page->active, 0);
    unsigned count = 0, used = 0;
    const char *cwd = tracee->fs->cwd;
    size_t cwd_length = cwd ? strlen(cwd) : 0;
    bool root = false, valid = active && bindings && cwd_length &&
        cwd_length < sizeof(page->cwd) && cwd[0] == '/';
    if (valid)
        for (size_t i = 0; i <= cwd_length; i++)
            atomic_store_explicit(&page->cwd[i], cwd[i], memory_order_relaxed);
    if (valid) {
        const Binding *binding;
        CIRCLEQ_FOREACH(binding, bindings, link.guest) {
            size_t gl = strlen(binding->guest.path), hl = strlen(binding->host.path);
            if (count == PROOT_FASTPATH_BINDINGS || used + gl + hl + 2 > PROOT_FASTPATH_BYTES ||
                !gl || !hl || binding->guest.path[0] != '/' || binding->host.path[0] != '/') {
                valid = false;
                break;
            }
            struct proot_fastpath_binding *item = &page->bindings[count++];
            atomic_store(&item->guest, used);
            atomic_store(&item->guest_length, gl);
            for (size_t i = 0; i <= gl; i++) atomic_store_explicit(&page->paths[used++], binding->guest.path[i], memory_order_relaxed);
            atomic_store(&item->host, used);
            atomic_store(&item->host_length, hl);
            for (size_t i = 0; i <= hl; i++) atomic_store_explicit(&page->paths[used++], binding->host.path[i], memory_order_relaxed);
            if (gl == 1) root = true;
        }
    }
    atomic_store(&page->count, count);
    atomic_store(&page->active, valid && root);
    atomic_fetch_add(&page->sequence, 1);
}

/* Keep a reference to the classified superblock so its device number cannot
 * be recycled while the guest relies on it. Guest FDs never own this reference.
 * Other devices still require a live fstatfs; mount/binding changes therefore
 * cannot make a procfs file look like an ordinary file on this device. */
static void publish_storage(Tracee *tracee, struct fastfd_state *state)
{
    const char *setting = getenv("PROOT_FASTSTORAGE");
    atomic_store(&state->page->storage_active, 0);
    if (!state->path_requested || (setting && !strcmp(setting, "0"))) return;
    const Bindings *bindings = tracee->fs->bindings.guest;
    if (!bindings) return;
    const Binding *binding;
    CIRCLEQ_FOREACH(binding, bindings, link.guest) {
        if (strcmp(binding->guest.path, "/")) continue;
        int fd = open(binding->host.path, O_PATH | O_DIRECTORY | O_CLOEXEC);
        if (fd < 0) return;
        struct stat info;
        struct statfs filesystem;
        if (fstat(fd, &info) || fstatfs(fd, &filesystem)) { close(fd); return; }
        switch (filesystem.f_type) {
        case EXT4_SUPER_MAGIC: case F2FS_SUPER_MAGIC: case TMPFS_MAGIC:
        case XFS_SUPER_MAGIC: case BTRFS_SUPER_MAGIC: case OVERLAYFS_SUPER_MAGIC:
        case EROFS_SUPER_MAGIC_V1: case SQUASHFS_MAGIC:
            state->storage_fd = fd;
            atomic_store(&state->page->storage_dev_low, (uint32_t)info.st_dev);
            atomic_store(&state->page->storage_dev_high, (uint32_t)((uint64_t)info.st_dev >> 32));
            atomic_store(&state->page->storage_active, 1);
            return;
        default:
            close(fd);
            return;
        }
    }
}

static int release_page(struct fastfd_state *state)
{
    atomic_store(&state->page->storage_active, 0);
    if (state->storage_fd >= 0) close(state->storage_fd);
    munmap(state->page, PROOT_FASTFD_MAPPING_SIZE);
    return 0;
}

static bool compatible(Tracee *tracee)
{
    if (tracee->seccomp != ENABLED || is_32on64_mode(tracee)) return false;
    Extension *extension;
    if (tracee->extensions)
        LIST_FOREACH(extension, tracee->extensions, link)
            if (extension->callback != fake_id0_callback && extension->callback != mountinfo_callback &&
                extension->callback != link2symlink_callback)
                return false;
    return true;
}

void fastfd_reset(Tracee *tracee)
{
    TALLOC_FREE(tracee->fastfd);
}

/* Monotonic until exec: a suspended fstat cannot see re-enabled eligibility. */
void fastfd_signal(Tracee *tracee)
{
    tracee->fastfd_single_disabled = true;
    if (tracee->fastfd) atomic_store(&tracee->fastfd->page->single_safe, 0);
}

static void disable_async_fd(void)
{
    async_fd_seen = true;
    Tracees *head = get_tracees_list_head();
    if (!head) return;
    Tracee *item;
    LIST_FOREACH(item, head, link) {
        fastfd_signal(item);
        if (item->fastfd) atomic_store(&item->fastfd->page->pin_safe, 0);
    }
}

void fastclose_observe_enter(Tracee *tracee)
{
#if defined(__aarch64__)
    word_t number = peek_reg(tracee, ORIGINAL, SYSARG_NUM);
    if (guest_exec_seen && !is_32on64_mode(tracee) &&
        ((number >= 425 && number <= 427) || number == 277 ||
         /* prctl's option is an int; the kernel ignores its high word. */
         (number == 167 && (uint32_t)peek_reg(tracee, ORIGINAL, SYSARG_1) == 22 &&
          peek_reg(tracee, ORIGINAL, SYSARG_2) == 2))) {
        disable_async_fd();
    }
#endif
    switch (get_sysnum(tracee, ORIGINAL)) {
    case PR_clone: case PR_clone3: case PR_fork: case PR_vfork: break;
    default: return;
    }
    /* Invalidate before the kernel can create a descriptor-table sharer.
     * New tracees start ineligible; only a later exec can enable them. */
    Tracees *head = get_tracees_list_head();
    Tracee *item;
    LIST_FOREACH(item, head, link) {
        item->fastclose_exec_isolated = false;
        if (item->fastfd) {
            atomic_store(&item->fastfd->page->close_safe, 0);
            atomic_store(&item->fastfd->page->single_safe, 0);
            atomic_store(&item->fastfd->page->pin_safe, 0);
        }
    }
}

void fastfd_sync(Tracee *tracee)
{
    struct fastfd_state *state = tracee->fastfd;
    if (!state) return;
    uint32_t uid = state->host_uid, gid = state->host_gid;
    uint32_t active = compatible(tracee) ?
        (get_extension(tracee, link2symlink_callback) ? 2 : 1) : 0;
    static int requested = -1;
    if (requested < 0) {
        const char *setting = getenv("PROOT_FASTCLOSE");
        requested = setting && !strcmp(setting, "1");
    }
    if (tracee->auxv_fd >= 0 || tracee->fake_netlink_fds_count || tracee->netlink_route_fds_count)
        tracee->fastclose_exec_isolated = false;
    atomic_store(&state->page->close_safe, requested && active && tracee->fastclose_exec_isolated);
    atomic_store(&state->page->single_safe, requested && active &&
                 tracee->fastclose_exec_isolated && !tracee->fastfd_single_disabled && !async_fd_seen);
    atomic_store(&state->page->pin_safe, active && tracee->fastclose_exec_isolated && !async_fd_seen);
    Extension *identity = get_extension(tracee, fake_id0_callback);
    if (identity) {
        Config *config = identity->config;
        uid = config->suid;
        gid = config->sgid;
    }
    publish_paths(tracee, state, active);
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
    if (version != PROOT_FASTFD_VERSION || !compatible(tracee)) return -EOPNOTSUPP;
    if (guest_fd == -1)
        return tracee->fastclose_exec_isolated && !async_fd_seen ? PROOT_FASTFD_PIN_ALLOWED : 0;
    const char *enabled = getenv("PROOT_FASTFD");
    if (!enabled || strcmp(enabled, "1"))
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
    state->storage_fd = -1;
    state->host_uid = getuid();
    state->host_gid = getgid();
    const char *paths = getenv("PROOT_FASTPATH");
    state->path_requested = paths && !strcmp(paths, "1");
    talloc_set_destructor(state, release_page);
    fastfd_reset(tracee);
    tracee->fastfd = state;
    atomic_store(&state->page->sequence, 0);
    atomic_store(&state->page->paths.sequence, 0);
    atomic_store(&state->page->paths.active, 0);
    publish_storage(tracee, state);
    fastfd_sync(tracee);
    return 0;
}
