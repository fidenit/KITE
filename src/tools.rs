//! AI 가 실제 일을 하는 데 쓰는 도구. 전부 묻지 않고 바로 실행한다 (사용자가 그렇게 정함).

use std::io::Read;
use std::process::{Command, Stdio};
use std::time::{Duration, Instant};

use serde_json::{Value, json};

use crate::vault;

thread_local! {
    /// 이 스레드에서 도는 턴을 사용자가 멈추라고 했는지 보는 신호 (메인 턴의 스레드에만 걸린다)
    static STOP: std::cell::RefCell<Option<std::sync::Arc<std::sync::atomic::AtomicBool>>> = const { std::cell::RefCell::new(None) };
}

pub fn set_stop(flag: std::sync::Arc<std::sync::atomic::AtomicBool>) {
    STOP.with(|s| *s.borrow_mut() = Some(flag));
}

/// 사용자가 지금 턴을 멈추라고 했는가.
pub fn stopped() -> bool {
    STOP.with(|s| s.borrow().as_ref().map(|f| f.load(std::sync::atomic::Ordering::Relaxed)).unwrap_or(false))
}

const SHELL_NOTE: &str = if cfg!(windows) { "Windows PowerShell 5.1" } else { "sh" };

/// API 에 보내는 도구 정의. 순서와 내용이 바뀌면 캐시가 깨지므로 고정해 둔다.
pub fn definitions(worker: bool) -> Value {
    let tool = |name: &str, desc: &str, props: Value, required: &[&str]| {
        json!({
            "name": name,
            "description": desc,
            "input_schema": { "type": "object", "properties": props, "required": required, "additionalProperties": false },
        })
    };
    let all = json!([
        tool(
            "screen",
            "Edit the screen (the board) while you are working with tools. Put board ops into `ops`, one per line. Ordinary text you write while working is not shown to the user, so progress and interim results must go through this tool. Rejected ops come back with the reason.",
            json!({ "ops": { "type": "string", "description": "Board ops, separated by line breaks." } }),
            &["ops"],
        ),
        tool(
            "shell",
            &format!("Run a command on the user's computer ({SHELL_NOTE}). Returns standard output, error output and the exit code. Use it for anything: finding files, running programs, system information, git."),
            json!({
                "command": { "type": "string" },
                "cwd": { "type": "string", "description": "Working folder (the user's home folder if omitted)" },
                "timeout_sec": { "type": "integer", "description": "Default 120" },
            }),
            &["command"],
        ),
        tool(
            "read_file",
            "Read a text file. For a large file read only the part you need with from_line and lines (line numbers are then shown).",
            json!({
                "path": { "type": "string", "description": "Absolute path" },
                "from_line": { "type": "integer", "description": "First line to read (from 1)" },
                "lines": { "type": "integer", "description": "Number of lines to read" },
            }),
            &["path"],
        ),
        tool(
            "route_find",
            "Find the driving route through the stops in order. Returns distance and time per leg and the coordinates of each stop, and saves the shape of the road for the route card. For a request to show a route or an itinerary, do not open a map page: find it with this tool and draw it on a route card.",
            json!({
                "card": { "type": "string", "description": "Id of the route card that will draw this route (a new id, or that of an existing card)" },
                "stops": {
                    "type": "array",
                    "description": "The stops, in the order travelled (2 to 12)",
                    "items": {
                        "type": "object",
                        "properties": {
                            "name": { "type": "string", "description": "Short name shown on the card" },
                            "find": { "type": "string", "description": "Search words to find this place on Kakao Map (name if omitted). A business name with its area is accurate: 강릉 솔향 펜션, 홍천 비발디파크. An address works too" },
                            "lat": { "type": "number" },
                            "lng": { "type": "number" },
                        },
                        "required": ["name"],
                    },
                },
            }),
            &["card", "stops"],
        ),
        tool(
            "place_find",
            "Find coordinates (latitude, longitude) from a place name or an address (OpenStreetMap). A route through several places on a map page needs coordinates. If a business name gives nothing, search again by neighbourhood or street address.",
            json!({ "query": { "type": "string", "description": "A place name or an address. For example: 비발디파크, 양양 낙산사, 강원 양양군 손양면 상운리" } }),
            &["query"],
        ),
        tool(
            "view_image",
            "Look at a picture file (png, jpg, webp, gif, bmp). Use it to look again at a picture the user attached, or to check a screen capture, a drawing or a photo.",
            json!({ "path": { "type": "string", "description": "Absolute path" } }),
            &["path"],
        ),
        tool(
            "edit_file",
            "Change part of a file. Put into `old` the text as it is in the file now (spaces and indentation included) and into `new` the text to replace it. `old` must point at exactly one place in the file, so include enough lines around it. To change an existing file use this tool; do not overwrite it whole with write_file.",
            json!({
                "path": { "type": "string", "description": "Absolute path" },
                "old": { "type": "string" },
                "new": { "type": "string" },
                "all": { "type": "boolean", "description": "true replaces every occurrence of old" },
            }),
            &["path", "old", "new"],
        ),
        tool(
            "find_text",
            "Search for text in every text file under a folder (case-insensitive; .git, target, node_modules and the like are skipped). Returns which file and which line. Use it to find where a function or a setting is in code.",
            json!({
                "path": { "type": "string", "description": "Absolute path of the folder to search" },
                "text": { "type": "string" },
                "ext": { "type": "string", "description": "Only files with this extension (for example rs). All files if omitted" },
            }),
            &["path", "text"],
        ),
        tool(
            "write_file",
            "Create a file, or overwrite it whole. Folders are created as needed.",
            json!({ "path": { "type": "string", "description": "Absolute path" }, "content": { "type": "string" } }),
            &["path", "content"],
        ),
        tool("list_dir", "List what is in a folder.", json!({ "path": { "type": "string", "description": "Absolute path" } }), &["path"]),
        tool(
            "note_search",
            "Search for text in every note and all past records of the memory vault (Obsidian). Use it to recall what the user said or what was done before.",
            json!({ "query": { "type": "string" } }),
            &["query"],
        ),
        tool("note_read", "Read one note of the vault.", json!({ "path": { "type": "string", "description": "Path within the vault. For example: 기억/사용자.md" } }), &["path"]),
        tool(
            "note_write",
            "Create a note in the vault, or rewrite it whole. Facts to keep go under 기억/, summaries of finished work under 작업/.",
            json!({ "path": { "type": "string" }, "content": { "type": "string", "description": "Obsidian Markdown. Put one line of description in the properties at the top." } }),
            &["path", "content"],
        ),
        tool("note_append", "Append to the end of a note in the vault (created if missing).", json!({ "path": { "type": "string" }, "content": { "type": "string" } }), &["path", "content"]),
        tool(
            "browser_text",
            "Read the title, address and body text of the page showing on a web card. Use it to check that the page loaded and what it says.",
            json!({ "card": { "type": "string", "description": "Id of the web card" } }),
            &["card"],
        ),
        tool(
            "browser_eval",
            "Run JavaScript inside the page showing on a web card and get the value of the last expression. Use it to press an element (document.querySelector(…).click()), fill a field, read a value on the page, or scroll. To go to another address use the board op `set <card> url <address>`, not this tool.",
            json!({ "card": { "type": "string", "description": "Id of the web card" }, "script": { "type": "string" } }),
            &["card", "script"],
        ),
        tool(
            "page_open",
            "Open an address in the unseen browser (APERIT's own browser, not shown on the screen). Waits until it has loaded and returns the title, the address and the visible text. \
             Use it when something has to be done on a site (booking, applying, looking something up, a check that needs a login). Logins persist: a site the user logged in to once opens logged in the next time. \
             When a check a person must solve (a captcha) comes up, the program shows it to the user by itself and says so in the result.",
            json!({ "url": { "type": "string", "description": "The address to open" } }),
            &["url"],
        ),
        tool("page_read", "Read again the title, the address and the visible text of the page in the unseen browser.", json!({}), &[]),
        tool(
            "page_run",
            "Run JavaScript inside the page of the unseen browser. Use it to press elements (document.querySelector(…).click()), fill fields and read values. \
             Returns the value of the last expression and, if the page changed, the new page's title, address and the start of its text. \
             A site may not notice a value that is only set, so send input and change events after setting it. \
             Do not press with this the last button that is hard to undo: confirming a booking, paying, signing up, sending, confirming a cancellation (show it with page_show and the user presses it).",
            json!({ "script": { "type": "string" } }),
            &["script"],
        ),
        tool(
            "page_show",
            "Bring the unseen browser onto the board and show it to the user. Use it for a step a person must do (logging in, identity verification, a captcha), \
             and to let the user press the last button that is hard to undo. When the user presses 'Done' or 'Stop' it is hidden again, \
             and the next turn's <events> has a line starting with '브라우저:'. After calling it, do not operate the page; end this turn.",
            json!({ "reason": { "type": "string", "description": "What the user should do. Shown as it is above the window. For example: 'Please log in to Naver', 'Check the details and press Book'" } }),
            &["reason"],
        ),
        tool(
            "job_start",
            "Hand work to a worker. A worker is a separate AI that receives only `order` (the work order) and does not know the conversation so far. The call returns at once and the worker works in the background. Calling it several times makes several workers work at the same time. A progress card appears on the screen by itself, and when the job ends its report comes on the next turn as <job_done>.",
            json!({
                "title": { "type": "string", "description": "A name for the job the user will recognise (it becomes the title of the progress card, 40 characters)" },
                "order": { "type": "string", "description": "The work order. Write it so the work can be done from this alone: goal, background, absolute paths of the files and folders, what to do, what not to do, when it is done, how to check." },
                "dir": { "type": "string", "description": "Absolute path of the working folder (the user's home folder if omitted)" },
                "topic": { "type": "string", "description": "Id of the topic for the progress card (a topic for delegated work if omitted)" },
                "worktree": { "type": "boolean", "description": "true gives this job a branch and a working folder of its own in a git repository. Use it when several workers change the same repository at the same time." },
                "effort": { "type": "string", "enum": ["low", "medium", "high"], "description": "How deeply to think while working. Default medium; high for hard coding" },
                "on": { "type": "string", "description": "Where the worker runs: a name from <places> (a server, claude-code, api). Omit for the default, the first one listed. On a server, `dir` is a folder on that server." },
                "after": { "type": "array", "items": { "type": "string" }, "description": "Ids of jobs that must be done first. This job waits, then starts by itself with their reports added to its order (it does not start if one of them fails). For a step that needs an earlier result, such as a review of a change." },
            }),
            &["title", "order"],
        ),
        tool(
            "board_read",
            "Read all cards of one topic of the board. When <board> came shortened (a topic with a note line starting with #) and you must change that topic's cards or answer from them, read it with this first.",
            json!({ "topic": { "type": "string", "description": "Topic id" } }),
            &["topic"],
        ),
        tool("job_list", "See the state of the delegated jobs.", json!({}), &[]),
        tool(
            "settings",
            "Change a setting of APERIT when the user asks you to. `language`, `theme`, `model`, and a daily limit lower than the one set, change at once. `engine` (who answers, or which saved server), `permission` (what may be done without asking), a higher or no daily limit, and keys are never changed by you: the Settings window opens with the change already made, and it takes effect when the user presses Save there; say so in `say`. Keys and passwords never go through the conversation: if the user wants to give one, use `open` and tell them to paste it in the window. To connect to a server over SSH, use `engine` with `ssh`, or with the address if the user gave one: the window has the fields, a connection test and the password box. Never ask for server details or passwords with a card.",
            json!({
                "change": { "type": "string", "enum": ["language", "theme", "model", "daily_limit", "engine", "permission", "open"] },
                "value": { "type": "string", "description": "language: ko or en. theme: light or dark. model: opus, sonnet or haiku. daily_limit: dollars a day, or none. engine: api, claude-code, local, agent, ssh (a new server), an address such as root@1.2.3.4:22 (a new server, filled in), or the name of a saved server. permission: ask, edits or all. open: nothing" },
            }),
            &["change"],
        ),
        tool(
            "contact_find",
            "Find a person and their phone numbers in the Contacts app by (part of) a name (Mac).",
            json!({ "query": { "type": "string", "description": "A name, or part of one" } }),
            &["query"],
        ),
        tool(
            "message_send",
            "Prepare to send a text message (the Mac Messages app; an ordinary text from the user's number when the iPhone's text forwarding is on). It is not sent at once: output the ops of the confirmation card that come as the result, and the program sends it when the user presses 'send'.",
            json!({
                "to": { "type": "string", "description": "The recipient's name (looked up in Contacts) or phone number" },
                "text": { "type": "string", "description": "The text to send" },
                "topic": { "type": "string", "description": "Id of the topic for the confirmation card (msg if omitted)" },
            }),
            &["to", "text"],
        ),
        tool(
            "phone_call",
            "Prepare to place a call through the iPhone (the user does the talking). It is not placed at once: output the ops of the confirmation card that come as the result, and the call is placed when the user presses 'call'.",
            json!({
                "to": { "type": "string", "description": "A name (looked up in Contacts) or a phone number" },
                "topic": { "type": "string", "description": "Id of the topic for the confirmation card (msg if omitted)" },
            }),
            &["to"],
        ),
        tool(
            "message_read",
            "Read recent text messages (the history of the Mac Messages app). With `who`, only those with that person.",
            json!({
                "who": { "type": "string", "description": "A name or a phone number (everyone if omitted)" },
                "limit": { "type": "integer", "description": "How many at most (default 30)" },
            }),
            &[],
        ),
        tool(
            "job_tell",
            "Pass an additional instruction or the user's answer to a running worker.",
            json!({ "id": { "type": "string", "description": "Job id (for example job3)" }, "message": { "type": "string" } }),
            &["id", "message"],
        ),
        tool("job_stop", "Stop a running job.", json!({ "id": { "type": "string" } }), &["id"]),
        tool("job_result", "Look again at the report of a finished job.", json!({ "id": { "type": "string" } }), &["id"]),
        tool(
            "progress",
            "Update the progress card on the screen the user sees. Call it each time a step ends.",
            json!({
                "text": { "type": "string", "description": "What you are doing now, in words the user will recognise, 60 characters" },
                "pct": { "type": "integer", "description": "How far along the whole is (0 to 100)" },
            }),
            &["text"],
        ),
        { "type": "web_search_20260209", "name": "web_search", "max_uses": 8 },
        { "type": "web_fetch_20260209", "name": "web_fetch", "max_uses": 8 },
    ]);
    // 작업자는 화면과 브라우저를 직접 다루지 않고, 일을 다시 남에게 맡기지도 않는다. 메인은 progress 를 쓰지 않는다.
    let keep = |name: &str| {
        if worker {
            // They may read the board and the other jobs (job_list, job_result), not start or steer them.
            !(name == "screen" || name == "settings" || name == "route_find" || name.starts_with("browser_") || name.starts_with("page_") || matches!(name, "job_start" | "job_tell" | "job_stop") || name == "message_send" || name == "phone_call")
        } else {
            name != "progress"
        }
    };
    Value::Array(all.as_array().unwrap().iter().filter(|t| keep(t["name"].as_str().unwrap_or(""))).cloned().collect())
}

fn clip(s: &str, max: usize) -> String {
    if s.chars().count() <= max {
        return s.to_string();
    }
    let head: String = s.chars().take(max * 2 / 3).collect();
    let tail: String = s.chars().rev().take(max / 3).collect::<Vec<_>>().into_iter().rev().collect();
    format!("{head}\n… (가운데 생략, 전체 {}자) …\n{tail}", s.chars().count())
}

fn arg<'a>(input: &'a Value, key: &str) -> Result<&'a str, String> {
    input[key].as_str().ok_or_else(|| format!("'{key}' 가 필요함"))
}

/// 화면 아래에 띄울 한 줄 설명 ("명령 실행: git status").
pub fn describe(name: &str, input: &Value) -> String {
    let short = |key: &str| -> String {
        let s = input[key].as_str().unwrap_or("").replace('\n', " ");
        if s.chars().count() > 70 { s.chars().take(70).collect::<String>() + "…" } else { s }
    };
    match name {
        "shell" => format!("명령 실행: {}", short("command")),
        "read_file" => format!("파일 읽기: {}", short("path")),
        "write_file" => format!("파일 쓰기: {}", short("path")),
        "list_dir" => format!("폴더 보기: {}", short("path")),
        "edit_file" => format!("파일 고치기: {}", short("path")),
        "view_image" => format!("그림 보기: {}", short("path")),
        "place_find" => format!("장소 찾기: {}", short("query")),
        "route_find" => "길 찾기".to_string(),
        "find_text" => format!("글자 찾기: {}", short("text")),
        "job_start" => format!("작업 맡기기: {}", short("title")),
        "job_list" => "맡긴 작업 확인".to_string(),
        "settings" => format!("설정: {} {}", short("change"), short("value")),
        "board_read" => format!("보드 읽기: {}", short("topic")),
        "job_tell" => format!("작업자에게 전하기: {}", short("id")),
        "job_stop" => format!("작업 멈추기: {}", short("id")),
        "job_result" => format!("작업 결과 보기: {}", short("id")),
        "progress" => format!("진행: {}", short("text")),
        "note_search" => format!("기억 찾기: {}", short("query")),
        "note_read" => format!("기억 읽기: {}", short("path")),
        "note_write" => format!("기억 쓰기: {}", short("path")),
        "note_append" => format!("기억 덧붙이기: {}", short("path")),
        "browser_text" => format!("페이지 읽기: {}", short("card")),
        "browser_eval" => format!("페이지 조작: {}", short("script")),
        "page_open" => format!("사이트 여는 중: {}", short("url")),
        "page_read" => "사이트 읽는 중".to_string(),
        "page_run" => "사이트에서 누르고 채우는 중".to_string(),
        "page_show" => format!("브라우저 보여 주기: {}", short("reason")),
        "web_search" => format!("웹 검색: {}", short("query")),
        "web_fetch" => format!("웹 페이지 읽기: {}", short("url")),
        "contact_find" => format!("연락처 찾기: {}", short("query")),
        "message_send" => format!("문자 준비: {}", short("to")),
        "phone_call" => format!("전화 준비: {}", short("to")),
        "message_read" => "문자 읽기".to_string(),
        other => other.to_string(),
    }
}

/// 도구 하나를 실행한다. 반환값은 (결과 글, 오류인가). `screen` 은 화면 쪽에서 따로 처리한다.
pub fn execute(name: &str, input: &Value) -> (String, bool) {
    let result: Result<String, String> = (|| match name {
        "shell" => shell(arg(input, "command")?, input["cwd"].as_str(), input["timeout_sec"].as_u64().unwrap_or(120)),
        "read_file" => {
            let bytes = std::fs::read(arg(input, "path")?).map_err(|e| e.to_string())?;
            let text = String::from_utf8_lossy(&bytes);
            let (from, count) = (input["from_line"].as_u64(), input["lines"].as_u64());
            if from.is_none() && count.is_none() {
                return Ok(clip(&text, 60_000));
            }
            let from = from.unwrap_or(1).max(1) as usize;
            let total = text.lines().count();
            let part: Vec<String> =
                text.lines().enumerate().skip(from - 1).take(count.unwrap_or(400) as usize).map(|(n, l)| format!("{:>5}  {l}", n + 1)).collect();
            Ok(format!("{}\n(전체 {total}줄)", clip(&part.join("\n"), 60_000)))
        }
        "edit_file" => {
            let path = arg(input, "path")?;
            let text = std::fs::read_to_string(path).map_err(|e| format!("{path}: {e}"))?;
            let (mut old, mut new) = (arg(input, "old")?.to_string(), arg(input, "new")?.to_string());
            if old.is_empty() {
                return Err("old 가 비어 있음".into());
            }
            // 파일의 줄 끝이 CRLF 인데 LF 로 적어 왔으면 맞춰 준다.
            if !text.contains(&old) && text.contains("\r\n") {
                old = old.replace("\r\n", "\n").replace('\n', "\r\n");
                new = new.replace("\r\n", "\n").replace('\n', "\r\n");
            }
            let found = text.matches(&old).count();
            let all = input["all"].as_bool().unwrap_or(false);
            if found == 0 {
                return Err("old 의 글이 파일에 없음. 공백과 들여쓰기까지 같아야 한다. read_file 로 그 부분을 다시 읽고 그대로 옮겨 적는다".into());
            }
            if found > 1 && !all {
                return Err(format!("old 의 글이 {found}군데 있음. 앞뒤 줄을 더 넣어 한 군데만 가리키게 하거나 all 을 true 로 준다"));
            }
            let out = if all { text.replace(&old, &new) } else { text.replacen(&old, &new, 1) };
            std::fs::write(path, out).map_err(|e| e.to_string())?;
            Ok(format!("고침: {path} ({}군데)", if all { found } else { 1 }))
        }
        "find_text" => {
            let root = std::path::PathBuf::from(arg(input, "path")?);
            let needle = arg(input, "text")?.to_lowercase();
            if needle.is_empty() {
                return Err("text 가 비어 있음".into());
            }
            let ext = input["ext"].as_str().map(|e| e.trim_start_matches('.').to_lowercase()).filter(|e| !e.is_empty());
            let mut hits = Vec::new();
            let mut stack = vec![root.clone()];
            while let Some(dir) = stack.pop() {
                let Ok(rd) = std::fs::read_dir(&dir) else { continue };
                for e in rd.flatten() {
                    let p = e.path();
                    let name = e.file_name().to_string_lossy().to_string();
                    if p.is_dir() {
                        if ![".git", "target", "node_modules", ".venv", "dist", "build", "__pycache__"].contains(&name.as_str()) {
                            stack.push(p);
                        }
                        continue;
                    }
                    if let Some(want) = &ext {
                        if p.extension().map(|x| x.to_string_lossy().to_lowercase()) != Some(want.clone()) {
                            continue;
                        }
                    }
                    if e.metadata().map(|m| m.len() > 2_000_000).unwrap_or(true) {
                        continue;
                    }
                    let Ok(text) = std::fs::read_to_string(&p) else { continue }; // 글이 아닌 파일은 건너뛴다
                    for (n, line) in text.lines().enumerate() {
                        if line.to_lowercase().contains(&needle) {
                            let snippet: String = line.trim().chars().take(160).collect();
                            hits.push(format!("{}:{}: {snippet}", p.strip_prefix(&root).unwrap_or(&p).display(), n + 1));
                        }
                    }
                }
            }
            if hits.is_empty() {
                return Ok("찾는 글이 없음".into());
            }
            hits.sort();
            let total = hits.len();
            hits.truncate(120);
            Ok(format!("{}\n(총 {total}건)", hits.join("\n")))
        }
        "route_find" => crate::route::find(input),
        "contact_find" => crate::phone::find(arg(input, "query")?),
        "message_send" => crate::phone::prepare_message(arg(input, "to")?, arg(input, "text")?, input["topic"].as_str().unwrap_or("msg")),
        "phone_call" => crate::phone::prepare_call(arg(input, "to")?, input["topic"].as_str().unwrap_or("msg")),
        "message_read" => crate::phone::read(input["who"].as_str().unwrap_or(""), input["limit"].as_u64().unwrap_or(30) as usize),
        "place_find" => {
            let query = arg(input, "query")?;
            let encoded: String = query.bytes().map(|b| if b.is_ascii_alphanumeric() { (b as char).to_string() } else { format!("%{b:02X}") }).collect();
            let url = format!("https://nominatim.openstreetmap.org/search?q={encoded}&format=jsonv2&limit=6&accept-language=ko");
            let mut resp = crate::agent::http().get(&url).header("user-agent", "APERIT-desktop/0.1").call().map_err(|e| format!("연결 실패: {e}"))?;
            let text = resp.body_mut().read_to_string().map_err(|e| e.to_string())?;
            let found: Value = serde_json::from_str(&text).map_err(|_| format!("장소 찾기 응답을 읽지 못함: {}", clip(&text, 200)))?;
            let rows: Vec<String> = found
                .as_array()
                .map(|a| {
                    a.iter()
                        .map(|p| {
                            let num = |k: &str| p[k].as_str().and_then(|v| v.parse::<f64>().ok()).unwrap_or(0.0);
                            format!("{} — 위도 {:.6}, 경도 {:.6} ({})", p["display_name"].as_str().unwrap_or("?"), num("lat"), num("lon"), p["type"].as_str().unwrap_or(""))
                        })
                        .collect()
                })
                .unwrap_or_default();
            if rows.is_empty() {
                return Ok(format!("'{query}' 로는 찾지 못함. 상호 대신 동네 이름이나 도로명 주소로 다시 찾아 본다"));
            }
            Ok(rows.join("\n"))
        }
        "job_start" | "job_list" | "job_tell" | "job_stop" | "job_result" => crate::jobs::tool(name, input),
        "write_file" => {
            let path = std::path::PathBuf::from(arg(input, "path")?);
            if let Some(dir) = path.parent() {
                std::fs::create_dir_all(dir).map_err(|e| e.to_string())?;
            }
            let content = arg(input, "content")?;
            std::fs::write(&path, content).map_err(|e| e.to_string())?;
            Ok(format!("저장함: {} ({}자)", path.display(), content.chars().count()))
        }
        "list_dir" => {
            let mut rows = Vec::new();
            for e in std::fs::read_dir(arg(input, "path")?).map_err(|e| e.to_string())?.flatten() {
                let meta = e.metadata().ok();
                let is_dir = meta.as_ref().map(|m| m.is_dir()).unwrap_or(false);
                let size = meta.map(|m| m.len()).unwrap_or(0);
                rows.push(if is_dir { format!("[폴더] {}", e.file_name().to_string_lossy()) } else { format!("{} ({size} 바이트)", e.file_name().to_string_lossy()) });
            }
            rows.sort();
            let total = rows.len();
            rows.truncate(400);
            Ok(format!("{}\n(총 {total}개)", rows.join("\n")))
        }
        "note_search" => Ok(vault::search(arg(input, "query")?)),
        "note_read" => vault::read(arg(input, "path")?).map(|t| clip(&t, 40_000)),
        "note_write" => vault::write(arg(input, "path")?, arg(input, "content")?),
        "note_append" => vault::append(arg(input, "path")?, arg(input, "content")?),
        other => Err(format!("모르는 도구 '{other}'")),
    })();
    match result {
        Ok(s) => (if s.is_empty() { "(출력 없음)".into() } else { s }, false),
        Err(e) => (e, true),
    }
}

pub fn shell(command: &str, cwd: Option<&str>, timeout_sec: u64) -> Result<String, String> {
    let home = std::env::var(if cfg!(windows) { "USERPROFILE" } else { "HOME" }).unwrap_or_else(|_| ".".into());
    let mut cmd;
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        cmd = Command::new("powershell.exe");
        // 출력이 한글로 깨지지 않게 UTF-8 로 맞춘다.
        let wrapped = format!("[Console]::OutputEncoding = [Text.Encoding]::UTF8; $ProgressPreference = 'SilentlyContinue'; {command}");
        cmd.args(["-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", &wrapped]);
        cmd.creation_flags(0x0800_0000); // 콘솔 창을 띄우지 않는다
    }
    #[cfg(not(windows))]
    {
        cmd = Command::new("sh");
        cmd.args(["-c", command]);
    }
    let mut child = cmd
        .current_dir(cwd.filter(|c| !c.is_empty()).unwrap_or(&home))
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .map_err(|e| format!("실행 실패: {e}"))?;
    // 출력이 많아도 막히지 않도록 따로 읽는다.
    let mut out_pipe = child.stdout.take().unwrap();
    let mut err_pipe = child.stderr.take().unwrap();
    let out_t = std::thread::spawn(move || {
        let mut b = Vec::new();
        let _ = out_pipe.read_to_end(&mut b);
        b
    });
    let err_t = std::thread::spawn(move || {
        let mut b = Vec::new();
        let _ = err_pipe.read_to_end(&mut b);
        b
    });
    let started = Instant::now();
    let limit = Duration::from_secs(timeout_sec.clamp(1, 1800));
    let status = loop {
        match child.try_wait().map_err(|e| e.to_string())? {
            Some(s) => break Some(s),
            None if started.elapsed() > limit || stopped() => {
                let _ = child.kill();
                let _ = child.wait();
                break None;
            }
            None => std::thread::sleep(Duration::from_millis(40)),
        }
    };
    let out = String::from_utf8_lossy(&out_t.join().unwrap_or_default()).to_string();
    let err = String::from_utf8_lossy(&err_t.join().unwrap_or_default()).to_string();
    let mut text = clip(out.trim_end(), 20_000);
    if !err.trim().is_empty() {
        text += &format!("\n[오류 출력]\n{}", clip(err.trim_end(), 6_000));
    }
    match status {
        Some(s) if s.success() => Ok(text),
        Some(s) => Err(format!("{text}\n[종료 코드 {}]", s.code().unwrap_or(-1))),
        None if stopped() => Err(format!("{text}\n[사용자가 멈춰서 중단함]")),
        None => Err(format!("{text}\n[{timeout_sec}초 제한 시간 초과로 중단함]")),
    }
}
