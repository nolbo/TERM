/* SPDX-License-Identifier: Apache-2.0 */
/* Bionic is used only during preparation. After entry all runtime code is
 * freestanding and must not touch Bionic TLS, PLT functions or allocators. */
#define _GNU_SOURCE
#include "runtime.h"
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/auxv.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

static int fail(const char *message)
{
    fprintf(stderr, "Direct Linux: %s\n", message); return -1;
}

static int check_policy(void)
{
    FILE *status = fopen("/proc/self/status", "r");
    if (!status) return fail("cannot inspect launch policy");
    int untraced = 0, unprivileged = 0;
    int report = getenv("DIRECT_LINUX_POLICY") && !strcmp(getenv("DIRECT_LINUX_POLICY"), "1");
    char line[256];
    while (fgets(line, sizeof(line), status)) {
        unsigned long long value;
        if (sscanf(line, "TracerPid: %llu", &value) == 1) untraced = value == 0;
        if (sscanf(line, "CapEff: %llx", &value) == 1) unprivileged = value == 0;
        if (report && (!strncmp(line, "TracerPid:", 10) || !strncmp(line, "CapEff:", 7) ||
                !strncmp(line, "NoNewPrivs:", 11) || !strncmp(line, "Seccomp:", 8))) fprintf(stderr, "POLICY %s", line);
    }
    fclose(status);
    return untraced && unprivileged && getuid() != 0 ? 0 : fail("ordinary untraced app/user execution required");
}

int main(int argc, char **argv)
{
    if (argc < 3 || argv[2][0] != '/') {
        fprintf(stderr, "usage: loader ROOT_DIRECTORY /guest/executable [arguments...]\n"); return 2;
    }
    if (check_policy()) return 2;
    dl_state.page = (size_t)sysconf(_SC_PAGESIZE);
    if (dl_state.page < 4096 || dl_state.page > 65536 || (dl_state.page & (dl_state.page - 1))) return 2;
    if (!realpath(argv[1], dl_state.root) || dl_equal(dl_state.root, "/")) { fail("an independent root directory is required"); return 2; }
    dl_state.trace = getenv("DIRECT_LINUX_TRACE") && !strcmp(getenv("DIRECT_LINUX_TRACE"), "1");
    dl_state.exec_profile = getenv("DIRECT_LINUX_EXEC_PROFILE") && !strcmp(getenv("DIRECT_LINUX_EXEC_PROFILE"), "1");
    dl_state.hwcap = getauxval(AT_HWCAP); dl_state.hwcap2 = getauxval(AT_HWCAP2);
    dl_state.vdso = getauxval(AT_SYSINFO_EHDR); dl_state.minsigstksz = getauxval(AT_MINSIGSTKSZ);
    dl_state.clktck = sysconf(_SC_CLK_TCK);
    int root = open(dl_state.root, O_PATH | O_DIRECTORY | O_CLOEXEC);
    if (root < 0) { fail("cannot open root directory"); return 2; }
    dl_state.root_fd = fcntl(root, F_DUPFD_CLOEXEC, 256); close(root);
    if (dl_state.root_fd < 0 || fchdir(dl_state.root_fd)) { fail("cannot enter root directory"); return 2; }
    char *environment[] = {"PATH=/usr/bin:/bin", "HOME=/", "LANG=C", "TMPDIR=/tmp", NULL};
    struct dl_entry entry;
    long result = dl_exec(argv[2], argv + 2, environment, &entry, 0);
    if (result) { fprintf(stderr, "Direct Linux: ELF preparation failed (%ld)\n", -result); return 126; }
    void *signals = mmap(NULL, 256 * 1024 + 2 * dl_state.page, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (signals == MAP_FAILED || mprotect((char *)signals + dl_state.page, 256 * 1024, PROT_READ | PROT_WRITE)) {
        fail("cannot construct signal stack"); return 126;
    }
    fflush(NULL);
    int installed = dl_install((char *)signals + dl_state.page, 256 * 1024);
    if (installed) {
        const char error[] = "Direct Linux: syscall dispatcher rejected by platform\n";
        DL_CALL(SYS_write, 2, error, sizeof(error) - 1, 0, 0, 0);
        DL_CALL(SYS_exit_group, 126, 0, 0, 0, 0, 0);
    }
    dl_enter(entry.pc, entry.sp);
}
