/* SPDX-License-Identifier: Apache-2.0 */
/* ARM64 host test of the actual ELF reader/mapper with ordinary file syscalls.
 * Path/argument helpers below are a host adapter, not guest-path validation. */
#define _GNU_SOURCE
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "exec.c"

struct dl_state dl_state;
long dl_host_syscall(long n, long a, long b, long c, long d, long e, long f)
{
    long result = syscall(n, a, b, c, d, e, f); return result < 0 ? -errno : result;
}
size_t dl_length(const char *s) { return strlen(s); }
long dl_read_memory(void *d, const void *s, size_t n) { memcpy(d, s, n); return 0; }
long dl_string(char *d, const char *s) { if (strlen(s) >= DL_PATH) return -ENAMETOOLONG; strcpy(d, s); return 0; }
long dl_open(int fd, const char *path, int flags, unsigned mode)
{
    int result = openat(fd, path, flags, mode); return result < 0 ? -errno : result;
}
long dl_file_path(int fd, char out[DL_PATH])
{
    char name[64]; snprintf(name, sizeof(name), "/proc/self/fd/%d", fd);
    ssize_t n = readlink(name, out, DL_PATH - 1); if (n < 0) return -errno; out[n] = 0; return 0;
}
long dl_executable(int fd)
{
    char name[64]; snprintf(name, sizeof(name), "/proc/self/fd/%d", fd);
    return access(name, X_OK) ? -errno : 0;
}
long dl_close_exec_fds(int a, int b) { (void)a; (void)b; return 0; }

int main(void)
{
    dl_state.page = (size_t)sysconf(_SC_PAGESIZE);
    size_t size = 2 * dl_state.page, phoff = dl_state.page - 32, interp = phoff + 2 * sizeof(Elf64_Phdr) + 64;
    unsigned char *bytes = calloc(1, size); assert(bytes);
    Elf64_Ehdr *header = (void *)bytes;
    memcpy(header->e_ident, ELFMAG, SELFMAG);
    header->e_ident[EI_CLASS] = ELFCLASS64; header->e_ident[EI_DATA] = ELFDATA2LSB;
    header->e_ident[EI_VERSION] = EV_CURRENT;
    header->e_type = ET_DYN; header->e_machine = EM_AARCH64; header->e_version = EV_CURRENT;
    header->e_entry = 512; header->e_phoff = phoff; header->e_ehsize = sizeof(*header);
    header->e_phentsize = sizeof(Elf64_Phdr); header->e_phnum = 2;
    Elf64_Phdr *ph = (void *)(bytes + phoff);
    ph[0] = (Elf64_Phdr){.p_type = PT_LOAD, .p_flags = PF_R | PF_X, .p_filesz = size, .p_memsz = size, .p_align = dl_state.page};
    const char interpreter[] = "/fixture-ld.so"; memcpy(bytes + interp, interpreter, sizeof(interpreter));
    ph[1] = (Elf64_Phdr){.p_type = PT_INTERP, .p_offset = interp, .p_filesz = sizeof(interpreter)};
    char path[] = "/tmp/direct-linux-elf-metadata-XXXXXX";
    int fd = mkstemp(path); assert(fd >= 0 && !fchmod(fd, 0700));
    assert(write(fd, bytes, size) == (ssize_t)size);
    const size_t mutations[] = {offsetof(Elf64_Ehdr, e_entry), phoff + sizeof(Elf64_Phdr), interp + 1};
    for (unsigned i = 0; i < sizeof(mutations) / sizeof(mutations[0]); i++) {
        struct image image = {0}; unsigned long costs[5] = {0};
        assert(read_image(path, &image, 0) == 0);
        assert(image.read_bytes == 256 + 2 * sizeof(Elf64_Phdr) + sizeof(interpreter));
        unsigned char changed = bytes[mutations[i]] ^ 1;
        assert(pwrite(fd, &changed, 1, mutations[i]) == 1);
        assert(map_image(&image, costs) == -EAGAIN);
        dl_unmap_all(); dispose_image(&image);
        assert(pwrite(fd, bytes + mutations[i], 1, mutations[i]) == 1);
    }
    struct image image = {0}; unsigned long costs[5] = {0};
    assert(read_image(path, &image, 0) == 0);
    // Ordinary payload bytes are demand-paged contents, not a promised immutable
    // executable snapshot. Metadata checks above must remain strict regardless.
    unsigned char changed = 0x69; assert(pwrite(fd, &changed, 1, 512) == 1);
    assert(map_image(&image, costs) == 0 && *(unsigned char *)(image.base + 512) == changed);
    dl_unmap_all(); dispose_image(&image);
    assert(read_image(path, &image, 0) == 0);
    assert(unlink(path) == 0);
    int replacement = open(path, O_CREAT | O_EXCL | O_WRONLY, 0700); assert(replacement >= 0);
    assert(write(replacement, "not an ELF", 10) == 10 && !close(replacement));
    assert(map_image(&image, costs) == 0 && *(unsigned char *)(image.base + 512) == changed);
    dl_unmap_all(); dispose_image(&image);
    assert(!close(fd) && !unlink(path)); free(bytes);
    puts("PASS ELF metadata mutation rejection, page-crossing program headers, payload paging and pinned source inode");
    return 0;
}
