/* SPDX-License-Identifier: GPL-3.0-or-later
 * Host-only pairing records. Bounded, no symlinks, atomic replace, owner-only.
 */
#include <ctype.h>
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <stdio.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/random.h>
#include <unistd.h>
#include "conf.h"

#define MAX_RECORD (128 * 1024)
static int directory = -1;
static char *buid;
static atomic_int store_error;
int terminal_store_status(void) { return atomic_load(&store_error); }

int terminal_store_init(const char *path, const char *host_buid)
{
    directory = open(path, O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
    struct stat st;
    if (directory < 0 || fstat(directory, &st) || st.st_uid != getuid() || (st.st_mode & 077)) return -1;
    buid = strdup(host_buid);
    return buid ? 0 : -1;
}

static int filename(const char *udid, char *result)
{
    if (!udid || strlen(udid) < 8 || strlen(udid) > 64) return -EINVAL;
    for (const unsigned char *p = (const unsigned char *)udid; *p; p++)
        if (!isxdigit(*p) && *p != '-') return -EINVAL;
    snprintf(result, 72, "%s.plist", udid);
    return 0;
}

void config_get_system_buid(char **value) { *value = buid ? strdup(buid) : NULL; }

int config_get_device_record(const char *udid, char **data, uint64_t *size)
{
    char name[72];
    *data = NULL; *size = 0;
    if (filename(udid, name)) return -EINVAL;
    int fd = openat(directory, name, O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
    if (fd < 0) return -errno;
    struct stat st;
    int result = -EIO;
    if (!fstat(fd, &st) && S_ISREG(st.st_mode) && st.st_uid == getuid() &&
        !(st.st_mode & 077) && st.st_size > 0 && st.st_size <= MAX_RECORD) {
        char *buffer = malloc(st.st_size);
        size_t used = 0;
        if (buffer) {
            while (used < (size_t)st.st_size) {
                ssize_t count = read(fd, buffer + used, st.st_size - used);
                if (count < 0 && errno == EINTR) continue;
                if (count <= 0) break;
                used += count;
            }
            if (used == (size_t)st.st_size) { *data = buffer; *size = used; result = 0; }
            else free(buffer);
        }
    }
    close(fd);
    return result;
}

static int save_record(const char *udid, char *data, uint64_t size)
{
    char name[72], temp[96];
    if (filename(udid, name) || !data || !size || size > MAX_RECORD) return -EINVAL;
    /* A killed writer must not block a later writer when Android reuses its PID. */
    uint64_t random;
    ssize_t count;
    do { count = getrandom(&random, sizeof(random), 0); } while (count < 0 && errno == EINTR);
    if (count != sizeof(random)) return -EIO;
    snprintf(temp, sizeof(temp), ".%s.%016" PRIx64, name, random);
    int fd = openat(directory, temp, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
    if (fd < 0) return -errno;
    uint64_t used = 0;
    while (used < size) {
        ssize_t count = write(fd, data + used, size - used);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) break;
        used += count;
    }
    int result = used == size && !fsync(fd) ? 0 : -EIO;
    close(fd);
    if (!result && renameat(directory, temp, directory, name)) result = -errno;
    if (!result && fsync(directory)) result = -errno;
    if (result) unlinkat(directory, temp, 0);
    return result;
}

static int remove_record(const char *udid)
{
    char name[72];
    if (filename(udid, name)) return -EINVAL;
    if (unlinkat(directory, name, 0) && errno != ENOENT) return -errno;
    return fsync(directory) ? -errno : 0;
}

int config_set_device_record(const char *udid, char *data, uint64_t size)
{
    int result = save_record(udid, data, size);
    atomic_store(&store_error, result);
    return result;
}

int config_remove_device_record(const char *udid)
{
    int result = remove_record(udid);
    atomic_store(&store_error, result);
    return result;
}

int config_has_device_record(const char *udid)
{
    char *data; uint64_t size;
    int result = config_get_device_record(udid, &data, &size);
    free(data);
    return result == 0;
}

void config_device_record_get_host_id(const char *udid, char **host_id)
{
    char *data; uint64_t size;
    *host_id = NULL;
    if (!config_get_device_record(udid, &data, &size)) {
        plist_t record = NULL;
        plist_from_memory(data, (uint32_t)size, &record, NULL);
        if (record) {
            plist_get_string_val(plist_dict_get_item(record, "HostID"), host_id);
            plist_free(record);
        }
        free(data);
    }
}
