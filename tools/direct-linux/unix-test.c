/* SPDX-License-Identifier: Apache-2.0 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <setjmp.h>
#include <signal.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#define CHECK(x) do { if (!(x)) { fprintf(stderr, "UNIX FAIL %d errno=%d\n", __LINE__, errno); return 1; } } while (0)
static sigjmp_buf escape;
static volatile sig_atomic_t delivered, jump_out;

static socklen_t named(struct sockaddr_un *address, const char *path)
{
    memset(address, 0, sizeof(*address)); address->sun_family = AF_UNIX;
    size_t length = strlen(path);
    if (length >= sizeof(address->sun_path)) abort();
    memcpy(address->sun_path, path, length + 1);
    return offsetof(struct sockaddr_un, sun_path) + length + 1;
}

static int endpoint(int type)
{
    int fd = socket(AF_UNIX, type | SOCK_CLOEXEC, 0);
    struct timeval timeout = {.tv_sec = 2};
    if (fd >= 0 && (setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout)) ||
            setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout)))) { close(fd); return -1; }
    return fd;
}

static int name_is(int fd, const char *expected, int peer)
{
    struct sockaddr_un address = {0}; socklen_t size = sizeof(address);
    int result = peer ? getpeername(fd, (void *)&address, &size) : getsockname(fd, (void *)&address, &size);
    return !result && address.sun_family == AF_UNIX && size > sizeof(sa_family_t) &&
        size <= sizeof(address) && !strcmp(address.sun_path, expected);
}

static int streams(const char *directory, const char *program)
{
    char path[108], client_path[108], moved[108], link[108];
    CHECK(snprintf(path, sizeof(path), "%s/server", directory) > 0);
    CHECK(snprintf(client_path, sizeof(client_path), "%s/client", directory) > 0);
    CHECK(snprintf(moved, sizeof(moved), "%s/moved", directory) > 0);
    CHECK(snprintf(link, sizeof(link), "%s/link", directory) > 0);
    struct sockaddr_un address; socklen_t size = named(&address, path);
    int server = endpoint(SOCK_STREAM); CHECK(server >= 0);
    mode_t previous = umask(0077);
    CHECK(bind(server, (void *)&address, size) == 0); umask(previous);
    struct stat st; CHECK(lstat(path, &st) == 0 && S_ISSOCK(st.st_mode) && (st.st_mode & 0777) == 0700);
    CHECK(listen(server, 4) == 0 && name_is(server, path, 0));
    int duplicate = dup(server); CHECK(duplicate >= 0 && name_is(duplicate, path, 0) && close(duplicate) == 0);
    CHECK(rename(path, moved) == 0 && symlink(moved, link) == 0);
    pid_t child = fork(); CHECK(child >= 0);
    if (!child) { execl(program, program, "client", link, client_path, path, (char *)NULL); _exit(90); }
    struct sockaddr_un peer; socklen_t capacity = sizeof(peer);
    int accepted = accept4(server, (void *)&peer, &capacity, SOCK_CLOEXEC); CHECK(accepted >= 0);
    CHECK(peer.sun_family == AF_UNIX && !strcmp(peer.sun_path, client_path));
    CHECK(name_is(accepted, path, 0) && name_is(accepted, client_path, 1));
    char byte; CHECK(recv(accepted, &byte, 1, 0) == 1 && byte == 'Q');
    CHECK(unlink(moved) == 0 && name_is(server, path, 0));
    CHECK(send(accepted, "R", 1, MSG_NOSIGNAL) == 1);
    int status; CHECK(waitpid(child, &status, 0) == child && WIFEXITED(status) && !WEXITSTATUS(status));
    CHECK(close(accepted) == 0 && close(server) == 0 && unlink(link) == 0 && unlink(client_path) == 0);
    puts("PASS Unix stream fork/exec, names, metadata, symlink, rename/unlink survival"); return 0;
}

static void caught(int sig)
{
    struct stat st;
    if (sig == SIGUSR1 && stat("/input", &st) == 0 && st.st_size == 8 && chdir("/") == 0) delivered++;
    if (jump_out) siglongjmp(escape, 1);
}

static int datagrams(const char *directory, int direct)
{
    char path[108], sender_path[108];
    CHECK(snprintf(path, sizeof(path), "%s/dgram", directory) > 0);
    CHECK(snprintf(sender_path, sizeof(sender_path), "%s/source", directory) > 0);
    int receiver = endpoint(SOCK_DGRAM), sender = endpoint(SOCK_DGRAM); CHECK(receiver >= 0 && sender >= 0);
    struct sockaddr_un target, source; socklen_t target_size = named(&target, path), source_size = named(&source, sender_path);
    CHECK(bind(receiver, (void *)&target, target_size) == 0 && bind(sender, (void *)&source, source_size) == 0);
    ssize_t sent = sendto(sender, "data", 4, 0, (void *)&target, target_size);
    if (sent != 4) {
        fprintf(stderr, "UNIX DATAGRAM sendto result=%zd errno=%d\n", sent, errno);
        close(receiver); close(sender); unlink(path); unlink(sender_path);
        return 1;
    }
    char bytes[4096]; struct sockaddr_un peer; socklen_t capacity = sizeof(peer);
    CHECK(recvfrom(receiver, bytes, 4, 0, (void *)&peer, &capacity) == 4 && !memcmp(bytes, "data", 4));
    CHECK(peer.sun_family == AF_UNIX && !strcmp(peer.sun_path, sender_path));
    struct iovec iov = {.iov_base = bytes, .iov_len = 4};
    struct msghdr message = {.msg_name = &target, .msg_namelen = target_size, .msg_iov = &iov, .msg_iovlen = 1};
    CHECK(sendmsg(sender, &message, 0) == 4);
    message.msg_name = &peer; message.msg_namelen = sizeof(peer);
    CHECK(recvmsg(receiver, &message, 0) == 4 && message.msg_namelen > 2 && !strcmp(peer.sun_path, sender_path));
    if (direct) {
        // PRoot's truncated-output detranslation has different semantics. Keep
        // the strict Linux contract checked here without weakening it to match.
        unsigned char short_name[8]; memset(short_name, 0x5a, sizeof(short_name));
        capacity = 5;
        CHECK(getsockname(receiver, (void *)short_name, &capacity) == 0 && capacity == target_size);
        CHECK(!memcmp(short_name, &target, 5) && short_name[5] == 0x5a);
        capacity = 0;
        CHECK(getsockname(receiver, NULL, &capacity) == 0 && capacity == target_size);
        puts("PASS Unix name truncation and zero-capacity length query");
    }
    struct sigaction action = {.sa_handler = caught}; sigemptyset(&action.sa_mask);
    CHECK(sigaction(SIGUSR1, &action, NULL) == 0);
    sigset_t unblock, inherited; sigemptyset(&unblock); sigaddset(&unblock, SIGUSR1);
    CHECK(sigprocmask(SIG_UNBLOCK, &unblock, &inherited) == 0);
    int baseline = open("/input", O_RDONLY); CHECK(baseline >= 0 && close(baseline) == 0);
    for (int round = 0; round < 6; round++) {
        while (sendto(sender, bytes, 1, MSG_DONTWAIT, (void *)&target, target_size) == 1) {}
        CHECK(errno == EAGAIN || errno == EWOULDBLOCK);
        CHECK(chdir(directory) == 0);
        pid_t parent = getpid(), child = fork(); CHECK(child >= 0);
        if (!child) {
            struct timespec delay = {.tv_nsec = 50000000}; nanosleep(&delay, NULL);
            _exit(getppid() != parent || kill(parent, SIGUSR1) ? 91 : 0);
        }
        delivered = 0; jump_out = round & 1;
        if (!sigsetjmp(escape, 1)) {
            CHECK(sendto(sender, bytes, 1, 0, (void *)&target, target_size) == -1 && errno == EINTR);
            CHECK(!jump_out);
        } else CHECK(jump_out);
        jump_out = 0; CHECK(delivered == 1);
        int status; CHECK(waitpid(child, &status, 0) == child && WIFEXITED(status) && !WEXITSTATUS(status));
        int next = open("/input", O_RDONLY); CHECK(next == baseline && close(next) == 0);
        char cwd[8]; CHECK(getcwd(cwd, sizeof(cwd)) == cwd && !strcmp(cwd, "/"));
    }
    CHECK(sigprocmask(SIG_SETMASK, &inherited, NULL) == 0);
    CHECK(close(receiver) == 0 && close(sender) == 0 && unlink(path) == 0 && unlink(sender_path) == 0);
    puts("PASS Unix datagram source names, sendmsg/recvmsg, EINTR and repeated siglongjmp without FD leaks"); return 0;
}

int main(int argc, char **argv)
{
    CHECK(setvbuf(stdout, NULL, _IONBF, 0) == 0);
    if (argc == 5 && !strcmp(argv[1], "client")) {
        int fd = endpoint(SOCK_STREAM); CHECK(fd >= 0);
        struct sockaddr_un address; socklen_t size = named(&address, argv[3]);
        CHECK(bind(fd, (void *)&address, size) == 0);
        size = named(&address, argv[2]); CHECK(connect(fd, (void *)&address, size) == 0);
        CHECK(name_is(fd, argv[4], 1));
        CHECK(send(fd, "Q", 1, MSG_NOSIGNAL) == 1);
        char byte; CHECK(recv(fd, &byte, 1, 0) == 1 && byte == 'R');
        CHECK(close(fd) == 0); return 0;
    }
    char directory[] = "/tmp/ux-XXXXXX"; CHECK(mkdtemp(directory));
    if (argc == 2 && !strcmp(argv[1], "long-name")) {
        char path[101]; size_t prefix = strlen(directory);
        memcpy(path, directory, prefix); path[prefix++] = '/';
        memset(path + prefix, 'x', 100 - prefix); path[100] = 0;
        struct sockaddr_un address; socklen_t size = named(&address, path);
        int fd = endpoint(SOCK_STREAM); CHECK(fd >= 0);
        int result = bind(fd, (void *)&address, size), failure = errno;
        CHECK(close(fd) == 0);
        if (!result) CHECK(unlink(path) == 0);
        CHECK(rmdir(directory) == 0);
        if (result) {
            fprintf(stderr, "UNIX LIMIT 100-byte pathname bind errno=%d\n", failure);
            return failure == ENAMETOOLONG ? 78 : 1;
        }
        puts("PASS Unix 100-byte pathname bind"); return 0;
    }
    int direct = argc == 2 && !strcmp(argv[1], "direct");
    if (argc == 2 && !strcmp(argv[1], "datagrams")) {
        int result = datagrams(directory, 0);
        CHECK(rmdir(directory) == 0);
        return result;
    }
    CHECK(streams(directory, argv[0]) == 0);
    if (direct) CHECK(datagrams(directory, 1) == 0);
    CHECK(rmdir(directory) == 0);
    puts("PASS common Unix socket calls"); return 0;
}
