/* SPDX-License-Identifier: Apache-2.0 */
#define _GNU_SOURCE
#include "threads.h"
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <linux/close_range.h>
#include <stdio.h>
#include <sys/resource.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>

struct dl_state dl_state;
size_t dl_fd_path(char *out, int fd)
{
    assert(fd == -1); return (size_t)sprintf(out, "/proc/%ld/fd", syscall(SYS_gettid));
}
unsigned dl_files_lock;
void dl_lock(unsigned *lock) { assert(!*lock); *lock = 1; }
void dl_unlock(unsigned *lock) { assert(*lock); *lock = 0; }
int dl_files_may_be_shared(void) { return 0; }
int dl_internal_fd(int fd) { return fd == dl_state.root_fd || fd == dl_state.vfork_fd; }
unsigned dl_next_internal_fd(unsigned first)
{
    unsigned result = UINT_MAX;
    int values[] = {dl_state.root_fd, dl_state.vfork_fd};
    for (unsigned i = 0; i < 2; i++)
        if (values[i] >= 0 && (unsigned)values[i] >= first && (unsigned)values[i] < result) result = values[i];
    return result;
}
long dl_host_syscall(long n, long a, long b, long c, long d, long e, long f)
{
    long r = syscall(n, a, b, c, d, e, f); return r < 0 ? -errno : r;
}
int main(void)
{
    for (unsigned mark = 0; mark < 2; mark++) {
        pid_t child = fork(); assert(child >= 0);
        if (!child) {
            int source = open("/dev/null", O_RDONLY); assert(source >= 0 && source < 7);
            dl_state.vfork_fd = fcntl(source, F_DUPFD, 7); assert(dl_state.vfork_fd == 7);
            dl_state.root_fd = fcntl(source, F_DUPFD, 256); assert(dl_state.root_fd == 256);
            struct rlimit limit; assert(!getrlimit(RLIMIT_NOFILE, &limit));
            limit.rlim_cur = 64; assert(!setrlimit(RLIMIT_NOFILE, &limit));
            while (dup(source) >= 0) {}
            assert(errno == EMFILE);
            assert(!dl_close_range(3, UINT_MAX, mark ? CLOSE_RANGE_CLOEXEC : 0));
            assert(fcntl(7, F_GETFD) == 0 && fcntl(256, F_GETFD) == 0);
            if (mark) {
                assert(fcntl(source, F_GETFD) == FD_CLOEXEC);
                assert(!dl_close_range(3, UINT_MAX, 0));
            }
            assert(fcntl(source, F_GETFD) == -1 && errno == EBADF);
            assert(open("/dev/null", O_RDONLY) >= 0);
            _exit(0);
        }
        int status; assert(waitpid(child, &status, 0) == child);
        assert(WIFEXITED(status) && WEXITSTATUS(status) == 0);
    }
    puts("PASS close/CLOEXEC at EMFILE with private FDs below and above reduced RLIMIT");
    return 0;
}
