//! Claude Code on a server, reached over SSH: the same engine as `cc.rs`, but `claude` runs on the server.
//! The server's Claude Code login, CLAUDE.md, skills, hooks and MCP servers apply, and its files and
//! commands are the server's. APERIT's own tools (memory notes, route maps, workers, reading the board)
//! stay on this computer and reach the server's Claude Code through a reverse tunnel.
//!
//! It uses the `ssh` program that Windows 10/11 and macOS already have. Sign-in is a key (a key file, or
//! the ssh agent) or a password; a password is kept with the other keys (`secret.rs`) and handed to ssh
//! by this program itself acting as its SSH_ASKPASS, so it never goes on a command line or into a file.
//!
//! Settings are `ssh.txt` next to the program. Several servers can be kept, each under a `[name]` line and
//! followed by `name=value` lines: host, port, user, key (a key file), dir (the folder on the server), claude
//! (how to call Claude Code there). A `use=name` line before them says which one the `ssh` engine uses (the
//! first when there is none). Workers can be sent to any of them (`jobs.rs`). A file from before, with the
//! lines and no `[name]`, is one server named after its host.

use std::io::Write;
use std::process::{Command, Stdio};

#[derive(Clone, Debug, Default, PartialEq)]
pub struct Server {
    /// What the user calls it ("gpu", "office"); empty: the host
    pub name: String,
    pub host: String,
    /// 0: the default port (22, or what ~/.ssh/config says)
    pub port: u16,
    pub user: String,
    /// A private key file; empty: the ssh agent and the usual key files
    pub key: String,
    /// The folder Claude Code works in on the server; empty: the home folder
    pub dir: String,
    /// How to call Claude Code on the server; empty: `claude`
    pub claude: String,
}

const FILE: &str = "ssh.txt";

impl Server {
    /// The name it goes by: the name given, or else the host.
    pub fn id(&self) -> String {
        let n = self.name.trim();
        if n.is_empty() { self.host.trim().to_string() } else { n.to_string() }
    }

    /// Where its password is kept among the keys (`secret.rs`), one per server.
    fn slot(&self) -> String {
        format!("{}:{}", crate::secret::SSH_PASSWORD, self.id())
    }

    /// The saved password of this server, if it signs in with one.
    pub fn password(&self) -> Option<String> {
        crate::secret::get(&self.slot())
    }

    /// Keep (or, with an empty value, forget) this server's password.
    pub fn set_password(&self, value: &str) -> Result<(), String> {
        crate::secret::set(&self.slot(), value)
    }

    /// `user@host`, or `host` (then ~/.ssh/config or the local user name decides).
    pub fn target(&self) -> String {
        if self.user.trim().is_empty() { self.host.trim().to_string() } else { format!("{}@{}", self.user.trim(), self.host.trim()) }
    }

    pub fn claude(&self) -> String {
        let c = self.claude.trim();
        if c.is_empty() { "claude".into() } else { c.to_string() }
    }

    /// `cd` into the working folder, in the server's shell.
    pub fn cd(&self) -> String {
        let d = self.dir.trim();
        if d.is_empty() || d == "~" {
            "cd".into()
        } else if let Some(rest) = d.strip_prefix("~/") {
            format!("cd \"$HOME\"/{}", shq(rest))
        } else {
            format!("cd {}", shq(d))
        }
    }

    /// One line to show where Claude Code runs.
    pub fn label(&self) -> String {
        let dir = self.dir.trim();
        if dir.is_empty() { self.target() } else { format!("{}:{dir}", self.target()) }
    }
}

/// Read `ssh.txt`: the servers, and the one the engine uses.
fn parse(text: &str) -> (Vec<Server>, String) {
    let (mut list, mut using) = (Vec::new(), String::new());
    let mut cur: Option<Server> = None;
    for line in text.lines().map(str::trim).filter(|l| !l.is_empty() && !l.starts_with('#')) {
        if let Some(name) = line.strip_prefix('[').and_then(|l| l.strip_suffix(']')) {
            list.extend(cur.take());
            cur = Some(Server { name: name.trim().to_string(), ..Default::default() });
            continue;
        }
        let Some((k, v)) = line.split_once('=') else { continue };
        let v = v.trim().to_string();
        if k.trim() == "use" && cur.is_none() {
            using = v;
            continue;
        }
        let s = cur.get_or_insert_with(Server::default);
        match k.trim() {
            "host" => s.host = v,
            "port" => s.port = v.parse().unwrap_or(0),
            "user" => s.user = v,
            "key" => s.key = v,
            "dir" => s.dir = v,
            "claude" => s.claude = v,
            _ => {}
        }
    }
    list.extend(cur);
    list.retain(|s| !s.host.trim().is_empty());
    (list, using)
}

fn write(list: &[Server], using: &str) -> Result<(), String> {
    let mut text = format!("use={using}\n");
    for s in list {
        let port = if s.port == 0 { String::new() } else { s.port.to_string() };
        text += &format!("\n[{}]\nhost={}\nport={port}\nuser={}\nkey={}\ndir={}\nclaude={}\n", s.id(), s.host.trim(), s.user.trim(), s.key.trim(), s.dir.trim(), s.claude.trim());
    }
    std::fs::write(crate::sys::base_dir().join(FILE), text).map_err(|e| e.to_string())
}

/// Every saved server, and the id of the one the engine uses.
pub fn servers() -> (Vec<Server>, String) {
    let (mut list, using) = parse(&std::fs::read_to_string(crate::sys::base_dir().join(FILE)).unwrap_or_default());
    for s in list.iter_mut() {
        s.name = s.id();
    }
    let using = list.iter().find(|s| s.id() == using).or(list.first()).map(|s| s.id()).unwrap_or_default();
    // A password kept by an earlier version, for its one server: give it to that server.
    if let Some(old) = crate::secret::get(crate::secret::SSH_PASSWORD) {
        if let Some(s) = list.iter().find(|s| s.id() == using) {
            if s.password().is_none() && s.set_password(&old).is_ok() {
                let _ = crate::secret::set(crate::secret::SSH_PASSWORD, "");
            }
        }
    }
    (list, using)
}

/// The server the `ssh` engine uses, if any.
pub fn settings() -> Option<Server> {
    let (list, using) = servers();
    list.into_iter().find(|s| s.id() == using)
}

/// A saved server by its name.
pub fn find(id: &str) -> Option<Server> {
    servers().0.into_iter().find(|s| s.id() == id.trim())
}

/// Save a server and make it the one the engine uses. `was` is the name it had, when it is renamed or
/// changed; a server with the same name is replaced.
pub fn save_as(was: Option<&str>, s: &Server) -> Result<(), String> {
    let (mut list, _) = servers();
    let mut s = s.clone();
    s.name = s.id();
    if let Some(old) = was.filter(|w| *w != s.id()).and_then(|w| list.iter().find(|x| x.id() == w)).cloned() {
        // Renamed: the password goes along.
        if let Some(p) = old.password() {
            s.set_password(&p)?;
            old.set_password("")?;
        }
        list.retain(|x| x.id() != old.id());
    }
    match list.iter_mut().find(|x| x.id() == s.id()) {
        Some(x) => *x = s.clone(),
        None => list.push(s.clone()),
    }
    write(&list, &s.id())
}

/// Forget a server, and its password.
pub fn remove(id: &str) -> Result<(), String> {
    let (mut list, using) = servers();
    if let Some(s) = list.iter().find(|s| s.id() == id) {
        s.set_password("")?;
    }
    list.retain(|s| s.id() != id);
    let using = if using == id { list.first().map(|s| s.id()).unwrap_or_default() } else { using };
    write(&list, &using)
}

/// The server, when the chosen engine is Claude Code over SSH.
pub fn chosen() -> Option<Server> {
    let engine = crate::sys::var("ENGINE").ok().or_else(|| std::fs::read_to_string(crate::sys::base_dir().join("engine.txt")).ok())?;
    if engine.trim() != "ssh" || crate::sys::var("DEMO").is_ok() {
        return None;
    }
    settings()
}

/// Quote for a POSIX shell.
pub fn shq(s: &str) -> String {
    format!("'{}'", s.replace('\'', "'\\''"))
}

fn program() -> String {
    if cfg!(windows) {
        // The one Windows ships, not one that another tool put first on the PATH.
        let builtin = std::path::PathBuf::from(std::env::var("WINDIR").unwrap_or_else(|_| "C:\\Windows".into())).join("System32").join("OpenSSH").join("ssh.exe");
        if builtin.exists() {
            return builtin.display().to_string();
        }
    }
    "ssh".into()
}

/// An `ssh` command that runs `script` with bash on the server. `forward` is (port on the server, port here):
/// the server's 127.0.0.1:port reaches this computer's 127.0.0.1:port while it runs. `password` overrides the
/// saved one (to test a password before saving it).
pub fn command(s: &Server, script: &str, forward: Option<(u16, u16)>, password: Option<&str>) -> Command {
    let mut c = Command::new(program());
    c.args(["-T", "-o", "StrictHostKeyChecking=accept-new", "-o", "ConnectTimeout=10", "-o", "ServerAliveInterval=15", "-o", "ServerAliveCountMax=3"]);
    if s.port != 0 {
        c.args(["-p", &s.port.to_string()]);
    }
    if !s.key.trim().is_empty() {
        c.arg("-i").arg(s.key.trim()).args(["-o", "IdentitiesOnly=yes"]);
    }
    let saved = s.password();
    let password = password.map(str::to_string).filter(|p| !p.is_empty()).or_else(|| saved.clone());
    match (password, std::env::current_exe()) {
        (Some(p), Ok(me)) => {
            // ssh asks this program for the password (see `askpass` in main.rs); nothing is typed or written.
            c.env("SSH_ASKPASS", me).env("SSH_ASKPASS_REQUIRE", "force").env("DISPLAY", ":0").env("APERIT_ASKPASS", s.id()).args(["-o", "NumberOfPasswordPrompts=1"]);
            // A password given only for this connection (a test before saving) is handed over as it is.
            if saved.as_deref() != Some(p.as_str()) {
                c.env("APERIT_ASKPASS_VALUE", p);
            }
        }
        _ => {
            c.args(["-o", "BatchMode=yes"]);
        }
    }
    if let Some((remote, local)) = forward {
        c.args(["-o", "ExitOnForwardFailure=yes", "-R", &format!("127.0.0.1:{remote}:127.0.0.1:{local}")]);
    }
    let path = "export PATH=\"$HOME/.local/bin:$HOME/.claude/local:$HOME/.npm-global/bin:/usr/local/bin:$PATH\"";
    c.arg(s.target()).arg("--").arg(format!("bash -lc {}", shq(&format!("{path}; {script}"))));
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        c.creation_flags(0x0800_0000); // no console window
    }
    c.env_remove("CLAUDECODE").env_remove("CLAUDE_CODE_ENTRYPOINT");
    c
}

/// Run a short script on the server and return what it printed.
pub fn run(s: &Server, script: &str, stdin: &[u8], password: Option<&str>) -> Result<String, String> {
    let mut child = command(s, script, None, password).stdin(Stdio::piped()).stdout(Stdio::piped()).stderr(Stdio::piped()).spawn().map_err(|e| format!("ssh: {e}"))?;
    if let Some(mut i) = child.stdin.take() {
        let _ = i.write_all(stdin);
    }
    let out = child.wait_with_output().map_err(|e| e.to_string())?;
    if !out.status.success() {
        let err = String::from_utf8_lossy(&out.stderr).trim().to_string();
        return Err(explain(&err));
    }
    Ok(String::from_utf8_lossy(&out.stdout).to_string())
}

/// What ssh said, in words a user can act on.
pub fn explain(err: &str) -> String {
    let lower = err.to_lowercase();
    let what = if lower.contains("permission denied") {
        crate::sys::tr("로그인하지 못했습니다. 사용자 이름과 키(또는 비밀번호)를 확인해 주세요.", "Could not sign in. Check the user name and the key (or the password).")
    } else if lower.contains("could not resolve") || lower.contains("name or service not known") {
        crate::sys::tr("서버 주소를 찾지 못했습니다.", "The server's address was not found.")
    } else if lower.contains("timed out") || lower.contains("connection refused") || lower.contains("no route") {
        crate::sys::tr("서버에 연결하지 못했습니다. 주소와 포트를 확인해 주세요.", "Could not reach the server. Check the address and the port.")
    } else if lower.contains("host key verification failed") || lower.contains("remote host identification has changed") {
        crate::sys::tr("서버의 호스트 키가 예전과 다릅니다. 같은 서버가 맞는지 확인한 뒤 known_hosts 에서 그 줄을 지워 주세요.", "The server's host key is not the one seen before. Make sure it is the same server, then remove its line from known_hosts.")
    } else if lower.contains("command not found") || lower.contains("not found") && lower.contains("claude") {
        crate::sys::tr("서버에서 Claude Code(claude)를 찾지 못했습니다.", "Claude Code (claude) was not found on the server.")
    } else {
        ""
    };
    let tail: String = err.lines().last().unwrap_or("").chars().take(200).collect();
    if what.is_empty() { tail } else { format!("{what} ({tail})") }
}

/// Connect and ask the server's Claude Code for its version: what the Keys window's test button shows.
pub fn check(s: &Server, password: Option<&str>) -> Result<String, String> {
    let out = run(s, &format!("{} && {} --version", s.cd(), s.claude()), b"", password)?;
    let version = out.lines().last().unwrap_or("").trim().to_string();
    if version.is_empty() { Err(crate::sys::tr("연결은 됐지만 Claude Code 버전을 읽지 못했습니다.", "Connected, but Claude Code did not say its version.").into()) } else { Ok(version) }
}

/// The home folder on the server (for absolute paths the model can open), asked once per server.
pub fn home(s: &Server) -> Option<String> {
    static CACHE: std::sync::Mutex<Vec<(String, String)>> = std::sync::Mutex::new(Vec::new());
    let key = s.target();
    if let Some((_, h)) = CACHE.lock().unwrap().iter().find(|(k, _)| *k == key) {
        return Some(h.clone());
    }
    let h = run(s, "echo \"$HOME\"", b"", None).ok()?.trim().to_string();
    if h.is_empty() {
        return None;
    }
    CACHE.lock().unwrap().push((key, h.clone()));
    Some(h)
}

/// When this program was started by ssh as its SSH_ASKPASS: print the password of the server named in
/// APERIT_ASKPASS and say so.
pub fn askpass() -> bool {
    let Ok(id) = std::env::var("APERIT_ASKPASS") else { return false };
    let saved = || Server { name: id.clone(), ..Default::default() }.password();
    let p = std::env::var("APERIT_ASKPASS_VALUE").ok().or_else(saved).unwrap_or_default();
    let mut out = std::io::stdout();
    let _ = writeln!(out, "{p}");
    let _ = out.flush();
    true
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn quoting_survives_a_single_quote() {
        assert_eq!(shq("it's"), "'it'\\''s'");
        let s = Server { host: "h".into(), dir: "~/my work".into(), ..Default::default() };
        assert_eq!(s.cd(), "cd \"$HOME\"/'my work'");
        assert_eq!(Server { host: "h".into(), user: "me".into(), ..Default::default() }.target(), "me@h");
    }

    #[test]
    fn several_servers_and_the_old_file() {
        // The file of earlier versions: one server, named after its host.
        let (list, using) = parse("host=1.2.3.4\nuser=root\ndir=~/work\n");
        assert_eq!(list.len(), 1);
        assert_eq!(list[0].id(), "1.2.3.4");
        assert_eq!(using, "");
        // Named servers and the one in use.
        let (list, using) = parse("use=gpu\n\n[office]\nhost=o.example\nport=2222\n\n[gpu]\nhost=g.example\nuser=me\n[empty]\n");
        assert_eq!(list.iter().map(|s| s.id()).collect::<Vec<_>>(), ["office", "gpu"]);
        assert_eq!(list[0].port, 2222);
        assert_eq!(list[1].target(), "me@g.example");
        assert_eq!(using, "gpu");
    }
}
