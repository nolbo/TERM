/* SPDX-License-Identifier: GPL-3.0-or-later
 * Standard com.apple.afc only. No AFC2, app-container services, delete API,
 * arbitrary services or host path access. Transfer data uses stdin/stdout pipes.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <libimobiledevice/afc.h>
#include "afc_limits.h"

static int failure(const char *name, int status)
{
    printf("{\"ok\":false,\"error\":\"%s\",\"code\":%d}\n", name, status);
    fflush(stdout);
    return 1;
}

int terminal_afc_path_valid(const char *path)
{
    if (!path || path[0] != '/' || strlen(path) > 1024) return 0;
    if (!strcmp(path, "/")) return 1;
    const char *component = path + 1;
    for (const unsigned char *p = (const unsigned char *)component; ; p++) {
        if (*p && (*p < 32 || *p == 127 || *p == '\\')) return 0;
        if (*p == '/' || !*p) {
            size_t n = (const char *)p - component;
            if (!n || (n == 1 && component[0] == '.') || (n == 2 && !memcmp(component, "..", 2))) return 0;
            component = (const char *)p + 1;
        }
        if (!*p) break;
    }
    return 1;
}

static int stat_path(afc_client_t afc, const char *path, const char *type, uint64_t *size)
{
    plist_t info = NULL;
    int status = afc_get_file_info_plist(afc, path, &info);
    if (status != AFC_E_SUCCESS) { plist_free(info); return status; }
    plist_t mode = info ? plist_dict_get_item(info, "st_ifmt") : NULL;
    uint64_t length = 0;
    const char *value = mode && plist_get_node_type(mode) == PLIST_STRING ? plist_get_string_ptr(mode, &length) : NULL;
    if (!value || strcmp(value, type)) status = AFC_E_PERM_DENIED; // No symlinks/special files.
    if (size && status == AFC_E_SUCCESS) {
        plist_t node = plist_dict_get_item(info, "st_size");
        if (!node || plist_get_node_type(node) != PLIST_UINT) status = AFC_E_READ_ERROR;
        else plist_get_uint_val(node, size);
    }
    plist_free(info);
    return status;
}

static int check_parents(afc_client_t afc, const char *path)
{
    char copy[1025];
    strcpy(copy, path);
    for (char *p = copy + 1; *p; p++) if (*p == '/') {
        *p = 0;
        int result = stat_path(afc, copy, "S_IFDIR", NULL);
        *p = '/';
        if (result) return result;
    }
    return 0;
}

static int list_directory(afc_client_t afc, const char *path)
{
    int status = stat_path(afc, path, "S_IFDIR", NULL);
    if (status) return failure("AFC_DIRECTORY_UNAVAILABLE", status);
    char **names = NULL;
    status = afc_read_directory(afc, path, &names);
    if (status || !names) return failure("AFC_LIST_FAILED", status);
    plist_t entries = plist_new_array();
    int count = 0, truncated = 0;
    for (char **name = names; *name; name++) {
        if (!strcmp(*name, ".") || !strcmp(*name, "..")) continue;
        if (count == 256) { truncated = 1; break; }
        size_t length = strlen(*name);
        if (!length || length > 255 || strchr(*name, '/')) { status = AFC_E_READ_ERROR; break; }
        for (unsigned char *c = (unsigned char *)*name; *c; c++) if (*c < 32 || *c == 127) status = AFC_E_READ_ERROR;
        if (status) break;
        plist_array_append_item(entries, plist_new_string(*name)); count++;
    }
    afc_dictionary_free(names);
    if (status) { plist_free(entries); return failure("AFC_INVALID_DIRECTORY_ENTRY", status); }
    plist_t result = plist_new_dict();
    plist_dict_set_item(result, "entries", entries);
    plist_dict_set_item(result, "truncated", plist_new_bool(truncated));
    char *json = NULL; uint32_t length = 0;
    plist_to_json(result, &json, &length, 0);
    if (!json || length > 48000) { free(json); plist_free(result); return failure("AFC_DIRECTORY_TOO_LARGE", 0); }
    printf("{\"ok\":true,\"result\":%s}\n", json);
    free(json); plist_free(result);
    return 0;
}

static int get_file(afc_client_t afc, const char *path)
{
    uint64_t size = 0, handle = 0;
    int status = stat_path(afc, path, "S_IFREG", &size);
    if (status) return failure("AFC_FILE_UNAVAILABLE", status);
    if (size > TERMINAL_FILE_LIMIT) return failure("AFC_FILE_TOO_LARGE", 0);
    if ((status = afc_file_open(afc, path, AFC_FOPEN_RDONLY, &handle))) return failure("AFC_OPEN_FAILED", status);
    printf("{\"event\":\"start\",\"size\":%u}\n", (unsigned)size); fflush(stdout);
    char bytes[TERMINAL_FILE_CHUNK], hex[TERMINAL_FILE_CHUNK * 2 + 1];
    unsigned offset = 0;
    while (offset < size) {
        uint32_t count = 0, wanted = size - offset > sizeof(bytes) ? sizeof(bytes) : (uint32_t)size - offset;
        status = afc_file_read(afc, handle, bytes, wanted, &count);
        if (status || !count || count > wanted) { status = AFC_E_READ_ERROR; break; }
        for (unsigned i = 0; i < count; i++) {
            hex[i * 2] = "0123456789abcdef"[(unsigned char)bytes[i] >> 4];
            hex[i * 2 + 1] = "0123456789abcdef"[(unsigned char)bytes[i] & 15];
        }
        hex[count * 2] = 0;
        printf("{\"event\":\"data\",\"offset\":%u,\"data\":\"%s\"}\n", offset, hex); fflush(stdout);
        offset += count;
    }
    int closed = afc_file_close(afc, handle);
    uint64_t after = 0;
    if (status || closed || stat_path(afc, path, "S_IFREG", &after) || after != size)
        return failure("AFC_FILE_CHANGED_OR_READ_FAILED", status ? status : closed);
    printf("{\"ok\":true,\"result\":{\"bytes\":%u,\"completed\":true}}\n", offset);
    return 0;
}

static int put_file(afc_client_t afc, const char *path, unsigned size)
{
    // Path is generated by Android, not supplied by a guest. Never overwrite a chosen user file.
    if (strncmp(path, "/TerminalImports/", 17) || strlen(path + 17) < 38 || strchr(path + 17, '/'))
        return failure("INVALID_AFC_UPLOAD_PATH", 0);
    int status = stat_path(afc, "/TerminalImports", "S_IFDIR", NULL);
    if (status == AFC_E_OBJECT_NOT_FOUND) status = afc_make_directory(afc, "/TerminalImports");
    if (status || stat_path(afc, "/TerminalImports", "S_IFDIR", NULL)) return failure("AFC_IMPORT_DIRECTORY_UNAVAILABLE", status);
    plist_t existing = NULL;
    status = afc_get_file_info_plist(afc, path, &existing);
    plist_free(existing);
    if (status != AFC_E_OBJECT_NOT_FOUND) return failure("AFC_UPLOAD_TARGET_EXISTS_OR_UNAVAILABLE", status);
    uint64_t handle = 0;
    if ((status = afc_file_open(afc, path, AFC_FOPEN_WRONLY, &handle))) return failure("AFC_OPEN_FAILED", status);
    puts("{\"event\":\"ready\"}"); fflush(stdout);
    char buffer[TERMINAL_FILE_CHUNK];
    unsigned offset = 0;
    while (offset < size) {
        unsigned count = size - offset > sizeof(buffer) ? sizeof(buffer) : size - offset;
        if (fread(buffer, 1, count, stdin) != count) { status = AFC_E_READ_ERROR; break; }
        unsigned written = 0;
        while (written < count) {
            uint32_t n = 0;
            status = afc_file_write(afc, handle, buffer + written, count - written, &n);
            if (status || !n || n > count - written) { status = AFC_E_WRITE_ERROR; break; }
            written += n;
        }
        if (status) break;
        offset += count;
    }
    int closed = afc_file_close(afc, handle);
    if (status || closed) {
        // Only this operation's freshly-created, host-randomized file is a cleanup target.
        afc_remove_path(afc, path);
        return failure("AFC_UPLOAD_INCOMPLETE", status ? status : closed);
    }
    uint64_t actual = 0;
    if (stat_path(afc, path, "S_IFREG", &actual) || actual != size) return failure("AFC_UPLOAD_SIZE_MISMATCH", 0);
    printf("{\"ok\":true,\"result\":{\"bytes\":%u,\"completed\":true}}\n", offset);
    return 0;
}

int terminal_afc_run(idevice_t device, lockdownd_client_t lockdown, const char *action, const char *path, unsigned size)
{
    if (!terminal_afc_path_valid(path) || size > TERMINAL_FILE_LIMIT) return failure("INVALID_AFC_ARGUMENT", 0);
    lockdownd_service_descriptor_t service = NULL;
    int status = lockdownd_start_service(lockdown, AFC_SERVICE_NAME, &service);
    if (status) return failure("AFC_SERVICE_UNAVAILABLE", status);
    afc_client_t afc = NULL;
    status = afc_client_new(device, service, &afc);
    lockdownd_service_descriptor_free(service);
    if (status) return failure("AFC_CONNECTION_FAILED", status);
    int result;
    if (!strcmp(action, "afc-put")) result = put_file(afc, path, size);
    else if ((status = check_parents(afc, path))) result = failure("AFC_PARENT_UNAVAILABLE_OR_SYMLINK", status);
    else if (!strcmp(action, "afc-list")) result = list_directory(afc, path);
    else result = get_file(afc, path);
    afc_client_free(afc);
    return result;
}
