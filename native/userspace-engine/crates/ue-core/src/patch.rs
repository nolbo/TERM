//! A branch encoder, not a general instruction-site verifier.
//! Callers must separately prove wrapper ABI, mapping ownership and publication safety.

pub const SVC_0: u32 = 0xd4000001;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum BranchError { Unaligned, OutOfRange, ChangedInstruction }

pub fn svc_branch(site: u64, target: u64, observed: u32) -> Result<u32, BranchError> {
    if observed != SVC_0 { return Err(BranchError::ChangedInstruction); }
    if site & 3 != 0 || target & 3 != 0 { return Err(BranchError::Unaligned); }
    let bytes = i128::from(target) - i128::from(site);
    if !(-(1_i128 << 27)..(1_i128 << 27)).contains(&bytes) {
        return Err(BranchError::OutOfRange);
    }
    Ok(0x14000000 | (((bytes / 4) as u32) & 0x03ffffff))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn encodes_signed_range_without_address_wraparound() {
        assert_eq!(svc_branch(0x10000000, 0x10000024, SVC_0), Ok(0x14000009));
        assert_eq!(svc_branch(0x10000000, 0x0ffffffc, SVC_0), Ok(0x17ffffff));
        assert!(svc_branch(0x10000000, 0x08000000, SVC_0).is_ok());
        assert!(svc_branch(0x10000000, 0x17fffffc, SVC_0).is_ok());
        assert_eq!(svc_branch(0x10000000, 0x18000000, SVC_0), Err(BranchError::OutOfRange));
        assert_eq!(svc_branch(0x10000000, 0x07fffffc, SVC_0), Err(BranchError::OutOfRange));
        assert_eq!(svc_branch(u64::MAX - 3, 0, SVC_0), Err(BranchError::OutOfRange));
    }

    #[test]
    fn rejects_stale_or_unaligned_sites() {
        assert_eq!(svc_branch(0, 4, 0xd65f03c0), Err(BranchError::ChangedInstruction));
        assert_eq!(svc_branch(2, 4, SVC_0), Err(BranchError::Unaligned));
        assert_eq!(svc_branch(0, 6, SVC_0), Err(BranchError::Unaligned));
    }
}
