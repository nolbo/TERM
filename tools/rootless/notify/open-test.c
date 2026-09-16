/* SPDX-License-Identifier: GPL-2.0-or-later */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <signal.h>
#include <sys/ioctl.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#define CHECK(x) do { if (!(x)) { fprintf(stderr, "FAIL line %d: %s errno=%d\n", __LINE__, #x, errno); exit(1); } } while (0)
static const char fixture[64] = "fixture", missing[64] = "missing", cancel_name[64] = "cancel";
static const char payload[] = "notification fixture\n";
static pid_t owner_pid, pending_child;
static int owned_root = -1;
static char owned_directory[4096];

static void cleanup(void)
{
    if (getpid() != owner_pid) return;
    if (pending_child > 0) {
        (void)kill(pending_child, SIGKILL);
        while (waitpid(pending_child, NULL, 0) < 0 && errno == EINTR) {}
    }
    if (owned_root >= 0) {
        (void)unlinkat(owned_root, "data", 0);
        (void)close(owned_root);
    }
    if (owned_directory[0]) (void)rmdir(owned_directory);
}

static void send_fd(int socket, int fd)
{
    char byte = 'F', control[CMSG_SPACE(sizeof(int))] = {0};
    struct iovec iov = { &byte, 1 };
    struct msghdr msg = { .msg_iov = &iov, .msg_iovlen = 1,
        .msg_control = control, .msg_controllen = sizeof(control) };
    struct cmsghdr *cmsg = CMSG_FIRSTHDR(&msg);
    cmsg->cmsg_level = SOL_SOCKET; cmsg->cmsg_type = SCM_RIGHTS;
    cmsg->cmsg_len = CMSG_LEN(sizeof(int));
    memcpy(CMSG_DATA(cmsg), &fd, sizeof(fd));
    CHECK(sendmsg(socket, &msg, 0) == 1);
}

static int receive_fd(int socket)
{
    char byte, control[CMSG_SPACE(sizeof(int))] = {0};
    struct iovec iov = { &byte, 1 };
    struct msghdr msg = { .msg_iov = &iov, .msg_iovlen = 1,
        .msg_control = control, .msg_controllen = sizeof(control) };
    CHECK(recvmsg(socket, &msg, MSG_CMSG_CLOEXEC) == 1 && byte == 'F');
    CHECK(!(msg.msg_flags & (MSG_TRUNC | MSG_CTRUNC)));
    struct cmsghdr *cmsg = CMSG_FIRSTHDR(&msg);
    CHECK(cmsg && cmsg->cmsg_level == SOL_SOCKET && cmsg->cmsg_type == SCM_RIGHTS &&
        cmsg->cmsg_len == CMSG_LEN(sizeof(int)));
    int fd; memcpy(&fd, CMSG_DATA(cmsg), sizeof(fd));
    return fd;
}

static int install_listener(void)
{
    struct sock_filter filter[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, arch)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, AUDIT_ARCH_AARCH64, 1, 0),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_KILL_PROCESS),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_openat, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_USER_NOTIF),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    };
    struct sock_fprog program = { .len = sizeof(filter) / sizeof(filter[0]), .filter = filter };
    CHECK(prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) == 0);
    int fd = syscall(SYS_seccomp, SECCOMP_SET_MODE_FILTER, SECCOMP_FILTER_FLAG_NEW_LISTENER, &program);
    CHECK(fd >= 0);
    return fd;
}

static double now(void)
{
    struct timespec ts; CHECK(clock_gettime(CLOCK_MONOTONIC, &ts) == 0);
    return ts.tv_sec * 1000.0 + ts.tv_nsec / 1000000.0;
}

static int request_open(const char *name, int flags)
{ return syscall(SYS_openat, AT_FDCWD, name, flags, 0); }

static void verify_file(int fd, int cloexec)
{
    CHECK(fd >= 0 && fcntl(fd, F_GETFD) == cloexec);
    char buffer[sizeof(payload)] = {0};
    CHECK(read(fd, buffer, sizeof(payload) - 1) == (ssize_t)sizeof(payload) - 1);
    CHECK(strcmp(buffer, payload) == 0);
    CHECK(write(fd, "x", 1) == -1 && errno == EBADF);
    CHECK(close(fd) == 0);
}

static void benchmark(const char *name, const char *mode, unsigned count)
{
    double start = now();
    for (unsigned i = 0; i < count; i++) {
        int fd = request_open(name, O_RDONLY | O_CLOEXEC);
        CHECK(fd >= 0 && close(fd) == 0);
    }
    printf("{\"mode\":\"%s\",\"count\":%u,\"wall_ms\":%.3f}\n", mode, count, now() - start);
    fflush(stdout);
}

int main(int argc, char **argv)
{
    alarm(60);
    owner_pid = getpid();
    CHECK(atexit(cleanup) == 0);
    CHECK(argc == 4);
    CHECK(strcmp(argv[2], "direct") == 0 || strcmp(argv[2], "notify") == 0);
    int notify = strcmp(argv[2], "notify") == 0;
    char *end; unsigned long iterations = strtoul(argv[3], &end, 10);
    CHECK(*argv[3] && !*end && iterations > 0 && iterations <= 100000);
    if (notify) CHECK(geteuid() != 0);
    char directory[4096], filename[4096];
    CHECK(snprintf(directory, sizeof(directory), "%s/terminal-notify-XXXXXX", argv[1]) < (int)sizeof(directory));
    CHECK(mkdtemp(directory));
    strcpy(owned_directory, directory);
    CHECK(snprintf(filename, sizeof(filename), "%s/data", directory) < (int)sizeof(filename));
    int root = open(directory, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    CHECK(root >= 0);
    owned_root = root;
    int fd = openat(root, "data", O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC, 0600);
    CHECK(fd >= 0 && write(fd, payload, sizeof(payload) - 1) == (ssize_t)sizeof(payload) - 1);
    CHECK(close(fd) == 0);
    if (!notify) {
        verify_file(request_open(filename, O_RDONLY | O_CLOEXEC), FD_CLOEXEC);
        benchmark(filename, "direct", iterations);
    } else {
        struct seccomp_notif_sizes sizes;
        CHECK(syscall(SYS_seccomp, SECCOMP_GET_NOTIF_SIZES, 0, &sizes) == 0);
        CHECK(sizes.seccomp_notif >= sizeof(struct seccomp_notif));
        CHECK(sizes.seccomp_notif_resp >= sizeof(struct seccomp_notif_resp));
        struct seccomp_notif *request = calloc(1, sizes.seccomp_notif);
        struct seccomp_notif_resp *response = calloc(1, sizes.seccomp_notif_resp);
        CHECK(request && response);
        int channel[2]; CHECK(socketpair(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0, channel) == 0);
        pid_t child = fork(); CHECK(child >= 0);
        if (child == 0) {
            alarm(45);
            CHECK(close(root) == 0 && close(channel[0]) == 0);
            int listener = install_listener();
            send_fd(channel[1], listener);
            CHECK(close(listener) == 0);
            verify_file(request_open(fixture, O_RDONLY | O_CLOEXEC), FD_CLOEXEC);
            CHECK(write(channel[1], "A", 1) == 1);
            CHECK(request_open(missing, O_RDONLY) == -1 && errno == ENOENT);
            CHECK(request_open((const char *)1, O_RDONLY) == -1 && errno == EFAULT);
            verify_file(request_open(fixture, O_RDONLY), 0);
            benchmark(fixture, "notify", iterations);
            (void)request_open(cancel_name, O_RDONLY);
            _exit(2); /* The supervisor must cancel this pending request. */
        }
        pending_child = child;
        CHECK(close(channel[1]) == 0);
        int listener = receive_fd(channel[0]);
        for (unsigned i = 0; i < iterations + 5; i++) {
            memset(request, 0, sizes.seccomp_notif);
            int received;
            do { received = ioctl(listener, SECCOMP_IOCTL_NOTIF_RECV, request); } while (received < 0 && errno == EINTR);
            CHECK(received == 0 && request->pid == (unsigned)child && request->flags == 0);
            CHECK(request->data.arch == AUDIT_ARCH_AARCH64 && request->data.nr == SYS_openat);
            CHECK((int)request->data.args[0] == AT_FDCWD);
            int flags = request->data.args[2];
            CHECK(flags == O_RDONLY || flags == (O_RDONLY | O_CLOEXEC));
            char name[64] = {0};
            struct iovec local = { name, sizeof(name) };
            struct iovec remote = { (void *)(uintptr_t)request->data.args[1], sizeof(name) };
            errno = 0;
            ssize_t copied = process_vm_readv(child, &local, 1, &remote, 1, 0);
            if (copied != sizeof(name) && request->data.args[1] != 1)
                fprintf(stderr, "fixture memory read failed: bytes=%zd errno=%d\n", copied, errno);
            int error = copied != sizeof(name) || !memchr(name, 0, sizeof(name)) ? EFAULT : 0;
            CHECK(ioctl(listener, SECCOMP_IOCTL_NOTIF_ID_VALID, &request->id) == 0);
            if (!error && strcmp(name, cancel_name) == 0) {
                CHECK(i == iterations + 4 && kill(child, SIGKILL) == 0);
                int status;
                pid_t waited;
                do { waited = waitpid(child, &status, 0); } while (waited < 0 && errno == EINTR);
                if (waited == child) pending_child = 0;
                CHECK(waited == child && WIFSIGNALED(status) && WTERMSIG(status) == SIGKILL);
                CHECK(ioctl(listener, SECCOMP_IOCTL_NOTIF_ID_VALID, &request->id) == -1 && errno == ENOENT);
                fd = openat(root, "data", O_RDONLY | O_CLOEXEC); CHECK(fd >= 0);
                struct seccomp_notif_addfd add = { .id = request->id, .flags = SECCOMP_ADDFD_FLAG_SEND, .srcfd = fd };
                CHECK(ioctl(listener, SECCOMP_IOCTL_NOTIF_ADDFD, &add) == -1 && errno == ENOENT);
                CHECK(close(fd) == 0);
                break;
            }
            if (!error && strcmp(name, fixture) != 0) error = ENOENT;
            if (error) {
                memset(response, 0, sizes.seccomp_notif_resp);
                response->id = request->id;
                response->error = -error;
                CHECK(ioctl(listener, SECCOMP_IOCTL_NOTIF_SEND, response) == 0);
            } else {
                /* Never interpret guest bytes as a host path: this probe serves one owned fixture. */
                fd = openat(root, "data", O_RDONLY | O_CLOEXEC); CHECK(fd >= 0);
                struct seccomp_notif_addfd add = { .id = request->id, .flags = SECCOMP_ADDFD_FLAG_SEND,
                    .srcfd = fd, .newfd_flags = flags & O_CLOEXEC };
                CHECK(ioctl(listener, SECCOMP_IOCTL_NOTIF_ADDFD, &add) >= 0);
                CHECK(close(fd) == 0);
                if (i == 0) {
                    char ack; CHECK(read(channel[0], &ack, 1) == 1 && ack == 'A');
                    CHECK(ioctl(listener, SECCOMP_IOCTL_NOTIF_ID_VALID, &request->id) == -1 && errno == ENOENT);
                }
            }
        }
        CHECK(pending_child == 0); /* Cancellation branch, not merely loop exhaustion. */
        CHECK(close(listener) == 0 && close(channel[0]) == 0);
        free(request);
        free(response);
        puts("PASS unprivileged listener, memory copy, ADDFD_SEND, CLOEXEC, errors and canceled ID");
    }
    CHECK(unlinkat(root, "data", 0) == 0 && close(root) == 0 && rmdir(directory) == 0);
    owned_root = -1;
    owned_directory[0] = '\0';
    return 0;
}
