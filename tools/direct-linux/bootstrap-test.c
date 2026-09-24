/* SPDX-License-Identifier: Apache-2.0 */
/* Actual fork from a surviving alternate stack; child never calls libc. */
#define _GNU_SOURCE
#include "runtime.h"
#include <assert.h>
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/auxv.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>
#include "bootstrap.h"

struct dl_state dl_state;
static unsigned char *bootstrap_memory, *guest_memory, *guest_excluded;
static volatile unsigned char runtime_bss[32768];
static volatile sig_atomic_t status_code;
static int child_status;
static long child_result;
static unsigned char *clock_mapping;

static int child_checks(void)
{
    unsigned char residency;
    if (DL_CALL(SYS_mincore, bootstrap_memory, dl_state.page, &residency, 0, 0, 0) != -ENOMEM) return 1;
    if (DL_CALL(SYS_mincore, guest_excluded, dl_state.page, &residency, 0, 0, 0) != -ENOMEM) return 2;
    if (DL_CALL(SYS_mincore, guest_memory, dl_state.page, &residency, 0, 0, 0)) return 3;
    if (guest_memory[0] != 37 || guest_memory[dl_state.page - 1] != 91) return 4;
    if (runtime_bss[0] != 12 || runtime_bss[sizeof(runtime_bss) - 1] != 34) return 5;
    if (clock_mapping && DL_CALL(SYS_mincore, clock_mapping, dl_state.page, &residency, 0, 0, 0)) return 6;
    return 0;
}

static void handler(int signal)
{
    (void)signal;
    long child = DL_CALL(SYS_clone, SIGCHLD, 0, 0, 0, 0, 0);
    if (!child) {
        int result = child_checks();
        if (!result) {
            long descendant = DL_CALL(SYS_clone, SIGCHLD, 0, 0, 0, 0, 0);
            if (!descendant) {
                DL_CALL(SYS_exit_group, child_checks(), 0, 0, 0, 0, 0);
                __builtin_unreachable();
            }
            int status = -1;
            if (descendant < 0 || DL_CALL(SYS_wait4, descendant, &status, 0, 0, 0, 0) != descendant || status)
                result = 7;
        }
        guest_memory[0] = 0; runtime_bss[0] = 0;
        DL_CALL(SYS_exit_group, result, 0, 0, 0, 0, 0);
        __builtin_unreachable();
    }
    int status = -1;
    child_result = child;
    status_code = child > 0 && DL_CALL(SYS_wait4, child, &status, 0, 0, 0, 0) == child && !status ? 1 : -1;
    child_status = status;
}

int main(void)
{
    dl_state.page = (size_t)sysconf(_SC_PAGESIZE);
    bootstrap_memory = mmap(NULL, 3 * dl_state.page, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    assert(bootstrap_memory != MAP_FAILED);
    bootstrap_memory += dl_state.page;
    assert(!mprotect(bootstrap_memory, dl_state.page, PROT_READ | PROT_WRITE));
    bootstrap_memory[0] = 55;
    runtime_bss[0] = 12; runtime_bss[sizeof(runtime_bss) - 1] = 34;
    clock_mapping = (void *)getauxval(AT_SYSINFO_EHDR);
    assert(!bootstrap_fork_policy());
    // Guest mappings and the alternate stack are allocated after preparation.
    guest_memory = mmap(NULL, dl_state.page, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    guest_excluded = mmap(NULL, dl_state.page, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    assert(guest_memory != MAP_FAILED && guest_excluded != MAP_FAILED);
    guest_memory[0] = 37; guest_memory[dl_state.page - 1] = 91;
    assert(!madvise(guest_excluded, dl_state.page, MADV_DONTFORK));
    stack_t stack = {.ss_size = 256 * 1024};
    stack.ss_sp = mmap(NULL, stack.ss_size, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    assert(stack.ss_sp != MAP_FAILED && !sigaltstack(&stack, NULL));
    struct sigaction action = {.sa_handler = handler, .sa_flags = SA_ONSTACK};
    sigemptyset(&action.sa_mask); assert(!sigaction(SIGUSR1, &action, NULL));
    assert(!raise(SIGUSR1));
    if (status_code != 1) fprintf(stderr, "fork result=%ld child status=%d\n", child_result, child_status);
    assert(status_code == 1);
    assert(bootstrap_memory[0] == 55 && guest_memory[0] == 37 && runtime_bss[0] == 12);
    puts("PASS bootstrap omission, runtime/BSS/vDSO/guest/alternate-stack inheritance, nested fork and parent isolation");
    return 0;
}
