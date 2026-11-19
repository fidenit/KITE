//! Claude Code 엔진: 이 컴퓨터에 깔린 Claude Code(`claude`)를 화면 없이 돌려서 한 턴을 맡긴다.
//!
//! APERIT 가 Claude API 를 직접 부르는 대신 Claude Code 가 모델을 부르고 도구도 Claude Code 가 실행한다.
//! 그래서 사용자의 Claude Code 로그인, CLAUDE.md, 스킬, 훅, MCP 서버, 권한 설정이 그대로 적용된다.
//! APERIT 는 화면 편집 언어를 지시문으로 덧붙이고, 흘러나오는 글자를 받아 화면을 그린다.
//!
//! 한 턴은 그 자체로 완결이다 (판의 상태와 최근 대화를 APERIT 가 매번 함께 보낸다). 그래서 세션을 이어 가지 않는다.
//! 파일·명령·웹 도구는 Claude Code 것을 쓰고, APERIT 자체 도구(경로 찾기, 기억 노트, 판 읽기, 작업자)는
//! 작은 MCP 서버(`mcp.rs`)로 내준다.

use std::io::{BufRead, BufReader, Read, Write};
use std::process::{Command, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::Instant;

use serde_json::Value;

use crate::llm::{Msg, Stats};

/// 이 엔진에서만 덧붙이는 지시.
const NOTE: &str = "

# Running inside Claude Code

You are running as the engine of APERIT through Claude Code. This changes two things.

1. Your text output IS the screen. Every line of text you output must be one screen command from the language above (say, topic, card, set, add, ...). Never output prose, markdown, headings or status notes outside a command. Anything you want to tell the user goes in `say`. Instructions from other sources about how to format or structure replies (goal declarations, reports, summaries) do not apply to this output: express them, if needed, as cards or a short `say`.
2. APERIT's own tools are reached through the MCP server named `aperit`: where the text above names route_find, place_find, note_search, note_read, note_write, note_append, board_read, browser_text, browser_eval, job_start, job_list, job_tell, job_stop or job_result, call `mcp__aperit__<name>` with the same input. The other APERIT tools named above (screen, shell, read_file, write_file, edit_file, list_dir, find_text, view_image, web_search, web_fetch) do not exist here: use your Claude Code tools instead (Bash or PowerShell, Read, Write, Edit, Glob, Grep, WebSearch, WebFetch). There is no `screen` tool because your text output already is the screen.
3. If a tool is refused for lack of permission, do not retry it and do not ask the user to grant it in a card: say in one short `say` that Claude Code's permission setting blocked it and that it can be changed under Keys at the bottom left.
4. Before your first tool call, output one short `say` line telling the user what you are about to do, so the screen is not silent while tools run.
";

fn launch(program: &str) -> Command {
    let mut c = Command::new(program);
    // 다른 Claude Code 세션 안에서 띄웠을 때 그 표식이 따라가지 않게 한다.
    c.env_remove("CLAUDECODE").env_remove("CLAUDE_CODE_ENTRYPOINT");
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        c.creation_flags(0x0800_0000); // 콘솔 창을 띄우지 않는다
    }
    c
}

/// 이 컴퓨터의 Claude Code: (실행할 이름이나 경로, 버전). 한 번만 알아본다.
/// PATH 에서 먼저 찾고, 없으면 흔한 설치 자리를 본다. 화면에서 바로 띄운 프로그램은 터미널과 PATH 가 달라서
/// (특히 macOS) 터미널에서는 되는 `claude` 가 안 보일 수 있다. Windows 에서 npm 으로 깐 것은 claude.cmd 다.
fn found() -> Option<(String, String)> {
    static V: OnceLock<Option<(String, String)>> = OnceLock::new();
    V.get_or_init(|| {
        let mut places: Vec<String> = Vec::new();
        match crate::sys::var("CLAUDE") {
            Ok(p) => places.push(p),
            Err(_) => {
                places.push("claude".into());
                let home = std::env::var(if cfg!(windows) { "USERPROFILE" } else { "HOME" }).unwrap_or_default();
                if cfg!(windows) {
                    places.push("claude.cmd".into());
                    places.push(format!("{home}\\.local\\bin\\claude.exe"));
                    places.push(format!("{}\\npm\\claude.cmd", std::env::var("APPDATA").unwrap_or_default()));
                } else {
                    places.extend([format!("{home}/.local/bin/claude"), format!("{home}/.claude/local/claude"), "/opt/homebrew/bin/claude".into(), "/usr/local/bin/claude".into()]);
                }
            }
        }
        places.into_iter().find_map(|p| {
            let out = launch(&p).arg("--version").stdin(Stdio::null()).output().ok()?;
            if !out.status.success() {
                return None;
            }
            let version = String::from_utf8_lossy(&out.stdout).split_whitespace().next()?.to_string();
            Some((p, version))
        })
    })
    .clone()
}

/// 키 창에서 고른 권한 방식: default(Claude Code 설정 그대로), acceptEdits(파일 수정 허용), bypassPermissions(전부 허용).
pub fn permission() -> String {
    let saved = std::fs::read_to_string(crate::sys::base_dir().join("claude-permission.txt")).unwrap_or_default();
    match saved.trim() {
        "acceptEdits" => "acceptEdits".into(),
        "bypassPermissions" => "bypassPermissions".into(),
        _ => "default".into(),
    }
}

fn command() -> Command {
    // 못 찾았으면 지정한 이름(없으면 claude)으로 띄워 본다. 실패하면 그 오류가 사용자에게 보인다.
    launch(&found().map(|f| f.0).unwrap_or_else(|| crate::sys::var("CLAUDE").unwrap_or_else(|_| "claude".into())))
}

/// 이 컴퓨터에 Claude Code 가 있으면 그 버전.
pub fn version() -> Option<String> {
    found().map(|f| f.1)
}

/// Is the Claude Code on this computer signed in (`claude auth status`)? The answer is the last one known:
/// asking takes a moment, so it is asked in the background, at most once every few seconds.
pub fn signed_in() -> bool {
    use std::sync::atomic::{AtomicBool, Ordering};
    static KNOWN: AtomicBool = AtomicBool::new(false);
    static ASKING: AtomicBool = AtomicBool::new(false);
    static ASKED: std::sync::Mutex<Option<std::time::Instant>> = std::sync::Mutex::new(None);
    let stale = ASKED.lock().unwrap().map(|t| t.elapsed().as_secs() >= 4).unwrap_or(true);
    if stale && !ASKING.swap(true, Ordering::SeqCst) {
        *ASKED.lock().unwrap() = Some(std::time::Instant::now());
        std::thread::spawn(|| {
            let out = command().args(["auth", "status"]).stdin(std::process::Stdio::null()).stderr(std::process::Stdio::null()).output();
            let yes = out.ok().and_then(|o| serde_json::from_slice::<Value>(&o.stdout).ok()).map(|v| v["loggedIn"] == true).unwrap_or(false);
            KNOWN.store(yes, Ordering::SeqCst);
            ASKING.store(false, Ordering::SeqCst);
        });
    }
    KNOWN.load(Ordering::SeqCst)
}

/// Start signing in to Claude: a console window running `claude auth login`, which opens the browser.
pub fn sign_in() {
    let _ = if cfg!(windows) {
        Command::new("cmd").args(["/c", "start", "APERIT sign-in", "cmd", "/k", "claude auth login"]).spawn()
    } else if cfg!(target_os = "macos") {
        Command::new("osascript").args(["-e", "tell application \"Terminal\" to do script \"claude auth login\""]).spawn()
    } else {
        Command::new("x-terminal-emulator").args(["-e", "sh", "-c", "claude auth login"]).spawn()
    };
}

/// base64 글자를 바이트로 되돌린다.
fn unbase64(text: &str) -> Option<Vec<u8>> {
    let mut out = Vec::with_capacity(text.len() * 3 / 4);
    let (mut acc, mut bits) = (0u32, 0u32);
    for c in text.bytes() {
        let v = match c {
            b'A'..=b'Z' => c - b'A',
            b'a'..=b'z' => c - b'a' + 26,
            b'0'..=b'9' => c - b'0' + 52,
            b'+' => 62,
            b'/' => 63,
            b'=' | 10 | 13 => continue,
            _ => return None,
        };
        acc = (acc << 6) | v as u32;
        bits += 6;
        if bits >= 8 {
            bits -= 8;
            out.push((acc >> bits) as u8);
        }
    }
    Some(out)
}

/// 도구 사용을 진행 표시에 쓸 말로 바꾼다 (chat::plain 이 아는 꼴).
fn describe(name: &str, input: &Value) -> String {
    let s = |k: &str| input.get(k).and_then(Value::as_str).unwrap_or("").chars().take(80).collect::<String>();
    match name {
        "Bash" | "PowerShell" => format!("명령 실행: {}", s("command")),
        "Read" => format!("파일 읽기: {}", s("file_path")),
        "Write" => format!("파일 쓰기: {}", s("file_path")),
        "Edit" | "NotebookEdit" => format!("파일 고치기: {}", s("file_path")),
        "Glob" => format!("폴더 보기: {}", s("pattern")),
        "Grep" => format!("글자 찾기: {}", s("pattern")),
        "WebSearch" => format!("웹 검색: {}", s("query")),
        "WebFetch" => format!("웹 읽기: {}", s("url")),
        other => other.to_string(),
    }
}

/// 한 턴을 돌린다. 글자는 나오는 대로 `send` 로 보낸다. `stop` 이 켜지면 Claude Code 를 끝낸다.
pub fn run(effort: &str, turn_text: &str, images: &[(String, String)], stop: &Arc<AtomicBool>, send: &dyn Fn(Msg)) -> Result<Stats, String> {
    let system = crate::agent::system().to_string() + NOTE;
    exec("cc-system.txt", &system, &crate::sys::base_dir(), effort, turn_text, images, stop, send).map(|(stats, _)| stats)
}

/// 작업자에게만 덧붙이는 지시.
const WORKER_NOTE: &str = "

# Running inside Claude Code

You are running through Claude Code. The tools named above (shell, read_file, write_file, edit_file, list_dir, find_text, web_search, web_fetch, view_image) do not exist here: use your Claude Code tools instead (Bash or PowerShell, Read, Write, Edit, Glob, Grep, WebSearch, WebFetch). The note tools are reached as `mcp__aperit__note_search`, `mcp__aperit__note_read`, `mcp__aperit__note_write` and `mcp__aperit__note_append`. There is no `progress` tool and no messages from the main session arrive while you work; what you are doing is shown to the user automatically. If a tool is refused for lack of permission, do not retry it: say so in your report. Your final message is your report.
";

/// 작업자 한 명을 Claude Code 로 돌린다. 마지막 글(보고서)도 돌려준다.
pub fn worker(id: &str, system: &str, dir: &str, effort: &str, order: &str, stop: &Arc<AtomicBool>, send: &dyn Fn(Msg)) -> Result<(Stats, String), String> {
    exec(&format!("cc-worker-{id}.txt"), &(system.to_string() + WORKER_NOTE), std::path::Path::new(dir), effort, order, &[], stop, send)
}

/// What the model is told when Claude Code runs on a server.
fn remote_note(s: &crate::ssh::Server) -> String {
    format!(
        "

# Claude Code runs on a server

You run on the server `{}`, reached over SSH, in the folder `{}`. Files, commands and paths are the server's. The home folder and operating system named in `<env>` are the user's own computer, where APERIT runs and where the memory notes are: use the note tools for memory, and the server's paths for work.
",
        s.target(),
        if s.dir.trim().is_empty() { "~" } else { s.dir.trim() }
    )
}

/// `agent::ask` 에서 키 자리에 이 값을 주면 API 대신 Claude Code 로 묻는다.
pub const VIA: &str = "\u{1}claude-code";

/// 도구 없이 한 번 묻고 답을 받는다 (기억 정리 같은 작은 일). 작은 모델을 쓴다.
pub fn ask(system: &str, user: &str) -> Result<String, String> {
    if let Some(s) = crate::ssh::chosen() {
        let script = format!(
            "mkdir -p ~/.aperit && head -c {} > ~/.aperit/ask.txt && exec {} -p --output-format json --no-session-persistence --tools '' --strict-mcp-config --model haiku --system-prompt-file \"$HOME/.aperit/ask.txt\"",
            system.len(),
            s.claude()
        );
        let out = crate::ssh::run(&s, &script, format!("{system}{user}").as_bytes(), None)?;
        let v: Value = serde_json::from_str(out.trim()).map_err(|e| format!("Claude Code 의 답을 읽지 못함: {e}"))?;
        if v["is_error"].as_bool().unwrap_or(false) {
            return Err(v["result"].as_str().unwrap_or("Claude Code error").chars().take(300).collect());
        }
        return Ok(v["result"].as_str().unwrap_or("").to_string());
    }
    static N: std::sync::atomic::AtomicUsize = std::sync::atomic::AtomicUsize::new(0);
    let dir = crate::sys::data_dir();
    let _ = std::fs::create_dir_all(&dir);
    let file = dir.join(format!("cc-ask-{}.txt", N.fetch_add(1, Ordering::Relaxed) % 8));
    std::fs::write(&file, system).map_err(|e| e.to_string())?;
    let mut child = command()
        .args(["-p", "--output-format", "json", "--no-session-persistence", "--tools", "", "--strict-mcp-config", "--model", "haiku", "--system-prompt-file"])
        .arg(&file)
        .current_dir(&dir)
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()
        .map_err(|e| format!("Claude Code: {e}"))?;
    if let Some(mut stdin) = child.stdin.take() {
        let _ = stdin.write_all(user.as_bytes());
    }
    let out = child.wait_with_output().map_err(|e| e.to_string())?;
    let v: Value = serde_json::from_slice(&out.stdout).map_err(|e| format!("Claude Code 의 답을 읽지 못함: {e}"))?;
    if v["is_error"].as_bool().unwrap_or(false) {
        return Err(v["result"].as_str().unwrap_or("Claude Code error").chars().take(300).collect());
    }
    Ok(v["result"].as_str().unwrap_or("").to_string())
}

/// Claude Code 를 한 번 돌린다: 지시문을 덧붙이고, 글자는 나오는 대로 `send` 로 보낸다. (통계, 마지막 글)을 돌려준다.
#[allow(clippy::too_many_arguments)]
fn exec(tag: &str, system_text: &str, cwd: &std::path::Path, effort: &str, turn_text: &str, images: &[(String, String)], stop: &Arc<AtomicBool>, send: &dyn Fn(Msg)) -> Result<(Stats, String), String> {
    let started = Instant::now();
    let dir = crate::sys::data_dir();
    let _ = std::fs::create_dir_all(&dir);
    // On a server (engine "ssh"), everything below happens there; APERIT's own tools stay here.
    let remote = crate::ssh::chosen();
    let system_text = match &remote {
        Some(s) => format!("{system_text}{}", remote_note(s)),
        None => system_text.to_string(),
    };

    // 붙인 사진은 파일로 내려 주고 경로를 알려 준다 (Claude Code 가 Read 로 본다). 서버라면 서버에 올린다.
    let mut text = turn_text.to_string();
    if !images.is_empty() {
        let mut paths = Vec::new();
        let home = remote.as_ref().and_then(crate::ssh::home);
        for (i, (media, data)) in images.iter().enumerate() {
            let ext = if media.contains("png") { "png" } else { "jpg" };
            let Some(bytes) = unbase64(data) else { continue };
            match (&remote, &home) {
                (Some(s), Some(h)) => {
                    let name = format!("cc-image-{i}.{ext}");
                    if crate::ssh::run(s, &format!("mkdir -p ~/.aperit && cat > ~/.aperit/{name}"), &bytes, None).is_ok() {
                        paths.push(format!("{h}/.aperit/{name}"));
                    }
                }
                (Some(_), None) => {}
                (None, _) => {
                    let path = dir.join(format!("cc-image-{i}.{ext}"));
                    if std::fs::write(&path, bytes).is_ok() {
                        paths.push(path.display().to_string());
                    }
                }
            }
        }
        if !paths.is_empty() {
            text += &format!("\n<attached_images>\nThe user attached these pictures. Look at them with the Read tool.\n{}\n</attached_images>", paths.join("\n"));
        }
    }

    let mut args: Vec<String> = ["-p", "--output-format", "stream-json", "--verbose", "--include-partial-messages", "--no-session-persistence"].map(String::from).to_vec();
    // 사용자가 더 주고 싶은 옵션 (예: --permission-mode acceptEdits, --model sonnet). 한 줄에 하나씩 또는 띄어서.
    let extra = std::fs::read_to_string(crate::sys::base_dir().join("claude-args.txt")).unwrap_or_default();
    // 화면이 빨리 나와야 하므로 생각의 깊이는 APERIT 가 정한 값을 넘긴다 (사용자가 따로 적었으면 그것을 따른다).
    if !extra.contains("--effort") && !effort.is_empty() {
        args.extend(["--effort".into(), effort.to_string()]);
    }
    // Claude Code 의 도구는 꼭 필요한 것만 싣는다. 전부 실으면 턴마다 보내는 양이 늘어 첫 반응이 늦어진다 (실측 10.6초 → 6.4초).
    // 사용자가 붙여 둔 MCP 서버의 도구는 그대로 남는다. ToolSearch 를 빼면 안 된다: 그것이 없으면 MCP 도구의 설명이
    // 전부 매 턴 실려 간다 (도구 200개짜리 서버가 붙은 PC에서 턴당 39만 토큰 → 4만 토큰).
    if !extra.contains("--tools") {
        let windows_here = cfg!(windows) && remote.is_none();
        args.extend(["--tools".into(), if windows_here { "Bash,PowerShell,Read,Edit,Write,Glob,Grep,WebSearch,WebFetch,ToolSearch" } else { "Bash,Read,Edit,Write,Glob,Grep,WebSearch,WebFetch,ToolSearch" }.into()]);
    }
    // 권한: 기본은 사용자의 Claude Code 설정 그대로다 (허용해 두지 않은 파일 수정과 명령은 거절된다).
    // 키 창에서 풀어 줄 수 있다.
    let mode = permission();
    if !extra.contains("--permission-mode") && mode != "default" {
        args.extend(["--permission-mode".into(), mode.clone()]);
    }
    let (mut cmd, input) = match &remote {
        None => {
            let system = dir.join(tag);
            std::fs::write(&system, &system_text).map_err(|e| format!("지시문을 쓰지 못함: {e}"))?;
            let mut cmd = command();
            cmd.args(&args).arg("--append-system-prompt-file").arg(&system);
            // APERIT 자체 도구를 MCP 로 내준다.
            if let Some(config) = crate::mcp::inside_config(stop) {
                cmd.arg("--mcp-config").arg(config).args(["--allowedTools", "mcp__aperit"]);
            }
            cmd.args(extra.split_whitespace()).current_dir(cwd);
            (cmd, text.into_bytes())
        }
        Some(s) => {
            // The instructions travel first on the same connection (head -c takes exactly their bytes), the
            // turn after them. APERIT's tools come through a reverse tunnel to this computer's inside door.
            let mut forward = None;
            let mut tail: Vec<String> = Vec::new();
            if let Some((local, token)) = crate::mcp::inside_door(stop) {
                let remote_port = 20000 + (std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.subsec_nanos()).unwrap_or(0) % 20000) as u16;
                forward = Some((remote_port, local));
                tail.extend(["--mcp-config".into(), crate::ssh::shq(&crate::mcp::config_line(remote_port, &token)), "--allowedTools".into(), "mcp__aperit".into()]);
            }
            let quoted: Vec<String> = args.iter().map(|a| crate::ssh::shq(a)).chain(tail).chain(extra.split_whitespace().map(crate::ssh::shq)).collect();
            let tag = tag.replace(|c: char| !(c.is_ascii_alphanumeric() || c == '-' || c == '.' || c == '_'), "-");
            let script = format!(
                "mkdir -p ~/.aperit && head -c {} > ~/.aperit/{tag} && {} && echo \"APERITPID $$\" && exec {} {} --append-system-prompt-file \"$HOME/.aperit/{tag}\"",
                system_text.len(),
                s.cd(),
                s.claude(),
                quoted.join(" ")
            );
            let mut input = system_text.clone().into_bytes();
            input.extend_from_slice(text.as_bytes());
            (crate::ssh::command(s, &script, forward, None), input)
        }
    };
    let mut child = cmd
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .map_err(|e| format!("{} ({e})", crate::sys::tr("Claude Code 를 실행하지 못했습니다. 설치되어 있는지 확인해 주세요.", "Could not start Claude Code. Check that it is installed.")))?;
    if let Some(mut stdin) = child.stdin.take() {
        let _ = stdin.write_all(&input);
    }
    let stdout = child.stdout.take().ok_or("출력을 받지 못함")?;
    let mut stderr = child.stderr.take();
    let errors = std::thread::spawn(move || {
        let mut s = String::new();
        if let Some(e) = stderr.as_mut() {
            let _ = e.read_to_string(&mut s);
        }
        s
    });
    // 멈춤 버튼: 따로 지켜보다가 Claude Code 를 끝낸다. 서버라면 서버의 Claude Code 도 끝낸다.
    let child = Arc::new(Mutex::new(child));
    let finished = Arc::new(AtomicBool::new(false));
    let remote_pid: Arc<Mutex<Option<u32>>> = Arc::new(Mutex::new(None));
    let watcher = {
        let (child, finished, stop, remote, remote_pid) = (child.clone(), finished.clone(), stop.clone(), remote.clone(), remote_pid.clone());
        std::thread::spawn(move || {
            while !finished.load(Ordering::Relaxed) {
                if stop.load(Ordering::Relaxed) {
                    if let (Some(s), Some(pid)) = (&remote, *remote_pid.lock().unwrap_or_else(|e| e.into_inner())) {
                        let _ = crate::ssh::run(s, &format!("kill {pid} 2>/dev/null; true"), b"", None);
                    }
                    let _ = child.lock().unwrap_or_else(|e| e.into_inner()).kill();
                    return;
                }
                std::thread::sleep(std::time::Duration::from_millis(120));
            }
        })
    };

    let place = remote.as_ref().map(|s| format!(" · {}", s.label())).unwrap_or_default();
    let mut stats = Stats { model: format!("Claude Code{place}"), ..Default::default() };
    let mut failure: Option<String> = None;
    let mut last_text = String::new();
    let mut got_result = false;
    let mut line_open = false; // 마지막으로 보낸 글자가 줄 끝이 아닌가
    for line in BufReader::new(stdout).lines() {
        let Ok(line) = line else { break };
        if let Some(pid) = line.strip_prefix("APERITPID ") {
            *remote_pid.lock().unwrap_or_else(|e| e.into_inner()) = pid.trim().parse().ok();
            continue;
        }
        let Ok(v) = serde_json::from_str::<Value>(&line) else { continue };
        match v["type"].as_str().unwrap_or("") {
            "system" if v["subtype"] == "init" => {
                if let Some(m) = v["model"].as_str() {
                    stats.model = format!("Claude Code · {m}{place}");
                }
            }
            "stream_event" => {
                let e = &v["event"];
                match e["type"].as_str().unwrap_or("") {
                    "content_block_start" => match e["content_block"]["type"].as_str().unwrap_or("") {
                        // 도구를 쓰고 난 뒤 이어지는 글은 새 줄에서 시작해야 한다.
                        "text" if line_open => {
                            send(Msg::Delta("\n".into()));
                            line_open = false;
                        }
                        "tool_use" => send(Msg::Activity(describe(e["content_block"]["name"].as_str().unwrap_or(""), &Value::Null))),
                        _ => {}
                    },
                    "content_block_delta" if e["delta"]["type"] == "text_delta" => {
                        let t = e["delta"]["text"].as_str().unwrap_or("");
                        if !t.is_empty() {
                            if stats.first_ms == 0 {
                                stats.first_ms = started.elapsed().as_millis() as u64;
                            }
                            line_open = !t.ends_with('\n');
                            send(Msg::Delta(t.to_string()));
                        }
                    }
                    _ => {}
                }
            }
            // 완성된 응답: 도구에 넘긴 내용까지 알 수 있으므로 진행 표시를 자세히 고친다.
            "assistant" => {
                for b in v["message"]["content"].as_array().into_iter().flatten() {
                    if b["type"] == "tool_use" {
                        send(Msg::Activity(describe(b["name"].as_str().unwrap_or(""), &b["input"])));
                    }
                }
            }
            "user" => send(Msg::Activity(String::new())),
            "rate_limit_event" => {
                let r = &v["rate_limit_info"];
                let kind = r["rateLimitType"].as_str().unwrap_or("").replace('_', " ");
                let status = r["status"].as_str().unwrap_or("");
                if !kind.is_empty() {
                    stats.limit = if status == "allowed" { kind } else { format!("{kind}: {status}") };
                }
            }
            "result" => {
                got_result = true;
                crate::sys::log("cc.log", &format!("결과 turns={} usage={} model_usage={}", v["num_turns"], v["usage"], v["modelUsage"]));
                let u = &v["usage"];
                let n = |k: &str| u[k].as_u64().unwrap_or(0);
                stats.cached_tokens = n("cache_read_input_tokens");
                stats.input_tokens = n("input_tokens") + n("cache_creation_input_tokens") + stats.cached_tokens;
                stats.output_tokens = n("output_tokens");
                stats.cost_usd = v["total_cost_usd"].as_f64().unwrap_or(0.0);
                last_text = v["result"].as_str().unwrap_or("").to_string();
                if v["is_error"].as_bool().unwrap_or(false) {
                    failure = Some(v["result"].as_str().unwrap_or("Claude Code error").chars().take(400).collect());
                }
            }
            _ => {}
        }
    }
    if line_open {
        send(Msg::Delta("\n".into()));
    }
    finished.store(true, Ordering::Relaxed);
    let _ = watcher.join();
    let status = child.lock().unwrap_or_else(|e| e.into_inner()).wait();
    let errors = errors.join().unwrap_or_default();
    stats.total_ms = started.elapsed().as_millis() as u64;
    if stop.load(Ordering::Relaxed) {
        return Err(crate::agent::STOPPED.to_string());
    }
    if let Some(f) = failure {
        return Err(format!("Claude Code: {f}"));
    }
    if !got_result {
        let code = status.ok().and_then(|s| s.code()).unwrap_or(-1);
        let tail: String = errors.trim().chars().rev().take(300).collect::<String>().chars().rev().collect();
        crate::sys::log("cc.log", &format!("끝남 {code}: {errors}"));
        if remote.is_some() && code == 255 {
            return Err(format!("{}: {}", crate::sys::tr("서버", "Server"), crate::ssh::explain(errors.trim())));
        }
        return Err(format!("{} ({code}) {tail}", crate::sys::tr("Claude Code 가 답 없이 끝났습니다. 로그인되어 있는지 확인해 주세요.", "Claude Code ended without an answer. Check that it is logged in.")));
    }
    Ok((stats, last_text))
}
