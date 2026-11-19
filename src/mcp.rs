//! APERIT as an MCP server (Streamable HTTP, JSON answers, 127.0.0.1 only). Two doors:
//!
//! - **Inside**: APERIT's own Claude Code engine runs in another process and cannot call APERIT's tools
//!   (routes, memory notes, reading the board, workers) directly, so they are offered to it here.
//!   The address and the token change on every start.
//! - **Outside**: any agent that speaks MCP (Claude Code, Cursor, an agent of your own) can use the running
//!   board as its screen: write it with `screen`, read it with `board_read`, and learn what the user did with
//!   `events`. This is the MCP binding of PATET (protocol/SPEC.md). Off unless a port is set.
//!
//! One request per connection. Tools run inside the APERIT process, so they reach the screen as they are.

use std::io::{BufRead, BufReader, Read, Write};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::Duration;

use serde_json::{Value, json};

use crate::llm::Msg;

/// Tools offered to APERIT's own Claude Code engine. File, command and web tools are Claude Code's own.
const INSIDE: [&str; 18] = [
    "route_find", "place_find", "note_search", "note_read", "note_write", "note_append", "board_read", "browser_text", "browser_eval", "job_start", "job_list", "job_tell", "job_stop",
    "job_result", "page_open", "page_read", "page_run", "page_show",
];

/// The guide an outside agent reads to learn the board language and how to compose a good board.
pub const GUIDE: &str = include_str!("../protocol/GUIDE.md");

/// Kinds that make the renderer load or open something the writer chose.
pub const MEDIA_KINDS: [&str; 3] = ["web", "image", "doc"];

/// May a writer outside APERIT create those? Off unless APERIT_OUTSIDE_MEDIA is set or `outside-media.txt`
/// next to the program says `yes` (the Settings window writes it).
pub fn outside_media() -> bool {
    crate::sys::var("OUTSIDE_MEDIA").ok().or_else(|| std::fs::read_to_string(crate::sys::base_dir().join("outside-media.txt")).ok()).map(|v| matches!(v.trim(), "1" | "yes" | "true")).unwrap_or(false)
}

/// This renderer's description of itself (protocol/SPEC.md section 6), as told to a writer outside APERIT.
pub fn description() -> Vec<String> {
    let kinds: Vec<&str> = crate::core::kind_names().into_iter().filter(|k| outside_media() || !MEDIA_KINDS.contains(k)).collect();
    vec![format!("patet {}", crate::lang::VERSION), format!("renderer APERIT {}", env!("CARGO_PKG_VERSION")), format!("kinds {}", kinds.join(" ")), "features tell".to_string()]
}

/// What an MCP client shows its model about this server (the `instructions` of `initialize`).
const INSTRUCTIONS: &str = "APERIT is a screen for an AI: a board of cards instead of a chat transcript. You do not answer the user in prose; you edit their board, and the board is the answer. \
Write the board with the `screen` tool, one op per line (say, topic, card, set, add, upd, del, ...). Read what is on it with `board_read` before you change it, and change existing cards instead of adding new ones. \
When you need a decision or a value from the user, put a `decision` or `input` card on the board and call `events` to wait for what they pick or type. \
Call `guide` once before your first `screen` call: it returns the op syntax, the card types and how to compose a board that reads at a glance.";

/// The stop signal of the turn in progress (so that the stop button reaches a slow tool).
static STOP: Mutex<Option<Arc<AtomicBool>>> = Mutex::new(None);

#[derive(Clone)]
struct Door {
    /// The exact `Authorization` header a caller must send.
    expect: String,
    outside: bool,
}

fn random_token() -> String {
    use std::hash::{BuildHasher, Hasher};
    (0..4).map(|_| format!("{:016x}", std::collections::hash_map::RandomState::new().build_hasher().finish())).collect()
}

fn listen(listener: std::net::TcpListener, door: Door) {
    std::thread::spawn(move || {
        for conn in listener.incoming().flatten() {
            let door = door.clone();
            std::thread::spawn(move || serve(conn, &door));
        }
    });
}

/// Start the inside door (once) and return the path of the config file to hand to Claude Code.
pub fn inside_config(stop: &Arc<AtomicBool>) -> Option<std::path::PathBuf> {
    let (port, token) = inside_door(stop)?;
    let path = crate::sys::data_dir().join("cc-mcp.json");
    std::fs::write(&path, client_config(port, &token).to_string()).ok()?;
    Some(path)
}

/// The client config as one line of JSON, for a client that reaches this door on `port` (a tunnel's end).
pub fn config_line(port: u16, token: &str) -> String {
    client_config(port, token).to_string()
}

/// The inside door: (port on 127.0.0.1, token). Opened on first use.
pub fn inside_door(stop: &Arc<AtomicBool>) -> Option<(u16, String)> {
    *STOP.lock().unwrap_or_else(|e| e.into_inner()) = Some(stop.clone());
    static SERVER: OnceLock<Option<(u16, String)>> = OnceLock::new();
    let (port, token) = SERVER
        .get_or_init(|| {
            let listener = std::net::TcpListener::bind(("127.0.0.1", 0)).ok()?;
            let port = listener.local_addr().ok()?.port();
            // The token keeps other programs on this computer from calling the tools even if they find the address.
            let token = random_token();
            listen(listener, Door { expect: format!("Bearer {token}"), outside: false });
            Some((port, token))
        })
        .clone()?;
    Some((port, token))
}

fn client_config(port: u16, token: &str) -> Value {
    json!({ "mcpServers": { "aperit": { "type": "http", "url": format!("http://127.0.0.1:{port}/mcp"), "headers": { "Authorization": format!("Bearer {token}") } } } })
}

/// The port of the outside door when the outside-agent engine is chosen and no port is set.
pub const DEFAULT_PORT: u16 = 7711;

/// The client config as text, to paste into an agent's MCP settings.
pub fn client_config_text(port: u16, token: &str) -> String {
    serde_json::to_string_pretty(&client_config(port, token)).unwrap_or_default()
}

/// Where the outside door is listening, once it is: (port, token).
static OUTSIDE: OnceLock<(u16, String)> = OnceLock::new();

/// The port asked for the outside door: APERIT_MCP_PORT, or the number in `mcp-port.txt` next to the program.
pub fn outside_port_setting() -> Option<u16> {
    let text = crate::sys::var("MCP_PORT").ok().or_else(|| std::fs::read_to_string(crate::sys::base_dir().join("mcp-port.txt")).ok())?;
    text.trim().parse::<u16>().ok().filter(|p| *p >= 1024)
}

/// Open the outside door on 127.0.0.1:`port` (once). The token is APERIT_MCP_TOKEN, or one made on first use
/// and kept in data/mcp-token.txt. A ready-to-paste client config is written to data/mcp.json.
pub fn start_outside(port: u16) -> Result<(u16, String), String> {
    if let Some(open) = OUTSIDE.get() {
        return Ok(open.clone());
    }
    let listener = std::net::TcpListener::bind(("127.0.0.1", port)).map_err(|e| format!("cannot listen on 127.0.0.1:{port}: {e}"))?;
    let token_file = crate::sys::data_dir().join("mcp-token.txt");
    let token = crate::sys::var("MCP_TOKEN").ok().map(|t| t.trim().to_string()).filter(|t| !t.is_empty()).or_else(|| std::fs::read_to_string(&token_file).ok().map(|t| t.trim().to_string()).filter(|t| t.len() >= 16)).unwrap_or_else(|| {
        let t = random_token();
        let _ = std::fs::write(&token_file, &t);
        t
    });
    listen(listener, Door { expect: format!("Bearer {token}"), outside: true });
    let _ = std::fs::write(crate::sys::data_dir().join("mcp.json"), serde_json::to_string_pretty(&client_config(port, &token)).unwrap_or_default());
    crate::sys::log("mcp.log", &format!("listening on http://127.0.0.1:{port}/mcp (config for clients: data/mcp.json)"));
    let _ = OUTSIDE.set((port, token.clone()));
    Ok((port, token))
}

/// The outside door, if it is open: (port, token).
pub fn outside() -> Option<(u16, String)> {
    OUTSIDE.get().cloned()
}

/// The command that connects Claude Code to the outside door.
pub fn claude_code_command(port: u16, token: &str) -> String {
    format!("claude mcp add --transport http aperit http://127.0.0.1:{port}/mcp --header \"Authorization: Bearer {token}\"")
}

/// Tool definitions for an outside agent.
fn outside_tools() -> Value {
    json!([
        {
            "name": "screen",
            "description": "Edit the user's APERIT board. `ops` holds board ops, one per line, applied in order; a line that fails is skipped and the rest still run. Returns `applied`, or the rejected lines with a code and a reason each. This is how you answer the user: there is no chat. Call `guide` first for the syntax and the card types.",
            "inputSchema": { "type": "object", "properties": { "ops": { "type": "string", "description": "Ops, one per line. Example:\nsay Two venues compared. Pick one.\ntopic ws Team Workshop\ncard k1 decision ws 3x3 Which venue?\nadd k1 o1 Gangneung | Far, but better facilities\nadd k1 o2 Gapyeong | Close and cheaper" } }, "required": ["ops"] }
        },
        {
            "name": "board_read",
            "description": "Read the board as ops (the same language `screen` takes). Without `topic` the whole board; with it, that topic in full. Read before you edit, and edit what is there instead of creating it again.",
            "inputSchema": { "type": "object", "properties": { "topic": { "type": "string", "description": "A topic id. Leave out for the whole board." } } }
        },
        {
            "name": "events",
            "description": "What the user did since your last call: `input <text>` for something they typed or said, and `user <op>` for a gesture on the board (picking an option of a decision card is `user set <card> chosen <item>`, sending an input card is `user upd ...` lines and then `user set <card> sent yes`, ticking a checklist item is `user upd <card> <item> done | ...`). Waits up to `wait` seconds when nothing happened yet. Call it after you put a question on the board, and in a loop to serve the user.",
            "inputSchema": { "type": "object", "properties": {
                "wait": { "type": "number", "description": "Seconds to wait for something to happen (0 to 50). Default 25." },
                "after": { "type": "integer", "description": "Return events numbered above this (the `cursor` of an earlier result). Leave out to continue from your last call." }
            } }
        },
        {
            "name": "guide",
            "description": "The PATET guide: every op, every card type with its fields and limits, and how to compose a board that reads at a glance. Read it once before your first `screen` call.",
            "inputSchema": { "type": "object", "properties": {} }
        }
    ])
}

/// Where an outside agent that passes no `after` continues from. Starts at 0, so what the user asked for
/// before the agent connected is still delivered.
static SEEN: AtomicU64 = AtomicU64::new(0);

fn outside_call(name: &str, input: &Value) -> (String, bool) {
    let ask = |msg: Msg, rx: std::sync::mpsc::Receiver<String>| -> (String, bool) {
        if !crate::jobs::to_ui(msg) {
            return ("error: the screen is not available".into(), true);
        }
        match rx.recv_timeout(Duration::from_secs(15)) {
            Ok(out) => (out, false),
            Err(_) => ("error: the screen did not answer".into(), true),
        }
    };
    match name {
        "screen" => {
            let (tx, rx) = std::sync::mpsc::channel();
            ask(Msg::Write { peer: "mcp".into(), ops: input["ops"].as_str().unwrap_or("").to_string(), reply: tx }, rx)
        }
        "board_read" => {
            let (tx, rx) = std::sync::mpsc::channel();
            if !crate::jobs::to_ui(Msg::Board { topic: input["topic"].as_str().unwrap_or("").trim().to_string(), reply: tx }) {
                return ("error: the screen is not available".into(), true);
            }
            match rx.recv_timeout(Duration::from_secs(15)) {
                Ok(Ok(text)) if text.is_empty() => ("(the board is empty)".into(), false),
                Ok(Ok(text)) => (text, false),
                Ok(Err(e)) => (format!("error: {e}"), true),
                Err(_) => ("error: the screen did not answer".into(), true),
            }
        }
        "events" => {
            let wait = input["wait"].as_f64().unwrap_or(25.0).clamp(0.0, 50.0);
            let after = input["after"].as_u64().unwrap_or_else(|| SEEN.load(Ordering::SeqCst));
            // An agent is told what the user did, not what agents (itself included) wrote.
            let (events, next) = crate::hub::hub().wait(after, Duration::from_secs_f64(wait), &|e| e.kind != "agent");
            SEEN.fetch_max(next, Ordering::SeqCst);
            let mut text: String = events.iter().map(|e| e.line() + "\n").collect();
            if events.is_empty() {
                text += "(nothing yet)\n";
            }
            (format!("{text}cursor {next}"), false)
        }
        // The guide carries the card type table itself; what follows it is what this renderer supports.
        "guide" => (format!("{GUIDE}\n## This renderer\n\n```\n{}\n```\n", description().join("\n")), false),
        other => (format!("error: no tool named {other}"), true),
    }
}

/// Take one request and answer it (one request per connection).
fn serve(mut conn: std::net::TcpStream, door: &Door) {
    let _ = conn.set_read_timeout(Some(Duration::from_secs(20)));
    let mut reader = BufReader::new(match conn.try_clone() {
        Ok(c) => c,
        Err(_) => return,
    });
    let mut first = String::new();
    if reader.read_line(&mut first).is_err() {
        return;
    }
    let (mut length, mut authorized, mut from_page) = (0usize, false, false);
    loop {
        let mut line = String::new();
        if reader.read_line(&mut line).unwrap_or(0) == 0 || line.trim().is_empty() {
            break;
        }
        if let Some((k, v)) = line.split_once(':') {
            match k.trim().to_ascii_lowercase().as_str() {
                "content-length" => length = v.trim().parse().unwrap_or(0),
                "authorization" => authorized = v.trim() == door.expect,
                // A browser adds Origin to what a web page sends. No MCP client on this computer does.
                "origin" => from_page = true,
                _ => {}
            }
        }
    }
    let mut reply = |status: &str, body: &str| {
        let _ = write!(conn, "HTTP/1.1 {status}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}", body.len());
        let _ = conn.flush();
    };
    // Read the whole request before answering. Closing before that shows the caller a dropped connection, not a refusal.
    let mut body = vec![0u8; length.min(4_000_000)];
    if reader.read_exact(&mut body).is_err() {
        return reply("400 Bad Request", "{}");
    }
    if !authorized {
        return reply("401 Unauthorized", "{}");
    }
    if from_page {
        return reply("403 Forbidden", "{}");
    }
    if !first.starts_with("POST") {
        return reply("405 Method Not Allowed", "{}");
    }
    let Ok(req) = serde_json::from_slice::<Value>(&body) else { return reply("400 Bad Request", "{}") };
    // A notification (no id) is only acknowledged.
    if req.get("id").is_none() {
        return reply("202 Accepted", "");
    }
    let result = match req["method"].as_str().unwrap_or("") {
        "initialize" => {
            let mut v = json!({
                "protocolVersion": req["params"]["protocolVersion"].as_str().unwrap_or("2025-06-18"),
                "capabilities": { "tools": {} },
                "serverInfo": { "name": "aperit", "version": env!("CARGO_PKG_VERSION") },
            });
            if door.outside {
                v["instructions"] = INSTRUCTIONS.into();
            }
            v
        }
        "ping" => json!({}),
        "tools/list" if door.outside => json!({ "tools": outside_tools() }),
        "tools/list" => {
            let all = crate::tools::definitions(false);
            let tools: Vec<Value> = all
                .as_array()
                .into_iter()
                .flatten()
                .filter(|t| INSIDE.contains(&t["name"].as_str().unwrap_or("")))
                .map(|t| json!({ "name": t["name"], "description": t["description"], "inputSchema": t["input_schema"] }))
                .collect();
            json!({ "tools": tools })
        }
        "tools/call" => {
            let name = req["params"]["name"].as_str().unwrap_or("");
            let input = &req["params"]["arguments"];
            let (text, is_error) = if door.outside {
                outside_call(name, input)
            } else if INSIDE.contains(&name) {
                inside_call(name, input)
            } else {
                (format!("error: no tool named {name}"), true)
            };
            json!({ "content": [{ "type": "text", "text": text }], "isError": is_error })
        }
        other => {
            let e = json!({ "jsonrpc": "2.0", "id": req["id"], "error": { "code": -32601, "message": format!("unknown method {other}") } });
            return reply("200 OK", &e.to_string());
        }
    };
    reply("200 OK", &json!({ "jsonrpc": "2.0", "id": req["id"], "result": result }).to_string());
}

/// Run one of APERIT's own tools. What lives on the screen side (the board, browser cards) is asked of the screen thread.
fn inside_call(name: &str, input: &Value) -> (String, bool) {
    if let Some(stop) = STOP.lock().unwrap_or_else(|e| e.into_inner()).clone() {
        crate::tools::set_stop(stop);
    }
    crate::jobs::to_ui(Msg::Activity(crate::tools::describe(name, input)));
    match name {
        "board_read" => {
            let (tx, rx) = std::sync::mpsc::channel();
            if !crate::jobs::to_ui(Msg::Board { topic: input["topic"].as_str().unwrap_or("").trim().to_string(), reply: tx }) {
                return ("error: the screen is not available".into(), true);
            }
            match rx.recv_timeout(Duration::from_secs(15)) {
                Ok(Ok(text)) => (text, false),
                Ok(Err(e)) => (format!("error: {e}"), true),
                Err(_) => ("error: the screen did not answer".into(), true),
            }
        }
        "browser_text" | "browser_eval" => {
            let script = if name == "browser_text" {
                "document.title + '\\n' + location.href + '\\n\\n' + (document.body ? document.body.innerText : '').slice(0, 30000)".to_string()
            } else {
                input["script"].as_str().unwrap_or("").to_string()
            };
            let card = input["card"].as_str().unwrap_or("").to_string();
            let (tx, rx) = std::sync::mpsc::channel();
            if !crate::jobs::to_ui(Msg::Browser { card, script, reply: tx }) {
                return ("error: the screen is not available".into(), true);
            }
            match rx.recv_timeout(Duration::from_secs(20)) {
                Ok(out) => (out.clone(), out.starts_with("오류:")),
                Err(_) => ("error: the page did not answer within 20 seconds".into(), true),
            }
        }
        n if n.starts_with("page_") => crate::page::call(name, input),
        _ => crate::tools::execute(name, input),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn post(port: u16, headers: &str, body: &str) -> (String, Value) {
        let mut c = std::net::TcpStream::connect(("127.0.0.1", port)).unwrap();
        write!(c, "POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\n{headers}Content-Length: {}\r\n\r\n{body}", body.len()).unwrap();
        let mut text = String::new();
        c.read_to_string(&mut text).unwrap();
        let (head, body) = text.split_once("\r\n\r\n").unwrap_or((&text, ""));
        (head.lines().next().unwrap_or("").to_string(), serde_json::from_str(body).unwrap_or(Value::Null))
    }

    fn door(outside: bool) -> u16 {
        let listener = std::net::TcpListener::bind(("127.0.0.1", 0)).unwrap();
        let port = listener.local_addr().unwrap().port();
        listen(listener, Door { expect: "Bearer t0k".into(), outside });
        port
    }

    #[test]
    fn outside_door_lists_its_tools_and_explains_itself() {
        let port = door(true);
        let auth = "Authorization: Bearer t0k\r\n";
        let (status, v) = post(port, auth, r#"{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}"#);
        assert!(status.contains("200"), "{status}");
        assert_eq!(v["result"]["serverInfo"]["name"], "aperit");
        assert!(v["result"]["instructions"].as_str().unwrap().contains("screen"));
        let (_, v) = post(port, auth, r#"{"jsonrpc":"2.0","id":2,"method":"tools/list"}"#);
        let names: Vec<&str> = v["result"]["tools"].as_array().unwrap().iter().map(|t| t["name"].as_str().unwrap()).collect();
        assert_eq!(names, ["screen", "board_read", "events", "guide"]);
        // The guide comes with the card type table.
        let (_, v) = post(port, auth, r#"{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"guide","arguments":{}}}"#);
        let text = v["result"]["content"][0]["text"].as_str().unwrap();
        assert!(text.contains("| decision |") && text.contains("card <id> <kind> <topic>"));
        assert_eq!(v["result"]["isError"], false);
    }

    #[test]
    fn a_token_is_required_and_web_pages_are_refused() {
        let port = door(true);
        let call = r#"{"jsonrpc":"2.0","id":1,"method":"tools/list"}"#;
        assert!(post(port, "", call).0.contains("401"));
        assert!(post(port, "Authorization: Bearer wrong\r\n", call).0.contains("401"));
        assert!(post(port, "Authorization: Bearer t0k\r\nOrigin: https://evil.example\r\n", call).0.contains("403"));
        assert!(post(port, "Authorization: Bearer t0k\r\n", call).0.contains("200"));
    }

    #[test]
    fn events_tool_returns_what_the_user_did_and_a_cursor() {
        let hub = crate::hub::hub();
        let start = hub.cursor();
        hub.publish("agent", "mcp", "say mine");
        hub.publish("user", "user", "set k2 chosen o2");
        hub.publish("input", "user", "the cheaper one");
        let (text, is_error) = outside_call("events", &json!({ "wait": 0, "after": start }));
        assert!(!is_error);
        let lines: Vec<&str> = text.lines().collect();
        assert_eq!(&lines[..2], ["user set k2 chosen o2", "input the cheaper one"]);
        let cursor: u64 = lines[2].strip_prefix("cursor ").unwrap().parse().unwrap();
        // Nothing after that cursor.
        let (text, _) = outside_call("events", &json!({ "wait": 0, "after": cursor }));
        assert!(text.starts_with("(nothing yet)\ncursor "), "{text}");
    }

    #[test]
    fn inside_door_keeps_its_own_tool_list() {
        let port = door(false);
        let (_, v) = post(port, "Authorization: Bearer t0k\r\n", r#"{"jsonrpc":"2.0","id":1,"method":"tools/list"}"#);
        let names: Vec<&str> = v["result"]["tools"].as_array().unwrap().iter().map(|t| t["name"].as_str().unwrap()).collect();
        assert!(names.contains(&"board_read") && names.contains(&"route_find") && !names.contains(&"screen"));
        let (_, v) = post(port, "Authorization: Bearer t0k\r\n", r#"{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}"#);
        assert!(v["result"]["instructions"].is_null());
    }
}
