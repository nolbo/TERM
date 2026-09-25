/* SPDX-License-Identifier: Apache-2.0 */
.text
.p2align 4
.global dl_start
.hidden dl_start
.type dl_start, %function
dl_start:
    mov x0, sp
    mov x29, xzr
    bl dl_bootstrap
    mov x1, x0
    mov x0, #94
    bl dl_host_syscall
    brk #0
.size dl_start, .-dl_start

.global dl_sigreturn
.hidden dl_sigreturn
.type dl_sigreturn, %function
dl_sigreturn:
    mov x8, #139
    svc #0
.global dl_sigreturn_return
.hidden dl_sigreturn_return
dl_sigreturn_return:
    brk #0
.size dl_sigreturn, .-dl_sigreturn

.global dl_enter
.hidden dl_enter
.type dl_enter, %function
dl_enter:
    mov x16, x0
    mov sp, x1
    msr tpidr_el0, xzr
    mov x0, xzr
    mov x1, xzr
    mov x2, xzr
    mov x3, xzr
    mov x18, xzr
    mov x29, xzr
    mov x30, xzr
    br x16
.size dl_enter, .-dl_enter
.section .note.GNU-stack,"",%progbits
