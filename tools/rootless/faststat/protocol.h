/* SPDX-License-Identifier: GPL-2.0-or-later */
#ifndef PROOT_FASTSTAT_PROTOCOL_H
#define PROOT_FASTSTAT_PROTOCOL_H

/* Experimental AArch64 ABI. Never enable this as a security boundary. */
#define PROOT_FASTSTAT_QUERY 0x50525346
#define PROOT_FASTSTAT_VERSION 1
#define PROOT_FASTSTAT_CONTEXT_VERSION 4
#define PROOT_FASTSTAT_RESOLVE 0x50525347
#include <stdint.h>
#include <stdatomic.h>

#define PROOT_FASTFD_ATTACH 0x50525348
#define PROOT_FASTFD_VERSION 1
#define PROOT_FASTFD_MAPPING_SIZE 4096
/* Per-thread credentials only. File metadata is never stored here. */
struct proot_fastfd_identity {
    _Atomic uint32_t sequence, active, host_uid, host_gid, guest_uid, guest_gid;
};
_Static_assert(sizeof(struct proot_fastfd_identity) == 24, "fastfd identity ABI");
_Static_assert(ATOMIC_INT_LOCK_FREE == 2, "shared identity needs lock-free atomics");
struct tracee;
int fastfd_attach(struct tracee *tracee, int fd, unsigned version);
void fastfd_sync(struct tracee *tracee);
void fastfd_reset(struct tracee *tracee);
void forget_closed_fd(struct tracee *tracee, int fd);

/* Fixed-width request/reply shared by Android PRoot and guest glibc.
 * A fresh reply is obtained for every context-mode call; it is not a cache. */
struct proot_faststat_context {
    int32_t dirfd;
    int32_t flags;
    uint32_t host_uid, host_gid;
    uint32_t guest_uid, guest_gid;
    uint32_t fake_root;
    uint32_t emulated_nlink; /* Zero: ordinary object; otherwise fresh l2s count. */
    int32_t result_errno; /* Request: -1; reply: 0 or verified terminal ENOENT. */
    char path[4096];
};
_Static_assert(sizeof(struct proot_faststat_context) == 4132, "faststat protocol ABI");
/* PRoot reserves 0x20... for its loader, 0x30... for ELF, 0x3f... for ld.so. */
#define PROOT_FASTSTAT_ADDRESS 0x4000000000ULL
/* AArch64 seccomp sees the PC immediately following SVC. */
#define PROOT_FASTSTAT_PC (PROOT_FASTSTAT_ADDRESS + 4)
#define PROOT_FASTSTAT_MAPPING_SIZE 16384

#endif
