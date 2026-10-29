//! Bounded classic-BPF construction for entry probes. Unlisted calls are allowed;
//! this is deliberately not the production guest syscall policy or a sandbox.

use ue_abi::AUDIT_ARCH_AARCH64;

pub const ALLOW: u32 = 0x7fff0000;
pub const TRAP: u32 = 0x00030000;
pub const KILL_PROCESS: u32 = 0x80000000;
pub const ERRNO: u32 = 0x00050000;
pub const MAX_TRAPPED: usize = 32;

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
#[repr(C)]
pub struct Instruction {
    pub code: u16,
    pub jt: u8,
    pub jf: u8,
    pub k: u32,
}

#[repr(C)]
pub struct Program<'a> {
    pub len: u16,
    pub filter: *const Instruction,
    lifetime: core::marker::PhantomData<&'a [Instruction]>,
}

impl<'a> Program<'a> {
    pub fn from_slice(instructions: &'a [Instruction]) -> Option<Self> {
        if instructions.is_empty() || instructions.len() > 4096 { return None; }
        Some(Self { len: instructions.len() as u16, filter: instructions.as_ptr(), lifetime: core::marker::PhantomData })
    }
}

pub const fn load(offset: u32) -> Instruction { Instruction { code: 0x20, jt: 0, jf: 0, k: offset } }
pub const fn equal(value: u32, yes: u8, no: u8) -> Instruction { Instruction { code: 0x15, jt: yes, jf: no, k: value } }
pub const fn ret(value: u32) -> Instruction { Instruction { code: 0x06, jt: 0, jf: 0, k: value } }

#[derive(Debug, PartialEq, Eq)]
pub enum FilterError { TooManyCalls, DuplicateCall, InvalidGate }

pub struct ProbeFilter {
    instructions: [Instruction; 10 + MAX_TRAPPED * 2],
    len: usize,
}

impl ProbeFilter {
    pub fn new(gate_ip: u64, numbers: &[u32]) -> Result<Self, FilterError> {
        if gate_ip == 0 || gate_ip & 3 != 0 { return Err(FilterError::InvalidGate); }
        if numbers.len() > MAX_TRAPPED { return Err(FilterError::TooManyCalls); }
        for (i, nr) in numbers.iter().enumerate() {
            if numbers[..i].contains(nr) { return Err(FilterError::DuplicateCall); }
        }
        let mut result = Self { instructions: [ret(KILL_PROCESS); 10 + MAX_TRAPPED * 2], len: 10 + numbers.len() * 2 };
        result.instructions[..9].copy_from_slice(&[
            load(4), equal(AUDIT_ARCH_AARCH64, 1, 0), ret(KILL_PROCESS),
            load(12), equal((gate_ip >> 32) as u32, 0, 3),
            load(8), equal(gate_ip as u32, 0, 1), ret(ALLOW), load(0),
        ]);
        for (i, nr) in numbers.iter().enumerate() {
            result.instructions[9 + i * 2] = equal(*nr, 0, 1);
            result.instructions[10 + i * 2] = ret(TRAP);
        }
        result.instructions[result.len - 1] = ret(ALLOW);
        Ok(result)
    }

    pub fn instructions(&self) -> &[Instruction] { &self.instructions[..self.len] }
    pub fn program(&self) -> Program<'_> {
        Program { len: self.len as u16, filter: self.instructions.as_ptr(), lifetime: core::marker::PhantomData }
    }
}

const _: () = assert!(core::mem::size_of::<Instruction>() == 8);

#[cfg(test)]
mod tests {
    use super::*;

    fn evaluate(filter: &ProbeFilter, nr: u32, arch: u32, ip: u64) -> u32 {
        let mut pc = 0;
        let mut a = 0;
        for _ in 0..100 {
            let insn = filter.instructions()[pc];
            match insn.code {
                0x20 => a = match insn.k { 0 => nr, 4 => arch, 8 => ip as u32, 12 => (ip >> 32) as u32, _ => panic!("bad offset") },
                0x15 => pc += if a == insn.k { insn.jt } else { insn.jf } as usize,
                0x06 => return insn.k,
                _ => panic!("bad opcode"),
            }
            pc += 1;
        }
        panic!("nonterminating filter")
    }

    #[test]
    fn gate_requires_both_address_halves_and_native_architecture() {
        let ip = 0x123456789abc_u64;
        let filter = ProbeFilter::new(ip, &[56, 64, 172]).unwrap();
        assert_eq!(evaluate(&filter, 56, AUDIT_ARCH_AARCH64, ip), ALLOW);
        assert_eq!(evaluate(&filter, 56, AUDIT_ARCH_AARCH64, ip ^ (1 << 32)), TRAP);
        assert_eq!(evaluate(&filter, 56, AUDIT_ARCH_AARCH64, ip + 4), TRAP);
        assert_eq!(evaluate(&filter, 56, 0x40000028, ip), KILL_PROCESS);
        for nr in [56, 64, 172] { assert_eq!(evaluate(&filter, nr, AUDIT_ARCH_AARCH64, 0), TRAP); }
        assert_eq!(evaluate(&filter, 9999, AUDIT_ARCH_AARCH64, 0), ALLOW);
    }

    #[test]
    fn rejects_invalid_input_and_handles_empty_filter() {
        assert!(matches!(ProbeFilter::new(0, &[]), Err(FilterError::InvalidGate)));
        assert!(matches!(ProbeFilter::new(6, &[]), Err(FilterError::InvalidGate)));
        assert!(matches!(ProbeFilter::new(4, &[1, 1]), Err(FilterError::DuplicateCall)));
        assert!(matches!(ProbeFilter::new(4, &[1; 33]), Err(FilterError::TooManyCalls)));
        let filter = ProbeFilter::new(4, &[]).unwrap();
        assert_eq!(evaluate(&filter, 56, AUDIT_ARCH_AARCH64, 8), ALLOW);
        assert!(Program::from_slice(&[]).is_none());
    }
}
