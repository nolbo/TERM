/* SPDX-License-Identifier: Apache-2.0 */
#ifndef DIRECT_LINUX_RUNTIME_H
#define DIRECT_LINUX_RUNTIME_H
#include <stddef.h>
#include <stdint.h>

#define DL_PATH 4096
#define DL_HEAP (64UL * 1024 * 1024)
extern long dl_host_syscall(long, long, long, long, long, long, long);
extern char dl_host_return[];
extern char dl_sigreturn_return[], dl_resume_return[];
extern void dl_sigreturn(void);
extern _Noreturn void dl_enter(uintptr_t entry, uintptr_t stack);
#define DL_CALL(n,a,b,c,d,e,f) dl_host_syscall(n,(long)(a),(long)(b),(long)(c),(long)(d),(long)(e),(long)(f))

struct dl_state {
    int root_fd;
    int vfork_fd;
    int trace;
    int exec_profile;
    int startup_profile;
    size_t page;
    unsigned long hwcap, hwcap2, vdso, minsigstksz, clktck;
    char root[DL_PATH];
    char executable[DL_PATH];
    uintptr_t heap_start, heap_end, heap_mapped;
};
extern struct dl_state dl_state;
size_t dl_length(const char *s);
void dl_copy(void *destination, const void *source, size_t size);
int dl_equal_memory(const void *a, const void *b, size_t size);
int dl_equal(const char *a, const char *b);
long dl_read_memory(void *destination, const void *source, size_t size);
long dl_write_memory(void *destination, const void *source, size_t size);
long dl_string(char *destination, const char *source);
long dl_open(int dirfd, const char *path, int flags, unsigned mode);
// Reopen a guest descriptor's pinned inode; caller holds dl_files_lock.
long dl_reopen(int fd, int flags);
long dl_filesystem(long nr, unsigned long args[6], void *context);
long dl_devices_init(void);
int dl_device_index(int dirfd, const char *name);
long dl_device_open(int index, int dirfd, int flags, unsigned mode);
long dl_device_change(int parent, const char *name, int removing);
long dl_getdents(int fd, void *buffer, unsigned count);
long dl_close_range(unsigned first, unsigned last, unsigned flags);
long dl_close_exec_fds(int main_fd, int interpreter_fd);
long dl_network(long nr, unsigned long args[6], void *context);
long dl_blocking(long nr, unsigned long args[6], void *context);
_Noreturn void dl_signal_return(void *context);
long dl_signal_action(unsigned long args[6]);
long dl_signal_reset_exec(void);
long dl_signal_wait(long nr, unsigned long args[6], void *context);
void dl_signal_collect(uintptr_t stack);
long dl_file_path(int fd, char out[DL_PATH]);
// Caller supplies at least 40 bytes; fd < 0 names the live task's fd directory.
size_t dl_fd_path(char *out, int fd);
long dl_socket_path(const char *path, char out[DL_PATH], int binding);
long dl_executable(int fd);
long dl_virtual_cwd(char path[DL_PATH]);
int dl_install(void *signal_stack, size_t size, int filtering);
struct dl_entry { uintptr_t pc, sp; unsigned long mask, virtual_sigsys; };
long dl_exec(const char *path, char *const *argv, char *const *env, struct dl_entry *entry, int replacing);
long dl_execat(int dirfd, const char *path, char *const *argv, char *const *env, int flags,
    struct dl_entry *entry, int replacing);
long dl_exec_resume(int fd, struct dl_entry *entry);
long dl_mmap(uintptr_t address, size_t size, int prot, int flags, int fd, unsigned long offset);
long dl_munmap(uintptr_t address, size_t size);
long dl_madvise(uintptr_t address, size_t size, int advice);
long dl_memory_fork_begin(void);
void dl_memory_fork_end(int child);
long dl_track(uintptr_t address, size_t size);
void dl_unmap_all(void);
void dl_clear_cache(uintptr_t begin, uintptr_t end);
#endif
