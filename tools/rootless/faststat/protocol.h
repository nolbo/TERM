/* SPDX-License-Identifier: GPL-2.0-or-later */
#ifndef PROOT_FASTSTAT_PROTOCOL_H
#define PROOT_FASTSTAT_PROTOCOL_H

/* Experimental AArch64 ABI. Never enable this as a security boundary. */
#define PROOT_FASTSTAT_QUERY 0x50525346
#define PROOT_FASTSTAT_VERSION 1
#define PROOT_FASTSTAT_CONTEXT_VERSION 2
#define PROOT_FASTSTAT_RESOLVE 0x50525347
#include <stdint.h>

/* Fixed-width request/reply shared by Android PRoot and guest glibc.
 * A fresh reply is obtained for every context-mode call; it is not a cache. */
struct proot_faststat_context {
    int32_t dirfd;
    int32_t flags;
    uint32_t host_uid, host_gid;
    uint32_t guest_uid, guest_gid;
    uint32_t fake_root;
    char path[4096];
};
_Static_assert(sizeof(struct proot_faststat_context) == 4124, "faststat protocol ABI");
/* PRoot reserves 0x20... for its loader, 0x30... for ELF, 0x3f... for ld.so. */
#define PROOT_FASTSTAT_ADDRESS 0x4000000000ULL
/* AArch64 seccomp sees the PC immediately following SVC. */
#define PROOT_FASTSTAT_PC (PROOT_FASTSTAT_ADDRESS + 4)
#define PROOT_FASTSTAT_MAPPING_SIZE 16384

#endif
