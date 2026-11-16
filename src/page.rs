//! 보이지 않는 브라우저. AI 가 화면 없이 사이트를 열고, 읽고, 누르고, 채운다.
//!
//! 사람이 해야 하는 단계(로그인, 본인인증, 캡차, 되돌리기 어려운 마지막 단추)에서만 판 위에 띄운다.
//! 새 창을 여는 것이 아니라 숨겨 둔 그 브라우저를 옮겨 보이므로, 입력하던 내용과 로그인 상태가 그대로다.
//! 사용자가 "다 했어요"를 누르면 다시 숨기고, 다음 턴에 AI 가 이어서 한다.
//!
//! 비밀번호는 다루지 않는다. 로그인은 사용자가 띄운 화면에서 직접 하고, 로그인 상태는 브라우저 저장소(쿠키)에 남는다.
//!
//! 브라우저는 화면 스레드에 있다. AI(엔진 스레드)는 `call` 로 일을 넘기고 답을 기다린다.

use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{Receiver, Sender, channel};
use std::time::{Duration, Instant};

use eframe::egui;
use serde_json::Value;

/// AI 가 브라우저에 시키는 일
pub enum Req {
    Open(String),
    Read,
    Run(String),
    Show(String),
}

/// 엔진 스레드에서 부른다: 화면 스레드에 일을 넘기고 답을 기다린다.
pub fn call(name: &str, input: &Value) -> (String, bool) {
    let text = |k: &str| input[k].as_str().unwrap_or("").trim().to_string();
    let req = match name {
        "page_open" => Req::Open(text("url")),
        "page_read" => Req::Read,
        "page_run" => Req::Run(input["script"].as_str().unwrap_or("").to_string()),
        "page_show" => Req::Show(text("reason")),
        _ => return (format!("없는 도구: {name}"), true),
    };
    let (tx, rx) = channel();
    if !crate::jobs::to_ui(crate::llm::Msg::Page { req, reply: tx }) {
        return ("오류: 화면에 닿지 못함".into(), true);
    }
    match rx.recv_timeout(Duration::from_secs(60)) {
        Ok(out) => (out.clone(), out.starts_with("오류:")),
        Err(_) => ("오류: 브라우저에서 답이 오지 않음".into(), true),
    }
}

/// 페이지를 다 띄우기를 기다리는 한도
const LOAD_LIMIT: Duration = Duration::from_secs(15);
/// 다 뜬 뒤 페이지의 스크립트가 내용을 그릴 틈
const SETTLE: Duration = Duration::from_millis(900);
/// 누르거나 채운 뒤, 그 결과로 페이지가 바뀌기 시작할 틈
const AFTER_RUN: Duration = Duration::from_millis(1200);
/// 읽기 답을 기다리다 다시 보내는 간격과 횟수
const READ_RETRY: Duration = Duration::from_secs(4);
const READ_TRIES: u32 = 4;

/// 페이지를 읽는 스크립트. 제목, 주소, 보이는 글, 그리고 사람이 풀어야 하는 확인(캡차)이 떠 있는지.
/// 캡차는 실제로 보이는 것만 센다: 보이지 않는 reCAPTCHA(오른쪽 아래 작은 표시)는 많은 사이트에 늘 있으므로 세지 않는다.
const READ_JS: &str = r#"(() => {
  const t = document.body ? document.body.innerText : '';
  const seen = el => { const r = el.getBoundingClientRect(); const s = getComputedStyle(el);
    return r.width > 40 && r.height > 40 && s.visibility !== 'hidden' && s.display !== 'none' && +s.opacity > 0.1; };
  const frames = [...document.querySelectorAll('iframe')].filter(f => {
    const src = f.src || '';
    return /recaptcha|hcaptcha|challenges\.cloudflare\.com/i.test(src) && !/size=invisible/i.test(src) && seen(f); });
  const words = /자동입력\s*방지|로봇이\s*아닙니다|보안\s*문자를?\s*입력|I'm not a robot|Verify you are human|사람인지\s*확인/i.test(t);
  const captcha = frames.length > 0 || words || /Just a moment/i.test(document.title);
  return JSON.stringify({ title: document.title, url: location.href, captcha, text: t.slice(0, __LIMIT__) });
})()"#;

/// AI 가 돌리는 스크립트를 감싼다: 마지막 식의 값을 글로, 오류는 오류로 돌려준다.
fn wrap(script: &str) -> String {
    let src = serde_json::to_string(script).unwrap_or_else(|_| "\"\"".into());
    format!(
        "(() => {{ try {{ const v = eval({src}); return JSON.stringify({{ ok: true, value: v === undefined ? '(값 없음)' : (typeof v === 'object' ? JSON.stringify(v) : String(v)) }}); }} catch (e) {{ return JSON.stringify({{ ok: false, value: String(e) }}); }} }})()"
    )
}

/// 숨겨 둘 때의 자리: 창 밖. 페이지는 보통 크기의 화면에 떠 있는 줄 안다.
/// (보이지 않게 끄지 않고 창 밖에 둔다. 경로 찾기 브라우저와 같은 방식이다.)
fn hidden_bounds() -> wry::Rect {
    wry::Rect { position: wry::dpi::LogicalPosition::new(-4000.0, -4000.0).into(), size: wry::dpi::LogicalSize::new(1280.0, 900.0).into() }
}

fn to_url(s: &str) -> String {
    let s = s.trim();
    if s.contains("://") || s.starts_with("about:") || s.starts_with("data:") { s.to_string() } else { format!("https://{s}") }
}

#[derive(Debug, Clone, Copy, PartialEq)]
enum Step {
    /// 페이지가 다 뜨기를 기다린다
    Loading,
    /// 다 떴다. 이 시각이 지나면 읽는다 (그 사이 다시 뜨기 시작하면 다시 기다린다)
    Settle(Instant),
    /// 스크립트를 보냈다. 결과를 기다린다
    Running,
    /// 읽는 스크립트를 보냈다. 결과를 기다린다
    Reading,
}

struct Job {
    reply: Sender<String>,
    step: Step,
    /// 지금 단계에 들어선 시각 (단계마다 따로 기다린다)
    since: Instant,
    /// 기다리는 스크립트 답의 번호. 늦게 온 다른 답(다시 보내기 전의 것 등)은 버린다.
    want: u64,
    /// 읽기를 보낸 횟수 (페이지가 바뀌는 도중에 보낸 읽기는 답이 오지 않을 수 있어서 다시 보낸다)
    tries: u32,
    /// 읽을 글자 수
    limit: usize,
    /// 돌린 스크립트의 값 (page_run)
    value: Option<String>,
}

impl Job {
    fn to(&mut self, step: Step, now: Instant) {
        trace(&format!("{:?} → {step:?}", self.step));
        self.step = step;
        self.since = now;
    }
}

/// 확인용 기록 (LYBOARD_PAGE 로 시험할 때만)
fn trace(msg: &str) {
    static ON: std::sync::OnceLock<bool> = std::sync::OnceLock::new();
    if *ON.get_or_init(|| std::env::var("LYBOARD_PAGE").is_ok()) {
        crate::sys::log("page.log", &format!("  · {msg}"));
    }
}

/// 사용자가 보여 준 화면에서 누른 것
pub enum Outcome {
    Done(String),
    Cancel(String),
}

pub struct Page {
    view: Option<wry::WebView>,
    /// 사용자에게 보여 주는 중이면 그 이유
    shown: Option<String>,
    /// 팝업 처리기가 보는 "보여 주는 중" (처리기는 브라우저 안에서 따로 불린다)
    shown_flag: Arc<AtomicBool>,
    /// 지금 화면에 놓인 자리 (판 기준 좌표). 숨겨 두었으면 None.
    placed: Option<egui::Rect>,
    job: Option<Job>,
    /// 페이지가 뜨기 시작함(true)·다 뜸(false) 소식
    load_rx: Receiver<bool>,
    load_tx: Sender<bool>,
    loading: bool,
    /// 스크립트 결과 (번호, 결과)
    eval_rx: Receiver<(u64, String)>,
    eval_tx: Sender<(u64, String)>,
    /// 마지막으로 보낸 스크립트의 번호
    seq: u64,
    /// 숨겨 둔 동안 페이지가 새 창으로 열려 한 주소 (같은 브라우저에서 연다)
    popup_rx: Receiver<String>,
    popup_tx: Sender<String>,
    /// 처음 만들 때 열 주소
    first_url: Option<String>,
}

impl Default for Page {
    fn default() -> Self {
        let (load_tx, load_rx) = channel();
        let (eval_tx, eval_rx) = channel();
        let (popup_tx, popup_rx) = channel();
        Page {
            view: None,
            shown: None,
            shown_flag: Arc::new(AtomicBool::new(false)),
            placed: None,
            job: None,
            load_rx,
            load_tx,
            loading: false,
            eval_rx,
            eval_tx,
            seq: 0,
            popup_rx,
            popup_tx,
            first_url: None,
        }
    }
}

impl Page {
    pub fn is_shown(&self) -> bool {
        self.shown.is_some()
    }

    /// 아직 할 일이 있거나 보여 주는 중이면 화면을 계속 그려야 한다.
    pub fn active(&self) -> bool {
        self.job.is_some() || self.shown.is_some() || self.first_url.is_some()
    }

    /// 스크립트를 보낸다. 답은 번호와 함께 `eval_rx` 로 온다.
    fn eval(&mut self, js: &str) -> Result<u64, String> {
        let v = self.view.as_ref().ok_or("열린 페이지가 없음")?;
        self.seq += 1;
        let (id, tx) = (self.seq, self.eval_tx.clone());
        v.evaluate_script_with_callback(js, move |r| {
            let _ = tx.send((id, r));
        })
        .map_err(|e| e.to_string())?;
        Ok(id)
    }

    /// AI 의 요청을 받는다 (화면 스레드). 실제 일은 `tick` 에서 한다.
    pub fn request(&mut self, req: Req, reply: Sender<String>) {
        if self.job.is_some() {
            let _ = reply.send("오류: 브라우저가 앞의 일을 하는 중".into());
            return;
        }
        let busy_user = |r: &Option<String>| format!("오류: 사용자가 브라우저를 보고 있는 중이다 (보여 준 이유: {}). '다 했어요' 알림이 올 때까지 페이지를 조작하지 않는다.", r.clone().unwrap_or_default());
        let no_page = "오류: 열린 페이지가 없음 (page_open 으로 먼저 연다)";
        let now = Instant::now();
        let job = |step, want, limit| Job { reply: reply.clone(), step, since: now, want, tries: 0, limit, value: None };
        match req {
            Req::Open(url) => {
                if url.is_empty() {
                    let _ = reply.send("오류: 주소가 비어 있음".into());
                    return;
                }
                if self.shown.is_some() {
                    let _ = reply.send(busy_user(&self.shown));
                    return;
                }
                let url = to_url(&url);
                trace(&format!("열기 {url}"));
                match &self.view {
                    Some(v) => {
                        if let Err(e) = v.load_url(&url) {
                            let _ = reply.send(format!("오류: 페이지를 열지 못함 ({e})"));
                            return;
                        }
                    }
                    None => self.first_url = Some(url),
                }
                self.loading = true;
                self.job = Some(job(Step::Loading, 0, 12_000));
            }
            Req::Read => {
                if self.view.is_none() {
                    let _ = reply.send(no_page.into());
                    return;
                }
                self.job = Some(job(Step::Settle(now), 0, 30_000));
            }
            Req::Run(script) => {
                if self.shown.is_some() {
                    let _ = reply.send(busy_user(&self.shown));
                    return;
                }
                if self.view.is_none() {
                    let _ = reply.send(no_page.into());
                    return;
                }
                match self.eval(&wrap(&script)) {
                    Ok(id) => self.job = Some(job(Step::Running, id, 3_000)),
                    Err(e) => {
                        let _ = reply.send(format!("오류: {e}"));
                    }
                }
            }
            Req::Show(reason) => {
                if self.view.is_none() {
                    let _ = reply.send(no_page.into());
                    return;
                }
                self.show(if reason.is_empty() { "확인해 주세요".into() } else { reason });
                let _ = reply.send(
                    "사용자에게 브라우저를 보여 주는 중이다. 사용자가 '다 했어요'나 '그만두기'를 누르면 다음 턴의 <events> 에 '브라우저:' 로 시작하는 줄이 온다. \
                     그때까지 이 페이지를 조작하지 말고, 사용자에게 무엇을 하면 되는지 한 줄로 알린 뒤 이번 턴을 마친다."
                        .into(),
                );
            }
        }
    }

    fn show(&mut self, reason: String) {
        trace(&format!("보여 주기: {reason}"));
        self.shown = Some(reason);
        self.shown_flag.store(true, Ordering::Relaxed);
    }

    fn hide(&mut self) {
        trace("숨기기");
        self.shown = None;
        self.shown_flag.store(false, Ordering::Relaxed);
        if let Some(v) = &self.view {
            let _ = v.set_bounds(hidden_bounds());
            let _ = v.focus_parent();
        }
        self.placed = None;
    }

    /// 매 프레임 부른다: 브라우저를 만들고, 기다리던 일을 진행하고, 끝나면 답한다.
    pub fn tick(&mut self, frame: &eframe::Frame) {
        if let Some(url) = self.first_url.take() {
            let (load_tx, popup_tx, shown) = (self.load_tx.clone(), self.popup_tx.clone(), self.shown_flag.clone());
            let built = wry::WebViewBuilder::new()
                .with_bounds(hidden_bounds())
                .with_url(&url)
                .with_focused(false)
                .with_on_page_load_handler(move |ev, _| {
                    let _ = load_tx.send(matches!(ev, wry::PageLoadEvent::Started));
                })
                // 새 창: 사용자가 보고 있을 때는 보통 브라우저처럼 새 창으로 (간편 로그인 팝업이 원래 페이지와 이어져야 한다),
                // 숨겨 둔 동안에는 사용자 화면에 창이 튀어나오지 않게 같은 브라우저에서 연다.
                .with_new_window_req_handler(move |url, _| {
                    if shown.load(Ordering::Relaxed) {
                        wry::NewWindowResponse::Allow
                    } else {
                        let _ = popup_tx.send(url);
                        wry::NewWindowResponse::Deny
                    }
                })
                .build_as_child(frame);
            match built {
                Ok(v) => self.view = Some(v),
                Err(e) => {
                    if let Some(j) = self.job.take() {
                        let _ = j.reply.send(format!("오류: 브라우저를 만들지 못함 ({e})"));
                    }
                    return;
                }
            }
        }
        while let Ok(url) = self.popup_rx.try_recv() {
            trace(&format!("새 창 요청을 같은 브라우저에서 엶: {url}"));
            if let Some(v) = &self.view {
                let _ = v.load_url(&url);
            }
        }
        let now = Instant::now();
        while let Ok(started) = self.load_rx.try_recv() {
            trace(if started { "뜨기 시작" } else { "다 뜸" });
            self.loading = started;
            if let Some(j) = &mut self.job {
                // 다 기다리는 중에 다시 뜨기 시작하면 (넘겨주기 페이지 등) 끝까지 다시 기다린다.
                if started && matches!(j.step, Step::Settle(_)) {
                    j.to(Step::Loading, now);
                } else if !started && j.step == Step::Loading {
                    j.to(Step::Settle(now + SETTLE), now);
                }
            }
        }
        // 기다리던 스크립트의 답. 번호가 다른 것(다시 보내기 전의 늦은 답, 앞의 일의 답)은 버린다.
        let mut answer = None;
        while let Ok((id, r)) = self.eval_rx.try_recv() {
            if self.job.as_ref().map(|j| j.want == id).unwrap_or(false) {
                answer = Some(serde_json::from_str::<String>(&r).unwrap_or(r));
            }
        }
        let Some(j) = &mut self.job else { return };
        let waited = now - j.since;
        let mut read = false;
        match j.step {
            // 끝내 다 뜨지 않으면(광고·실시간 연결이 끝나지 않는 페이지) 지금 있는 대로 읽는다.
            Step::Loading => read = waited > LOAD_LIMIT,
            Step::Settle(at) => read = now >= at,
            Step::Running => match answer {
                Some(r) => {
                    let v: Value = serde_json::from_str(&r).unwrap_or(Value::Null);
                    let value = v["value"].as_str().unwrap_or(&r).chars().take(4000).collect::<String>();
                    j.value = Some(if v["ok"].as_bool() == Some(false) { format!("오류: {value}") } else { value });
                    // 누른 결과로 페이지가 바뀔 수 있으니 잠깐 기다린다 (그 사이 뜨기 시작하면 다 뜰 때까지).
                    let next = if self.loading { Step::Loading } else { Step::Settle(now + AFTER_RUN) };
                    j.to(next, now);
                }
                None if waited > Duration::from_secs(20) => {
                    let _ = j.reply.send("오류: 20초 안에 스크립트의 답이 오지 않음".into());
                    self.job = None;
                    return;
                }
                None => {}
            },
            Step::Reading => match answer {
                Some(r) => {
                    let job = self.job.take().expect("위에서 확인함");
                    let page: Value = serde_json::from_str(&r).unwrap_or(Value::Null);
                    let mut out = String::new();
                    if let Some(v) = &job.value {
                        out += &format!("스크립트 값: {v}\n\n");
                    }
                    out += &format!("제목: {}\n주소: {}\n", page["title"].as_str().unwrap_or(""), page["url"].as_str().unwrap_or(""));
                    if page["captcha"].as_bool() == Some(true) {
                        if self.shown.is_none() {
                            self.show("자동입력 방지(캡차)가 나왔어요. 풀어 주신 뒤 '다 했어요'를 눌러 주세요.".into());
                        }
                        out += "※ 사람이 풀어야 하는 확인(캡차)이 떠서 사용자에게 브라우저를 보여 주는 중이다. \
                                사용자가 '다 했어요'를 누르면 <events> 에 알림이 온다. 그때까지 이 페이지를 조작하지 말고 턴을 마친다.\n";
                    }
                    out += &format!("\n{}", page["text"].as_str().unwrap_or(&r));
                    trace("답함");
                    let _ = job.reply.send(out);
                    return;
                }
                // 페이지가 바뀌는 도중에 보낸 읽기는 답이 오지 않을 수 있다. 몇 번 다시 보낸다.
                None if waited > READ_RETRY => {
                    if j.tries >= READ_TRIES {
                        let _ = j.reply.send("오류: 페이지를 읽지 못함 (답이 오지 않음)".into());
                        self.job = None;
                        return;
                    }
                    trace("읽기 답이 없어 다시 보냄");
                    read = true;
                }
                None => {}
            },
        }
        if read {
            let limit = j.limit;
            let js = READ_JS.replace("__LIMIT__", &limit.to_string());
            match self.eval(&js) {
                Ok(id) => {
                    let j = self.job.as_mut().expect("위에서 확인함");
                    j.want = id;
                    j.tries += 1;
                    j.to(Step::Reading, now);
                }
                Err(e) => {
                    if let Some(j) = self.job.take() {
                        let _ = j.reply.send(format!("오류: {e}"));
                    }
                }
            }
        }
    }

    /// 보여 주는 중이면 판 위에 브라우저를 놓고, 위에 이유와 단추를 그린다.
    /// `area` 는 판의 자리(화면 좌표), `zoom` 은 egui 확대 비율(브라우저 자리는 실제 픽셀 기준이다).
    pub fn overlay(&mut self, ctx: &egui::Context, area: egui::Rect, zoom: f32) -> Option<Outcome> {
        let reason = self.shown.clone()?;
        let panel = area.shrink2(egui::vec2(0.0, 8.0));
        let head_h = 58.0;
        let web = egui::Rect::from_min_max(panel.min + egui::vec2(0.0, head_h + 8.0), panel.max - egui::vec2(0.0, 10.0));
        let mut out = None;
        egui::Area::new(egui::Id::unique("page-bar")).order(egui::Order::Foreground).fixed_pos(panel.min).show(ctx, |ui| {
            egui::Frame::new()
                .fill(crate::ui::ZONE())
                .stroke(egui::Stroke::new(1.0, crate::ui::ORANGE().gamma_multiply(0.7)))
                .corner_radius(egui::CornerRadius::same(14))
                .inner_margin(egui::Margin::symmetric(16, 10))
                .show(ui, |ui| {
                    ui.set_width(panel.width() - 32.0);
                    ui.set_height(head_h - 20.0);
                    ui.horizontal_centered(|ui| {
                        ui.vertical(|ui| {
                            ui.label(egui::RichText::new(&reason).font(crate::ui::bold(15.0)).color(crate::ui::TEXT()));
                            let url = self.view.as_ref().and_then(|v| v.url().ok()).unwrap_or_default();
                            ui.label(egui::RichText::new(url).font(crate::ui::font(11.5)).color(crate::ui::DIM()));
                        });
                        ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                            let done = egui::Button::new(egui::RichText::new(crate::sys::tr("다 했어요", "Done")).font(crate::ui::bold(14.0)))
                                .corner_radius(egui::CornerRadius::same(16))
                                .min_size(egui::vec2(96.0, 32.0));
                            if ui.add(done).clicked() {
                                out = Some(Outcome::Done(reason.clone()));
                            }
                            let stop = egui::Button::new(egui::RichText::new(crate::sys::tr("그만두기", "Stop")).font(crate::ui::font(13.0)))
                                .corner_radius(egui::CornerRadius::same(16))
                                .min_size(egui::vec2(80.0, 32.0));
                            if ui.add(stop).clicked() {
                                out = Some(Outcome::Cancel(reason.clone()));
                            }
                        });
                    });
                });
        });
        if out.is_some() {
            self.hide();
            return out;
        }
        if self.placed != Some(web) {
            if let Some(v) = &self.view {
                let bounds = wry::Rect {
                    position: wry::dpi::LogicalPosition::new((web.left() * zoom) as f64, (web.top() * zoom) as f64).into(),
                    size: wry::dpi::LogicalSize::new((web.width() * zoom) as f64, (web.height() * zoom) as f64).into(),
                };
                let _ = v.set_bounds(bounds);
                // 처음 보여 줄 때 입력이 바로 브라우저로 가게 한다.
                if self.placed.is_none() {
                    let _ = v.focus();
                }
            }
            self.placed = Some(web);
        }
        None
    }
}

impl Outcome {
    /// 다음 턴의 <events> 에 넣을 줄
    pub fn event_line(&self, page: &Page) -> String {
        let at = page.view.as_ref().and_then(|v| v.url().ok()).unwrap_or_default();
        match self {
            Outcome::Done(why) => format!("브라우저: 사용자가 '다 했어요'를 눌렀다 (보여 준 이유: {why}). 지금 주소: {at}. page_read 로 확인하고 하던 일을 이어서 한다."),
            Outcome::Cancel(why) => format!("브라우저: 사용자가 '그만두기'를 눌렀다 (보여 준 이유: {why}). 지금 주소: {at}. 브라우저 일을 멈추고, 어떻게 할지 사용자에게 묻는다."),
        }
    }
}
