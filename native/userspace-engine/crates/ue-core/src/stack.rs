//! Linux ELF initial-stack encoding into caller-owned memory, with no allocator.

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Error { TooLarge, InvalidString, InvalidAuxiliary, AddressOverflow }

pub struct Builder<'a> {
    bytes: &'a mut [u8],
    base: u64,
    cursor: usize,
}

impl<'a> Builder<'a> {
    pub fn new(bytes: &'a mut [u8], base: u64) -> Result<Self, Error> {
        if base & 15 != 0 || base.checked_add(bytes.len() as u64).is_none() { return Err(Error::AddressOverflow); }
        let cursor = bytes.len();
        Ok(Self { bytes, base, cursor })
    }

    pub fn push_bytes(&mut self, bytes: &[u8]) -> Result<u64, Error> {
        let start = self.cursor.checked_sub(bytes.len()).ok_or(Error::TooLarge)?;
        self.bytes[start..self.cursor].copy_from_slice(bytes);
        self.cursor = start;
        Ok(self.base + start as u64)
    }

    pub fn push_string(&mut self, bytes: &[u8]) -> Result<u64, Error> {
        if bytes.contains(&0) { return Err(Error::InvalidString); }
        if bytes.len().checked_add(1).ok_or(Error::TooLarge)? > self.cursor { return Err(Error::TooLarge); }
        self.push_bytes(&[0])?;
        self.push_bytes(bytes)
    }

    /// argv/env pointers must refer to strings already placed in this builder.
    pub fn finish(self, argv: &[u64], env: &[u64], aux: &[(u64, u64)]) -> Result<u64, Error> {
        if argv.is_empty() || aux.iter().any(|(key, _)| *key == 0) { return Err(Error::InvalidAuxiliary); }
        for (i, (key, _)) in aux.iter().enumerate() {
            if aux[..i].iter().any(|(previous, _)| previous == key) { return Err(Error::InvalidAuxiliary); }
        }
        for address in argv.iter().chain(env) {
            let offset = address.checked_sub(self.base).ok_or(Error::InvalidString)? as usize;
            if offset < self.cursor || offset >= self.bytes.len() || !self.bytes[offset..].contains(&0) {
                return Err(Error::InvalidString);
            }
        }
        let words = argv.len().checked_add(env.len()).and_then(|n| n.checked_add(5))
            .and_then(|n| aux.len().checked_mul(2).and_then(|a| n.checked_add(a))).ok_or(Error::TooLarge)?;
        let needed = words.checked_mul(8).ok_or(Error::TooLarge)?;
        let start = self.cursor.checked_sub(needed).ok_or(Error::TooLarge)? & !15;
        let mut cursor = start;
        let mut word = |value: u64| {
            self.bytes[cursor..cursor + 8].copy_from_slice(&value.to_le_bytes());
            cursor += 8;
        };
        word(argv.len() as u64);
        for address in argv { word(*address); }
        word(0);
        for address in env { word(*address); }
        word(0);
        for (key, value) in aux { word(*key); word(*value); }
        word(0); word(0);
        Ok(self.base + start as u64)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn lays_out_argv_env_and_auxv_at_a_16_byte_boundary() {
        let mut bytes = [0xcc; 1024];
        let mut builder = Builder::new(&mut bytes, 0x10000).unwrap();
        let a = builder.push_string(b"/bin/program").unwrap();
        let b = builder.push_string(b"").unwrap();
        let e = builder.push_string(b"LANG=C").unwrap();
        let random = builder.push_bytes(&[0x5a; 16]).unwrap();
        let sp = builder.finish(&[a, b], &[e], &[(6, 4096), (25, random)]).unwrap();
        assert_eq!(sp & 15, 0);
        let start = (sp - 0x10000) as usize;
        let expected = [2, a, b, 0, e, 0, 6, 4096, 25, random, 0, 0];
        for (i, expected) in expected.iter().enumerate() {
            assert_eq!(u64::from_le_bytes(bytes[start+i*8..start+i*8+8].try_into().unwrap()), *expected);
        }
        assert_eq!(&bytes[(a-0x10000) as usize..(a-0x10000) as usize+13], b"/bin/program\0");
    }

    #[test]
    fn rejects_oversize_invalid_pointers_and_duplicate_auxv() {
        let mut bytes = [0; 64];
        let mut builder = Builder::new(&mut bytes, 0x10000).unwrap();
        assert_eq!(builder.push_string(b"x\0y"), Err(Error::InvalidString));
        assert_eq!(builder.push_string(&[1; 64]), Err(Error::TooLarge));
        let arg = builder.push_string(b"x").unwrap();
        assert_eq!(builder.finish(&[arg], &[], &[(1, 1), (1, 2)]), Err(Error::InvalidAuxiliary));
        let builder = Builder::new(&mut bytes, 0x10000).unwrap();
        assert_eq!(builder.finish(&[1], &[], &[]), Err(Error::InvalidString));
        assert!(Builder::new(&mut bytes, u64::MAX - 15).is_err());
    }
}
