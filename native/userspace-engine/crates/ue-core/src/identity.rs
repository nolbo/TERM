use ue_abi::{nr, RawReturn};

/// Guest credentials never change the Android UID or host capability grants.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Credentials {
    pub uid: u32,
    pub euid: u32,
    pub gid: u32,
    pub egid: u32,
}

impl Credentials {
    /// None means this handler does not implement the syscall, not host passthrough.
    pub fn query(&self, nr: u64) -> Option<RawReturn> {
        let value = match nr {
            nr::GETUID => self.uid,
            nr::GETEUID => self.euid,
            nr::GETGID => self.gid,
            nr::GETEGID => self.egid,
            _ => return None,
        };
        Some(RawReturn(i64::from(value)))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn queries_use_distinct_explicit_credentials() {
        let credentials = Credentials { uid: 10, euid: 11, gid: 12, egid: 13 };
        for (nr, expected) in [(nr::GETUID, 10), (nr::GETEUID, 11), (nr::GETGID, 12), (nr::GETEGID, 13)] {
            assert_eq!(credentials.query(nr), Some(RawReturn(expected)));
        }
        assert_eq!(credentials.query(nr::GETPID), None);
        assert_eq!(credentials.query(u64::MAX), None);
    }
}
