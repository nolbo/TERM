//! Bounded, lossy terminal tails. A slow or absent viewer must never stop a PTY.
use std::collections::{BTreeMap, VecDeque};

// A supported 6 MiB local graphic expands to slightly over 8 MiB of wire APCs.
// Retain one complete expansion when a disconnected client cannot consume it.
const SESSION_BYTES: usize = 9 * 1024 * 1024;
const TOTAL_BYTES: usize = 16 * 1024 * 1024;
const TOTAL_CHUNKS: usize = 2048;

#[derive(Default)]
pub struct Tail {
    pub chunks: VecDeque<(u64, Vec<u8>)>,
    bytes: usize,
    pub gap: Option<i64>,
    pub exit: Option<i32>,
}

impl Tail {
    pub fn has_pending(&self) -> bool { !self.chunks.is_empty() || self.gap.is_some() || self.exit.is_some() }
}

#[derive(Default)]
pub struct OutputBuffer {
    pub tails: BTreeMap<String, Tail>,
    bytes: usize,
    chunks: usize,
    sequence: u64,
}

impl OutputBuffer {
    pub fn has_pending(&self, id: &str) -> bool { self.tails.get(id).is_some_and(Tail::has_pending) }

    pub fn push(&mut self, id: &str, bytes: Vec<u8>) {
        if bytes.is_empty() { return; }
        self.sequence += 1;
        if !self.tails.contains_key(id) { self.tails.insert(id.into(), Tail::default()); }
        let tail = self.tails.get_mut(id).unwrap();
        tail.bytes += bytes.len(); self.bytes += bytes.len();
        if let Some((_, last)) = tail.chunks.back_mut().filter(|(_, last)| last.len() + bytes.len() <= 32 * 1024) {
            last.extend_from_slice(&bytes);
        } else {
            self.chunks += 1;
            tail.chunks.push_back((self.sequence, bytes));
        }
        if self.tails[id].bytes > SESSION_BYTES || self.tails[id].chunks.len() > 512 {
            // Shed a batch, retaining the newest chunk. Dropping one chunk each
            // turn would create a parser-reset/checkpoint storm for slow viewers.
            while self.tails[id].chunks.len() > 1 { self.discard(id); }
            if self.tails[id].bytes > SESSION_BYTES { self.discard(id); }
        }
        while self.bytes > TOTAL_BYTES || self.chunks > TOTAL_CHUNKS {
            let oldest = self.tails.iter().filter_map(|(id, tail)|
                tail.chunks.front().map(|(sequence, _)| (id, sequence)))
                .min_by_key(|(_, sequence)| *sequence).unwrap().0.clone();
            while !self.tails[&oldest].chunks.is_empty() { self.discard(&oldest); }
        }
    }

    fn discard(&mut self, id: &str) {
        let bytes = self.pop(id).unwrap();
        let gap = &mut self.tails.get_mut(id).unwrap().gap;
        if *gap != Some(-1) { *gap = Some(gap.unwrap_or(0).saturating_add(bytes.len() as i64)); }
    }

    pub fn pop(&mut self, id: &str) -> Option<Vec<u8>> {
        let tail = self.tails.get_mut(id)?;
        let (_, bytes) = tail.chunks.pop_front()?;
        tail.bytes -= bytes.len(); self.bytes -= bytes.len(); self.chunks -= 1;
        Some(bytes)
    }

    pub fn remove(&mut self, id: &str) {
        if let Some(tail) = self.tails.remove(id) {
            self.bytes -= tail.bytes; self.chunks -= tail.chunks.len();
        }
    }

    pub fn resize_boundary(&mut self, id: &str) -> Option<i64> {
        let tail = self.tails.get(id)?;
        let count = if tail.gap == Some(-1) { -1 } else { tail.gap.unwrap_or(0).saturating_add(tail.bytes as i64) };
        if count == 0 { return None; }
        self.remove(id);
        Some(count)
    }

    pub fn disconnected(&mut self, ids: impl Iterator<Item = String>) {
        // Frames queued to the old socket have unknown delivery. Do not replay
        // them; make the discontinuity explicit before delivering the retained tail.
        for id in ids { self.tails.entry(id).or_default().gap = Some(-1); }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn one_maximum_graphic_expansion_is_retained_without_artificial_loss() {
        let mut output = OutputBuffer::default();
        for _ in 0..257 { output.push("graphic", vec![1; 32 * 1024]); }
        assert_eq!(output.tails["graphic"].gap, None);
        assert_eq!(output.bytes, 257 * 32 * 1024);
    }

    #[test]
    fn bounds_bytes_and_metadata_and_reports_loss_before_tail() {
        let mut output = OutputBuffer::default();
        for i in 0..20 {
            for _ in 0..400 { output.push(&i.to_string(), vec![b'x'; 32 * 1024]); }
        }
        assert!(output.bytes <= TOTAL_BYTES && output.chunks <= TOTAL_CHUNKS);
        assert!(output.tails.values().all(|tail| tail.bytes <= SESSION_BYTES && tail.chunks.len() <= 512));
        assert!(output.tails.values().all(|tail| tail.gap.unwrap() > 0));
        output.disconnected(std::iter::once("19".into()));
        for _ in 0..200 { output.push("19", vec![1]); }
        assert_eq!(output.tails["19"].gap, Some(-1));
        for i in 0..20 { output.remove(&i.to_string()); }
        assert_eq!((output.bytes, output.chunks), (0, 0));
    }
}
