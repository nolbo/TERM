/* SPDX-License-Identifier: Apache-2.0 */
#define _GNU_SOURCE
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <setjmp.h>
#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#define CHECK(x) do { if (!(x)) { fprintf(stderr, "NETWORK FAIL %d errno=%d\n", __LINE__, errno); return 1; } } while (0)
static volatile sig_atomic_t delivered, jump_out;
static sigjmp_buf escape;
static int bound_waits(int fd)
{
    struct timeval timeout = {.tv_sec = 2};
    return setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout)) ||
        setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout));
}
static void caught(int sig)
{
    struct stat st;
    if (sig == SIGUSR1 && stat("/input", &st) == 0 && st.st_size == 8) delivered++;
    if (jump_out) siglongjmp(escape, 1);
}

static int streams(void)
{
    int server = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0); CHECK(server >= 0);
    CHECK(bound_waits(server) == 0);
    struct sockaddr_in address = {.sin_family = AF_INET, .sin_addr.s_addr = htonl(INADDR_LOOPBACK)};
    CHECK(bind(server, (void *)&address, sizeof(address)) == 0 && listen(server, 4) == 0);
    socklen_t length = sizeof(address);
    CHECK(getsockname(server, (void *)&address, &length) == 0 && address.sin_port);
    pid_t child = fork(); CHECK(child >= 0);
    if (!child) {
        close(server);
        int client = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
        if (client < 0 || bound_waits(client) || connect(client, (void *)&address, sizeof(address))) _exit(91);
        if (send(client, "hello", 5, MSG_NOSIGNAL) != 5 || shutdown(client, SHUT_WR)) _exit(92);
        char reply[8]; ssize_t count = recv(client, reply, sizeof(reply), MSG_WAITALL);
        close(client); _exit(count == 5 && !memcmp(reply, "world", 5) ? 0 : 93);
    }
    struct sockaddr_storage peer; length = sizeof(peer);
    int client = accept4(server, (void *)&peer, &length, SOCK_CLOEXEC); CHECK(client >= 0);
    CHECK(peer.ss_family == AF_INET && fcntl(client, F_GETFD) == FD_CLOEXEC);
    length = sizeof(peer); CHECK(getpeername(client, (void *)&peer, &length) == 0 && peer.ss_family == AF_INET);
    char data[8]; CHECK(recv(client, data, 5, MSG_WAITALL) == 5 && !memcmp(data, "hello", 5));
    CHECK(recv(client, data, 1, 0) == 0);
    CHECK(send(client, "world", 5, MSG_NOSIGNAL) == 5 && shutdown(client, SHUT_WR) == 0);
    int kind; length = sizeof(kind);
    CHECK(getsockopt(client, SOL_SOCKET, SO_TYPE, &kind, &length) == 0 && kind == SOCK_STREAM);
    CHECK(close(client) == 0 && close(server) == 0);
    int status; CHECK(waitpid(child, &status, 0) == child && WIFEXITED(status) && WEXITSTATUS(status) == 0);
    puts("PASS TCP accept4/CLOEXEC/peer/half-close"); return 0;
}

static int datagrams(void)
{
    for (int family = AF_INET; family <= AF_INET6; family += AF_INET6 - AF_INET) {
        int receiver = socket(family, SOCK_DGRAM | SOCK_CLOEXEC, 0); CHECK(receiver >= 0);
        int sender = socket(family, SOCK_DGRAM | SOCK_CLOEXEC, 0); CHECK(sender >= 0);
        CHECK(bound_waits(receiver) == 0 && bound_waits(sender) == 0);
        struct sockaddr_storage storage = {0};
        socklen_t length;
        if (family == AF_INET) {
            struct sockaddr_in *a = (void *)&storage;
            a->sin_family = AF_INET; a->sin_addr.s_addr = htonl(INADDR_LOOPBACK); length = sizeof(*a);
        } else {
            struct sockaddr_in6 *a = (void *)&storage;
            a->sin6_family = AF_INET6; a->sin6_addr = in6addr_loopback; length = sizeof(*a);
        }
        CHECK(bind(receiver, (void *)&storage, length) == 0);
        CHECK(getsockname(receiver, (void *)&storage, &length) == 0);
        CHECK(sendto(sender, "abcdef", 6, 0, (void *)&storage, length) == 6);
        char bytes[4]; struct sockaddr_storage peer; socklen_t peer_length = sizeof(peer);
        CHECK(recvfrom(receiver, bytes, sizeof(bytes), MSG_PEEK, (void *)&peer, &peer_length) == 4);
        CHECK(peer.ss_family == family && !memcmp(bytes, "abcd", 4));
        CHECK(recvfrom(receiver, bytes, sizeof(bytes), MSG_TRUNC, NULL, NULL) == 6);
        CHECK(connect(sender, (void *)&storage, length) == 0 && send(sender, "ok", 2, 0) == 2);
        CHECK(recv(receiver, bytes, sizeof(bytes), 0) == 2 && !memcmp(bytes, "ok", 2));
        struct sockaddr disconnected = {.sa_family = AF_UNSPEC};
        CHECK(connect(sender, &disconnected, sizeof(disconnected)) == 0);
        CHECK(send(sender, "x", 1, 0) == -1 && errno == EDESTADDRREQ);
        CHECK(close(sender) == 0 && close(receiver) == 0);
    }
    puts("PASS IPv4/IPv6 UDP peek/truncate/disconnect"); return 0;
}

static int rights(void)
{
    int pair[2]; CHECK(socketpair(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0, pair) == 0);
    CHECK(bound_waits(pair[0]) == 0 && bound_waits(pair[1]) == 0);
    int file = open("/input", O_RDONLY); CHECK(file >= 0);
    char byte = 'F'; struct iovec iov = {.iov_base = &byte, .iov_len = 1};
    union { struct cmsghdr alignment; char bytes[CMSG_SPACE(sizeof(int))]; } control = {0};
    struct msghdr message = {.msg_iov = &iov, .msg_iovlen = 1,
        .msg_control = control.bytes, .msg_controllen = sizeof(control.bytes)};
    struct cmsghdr *header = CMSG_FIRSTHDR(&message);
    header->cmsg_level = SOL_SOCKET; header->cmsg_type = SCM_RIGHTS; header->cmsg_len = CMSG_LEN(sizeof(int));
    memcpy(CMSG_DATA(header), &file, sizeof(file));
    CHECK(sendmsg(pair[0], &message, MSG_NOSIGNAL) == 1 && close(file) == 0);
    memset(&control, 0, sizeof(control)); byte = 0;
    CHECK(recvmsg(pair[1], &message, MSG_CMSG_CLOEXEC) == 1 && byte == 'F' && !(message.msg_flags & MSG_CTRUNC));
    header = CMSG_FIRSTHDR(&message);
    CHECK(header && header->cmsg_level == SOL_SOCKET && header->cmsg_type == SCM_RIGHTS);
    memcpy(&file, CMSG_DATA(header), sizeof(file));
    char bytes[8]; CHECK(fcntl(file, F_GETFD) == FD_CLOEXEC);
    CHECK(read(file, bytes, sizeof(bytes)) == 8 && !memcmp(bytes, "fixture\n", 8));
    CHECK(close(file) == 0 && close(pair[0]) == 0 && close(pair[1]) == 0);
    puts("PASS anonymous IPC SCM_RIGHTS/CLOEXEC"); return 0;
}

static int interruptions(void)
{
    struct sigaction action = {.sa_handler = caught}; sigemptyset(&action.sa_mask);
    CHECK(sigaction(SIGUSR1, &action, NULL) == 0);
    sigset_t unblock, inherited;
    sigemptyset(&unblock); sigaddset(&unblock, SIGUSR1);
    CHECK(sigprocmask(SIG_UNBLOCK, &unblock, &inherited) == 0);
    printf("PASS signal test unblocks inherited SIGUSR1 (previously blocked=%d)\n", sigismember(&inherited, SIGUSR1));
    for (int nonlocal = 0; nonlocal < 2; nonlocal++) {
        int pair[2]; CHECK(socketpair(AF_UNIX, SOCK_STREAM, 0, pair) == 0);
        CHECK(bound_waits(pair[0]) == 0 && bound_waits(pair[1]) == 0);
        int buffer_size = 4096;
        CHECK(setsockopt(pair[0], SOL_SOCKET, SO_SNDBUF, &buffer_size, sizeof(buffer_size)) == 0);
        char bytes[4096] = {0};
        while (send(pair[0], bytes, sizeof(bytes), MSG_DONTWAIT | MSG_NOSIGNAL) > 0) {}
        CHECK(errno == EAGAIN || errno == EWOULDBLOCK);
        pid_t parent = getpid();
        pid_t child = fork(); CHECK(child >= 0);
        if (!child) {
            struct timespec delay = {.tv_nsec = 50000000}; nanosleep(&delay, NULL);
            _exit(getppid() != parent || kill(parent, SIGUSR1) ? 94 : 0);
        }
        delivered = 0; jump_out = nonlocal;
        if (!sigsetjmp(escape, 1)) {
            struct iovec iov = {.iov_base = bytes, .iov_len = 1};
            struct msghdr message = {.msg_iov = &iov, .msg_iovlen = 1};
            CHECK(sendmsg(pair[0], &message, MSG_NOSIGNAL) == -1 && errno == EINTR);
            CHECK(!nonlocal);
        } else CHECK(nonlocal);
        jump_out = 0; CHECK(delivered == 1);
        int status; CHECK(waitpid(child, &status, 0) == child && WIFEXITED(status) && WEXITSTATUS(status) == 0);
        CHECK(close(pair[0]) == 0 && close(pair[1]) == 0);
        // A nonlocal exit must leave subsequent SIGSYS dispatch usable.
        struct stat st; CHECK(stat("/input", &st) == 0 && st.st_size == 8);
    }
    CHECK(sigprocmask(SIG_SETMASK, &inherited, NULL) == 0);
    puts("PASS blocking sendmsg EINTR/reentrant handler/siglongjmp"); return 0;
}

int main(int argc, char **argv)
{
    CHECK(setvbuf(stdout, NULL, _IONBF, 0) == 0);
    CHECK(streams() == 0 && datagrams() == 0 && rights() == 0 && interruptions() == 0);
    if (argc == 2 && !strcmp(argv[1], "direct-policy")) {
        int pair[2]; CHECK(socketpair(AF_UNIX, SOCK_DGRAM, 0, pair) == 0);
        struct sockaddr_un address = {.sun_family = AF_UNIX};
        strcpy(address.sun_path, "/dev/socket/forbidden");
        CHECK(bind(pair[0], (void *)&address, sizeof(address)) == -1 && errno == ENOENT);
        address.sun_path[0] = 0;
        CHECK(connect(pair[0], (void *)&address, sizeof(address)) == -1 && errno == EOPNOTSUPP);
        CHECK(sendto(pair[0], "x", 1, 0, (void *)1, 4) == -1 && errno == EFAULT);
        CHECK(close(pair[0]) == 0 && close(pair[1]) == 0);
        puts("PASS host socket path and abstract address denied");
    }
    puts("PASS Linux network syscalls"); return 0;
}
