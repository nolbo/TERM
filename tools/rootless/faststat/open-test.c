/* SPDX-License-Identifier: GPL-2.0-or-later */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <semaphore.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#define CHECK(x) do { if (!(x)) { fprintf(stderr, "FAIL %d: %s errno=%d\n", __LINE__, #x, errno); exit(1); } } while (0)
static sem_t ready;

static void content(int fd)
{
    char data[6];
    CHECK(pread(fd, data, sizeof(data), 0) == sizeof(data));
    CHECK(memcmp(data, "sample", sizeof(data)) == 0);
}

static void *blocked_open(void *unused)
{
    (void)unused;
    CHECK(sem_post(&ready) == 0);
    int fd = open("fifo", O_RDONLY); /* libc cancellation point, intentionally blocking */
    if (fd >= 0) close(fd);
    return NULL;
}

static double now(void)
{
    struct timespec ts;
    CHECK(clock_gettime(CLOCK_MONOTONIC, &ts) == 0);
    return ts.tv_sec * 1000.0 + ts.tv_nsec / 1e6;
}

int main(int argc, char **argv)
{
    CHECK(argc == 1 || argc == 2);
    alarm(30);
    char temporary[] = "open-test-XXXXXX";
    int parent = open(".", O_RDONLY | O_DIRECTORY);
    CHECK(parent >= 0 && mkdtemp(temporary) && chdir(temporary) == 0);
    int fd = syscall(SYS_openat, AT_FDCWD, "file", O_CREAT | O_EXCL | O_RDWR, 0600);
    CHECK(fd >= 0 && write(fd, "sample", 6) == 6 && close(fd) == 0);
    if (argc == 2) {
        char *end;
        long count = strtol(argv[1], &end, 10);
        CHECK(*argv[1] && !*end && count >= 0 && count <= 100000);
        double start = 0;
        for (long i = -100; i < count; i++) {
            if (i == 0) start = now();
            fd = syscall(SYS_openat, AT_FDCWD, "file", O_RDONLY | O_CLOEXEC, 0);
            CHECK(fd >= 0);
            content(fd);
            CHECK(close(fd) == 0);
        }
        printf("{\"iterations\":%ld,\"ms\":%.3f}\n", count, count ? now() - start : 0);
    } else {
        fd = syscall(SYS_openat, AT_FDCWD, "file", O_RDONLY | O_CLOEXEC, 0);
        CHECK(fd >= 0);
        CHECK(fcntl(fd, F_GETFD) == FD_CLOEXEC);
        CHECK((fcntl(fd, F_GETFL) & O_ACCMODE) == O_RDONLY);
        content(fd);
        int copy = dup(fd);
        CHECK(copy >= 0 && fcntl(copy, F_GETFD) == 0);
        content(copy);
        CHECK(close(copy) == 0 && close(fd) == 0);
        CHECK(syscall(SYS_openat, AT_FDCWD, "file", O_CREAT | O_EXCL | O_WRONLY, 0600) == -1 && errno == EEXIST);
        CHECK(syscall(SYS_openat, -123, "file", O_RDONLY, 0) == -1 && errno == EBADF);
        CHECK(syscall(SYS_openat, AT_FDCWD, "missing", O_RDONLY, 0) == -1 && errno == ENOENT);
        CHECK(symlink("file", "symbolic") == 0);
        CHECK(syscall(SYS_openat, AT_FDCWD, "symbolic", O_RDONLY | O_NOFOLLOW, 0) == -1 && errno == ELOOP);
        fd = syscall(SYS_openat, AT_FDCWD, "symbolic", O_RDONLY, 0);
        CHECK(fd >= 0); content(fd); CHECK(close(fd) == 0);
        CHECK(link("file", "hard") == 0);
        fd = syscall(SYS_openat, AT_FDCWD, "hard", O_RDONLY, 0);
        CHECK(fd >= 0); content(fd);
        struct stat metadata;
        CHECK(fstat(fd, &metadata) == 0 && metadata.st_nlink == 2);
        char proc[64], target[4096];
        CHECK(snprintf(proc, sizeof(proc), "/proc/self/fd/%d", fd) < (int)sizeof(proc));
        ssize_t length = readlink(proc, target, sizeof(target) - 1);
        CHECK(length > 5); target[length] = 0;
        CHECK(strcmp(target + length - 5, "/hard") == 0);

        int sockets[2];
        CHECK(socketpair(AF_UNIX, SOCK_DGRAM, 0, sockets) == 0);
        union { struct cmsghdr alignment; char bytes[CMSG_SPACE(sizeof(int))]; } control = {0};
        char byte = 'x';
        struct iovec vector = { &byte, 1 };
        struct msghdr message = { .msg_iov = &vector, .msg_iovlen = 1,
            .msg_control = control.bytes, .msg_controllen = sizeof(control.bytes) };
        struct cmsghdr *header = CMSG_FIRSTHDR(&message);
        header->cmsg_level = SOL_SOCKET; header->cmsg_type = SCM_RIGHTS;
        header->cmsg_len = CMSG_LEN(sizeof(int)); memcpy(CMSG_DATA(header), &fd, sizeof(fd));
        CHECK(sendmsg(sockets[0], &message, 0) == 1);
        pid_t child = fork(); CHECK(child >= 0);
        if (child == 0) {
            CHECK(close(fd) == 0);
            memset(control.bytes, 0, sizeof(control.bytes));
            CHECK(recvmsg(sockets[1], &message, 0) == 1);
            header = CMSG_FIRSTHDR(&message);
            CHECK(header && header->cmsg_type == SCM_RIGHTS && !(message.msg_flags & MSG_CTRUNC));
            memcpy(&copy, CMSG_DATA(header), sizeof(copy));
            content(copy); CHECK(close(copy) == 0); _exit(0);
        }
        int status; CHECK(waitpid(child, &status, 0) == child);
        CHECK(WIFEXITED(status) && WEXITSTATUS(status) == 0);
        CHECK(close(sockets[0]) == 0 && close(sockets[1]) == 0 && close(fd) == 0);
        CHECK(unlink("hard") == 0 && unlink("symbolic") == 0);
        CHECK(mkfifo("fifo", 0600) == 0 && sem_init(&ready, 0, 0) == 0);
        pthread_t thread; CHECK(pthread_create(&thread, NULL, blocked_open, NULL) == 0);
        CHECK(sem_wait(&ready) == 0 && pthread_cancel(thread) == 0);
        void *result; CHECK(pthread_join(thread, &result) == 0 && result == PTHREAD_CANCELED);
        CHECK(sem_destroy(&ready) == 0 && unlink("fifo") == 0);
        puts("PASS open flags, errors, links, FD transfer and cancellation");
    }
    CHECK(unlink("file") == 0 && fchdir(parent) == 0 && close(parent) == 0);
    CHECK(rmdir(temporary) == 0);
    return 0;
}
