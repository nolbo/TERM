/* SPDX-License-Identifier: GPL-3.0-or-later
 * Upstream's Lockdown receive buffer allocation accepts a device-provided u32.
 * This worker only exchanges small pairing records and single information keys.
 * Bound that allocation without limiting Bionic/Scudo's virtual reservations.
 */
#include <stdlib.h>
static void *terminal_plist_malloc(size_t length)
{
    if (!length || length > 1024 * 1024) return NULL;
    return malloc(length);
}
#define malloc terminal_plist_malloc
#include <property_list_service.c>
