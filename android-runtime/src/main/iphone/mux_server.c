/* SPDX-License-Identifier: GPL-3.0-or-later */
#include <sys/socket.h>
static int terminal_accept(int fd, struct sockaddr *address, socklen_t *size)
{
    (void)address;
    *size = 0;
    return fd; /* transferred from our internal socketpair queue, never listen() */
}
#define accept terminal_accept
#include <terminal_mux_client.inc>
