//! What happened on the board, for programs outside KITE that want to follow it
//! (the protocol socket's `watch`, the MCP tool `events`). See protocol/SPEC.md, "Events".
//!
//! Every accepted op and every line the user types is published here with a running number.
//! A reader keeps the number of the last event it saw and asks for what came after it.

use std::collections::VecDeque;
use std::sync::{Arc, Condvar, Mutex, OnceLock};
use std::time::{Duration, Instant};

/// How many events are kept for readers that fall behind.
const KEEP: usize = 2000;

#[derive(Clone, Debug, PartialEq)]
pub struct Event {
    pub seq: u64,
    /// `user` (a gesture on the board, as an op), `agent` (an op from a model or a program), `input` (text the user typed or spoke).
    pub kind: &'static str,
    /// Who wrote it: `user`, `model`, or the address of the connection that sent it. A watcher is not told its own lines.
    pub origin: String,
    pub text: String,
}

impl Event {
    /// The event as one protocol line: `user <op>`, `agent <op>` or `input <text>`.
    pub fn line(&self) -> String {
        format!("{} {}", self.kind, self.text)
    }
}

#[derive(Default)]
struct Inner {
    last: u64,
    items: VecDeque<Event>,
}

#[derive(Default)]
pub struct Hub {
    inner: Mutex<Inner>,
    news: Condvar,
}

impl Hub {
    pub fn publish(&self, kind: &'static str, origin: &str, text: &str) {
        let mut g = self.inner.lock().unwrap_or_else(|e| e.into_inner());
        g.last += 1;
        let seq = g.last;
        g.items.push_back(Event { seq, kind, origin: origin.to_string(), text: text.trim().replace(['\n', '\r'], " ") });
        while g.items.len() > KEEP {
            g.items.pop_front();
        }
        drop(g);
        self.news.notify_all();
    }

    /// The number of the newest event. A reader that starts now passes this as `after`.
    pub fn cursor(&self) -> u64 {
        self.inner.lock().unwrap_or_else(|e| e.into_inner()).last
    }

    /// Events numbered above `after` that `want` accepts. Waits up to `timeout` when there are none yet.
    /// Returns them with the number to pass next time.
    pub fn wait(&self, after: u64, timeout: Duration, want: &dyn Fn(&Event) -> bool) -> (Vec<Event>, u64) {
        let until = Instant::now() + timeout;
        let mut g = self.inner.lock().unwrap_or_else(|e| e.into_inner());
        loop {
            let found: Vec<Event> = g.items.iter().filter(|e| e.seq > after && want(e)).cloned().collect();
            let left = until.saturating_duration_since(Instant::now());
            if !found.is_empty() || left.is_zero() {
                return (found, g.last.max(after));
            }
            g = self.news.wait_timeout(g, left).unwrap_or_else(|e| e.into_inner()).0;
        }
    }
}

/// The one hub of this program.
pub fn hub() -> Arc<Hub> {
    static HUB: OnceLock<Arc<Hub>> = OnceLock::new();
    HUB.get_or_init(Default::default).clone()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn readers_get_what_came_after_their_cursor() {
        let h = Hub::default();
        h.publish("agent", "model", "topic a A");
        let start = h.cursor();
        h.publish("user", "user", "set k2 chosen o1");
        h.publish("input", "user", "two\nlines");
        let (got, next) = h.wait(start, Duration::ZERO, &|_| true);
        assert_eq!(got.iter().map(Event::line).collect::<Vec<_>>(), ["user set k2 chosen o1", "input two lines"]);
        assert_eq!(next, 3);
        // Nothing new: returns empty after the timeout, and the cursor stays.
        assert_eq!(h.wait(next, Duration::from_millis(20), &|_| true), (vec![], 3));
        // A filter that takes nothing does not hold up the cursor.
        assert_eq!(h.wait(0, Duration::ZERO, &|e| e.kind == "nope"), (vec![], 3));
    }

    #[test]
    fn a_waiting_reader_wakes_when_something_is_published() {
        let h = Arc::new(Hub::default());
        let h2 = h.clone();
        let t = std::thread::spawn(move || h2.wait(0, Duration::from_secs(5), &|e| e.kind == "user"));
        std::thread::sleep(Duration::from_millis(50));
        h.publish("agent", "model", "say hi");
        h.publish("user", "user", "upd k1 i1 done | x");
        let (got, _) = t.join().unwrap();
        assert_eq!(got.len(), 1);
        assert_eq!(got[0].text, "upd k1 i1 done | x");
    }
}
