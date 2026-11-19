//! 맡긴 작업. 메인 AI 가 작업 지시서를 쓰면, 지시서마다 작업자(따로 도는 대화)가 하나씩 붙어 동시에 일한다.
//! 메인은 기다리지 않고 사용자와 계속 대화하고, 작업자가 끝나면 보고서가 메인에게 돌아온다.
//! A job can wait for others (`after`): it starts by itself when they are done, with their reports added to its
//! order, so a change and its review, or the steps of a pipeline, are handed over at once. Workers can read the
//! board and the other jobs' reports while they work.
//!
//!   지시서와 결과  보관함 `작업/지시서/<작업ID> <제목>.md`
//!   진행 상황      화면의 진행 카드 (카드 ID = 작업 ID)

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::Sender;
use std::sync::{Arc, Mutex};
use std::time::Instant;

use serde_json::Value;

use crate::llm::{Config, Msg};
use crate::{agent, sys, tools, vault};

/// The instructions for a worker, in the same language as the main instructions.
fn worker_prompt() -> &'static str {
    if crate::sys::korean_prompt() { include_str!("worker.ko.md") } else { include_str!("worker.md") }
}
/// 작업자 하나가 도구를 주고받는 횟수의 상한
const MAX_STEPS: usize = 200;

/// Where a worker runs.
#[derive(Clone)]
enum Place {
    /// APERIT's own loop with the Claude API key
    Api,
    /// APERIT's own loop with the OpenAI-compatible service
    OpenAi,
    /// The Claude Code on this computer
    Here,
    /// The Claude Code on a saved server
    Server(crate::ssh::Server),
}

impl Place {
    fn name(&self) -> String {
        match self {
            Place::Api => "api".into(),
            Place::OpenAi => "openai".into(),
            Place::Here => "claude-code".into(),
            Place::Server(s) => s.id(),
        }
    }
}

/// Where the main engine itself runs: a worker goes there unless told otherwise.
fn default_place(cfg: &Config) -> Option<Place> {
    if cfg.ssh {
        crate::ssh::settings().map(Place::Server)
    } else if cfg.cc {
        Some(Place::Here)
    } else if cfg.openai.is_some() {
        Some(Place::OpenAi)
    } else if cfg.key.is_some() {
        Some(Place::Api)
    } else {
        None
    }
}

/// Every place a worker can be sent to now, the default first: (name, what it is).
fn places(cfg: &Config) -> Vec<(String, String)> {
    let mut out: Vec<(Place, String)> = Vec::new();
    if cfg.key.is_some() {
        out.push((Place::Api, format!("Claude API ({}), APERIT's own tools", cfg.model)));
    }
    if let Some(p) = &cfg.openai {
        out.push((Place::OpenAi, format!("{} at {}", p.model, p.url)));
    }
    if crate::cc::version().is_some() {
        out.push((Place::Here, "Claude Code on this computer".into()));
    }
    for s in crate::ssh::servers().0 {
        let what = format!("Claude Code on the server {} (its files, Linux shell)", s.label());
        out.push((Place::Server(s), what));
    }
    let first = default_place(cfg).map(|p| p.name());
    out.sort_by_key(|(p, _)| Some(p.name()) != first);
    out.into_iter().map(|(p, what)| (p.name(), what)).collect()
}

/// For the main model's turn: where workers can go, when there is more than one place.
pub fn places_text(cfg: &Config) -> String {
    let all = places(cfg);
    if all.len() < 2 {
        return String::new();
    }
    all.iter().enumerate().map(|(i, (name, what))| format!("{name}: {what}{}\n", if i == 0 { " (the default)" } else { "" })).collect()
}

/// The place named in `on` (empty: the default).
fn place_named(cfg: &Config, on: &str) -> Result<Place, String> {
    let on = on.trim();
    if on.is_empty() || on == "here" || on == "default" {
        return default_place(cfg).ok_or_else(|| "API 키가 없어 작업자를 띄울 수 없음".to_string());
    }
    match on {
        "api" if cfg.key.is_some() => Ok(Place::Api),
        "openai" if cfg.openai.is_some() => Ok(Place::OpenAi),
        "claude-code" if crate::cc::version().is_some() => Ok(Place::Here),
        _ => crate::ssh::find(on).map(Place::Server).ok_or_else(|| {
            let names: Vec<String> = places(cfg).into_iter().map(|p| p.0).collect();
            format!("'{on}' 라는 곳이 없음. 고를 수 있는 곳: {}", names.join(", "))
        }),
    }
}

pub enum Event {
    /// `place`: where the worker runs, written as the card's `by` (shown when cards name more than one writer).
    Started { id: String, title: String, topic: Option<String>, place: String },
    Progress { id: String, pct: Option<u8>, text: Option<String> },
    Finished { id: String, title: String, ok: bool, report: String },
}

struct Job {
    id: String,
    title: String,
    /// Where it runs (a name from `places`)
    place: String,
    dir: String,
    note: String,
    /// 기다림 (for the jobs in `after`) / 진행 중 / 끝남 / 실패 / 중단함
    state: &'static str,
    /// 가장 최근에 한 일 한 줄
    last: String,
    report: String,
    started: Instant,
    took_secs: u64,
    stop: Arc<AtomicBool>,
    inbox: Arc<Mutex<Vec<String>>>,
}

static JOBS: Mutex<Vec<Job>> = Mutex::new(Vec::new());
type Ui = (Sender<Msg>, Box<dyn Fn() + Send>);
static UI: Mutex<Option<Ui>> = Mutex::new(None);

fn jobs() -> std::sync::MutexGuard<'static, Vec<Job>> {
    JOBS.lock().unwrap_or_else(|e| e.into_inner())
}

/// 화면 쪽으로 소식을 보낼 길을 정해 둔다 (프로그램이 뜰 때 한 번).
pub fn init(tx: Sender<Msg>, wake: impl Fn() + Send + 'static) {
    *UI.lock().unwrap_or_else(|e| e.into_inner()) = Some((tx, Box::new(wake)));
}

/// 화면 쪽 스레드에 요청을 보낸다 (도구가 브라우저 같은 화면 쪽 기능을 써야 할 때).
pub fn to_ui(m: Msg) -> bool {
    match UI.lock().unwrap_or_else(|e| e.into_inner()).as_ref() {
        Some((tx, wake)) => {
            let ok = tx.send(m).is_ok();
            wake();
            ok
        }
        None => false,
    }
}

fn emit(ev: Event) {
    if let Some((tx, wake)) = UI.lock().unwrap_or_else(|e| e.into_inner()).as_ref() {
        let _ = tx.send(Msg::Job(ev));
        wake();
    }
}

/// Wait until every job in `after` is done. Returns their reports, to add to the order; an error when one of
/// them failed or was stopped (then this job does not start), or when this job is stopped while it waits.
fn wait_for(id: &str, after: &[String], stop: &Arc<AtomicBool>) -> Result<String, String> {
    if after.is_empty() {
        return Ok(String::new());
    }
    let waits = format!("{}: {}", crate::sys::tr("먼저 끝나야 하는 작업", "Waits for"), after.join(", "));
    set(id, |j| j.last = waits.clone());
    emit(Event::Progress { id: id.to_string(), pct: None, text: Some(waits) });
    loop {
        if stop.load(Ordering::Relaxed) {
            return Err("시작하기 전에 중단함".into());
        }
        let all = jobs();
        let of = |a: &String| all.iter().find(|j| &j.id == a);
        if let Some(j) = after.iter().filter_map(of).find(|j| matches!(j.state, "실패" | "중단함")) {
            return Err(format!("시작하지 않음: 먼저 끝나야 하는 작업 {} ({}) 이 {} 상태로 끝남", j.id, j.title, j.state));
        }
        if after.iter().filter_map(of).all(|j| j.state == "끝남") {
            let earlier: String = after.iter().filter_map(of).map(|j| format!("<earlier id=\"{}\" title=\"{}\">\n{}\n</earlier>\n", j.id, j.title, j.report)).collect();
            drop(all);
            set(id, |j| j.state = "진행 중");
            emit(Event::Progress { id: id.to_string(), pct: None, text: Some(crate::sys::tr("시작함", "Started").into()) });
            return Ok(format!("\n<earlier_jobs>\nThe reports of the jobs this one waited for:\n{earlier}</earlier_jobs>"));
        }
        drop(all);
        std::thread::sleep(std::time::Duration::from_millis(500));
    }
}

fn next_id() -> String {
    let path = sys::data_dir().join("job.seq");
    let n = std::fs::read_to_string(&path).ok().and_then(|s| s.trim().parse::<u32>().ok()).unwrap_or(0) + 1;
    let _ = std::fs::write(&path, n.to_string());
    format!("job{n}")
}

pub fn running() -> usize {
    jobs().iter().filter(|j| matches!(j.state, "진행 중" | "기다림")).count()
}

/// 지금 돌고 있는 작업 (매 턴 메인 모델에게 보여 준다).
pub fn summary() -> String {
    let mut out = String::new();
    for j in jobs().iter().filter(|j| matches!(j.state, "진행 중" | "기다림")) {
        if j.state == "기다림" {
            out += &format!("{} [기다림] {} — {}\n", j.id, j.title, j.last);
        } else {
            out += &format!("{} [{}분째] {} — 지금: {}\n", j.id, j.started.elapsed().as_secs() / 60, j.title, if j.last.is_empty() { "시작하는 중" } else { &j.last });
        }
    }
    out
}

fn set<F: FnOnce(&mut Job)>(id: &str, f: F) {
    if let Some(j) = jobs().iter_mut().find(|j| j.id == id) {
        f(j);
    }
}

/// 메인 모델이 부르는 작업 도구.
pub fn tool(name: &str, input: &Value) -> Result<String, String> {
    let id = input["id"].as_str().unwrap_or("").trim().to_string();
    match name {
        "job_start" => start(input),
        "job_list" => {
            let all = jobs();
            if all.is_empty() {
                return Ok("맡긴 작업 없음".into());
            }
            Ok(all
                .iter()
                .map(|j| {
                    let time = match j.state {
                "진행 중" => format!("{}분째", j.started.elapsed().as_secs() / 60),
                "기다림" => "시작 전".to_string(),
                _ => format!("{}초 걸림", j.took_secs),
            };
                    format!("{} [{} · {time}] {} — {} (곳: {}, 작업 폴더: {}, 지시서: {})", j.id, j.state, j.title, if j.last.is_empty() { "-" } else { &j.last }, j.place, j.dir, j.note)
                })
                .collect::<Vec<_>>()
                .join("\n"))
        }
        "job_tell" => {
            let msg = input["message"].as_str().unwrap_or("").trim().to_string();
            let all = jobs();
            let j = all.iter().find(|j| j.id == id).ok_or_else(|| format!("'{id}' 라는 작업이 없음"))?;
            if !matches!(j.state, "진행 중" | "기다림") {
                return Err(format!("{id} 는 이미 {} 상태라 전할 수 없음. 이어서 시키려면 job_start 로 새 작업을 맡긴다", j.state));
            }
            j.inbox.lock().unwrap_or_else(|e| e.into_inner()).push(msg);
            Ok(format!("{id} 에게 전함 (작업자가 지금 하던 단계를 마치는 대로 읽는다)"))
        }
        "job_stop" => {
            let all = jobs();
            let j = all.iter().find(|j| j.id == id).ok_or_else(|| format!("'{id}' 라는 작업이 없음"))?;
            j.stop.store(true, Ordering::Relaxed);
            Ok(format!("{id} 에게 멈추라고 함 (지금 하던 단계를 마치면 멈춘다)"))
        }
        "job_result" => {
            let all = jobs();
            let j = all.iter().find(|j| j.id == id).ok_or_else(|| format!("'{id}' 라는 작업이 없음"))?;
            Ok(match j.state {
                "진행 중" => format!("아직 진행 중. 지금: {}", j.last),
                "기다림" => format!("아직 시작 전. {}", j.last),
                _ => format!("[{}]\n{}", j.state, j.report),
            })
        }
        other => Err(format!("모르는 도구 '{other}'")),
    }
}

fn start(input: &Value) -> Result<String, String> {
    let title = input["title"].as_str().unwrap_or("").trim().to_string();
    let order = input["order"].as_str().unwrap_or("").trim().to_string();
    if title.is_empty() || order.is_empty() {
        return Err("title 과 order 가 필요함".into());
    }
    let mut cfg = Config::load();
    // 작업자는 지금 엔진으로 돈다 (`on` 으로 다른 곳을 고르지 않으면): Claude Code 엔진이면 Claude Code 로, 서버면 그 서버로, 아니면 API 키로.
    let place = place_named(&cfg, input["on"].as_str().unwrap_or(""))?;
    let key = cfg.key.clone().unwrap_or_default();
    // The jobs this one waits for. A list, or ids in one string.
    let after: Vec<String> = match &input["after"] {
        Value::Array(a) => a.iter().filter_map(|v| v.as_str()).map(|s| s.trim().to_string()).filter(|s| !s.is_empty()).collect(),
        Value::String(s) => s.split([',', ' ']).map(str::trim).filter(|s| !s.is_empty()).map(String::from).collect(),
        _ => Vec::new(),
    };
    {
        let all = jobs();
        for a in &after {
            match all.iter().find(|j| &j.id == a) {
                None => return Err(format!("'{a}' 라는 작업이 없음 (after)")),
                Some(j) if matches!(j.state, "실패" | "중단함") => return Err(format!("{a} 는 {} 상태로 끝나서 기다릴 수 없음", j.state)),
                _ => {}
            }
        }
    }
    cfg.effort = match input["effort"].as_str() {
        Some(e) if ["low", "medium", "high"].contains(&e) => e.to_string(),
        _ => "medium".into(),
    };
    let home = std::env::var(if cfg!(windows) { "USERPROFILE" } else { "HOME" }).unwrap_or_else(|_| ".".into());
    let asked_dir = input["dir"].as_str().map(str::trim).filter(|d| !d.is_empty());
    // On a server the folder is the server's: the one given, or the server's own working folder.
    let place = match place {
        Place::Server(mut s) => {
            if let Some(d) = asked_dir {
                s.dir = d.to_string();
            }
            if input["worktree"].as_bool().unwrap_or(false) {
                return Err("worktree 는 이 컴퓨터의 작업자만 쓸 수 있음. 서버에서는 dir 로 따로 떼어 둔 폴더를 준다".into());
            }
            Place::Server(s)
        }
        other => other,
    };
    let remote = matches!(place, Place::Server(_));
    let mut dir = match &place {
        Place::Server(s) => if s.dir.trim().is_empty() { "~".to_string() } else { s.dir.trim().to_string() },
        _ => asked_dir.unwrap_or(&home).to_string(),
    };
    if !remote && !std::path::Path::new(&dir).is_dir() {
        std::fs::create_dir_all(&dir).map_err(|e| format!("작업 폴더를 만들 수 없음: {e}"))?;
    }
    let id = next_id();
    // 같은 저장소를 여러 작업자가 동시에 고치면 서로 덮어쓴다. 요청하면 작업자마다 따로 떼어 낸 작업 폴더(git worktree)를 준다.
    let mut note_extra = String::new();
    if input["worktree"].as_bool().unwrap_or(false) {
        let top = tools::shell("git rev-parse --show-toplevel", Some(&dir), 20).map_err(|e| format!("worktree 를 쓰려면 git 저장소여야 함: {e}"))?;
        let top = top.trim().replace('/', if cfg!(windows) { "\\" } else { "/" });
        let wt = format!("{}-{id}", top.trim_end_matches(['\\', '/']));
        tools::shell(&format!("git worktree add -b 'kite/{id}' '{wt}'"), Some(&top), 120).map_err(|e| format!("git worktree 를 만들지 못함: {e}"))?;
        note_extra = format!("- 원래 저장소: {top}\n- 따로 떼어 낸 작업 폴더 (브랜치 kite/{id}): {wt}\n");
        dir = wt;
    }
    let safe: String = title.chars().map(|c| if "\\/:*?\"<>|#^[]".contains(c) { ' ' } else { c }).take(40).collect();
    let note = format!("작업/지시서/{id} {}.md", safe.trim());
    let (date, time) = sys::now_local();
    let _ = vault::write(
        &note,
        &format!("---\ndescription: 작업 지시서 {id} — {title}\ncreated: {date}\ntags:\n  - kite/작업\n---\n# {title}\n\n- 작업 ID: {id}\n- 맡긴 때: {date} {time}\n- 작업 폴더: {dir}\n{note_extra}\n## 지시\n\n{order}\n"),
    );
    let stop = Arc::new(AtomicBool::new(false));
    let inbox = Arc::new(Mutex::new(Vec::new()));
    jobs().push(Job {
        id: id.clone(),
        title: title.clone(),
        place: place.name(),
        dir: dir.clone(),
        note: note.clone(),
        state: if after.is_empty() { "진행 중" } else { "기다림" },
        last: String::new(),
        report: String::new(),
        started: Instant::now(),
        took_secs: 0,
        stop: stop.clone(),
        inbox: inbox.clone(),
    });
    emit(Event::Started { id: id.clone(), title: title.clone(), topic: input["topic"].as_str().map(str::to_string).filter(|t| !t.is_empty()), place: place.name() });
    let first = format!(
        "<order id=\"{id}\" title=\"{title}\">\n{order}\n</order>\n<env>\n지금: {date} {time}\n운영체제: {}\n작업 폴더: {dir}\n{note_extra}지시서 노트: {note}\n기억 보관함 폴더: {}\n</env>",
        match &place {
            Place::Server(s) => format!("서버 {} (작업은 서버에서, 기억 보관함은 사용자의 컴퓨터에)", s.target()),
            _ if cfg!(windows) => "Windows (shell 은 Windows PowerShell 5.1)".to_string(),
            _ => "macOS".to_string(),
        },
        vault::root().display()
    );
    if !matches!((&place, default_place(&cfg)), (p, Some(d)) if p.name() == d.name()) {
        emit(Event::Progress { id: id.clone(), pct: None, text: Some(format!("{}: {}", crate::sys::tr("도는 곳", "Runs on"), place.name())) });
    }
    let (jid, jtitle, jdir, jnote) = (id.clone(), title.clone(), dir.clone(), note.clone());
    let place_name = place.name();
    let after_text = after.join(", ");
    std::thread::spawn(move || {
        let id = jid;
        // Wait for the jobs in `after`, then add their reports to the order.
        let waited = wait_for(&id, &after, &stop);
        let started = Instant::now();
        set(&id, |j| j.started = started);
        let send = |m: Msg| match m {
            Msg::Activity(a) => {
                if !a.is_empty() {
                    set(&id, |j| j.last = a);
                }
            }
            // Reading the board is done by the screen thread.
            other => {
                to_ui(other);
            }
        };
        let check = || {
            if stop.load(Ordering::Relaxed) {
                return Err("중단함".to_string());
            }
            let said: Vec<String> = inbox.lock().unwrap_or_else(|e| e.into_inner()).drain(..).collect();
            Ok(if said.is_empty() { None } else { Some(format!("<from_main>\n{}\n</from_main>", said.join("\n"))) })
        };
        let tool = |name: &str, input: &Value| -> Option<(String, bool)> {
            match name {
                "progress" => {
                    let text = input["text"].as_str().map(|t| t.replace('\n', " ")).filter(|t| !t.trim().is_empty());
                    if let Some(t) = &text {
                        set(&id, |j| j.last = t.clone());
                    }
                    emit(Event::Progress { id: id.clone(), pct: input["pct"].as_u64().map(|p| p.min(100) as u8), text });
                    Some(("화면에 반영됨".into(), false))
                }
                // 작업 폴더를 따로 주지 않은 명령은 이 작업의 폴더에서 실행한다.
                "shell" if input["cwd"].as_str().map(|c| c.trim().is_empty()).unwrap_or(true) => {
                    let mut with = input.clone();
                    with["cwd"] = jdir.clone().into();
                    Some(tools::execute("shell", &with))
                }
                _ => None,
            }
        };
        let first = match &waited {
            Ok(earlier) => format!("{first}{earlier}"),
            Err(_) => first,
        };
        let result = match (&waited, &place) {
            (Err(e), _) => Err(e.clone()),
            (_, Place::Here | Place::Server(_)) => {
                // Claude Code 가 하는 일을 진행 카드에 그대로 보여 준다 (이 엔진에는 progress 도구가 없다).
                let shown = |m: Msg| {
                    if let Msg::Activity(a) = m {
                        if !a.is_empty() {
                            set(&id, |j| j.last = a.clone());
                            emit(Event::Progress { id: id.clone(), pct: None, text: Some(a) });
                        }
                    }
                };
                let server = if let Place::Server(s) = &place { Some(s.clone()) } else { None };
                crate::cc::worker(&id, worker_prompt(), &jdir, &cfg.effort, &first, server, &stop, &shown)
            }
            (_, Place::OpenAi | Place::Api) => {
                let role = agent::Role { system: worker_prompt(), tools: tools::definitions(true), max_steps: MAX_STEPS, check: &check, tool: &tool };
                match (&place, &cfg.openai) {
                    (Place::OpenAi, Some(p)) => crate::openai::run_as(p, &role, &first, &[], &send),
                    _ => agent::run_as(&cfg, &key, &role, &first, &[], &send),
                }
            }
        };
        let secs = started.elapsed().as_secs();
        let (state, ok, report) = match result {
            Ok((stats, text)) => {
                sys::log("jobs.log", &format!("{id} 끝남: {secs}초, 입력 {} (캐시 {}) 출력 {} 토큰", stats.input_tokens, stats.cached_tokens, stats.output_tokens));
                ("끝남", true, if text.trim().is_empty() { "결과: 작업자가 보고서 없이 끝냄".to_string() } else { text.trim().to_string() })
            }
            Err(e) => {
                sys::log("jobs.log", &format!("{id} 실패: {secs}초, {e}"));
                if stop.load(Ordering::Relaxed) { ("중단함", false, "결과: 도중에 멈춤 (메인이 중단시킴)".to_string()) } else { ("실패", false, format!("결과: 작업자가 끝내지 못함\n오류: {e}")) }
            }
        };
        let (_, time) = sys::now_local();
        let _ = vault::append(&jnote, &format!("\n## 결과 ({state}, {time}, {secs}초)\n\n{report}"));
        set(&id, |j| {
            j.state = state;
            j.report = report.clone();
            j.took_secs = secs;
        });
        emit(Event::Finished { id: id.clone(), title: jtitle, ok, report });
    });
    let waits = if after_text.is_empty() { String::new() } else { format!(" {after_text} 이 끝나면 그 보고서를 받아 저절로 시작한다.") };
    Ok(format!("작업 {id} 을 작업자에게 맡김 ({place_name}).{waits} 작업 폴더: {dir}.") + &format!(" 지시서: {note}. 화면에 진행 카드 {id} 가 자동으로 생겼다. 끝나면 보고서가 <job_done> 으로 온다. 기다리지 말고 이 턴을 마친다."))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn job(id: &str, state: &'static str, report: &str) -> Job {
        Job {
            id: id.into(),
            title: format!("{id} title"),
            place: "api".into(),
            dir: String::new(),
            note: String::new(),
            state,
            last: String::new(),
            report: report.into(),
            started: Instant::now(),
            took_secs: 0,
            stop: Arc::new(AtomicBool::new(false)),
            inbox: Arc::new(Mutex::new(Vec::new())),
        }
    }

    #[test]
    fn a_job_waits_for_the_ones_before_it() {
        jobs().extend([job("tA", "진행 중", ""), job("tB", "기다림", ""), job("tC", "실패", "broke")]);
        let stop = Arc::new(AtomicBool::new(false));
        // tB waits for tA; when tA is done, tB gets its report and is running.
        let waiter = {
            let stop = stop.clone();
            std::thread::spawn(move || wait_for("tB", &["tA".to_string()], &stop))
        };
        std::thread::sleep(std::time::Duration::from_millis(700));
        assert_eq!(jobs().iter().find(|j| j.id == "tB").unwrap().state, "기다림");
        set("tA", |j| {
            j.state = "끝남";
            j.report = "Result: alpha written".into();
        });
        let got = waiter.join().unwrap().unwrap();
        assert!(got.contains("<earlier id=\"tA\"") && got.contains("alpha written"), "{got}");
        assert_eq!(jobs().iter().find(|j| j.id == "tB").unwrap().state, "진행 중");
        // One that failed: the waiting job does not start.
        let err = wait_for("tD", &["tC".to_string()], &stop).unwrap_err();
        assert!(err.contains("tC") && err.contains("실패"), "{err}");
        // Stopped while waiting.
        jobs().push(job("tE", "진행 중", ""));
        stop.store(true, Ordering::Relaxed);
        assert!(wait_for("tF", &["tE".to_string()], &stop).is_err());
        jobs().retain(|j| !j.id.starts_with('t'));
    }
}
