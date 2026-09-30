/* SPDX-License-Identifier: GPL-2.0-or-later */
#ifndef PROOT_FALLBACK_PROBE_H
#define PROOT_FALLBACK_PROBE_H
/* Test build only: count the dispatcher's ordinary helper syscalls. Raw guest
 * syscalls and the private fast trampoline are intentionally outside scope. */
static _Atomic unsigned long probe_masks, probe_pids;
static void probe_syscall(long number)
{
    if (number == SYS_rt_sigprocmask) atomic_fetch_add(&probe_masks, 1);
    if (number == SYS_getpid) atomic_fetch_add(&probe_pids, 1);
}
unsigned long proot_faststat_probe_count(long number)
{
    if (number == SYS_rt_sigprocmask) return atomic_load(&probe_masks);
    if (number == SYS_getpid) return atomic_load(&probe_pids);
    return 0;
}
#define syscall(number, ...) (probe_syscall(number), syscall(number, ##__VA_ARGS__))
#endif
