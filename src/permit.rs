//! What KITE's own engines may do on this computer without asking.
//!
//! KITE is an interface; what happens behind it belongs to the engine. With Claude Code the user's Claude Code
//! settings decide, and with an agent outside KITE that agent decides. This file is for the engines that run
//! KITE's own loop (the Claude API, another model): before a tool that changes something runs, the user is
//! asked on the board with a decision card, unless they allowed that kind of work.

use std::collections::HashSet;
use std::sync::Mutex;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{Duration, Instant};

use serde_json::Value;

use crate::llm::Msg;

/// How much may be done without asking.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Level {
    /// Ask before running a command and before creating or changing a file. The default.
    Ask,
    /// Files may be created and changed; commands are still asked about.
    Edits,
    /// Nothing is asked.
    All,
}

impl Level {
    pub fn name(self) -> &'static str {
        match self {
            Level::Ask => "ask",
            Level::Edits => "edits",
            Level::All => "all",
        }
    }
    pub fn parse(s: &str) -> Option<Level> {
        match s.trim() {
            "ask" => Some(Level::Ask),
            "edits" => Some(Level::Edits),
            "all" => Some(Level::All),
            _ => None,
        }
    }
}

/// The saved level: KITE_PERMISSION, or `permission.txt` next to the program. Without either, ask.
pub fn level() -> Level {
    crate::sys::var("PERMISSION").ok().or_else(|| std::fs::read_to_string(crate::sys::base_dir().join("permission.txt")).ok()).and_then(|s| Level::parse(&s)).unwrap_or(Level::Ask)
}

/// The kind of work a tool does, when it is a kind that is asked about.
#[derive(Clone, Copy, PartialEq, Eq, Hash, Debug)]
pub enum Kind {
    Command,
    Edit,
}

pub fn kind_of(tool: &str) -> Option<Kind> {
    match tool {
        "shell" => Some(Kind::Command),
        "write_file" | "edit_file" => Some(Kind::Edit),
        _ => None,
    }
}

fn needs_asking(level: Level, kind: Kind) -> bool {
    match (level, kind) {
        (Level::All, _) => false,
        (Level::Edits, Kind::Edit) => false,
        _ => true,
    }
}

/// Kinds the user allowed for the rest of this run ("allow all of these").
static ALLOWED: Mutex<Option<HashSet<Kind>>> = Mutex::new(None);
static NEXT: AtomicU64 = AtomicU64::new(1);

/// How long an unanswered question waits before it counts as "no".
const WAIT: Duration = Duration::from_secs(900);

/// The question as the user reads it: (title, what exactly).
fn question(kind: Kind, input: &Value) -> (String, String) {
    let short = |s: &str, n: usize| -> String {
        let one: String = s.split_whitespace().collect::<Vec<_>>().join(" ").replace('|', "/");
        if one.chars().count() > n { one.chars().take(n - 1).collect::<String>() + "…" } else { one }
    };
    match kind {
        Kind::Command => (crate::sys::tr("명령을 실행할까요?", "Run this command?").to_string(), short(input["command"].as_str().unwrap_or(""), 118)),
        Kind::Edit => (crate::sys::tr("파일을 만들거나 고칠까요?", "Create or change this file?").to_string(), short(input["path"].as_str().unwrap_or(""), 118)),
    }
}

/// Before a tool runs: Ok to go ahead, or Err with what to tell the model. Blocks while the user decides.
pub fn check(tool: &str, input: &Value) -> Result<(), String> {
    let Some(kind) = kind_of(tool) else { return Ok(()) };
    if !needs_asking(level(), kind) || ALLOWED.lock().unwrap_or_else(|e| e.into_inner()).as_ref().map(|a| a.contains(&kind)).unwrap_or(false) {
        return Ok(());
    }
    let key = NEXT.fetch_add(1, Ordering::Relaxed);
    let (title, detail) = question(kind, input);
    let (tx, rx) = std::sync::mpsc::channel();
    if !crate::jobs::to_ui(Msg::Approve { key, title, detail, reply: tx }) {
        return Err("not allowed: the user could not be asked".into());
    }
    let since = Instant::now();
    let answer = loop {
        match rx.recv_timeout(Duration::from_millis(200)) {
            Ok(a) => break a,
            Err(std::sync::mpsc::RecvTimeoutError::Disconnected) => break String::new(),
            Err(_) if crate::tools::stopped() || since.elapsed() > WAIT => break String::new(),
            Err(_) => {}
        }
    };
    match answer.as_str() {
        "yes" => Ok(()),
        "all" => {
            ALLOWED.lock().unwrap_or_else(|e| e.into_inner()).get_or_insert_with(HashSet::new).insert(kind);
            Ok(())
        }
        "no" => Err("The user said no to this. Do not try it again another way; go on without it, or ask what they would prefer.".into()),
        _ => {
            // Stopped, or nobody answered: take the question off the board.
            crate::jobs::to_ui(Msg::ApproveCancel { key });
            Err("The user did not allow this (no answer).".into())
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn levels_and_kinds() {
        assert_eq!(kind_of("shell"), Some(Kind::Command));
        assert_eq!(kind_of("edit_file"), Some(Kind::Edit));
        assert_eq!(kind_of("read_file"), None);
        assert_eq!(kind_of("note_write"), None); // KITE's own memory is not the user's files
        assert!(needs_asking(Level::Ask, Kind::Command) && needs_asking(Level::Ask, Kind::Edit));
        assert!(needs_asking(Level::Edits, Kind::Command) && !needs_asking(Level::Edits, Kind::Edit));
        assert!(!needs_asking(Level::All, Kind::Command) && !needs_asking(Level::All, Kind::Edit));
        for l in [Level::Ask, Level::Edits, Level::All] {
            assert_eq!(Level::parse(l.name()), Some(l));
        }
        assert_eq!(Level::parse("everything"), None);
    }

    #[test]
    fn the_question_fits_a_decision_card() {
        let long = "x".repeat(400);
        let (title, detail) = question(Kind::Command, &serde_json::json!({ "command": format!("echo a | grep b\n{long}") }));
        assert!(!title.is_empty());
        assert!(detail.chars().count() <= 118 && detail.starts_with("echo a / grep b x") && detail.ends_with('…'));
    }
}
