//! The protocol socket: another program on this computer drives the running board with protocol lines
//! (protocol/SPEC.md, "Line session"). Off by default; it listens on 127.0.0.1 only when
//! KITE_PROTOCOL_PORT is set. Standard library only, so it is the same on Windows, macOS and Linux.
//!
//! A received op goes through the same door as a model's line (`App::apply_op_line`). This module cuts
//! bytes into lines, limits size and rate, answers each line with one verdict (`ok`, `ok clipped ...`,
//! `err <code> <message>`), and serves the session commands: `auth`, `read`, `watch`, `unwatch`.

use std::io::{BufRead, BufReader, Read, Write};
use std::net::{Shutdown, TcpListener, TcpStream};
use std::sync::atomic::{AtomicBool, AtomicU8, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use crate::hub::Hub;

/// Longest line in bytes. The longest board value (a 600-character address) fits with room to spare.
pub const MAX_LINE: usize = 4096;
/// Lines a connection may send per second (the refill rate), and how many it may send in one burst.
pub const LINES_PER_SEC: f64 = 50.0;
pub const BURST: f64 = 100.0;
/// Connections open at once.
pub const MAX_CONNS: usize = 4;
/// A connection that sends nothing for this long is closed, unless it is watching.
pub const IDLE: Duration = Duration::from_secs(600);

/// The verdict on one received line. Only lines this module accepts become `Line`.
#[derive(Debug, PartialEq)]
pub enum Item {
    /// A line to try (blank lines and comments never get this far).
    Line(String),
    /// Not tried; the reason goes back to the sender as `err <code> <message>`.
    Reject(String),
    /// Stop reading and close: an unsupported protocol version was declared, or an HTTP request arrived.
    Close(String),
}

/// Does this look like the first line of an HTTP request (`POST / HTTP/1.1`)? Any web page open in a browser
/// can send a request to 127.0.0.1; left alone, the lines of its body would be read as protocol lines and
/// written to the board. Protocol verbs are all lower case, so there is no confusion.
fn looks_like_http(line: &str) -> bool {
    let mut parts = line.split_whitespace();
    let (Some(method), Some(_target), Some(version), None) = (parts.next(), parts.next(), parts.next(), parts.next()) else {
        return false;
    };
    method.bytes().all(|b| b.is_ascii_uppercase()) && version.starts_with("HTTP/")
}

/// Cuts byte pieces into lines. It knows nothing of sockets or clocks, so it can be tested as it is.
pub struct LineFeeder {
    buf: Vec<u8>,
    skipping: bool,
    tokens: f64,
    last: Instant,
}

impl LineFeeder {
    pub fn new(now: Instant) -> Self {
        LineFeeder { buf: Vec::new(), skipping: false, tokens: BURST, last: now }
    }

    /// Push received bytes; get a verdict for every line that ended. An unfinished line waits for the next piece.
    pub fn push(&mut self, bytes: &[u8], now: Instant) -> Vec<Item> {
        let mut out = Vec::new();
        for &b in bytes {
            if b == b'\n' {
                if std::mem::take(&mut self.skipping) {
                    self.buf.clear();
                } else {
                    let raw = std::mem::take(&mut self.buf);
                    if let Some(it) = self.finish(&raw, now) {
                        out.push(it);
                    }
                }
            } else if self.skipping {
                // The rest of a line that was too long is dropped until the line ends.
            } else if self.buf.len() >= MAX_LINE {
                self.skipping = true;
                self.buf.clear();
                out.push(Item::Reject(format!("line-too-long a line is longer than {MAX_LINE} bytes")));
            } else {
                self.buf.push(b);
            }
        }
        out
    }

    fn finish(&mut self, raw: &[u8], now: Instant) -> Option<Item> {
        let Ok(text) = std::str::from_utf8(raw) else {
            return Some(Item::Reject("bad-utf8 the line is not UTF-8".into()));
        };
        let line = text.trim();
        if let Some(v) = crate::lang::declared_version(line) {
            return if crate::lang::can_read(v) { None } else { Some(Item::Close(format!("unsupported-version cannot read protocol {v} (this program speaks {})", crate::lang::VERSION))) };
        }
        if looks_like_http(line) {
            return Some(Item::Close("http-refused HTTP requests are not accepted here (a web page may have sent this)".into()));
        }
        if crate::lang::is_blank(line) {
            return None;
        }
        self.tokens = (self.tokens + now.saturating_duration_since(self.last).as_secs_f64() * LINES_PER_SEC).min(BURST);
        self.last = now;
        if self.tokens < 1.0 {
            return Some(Item::Reject(format!("rate-limited too fast (up to {LINES_PER_SEC:.0} lines a second)")));
        }
        self.tokens -= 1.0;
        Some(Item::Line(line.to_string()))
    }
}

/// The side that applies lines to the board.
pub trait Sink: Send + Sync {
    /// Try one op line. Returns the verdict: `ok`, `ok clipped <what> <limit>`, or `err <code> <message>`.
    fn line(&self, peer: &str, line: &str) -> String;
    /// The board as protocol lines: all of it when `topic` is empty, else that topic. Err is `<code> <message>`.
    fn read(&self, topic: &str) -> Result<String, String>;
    /// Record a connection, a refusal, the start. A failure here never blocks the feed.
    fn note(&self, text: &str);
}

/// Start listening on 127.0.0.1:port. Returns the reason when the port cannot be used.
pub fn start(port: u16, token: Option<String>, sink: Arc<dyn Sink>, hub: Arc<Hub>) -> Result<(), String> {
    let listener = TcpListener::bind(("127.0.0.1", port)).map_err(|e| format!("cannot listen on 127.0.0.1:{port}: {e}"))?;
    sink.note(&format!("listening on 127.0.0.1:{port}, protocol {}, token {}", crate::lang::VERSION, if token.is_some() { "set" } else { "not set" }));
    let open = Arc::new(AtomicUsize::new(0));
    std::thread::spawn(move || {
        for conn in listener.incoming().flatten() {
            if open.fetch_add(1, Ordering::SeqCst) >= MAX_CONNS {
                open.fetch_sub(1, Ordering::SeqCst);
                sink.note("connection refused: too many connections");
                let _ = (&conn).write_all(b"err too-many-connections try again later\n");
                continue;
            }
            let (sink, open, token, hub) = (sink.clone(), open.clone(), token.clone(), hub.clone());
            std::thread::spawn(move || {
                let closed = Arc::new(AtomicBool::new(false));
                serve(conn, token.as_deref(), &*sink, &hub, &closed);
                closed.store(true, Ordering::SeqCst);
                open.fetch_sub(1, Ordering::SeqCst);
            });
        }
    });
    Ok(())
}

/// What a connection asked to be told: nothing, what the user does, or everything other writers do too.
const WATCH_OFF: u8 = 0;
const WATCH_USER: u8 = 1;
const WATCH_ALL: u8 = 2;

fn serve(conn: TcpStream, token: Option<&str>, sink: &dyn Sink, hub: &Arc<Hub>, closed: &Arc<AtomicBool>) {
    let peer = conn.peer_addr().map(|a| a.to_string()).unwrap_or_else(|_| "?".into());
    // Only this computer (the listener is bound to 127.0.0.1, so this should never trigger).
    if !conn.peer_addr().map(|a| a.ip().is_loopback()).unwrap_or(false) {
        sink.note(&format!("{peer} refused: not this computer"));
        return;
    }
    let _ = conn.set_read_timeout(Some(IDLE));
    let _ = conn.set_write_timeout(Some(Duration::from_secs(10)));
    sink.note(&format!("{peer} connected"));
    // Verdicts and pushed events are written from two threads; one lock keeps their lines whole.
    let Ok(writer) = conn.try_clone() else { return };
    let out = Arc::new(Mutex::new(writer));
    let say = |text: &str| -> bool {
        let mut w = out.lock().unwrap_or_else(|e| e.into_inner());
        writeln!(w, "{}", text.replace('\n', " / ")).is_ok()
    };
    let watch = Arc::new(AtomicU8::new(WATCH_OFF));
    let mut watcher_started = false;
    let mut reader = BufReader::new(&conn);
    let mut authed = token.is_none();
    let mut feeder = LineFeeder::new(Instant::now());
    let mut chunk = Vec::new();
    loop {
        chunk.clear();
        // Never hand memory to a sender that sends without a line break: read MAX_LINE+1 bytes at most.
        match (&mut reader).take(MAX_LINE as u64 + 1).read_until(b'\n', &mut chunk) {
            Ok(0) => break,
            Ok(_) => {}
            // A watching connection may stay silent as long as it likes.
            Err(e) if watch.load(Ordering::SeqCst) != WATCH_OFF && matches!(e.kind(), std::io::ErrorKind::WouldBlock | std::io::ErrorKind::TimedOut) => continue,
            Err(_) => break,
        }
        for item in feeder.push(&chunk, Instant::now()) {
            let reply = match item {
                Item::Line(line) if !authed => match line.strip_prefix("auth ") {
                    Some(t) if Some(t.trim()) == token => {
                        authed = true;
                        "ok".to_string()
                    }
                    _ => {
                        sink.note(&format!("{peer} wrong or missing token, closed"));
                        say("err auth-required send 'auth <token>' as the first line");
                        let _ = conn.shutdown(Shutdown::Both);
                        return;
                    }
                },
                Item::Line(line) => {
                    let mut words = line.split_whitespace();
                    match (words.next().unwrap_or(""), words.next(), words.next()) {
                        ("auth", _, _) => "ok".to_string(),
                        ("read", topic, None) => match sink.read(topic.unwrap_or("")) {
                            Ok(text) => {
                                // The board goes out as data lines, each behind `| `, and then the verdict.
                                let mut w = out.lock().unwrap_or_else(|e| e.into_inner());
                                let sent = text.lines().all(|l| writeln!(w, "| {l}").is_ok());
                                drop(w);
                                if !sent {
                                    return;
                                }
                                "ok".to_string()
                            }
                            Err(why) => format!("err {why}"),
                        },
                        ("watch", mode @ (None | Some("all")), None) => {
                            watch.store(if mode.is_some() { WATCH_ALL } else { WATCH_USER }, Ordering::SeqCst);
                            if !std::mem::replace(&mut watcher_started, true) {
                                let (hub, out, watch, closed, me) = (hub.clone(), out.clone(), watch.clone(), closed.clone(), peer.clone());
                                let mut after = hub.cursor();
                                std::thread::spawn(move || {
                                    while !closed.load(Ordering::SeqCst) {
                                        // The mode is read per event, so a change of mode applies to whatever is published after it.
                                        let want = |e: &crate::hub::Event| match watch.load(Ordering::SeqCst) {
                                            WATCH_USER => e.kind != "agent",
                                            WATCH_ALL => e.origin != me,
                                            _ => false,
                                        };
                                        let (events, next) = hub.wait(after, Duration::from_millis(500), &want);
                                        after = next;
                                        let mut w = out.lock().unwrap_or_else(|e| e.into_inner());
                                        for e in events {
                                            if writeln!(w, "event {}", e.line()).is_err() {
                                                return;
                                            }
                                        }
                                    }
                                });
                            }
                            "ok".to_string()
                        }
                        ("unwatch", None, None) => {
                            watch.store(WATCH_OFF, Ordering::SeqCst);
                            "ok".to_string()
                        }
                        ("read" | "watch" | "unwatch", _, _) => "err bad-syntax expected: read [<topic>] | watch [all] | unwatch".to_string(),
                        _ => sink.line(&peer, &line),
                    }
                }
                Item::Reject(why) => {
                    sink.note(&format!("{peer} rejected: {why}"));
                    format!("err {why}")
                }
                Item::Close(why) => {
                    sink.note(&format!("{peer} closed: {why}"));
                    say(&format!("err {why}"));
                    let _ = conn.shutdown(Shutdown::Both);
                    return;
                }
            };
            if !say(&reply) {
                return;
            }
        }
    }
    sink.note(&format!("{peer} disconnected"));
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Mutex;

    fn lines(f: &mut LineFeeder, s: &str, t: Instant) -> Vec<Item> {
        f.push(s.as_bytes(), t)
    }
    fn l(s: &str) -> Item {
        Item::Line(s.into())
    }

    #[test]
    fn splits_trims_and_skips_comments() {
        let t = Instant::now();
        let mut f = LineFeeder::new(t);
        let out = lines(&mut f, "topic a A\r\n\n  # note\n```\nsay hi  \n", t);
        assert_eq!(out, vec![l("topic a A"), l("say hi")]);
    }

    #[test]
    fn joins_pieces_and_keeps_the_unfinished_line() {
        let t = Instant::now();
        let mut f = LineFeeder::new(t);
        assert!(lines(&mut f, "topic a ", t).is_empty());
        assert!(lines(&mut f, "A", t).is_empty());
        assert_eq!(lines(&mut f, "\nsay x", t), vec![l("topic a A")]);
        assert_eq!(lines(&mut f, "y\n", t), vec![l("say xy")]);
    }

    #[test]
    fn multibyte_split_across_pieces() {
        let t = Instant::now();
        let mut f = LineFeeder::new(t);
        let b = "say 안녕\n".as_bytes();
        assert!(f.push(&b[..5], t).is_empty()); // cut in the middle of a character
        assert_eq!(f.push(&b[5..], t), vec![l("say 안녕")]);
    }

    #[test]
    fn too_long_line_is_rejected_once_and_rest_dropped() {
        let t = Instant::now();
        let mut f = LineFeeder::new(t);
        let long = "x".repeat(MAX_LINE * 3);
        let out = f.push(format!("say {long}\nsay ok\n").as_bytes(), t);
        assert_eq!(out.len(), 2);
        assert!(matches!(&out[0], Item::Reject(m) if m.starts_with("line-too-long ")));
        assert_eq!(out[1], l("say ok"));
        // Within the limit it is taken.
        let fine = "y".repeat(MAX_LINE - 4);
        assert_eq!(f.push(format!("say {fine}\n").as_bytes(), t).len(), 1);
    }

    #[test]
    fn invalid_utf8_is_rejected() {
        let t = Instant::now();
        let mut f = LineFeeder::new(t);
        let out = f.push(b"say \xff\xfe\nsay ok\n", t);
        assert!(matches!(&out[0], Item::Reject(m) if m.starts_with("bad-utf8 ")));
        assert_eq!(out[1], l("say ok"));
    }

    #[test]
    fn rate_limit_burst_then_refill() {
        let t = Instant::now();
        let mut f = LineFeeder::new(t);
        let n = BURST as usize;
        let many: String = (0..n + 5).map(|i| format!("say {i}\n")).collect();
        let out = f.push(many.as_bytes(), t);
        assert_eq!(out.iter().filter(|i| matches!(i, Item::Line(_))).count(), n);
        assert_eq!(out.iter().filter(|i| matches!(i, Item::Reject(m) if m.starts_with("rate-limited "))).count(), 5);
        // A second later it takes the per-second amount again.
        let later = t + Duration::from_secs(1);
        let out = f.push(many.as_bytes(), later);
        assert_eq!(out.iter().filter(|i| matches!(i, Item::Line(_))).count(), LINES_PER_SEC as usize);
        // Blank lines and comments do not use the allowance.
        let mut g = LineFeeder::new(t);
        let blank = "\n# c\n".repeat(1000);
        assert!(g.push(blank.as_bytes(), t).is_empty());
        assert_eq!(g.push(b"say a\n", t), vec![l("say a")]);
    }

    #[test]
    fn version_header() {
        let t = Instant::now();
        let mut f = LineFeeder::new(t);
        assert_eq!(f.push(b"# kite-protocol 0.1\nsay a\n", t), vec![l("say a")]);
        assert_eq!(f.push(b"# kite-protocol 0.7\n", t), vec![]);
        let out = f.push(b"# kite-protocol 1.0\nsay b\n", t);
        assert!(matches!(&out[0], Item::Close(m) if m.starts_with("unsupported-version ") && m.contains("1.0")));
    }

    #[test]
    fn http_request_line_closes() {
        let t = Instant::now();
        let mut f = LineFeeder::new(t);
        let out = f.push(b"POST / HTTP/1.1\r\nsay pwned\n", t);
        assert!(matches!(&out[0], Item::Close(m) if m.starts_with("http-refused ")));
        for req in ["GET /x?y=1 HTTP/1.1", "PUT / HTTP/1.0", "PRI * HTTP/2.0", "OPTIONS * HTTP/1.1"] {
            assert!(looks_like_http(req), "{req}");
        }
        // A protocol line is never taken for an HTTP request.
        for op in ["say GET / HTTP/1.1", "topic http HTTP/1", "set k1 text HTTP/1.1", "GET / HTTP"] {
            assert!(!looks_like_http(op), "{op}");
        }
    }

    struct Fake(Mutex<Vec<String>>);
    impl Sink for Fake {
        fn line(&self, _peer: &str, line: &str) -> String {
            self.0.lock().unwrap().push(line.to_string());
            // A line with "bad" in it is rejected; the rest are applied.
            if line.contains("bad") { "err unknown-verb unknown verb 'bad'".into() } else { "ok".into() }
        }
        fn read(&self, topic: &str) -> Result<String, String> {
            match topic {
                "" => Ok("topic a A\ncard k1 note a 3x2 One\n".into()),
                "a" => Ok("topic a A\n".into()),
                _ => Err(format!("no-such-topic no topic '{topic}'")),
            }
        }
        fn note(&self, _text: &str) {}
    }

    fn open(port: u16) -> (TcpStream, BufReader<TcpStream>) {
        let c = TcpStream::connect(("127.0.0.1", port)).unwrap();
        c.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
        let r = BufReader::new(c.try_clone().unwrap());
        (c, r)
    }

    fn next(r: &mut BufReader<TcpStream>) -> String {
        let mut s = String::new();
        r.read_line(&mut s).unwrap();
        s.trim_end().to_string()
    }

    fn talk(port: u16, send: &[u8], want: usize) -> Vec<String> {
        let (mut c, mut r) = open(port);
        c.write_all(send).unwrap();
        (0..want).map(|_| next(&mut r)).collect()
    }

    fn free_port() -> u16 {
        TcpListener::bind(("127.0.0.1", 0)).unwrap().local_addr().unwrap().port()
    }

    fn serve_fake(token: Option<&str>) -> (u16, Arc<Fake>, Arc<Hub>) {
        let sink = Arc::new(Fake(Mutex::new(Vec::new())));
        let hub = Arc::new(Hub::default());
        let port = free_port();
        start(port, token.map(str::to_string), sink.clone(), hub.clone()).unwrap();
        (port, sink, hub)
    }

    #[test]
    fn tcp_round_trip_one_reply_per_op_line() {
        let (port, sink, _) = serve_fake(None);
        let got = talk(port, b"# kite-protocol 0.2\ntopic a A\n\nbad line\nsay hi\n", 3);
        assert_eq!(got, vec!["ok", "err unknown-verb unknown verb 'bad'", "ok"]);
        assert_eq!(*sink.0.lock().unwrap(), vec!["topic a A", "bad line", "say hi"]);
    }

    #[test]
    fn tcp_token_required_when_set() {
        let (port, sink, _) = serve_fake(Some("s3"));
        assert_eq!(talk(port, b"auth s3\nsay a\n", 2), vec!["ok", "ok"]);
        assert!(talk(port, b"say a\n", 1)[0].starts_with("err auth-required "));
        assert!(talk(port, b"auth nope\nsay b\n", 1)[0].starts_with("err auth-required "));
        assert_eq!(*sink.0.lock().unwrap(), vec!["say a"]);
    }

    #[test]
    fn tcp_refuses_unknown_major_and_port_in_use() {
        let (port, sink, hub) = serve_fake(None);
        let got = talk(port, b"# kite-protocol 2.0\nsay a\n", 1);
        assert!(got[0].starts_with("err unsupported-version ") && got[0].contains("2.0"));
        assert!(sink.0.lock().unwrap().is_empty());
        assert!(start(port, None, sink, hub).is_err());
    }

    #[test]
    fn tcp_browser_request_writes_nothing() {
        // What a page's fetch(..., {mode: "no-cors", body}) sends: request line, headers, blank line, body.
        let (port, sink, _) = serve_fake(None);
        let req = b"POST / HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: text/plain\r\nOrigin: https://evil.example\r\n\r\nsay pwned\ncard w1 metric a 2x2 X\n";
        let got = talk(port, req, 1);
        assert!(got[0].starts_with("err http-refused "), "{got:?}");
        assert!(sink.0.lock().unwrap().is_empty());
    }

    #[test]
    fn tcp_read_sends_the_board_as_data_lines() {
        let (port, sink, _) = serve_fake(None);
        assert_eq!(talk(port, b"read\n", 3), vec!["| topic a A", "| card k1 note a 3x2 One", "ok"]);
        assert_eq!(talk(port, b"read a\n", 2), vec!["| topic a A", "ok"]);
        assert_eq!(talk(port, b"read zz\n", 1), vec!["err no-such-topic no topic 'zz'"]);
        assert!(talk(port, b"read a b\n", 1)[0].starts_with("err bad-syntax "));
        // Session commands never reach the board.
        assert!(sink.0.lock().unwrap().is_empty());
    }

    #[test]
    fn tcp_watch_pushes_what_the_user_does() {
        let (port, _, hub) = serve_fake(None);
        let (mut c, mut r) = open(port);
        hub.publish("user", "user", "set k0 chosen before");
        c.write_all(b"watch\n").unwrap();
        assert_eq!(next(&mut r), "ok");
        hub.publish("agent", "model", "say not for a user watcher");
        hub.publish("user", "user", "set k2 chosen o1");
        hub.publish("input", "user", "book the second one");
        assert_eq!(next(&mut r), "event user set k2 chosen o1");
        assert_eq!(next(&mut r), "event input book the second one");
        // `watch all` adds what other writers do, but never the connection's own lines.
        c.write_all(b"watch all\n").unwrap();
        assert_eq!(next(&mut r), "ok");
        let me = c.local_addr().unwrap().to_string();
        hub.publish("agent", &me, "say mine");
        hub.publish("agent", "model", "say theirs");
        assert_eq!(next(&mut r), "event agent say theirs");
        c.write_all(b"unwatch\nsay x\n").unwrap();
        assert_eq!(next(&mut r), "ok");
        hub.publish("user", "user", "set k2 chosen o2");
        assert_eq!(next(&mut r), "ok");
    }
}
