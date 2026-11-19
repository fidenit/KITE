//! What the Claude API has cost, kept per day in `data/usage.json`, and the daily limit the user may set
//! (`spend-limit.txt` next to the program, in dollars; APERIT_DAILY_LIMIT wins).
//!
//! The amounts are estimates from the token counts each response reports and the published per-token prices
//! below (2026-11). They count requests made with the user's own Claude API key: the main turns, the workers and
//! the small background questions. Claude Code (its own billing) and other services (prices not known here) are
//! not counted.

use std::collections::BTreeMap;
use std::sync::Mutex;

/// Dollars per million tokens.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Price {
    pub input: f64,
    pub output: f64,
    pub cache_read: f64,
}

/// The price of a Claude model, by the start of its id.
pub fn price(model: &str) -> Option<Price> {
    const TABLE: [(&str, f64, f64, f64); 9] = [
        ("claude-fable-5-2", 8.0, 40.0, 0.20),
        ("claude-mythos-5-2", 8.0, 40.0, 0.20),
        ("claude-fable-5", 10.0, 50.0, 0.50),
        ("claude-opus-5-5", 4.0, 20.0, 0.20),
        ("claude-opus-5", 5.0, 25.0, 0.50),
        ("claude-sonnet-5-5", 2.0, 10.0, 0.10),
        ("claude-sonnet-5", 2.0, 10.0, 0.20),
        ("claude-haiku-5-5", 0.10, 0.50, 0.01),
        ("claude-haiku-4-5", 1.0, 5.0, 0.10),
    ];
    TABLE.iter().find(|(id, ..)| model.starts_with(id)).map(|&(_, input, output, cache_read)| Price { input, output, cache_read })
}

/// The cost of one request. `write_factor` is what writing the cache costs relative to input (2 for the
/// one-hour cache, 1.25 for the five-minute one).
pub fn cost(model: &str, input: u64, cache_read: u64, cache_write: u64, write_factor: f64, output: u64) -> Option<f64> {
    let p = price(model)?;
    let m = 1_000_000.0;
    Some(input as f64 * p.input / m + cache_read as f64 * p.cache_read / m + cache_write as f64 * p.input * write_factor / m + output as f64 * p.output / m)
}

/// What a typical turn costs: the instructions read from the cache, a few thousand new tokens, a short answer.
pub fn per_turn(model: &str) -> Option<f64> {
    cost(model, 4_000, 26_000, 0, 1.0, 1_000)
}

/// "≈ 4¢", "< 1¢", "≈ $1.20"
pub fn money(dollars: f64) -> String {
    if dollars < 0.005 {
        "< 1¢".into()
    } else if dollars < 1.0 {
        format!("≈ {:.0}¢", dollars * 100.0)
    } else {
        format!("≈ ${dollars:.2}")
    }
}

static LOCK: Mutex<()> = Mutex::new(());

fn path() -> std::path::PathBuf {
    crate::sys::data_dir().join("usage.json")
}

fn load() -> BTreeMap<String, f64> {
    std::fs::read_to_string(path()).ok().and_then(|t| serde_json::from_str(&t).ok()).unwrap_or_default()
}

/// Add a request's cost to today's total.
pub fn record(dollars: f64) {
    if dollars <= 0.0 {
        return;
    }
    let _guard = LOCK.lock().unwrap_or_else(|e| e.into_inner());
    let mut days = load();
    let (today, _) = crate::sys::now_local();
    *days.entry(today).or_insert(0.0) += dollars;
    let _ = std::fs::write(path(), serde_json::to_string_pretty(&days).unwrap_or_default());
}

/// Today's total and this month's, in dollars.
pub fn totals() -> (f64, f64) {
    let _guard = LOCK.lock().unwrap_or_else(|e| e.into_inner());
    let days = load();
    let (today, _) = crate::sys::now_local();
    let month = today.get(..7).unwrap_or("").to_string();
    (days.get(&today).copied().unwrap_or(0.0), days.iter().filter(|(d, _)| d.starts_with(&month)).map(|(_, v)| v).sum())
}

/// The daily limit, in dollars, when one is set.
pub fn limit() -> Option<f64> {
    let text = crate::sys::var("DAILY_LIMIT").ok().or_else(|| std::fs::read_to_string(crate::sys::base_dir().join("spend-limit.txt")).ok())?;
    text.trim().trim_start_matches('$').parse::<f64>().ok().filter(|v| *v > 0.0)
}

/// Set or clear the daily limit.
pub fn set_limit(value: Option<f64>) {
    let file = crate::sys::base_dir().join("spend-limit.txt");
    match value.filter(|v| *v > 0.0) {
        Some(v) => {
            let _ = std::fs::write(file, format!("{v}"));
        }
        None => {
            let _ = std::fs::remove_file(file);
        }
    }
}

/// When today's spending has reached the limit: what to tell the user.
pub fn over_limit() -> Option<String> {
    let limit = limit()?;
    let (today, _) = totals();
    (today >= limit).then(|| {
        if crate::sys::english() {
            format!("Today's spending limit (${limit:.2}) is reached: about ${today:.2} so far. Raise or clear it under Keys.")
        } else {
            format!("오늘의 사용 한도(${limit:.2})에 닿았습니다. 지금까지 약 ${today:.2}. 키 창에서 올리거나 없앨 수 있습니다.")
        }
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn prices_follow_the_model() {
        assert_eq!(price("claude-opus-5-5").unwrap().input, 4.0);
        assert_eq!(price("claude-sonnet-5-5").unwrap().output, 10.0);
        assert!(price("gpt-5").is_none());
        // A million tokens of output on Opus 5.5 is $20.
        assert!((cost("claude-opus-5-5", 0, 0, 0, 1.0, 1_000_000).unwrap() - 20.0).abs() < 1e-9);
        // Sonnet costs half of Opus for the same turn.
        let (o, s) = (per_turn("claude-opus-5-5").unwrap(), per_turn("claude-sonnet-5-5").unwrap());
        assert!((o / s - 2.0).abs() < 0.01, "{o} {s}");
        assert_eq!(money(0.04), "≈ 4¢");
        assert_eq!(money(0.001), "< 1¢");
    }
}
