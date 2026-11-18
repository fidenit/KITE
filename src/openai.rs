//! APERIT's own loop with a model behind an OpenAI-compatible API (`POST <base>/chat/completions`):
//! OpenAI itself, a local model served by Ollama or LM Studio, OpenRouter, and others that speak this format.
//!
//! The loop is the same as with Claude (`agent.rs`): stream the model's text to the board, run the tools it
//! asks for, send the results back, until it is done. Only the wire format differs. Tools that exist only
//! on Anthropic's side (web search, web fetch) are not offered here.

use std::io::{BufRead, BufReader};
use std::time::Instant;

use serde_json::{Value, json};

use crate::agent::{Role, ToolOut, use_tool};
use crate::llm::{Msg, Stats};

/// Give this where a key is expected and the question goes to the provider (see `agent::ask`).
pub const VIA: &str = "\u{1}openai";

#[derive(Clone, Debug, PartialEq)]
pub struct Provider {
    /// The base address, up to and including the version: `https://api.openai.com/v1`, `http://localhost:11434/v1`.
    pub url: String,
    pub model: String,
    /// Sent as a Bearer token. A local server usually needs none.
    pub key: Option<String>,
}

/// APERIT_OPENAI_URL and APERIT_OPENAI_MODEL (the key from OPENAI_API_KEY), or `openai.txt` next to the
/// program: the base address, the model, and optionally the key, one per line.
pub fn settings() -> Option<Provider> {
    let env = |k: &str| crate::sys::var(k).ok().map(|v| v.trim().to_string()).filter(|v| !v.is_empty());
    if let (Some(url), Some(model)) = (env("OPENAI_URL"), env("OPENAI_MODEL")) {
        let key = std::env::var("OPENAI_API_KEY").ok().map(|k| k.trim().to_string()).filter(|k| !k.is_empty());
        return Some(Provider { url: url.trim_end_matches('/').to_string(), model, key });
    }
    parse_settings(&std::fs::read_to_string(crate::sys::base_dir().join("openai.txt")).ok()?)
}

fn parse_settings(text: &str) -> Option<Provider> {
    let mut lines = text.lines().map(str::trim).filter(|l| !l.is_empty() && !l.starts_with('#'));
    let url = lines.next()?.trim_end_matches('/').to_string();
    let model = lines.next()?.to_string();
    if !url.starts_with("http://") && !url.starts_with("https://") {
        return None;
    }
    Some(Provider { url, model, key: lines.next().map(str::to_string) })
}

/// A service APERIT knows from the look of its key, so that pasting the key is all there is to do.
pub struct Known {
    pub name: &'static str,
    /// Keys of this service start like this.
    prefix: &'static str,
    /// Empty for Claude, which APERIT calls in its own format.
    pub url: &'static str,
    /// Which of the service's models to use: the newest whose name contains the first of these that any has.
    prefer: &'static [&'static str],
    /// The model when the list could not be read.
    fallback: &'static str,
}

/// Longer prefixes first: an OpenRouter key also starts with `sk-`.
const KNOWN: [Known; 6] = [
    Known { name: "Claude", prefix: "sk-ant-", url: "", prefer: &[], fallback: "" },
    Known { name: "OpenRouter", prefix: "sk-or-", url: "https://openrouter.ai/api/v1", prefer: &[], fallback: "openrouter/auto" },
    Known { name: "Groq", prefix: "gsk_", url: "https://api.groq.com/openai/v1", prefer: &["versatile", "llama"], fallback: "llama-3.3-70b-versatile" },
    Known { name: "xAI", prefix: "xai-", url: "https://api.x.ai/v1", prefer: &["grok"], fallback: "grok-4" },
    Known { name: "Gemini", prefix: "AIza", url: "https://generativelanguage.googleapis.com/v1beta/openai", prefer: &["pro", "flash"], fallback: "gemini-2.5-flash" },
    Known { name: "OpenAI", prefix: "sk-", url: "https://api.openai.com/v1", prefer: &["gpt-"], fallback: "gpt-5" },
];

/// Whose key is this?
pub fn recognize(key: &str) -> Option<&'static Known> {
    let key = key.trim();
    KNOWN.iter().find(|k| key.starts_with(k.prefix) && key.len() > k.prefix.len() + 8)
}

/// The service behind this address, if it is one APERIT knows.
pub fn known_by_url(url: &str) -> Option<&'static Known> {
    KNOWN.iter().find(|k| !k.url.is_empty() && k.url == url.trim_end_matches('/'))
}

impl Known {
    pub fn is_claude(&self) -> bool {
        self.url.is_empty()
    }

    /// The model to use with this key: read the service's list and take the newest general one.
    pub fn model(&self, key: &str) -> String {
        if self.prefer.is_empty() {
            return self.fallback.to_string();
        }
        let listed = || -> Option<Vec<String>> {
            let mut resp = crate::agent::http().get(format!("{}/models", self.url)).config().timeout_global(Some(std::time::Duration::from_secs(6))).build().header("authorization", format!("Bearer {}", key.trim())).call().ok()?;
            let v: Value = serde_json::from_str(&resp.body_mut().read_to_string().ok()?).ok()?;
            Some(v["data"].as_array()?.iter().filter_map(|m| m["id"].as_str().map(str::to_string)).collect())
        };
        listed().and_then(|ids| self.choose(&ids)).unwrap_or_else(|| self.fallback.to_string())
    }

    /// From a list of model names: no small, dated or special-purpose ones, then the highest version.
    fn choose(&self, ids: &[String]) -> Option<String> {
        const AVOID: [&str; 22] = ["mini", "nano", "lite", "audio", "realtime", "image", "tts", "transcribe", "whisper", "search", "embed", "preview", "instruct", "vision", "guard", "codex", "latest", "exp", "moderation", "8b", "instant", "learnlm"];
        let numbers = |id: &str| -> Vec<u32> { id.split(|c: char| !c.is_ascii_digit()).filter(|p| !p.is_empty()).filter_map(|p| p.parse().ok()).collect() };
        // Whole words of the name: "gemini" is not "mini".
        let general = |id: &&String| !id.split(|c: char| !c.is_ascii_alphanumeric()).any(|word| AVOID.iter().any(|a| word.starts_with(a))) && !numbers(id).iter().any(|n| *n >= 1000);
        self.prefer.iter().find_map(|want| ids.iter().filter(|id| id.contains(want)).filter(general).max_by_key(|id| (numbers(id), std::cmp::Reverse(id.len()))).cloned())
    }
}

fn post(p: &Provider, body: &Value) -> Result<ureq::http::Response<ureq::Body>, String> {
    let mut req = crate::agent::http().post(format!("{}/chat/completions", p.url)).header("content-type", "application/json");
    if let Some(k) = &p.key {
        req = req.header("authorization", format!("Bearer {k}"));
    }
    req.send(body.to_string()).map_err(|e| format!("could not reach {} ({e})", p.url))
}

fn api_error(status: u16, text: &str) -> String {
    let v = serde_json::from_str::<Value>(text).ok();
    let msg = v.as_ref().and_then(|v| v["error"]["message"].as_str().or(v["error"].as_str()).map(str::to_string)).unwrap_or_else(|| text.chars().take(300).collect());
    format!("HTTP {status}: {msg}")
}

/// One question, one answer, no tools (memory keeping and other small work behind the scenes).
pub fn ask(p: &Provider, system: &str, user: &str, max_tokens: u32) -> Result<String, String> {
    let body = json!({ "model": p.model, "max_tokens": max_tokens, "messages": [{ "role": "system", "content": system }, { "role": "user", "content": user }] });
    let mut resp = post(p, &body)?;
    let status = resp.status().as_u16();
    let text = resp.body_mut().read_to_string().unwrap_or_default();
    if status != 200 {
        return Err(api_error(status, &text));
    }
    let v: Value = serde_json::from_str(&text).map_err(|e| e.to_string())?;
    Ok(v["choices"][0]["message"]["content"].as_str().unwrap_or("").to_string())
}

/// APERIT's tools in this format. Tools that are a service of Anthropic's API have no schema of ours and are left out.
fn functions(tools: &Value) -> Vec<Value> {
    tools.as_array().into_iter().flatten().filter(|t| t["input_schema"].is_object()).map(|t| json!({ "type": "function", "function": { "name": t["name"], "description": t["description"], "parameters": t["input_schema"] } })).collect()
}

/// The main turn. `added` hands over what the user said while the model was working (see `agent::run`).
pub fn run(p: &Provider, turn_text: &str, images: &[(String, String)], added: &dyn Fn() -> Result<Option<String>, String>, send: &dyn Fn(Msg)) -> Result<Stats, String> {
    let role = Role { system: crate::agent::system(), tools: crate::tools::definitions(false), max_steps: 80, check: &|| added(), tool: &|_, _| None };
    run_as(p, &role, turn_text, images, send).map(|(stats, _)| stats)
}

/// Run until the model is done. Returns the text of its last answer too (a worker's report).
pub fn run_as(p: &Provider, role: &Role, turn_text: &str, images: &[(String, String)], send: &dyn Fn(Msg)) -> Result<(Stats, String), String> {
    let started = Instant::now();
    let mut stats = Stats { model: p.model.clone(), ..Default::default() };
    let picture = |media: &str, data: &str| json!({ "type": "image_url", "image_url": { "url": format!("data:{media};base64,{data}") } });
    let first: Value = if images.is_empty() {
        turn_text.into()
    } else {
        let mut parts: Vec<Value> = images.iter().map(|(media, data)| picture(media, data)).collect();
        parts.push(json!({ "type": "text", "text": turn_text }));
        parts.into()
    };
    let mut messages = vec![json!({ "role": "system", "content": role.system }), json!({ "role": "user", "content": first })];
    let tools = functions(&role.tools);
    // Not every server knows `stream_options` (it asks for token counts at the end). Dropped on the first refusal.
    let mut with_usage = true;

    for _ in 0..role.max_steps {
        let mut body = json!({ "model": p.model, "messages": messages, "stream": true });
        if !tools.is_empty() {
            body["tools"] = tools.clone().into();
        }
        if with_usage {
            body["stream_options"] = json!({ "include_usage": true });
        }
        let mut resp = post(p, &body)?;
        if resp.status().as_u16() == 400 && with_usage {
            with_usage = false;
            body.as_object_mut().map(|o| o.remove("stream_options"));
            resp = post(p, &body)?;
        }
        let status = resp.status().as_u16();
        if status != 200 {
            let text = resp.body_mut().read_to_string().unwrap_or_default();
            return Err(api_error(status, &text));
        }

        let mut text = String::new();
        let mut calls: Vec<(String, String, String)> = Vec::new(); // (id, name, arguments so far), by index
        let mut finish = String::new();
        for line in BufReader::new(resp.into_body().into_reader()).lines() {
            let line = line.map_err(|e| format!("the stream broke: {e}"))?;
            if crate::tools::stopped() {
                return Err(crate::agent::STOPPED.into());
            }
            let Some(data) = line.strip_prefix("data:").map(str::trim) else { continue };
            if data == "[DONE]" {
                break;
            }
            let Ok(v) = serde_json::from_str::<Value>(data) else { continue };
            if let Some(e) = v["error"]["message"].as_str() {
                return Err(format!("API error: {e}"));
            }
            if v["usage"].is_object() {
                let u = &v["usage"];
                stats.cached_tokens += u["prompt_tokens_details"]["cached_tokens"].as_u64().unwrap_or(0);
                stats.input_tokens += u["prompt_tokens"].as_u64().unwrap_or(0);
                stats.output_tokens += u["completion_tokens"].as_u64().unwrap_or(0);
            }
            if let Some(m) = v["model"].as_str().filter(|m| !m.is_empty()) {
                stats.model = m.to_string();
            }
            let choice = &v["choices"][0];
            if let Some(t) = choice["delta"]["content"].as_str().filter(|t| !t.is_empty()) {
                if stats.first_ms == 0 {
                    stats.first_ms = started.elapsed().as_millis() as u64;
                }
                text += t;
                send(Msg::Delta(t.to_string()));
            }
            for c in choice["delta"]["tool_calls"].as_array().into_iter().flatten() {
                let at = c["index"].as_u64().unwrap_or(calls.len().saturating_sub(1) as u64) as usize;
                if calls.len() <= at {
                    calls.resize(at + 1, Default::default());
                }
                if let Some(id) = c["id"].as_str().filter(|s| !s.is_empty()) {
                    calls[at].0 = id.to_string();
                }
                if let Some(name) = c["function"]["name"].as_str() {
                    calls[at].1 += name;
                }
                if let Some(args) = c["function"]["arguments"].as_str() {
                    calls[at].2 += args;
                }
            }
            if let Some(f) = choice["finish_reason"].as_str() {
                finish = f.to_string();
            }
        }
        send(Msg::Delta("\n".into())); // answers must not run into each other
        calls.retain(|c| !c.1.is_empty());
        if calls.is_empty() {
            return match finish.as_str() {
                "length" => Err("the answer was cut off at the output limit".into()),
                "content_filter" => Err("the model declined this request".into()),
                _ => {
                    stats.total_ms = started.elapsed().as_millis() as u64;
                    Ok((stats, text))
                }
            };
        }
        // Some servers give no call id; the tool messages need one to answer to.
        for (n, c) in calls.iter_mut().enumerate() {
            if c.0.is_empty() {
                c.0 = format!("call_{n}");
            }
        }
        let asked: Vec<Value> = calls.iter().map(|c| json!({ "id": c.0, "type": "function", "function": { "name": c.1, "arguments": if c.2.trim().is_empty() { "{}" } else { c.2.as_str() } } })).collect();
        messages.push(json!({ "role": "assistant", "content": if text.is_empty() { Value::Null } else { text.clone().into() }, "tool_calls": asked }));
        let mut pictures: Vec<Value> = Vec::new();
        for (id, name, args) in &calls {
            let input = if args.trim().is_empty() { json!({}) } else { serde_json::from_str(args).unwrap_or_else(|_| json!({ "__invalid_json": args })) };
            let out = match use_tool(role, name, &input, send) {
                ToolOut::Text(out, _) => out,
                // This format cannot return a picture from a tool: say so, and show it in a message that follows.
                ToolOut::Picture(pic) => {
                    let note = format!("the picture ({}x{} pixels) follows in the next message", pic.w, pic.h);
                    pictures.push(picture(pic.media, &pic.b64()));
                    note
                }
            };
            messages.push(json!({ "role": "tool", "tool_call_id": id, "content": out }));
        }
        // A stop signal, or something the user added meanwhile.
        let more = (role.check)()?;
        if !pictures.is_empty() || more.is_some() {
            if let Some(m) = more {
                pictures.push(json!({ "type": "text", "text": m }));
            }
            messages.push(json!({ "role": "user", "content": pictures }));
        }
        send(Msg::Activity(String::new()));
    }
    Err(format!("stopped after {} steps in one turn", role.max_steps))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{Read, Write};
    use std::sync::{Arc, Mutex};

    /// A scripted server: answers the n-th request with the n-th list of chunks (as SSE) and keeps what it was sent.
    fn server(rounds: Vec<(u16, Vec<Value>)>) -> (Provider, Arc<Mutex<Vec<(String, Value)>>>) {
        let listener = std::net::TcpListener::bind(("127.0.0.1", 0)).unwrap();
        let p = Provider { url: format!("http://127.0.0.1:{}/v1", listener.local_addr().unwrap().port()), model: "test-model".into(), key: Some("sk-test".into()) };
        let got = Arc::new(Mutex::new(Vec::new()));
        let seen = got.clone();
        std::thread::spawn(move || {
            for (n, conn) in listener.incoming().flatten().enumerate() {
                let mut conn = conn;
                let mut reader = BufReader::new(conn.try_clone().unwrap());
                let (mut length, mut head) = (0, String::new());
                loop {
                    let mut line = String::new();
                    if reader.read_line(&mut line).unwrap_or(0) == 0 || line.trim().is_empty() {
                        break;
                    }
                    if let Some(v) = line.to_ascii_lowercase().strip_prefix("content-length:") {
                        length = v.trim().parse().unwrap_or(0);
                    }
                    head += &line;
                }
                let mut body = vec![0u8; length];
                reader.read_exact(&mut body).unwrap();
                seen.lock().unwrap().push((head, serde_json::from_slice(&body).unwrap()));
                let (status, chunks) = rounds.get(n).cloned().unwrap_or((200, vec![]));
                let out: String = if status == 200 { chunks.iter().map(|e| format!("data: {e}\n\n")).collect::<String>() + "data: [DONE]\n\n" } else { chunks.first().map(|c| c.to_string()).unwrap_or_default() };
                let _ = write!(conn, "HTTP/1.1 {status} X\r\nContent-Type: text/event-stream\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{out}", out.len());
            }
        });
        (p, got)
    }

    fn delta(d: Value) -> Value {
        json!({ "model": "served-model", "choices": [{ "index": 0, "delta": d }] })
    }
    fn finish(reason: &str) -> Value {
        json!({ "choices": [{ "index": 0, "delta": {}, "finish_reason": reason }] })
    }

    #[test]
    fn text_streams_and_tools_are_run_and_answered() {
        let (p, got) = server(vec![
            (
                200,
                vec![
                    delta(json!({ "role": "assistant", "content": "say Looking" })),
                    delta(json!({ "content": " it up.\n" })),
                    delta(json!({ "tool_calls": [{ "index": 0, "id": "call_a", "type": "function", "function": { "name": "lookup", "arguments": "{\"q\":" } }] })),
                    delta(json!({ "tool_calls": [{ "index": 0, "function": { "arguments": "\"venue\"}" } }] })),
                    delta(json!({ "tool_calls": [{ "index": 1, "id": "call_b", "type": "function", "function": { "name": "screen", "arguments": "{\"ops\":\"topic ws Workshop\"}" } }] })),
                    finish("tool_calls"),
                    json!({ "choices": [], "usage": { "prompt_tokens": 100, "completion_tokens": 20, "prompt_tokens_details": { "cached_tokens": 60 } } }),
                ],
            ),
            (200, vec![delta(json!({ "content": "say Done.\ncard k1 note ws One" })), finish("stop"), json!({ "choices": [], "usage": { "prompt_tokens": 150, "completion_tokens": 10 } })]),
        ]);
        let out = Arc::new(Mutex::new(Vec::new()));
        let o = out.clone();
        let send = move |m: Msg| match m {
            Msg::Delta(d) => o.lock().unwrap().push(format!("delta:{d}")),
            Msg::Screen { ops, reply } => {
                o.lock().unwrap().push(format!("screen:{ops}"));
                let _ = reply.send("applied".into());
            }
            _ => {}
        };
        let tools = json!([
            { "name": "lookup", "description": "Look something up", "input_schema": { "type": "object", "properties": { "q": { "type": "string" } } } },
            { "name": "screen", "description": "Edit the board", "input_schema": { "type": "object", "properties": { "ops": { "type": "string" } } } },
            { "type": "web_search_20260209", "name": "web_search", "max_uses": 8 },
        ]);
        let looked = Arc::new(Mutex::new(Vec::new()));
        let l = looked.clone();
        let tool = move |name: &str, input: &Value| -> Option<(String, bool)> {
            l.lock().unwrap().push(format!("{name}:{input}"));
            Some(("Gapyeong".into(), false))
        };
        let role = Role { system: "SYSTEM", tools, max_steps: 5, check: &|| Ok(Some("<added>\nand the price\n</added>".into())), tool: &tool };
        let (stats, last) = run_as(&p, &role, "TURN", &[("image/png".into(), "QUJD".into())], &send).unwrap();

        assert_eq!(last, "say Done.\ncard k1 note ws One");
        assert_eq!((stats.model.as_str(), stats.input_tokens, stats.cached_tokens, stats.output_tokens), ("served-model", 250, 60, 30));
        assert_eq!(*out.lock().unwrap(), ["delta:say Looking", "delta: it up.\n", "delta:\n", "screen:topic ws Workshop", "delta:say Done.\ncard k1 note ws One", "delta:\n"]);
        assert_eq!(*looked.lock().unwrap(), ["lookup:{\"q\":\"venue\"}"]);

        let got = got.lock().unwrap();
        assert!(got[0].0.to_ascii_lowercase().contains("authorization: bearer sk-test"));
        let first = &got[0].1;
        assert_eq!((first["model"].as_str(), first["stream"].as_bool()), (Some("test-model"), Some(true)));
        assert_eq!(first["messages"][0], json!({ "role": "system", "content": "SYSTEM" }));
        assert_eq!(first["messages"][1]["content"][0]["image_url"]["url"], "data:image/png;base64,QUJD");
        assert_eq!(first["messages"][1]["content"][1]["text"], "TURN");
        // Only tools with a schema of ours are offered.
        let names: Vec<&str> = first["tools"].as_array().unwrap().iter().map(|t| t["function"]["name"].as_str().unwrap()).collect();
        assert_eq!(names, ["lookup", "screen"]);
        // The second request: the model's calls, an answer to each, and what the user added meanwhile.
        let m = got[1].1["messages"].as_array().unwrap();
        assert_eq!(m[2]["tool_calls"][0]["function"], json!({ "name": "lookup", "arguments": "{\"q\":\"venue\"}" }));
        assert_eq!(m[2]["content"], "say Looking it up.\n");
        assert_eq!(m[3], json!({ "role": "tool", "tool_call_id": "call_a", "content": "Gapyeong" }));
        assert_eq!(m[4], json!({ "role": "tool", "tool_call_id": "call_b", "content": "applied" }));
        assert_eq!(m[5]["content"][0]["text"], "<added>\nand the price\n</added>");
    }

    #[test]
    fn a_server_without_stream_options_and_errors() {
        // The first answer refuses the request; it is sent again without `stream_options`.
        let (p, got) = server(vec![(400, vec![json!({ "error": { "message": "unknown field stream_options" } })]), (200, vec![delta(json!({ "content": "say hi" })), finish("stop")])]);
        let role = Role { system: "S", tools: json!([]), max_steps: 3, check: &|| Ok(None), tool: &|_, _| None };
        let (_, last) = run_as(&p, &role, "T", &[], &|_| {}).unwrap();
        assert_eq!(last, "say hi");
        let got = got.lock().unwrap();
        assert!(got[0].1["stream_options"].is_object() && got[1].1["stream_options"].is_null() && got[1].1["tools"].is_null());
        drop(got);

        let (p, _) = server(vec![(401, vec![json!({ "error": { "message": "bad key" } })]), (401, vec![json!({ "error": { "message": "bad key" } })])]);
        assert_eq!(run_as(&p, &role, "T", &[], &|_| {}).unwrap_err(), "HTTP 401: bad key");
        let (p, _) = server(vec![(200, vec![delta(json!({ "content": "half" })), finish("length")])]);
        assert!(run_as(&p, &role, "T", &[], &|_| {}).unwrap_err().contains("cut off"));
    }

    #[test]
    fn settings_file() {
        assert_eq!(parse_settings("http://localhost:11434/v1/\nqwen3\n"), Some(Provider { url: "http://localhost:11434/v1".into(), model: "qwen3".into(), key: None }));
        assert_eq!(parse_settings("# OpenAI\nhttps://api.openai.com/v1\ngpt-5\nsk-abc"), Some(Provider { url: "https://api.openai.com/v1".into(), model: "gpt-5".into(), key: Some("sk-abc".into()) }));
        assert_eq!(parse_settings("https://api.openai.com/v1"), None);
        assert_eq!(parse_settings("api.openai.com\nx"), None);
    }
}

#[cfg(test)]
mod known {
    use super::*;

    #[test]
    fn a_key_says_whose_it_is() {
        let name = |k: &str| recognize(k).map(|k| k.name);
        assert_eq!(name("sk-ant-api03-abcdefghijkl"), Some("Claude"));
        assert_eq!(name("sk-or-v1-abcdefghijklmnop"), Some("OpenRouter"));
        assert_eq!(name("sk-proj-abcdefghijklmnop"), Some("OpenAI"));
        assert_eq!(name("AIzaSyAbcdefghijklmnop"), Some("Gemini"));
        assert_eq!(name("gsk_abcdefghijklmnop"), Some("Groq"));
        assert_eq!(name("hello"), None);
        assert_eq!(name("sk-"), None);
        assert!(recognize("sk-ant-api03-abcdefghijkl").unwrap().is_claude());
    }

    #[test]
    fn the_newest_general_model_is_chosen() {
        let ids = |list: &[&str]| list.iter().map(|s| s.to_string()).collect::<Vec<_>>();
        let of = |name: &str| KNOWN.iter().find(|k| k.name == name).unwrap();
        assert_eq!(of("OpenAI").choose(&ids(&["gpt-4o", "gpt-5", "gpt-5-mini", "gpt-5.2", "gpt-5.2-2026-01-10", "gpt-5.2-codex", "o3", "text-embedding-3-large"])).as_deref(), Some("gpt-5.2"));
        assert_eq!(of("Gemini").choose(&ids(&["models/gemini-2.5-flash", "models/gemini-2.5-pro", "models/gemini-3-pro-preview", "models/gemini-2.5-flash-lite"])).as_deref(), Some("models/gemini-2.5-pro"));
        assert_eq!(of("Gemini").choose(&ids(&["models/gemini-2.5-flash", "models/gemini-embedding-001"])).as_deref(), Some("models/gemini-2.5-flash"));
        assert_eq!(of("OpenAI").choose(&ids(&["whisper-1"])), None);
    }
}
