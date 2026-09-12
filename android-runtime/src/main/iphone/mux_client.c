/* SPDX-License-Identifier: GPL-3.0-or-later
 * Only in-process socketpairs may reach usbmuxd. No public socket, TCP fallback,
 * or USBMUXD_SOCKET_ADDRESS supplied by a terminal is used by this port.
 */
#define socket_connect_unix terminal_mux_connect
#include <libusbmuxd.c>
