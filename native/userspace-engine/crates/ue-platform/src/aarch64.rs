use ue_abi::RawReturn;

core::arch::global_asm!(include_str!("gate.S"));

unsafe extern "C" {
    fn ue_host_call(nr: u64, a0: u64, a1: u64, a2: u64, a3: u64, a4: u64, a5: u64) -> i64;
    static ue_host_gate_ip: u8;
    pub fn ue_sigreturn();
}

pub fn gate_ip() -> u64 {
    core::ptr::addr_of!(ue_host_gate_ip) as u64
}

/// # Safety
/// The caller must satisfy the selected Linux syscall's pointer, lifetime, memory
/// and process-state contracts. This gate grants no exemption from inherited filters.
pub unsafe fn syscall(nr: u64, args: [u64; 6]) -> RawReturn {
    RawReturn(unsafe { ue_host_call(nr, args[0], args[1], args[2], args[3], args[4], args[5]) })
}

/// # Safety
/// The range must be mapped and owned by the caller, with no concurrent execution
/// or mutation. Publication must wait until this operation and RX protection succeed.
pub unsafe fn synchronize_code(start: usize, end: usize) {
    let ctr: u64;
    unsafe { core::arch::asm!("mrs {ctr}, ctr_el0", ctr = out(reg) ctr, options(nostack, nomem)); }
    let dline = 4_usize << ((ctr >> 16) & 15);
    let iline = 4_usize << (ctr & 15);
    let mut cursor = start & !(dline - 1);
    while cursor < end {
        unsafe { core::arch::asm!("dc cvau, {p}", p = in(reg) cursor, options(nostack)); }
        let Some(next) = cursor.checked_add(dline) else { break };
        cursor = next;
    }
    unsafe { core::arch::asm!("dsb ish", options(nostack)); }
    cursor = start & !(iline - 1);
    while cursor < end {
        unsafe { core::arch::asm!("ic ivau, {p}", p = in(reg) cursor, options(nostack)); }
        let Some(next) = cursor.checked_add(iline) else { break };
        cursor = next;
    }
    unsafe { core::arch::asm!("dsb ish", "isb", options(nostack)); }
}
