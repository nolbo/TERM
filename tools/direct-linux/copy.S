/* SPDX-License-Identifier: Apache-2.0 */
/* Bounded non-overlapping copy using only caller-saved integer registers.
 * Never reads/writes outside [pointer, pointer + length); no SIMD/TLS/libc. */
.text
.p2align 4
.global dl_copy
.hidden dl_copy
.type dl_copy, %function
dl_copy:
    cmp x2, #32
    b.lo 2f
1:
    ldp x8, x9, [x1]
    ldp x10, x11, [x1, #16]
    stp x8, x9, [x0]
    stp x10, x11, [x0, #16]
    add x1, x1, #32
    add x0, x0, #32
    sub x2, x2, #32
    cmp x2, #32
    b.hs 1b
2:
    tbz x2, #4, 3f
    ldp x8, x9, [x1], #16
    stp x8, x9, [x0], #16
3:
    tbz x2, #3, 4f
    ldr x8, [x1], #8
    str x8, [x0], #8
4:
    tbz x2, #2, 5f
    ldr w8, [x1], #4
    str w8, [x0], #4
5:
    tbz x2, #1, 6f
    ldrh w8, [x1], #2
    strh w8, [x0], #2
6:
    tbz x2, #0, 7f
    ldrb w8, [x1]
    strb w8, [x0]
7:
    ret
.size dl_copy, .-dl_copy
/* Equality comparison with the same exact-byte bounds and no SIMD state. */
.p2align 4
.global dl_equal_memory
.hidden dl_equal_memory
.type dl_equal_memory, %function
dl_equal_memory:
    cmp x2, #16
    b.lo 2f
1:
    ldp x8, x9, [x0], #16
    ldp x10, x11, [x1], #16
    cmp x8, x10
    ccmp x9, x11, #0, eq
    b.ne 4f
    sub x2, x2, #16
    cmp x2, #16
    b.hs 1b
2:
    cbz x2, 3f
    ldrb w8, [x0], #1
    ldrb w9, [x1], #1
    cmp w8, w9
    b.ne 4f
    sub x2, x2, #1
    b 2b
3:
    mov w0, #1
    ret
4:
    mov w0, #0
    ret
.size dl_equal_memory, .-dl_equal_memory
.section .note.GNU-stack,"",%progbits
