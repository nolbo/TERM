/* SPDX-License-Identifier: Apache-2.0 */
/* Separate call sites make the host dispatcher exempt from its own TRAP filter.
 * This is a compatibility mechanism, not a security boundary. */
.text
.p2align 4
.global dl_host_syscall
.type dl_host_syscall, %function
dl_host_syscall:
    mov x8, x0
    mov x0, x1
    mov x1, x2
    mov x2, x3
    mov x3, x4
    mov x4, x5
    mov x5, x6
    svc #0
.global dl_host_return
.hidden dl_host_return
dl_host_return:
    ret
.size dl_host_syscall, .-dl_host_syscall

.p2align 4
.global dl_guest_syscall
.type dl_guest_syscall, %function
dl_guest_syscall:
    mov x8, x0
    mov x0, x1
    mov x1, x2
    mov x2, x3
    mov x3, x4
    mov x4, x5
    mov x5, x6
    svc #0
    ret
.size dl_guest_syscall, .-dl_guest_syscall
.section .note.GNU-stack,"",%progbits
