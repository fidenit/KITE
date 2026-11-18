//! The AG-UI engine: KITE is an AG-UI client and the agent is an AG-UI server (https://docs.ag-ui.com).
//! This is the AG-UI binding of the KITE Protocol (protocol/SPEC.md 10.5).
//!
//! For every turn KITE POSTs a `RunAgentInput` with the board, what the user did and said, the guide for
//! agents and one frontend tool (`screen`), then reads the agent's events as they stream in. Ops reach the
//! board from the agent's text, from `screen` tool calls, or from `CUSTOM` events named `kite.ops`.

use std::io::{BufRead, BufReader};
use std::sync::Mutex;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{Duration, Instant};

use serde_json::{Value, json};

use crate::llm::{Msg, Stats};

/// A run that ends with `screen` calls is followed by another that carries their verdicts. This bounds the chain.
const MAX_ROUNDS: usize = 12;

/// Where the agent is and what to send with every request.
#[derive(Clone, Debug, PartialEq)]
pub struct Settings {
    pub url: String,
    pub headers: Vec<(String, String)>,
}

/// KITE_AGUI_URL, or `agui.txt` next to the program: the address on the first line, then optional
/// `Header-Name: value` lines (an Authorization header, for example).
pub fn settings() -> Option<Settings> {
    if let Some(url) = crate::sys::var("AGUI_URL").ok().map(|u| u.trim().to_string()).filter(|u| !u.is_empty()) {
        return Some(Settings { url, headers: Vec::new() });
    }
    parse_settings(&std::fs::read_to_string(crate::sys::base_dir().join("agui.txt")).ok()?)
}

fn parse_settings(text: &str) -> Option<Settings> {
    let mut lines = text.lines().map(str::trim).filter(|l| !l.is_empty() && !l.starts_with('#'));
    let url = lines.next()?.to_string();
    if !url.starts_with("http://") && !url.starts_with("https://") {
        return None;
    }
    let headers = lines.filter_map(|l| l.split_once(':').map(|(k, v)| (k.trim().to_string(), v.trim().to_string()))).collect();
    Some(Settings { url, headers })
}

/// What one turn sends: the same things the spec lists, kept apart so they can go out both as text and as state.
#[derive(Clone, Default)]
pub struct Turn {
    /// The board as ops (SPEC.md section 8).
    pub board: String,
    /// What the user did since the last run, as event entries (`user set k2 chosen o2`).
    pub events: Vec<String>,
    /// Lines of the last run that were rejected or clipped, with the reason.
    pub rejected: Vec<String>,
    /// What the user typed or said. Empty when a gesture started the run.
    pub input: String,
}

impl Turn {
    /// The turn as the text of a user message.
    pub fn text(&self) -> String {
        let mut t = format!("<board>\n{}</board>\n", self.board);
        if !self.events.is_empty() {
            t += &format!("<events>\n{}\n</events>\n", self.events.join("\n"));
        }
        if !self.rejected.is_empty() {
            t += &format!("<rejected>\n{}\n</rejected>\n", self.rejected.join("\n"));
        }
        t + &format!("<input>{}</input>", self.input)
    }
    /// What stays in the history once the run is over: the board of that moment is no longer true.
    fn remembered(&self) -> String {
        let mut t = String::new();
        if !self.events.is_empty() {
            t += &format!("<events>\n{}\n</events>\n", self.events.join("\n"));
        }
        t + &format!("<input>{}</input>", self.input)
    }
}

/// One conversation with the agent: AG-UI calls it a thread.
struct Session {
    thread: String,
    messages: Vec<Value>,
    /// The agent's shared state, as last told (STATE_SNAPSHOT, STATE_DELTA). Sent back with every run.
    state: Value,
}

static SESSION: Mutex<Option<Session>> = Mutex::new(None);
static NEXT_ID: AtomicU64 = AtomicU64::new(1);

fn new_id(prefix: &str) -> String {
    use std::hash::{BuildHasher, Hasher};
    let r = std::collections::hash_map::RandomState::new().build_hasher().finish();
    format!("{prefix}-{:x}-{}", r & 0xffff_ffff, NEXT_ID.fetch_add(1, Ordering::Relaxed))
}

/// Forget the conversation (the user reset the board).
pub fn reset() {
    *SESSION.lock().unwrap_or_else(|e| e.into_inner()) = None;
}

/// The frontend tool KITE offers to the agent.
fn screen_tool() -> Value {
    json!({
        "name": "screen",
        "description": "Edit the user's KITE board. `ops` holds board ops, one per line, applied in order. The result is `applied`, or the lines that were rejected or clipped with a reason each. The board is the answer to the user: there is no chat. The syntax and the card types are in the context entry \"KITE Protocol guide\".",
        "parameters": { "type": "object", "properties": { "ops": { "type": "string", "description": "Ops, one per line." } }, "required": ["ops"] }
    })
}

/// Apply JSON Patch operations (RFC 6902) to the state. An operation that does not apply is skipped.
fn patch(state: &mut Value, ops: &Value) {
    fn split(path: &str) -> Option<(String, String)> {
        let at = path.rfind('/')?;
        Some((path[..at].to_string(), path[at + 1..].replace("~1", "/").replace("~0", "~")))
    }
    fn put(state: &mut Value, path: &str, value: Value, insert: bool) {
        if path.is_empty() {
            *state = value;
            return;
        }
        let Some((parent, key)) = split(path) else { return };
        match state.pointer_mut(&parent) {
            Some(Value::Object(m)) => {
                m.insert(key, value);
            }
            Some(Value::Array(a)) => {
                if key == "-" {
                    a.push(value);
                } else if let Ok(i) = key.parse::<usize>() {
                    if insert && i <= a.len() {
                        a.insert(i, value);
                    } else if i < a.len() {
                        a[i] = value;
                    }
                }
            }
            _ => {}
        }
    }
    fn take(state: &mut Value, path: &str) -> Option<Value> {
        let (parent, key) = split(path)?;
        match state.pointer_mut(&parent)? {
            Value::Object(m) => m.remove(&key),
            Value::Array(a) => key.parse::<usize>().ok().filter(|i| *i < a.len()).map(|i| a.remove(i)),
            _ => None,
        }
    }
    for op in ops.as_array().into_iter().flatten() {
        let path = op["path"].as_str().unwrap_or("");
        match op["op"].as_str().unwrap_or("") {
            "add" => put(state, path, op["value"].clone(), true),
            "replace" => put(state, path, op["value"].clone(), false),
            "remove" => {
                take(state, path);
            }
            "move" => {
                if let Some(v) = take(state, op["from"].as_str().unwrap_or("")) {
                    put(state, path, v, true);
                }
            }
            "copy" => {
                if let Some(v) = state.pointer(op["from"].as_str().unwrap_or("")).cloned() {
                    put(state, path, v, true);
                }
            }
            _ => {}
        }
    }
}

/// Text that is not an op, when the agent answered in prose: show it as the caption and a note, so that an
/// agent that knows nothing of KITE still says something on the board.
fn prose_as_ops(prose: &[String]) -> String {
    let text = prose.join(" ").split_whitespace().collect::<Vec<_>>().join(" ").replace('|', "/");
    let short: String = text.chars().take(78).collect();
    format!("say {short}\ntopic agent Agent\ncard agent-reply note agent 5x3 Reply\nset agent-reply text {text}")
}

/// Run one turn against the agent. Ops are sent on as they arrive; `stop` is the stop button.
pub fn run(set: &Settings, turn: &Turn, send: &dyn Fn(Msg)) -> Result<Stats, String> {
    let started = Instant::now();
    let host = set.url.split("//").nth(1).unwrap_or(&set.url).split('/').next().unwrap_or("").to_string();
    let mut stats = Stats { model: format!("AG-UI · {host}"), ..Default::default() };
    let (thread, mut messages, mut state) = {
        let mut g = SESSION.lock().unwrap_or_else(|e| e.into_inner());
        let s = g.get_or_insert_with(|| Session { thread: new_id("kite"), messages: Vec::new(), state: json!({}) });
        (s.thread.clone(), s.messages.clone(), s.state.clone())
    };
    let user_at = messages.len();
    messages.push(json!({ "id": new_id("user"), "role": "user", "content": turn.text() }));
    if !state.is_object() {
        state = json!({});
    }
    let mut events_for_state = turn.events.clone();
    let agent = crate::agent::http();
    let mut outcome = Ok(());
    let mut wrote_ops = false;
    let mut all_prose: Vec<String> = Vec::new();

    for _ in 0..MAX_ROUNDS {
        state["kite"] = json!({ "version": crate::lang::VERSION, "board": turn.board, "events": events_for_state });
        events_for_state.clear(); // told once
        let body = json!({
            "threadId": thread,
            "runId": new_id("run"),
            "state": state,
            "messages": messages,
            "tools": [screen_tool()],
            "context": [{ "description": "KITE Protocol guide: how to write the user's board. Your whole answer is board ops, one per line; do not write prose.", "value": crate::mcp::GUIDE }],
            "forwardedProps": {},
        });
        let mut req = agent.post(&set.url).header("content-type", "application/json").header("accept", "text/event-stream");
        for (k, v) in &set.headers {
            req = req.header(k.as_str(), v.as_str());
        }
        let mut resp = req.send(body.to_string()).map_err(|e| format!("AG-UI: could not reach {} ({e})", set.url))?;
        let status = resp.status().as_u16();
        if status != 200 {
            let text: String = resp.body_mut().read_to_string().unwrap_or_default().chars().take(300).collect();
            return Err(format!("AG-UI: HTTP {status} from {}: {text}", set.url));
        }

        // What this run produced, to go into the history and, for `screen` calls, back to the agent.
        let mut text = String::new();
        let mut text_id = String::new();
        let mut line = String::new();
        let mut prose: Vec<String> = Vec::new();
        let mut calls: Vec<(String, String, String)> = Vec::new(); // (id, name, arguments so far)
        let mut answered: Vec<String> = Vec::new(); // calls the agent's own side answered
        let mut results: Vec<(String, String)> = Vec::new(); // (call id, verdict) for `screen`
        let mut snapshot: Option<Value> = None;
        let mut finished = false;

        let end_line = |line: &mut String, prose: &mut Vec<String>, wrote_ops: &mut bool| {
            let l = std::mem::take(line);
            if l.trim().is_empty() {
                return;
            }
            // A line that is an op goes to the board now. Anything else is held: it is either a stray
            // sentence among ops (reported back as rejected) or an answer in prose (shown as a note).
            if crate::lang::parse(&l).is_ok() {
                *wrote_ops |= !crate::lang::is_blank(&l);
                send(Msg::Delta(format!("{l}\n")));
            } else {
                prose.push(l);
            }
        };

        let reader = BufReader::new(resp.into_body().into_reader());
        for raw in reader.lines() {
            let raw = raw.map_err(|e| format!("AG-UI: the stream broke ({e})"))?;
            if crate::tools::stopped() {
                return Err(crate::agent::STOPPED.into());
            }
            let Some(data) = raw.strip_prefix("data:") else { continue };
            let Ok(v) = serde_json::from_str::<Value>(data.trim()) else { continue };
            let s = |k: &str| v[k].as_str().unwrap_or("").to_string();
            match v["type"].as_str().unwrap_or("") {
                "TEXT_MESSAGE_START" => text_id = s("messageId"),
                "TEXT_MESSAGE_CONTENT" | "TEXT_MESSAGE_CHUNK" => {
                    if text_id.is_empty() {
                        text_id = s("messageId");
                    }
                    let delta = s("delta");
                    if !delta.is_empty() && stats.first_ms == 0 {
                        stats.first_ms = started.elapsed().as_millis() as u64;
                    }
                    text += &delta;
                    for c in delta.chars() {
                        if c == '\n' {
                            end_line(&mut line, &mut prose, &mut wrote_ops);
                        } else {
                            line.push(c);
                        }
                    }
                }
                "TEXT_MESSAGE_END" => end_line(&mut line, &mut prose, &mut wrote_ops),
                "TOOL_CALL_START" => {
                    let name = s("toolCallName");
                    if name != "screen" {
                        send(Msg::Activity(name.clone()));
                    }
                    calls.push((s("toolCallId"), name, String::new()));
                }
                "TOOL_CALL_ARGS" => {
                    if let Some(c) = calls.iter_mut().find(|c| c.0 == s("toolCallId")) {
                        c.2 += &s("delta");
                    }
                }
                "TOOL_CALL_CHUNK" => {
                    let id = s("toolCallId");
                    if !id.is_empty() && !calls.iter().any(|c| c.0 == id) {
                        calls.push((id.clone(), s("toolCallName"), String::new()));
                    }
                    let at = if id.is_empty() { calls.len().checked_sub(1) } else { calls.iter().position(|c| c.0 == id) };
                    if let Some(c) = at.and_then(|i| calls.get_mut(i)) {
                        c.2 += &s("delta");
                    }
                }
                "TOOL_CALL_END" => {
                    if let Some(c) = calls.iter().find(|c| c.0 == s("toolCallId") && c.1 == "screen") {
                        let ops = serde_json::from_str::<Value>(&c.2).ok().and_then(|a| a["ops"].as_str().map(str::to_string)).unwrap_or_default();
                        let (tx, rx) = std::sync::mpsc::channel();
                        send(Msg::Screen { ops, reply: tx });
                        wrote_ops = true;
                        results.push((c.0.clone(), rx.recv_timeout(Duration::from_secs(15)).unwrap_or_else(|_| "applied".into())));
                    }
                }
                "TOOL_CALL_RESULT" => {
                    answered.push(s("toolCallId"));
                    send(Msg::Activity(String::new()));
                }
                "CUSTOM" if v["name"] == "kite.ops" => {
                    let ops = match &v["value"] {
                        Value::String(t) => t.clone(),
                        Value::Array(a) => a.iter().filter_map(Value::as_str).collect::<Vec<_>>().join("\n"),
                        _ => String::new(),
                    };
                    if !ops.trim().is_empty() {
                        wrote_ops = true;
                        send(Msg::Delta(format!("{}\n", ops.trim_end())));
                    }
                }
                "STATE_SNAPSHOT" => state = if v["snapshot"].is_object() { v["snapshot"].clone() } else { json!({}) },
                "STATE_DELTA" => patch(&mut state, &v["delta"]),
                "MESSAGES_SNAPSHOT" => snapshot = Some(v["messages"].clone()),
                "STEP_STARTED" => send(Msg::Activity(s("stepName"))),
                "STEP_FINISHED" => send(Msg::Activity(String::new())),
                "RUN_ERROR" => {
                    outcome = Err(format!("AG-UI: {}", s("message")));
                    finished = true;
                }
                "RUN_FINISHED" => finished = true,
                _ => {}
            }
            if finished {
                break;
            }
        }
        end_line(&mut line, &mut prose, &mut wrote_ops);
        send(Msg::Activity(String::new()));
        all_prose.append(&mut prose);

        // The history: what the agent says it is, or what was seen in this run.
        match snapshot.and_then(|m| m.as_array().cloned()) {
            Some(list) => messages = list,
            None => {
                let open: Vec<Value> = calls.iter().filter(|c| !answered.contains(&c.0)).map(|c| json!({ "id": c.0, "type": "function", "function": { "name": c.1, "arguments": c.2 } })).collect();
                if !text.is_empty() || !open.is_empty() {
                    let mut m = json!({ "id": if text_id.is_empty() { new_id("assistant") } else { text_id.clone() }, "role": "assistant" });
                    if !text.is_empty() {
                        m["content"] = text.clone().into();
                    }
                    if !open.is_empty() {
                        m["toolCalls"] = open.into();
                    }
                    messages.push(m);
                }
            }
        }
        if outcome.is_err() || results.is_empty() {
            break;
        }
        // The verdicts of `screen` go back as tool messages, in a run of their own.
        for (id, verdict) in results {
            messages.push(json!({ "id": new_id("tool"), "role": "tool", "toolCallId": id, "content": verdict }));
        }
    }

    if !all_prose.is_empty() {
        if wrote_ops {
            // Sentences among ops: let them be rejected like any line that is not an op, so the agent is told.
            send(Msg::Delta(all_prose.join("\n") + "\n"));
        } else {
            let (tx, rx) = std::sync::mpsc::channel();
            send(Msg::Screen { ops: prose_as_ops(&all_prose), reply: tx });
            let _ = rx.recv_timeout(Duration::from_secs(15));
        }
    }
    // Keep the conversation, with this turn's board dropped from it.
    if let Some(m) = messages.get_mut(user_at).filter(|m| m["role"] == "user") {
        m["content"] = turn.remembered().into();
    }
    if let Some(obj) = state.as_object_mut() {
        obj.remove("kite");
    }
    if let Some(s) = SESSION.lock().unwrap_or_else(|e| e.into_inner()).as_mut() {
        s.messages = messages;
        s.state = state;
    }
    stats.total_ms = started.elapsed().as_millis() as u64;
    outcome.map(|()| stats)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{Read, Write};
    use std::sync::{Arc, Mutex};

    /// A scripted AG-UI server: answers the n-th request with the n-th list of events and keeps what it was sent.
    fn agent(rounds: Vec<Vec<Value>>) -> (String, Arc<Mutex<Vec<Value>>>) {
        let listener = std::net::TcpListener::bind(("127.0.0.1", 0)).unwrap();
        let url = format!("http://127.0.0.1:{}/agent", listener.local_addr().unwrap().port());
        let got = Arc::new(Mutex::new(Vec::new()));
        let seen = got.clone();
        std::thread::spawn(move || {
            for (n, conn) in listener.incoming().flatten().enumerate() {
                let mut conn = conn;
                let mut reader = BufReader::new(conn.try_clone().unwrap());
                let mut length = 0;
                loop {
                    let mut line = String::new();
                    if reader.read_line(&mut line).unwrap_or(0) == 0 || line.trim().is_empty() {
                        break;
                    }
                    if let Some(v) = line.to_ascii_lowercase().strip_prefix("content-length:") {
                        length = v.trim().parse().unwrap_or(0);
                    }
                }
                let mut body = vec![0u8; length];
                reader.read_exact(&mut body).unwrap();
                seen.lock().unwrap().push(serde_json::from_slice(&body).unwrap());
                let events: String = rounds.get(n).cloned().unwrap_or_default().iter().map(|e| format!("data: {e}\n\n")).collect();
                let _ = write!(conn, "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{events}", events.len());
            }
        });
        (url, got)
    }

    /// Collects what the engine sends to the screen, answering `screen` calls as the screen would.
    fn collect(verdict: &'static str) -> (Arc<Mutex<Vec<String>>>, impl Fn(Msg)) {
        let out = Arc::new(Mutex::new(Vec::new()));
        let o = out.clone();
        (out, move |m: Msg| match m {
            Msg::Delta(d) => o.lock().unwrap().push(format!("delta:{d}")),
            Msg::Screen { ops, reply } => {
                o.lock().unwrap().push(format!("screen:{ops}"));
                let _ = reply.send(verdict.to_string());
            }
            Msg::Activity(a) if !a.is_empty() => o.lock().unwrap().push(format!("activity:{a}")),
            _ => {}
        })
    }

    // The tests share one session, so each starts from an empty one and they do not run at the same time.
    static ONE_AT_A_TIME: Mutex<()> = Mutex::new(());

    #[test]
    fn text_tool_calls_and_custom_events_all_reach_the_board() {
        let _g = ONE_AT_A_TIME.lock().unwrap_or_else(|e| e.into_inner());
        reset();
        let (url, got) = agent(vec![
            vec![
                json!({ "type": "RUN_STARTED", "threadId": "t", "runId": "r1" }),
                json!({ "type": "TEXT_MESSAGE_START", "messageId": "m1", "role": "assistant" }),
                json!({ "type": "TEXT_MESSAGE_CONTENT", "messageId": "m1", "delta": "say Compar" }),
                json!({ "type": "TEXT_MESSAGE_CONTENT", "messageId": "m1", "delta": "ed.\ntopic ws Workshop\n" }),
                json!({ "type": "TEXT_MESSAGE_END", "messageId": "m1" }),
                json!({ "type": "STATE_SNAPSHOT", "snapshot": { "plan": { "step": 1 } } }),
                json!({ "type": "STATE_DELTA", "delta": [{ "op": "replace", "path": "/plan/step", "value": 2 }, { "op": "add", "path": "/done", "value": [] }, { "op": "add", "path": "/done/-", "value": "a" }] }),
                json!({ "type": "CUSTOM", "name": "kite.ops", "value": ["card k1 note ws One", "set k1 text hi"] }),
                json!({ "type": "TOOL_CALL_START", "toolCallId": "c1", "toolCallName": "search" }),
                json!({ "type": "TOOL_CALL_END", "toolCallId": "c1" }),
                json!({ "type": "TOOL_CALL_RESULT", "messageId": "m2", "toolCallId": "c1", "content": "found" }),
                json!({ "type": "TOOL_CALL_START", "toolCallId": "c2", "toolCallName": "screen" }),
                json!({ "type": "TOOL_CALL_ARGS", "toolCallId": "c2", "delta": "{\"ops\":\"card k2 dec" }),
                json!({ "type": "TOOL_CALL_ARGS", "toolCallId": "c2", "delta": "ision ws Pick\"}" }),
                json!({ "type": "TOOL_CALL_END", "toolCallId": "c2" }),
                json!({ "type": "RUN_FINISHED", "threadId": "t", "runId": "r1" }),
            ],
            vec![json!({ "type": "RUN_STARTED", "threadId": "t", "runId": "r2" }), json!({ "type": "RUN_FINISHED", "threadId": "t", "runId": "r2" })],
        ]);
        let (out, send) = collect("applied");
        let turn = Turn { board: "topic old Old\n".into(), events: vec!["user set k9 chosen o1".into()], rejected: vec![], input: "compare the venues".into() };
        let stats = run(&Settings { url, headers: vec![("X-Test".into(), "1".into())] }, &turn, &send).unwrap();
        assert!(stats.model.starts_with("AG-UI · 127.0.0.1"));
        assert_eq!(*out.lock().unwrap(), ["delta:say Compared.\n", "delta:topic ws Workshop\n", "delta:card k1 note ws One\nset k1 text hi\n", "activity:search", "screen:card k2 decision ws Pick"]);

        let got = got.lock().unwrap();
        assert_eq!(got.len(), 2, "a screen call is followed by a run that carries its verdict");
        // The first request: the turn as text and as state, the guide, the tool.
        let first = &got[0];
        let text = first["messages"][0]["content"].as_str().unwrap();
        assert!(text.contains("<board>\ntopic old Old\n</board>") && text.contains("<events>\nuser set k9 chosen o1\n</events>") && text.ends_with("<input>compare the venues</input>"), "{text}");
        assert_eq!(first["state"]["kite"]["board"], "topic old Old\n");
        assert_eq!(first["state"]["kite"]["events"][0], "user set k9 chosen o1");
        assert_eq!(first["tools"][0]["name"], "screen");
        assert!(first["context"][0]["value"].as_str().unwrap().contains("card <id> <kind> <topic>"));
        assert!(first["threadId"].as_str().unwrap().starts_with("kite-") && first["runId"] != got[1]["runId"]);
        // The second request: the agent's message with its open call, the verdict, the agent's state.
        let second = &got[1];
        let m = second["messages"].as_array().unwrap();
        assert_eq!(m[1]["role"], "assistant");
        assert_eq!(m[1]["content"], "say Compared.\ntopic ws Workshop\n");
        assert_eq!(m[1]["toolCalls"].as_array().unwrap().len(), 1, "the call the agent answered itself is not open");
        assert_eq!(m[1]["toolCalls"][0]["function"]["name"], "screen");
        assert_eq!((m[2]["role"].as_str(), m[2]["toolCallId"].as_str(), m[2]["content"].as_str()), (Some("tool"), Some("c2"), Some("applied")));
        assert_eq!(second["state"]["plan"]["step"], 2);
        assert_eq!(second["state"]["done"][0], "a");
        assert!(second["state"]["kite"]["events"].as_array().unwrap().is_empty(), "events are told once");
        assert_eq!(second["threadId"], first["threadId"]);
    }

    #[test]
    fn the_next_turn_carries_the_history_without_old_boards() {
        let _g = ONE_AT_A_TIME.lock().unwrap_or_else(|e| e.into_inner());
        reset();
        let done = |id: &str| vec![json!({ "type": "TEXT_MESSAGE_CHUNK", "messageId": id, "delta": "say ok\n" }), json!({ "type": "RUN_FINISHED" })];
        let (url, got) = agent(vec![done("a1"), done("a2")]);
        let set = Settings { url, headers: vec![] };
        let (_, send) = collect("applied");
        run(&set, &Turn { board: "topic a A\n".into(), input: "first".into(), ..Default::default() }, &send).unwrap();
        run(&set, &Turn { board: "topic b B\n".into(), input: "second".into(), ..Default::default() }, &send).unwrap();
        let got = got.lock().unwrap();
        let m = got[1]["messages"].as_array().unwrap();
        assert_eq!(m.len(), 3);
        assert_eq!(m[0]["content"], "<input>first</input>");
        assert_eq!((m[1]["role"].as_str(), m[1]["content"].as_str()), (Some("assistant"), Some("say ok\n")));
        assert!(m[2]["content"].as_str().unwrap().starts_with("<board>\ntopic b B\n"));
    }

    #[test]
    fn prose_is_shown_as_a_note_and_stray_sentences_are_rejected() {
        let _g = ONE_AT_A_TIME.lock().unwrap_or_else(|e| e.into_inner());
        reset();
        let says = |t: &str| vec![json!({ "type": "TEXT_MESSAGE_CONTENT", "messageId": "m", "delta": t }), json!({ "type": "RUN_FINISHED" })];
        // An agent that knows nothing of KITE answers in sentences.
        let (url, _) = agent(vec![says("Sure! Here is a plan.\nIt has | two steps.")]);
        let (out, send) = collect("applied");
        run(&Settings { url, headers: vec![] }, &Turn::default(), &send).unwrap();
        assert_eq!(*out.lock().unwrap(), ["screen:say Sure! Here is a plan. It has / two steps.\ntopic agent Agent\ncard agent-reply note agent 5x3 Reply\nset agent-reply text Sure! Here is a plan. It has / two steps."]);
        // A sentence among ops is passed on after them, to be rejected and reported like any bad line.
        reset();
        let (url, _) = agent(vec![says("Here you go:\nsay Done.\n")]);
        let (out, send) = collect("applied");
        run(&Settings { url, headers: vec![] }, &Turn::default(), &send).unwrap();
        assert_eq!(*out.lock().unwrap(), ["delta:say Done.\n", "delta:Here you go:\n"]);
    }

    #[test]
    fn errors_are_reported() {
        let _g = ONE_AT_A_TIME.lock().unwrap_or_else(|e| e.into_inner());
        reset();
        let (url, _) = agent(vec![vec![json!({ "type": "RUN_STARTED" }), json!({ "type": "RUN_ERROR", "message": "model overloaded" })]]);
        let (_, send) = collect("applied");
        assert_eq!(run(&Settings { url, headers: vec![] }, &Turn::default(), &send).unwrap_err(), "AG-UI: model overloaded");
        let err = run(&Settings { url: "http://127.0.0.1:9/none".into(), headers: vec![] }, &Turn::default(), &send).unwrap_err();
        assert!(err.starts_with("AG-UI: could not reach"), "{err}");
    }

    #[test]
    fn settings_file() {
        assert_eq!(parse_settings("# my agent\nhttp://localhost:8000/agent\nAuthorization: Bearer abc\n\nX-Team: a:b\n"), Some(Settings { url: "http://localhost:8000/agent".into(), headers: vec![("Authorization".into(), "Bearer abc".into()), ("X-Team".into(), "a:b".into())] }));
        assert_eq!(parse_settings("localhost:8000"), None);
        assert_eq!(parse_settings(""), None);
    }

    #[test]
    fn json_patch() {
        let mut s = json!({ "a": { "b": 1 }, "list": [1, 2, 3] });
        patch(&mut s, &json!([
            { "op": "replace", "path": "/a/b", "value": 2 },
            { "op": "add", "path": "/a/c~1d", "value": "x" },
            { "op": "remove", "path": "/list/0" },
            { "op": "add", "path": "/list/1", "value": 9 },
            { "op": "move", "from": "/a/b", "path": "/moved" },
            { "op": "copy", "from": "/list", "path": "/copy" },
            { "op": "remove", "path": "/nope/x" },
        ]));
        assert_eq!(s, json!({ "a": { "c/d": "x" }, "list": [2, 9, 3], "moved": 2, "copy": [2, 9, 3] }));
    }
}
