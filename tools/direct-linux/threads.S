/* SPDX-License-Identifier: Apache-2.0 */
.text
.p2align 4
.global dl_spawn
.hidden dl_spawn
.type dl_spawn, %function
dl_spawn:
    stp x19, x30, [sp, #-16]!
    mov x19, x0
    mov x2, x1
    ldr x1, [x19]
    ldr x3, [x19, #16]
    ldr x4, [x19, #24]
    ldr x5, [x19, #32]
    mov x0, #220
    mov x6, xzr
    bl dl_host_syscall
    cbz x0, 1f
    ldp x19, x30, [sp], #16
    ret
1:  ldr x0, [sp]
    b dl_thread_start
.size dl_spawn, .-dl_spawn

.global dl_resume
.hidden dl_resume
.type dl_resume, %function
dl_resume:
    mov sp, x0
    mov x8, #139
    svc #0
    brk #0
.size dl_resume, .-dl_resume
.section .note.GNU-stack,"",%progbits
