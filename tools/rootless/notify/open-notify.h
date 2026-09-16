/* SPDX-License-Identifier: GPL-2.0-or-later */
#ifndef PROOT_OPEN_NOTIFY_H
#define PROOT_OPEN_NOTIFY_H
#include "tracee/tracee.h"
int open_notify_prepare(const Tracee *tracee, int channel[2]);
int open_notify_child(int channel[2]);
int open_notify_parent(int channel[2]);
int open_notify_start(void);
void open_notify_stop(void);
pid_t open_notify_wait(int *status);
#endif
