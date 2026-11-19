//! 한 턴의 작업 루프: 모델이 도구를 요청하면 실행해서 결과를 돌려주기를, 모델이 일을 끝낼 때까지 반복한다.

use std::io::{BufRead, BufReader};
use std::time::{Duration, Instant};

use serde_json::{Value, json};

use crate::llm::{Config, Msg, Stats};

/// 지시문. 부품 표는 등록된 부품 목록에서 만들어 끼워 넣는다 (프로그램이 떠 있는 동안 한 번만 만든다: 바뀌면 캐시가 깨진다).
pub fn system() -> &'static str {
    static BUILT: std::sync::OnceLock<String> = std::sync::OnceLock::new();
    BUILT.get_or_init(|| if crate::sys::korean_prompt() { include_str!("prompt.ko.md") } else { include_str!("prompt.md") }.replace("{{PARTS}}", crate::core::parts_table().trim_end()))
}
/// 사용자가 멈춰서 턴이 끝났다는 표시
pub const STOPPED: &str = "__stopped";
const URL: &str = "https://api.anthropic.com/v1/messages";
/// 한 턴에서 도구를 주고받는 횟수의 상한
const MAX_STEPS: usize = 80;

/// 누가 일하는가: 메인(화면을 고치는 쪽)인지 맡은 작업자인지에 따라 지시문과 도구가 다르다.
pub struct Role<'a> {
    pub system: &'a str,
    pub tools: Value,
    pub max_steps: usize,
    /// 도구를 한 차례 쓴 뒤마다 불린다. Err 면 거기서 멈추고, Ok(Some(글)) 이면 그 글을 모델에게 함께 전한다.
    pub check: &'a dyn Fn() -> Result<Option<String>, String>,
    /// 이 역할만의 도구 처리. None 이면 공통 도구로 실행한다.
    pub tool: &'a dyn Fn(&str, &Value) -> Option<(String, bool)>,
}

pub fn http() -> ureq::Agent {
    ureq::Agent::config_builder()
        // OS 의 TLS 구현과 OS 인증서 저장소를 쓴다 (Windows: Schannel, Mac: Secure Transport).
        .tls_config(
            ureq::tls::TlsConfig::builder()
                .provider(ureq::tls::TlsProvider::NativeTls)
                .root_certs(ureq::tls::RootCerts::PlatformVerifier)
                .build(),
        )
        .http_status_as_error(false)
        .build()
        .into()
}

/// Send a request; when the service is busy (429, 5xx, 529 overloaded) or the connection failed, wait and send it
/// again, up to four more times. It waits what the service asks (retry-after) or 2, 4, 8, 16 seconds. Only a
/// request that got no answer yet is sent again, so nothing on the screen is repeated.
pub fn with_retries(notify: Option<&dyn Fn(Msg)>, mut post: impl FnMut() -> Result<ureq::http::Response<ureq::Body>, String>) -> Result<ureq::http::Response<ureq::Body>, String> {
    let mut wait = 2u64;
    for attempt in 0..5 {
        let result = post();
        let asked = match &result {
            Ok(resp) if matches!(resp.status().as_u16(), 429 | 500 | 502 | 503 | 504 | 529) => {
                Some(resp.headers().get("retry-after").and_then(|v| v.to_str().ok()).and_then(|v| v.trim().parse::<u64>().ok()))
            }
            Err(_) => Some(None),
            _ => None,
        };
        let Some(after) = asked else { return result };
        if attempt == 4 {
            return result;
        }
        let secs = after.unwrap_or(wait).clamp(1, 60);
        crate::sys::log("api.log", &format!("busy or unreachable ({}), trying again in {secs}s", match &result { Ok(r) => r.status().as_u16().to_string(), Err(e) => e.clone() }));
        if let Some(send) = notify {
            let busy = result.is_ok();
            send(Msg::Activity(match (busy, crate::sys::english()) {
                (true, true) => format!("The service is busy; trying again in {secs} s"),
                (true, false) => format!("서비스가 바빠서 {secs}초 뒤에 다시 보냅니다"),
                (false, true) => format!("Could not reach the service; trying again in {secs} s"),
                (false, false) => format!("서비스에 닿지 않아 {secs}초 뒤에 다시 보냅니다"),
            }));
        }
        for _ in 0..secs * 10 {
            if crate::tools::stopped() {
                return Err(STOPPED.into());
            }
            std::thread::sleep(Duration::from_millis(100));
        }
        wait *= 2;
    }
    unreachable!()
}

/// 도구 없이 한 번 묻고 답을 글로 받는다 (기억 정리 같은 작은 뒷일에 쓴다).
pub fn ask(key: &str, model: &str, system: &str, user: &str, max_tokens: u32) -> Result<String, String> {
    // Claude Code 엔진만 쓰는 사람: API 키 없이 Claude Code 를 통해 묻는다.
    if key == crate::cc::VIA {
        return crate::cc::ask(system, user);
    }
    // Only an OpenAI-compatible provider is set up: ask its model.
    if key == crate::openai::VIA {
        let p = crate::openai::settings().ok_or("no provider is set up")?;
        return crate::openai::ask(&p, system, user, max_tokens);
    }
    if let Some(reached) = crate::usage::over_limit() {
        return Err(reached);
    }
    let body = json!({ "model": model, "max_tokens": max_tokens, "system": system, "messages": [{ "role": "user", "content": user }] }).to_string();
    let agent = http();
    let mut resp = with_retries(None, || {
        agent
            .post(URL)
            .header("content-type", "application/json")
            .header("x-api-key", key)
            .header("anthropic-version", "2026-10-21")
            .send(body.as_str())
            .map_err(|e| format!("{}: {e}", crate::sys::tr("연결 실패", "Could not connect")))
    })?;
    let status = resp.status().as_u16();
    let text = resp.body_mut().read_to_string().unwrap_or_default();
    if status != 200 {
        return Err(api_error(status, &text));
    }
    let v: Value = serde_json::from_str(&text).map_err(|e| e.to_string())?;
    let u = &v["usage"];
    let n = |k: &str| u[k].as_u64().unwrap_or(0);
    if let Some(c) = crate::usage::cost(model, n("input_tokens"), n("cache_read_input_tokens"), n("cache_creation_input_tokens"), 1.25, n("output_tokens")) {
        crate::usage::record(c);
    }
    Ok(v["content"].as_array().map(|a| a.iter().filter_map(|b| b["text"].as_str()).collect::<Vec<_>>().join("")).unwrap_or_default())
}

/// When a request's input passes this many tokens, the API summarizes the older part of the conversation
/// (server-side compaction), so a long turn or a long job keeps going instead of running out of room, and each
/// step stops resending everything. APERIT_COMPACT_AT changes it (at least 50,000, the API's minimum).
pub fn compact_at() -> u64 {
    crate::sys::var("COMPACT_AT").ok().and_then(|v| v.trim().parse::<u64>().ok()).unwrap_or(100_000).max(50_000)
}

const COMPACT_BETA: &str = "compact-2026-01-12";

/// What a request may use beyond the basics. Each is dropped for the rest of the turn if the API refuses it.
#[derive(Clone, Copy)]
struct Extras {
    fallbacks: bool,
    drop_block: bool,
    compact: bool,
}

fn body(cfg: &Config, role: &Role, messages: &[Value], x: Extras) -> String {
    let Extras { fallbacks, drop_block, compact } = x;
    let mut v = json!({
        "model": cfg.model,
        "max_tokens": 32000,
        "stream": true,
        "output_config": { "effort": cfg.effort },
        // 지시문은 매 턴 같으므로 여기까지를 캐시한다.
        // The instructions and the tools are the same on every turn and are most of what is sent (about 25,000
        // tokens). They are cached for an hour: on a board, the next turn often comes more than five minutes later,
        // and with the default five-minute cache every such turn would pay for all of it again.
        "system": [{ "type": "text", "text": role.system, "cache_control": if LONG_CACHE.load(std::sync::atomic::Ordering::Relaxed) { json!({ "type": "ephemeral", "ttl": "1h" }) } else { json!({ "type": "ephemeral" }) } }],
        "tools": role.tools,
        // 한 턴 안에서 도구를 여러 번 쓰는 동안 쌓이는 내용도 캐시한다.
        "cache_control": { "type": "ephemeral" },
        "messages": messages,
    });
    if drop_block {
        // 앞선 생각 블록이 "대화가 달라졌다"는 이유로 거부됐을 때: 그 블록만 버리고 이어 가게 한다.
        v["thinking"] = json!({ "type": "adaptive", "block_binding": { "prefix_mismatch_behavior": "drop_block" } });
    }
    if fallbacks {
        // 안전 분류기가 요청을 거절하면 서버가 다른 모델로 이어서 답하게 한다.
        v["fallbacks"] = "default".into();
    }
    if compact {
        v["context_management"] = json!({ "edits": [{ "type": "compact_20260112", "trigger": { "type": "input_tokens", "value": compact_at() } }] });
    }
    v.to_string()
}

/// What a tool gave back.
pub enum ToolOut {
    /// (text, is it an error)
    Text(String, bool),
    Picture(crate::pics::Pic),
}

/// Run one tool the model asked for. Tools that live on the screen side (the board, browser cards, the unseen
/// browser) are asked of the screen thread through `send`. Shared by every engine that runs APERIT's own loop.
pub fn use_tool(role: &Role, name: &str, input: &Value, send: &dyn Fn(Msg)) -> ToolOut {
    let text = |t: (String, bool)| ToolOut::Text(t.0, t.1);
    if let Some(raw) = input["__invalid_json"].as_str() {
        return ToolOut::Text(json!({ "INVALID_JSON": raw }).to_string(), true);
    }
    if name == "screen" {
        // The screen thread edits the screen. Rejected lines come back with their reasons.
        let (rtx, rrx) = std::sync::mpsc::channel();
        send(Msg::Screen { ops: input["ops"].as_str().unwrap_or("").to_string(), reply: rtx });
        return ToolOut::Text(rrx.recv_timeout(Duration::from_secs(15)).unwrap_or_else(|_| "applied".into()), false);
    }
    send(Msg::Activity(crate::tools::describe(name, input)));
    match name {
        // Opens a picture file and shows it to the model as a picture.
        "view_image" => match crate::pics::from_file(input["path"].as_str().unwrap_or("")) {
            Ok(pic) => ToolOut::Picture(pic),
            Err(e) => ToolOut::Text(e, true),
        },
        "board_read" => {
            let (rtx, rrx) = std::sync::mpsc::channel();
            send(Msg::Board { topic: input["topic"].as_str().unwrap_or("").trim().to_string(), reply: rtx });
            text(match rrx.recv_timeout(Duration::from_secs(15)) {
                Ok(Ok(out)) => (out, false),
                Ok(Err(e)) => (format!("error: {e}"), true),
                Err(_) => ("error: the board could not be read".into(), true),
            })
        }
        "browser_text" | "browser_eval" => {
            let script = if name == "browser_text" {
                "document.title + '\\n' + location.href + '\\n\\n' + (document.body ? document.body.innerText : '').slice(0, 30000)".to_string()
            } else {
                input["script"].as_str().unwrap_or("").to_string()
            };
            let (rtx, rrx) = std::sync::mpsc::channel();
            send(Msg::Browser { card: input["card"].as_str().unwrap_or("").to_string(), script, reply: rtx });
            text(match rrx.recv_timeout(Duration::from_secs(20)) {
                Ok(out) => (out.clone(), out.starts_with("오류:")),
                Err(_) => ("error: the page did not answer within 20 seconds".into(), true),
            })
        }
        n if n.starts_with("page_") => text(crate::page::call(name, input)),
        _ => {
            // Work that changes something on the computer is done only if the user allowed it.
            if let Err(why) = crate::permit::check(name, input) {
                return ToolOut::Text(why, true);
            }
            text((role.tool)(name, input).unwrap_or_else(|| crate::tools::execute(name, input)))
        }
    }
}

/// Ask for the one-hour cache on the instructions. Turned off for the rest of the run if the API refuses it.
static LONG_CACHE: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(true);

/// 한 번의 응답에서 받은 것.
struct Reply {
    /// 받은 내용 블록 전부. 다음 요청에 그대로 돌려보내야 한다 (생각 블록 포함, 고치면 안 됨).
    blocks: Vec<Value>,
    stop: String,
}

/// `images` 는 사용자가 이번 말에 붙인 사진 (형식, base64).
/// `added` 는 일하는 도중에 사용자가 덧붙인 말을 꺼내 오는 함수 (없으면 None). 도구를 한 차례 쓴 뒤마다 불린다.
pub fn run(cfg: &Config, key: &str, turn_text: &str, images: &[(String, String)], added: &dyn Fn() -> Result<Option<String>, String>, send: &dyn Fn(Msg)) -> Result<Stats, String> {
    let role = Role { system: system(), tools: crate::tools::definitions(false), max_steps: MAX_STEPS, check: &|| added(), tool: &|_, _| None };
    run_as(cfg, key, &role, turn_text, images, send).map(|(stats, _)| stats)
}

/// 모델이 일을 끝낼 때까지 돌린다. 마지막 응답의 글도 함께 돌려준다 (작업자의 보고서).
pub fn run_as(cfg: &Config, key: &str, role: &Role, turn_text: &str, images: &[(String, String)], send: &dyn Fn(Msg)) -> Result<(Stats, String), String> {
    let agent = http();
    let started = Instant::now();
    let mut stats = Stats { model: cfg.model.clone(), ..Default::default() };
    let mut extras = Extras { fallbacks: true, drop_block: false, compact: true };
    let mut messages = if images.is_empty() {
        vec![json!({ "role": "user", "content": turn_text })]
    } else {
        // 사진을 먼저, 글을 뒤에 둔다.
        let mut content: Vec<Value> =
            images.iter().map(|(media, data)| json!({ "type": "image", "source": { "type": "base64", "media_type": media, "data": data } })).collect();
        content.push(json!({ "type": "text", "text": turn_text }));
        vec![json!({ "role": "user", "content": content })]
    };

    for _ in 0..role.max_steps {
        if let Some(reached) = crate::usage::over_limit() {
            return Err(reached);
        }
        let reply = stream_once(&agent, cfg, key, role, &messages, &mut extras, &mut stats, started, send)?;
        send(Msg::Delta("\n".into())); // 응답 사이에 줄이 붙지 않게 한다
        // 빈 글 블록은 되돌려 보낼 수 없다.
        let content: Vec<Value> =
            reply.blocks.iter().filter(|b| !(b["type"] == "text" && b["text"].as_str().unwrap_or("").is_empty())).cloned().collect();
        // The API summarized what came before: it ignores everything ahead of the summary from now on, so stop
        // sending it. The first message stays, so that the conversation still starts with the user.
        if content.iter().any(|b| b["type"] == "compaction") {
            messages.truncate(1);
        }
        match reply.stop.as_str() {
            "tool_use" => {
                messages.push(json!({ "role": "assistant", "content": content }));
                let mut results = Vec::new();
                for b in reply.blocks.iter().filter(|b| b["type"] == "tool_use") {
                    let name = b["name"].as_str().unwrap_or("");
                    let input = &b["input"];
                    let (out, is_error) = match use_tool(role, name, input, send) {
                        ToolOut::Text(out, is_error) => (out, is_error),
                        // A picture goes back as a picture.
                        ToolOut::Picture(pic) => {
                            results.push(json!({ "type": "tool_result", "tool_use_id": b["id"], "content": [
                                { "type": "image", "source": { "type": "base64", "media_type": pic.media, "data": pic.b64() } },
                                { "type": "text", "text": format!("sent at {}x{} pixels", pic.w, pic.h) },
                            ] }));
                            continue;
                        }
                    };
                    results.push(json!({ "type": "tool_result", "tool_use_id": b["id"], "content": out, "is_error": is_error }));
                }
                // 멈추라는 신호나 중간에 전할 말이 있는지 본다.
                if let Some(more) = (role.check)()? {
                    results.push(json!({ "type": "text", "text": more }));
                }
                // 도구 결과는 한 메시지에 모두 담아 돌려준다.
                messages.push(json!({ "role": "user", "content": results }));
                send(Msg::Activity(String::new()));
            }
            // 서버 쪽 도구(웹 검색 등)가 길어져 잠시 멈춘 것: 받은 것을 그대로 붙여 다시 보내면 이어서 한다.
            "pause_turn" => messages.push(json!({ "role": "assistant", "content": content })),
            "refusal" => return Err(crate::sys::tr("모델이 이 요청을 거절했습니다", "The model declined this request").into()),
            "max_tokens" => return Err(crate::sys::tr("출력이 한도에서 잘렸습니다", "The answer was cut off at the output limit").into()),
            _ => {
                stats.total_ms = started.elapsed().as_millis() as u64;
                let text: Vec<&str> = content.iter().filter_map(|b| if b["type"] == "text" { b["text"].as_str() } else { None }).collect();
                return Ok((stats, text.join("\n")));
            }
        }
    }
    Err(if crate::sys::english() { format!("Stopped after {} steps in one turn", role.max_steps) } else { format!("한 번에 {}단계를 넘겨 중단했습니다", role.max_steps) })
}

#[allow(clippy::too_many_arguments)]
fn stream_once(
    agent: &ureq::Agent,
    cfg: &Config,
    key: &str,
    role: &Role,
    messages: &[Value],
    extras: &mut Extras,
    stats: &mut Stats,
    started: Instant,
    send: &dyn Fn(Msg),
) -> Result<Reply, String> {
    let post = |x: Extras| {
        let mut req = agent
            .post(URL)
            .header("content-type", "application/json")
            .header("x-api-key", key)
            .header("anthropic-version", "2026-10-21");
        let betas: Vec<&str> = [(x.fallbacks, "server-side-fallback-2026-07-01"), (x.drop_block, "thinking-binding-controls-2026-08-01"), (x.compact, COMPACT_BETA)]
            .iter()
            .filter(|b| b.0)
            .map(|b| b.1)
            .collect();
        if !betas.is_empty() {
            req = req.header("anthropic-beta", betas.join(","));
        }
        req.send(body(cfg, role, messages, x)).map_err(|e| format!("{}: {e}", crate::sys::tr("연결 실패", "Could not connect")))
    };
    let mut resp = with_retries(Some(send), || post(*extras))?;
    for _ in 0..4 {
        if resp.status().as_u16() != 400 {
            break;
        }
        let text = resp.body_mut().read_to_string().unwrap_or_default();
        if !extras.drop_block && text.contains("block_binding") {
            // 앞서 받은 생각 블록이 "그때와 대화 내용이 다르다"고 거부됐다. 그 블록만 버리게 하고 이어 간다 (이 턴이 끝날 때까지 유지).
            // 원인을 찾을 수 있게 보낸 내용을 남긴다.
            crate::sys::log("api.log", &format!("생각 블록 거부, 버리고 다시 보냄: {}", api_error(400, &text)));
            let _ = std::fs::write(crate::sys::data_dir().join("bind-error.json"), json!({ "error": text, "messages": messages }).to_string());
            extras.drop_block = true;
        } else if extras.compact && (text.contains("context_management") || text.contains("compact")) {
            // Compaction is not accepted here (an older model, a proxy): go on without it.
            crate::sys::log("api.log", &format!("compaction refused, going on without it: {}", api_error(400, &text)));
            extras.compact = false;
        } else if LONG_CACHE.load(std::sync::atomic::Ordering::Relaxed) && (text.contains("ttl") || text.contains("cache_control")) {
            // The one-hour cache was not accepted here: go back to the default and send again.
            crate::sys::log("api.log", &format!("one-hour cache refused, using the default: {}", api_error(400, &text)));
            LONG_CACHE.store(false, std::sync::atomic::Ordering::Relaxed);
        } else if extras.fallbacks && text.contains("fallback") {
            extras.fallbacks = false; // 이 계정·모델에서 fallbacks 가 안 받아들여지면 빼고 다시 보낸다
        } else {
            return Err(api_error(400, &text));
        }
        resp = with_retries(Some(send), || post(*extras))?;
    }
    let status = resp.status().as_u16();
    if status != 200 {
        let text = resp.body_mut().read_to_string().unwrap_or_default();
        return Err(api_error(status, &text));
    }

    let mut blocks: Vec<Value> = Vec::new();
    let mut partial: std::collections::HashMap<usize, String> = Default::default();
    let mut stop = String::new();
    // What this one request used, for what it cost (usage.rs).
    let (mut used_in, mut used_read, mut used_write, mut used_out, mut used_model) = (0u64, 0u64, 0u64, 0u64, cfg.model.clone());
    // The summarizing step, when there was one: its own tokens, reported apart from the answer's.
    let mut compaction_usage = Value::Null;
    let reader = BufReader::new(resp.into_body().into_reader());
    for line in reader.lines() {
        let line = line.map_err(|e| format!("{}: {e}", crate::sys::tr("스트림 끊김", "The stream broke")))?;
        if crate::tools::stopped() {
            return Err(STOPPED.into());
        }
        let Some(data) = line.strip_prefix("data:") else { continue };
        let Ok(v) = serde_json::from_str::<Value>(data.trim()) else { continue };
        let idx = v["index"].as_u64().unwrap_or(0) as usize;
        match v["type"].as_str().unwrap_or("") {
            "message_start" => {
                let u = &v["message"]["usage"];
                // A turn that uses tools sends everything again for each step: add every request up.
                let read = u["cache_read_input_tokens"].as_u64().unwrap_or(0);
                used_in = u["input_tokens"].as_u64().unwrap_or(0);
                used_read = read;
                used_write = u["cache_creation_input_tokens"].as_u64().unwrap_or(0);
                if let Some(m) = v["message"]["model"].as_str() {
                    used_model = m.to_string();
                }
                stats.cached_tokens += read;
                stats.input_tokens += u["input_tokens"].as_u64().unwrap_or(0) + read + u["cache_creation_input_tokens"].as_u64().unwrap_or(0);
                if let Some(m) = v["message"]["model"].as_str() {
                    stats.model = m.to_string();
                }
            }
            "content_block_start" => {
                if blocks.len() <= idx {
                    blocks.resize(idx + 1, Value::Null);
                }
                blocks[idx] = v["content_block"].clone();
                match blocks[idx]["type"].as_str() {
                    Some("tool_use") => {
                        partial.insert(idx, String::new());
                    }
                    Some("server_tool_use") => {
                        partial.insert(idx, String::new());
                        let what = if blocks[idx]["name"] == "web_fetch" { "웹 페이지 읽는 중" } else { "웹 검색 중" };
                        send(Msg::Activity(what.into()));
                    }
                    Some("compaction") => {
                        send(Msg::Activity(crate::sys::tr("지금까지 한 일을 요약하는 중", "Summarizing the work so far").into()));
                    }
                    _ => {}
                }
            }
            "content_block_delta" if idx < blocks.len() => {
                let d = &v["delta"];
                let append = |b: &mut Value, field: &str, more: &str| {
                    let cur = b[field].as_str().unwrap_or("").to_string();
                    b[field] = Value::String(cur + more);
                };
                match d["type"].as_str().unwrap_or("") {
                    "text_delta" => {
                        let t = d["text"].as_str().unwrap_or("");
                        if stats.first_ms == 0 {
                            stats.first_ms = started.elapsed().as_millis() as u64;
                        }
                        append(&mut blocks[idx], "text", t);
                        send(Msg::Delta(t.to_string()));
                    }
                    "input_json_delta" => {
                        if let Some(p) = partial.get_mut(&idx) {
                            p.push_str(d["partial_json"].as_str().unwrap_or(""));
                        }
                    }
                    "thinking_delta" => append(&mut blocks[idx], "thinking", d["thinking"].as_str().unwrap_or("")),
                    "signature_delta" => blocks[idx]["signature"] = d["signature"].clone(),
                    // The summary comes whole, in one piece.
                    "compaction_delta" => {
                        if let Some(more) = d["content"].as_str() {
                            append(&mut blocks[idx], "content", more);
                        }
                    }
                    "citations_delta" => {
                        if !blocks[idx]["citations"].is_array() {
                            blocks[idx]["citations"] = json!([]);
                        }
                        blocks[idx]["citations"].as_array_mut().unwrap().push(d["citation"].clone());
                    }
                    _ => {}
                }
            }
            "content_block_stop" => {
                if let Some(raw) = partial.remove(&idx) {
                    if raw.trim().is_empty() {
                        // 입력이 조각으로 오지 않았으면 처음 받은 것을 그대로 둔다 (지우면 받은 내용을 고친 것이 되어 다음 요청이 거부된다).
                        if !blocks[idx]["input"].is_object() {
                            blocks[idx]["input"] = json!({});
                        }
                    } else {
                        blocks[idx]["input"] = serde_json::from_str(&raw).unwrap_or_else(|_| json!({ "__invalid_json": raw }));
                    }
                }
            }
            "message_delta" => {
                if let Some(it) = v["usage"]["iterations"].as_array() {
                    if let Some(c) = it.iter().rev().find(|i| i["type"] == "compaction") {
                        compaction_usage = c.clone();
                    }
                }
                if let Some(n) = v["usage"]["output_tokens"].as_u64() {
                    stats.output_tokens += n;
                    used_out = n;
                }
                if let Some(s) = v["delta"]["stop_reason"].as_str() {
                    stop = s.to_string();
                }
            }
            "error" => return Err(format!("{}: {}", crate::sys::tr("API 오류", "API error"), v["error"]["message"].as_str().unwrap_or("unknown"))),
            _ => {}
        }
    }
    blocks.retain(|b| !b.is_null());
    let write_factor = if LONG_CACHE.load(std::sync::atomic::Ordering::Relaxed) { 2.0 } else { 1.25 };
    if let Some(c) = crate::usage::cost(&used_model, used_in, used_read, used_write, write_factor, used_out) {
        crate::usage::record(c);
    }
    if !compaction_usage.is_null() {
        // The summarizing step is billed apart from the answer and is not in the answer's usage.
        let n = |k: &str| compaction_usage[k].as_u64().unwrap_or(0);
        stats.input_tokens += n("input_tokens") + n("cache_read_input_tokens") + n("cache_creation_input_tokens");
        stats.output_tokens += n("output_tokens");
        if let Some(c) = crate::usage::cost(&used_model, n("input_tokens"), n("cache_read_input_tokens"), n("cache_creation_input_tokens"), 1.25, n("output_tokens")) {
            crate::usage::record(c);
        }
    }
    if let Some(b) = blocks.iter().find(|b| b["type"] == "compaction") {
        let summary = b["content"].as_str().unwrap_or("");
        crate::sys::log("api.log", &format!("compacted: summary of {} characters; summarizing step {}", summary.chars().count(), compaction_usage));
        if summary.is_empty() {
            // No summary came back: the block cannot stand for what came before. Leave it out; the request
            // just goes on with the full conversation.
            blocks.retain(|b| b["type"] != "compaction");
        }
    }
    Ok(Reply { blocks, stop })
}

fn api_error(status: u16, text: &str) -> String {
    let msg = serde_json::from_str::<Value>(text)
        .ok()
        .and_then(|v| v["error"]["message"].as_str().map(str::to_string))
        .unwrap_or_else(|| text.chars().take(300).collect());
    format!("HTTP {status}: {msg}")
}

#[cfg(test)]
mod tests {
    use super::*;

    fn reply(status: u16, retry_after: Option<&str>) -> Result<ureq::http::Response<ureq::Body>, String> {
        let mut r = ureq::http::Response::builder().status(status);
        if let Some(v) = retry_after {
            r = r.header("retry-after", v);
        }
        Ok(r.body(ureq::Body::builder().data("{}")).unwrap())
    }

    #[test]
    fn busy_is_sent_again_and_a_bad_request_is_not() {
        // 529 overloaded, asked to wait a second: sent again, and the second answer is the one returned.
        let mut calls = 0;
        let started = std::time::Instant::now();
        let got = with_retries(None, || {
            calls += 1;
            if calls == 1 { reply(529, Some("1")) } else { reply(200, None) }
        });
        assert_eq!(got.unwrap().status().as_u16(), 200);
        assert_eq!(calls, 2);
        assert!(started.elapsed() >= Duration::from_millis(900));
        // A connection that failed is tried again too.
        let mut calls = 0;
        let got = with_retries(None, || {
            calls += 1;
            if calls == 1 { Err("Could not connect".into()) } else { reply(429, Some("1")).and_then(|_| reply(200, None)) }
        });
        assert_eq!(got.unwrap().status().as_u16(), 200);
        // 400 is the request's own fault: returned at once.
        let mut calls = 0;
        let got = with_retries(None, || {
            calls += 1;
            reply(400, None)
        });
        assert_eq!(got.unwrap().status().as_u16(), 400);
        assert_eq!(calls, 1);
    }
}
