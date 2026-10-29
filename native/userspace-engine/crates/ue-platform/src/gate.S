.text
.balign 16
.global ue_host_call
.type ue_host_call, %function
ue_host_call:
    mov x8, x0
    mov x0, x1
    mov x1, x2
    mov x2, x3
    mov x3, x4
    mov x4, x5
    mov x5, x6
.Lhost_svc:
    svc #0
.global ue_host_gate_ip
ue_host_gate_ip:
    ret
.size ue_host_call, .-ue_host_call

.global ue_sigreturn
.type ue_sigreturn, %function
ue_sigreturn:
    mov x8, #139
    b .Lhost_svc
.size ue_sigreturn, .-ue_sigreturn
.section .note.GNU-stack,"",%progbits
