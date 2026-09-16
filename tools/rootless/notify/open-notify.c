/* SPDX-License-Identifier: GPL-2.0-or-later */
/* Experimental read-only open transport. Never enabled by default. */
#include <errno.h>
#include <elf.h>
#include <fcntl.h>
#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/memfd.h>
#include <linux/magic.h>
#include <linux/seccomp.h>
#include <poll.h>
#include <pthread.h>
#include <stdatomic.h>
#include <time.h>
#include <signal.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/vfs.h>
#include <sys/syscall.h>
#include <sys/sysmacros.h>
#include <sys/uio.h>
#include <sys/wait.h>
#include <unistd.h>
#include <talloc.h>
#include "tracee/open-notify.h"
#include "tracee/reg.h"
#include "path/path.h"
#include "path/binding.h"
#include "syscall/syscall.h"
#include "syscall/pipe_shadow.h"
#include "extension/extension.h"

static int listener = -1;
static struct seccomp_notif_sizes sizes;
static struct seccomp_notif *request;
static struct seccomp_notif_resp *response;
static bool event_mask_initialized;
static sigset_t event_wait_mask;
static unsigned long notified_opens, ordinary_opens;
static pthread_mutex_t tracee_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_t receiver;
static bool threaded, main_holds_lock;
static atomic_bool stopping, receiver_done;
static int receiver_error;
static int receiver_signal;

static void receiver_wakeup(int number) { (void)number; }


static void report_counts(void)
{
    if (getenv("PROOT_OPEN_NOTIFY_REPORT"))
        fprintf(stderr, "{\"notify_opens\":%lu,\"ordinary_opens\":%lu}\n", notified_opens, ordinary_opens);
}

int open_notify_prepare(const Tracee *tracee, int channel[2])
{
    channel[0] = channel[1] = -1;
    const char *enabled = getenv("PROOT_OPEN_NOTIFY");
    if (!enabled || strcmp(enabled, "1")) return 0;
    if (getenv("PROOT_NO_SECCOMP")) return -EINVAL;
    if (tracee->extensions) {
        Extension *extension;
        LIST_FOREACH(extension, tracee->extensions, link)
            if (extension->callback != fake_id0_callback && extension->callback != mountinfo_callback)
                return -EOPNOTSUPP;
    }
    if (syscall(SYS_seccomp, SECCOMP_GET_NOTIF_SIZES, 0, &sizes)) return -errno;
    if (sizes.seccomp_notif < sizeof(*request) || sizes.seccomp_notif_resp < sizeof(*response)) return -EINVAL;
    request = calloc(1, sizes.seccomp_notif);
    response = calloc(1, sizes.seccomp_notif_resp);
    if (!request || !response) return -ENOMEM;
    return socketpair(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0, channel) ? -errno : 0;
}

int open_notify_child(int channel[2])
{
    if (channel[0] < 0) return 0;
    close(channel[0]);
    /* Only native read-only opens with these flags enter the transport.
     * Creation, writes, O_PATH, unknown flags and other ABIs retain TRACE. */
    struct sock_filter filter[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, arch)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, AUDIT_ARCH_AARCH64, 0, 5),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_openat, 0, 3),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args[2])),
        BPF_JUMP(BPF_JMP | BPF_JSET | BPF_K,
                 ~(O_CLOEXEC | O_LARGEFILE | O_DIRECTORY | O_NONBLOCK), 1, 0),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_USER_NOTIF),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    };
    struct sock_fprog program = { .len = sizeof(filter)/sizeof(filter[0]), .filter = filter };
    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0)) return -errno;
    int fd = syscall(SYS_seccomp, SECCOMP_SET_MODE_FILTER, SECCOMP_FILTER_FLAG_NEW_LISTENER, &program);
    if (fd < 0) return -errno;
    char byte = 'N', control[CMSG_SPACE(sizeof(fd))] = {0};
    struct iovec iov = { &byte, 1 };
    struct msghdr msg = { .msg_iov = &iov, .msg_iovlen = 1,
        .msg_control = control, .msg_controllen = sizeof(control) };
    struct cmsghdr *cmsg = CMSG_FIRSTHDR(&msg);
    cmsg->cmsg_level = SOL_SOCKET; cmsg->cmsg_type = SCM_RIGHTS;
    cmsg->cmsg_len = CMSG_LEN(sizeof(fd));
    memcpy(CMSG_DATA(cmsg), &fd, sizeof(fd));
    int result = sendmsg(channel[1], &msg, 0) == 1 ? 0 : -errno;
    close(fd); close(channel[1]);
    return result;
}

int open_notify_parent(int channel[2])
{
    if (channel[0] < 0) return 0;
    close(channel[1]);
    char byte = 0, control[CMSG_SPACE(sizeof(int))] = {0};
    struct iovec iov = { &byte, 1 };
    struct msghdr msg = { .msg_iov = &iov, .msg_iovlen = 1,
        .msg_control = control, .msg_controllen = sizeof(control) };
    ssize_t received;
    do { received = recvmsg(channel[0], &msg, MSG_CMSG_CLOEXEC); } while (received < 0 && errno == EINTR);
    close(channel[0]);
    struct cmsghdr *cmsg = CMSG_FIRSTHDR(&msg);
    if (received != 1 || byte != 'N' || (msg.msg_flags & (MSG_TRUNC | MSG_CTRUNC)) ||
        !cmsg || cmsg->cmsg_level != SOL_SOCKET || cmsg->cmsg_type != SCM_RIGHTS ||
        cmsg->cmsg_len != CMSG_LEN(sizeof(int))) return -EIO;
    memcpy(&listener, CMSG_DATA(cmsg), sizeof(listener));
    atexit(report_counts);
    return 0;
}

/* Read page-bounded chunks: do not require bytes past the pathname's NUL
 * to be mapped, and never fall back to ptrace on a notification-blocked task. */
static int copy_path(pid_t pid, uint64_t address, char path[PATH_MAX])
{
    size_t offset = 0, page = (size_t)sysconf(_SC_PAGESIZE);
    if (!address || address > UINTPTR_MAX - PATH_MAX) return -EFAULT;
    while (offset < PATH_MAX) {
        size_t count = page - ((address + offset) % page);
        if (count > PATH_MAX - offset) count = PATH_MAX - offset;
        struct iovec local = { path + offset, count };
        struct iovec remote = { (void *)(uintptr_t)(address + offset), count };
        ssize_t got = process_vm_readv(pid, &local, 1, &remote, 1, 0);
        if (got <= 0) return -EFAULT;
        if (memchr(path + offset, 0, got)) return path[0] ? 0 : -ENOENT;
        offset += got;
    }
    return -ENAMETOOLONG;
}

/* Fast success only: the kernel proves ordinary no-symlink traversal and
 * read access. Permission failures and ambiguous bindings use full PRoot
 * translation. This never caches a path or skips a failed permission check. */
static int ordinary_open(Tracee *tracee, const char *guest, int dirfd, int flags)
{
    char absolute[PATH_MAX], host[PATH_MAX];
    if (guest[0] != '/') {
        char cwd[PATH_MAX];
        if (dirfd != AT_FDCWD || getcwd2(tracee, cwd) < 0 ||
            join_paths(2, absolute, cwd, guest) < 0) return -1;
    } else strcpy(absolute, guest);
    size_t length = strlen(absolute);
    if (length < 2 || absolute[length - 1] == '/') return -1;
    for (const char *part = absolute + 1; *part;) {
        const char *slash = strchr(part, '/');
        size_t size = slash ? (size_t)(slash - part) : strlen(part);
        if (!size || (size == 1 && part[0] == '.') ||
            (size == 2 && part[0] == '.' && part[1] == '.')) return -1;
        if (!slash) break;
        part = slash + 1;
    }
    Binding *binding;
    CIRCLEQ_FOREACH(binding, tracee->fs->bindings.guest, link.guest) {
        Comparison relation = compare_paths(binding->guest.path, absolute);
        if (strchr(binding->guest.path + 1, '/') &&
            (relation == PATHS_ARE_EQUAL || relation == PATH1_IS_PREFIX)) return -1;
    }
    strcpy(host, absolute);
    if (substitute_binding(tracee, GUEST, host) < 0) return -1;
    const char *special[] = { "/proc", "/sys", "/dev" };
    for (unsigned i = 0; i < 3; i++) {
        Comparison a = compare_paths(special[i], absolute), b = compare_paths(special[i], host);
        if (a == PATHS_ARE_EQUAL || a == PATH1_IS_PREFIX ||
            b == PATHS_ARE_EQUAL || b == PATH1_IS_PREFIX) return -1;
    }
    struct { uint64_t flags, mode, resolve; } how = {
        .flags = O_PATH | O_CLOEXEC | (flags & O_DIRECTORY), .resolve = 0x04
    };
    int pinned = syscall(SYS_openat2, AT_FDCWD, host, &how, sizeof(how));
    if (pinned < 0) return -1;
    struct stat info;
    int fd = -1;
    if (fstat(pinned, &info) == 0 && (S_ISREG(info.st_mode) || S_ISDIR(info.st_mode))) {
        char reference[64];
        snprintf(reference, sizeof(reference), "/proc/self/fd/%d", pinned);
        fd = open(reference, flags | O_CLOEXEC);
    }
    close(pinned);
    return fd;
}

/* Check the pinned inode, never a pathname supplied by the guest. These
 * memory devices have nonblocking open operations and need no session state.
 * TTYs, arbitrary devices and blocking named FIFOs need separate handling. */
static bool supported_memory_device(const struct stat *info)
{
    if (!S_ISCHR(info->st_mode) || major(info->st_rdev) != 1) return false;
    switch (minor(info->st_rdev)) {
        case 3: /* null */
        case 5: /* zero */
        case 7: /* full */
        case 8: /* random */
        case 9: /* urandom */ return true;
        default: return false;
    }
}

/* Anonymous pipes never wait for a writer at open. Named FIFOs may wait,
 * except when the guest itself requested O_NONBLOCK. Classify the pinned
 * inode's filesystem; a /proc/fd pathname can refer to either kind. */
static bool supported_pipe(int pinned, const struct stat *info, int flags)
{
    if (!S_ISFIFO(info->st_mode)) return false;
    if (flags & O_NONBLOCK) return true;
    struct statfs filesystem;
    return fstatfs(pinned, &filesystem) == 0 && filesystem.f_type == PIPEFS_MAGIC;
}

/* Notification replies resume the guest without a ptrace stop, so merely
 * setting auxv_fd cannot arrange interception of the following read. Supply
 * a sealed snapshot instead: partial reads, pread and dup all see the same
 * corrected AT_EXECFN. This intentionally exposes a memfd, not procfs stat
 * metadata. Only the tracee's own translated auxv path is rewritten. */
static int snapshot_auxv(Tracee *tracee, const char *host, int source, int flags)
{
    char own_auxv[64];
    snprintf(own_auxv, sizeof(own_auxv), "/proc/%d/auxv", tracee->pid);
    if (!tracee->execfn_addr || strcmp(host, own_auxv)) return source;
    uint64_t words[512];
    size_t length = 0;
    int error = 0, snapshot = -1, result = -1;
    while (length < sizeof(words)) {
        ssize_t count = read(source, (char *)words + length, sizeof(words) - length);
        if (count < 0 && errno == EINTR) continue;
        if (count < 0) { error = errno; goto done; }
        if (!count) break;
        length += count;
    }
    if (!length || length % (2 * sizeof(uint64_t))) { error = EIO; goto done; }
    bool terminated = false, patched = false;
    for (size_t i = 0; i + 1 < length / sizeof(uint64_t); i += 2) {
        if (words[i] == AT_NULL) { terminated = true; break; }
        if (words[i] == AT_EXECFN) { words[i + 1] = tracee->execfn_addr; patched = true; }
    }
    if (!terminated || !patched) { error = EIO; goto done; }
    snapshot = syscall(SYS_memfd_create, "proot-auxv", MFD_CLOEXEC | MFD_ALLOW_SEALING);
    if (snapshot < 0) { error = errno; goto done; }
    for (size_t offset = 0; offset < length;) {
        ssize_t count = write(snapshot, (char *)words + offset, length - offset);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) { error = count < 0 ? errno : EIO; goto done; }
        offset += count;
    }
    if (fcntl(snapshot, F_ADD_SEALS, F_SEAL_SEAL | F_SEAL_SHRINK | F_SEAL_GROW | F_SEAL_WRITE)) {
        error = errno; goto done;
    }
    char reference[64];
    snprintf(reference, sizeof(reference), "/proc/self/fd/%d", snapshot);
    result = open(reference, flags | O_CLOEXEC);
    if (result < 0) error = errno;
 done:
    if (snapshot >= 0) close(snapshot);
    close(source);
    if (error) errno = error;
    return result;
}

static int complete_open(void)
{
    notified_opens++;
    int error = 0, fd = -1, path_fd = -1;
    char guest[PATH_MAX], host[PATH_MAX];
    Tracee *tracee = get_tracee(NULL, request->pid, false);
    if (!tracee || request->flags || request->data.arch != AUDIT_ARCH_AARCH64 || request->data.nr != SYS_openat)
        error = -ENOSYS;
    if (!error) error = copy_path(request->pid, request->data.args[1], guest);
    if (!error && ioctl(listener, SECCOMP_IOCTL_NOTIF_ID_VALID, &request->id)) error = -ENOENT;
    if (!error) fd = ordinary_open(tracee, guest, (int)request->data.args[0], (int)request->data.args[2]);
    if (fd >= 0) ordinary_opens++;
    if (!error && fd < 0) {
        /* Translation reads syscall arguments but needs no ptrace register
         * access. Preserve the real ptrace state around the synthetic view. */
        struct user_regs_struct saved[NB_REG_VERSION];
        memcpy(saved, tracee->_regs, sizeof(saved));
        bool changed = tracee->_regs_were_changed;
        poke_reg(tracee, SYSARG_NUM, SYS_openat);
        for (int i = 0; i < 6; i++) poke_reg(tracee, SYSARG_1 + i, request->data.args[i]);
        save_current_regs(tracee, ORIGINAL);
        int flags = (int)request->data.args[2];
        error = translate_path(tracee, host, (int)request->data.args[0], guest, !(flags & O_NOFOLLOW));
        memcpy(tracee->_regs, saved, sizeof(saved));
        tracee->_regs_were_changed = changed;
        if (!error) {
            /* Pin first: reject blocking named FIFOs and unsupported devices.
             * Reopen the same inode; no second pathname lookup race. */
            path_fd = open(host, O_PATH | O_CLOEXEC | (flags & (O_NOFOLLOW | O_DIRECTORY)));
            if (path_fd < 0) error = -errno;
            else {
                struct stat info;
                if (fstat(path_fd, &info)) error = -errno;
                else if (S_ISLNK(info.st_mode)) error = -ELOOP;
                else if (!S_ISREG(info.st_mode) && !S_ISDIR(info.st_mode) &&
                         !supported_memory_device(&info) &&
                         !supported_pipe(path_fd, &info, flags)) error = -EOPNOTSUPP;
                else {
                    char reference[64];
                    snprintf(reference, sizeof(reference), "/proc/self/fd/%d", path_fd);
                    fd = open(reference, (flags & ~O_NOFOLLOW) | O_CLOEXEC);
                    if (fd >= 0 && S_ISREG(info.st_mode))
                        fd = snapshot_auxv(tracee, host, fd, flags & ~O_NOFOLLOW);
                    if (fd < 0) error = -errno;
                }
            }
        }
    }
    if (!error) {
        struct seccomp_notif_addfd add = { .id = request->id, .flags = SECCOMP_ADDFD_FLAG_SEND,
            .srcfd = fd, .newfd_flags = request->data.args[2] & O_CLOEXEC };
        int result;
        do { result = ioctl(listener, SECCOMP_IOCTL_NOTIF_ADDFD, &add); } while (result < 0 && errno == EINTR);
        if (result < 0) error = -errno;
    }
    if (fd >= 0) close(fd);
    if (path_fd >= 0) close(path_fd);
    /* Run permission-restoration and temporary-file destructors after open. */
    if (tracee) { TALLOC_FREE(tracee->ctx); tracee->ctx = talloc_new(tracee); }
    if (error) {
        memset(response, 0, sizes.seccomp_notif_resp);
        response->id = request->id; response->error = error;
        int result;
        do { result = ioctl(listener, SECCOMP_IOCTL_NOTIF_SEND, response); } while (result < 0 && errno == EINTR);
        if (result && errno != ENOENT)
            return -errno;
    }
    return 0;
}

static int receive_open(void)
{
    memset(request, 0, sizes.seccomp_notif);
    if (!ioctl(listener, SECCOMP_IOCTL_NOTIF_RECV, request)) return 1;
    return errno == EINTR || errno == ENOENT ? 0 : -errno;
}

/* Only RECV is outside the lock. All Tracee, extension and talloc access,
 * including deletion on the ptrace thread, is serialized by tracee_lock. */
static void *receive_loop(void *unused)
{
    (void)unused;
    sigset_t mask;
    sigfillset(&mask);
    sigdelset(&mask, receiver_signal);
    pthread_sigmask(SIG_SETMASK, &mask, NULL);
    while (!atomic_load(&stopping)) {
        int received = receive_open();
        if (!received) continue;
        pthread_mutex_lock(&tracee_lock);
        if (atomic_load(&stopping)) {
            pthread_mutex_unlock(&tracee_lock);
            break;
        }
        int result = received > 0 ? complete_open() : received;
        if (result < 0) {
            receiver_error = -result;
            /* A failed transport cannot leave guests blocked forever.
             * Killing under the same lock also wakes the main waitpid. */
            kill_all_tracees();
            pthread_mutex_unlock(&tracee_lock);
            break;
        }
        pthread_mutex_unlock(&tracee_lock);
    }
    atomic_store(&receiver_done, true);
    return NULL;
}

void open_notify_stop(void)
{
    if (!threaded) return;
    atomic_store(&stopping, true);
    if (main_holds_lock) {
        main_holds_lock = false;
        pthread_mutex_unlock(&tracee_lock);
    }
    /* Closing another thread's fd does not reliably interrupt a blocked
     * ioctl. Repeat the private interrupt until it has left RECV, covering
     * the race between checking stopping and entering the syscall. */
    while (!atomic_load(&receiver_done)) {
        pthread_kill(receiver, receiver_signal);
        struct timespec pause = { .tv_nsec = 1000000 };
        nanosleep(&pause, NULL);
    }
    pthread_join(receiver, NULL);
    threaded = false;
}

int open_notify_start(void)
{
    const char *setting = getenv("PROOT_OPEN_NOTIFY_THREAD");
    if (listener < 0 || !setting || strcmp(setting, "1")) return 0;
    receiver_signal = SIGRTMIN + 1;
    struct sigaction action = { .sa_handler = receiver_wakeup };
    sigemptyset(&action.sa_mask);
    if (sigaction(receiver_signal, &action, NULL)) return -errno;
    /* Async talloc tree printing cannot traverse data owned by the receiver. */
    if (sigaction(SIGUSR1, &action, NULL) || sigaction(SIGUSR2, &action, NULL)) return -errno;
    atomic_store(&stopping, false);
    atomic_store(&receiver_done, false);
    pthread_mutex_lock(&tracee_lock);
    main_holds_lock = true;
    int error = pthread_create(&receiver, NULL, receive_loop, NULL);
    if (error) {
        main_holds_lock = false;
        pthread_mutex_unlock(&tracee_lock);
        return -error;
    }
    threaded = true;
    /* Registered after the event loop's kill_all_tracees handler, so join
     * happens before exit-time tracee destruction and reporting. */
    if (atexit(open_notify_stop)) { open_notify_stop(); return -ENOMEM; }
    return 0;
}

static void child_event(int signal_number) { (void)signal_number; }

pid_t open_notify_wait(int *status)
{
    if (listener < 0) return waitpid(-1, status, __WALL);
    if (threaded) {
        if (receiver_error) { errno = receiver_error; return -1; }
        main_holds_lock = false;
        pthread_mutex_unlock(&tracee_lock);
        pid_t pid = waitpid(-1, status, __WALL);
        int error = errno;
        pthread_mutex_lock(&tracee_lock);
        main_holds_lock = true;
        errno = receiver_error ? receiver_error : error;
        return receiver_error ? -1 : pid;
    }
    /* Block SIGCHLD across waitpid -> ppoll, then atomically unblock during
     * sleep. This closes the missed-child-event race without a polling delay. */
    if (!event_mask_initialized) {
        struct sigaction action = { .sa_handler = child_event };
        sigemptyset(&action.sa_mask);
        if (sigaction(SIGCHLD, &action, NULL)) return -1;
        sigset_t blocked;
        sigemptyset(&blocked); sigaddset(&blocked, SIGCHLD);
        if (sigprocmask(SIG_BLOCK, &blocked, &event_wait_mask)) return -1;
        sigdelset(&event_wait_mask, SIGCHLD);
        event_mask_initialized = true;
    }
    pid_t pid;
    for (;;) {
        pid = waitpid(-1, status, __WALL | WNOHANG);
        if (pid != 0) break;
        struct pollfd ready = { .fd = listener, .events = POLLIN };
        struct timespec timeout = { .tv_nsec = 10000000 };
        int result = ppoll(&ready, 1, shadow_pipes_held() ? &timeout : NULL, &event_wait_mask);
        if (result < 0) { pid = -1; break; }
        if (!result) { errno = EINTR; pid = -1; break; }
        if (ready.revents & POLLIN) {
            /* A canceled notification can disappear after poll. Keep the
             * existing bounded alarm wakeup active across RECV as well. */
            shadow_pipes_set_timer(true);
            int served = receive_open();
            if (served > 0) served = complete_open();
            shadow_pipes_set_timer(false);
            if (served < 0) { errno = -served; pid = -1; break; }
        } else if (ready.revents & POLLHUP) {
            /* The last filtered task may exit between waitpid(WNOHANG)
             * and poll. HUP means drain its ptrace/wait status, not EIO. */
            close(listener); listener = -1;
            pid = waitpid(-1, status, __WALL);
            break;
        } else if (ready.revents & (POLLERR | POLLNVAL)) {
            errno = EIO; pid = -1; break;
        }
    }
    return pid;
}
