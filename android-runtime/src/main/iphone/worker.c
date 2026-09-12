/* SPDX-License-Identifier: GPL-3.0-or-later
 * One service-owned, deadline-bounded operation per process. The upstream mux
 * server and libimobiledevice client communicate only through socketpairs.
 */
#define _GNU_SOURCE 1
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/resource.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <unistd.h>
#include <libimobiledevice/libimobiledevice.h>
#include <libimobiledevice/lockdown.h>
#include <usbmuxd.h>
#include "utils.h"
#include "device.h"
#include "client.h"
#include "usb.h"
#include "conf.h"
#include "log.h"
#include "afc_limits.h"

int terminal_usb_init(int fd);
int terminal_store_init(const char *path, const char *host_buid);
int terminal_store_status(void);
int terminal_crypto_self_test(void);
int terminal_afc_path_valid(const char *path);
int terminal_afc_run(idevice_t device, lockdownd_client_t lockdown, const char *action, const char *path, unsigned size);
int no_preflight = 1; /* Pairing is explicitly requested, never automatic. */
static int queue_fds[2], owner_fd = -1, usb_enabled;

int terminal_mux_connect(const char *ignored)
{
    (void)ignored;
    int pair[2];
    if (socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0, pair)) return -1;
    if (send(queue_fds[1], &pair[0], sizeof(int), MSG_NOSIGNAL) != sizeof(int)) {
        close(pair[0]); close(pair[1]); return -1;
    }
    return pair[1];
}

static void *mux_loop(void *ignored)
{
    (void)ignored;
    struct fdlist list;
    fdlist_create(&list);
    for (;;) {
        fdlist_reset(&list);
        fdlist_add(&list, FD_LISTEN, queue_fds[0], POLLIN);
        if (owner_fd >= 0) fdlist_add(&list, FD_LISTEN, owner_fd, POLLIN);
        client_get_fds(&list);
        if (usb_enabled) usb_get_fds(&list);
        int ready = poll(list.fds, list.count, 10);
        if (ready < 0 && errno != EINTR) _exit(71);
        for (int i = 0; ready > 0 && i < list.count; i++) {
            if (!list.fds[i].revents) continue;
            if (list.fds[i].fd == owner_fd) _exit(72); /* service death/revoke */
            if (list.fds[i].fd == queue_fds[0]) {
                int fd;
                if (recv(queue_fds[0], &fd, sizeof(fd), 0) == sizeof(fd)) client_accept(fd);
            } else if (list.owners[i] == FD_CLIENT) {
                client_process(list.fds[i].fd, list.fds[i].revents);
            }
        }
        if (usb_enabled && usb_process() < 0) _exit(73);
        device_check_timeouts();
    }
    return NULL;
}

static int emit_error(const char *error, int code)
{
    /* Never forward upstream diagnostics, identifiers, plist or secret values. */
    printf("{\"ok\":false,\"error\":\"%s\",\"code\":%d}\n", error, code);
    fflush(stdout);
    return 1;
}

static int emit_lockdown_error(int status)
{
    switch (status) {
    case LOCKDOWN_E_PASSWORD_PROTECTED: return emit_error("IPHONE_UNLOCK_REQUIRED", status);
    case LOCKDOWN_E_USER_DENIED_PAIRING: return emit_error("IPHONE_TRUST_DENIED", status);
    case LOCKDOWN_E_PAIRING_DIALOG_RESPONSE_PENDING: return emit_error("IPHONE_TRUST_PENDING_RETRY", status);
    case LOCKDOWN_E_PAIRING_PROHIBITED_OVER_THIS_CONNECTION: return emit_error("IPHONE_PAIRING_PROHIBITED", status);
    default: return emit_error("IPHONE_TRUST_OR_PAIRING_FAILED", status);
    }
}

static int receive_usb(const char *socket_name)
{
    struct sockaddr_un address = { .sun_family = AF_UNIX };
    size_t length = strlen(socket_name);
    if (!length || length >= sizeof(address.sun_path) - 1) return -1;
    memcpy(address.sun_path + 1, socket_name, length);
    owner_fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (owner_fd < 0 || connect(owner_fd, (struct sockaddr *)&address,
        offsetof(struct sockaddr_un, sun_path) + 1 + length)) return -1;
    char token[64];
    if (!fgets(token, sizeof(token), stdin)) return -1;
    size_t count = strlen(token);
    if (count != 37 || token[36] != '\n' || send(owner_fd, token, count, MSG_NOSIGNAL) != (ssize_t)count) return -1;
    char marker;
    union { struct cmsghdr align; char bytes[CMSG_SPACE(sizeof(int))]; } control;
    struct iovec iov = { &marker, 1 };
    struct msghdr message = { .msg_iov = &iov, .msg_iovlen = 1,
        .msg_control = control.bytes, .msg_controllen = sizeof(control.bytes) };
    if (recvmsg(owner_fd, &message, MSG_CMSG_CLOEXEC) != 1 || marker != 'U' ||
        (message.msg_flags & (MSG_CTRUNC | MSG_TRUNC))) return -1;
    struct cmsghdr *header = CMSG_FIRSTHDR(&message);
    if (!header || header->cmsg_level != SOL_SOCKET || header->cmsg_type != SCM_RIGHTS ||
        header->cmsg_len != CMSG_LEN(sizeof(int))) return -1;
    int fd; memcpy(&fd, CMSG_DATA(header), sizeof(fd));
    return fd;
}

static int run_operation(const char *operation, const char *path, unsigned size)
{
    idevice_t device = NULL;
    int status = IDEVICE_E_NO_DEVICE;
    for (int i = 0; i < 100; i++) {
        status = idevice_new_with_options(&device, NULL, IDEVICE_LOOKUP_USBMUX);
        if (status == IDEVICE_E_SUCCESS) break;
        usleep(100000);
    }
    if (!device) return emit_error("IPHONE_NOT_READY_UNLOCK_AND_RETRY", status);
    lockdownd_client_t lockdown = NULL;
    status = lockdownd_client_new(device, &lockdown, "Terminal");
    if (status != LOCKDOWN_E_SUCCESS) return emit_error("LOCKDOWN_CONNECTION_FAILED", status);
    if (!strcmp(operation, "pair")) {
        status = lockdownd_pair(lockdown, NULL);
    } else if (!strcmp(operation, "unpair")) {
        status = lockdownd_unpair(lockdown, NULL);
    } else {
        char *udid = NULL, *host_id = NULL, *session_id = NULL;
        idevice_get_udid(device, &udid);
        config_device_record_get_host_id(udid, &host_id);
        free(udid);
        if (!host_id) return emit_error("PAIR_REQUIRED", 0);
        status = lockdownd_start_session(lockdown, host_id, &session_id, NULL);
        free(host_id); free(session_id);
        if (status != LOCKDOWN_E_SUCCESS) return emit_error("PAIRING_INVALID_OR_DEVICE_LOCKED", status);
        if (!strncmp(operation, "afc-", 4)) {
            int result = terminal_afc_run(device, lockdown, operation, path, size);
            lockdownd_client_free(lockdown); idevice_free(device);
            return result;
        }
        const char *keys[] = { "DeviceName", "ProductType", "ProductVersion", "BuildVersion", "DeviceClass" };
        plist_t result = plist_new_dict();
        for (size_t i = 0; i < sizeof(keys) / sizeof(keys[0]); i++) {
            plist_t value = NULL;
            status = lockdownd_get_value(lockdown, NULL, keys[i], &value);
            if (status != LOCKDOWN_E_SUCCESS || !value || plist_get_node_type(value) != PLIST_STRING) {
                plist_free(value); plist_free(result);
                return emit_error("IPHONE_INFO_FAILED", status);
            }
            uint64_t length;
            const char *text = plist_get_string_ptr(value, &length);
            if (!text || length > 256) {
                plist_free(value); plist_free(result); return emit_error("IPHONE_INFO_TOO_LARGE", 0);
            }
            plist_dict_set_item(result, keys[i], value);
        }
        char *json = NULL; uint32_t length = 0;
        plist_to_json(result, &json, &length, 0);
        if (!json || length > 8192) return emit_error("IPHONE_INFO_ENCODING_FAILED", 0);
        printf("{\"ok\":true,\"result\":%s}\n", json);
        free(json); plist_free(result);
        lockdownd_client_free(lockdown); idevice_free(device);
        return 0;
    }
    lockdownd_client_free(lockdown); idevice_free(device);
    if (status != LOCKDOWN_E_SUCCESS) return emit_lockdown_error(status);
    if (terminal_store_status()) return emit_error("PAIR_STORE_WRITE_FAILED", terminal_store_status());
    printf("{\"ok\":true,\"result\":{\"operation\":\"%s\",\"completed\":true}}\n", operation);
    return 0;
}

static int self_test(void)
{
    if (!terminal_afc_path_valid("/DCIM/100APPLE") || terminal_afc_path_valid("/../secret") ||
        terminal_afc_path_valid("/a//b") || terminal_afc_path_valid("/a\\b") ||
        !terminal_afc_lengths(48, 40, 40) || terminal_afc_lengths(UINT64_MAX, 40, 40) ||
        terminal_afc_lengths(40, 48, 40)) return emit_error("SELF_TEST_AFC_BOUNDARY", 0);
    if (terminal_crypto_self_test()) return emit_error("SELF_TEST_PAIRING_CRYPTO", 0);
    usbmuxd_device_info_t *list = NULL;
    if (usbmuxd_get_device_list(&list) != 0) return emit_error("SELF_TEST_ENUMERATION", 0);
    usbmuxd_device_list_free(&list);
    char *value = NULL;
    if (usbmuxd_read_buid(&value) || !value || strcmp(value, "terminal-self-test")) return emit_error("SELF_TEST_BUID", 0);
    free(value);
    const char *id = "00000000-0000000000000000";
    char record[] = "<plist version=\"1.0\"><dict><key>HostID</key><string>test</string></dict></plist>";
    if (usbmuxd_save_pair_record(id, record, strlen(record))) return emit_error("SELF_TEST_SAVE", 0);
    uint32_t size = 0;
    if (usbmuxd_read_pair_record(id, &value, &size) || size != strlen(record) || memcmp(value, record, size))
        return emit_error("SELF_TEST_READ", 0);
    free(value);
    if (usbmuxd_delete_pair_record(id) || config_set_device_record("../escape", record, strlen(record)) != -EINVAL)
        return emit_error("SELF_TEST_BOUNDARY", 0);
    puts("{\"ok\":true,\"result\":{\"selfTest\":true,\"publicMuxSocket\":false}}");
    return 0;
}

int main(int argc, char **argv)
{
    umask(077);
    signal(SIGPIPE, SIG_IGN);
    alarm(25); /* Includes connect, initialization, user trust wait and cleanup. */
    prctl(PR_SET_DUMPABLE, 0); /* Pairing material must not enter native dumps. */
    prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0);
    struct rlimit files = { 256 * 1024, 256 * 1024 };
    struct rlimit core = { 0, 0 };
    if (setrlimit(RLIMIT_FSIZE, &files) || setrlimit(RLIMIT_CORE, &core))
        return emit_error("WORKER_LIMITS_FAILED", 0);
    unsetenv("USBMUXD_SOCKET_ADDRESS");
    log_level = LL_FATAL;
    int test = argc == 3 && !strcmp(argv[1], "--self-test");
    int afc = argc == 7 && (!strcmp(argv[1], "afc-list") || !strcmp(argv[1], "afc-get") || !strcmp(argv[1], "afc-put"));
    if (!test && !afc && (argc != 5 || (strcmp(argv[1], "pair") && strcmp(argv[1], "info") && strcmp(argv[1], "unpair"))))
        return emit_error("INVALID_OPERATION", 0);
    unsigned size = 0;
    if (afc) {
        if (!terminal_afc_path_valid(argv[5]) || !*argv[6] || strlen(argv[6]) > 8 || strspn(argv[6], "0123456789") != strlen(argv[6]))
            return emit_error("INVALID_AFC_ARGUMENT", 0);
        unsigned long value = strtoul(argv[6], NULL, 10);
        if (value > TERMINAL_FILE_LIMIT) return emit_error("INVALID_AFC_ARGUMENT", 0);
        size = (unsigned)value;
        alarm(120);
    }
    if (terminal_store_init(argv[2], test ? "terminal-self-test" : argv[4])) return emit_error("PAIR_STORE_UNAVAILABLE", 0);
    if (socketpair(AF_UNIX, SOCK_DGRAM | SOCK_CLOEXEC, 0, queue_fds)) return emit_error("MUX_QUEUE_FAILED", 0);
    client_init(); device_init();
    if (!test) {
        int fd = receive_usb(argv[3]);
        if (fd < 0) return emit_error("USB_FD_HANDOFF_FAILED", 0);
        if (terminal_usb_init(fd)) return emit_error("USB_MUX_INTERFACE_UNAVAILABLE", 0);
        usb_enabled = 1;
    }
    pthread_t thread;
    if (pthread_create(&thread, NULL, mux_loop, NULL)) return emit_error("MUX_THREAD_FAILED", 0);
    int result = test ? self_test() : run_operation(argv[1], afc ? argv[5] : "", size);
    fflush(stdout);
    /* Process exit closes all FDs and pending URBs; no thread/global state leaks
     * into another device or grant. Parent also has a hard deadline and revokes. */
    _exit(result);
}
