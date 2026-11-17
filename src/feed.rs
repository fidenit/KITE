//! 실행 중인 보드에 다른 프로그램이 프로토콜 줄을 넣는 통로 (docs/PROTOCOL.md 11장).
//! 기본은 꺼져 있다. 환경 변수 KITE_PROTOCOL_PORT 가 있을 때만 127.0.0.1 에서 듣는다.
//! 표준 라이브러리만 쓰므로 윈도우·맥·리눅스에서 같다.
//!
//! 받은 줄은 모델이 낸 줄과 같은 문(`App::apply_line`)을 지나간다. 이 모듈은 줄을 끊고, 크기와 속도를 제한하고,
//! 줄마다 답 한 줄(`ok`, `ok <알림>`, `err <이유>`)을 돌려주는 일만 한다.

use std::io::{BufRead, BufReader, Read, Write};
use std::net::{Shutdown, TcpListener, TcpStream};
use std::sync::Arc;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::{Duration, Instant};

/// 한 줄의 최대 바이트 수. 보드의 가장 긴 값(600자 주소)도 한글로 넉넉히 들어간다.
pub const MAX_LINE: usize = 4096;
/// 한 연결이 초당 보낼 수 있는 줄 수(채우는 속도)와, 한꺼번에 몰아 보낼 수 있는 줄 수.
pub const LINES_PER_SEC: f64 = 50.0;
pub const BURST: f64 = 100.0;
/// 동시에 열어 둘 수 있는 연결 수.
pub const MAX_CONNS: usize = 4;
/// 아무것도 보내지 않는 연결을 끊는 시간.
pub const IDLE: Duration = Duration::from_secs(600);

/// 한 줄에 대한 판정. 이 모듈이 받아 준 줄만 `Line` 이다.
#[derive(Debug, PartialEq)]
pub enum Item {
    /// 보드에 적용해 볼 줄 (빈 줄·주석은 여기까지 오지 않는다)
    Line(String),
    /// 적용하지 않고 돌려보낼 이유
    Reject(String),
    /// 더 받지 않고 연결을 닫는다: 지원하지 않는 프로토콜 버전을 선언했거나, HTTP 요청이 왔다.
    Close(String),
}

/// HTTP 요청의 첫 줄(`POST / HTTP/1.1`)처럼 생겼는가. 브라우저 안의 아무 웹페이지나 127.0.0.1 로 요청을 보낼 수 있고,
/// 그대로 두면 요청 본문의 줄이 프로토콜 줄로 읽혀 보드에 적힌다. 프로토콜 명령은 모두 소문자라 헷갈리지 않는다.
fn looks_like_http(line: &str) -> bool {
    let mut parts = line.split_whitespace();
    let (Some(method), Some(_target), Some(version), None) = (parts.next(), parts.next(), parts.next(), parts.next()) else {
        return false;
    };
    method.bytes().all(|b| b.is_ascii_uppercase()) && version.starts_with("HTTP/")
}

/// 바이트 조각을 줄로 끊는 부분. 소켓과 시계를 모르므로 그대로 시험할 수 있다.
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

    /// 받은 바이트를 넣고, 끝난 줄마다 판정을 돌려받는다. 끝나지 않은 줄은 다음 조각을 기다린다.
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
                // 너무 긴 줄의 나머지는 줄이 끝날 때까지 버린다
            } else if self.buf.len() >= MAX_LINE {
                self.skipping = true;
                self.buf.clear();
                out.push(Item::Reject(format!("줄이 {MAX_LINE}바이트를 넘음")));
            } else {
                self.buf.push(b);
            }
        }
        out
    }

    fn finish(&mut self, raw: &[u8], now: Instant) -> Option<Item> {
        let Ok(text) = std::str::from_utf8(raw) else {
            return Some(Item::Reject("UTF-8 이 아님".into()));
        };
        let line = text.trim();
        if let Some(v) = crate::lang::declared_version(line) {
            return if crate::lang::can_read(v) { None } else { Some(Item::Close(format!("프로토콜 {v} 은 읽을 수 없음 (이 프로그램은 {})", crate::lang::VERSION))) };
        }
        if looks_like_http(line) {
            return Some(Item::Close("HTTP 요청은 받지 않음 (웹페이지가 보낸 요청일 수 있음)".into()));
        }
        if line.is_empty() || line.starts_with('#') || line.starts_with("```") {
            return None;
        }
        self.tokens = (self.tokens + now.saturating_duration_since(self.last).as_secs_f64() * LINES_PER_SEC).min(BURST);
        self.last = now;
        if self.tokens < 1.0 {
            return Some(Item::Reject(format!("너무 빠름 (초당 {LINES_PER_SEC:.0}줄까지)")));
        }
        self.tokens -= 1.0;
        Some(Item::Line(line.to_string()))
    }
}

/// 줄을 받아 보드에 적용하는 쪽. `line` 은 판정 한 줄(`ok` / `ok <알림>` / `err <이유>`)을 돌려준다.
pub trait Sink: Send + Sync {
    fn line(&self, peer: &str, line: &str) -> String;
    /// 연결·거부·시작 같은 일을 기록한다. 실패해도 기능을 막지 않는다.
    fn note(&self, text: &str);
}

/// 127.0.0.1:port 에서 듣기 시작한다. 이미 쓰이는 포트면 이유를 돌려준다.
pub fn start(port: u16, token: Option<String>, sink: Arc<dyn Sink>) -> Result<(), String> {
    let listener = TcpListener::bind(("127.0.0.1", port)).map_err(|e| format!("127.0.0.1:{port} 에서 들을 수 없음: {e}"))?;
    sink.note(&format!("듣기 시작 127.0.0.1:{port} 프로토콜 {} 암호 {}", crate::lang::VERSION, if token.is_some() { "있음" } else { "없음" }));
    let open = Arc::new(AtomicUsize::new(0));
    std::thread::spawn(move || {
        for conn in listener.incoming().flatten() {
            if open.fetch_add(1, Ordering::SeqCst) >= MAX_CONNS {
                open.fetch_sub(1, Ordering::SeqCst);
                sink.note("연결 거부: 동시 연결이 너무 많음");
                let _ = (&conn).write_all(b"err too many connections\n");
                continue;
            }
            let (sink, open, token) = (sink.clone(), open.clone(), token.clone());
            std::thread::spawn(move || {
                serve(conn, token.as_deref(), &*sink);
                open.fetch_sub(1, Ordering::SeqCst);
            });
        }
    });
    Ok(())
}

fn serve(conn: TcpStream, token: Option<&str>, sink: &dyn Sink) {
    let peer = conn.peer_addr().map(|a| a.to_string()).unwrap_or_else(|_| "?".into());
    // 같은 컴퓨터가 아니면 받지 않는다 (127.0.0.1 에만 묶여 있어 일어나지 않아야 한다).
    if !conn.peer_addr().map(|a| a.ip().is_loopback()).unwrap_or(false) {
        sink.note(&format!("{peer} 연결 거부: 이 컴퓨터가 아님"));
        return;
    }
    let _ = conn.set_read_timeout(Some(IDLE));
    let _ = conn.set_write_timeout(Some(Duration::from_secs(10)));
    sink.note(&format!("{peer} 연결"));
    let mut out = &conn;
    let mut reader = BufReader::new(&conn);
    let mut authed = token.is_none();
    let mut feeder = LineFeeder::new(Instant::now());
    let mut chunk = Vec::new();
    loop {
        chunk.clear();
        // 줄바꿈 없이 끝없이 보내는 상대에게 메모리를 내주지 않는다: 한 번에 MAX_LINE+1 바이트까지만 읽는다.
        match (&mut reader).take(MAX_LINE as u64 + 1).read_until(b'\n', &mut chunk) {
            Ok(0) | Err(_) => break,
            Ok(_) => {}
        }
        for item in feeder.push(&chunk, Instant::now()) {
            let reply = match item {
                Item::Line(line) if !authed => match line.strip_prefix("auth ") {
                    Some(t) if Some(t.trim()) == token => {
                        authed = true;
                        "ok".to_string()
                    }
                    _ => {
                        sink.note(&format!("{peer} 암호가 맞지 않음, 연결을 닫음"));
                        let _ = out.write_all(b"err auth required\n");
                        let _ = conn.shutdown(Shutdown::Both);
                        return;
                    }
                },
                Item::Line(line) => sink.line(&peer, &line),
                Item::Reject(why) => {
                    sink.note(&format!("{peer} 거부: {why}"));
                    format!("err {why}")
                }
                Item::Close(why) => {
                    sink.note(&format!("{peer} 연결을 닫음: {why}"));
                    let _ = writeln!(out, "err {why}");
                    let _ = conn.shutdown(Shutdown::Both);
                    return;
                }
            };
            if writeln!(out, "{}", reply.replace('\n', " / ")).is_err() {
                return;
            }
        }
    }
    sink.note(&format!("{peer} 연결 끝"));
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
        assert!(f.push(&b[..5], t).is_empty()); // 글자 한가운데서 끊김
        assert_eq!(f.push(&b[5..], t), vec![l("say 안녕")]);
    }

    #[test]
    fn too_long_line_is_rejected_once_and_rest_dropped() {
        let t = Instant::now();
        let mut f = LineFeeder::new(t);
        let long = "x".repeat(MAX_LINE * 3);
        let out = f.push(format!("say {long}\nsay ok\n").as_bytes(), t);
        assert_eq!(out.len(), 2);
        assert!(matches!(&out[0], Item::Reject(m) if m.contains("4096")));
        assert_eq!(out[1], l("say ok"));
        // 길이가 한도 안이면 받는다
        let fine = "y".repeat(MAX_LINE - 4);
        assert_eq!(f.push(format!("say {fine}\n").as_bytes(), t).len(), 1);
    }

    #[test]
    fn invalid_utf8_is_rejected() {
        let t = Instant::now();
        let mut f = LineFeeder::new(t);
        let out = f.push(b"say \xff\xfe\nsay ok\n", t);
        assert!(matches!(&out[0], Item::Reject(m) if m.contains("UTF-8")));
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
        assert_eq!(out.iter().filter(|i| matches!(i, Item::Reject(_))).count(), 5);
        // 1초 뒤에는 초당 한도만큼 다시 받는다
        let later = t + Duration::from_secs(1);
        let out = f.push(many.as_bytes(), later);
        assert_eq!(out.iter().filter(|i| matches!(i, Item::Line(_))).count(), LINES_PER_SEC as usize);
        // 빈 줄과 주석은 한도를 쓰지 않는다
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
        assert!(matches!(&out[0], Item::Close(m) if m.contains("1.0")));
    }

    #[test]
    fn http_request_line_closes() {
        let t = Instant::now();
        let mut f = LineFeeder::new(t);
        let out = f.push(b"POST / HTTP/1.1\r\nsay pwned\n", t);
        assert!(matches!(&out[0], Item::Close(m) if m.contains("HTTP")));
        for req in ["GET /x?y=1 HTTP/1.1", "PUT / HTTP/1.0", "PRI * HTTP/2.0", "OPTIONS * HTTP/1.1"] {
            assert!(looks_like_http(req), "{req}");
        }
        // 프로토콜 줄은 HTTP 요청으로 오인하지 않는다
        for op in ["say GET / HTTP/1.1", "topic http HTTP/1", "set k1 text HTTP/1.1", "GET / HTTP"] {
            assert!(!looks_like_http(op), "{op}");
        }
    }

    struct Fake(Mutex<Vec<String>>);
    impl Sink for Fake {
        fn line(&self, _peer: &str, line: &str) -> String {
            self.0.lock().unwrap().push(line.to_string());
            // 줄에 "bad" 가 있으면 거부, 나머지는 반영
            if line.contains("bad") { "err 모르는 명령".into() } else { "ok".into() }
        }
        fn note(&self, _text: &str) {}
    }

    fn talk(port: u16, send: &[u8], want: usize) -> Vec<String> {
        let mut c = TcpStream::connect(("127.0.0.1", port)).unwrap();
        c.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
        c.write_all(send).unwrap();
        let mut r = BufReader::new(c);
        (0..want).map(|_| { let mut s = String::new(); r.read_line(&mut s).unwrap(); s.trim_end().to_string() }).collect()
    }

    fn free_port() -> u16 {
        TcpListener::bind(("127.0.0.1", 0)).unwrap().local_addr().unwrap().port()
    }

    #[test]
    fn tcp_round_trip_one_reply_per_op_line() {
        let sink = Arc::new(Fake(Mutex::new(Vec::new())));
        let port = free_port();
        start(port, None, sink.clone()).unwrap();
        let got = talk(port, b"# kite-protocol 0.1\ntopic a A\n\nbad line\nsay hi\n", 3);
        assert_eq!(got, vec!["ok", "err 모르는 명령", "ok"]);
        assert_eq!(*sink.0.lock().unwrap(), vec!["topic a A", "bad line", "say hi"]);
    }

    #[test]
    fn tcp_token_required_when_set() {
        let sink = Arc::new(Fake(Mutex::new(Vec::new())));
        let port = free_port();
        start(port, Some("s3".into()), sink.clone()).unwrap();
        assert_eq!(talk(port, b"auth s3\nsay a\n", 2), vec!["ok", "ok"]);
        assert_eq!(talk(port, b"say a\n", 1), vec!["err auth required"]);
        assert_eq!(talk(port, b"auth nope\nsay b\n", 1), vec!["err auth required"]);
        assert_eq!(*sink.0.lock().unwrap(), vec!["say a"]);
    }

    #[test]
    fn tcp_refuses_unknown_major_and_port_in_use() {
        let sink = Arc::new(Fake(Mutex::new(Vec::new())));
        let port = free_port();
        start(port, None, sink.clone()).unwrap();
        let got = talk(port, b"# kite-protocol 2.0\nsay a\n", 1);
        assert!(got[0].starts_with("err ") && got[0].contains("2.0"));
        assert!(sink.0.lock().unwrap().is_empty());
        assert!(start(port, None, sink).is_err());
    }

    #[test]
    fn tcp_browser_request_writes_nothing() {
        // 웹페이지의 fetch(..., {mode: "no-cors", body}) 가 보내는 모양: 요청 줄, 머리글, 빈 줄, 본문
        let sink = Arc::new(Fake(Mutex::new(Vec::new())));
        let port = free_port();
        start(port, None, sink.clone()).unwrap();
        let req = b"POST / HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: text/plain\r\nOrigin: https://evil.example\r\n\r\nsay pwned\ncard w1 metric a 2x2 X\n";
        let got = talk(port, req, 1);
        assert!(got[0].starts_with("err ") && got[0].contains("HTTP"), "{got:?}");
        assert!(sink.0.lock().unwrap().is_empty());
    }
}
