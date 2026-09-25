/* SPDX-License-Identifier: Apache-2.0 */
/* Diagnostic reproductions against the committed runtime, not a passing CI test. */
#define _GNU_SOURCE
#include "runtime.h"
#include <assert.h>
#include <errno.h>
#include <limits.h>
#include <stdio.h>
#include <sys/mman.h>
#include <sys/resource.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <fcntl.h>
#include <unistd.h>
struct dl_state dl_state;
long dl_host_syscall(long n, long a, long b, long c, long d, long e, long f)
{
    long r = syscall(n, a, b, c, d, e, f);
    return r < 0 ? -errno : r;
}
static void mapping_child(void)
{
    size_t page = dl_state.page = sysconf(_SC_PAGESIZE);
    long address = dl_mmap(0, page * 3, PROT_READ | PROT_WRITE,
        MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    assert(address > 0);
    void *hole = (void *)(address + page);
    assert(!madvise(hole, page, MADV_DONTFORK));
    pid_t pid = fork(); assert(pid >= 0);
    if (!pid) {
        unsigned char vec;
        assert(mincore(hole, page, &vec) == -1 && errno == ENOMEM);
        /* Model an untracked allocation made by dl_exec before dl_unmap_all. */
        void *fresh = mmap(hole, page, PROT_READ | PROT_WRITE,
            MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED_NOREPLACE, -1, 0);
        assert(fresh == hole);
        *(unsigned char *)fresh = 0x5a;
        dl_unmap_all();
        int mapped = mincore(fresh, page, &vec) == 0;
        printf("{\"case\":\"dontfork-ownership\",\"fresh_mapping_survived\":%s,\"reproduced\":%s}\n",
            mapped ? "true" : "false", mapped ? "false" : "true");
        fflush(stdout); _exit(mapped ? 1 : 0);
    }
    int status; assert(waitpid(pid, &status, 0) == pid);
    assert(WIFEXITED(status) && WEXITSTATUS(status) == 0);
    dl_unmap_all();
}
static void fd_child(void)
{
    pid_t pid = fork(); assert(pid >= 0);
    if (!pid) {
        struct rlimit limit; assert(!getrlimit(RLIMIT_NOFILE, &limit));
        assert(limit.rlim_max >= 64); limit.rlim_cur = 64;
        assert(!setrlimit(RLIMIT_NOFILE, &limit));
        while (open("/dev/null", O_RDONLY) >= 0) {}
        assert(errno == EMFILE);
        dl_state.root_fd = dl_state.vfork_fd = -1;
        long translated = dl_close_range(3, UINT_MAX, 0);
        long native = dl_host_syscall(SYS_close_range, 3, UINT_MAX, 0, 0, 0, 0);
        printf("{\"case\":\"close-range-emfile\",\"direct_result\":%ld,\"kernel_result\":%ld,\"reproduced\":%s}\n",
            translated, native, translated == -EMFILE && native == 0 ? "true" : "false");
        fflush(stdout); _exit(translated == -EMFILE && native == 0 ? 0 : 1);
    }
    int status; assert(waitpid(pid, &status, 0) == pid);
    assert(WIFEXITED(status) && WEXITSTATUS(status) == 0);
}
int main(void) { mapping_child(); fd_child(); return 0; }
