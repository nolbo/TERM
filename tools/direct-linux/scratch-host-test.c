/* SPDX-License-Identifier: Apache-2.0 */
/* Actual kernel clone/exec around the production scratch allocator. This is
 * a component test, not a claim that guest shared-VM dispatch is supported. */
#define _GNU_SOURCE
#include "runtime.h"
#include <assert.h>
#include <errno.h>
#include <sched.h>
#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/resource.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>

struct dl_state dl_state;
struct job {
    struct dl_scratch *scratch;
    void *payloads[3], *committed;
    const char *executable;
    int exec, error, guest_tid;
};
#define CHECK_CHILD(c) do { if (!(c)) { job->error = __LINE__; return 90; } } while (0)

static int child(void *opaque)
{
    struct job *job = opaque;
    job->guest_tid = (int)DL_CALL(SYS_gettid, 0, 0, 0, 0, 0, 0);
    CHECK_CHILD(DL_CALL(SYS_set_tid_address, &job->guest_tid, 0, 0, 0, 0, 0) == job->guest_tid);
    for (unsigned i = 0; i < 3; i++) {
        long p = dl_scratch_map(&job->scratch, (i + 1) * dl_state.page, PROT_READ | PROT_WRITE);
        CHECK_CHILD(p > 0); job->payloads[i] = (void *)p;
        *(unsigned char *)p = (unsigned char)(i + 1);
    }
    long committed = dl_scratch_map(&job->scratch, dl_state.page, PROT_READ | PROT_WRITE);
    CHECK_CHILD(committed > 0); job->committed = (void *)committed;
    *(unsigned char *)committed = 73;
    dl_scratch_detach((void *)committed);
    if (job->exec) {
        char *args[] = {(char *)job->executable, "--after-exec", NULL};
        char *env[] = {NULL};
        // A failed exec must leave every preparation mapping usable.
        CHECK_CHILD(DL_CALL(SYS_execve, "/direct-linux-missing-executable", args, env, 0, 0, 0) == -ENOENT);
        for (unsigned i = 0; i < 3; i++) CHECK_CHILD(*(unsigned char *)job->payloads[i] == i + 1);
        CHECK_CHILD(DL_CALL(SYS_execve, job->executable, args, env, 0, 0, 0) == 0);
        return 91;
    }
    return 0;
}

static void unmapped(void *address)
{
    unsigned char resident;
    assert(DL_CALL(SYS_mincore, address, dl_state.page, &resident, 0, 0, 0) == -ENOMEM);
}

static void local_ownership(void)
{
    struct dl_scratch *owner = NULL;
    void *pointers[512];
    for (unsigned i = 0; i < 512; i++) {
        long p = dl_scratch_map(&owner, dl_state.page + 1, PROT_READ | PROT_WRITE);
        assert(p > 0); pointers[i] = (void *)p;
        ((unsigned char *)p)[dl_state.page] = 63;
    }
    // Exercise removal of interior records, not just reverse-order frees.
    for (unsigned i = 1; i < 512; i += 2) dl_scratch_unmap(pointers[i]);
    dl_scratch_detach(pointers[0]);
    dl_scratch_clear(&owner); assert(!owner);
    for (unsigned i = 1; i < 512; i++) unmapped(pointers[i]);
    assert(((unsigned char *)pointers[0])[dl_state.page] == 63);
    assert(!munmap(pointers[0], 2 * dl_state.page));
    assert(dl_scratch_map(&owner, 0, PROT_READ) == -ENOMEM);
    assert(dl_scratch_map(&owner, SIZE_MAX, PROT_READ) == -ENOMEM);
    assert(dl_scratch_map(&owner, dl_state.page, -1) == -EINVAL && !owner);
    long guarded = dl_scratch_map(&owner, 3 * dl_state.page, PROT_NONE); assert(guarded > 0);
    assert(!mprotect((void *)(guarded + dl_state.page), dl_state.page, PROT_READ | PROT_WRITE));
    *(unsigned char *)(guarded + dl_state.page) = 37;
    struct rlimit saved, exhausted = {0, 0};
    assert(!getrlimit(RLIMIT_AS, &saved)); exhausted.rlim_max = saved.rlim_max;
    assert(!setrlimit(RLIMIT_AS, &exhausted));
    assert(dl_scratch_map(&owner, dl_state.page, PROT_READ | PROT_WRITE) == -ENOMEM);
    assert(!setrlimit(RLIMIT_AS, &saved));
    assert(*(unsigned char *)(guarded + dl_state.page) == 37);
    dl_scratch_clear(&owner); assert(!owner); unmapped((void *)guarded);
    puts("PASS dynamic ownership, unordered free, guest transfer, guards, overflow and allocation failure");
}

int main(int argc, char **argv)
{
    if (argc == 2 && !strcmp(argv[1], "--after-exec")) {
        // The parent reclaims the old VM's preparation while this new image
        // remains alive. No child-TID replacement is used as a detach signal.
        assert(!raise(SIGSTOP)); return 0;
    }
#ifdef __ANDROID__
    assert(geteuid() >= 10000);
#endif
    alarm(20);
    FILE *policy = fopen("/proc/self/status", "r"); assert(policy);
    char line[256];
    while (fgets(line, sizeof(line), policy))
        if (!strncmp(line, "TracerPid:", 10) || !strncmp(line, "CapEff:", 7) ||
                !strncmp(line, "Seccomp:", 8) || !strncmp(line, "NoNewPrivs:", 11)) printf("POLICY %s", line);
    assert(!fclose(policy));
    dl_state.page = (size_t)sysconf(_SC_PAGESIZE);
    local_ownership();
    char executable[4096];
    ssize_t length = readlink("/proc/self/exe", executable, sizeof(executable) - 1);
    assert(length > 0 && (size_t)length < sizeof(executable) - 1); executable[length] = 0;
    const size_t stack_size = 128 * 1024;
    void *stack = mmap(NULL, stack_size, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    assert(stack != MAP_FAILED);
    struct dl_scratch *parent = NULL;
    long sentinel = dl_scratch_map(&parent, dl_state.page, PROT_READ | PROT_WRITE); assert(sentinel > 0);
    *(unsigned char *)sentinel = 29;
    for (unsigned scenario = 0; scenario < 3; scenario++) for (unsigned i = 0; i < 64; i++) {
        struct job job = {.executable = executable, .exec = scenario != 0};
        int flags = CLONE_VM | SIGCHLD | (scenario == 2 ? CLONE_VFORK : 0);
        pid_t pid = clone(child, (char *)stack + stack_size, flags, &job); assert(pid > 0);
        int status; assert(waitpid(pid, &status, WUNTRACED) == pid);
        if (job.error) fprintf(stderr, "child failed at line %d\n", job.error);
        assert(!job.error);
        if (job.exec) assert(WIFSTOPPED(status) && WSTOPSIG(status) == SIGSTOP);
        else assert(WIFEXITED(status) && WEXITSTATUS(status) == 0);
        // waitpid establishes exit or the new executable's explicit stop.
        // At that point the old stack is unused; guest clear-TID also ran.
        assert(job.guest_tid == 0 && job.scratch);
        for (unsigned j = 0; j < 3; j++) assert(*(unsigned char *)job.payloads[j] == j + 1);
        dl_scratch_clear(&job.scratch); assert(!job.scratch);
        for (unsigned j = 0; j < 3; j++) unmapped(job.payloads[j]);
        assert(*(unsigned char *)job.committed == 73 && *(unsigned char *)sentinel == 29);
        assert(!munmap(job.committed, dl_state.page));
        if (job.exec) {
            assert(!kill(pid, SIGCONT)); assert(waitpid(pid, &status, 0) == pid);
            assert(WIFEXITED(status) && WEXITSTATUS(status) == 0);
        }
    }
    dl_scratch_clear(&parent); assert(!parent); unmapped((void *)sentinel);
    assert(!munmap(stack, stack_size));
    puts("PASS 192 real shared-VM clones: exit/exec/vfork reclamation, live new image, independent owners and guest clear-TID");
    return 0;
}
