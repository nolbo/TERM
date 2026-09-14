/* SPDX-License-Identifier: GPL-2.0-or-later */
#ifndef PROOT_FASTSTAT_PROTOCOL_H
#define PROOT_FASTSTAT_PROTOCOL_H

/* Experimental AArch64 ABI. Never enable this as a security boundary. */
#define PROOT_FASTSTAT_QUERY 0x50525346
#define PROOT_FASTSTAT_VERSION 1
/* PRoot reserves 0x20... for its loader, 0x30... for ELF, 0x3f... for ld.so. */
#define PROOT_FASTSTAT_ADDRESS 0x4000000000ULL
/* AArch64 seccomp sees the PC immediately following SVC. */
#define PROOT_FASTSTAT_PC (PROOT_FASTSTAT_ADDRESS + 4)
#define PROOT_FASTSTAT_MAPPING_SIZE 16384

#endif
