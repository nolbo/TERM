//! Fixed, aggregate guest resource observation. No command execution or PID export.
use std::{fs, io};

pub struct GuestResources {
    pub total: i64, pub available: i64, pub swap_total: i64, pub swap_free: i64,
    pub cpus: i32, pub load_milli: i32, pub pressure_milli: i32,
}

fn memory(text: &str, key: &str) -> io::Result<i64> {
    text.lines().find_map(|line| {
        let (name, value) = line.split_once(':')?;
        if name != key { return None; }
        value.split_whitespace().next()?.parse::<i64>().ok()?.checked_mul(1024)
    }).filter(|value| *value >= 0).ok_or_else(|| io::Error::other("memory metric unavailable"))
}
fn milli(text: &str, maximum: i32) -> Option<i32> {
    let value = text.parse::<f64>().ok()?;
    (value.is_finite() && value >= 0.0).then(|| (value * 1000.0).min(maximum as f64) as i32)
}
pub fn read() -> io::Result<GuestResources> {
    let text = fs::read_to_string("/proc/meminfo")?;
    let total = memory(&text, "MemTotal")?;
    let available = memory(&text, "MemAvailable")?;
    let swap_total = memory(&text, "SwapTotal")?;
    let swap_free = memory(&text, "SwapFree")?;
    if total <= 0 || available > total || swap_free > swap_total { return Err(io::Error::other("inconsistent memory metrics")); }
    let load = fs::read_to_string("/proc/loadavg")?;
    let load_milli = load.split_whitespace().next().and_then(|s| milli(s, i32::MAX))
        .ok_or_else(|| io::Error::other("load metric unavailable"))?;
    let pressure_milli = fs::read_to_string("/proc/pressure/memory").ok().and_then(|text|
        text.lines().find(|line| line.starts_with("some ")).and_then(|line|
            line.split_whitespace().find_map(|item| item.strip_prefix("avg10=")
                .and_then(|value| milli(value, 100_000))))).unwrap_or(-1);
    Ok(GuestResources { total, available, swap_total, swap_free,
        cpus: std::thread::available_parallelism()?.get().min(4096) as i32, load_milli, pressure_milli })
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test] fn parses_units_and_keeps_unavailable_distinct_from_zero() {
        assert_eq!(memory("MemTotal: 2048 kB\nSwapFree: 0 kB\n", "MemTotal").unwrap(), 2097152);
        assert_eq!(memory("SwapFree: 0 kB\n", "SwapFree").unwrap(), 0);
        assert!(memory("MemTotal: -1 kB", "MemTotal").is_err());
        assert!(memory("MemTotal: 9223372036854775807 kB", "MemTotal").is_err());
        assert_eq!(milli("nan", 100_000), None);
        assert_eq!(milli("0.25", 100_000), Some(250));
    }
}
