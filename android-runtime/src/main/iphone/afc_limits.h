/* SPDX-License-Identifier: GPL-3.0-or-later */
#ifndef TERMINAL_AFC_LIMITS_H
#define TERMINAL_AFC_LIMITS_H
#include <stdint.h>
#define TERMINAL_FILE_LIMIT (64u * 1024u * 1024u)
#define TERMINAL_FILE_CHUNK 16384u
static inline int terminal_afc_lengths(uint64_t entire, uint64_t part, uint64_t header)
{
    return part >= header && entire >= part && entire <= header + 1024u * 1024u;
}
#endif
