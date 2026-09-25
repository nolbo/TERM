/* SPDX-License-Identifier: Apache-2.0 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <linux/close_range.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/syscall.h>
#include <unistd.h>

#define CHECK(x) do { if (!(x)) { dprintf(2, "FAIL threads line %d: %s errno=%d\n", __LINE__, #x, errno); _exit(1); } } while (0)
#define WORKERS 8
static __thread int local = 101;
static __thread volatile sig_atomic_t signals;
static pthread_key_t key;
static pthread_barrier_t ready;
static pthread_mutex_t mutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t condition = PTHREAD_COND_INITIALIZER;
static pthread_mutex_t robust;
static atomic_int destructors, started, finished;
static int go, counter, shared_fd, pipe_fds[2];

static void caught(int signal)
{
    int old = errno;
    struct stat st; sigset_t mask;
    CHECK(signal == SIGUSR1 && local >= 200 && local < 200 + WORKERS);
    CHECK(sigprocmask(SIG_SETMASK, NULL, &mask) == 0 && sigismember(&mask, SIGSYS) == 1);
    CHECK(stat("/input", &st) == 0 && st.st_size == 8);
    signals++; errno = old;
}
static void destroyed(void *value)
{
    CHECK((uintptr_t)value >= 1 && (uintptr_t)value <= WORKERS);
    atomic_fetch_add(&destructors, 1);
}
static void *worker(void *argument)
{
    int id = (int)(uintptr_t)argument;
    CHECK(local == 101 && syscall(SYS_gettid) != getpid()); local = 200 + id;
    CHECK(pthread_setspecific(key, (void *)(uintptr_t)(id + 1)) == 0);
    sigset_t mask; sigemptyset(&mask); if (id & 1) sigaddset(&mask, SIGSYS);
    CHECK(pthread_sigmask(SIG_SETMASK, &mask, NULL) == 0);
    int barrier = pthread_barrier_wait(&ready); CHECK(!barrier || barrier == PTHREAD_BARRIER_SERIAL_THREAD);
    CHECK(pthread_mutex_lock(&mutex) == 0);
    while (!go) CHECK(pthread_cond_wait(&condition, &mutex) == 0);
    CHECK(pthread_mutex_unlock(&mutex) == 0);
    for (int i = 0; i < 100; i++) {
        struct stat st; char content[8];
        CHECK(stat("/input", &st) == 0 && st.st_size == 8);
        int fd = open("/input", O_RDONLY | O_CLOEXEC); CHECK(fd >= 0);
        CHECK(read(fd, content, 8) == 8 && !memcmp(content, "fixture\n", 8) && close(fd) == 0);
        CHECK(pread(shared_fd, content, 8, 0) == 8);
        size_t size = (size_t)sysconf(_SC_PAGESIZE);
        int *memory = mmap(NULL, size, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
        CHECK(memory != MAP_FAILED); *memory = id;
        CHECK(local == 200 + id && *memory == id && munmap(memory, size) == 0);
        CHECK(pthread_sigmask(SIG_SETMASK, NULL, &mask) == 0 && sigismember(&mask, SIGSYS) == (id & 1));
        errno = 1000 + id;
        CHECK(pthread_mutex_lock(&mutex) == 0); counter++;
        CHECK(pthread_mutex_unlock(&mutex) == 0 && errno == 1000 + id);
    }
    CHECK(signals == 1);
    if (id & 1) pthread_exit((void *)(uintptr_t)(17 + id));
    return (void *)(uintptr_t)(17 + id);
}
static void *simple(void *argument) { CHECK(local == 101); local = 99; return argument; }
static void *detached(void *argument) { CHECK(local == 101); atomic_fetch_add(&finished, 1); return argument; }
static void *owner(void *argument) { CHECK(pthread_mutex_lock(&robust) == 0); return argument; }
static void *cancelled(void *argument)
{
    (void)argument; char byte; atomic_store(&started, 1);
    CHECK(read(pipe_fds[0], &byte, 1) == 1); return NULL;
}
static void *unshare_files(void *argument)
{
    (void)argument;
    CHECK(syscall(SYS_close_range, shared_fd, shared_fd, CLOSE_RANGE_UNSHARE) == 0);
    CHECK(fcntl(shared_fd, F_GETFD) == -1 && errno == EBADF);
    struct stat st; CHECK(stat("/input", &st) == 0); return NULL;
}
static void *fifo_reader(void *argument)
{
    (void)argument; atomic_store(&started, 1);
    int fd = open("/tmp/thread-fifo", O_RDONLY); CHECK(fd >= 0);
    char bytes[8]; CHECK(read(fd, bytes, 8) == 8 && !memcmp(bytes, "threaded", 8));
    CHECK(close(fd) == 0); return NULL;
}
static void *last_thread(void *argument)
{
    usleep(30000);
    struct stat st; CHECK(local == 101 && stat("/input", &st) == 0);
    int dir = open("/tmp", O_RDONLY | O_DIRECTORY); CHECK(dir >= 0 && fchdir(dir) == 0);
    char cwd[64]; CHECK(getcwd(cwd, sizeof(cwd)) && !strcmp(cwd, "/tmp"));
    int file = open("after-leader", O_RDWR | O_CREAT | O_EXCL, 0600); CHECK(file >= 0);
    CHECK(chmod("after-leader", 0640) == 0 && fstat(file, &st) == 0 && (st.st_mode & 0777) == 0640);
    CHECK(close(file) == 0 && unlink("after-leader") == 0 && close(dir) == 0);
    if (argument) {
        int socket_fd = socket(AF_UNIX, SOCK_DGRAM, 0); CHECK(socket_fd >= 0);
        struct sockaddr_un address = {.sun_family = AF_UNIX}; strcpy(address.sun_path, "/tmp/after-leader-socket");
        CHECK(bind(socket_fd, (void *)&address, sizeof(address)) == 0);
        struct sockaddr_un actual; socklen_t size = sizeof(actual);
        CHECK(getsockname(socket_fd, (void *)&actual, &size) == 0);
        int translated = !strcmp(actual.sun_path, address.sun_path);
        CHECK(close(socket_fd) == 0 && unlink(address.sun_path) == 0);
        if (!translated) {
            puts("LIMIT Unix getsockname did not return the guest pathname after leader exit");
            fflush(stdout); _exit(78);
        }
        puts("PASS Unix pathname operations after leader exit");
    }
    puts("PASS worker survives initial-thread pthread_exit"); return NULL;
}

int main(int argc, char **argv)
{
    CHECK(argc == 2);
    pthread_t thread;
    if (!strcmp(argv[1], "main-exit") || !strcmp(argv[1], "main-exit-unix")) {
        CHECK(pthread_create(&thread, NULL, last_thread, (void *)(uintptr_t)!strcmp(argv[1], "main-exit-unix")) == 0);
        pthread_exit(NULL);
    }
    CHECK(!strcmp(argv[1], "common"));
    sigset_t mask; sigemptyset(&mask); CHECK(pthread_sigmask(SIG_SETMASK, &mask, NULL) == 0);
    struct sigaction action = {.sa_handler = caught}; sigfillset(&action.sa_mask);
    CHECK(sigaction(SIGUSR1, &action, NULL) == 0);
    shared_fd = open("/input", O_RDONLY | O_CLOEXEC); CHECK(shared_fd >= 0);
    CHECK(pthread_key_create(&key, destroyed) == 0 && pthread_barrier_init(&ready, NULL, WORKERS + 1) == 0);
    pthread_t workers[WORKERS];
    for (int i = 0; i < WORKERS; i++) {
        int r = pthread_create(workers + i, NULL, worker, (void *)(uintptr_t)i);
        if (r) dprintf(2, "pthread_create error=%d\n", r);
        CHECK(r == 0);
    }
    int barrier = pthread_barrier_wait(&ready); CHECK(!barrier || barrier == PTHREAD_BARRIER_SERIAL_THREAD);
    for (int i = 0; i < WORKERS; i++) CHECK(pthread_kill(workers[i], SIGUSR1) == 0);
    CHECK(pthread_mutex_lock(&mutex) == 0); go = 1;
    CHECK(pthread_cond_broadcast(&condition) == 0 && pthread_mutex_unlock(&mutex) == 0);
    for (int i = 0; i < WORKERS; i++) {
        void *result; CHECK(pthread_join(workers[i], &result) == 0 && result == (void *)(uintptr_t)(17 + i));
    }
    CHECK(counter == WORKERS * 100 && atomic_load(&destructors) == WORKERS && local == 101);
    CHECK(pthread_sigmask(SIG_SETMASK, NULL, &mask) == 0 && sigismember(&mask, SIGSYS) == 0);
    CHECK(pthread_key_delete(key) == 0 && pthread_barrier_destroy(&ready) == 0);
    puts("PASS pthread TLS/errno, shared memory/FDs, mutex/condition/barrier, masks and targeted signals");
    for (int i = 0; i < 200; i++) {
        void *value; CHECK(pthread_create(&thread, NULL, simple, (void *)(uintptr_t)(i + 1)) == 0);
        CHECK(pthread_join(thread, &value) == 0 && value == (void *)(uintptr_t)(i + 1));
    }
    for (int i = 0; i < 160; i++) {
        CHECK(pthread_create(&thread, NULL, detached, NULL) == 0 && pthread_detach(thread) == 0);
        while (atomic_load(&finished) <= i) usleep(1000);
    }
    puts("PASS repeated joinable and detached thread lifecycle");
    pthread_mutexattr_t attribute;
    CHECK(pthread_mutexattr_init(&attribute) == 0 && pthread_mutexattr_setrobust(&attribute, PTHREAD_MUTEX_ROBUST) == 0);
    CHECK(pthread_mutex_init(&robust, &attribute) == 0 && pthread_mutexattr_destroy(&attribute) == 0);
    CHECK(pthread_create(&thread, NULL, owner, NULL) == 0 && pthread_join(thread, NULL) == 0);
    CHECK(pthread_mutex_lock(&robust) == EOWNERDEAD && pthread_mutex_consistent(&robust) == 0);
    CHECK(pthread_mutex_unlock(&robust) == 0 && pthread_mutex_destroy(&robust) == 0);
    CHECK(pipe(pipe_fds) == 0 && pthread_create(&thread, NULL, cancelled, NULL) == 0);
    while (!atomic_load(&started)) usleep(1000);
    CHECK(pthread_cancel(thread) == 0);
    void *result; CHECK(pthread_join(thread, &result) == 0 && result == PTHREAD_CANCELED);
    CHECK(close(pipe_fds[0]) == 0 && close(pipe_fds[1]) == 0);
    puts("PASS robust mutex owner death and deferred cancellation during native read");
    CHECK(pthread_create(&thread, NULL, unshare_files, NULL) == 0 && pthread_join(thread, NULL) == 0);
    CHECK(fcntl(shared_fd, F_GETFD) == FD_CLOEXEC && close(shared_fd) == 0);
    atomic_store(&started, 0);
    CHECK(pthread_create(&thread, NULL, fifo_reader, NULL) == 0);
    while (!atomic_load(&started)) usleep(1000);
    usleep(20000);
    int fd = open("/tmp/thread-fifo", O_WRONLY); CHECK(fd >= 0);
    CHECK(write(fd, "threaded", 8) == 8 && close(fd) == 0 && pthread_join(thread, NULL) == 0);
    puts("PASS FD-table unshare and FIFO peers without a runtime lock deadlock");
    return 0;
}
