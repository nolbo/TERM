.text
.balign 16
.global ue_probe_direct_entry
.type ue_probe_direct_entry, %function
ue_probe_direct_entry:
    // Only the engine-generated AAPCS fixture may enter here. Its stack is valid,
    // x16 is scratch, and caller-saved registers are not part of its return ABI.
    stp x29, x30, [sp, #-16]!
    mov x29, sp
    mov x6, x5
    mov x5, x4
    mov x4, x3
    mov x3, x2
    mov x2, x1
    mov x1, x0
    mov x0, x8
    bl ue_probe_dispatch
    ldp x29, x30, [sp], #16
    ret
.size ue_probe_direct_entry, .-ue_probe_direct_entry

.global ue_probe_registers
.type ue_probe_registers, %function
ue_probe_registers:
    mov x8, x0
    mov x9, #0x1234
    mov x10, #0x5678
    movi v0.16b, #0x5a
    movi v31.16b, #0xa5
    cmp x9, x9
    svc #0
    mrs x11, nzcv
    mov x0, #0
    mov x12, #0x1234
    cmp x9, x12
    b.ne .Lbad_register
    mov x12, #0x5678
    cmp x10, x12
    b.ne .Lbad_register
    mov x12, #0x60000000
    cmp x11, x12
    b.ne .Lbad_register
    umov x9, v0.d[0]
    mov x10, #0x5a5a
    movk x10, #0x5a5a, lsl #16
    movk x10, #0x5a5a, lsl #32
    movk x10, #0x5a5a, lsl #48
    cmp x9, x10
    b.ne .Lbad_register
    umov x9, v0.d[1]
    cmp x9, x10
    b.ne .Lbad_register
    umov x9, v31.d[0]
    mov x10, #0xa5a5
    movk x10, #0xa5a5, lsl #16
    movk x10, #0xa5a5, lsl #32
    movk x10, #0xa5a5, lsl #48
    cmp x9, x10
    b.ne .Lbad_register
    umov x9, v31.d[1]
    cmp x9, x10
    b.ne .Lbad_register
    ret
.Lbad_register:
    mov x0, #1
    ret
.size ue_probe_registers, .-ue_probe_registers
.section .note.GNU-stack,"",%progbits
