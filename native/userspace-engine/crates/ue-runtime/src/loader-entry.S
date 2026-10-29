.text
.global ue_enter_guest
.type ue_enter_guest, %function
ue_enter_guest:
    mov x16, x0
    mov sp, x1
    msr tpidr_el0, xzr
    mov x0, xzr
    mov x1, xzr
    mov x2, xzr
    mov x29, xzr
    mov x30, xzr
    br x16
.size ue_enter_guest, .-ue_enter_guest
.section .note.GNU-stack,"",%progbits
