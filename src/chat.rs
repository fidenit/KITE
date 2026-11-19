//! 왼쪽에 고정된 대화 구역: 주고받은 말, 진행 표시, 입력창, 버튼.

use eframe::egui;

use crate::{App, llm, sys, ui, vault};

#[allow(non_snake_case)]
fn PANEL() -> egui::Color32 { ui::ZONE() }
#[allow(non_snake_case)]
fn PANEL_EDGE() -> egui::Color32 { ui::ZONE_EDGE() }
#[allow(non_snake_case)]
fn FIELD() -> egui::Color32 { if ui::is_light() { egui::Color32::WHITE } else { egui::Color32::from_rgb(28, 29, 32) } }
#[allow(non_snake_case)]
fn FIELD_EDGE() -> egui::Color32 { if ui::is_light() { egui::Color32::from_rgb(210, 210, 215) } else { egui::Color32::from_rgb(50, 51, 56) } }
pub const WIDTH: f32 = 444.0;

/// 이름이 붙은 스위치. 손잡이가 오른쪽에 밝게 있으면 켜진 것, 왼쪽에 어둡게 있으면 꺼진 것. 눌렸으면 true.
fn switch(ui: &mut egui::Ui, ctx: &egui::Context, id: &str, label: &str, on: bool, hint: &str) -> bool {
    let (r, p) = ui.allocate_painter(egui::vec2(104.0, 40.0), egui::Sense::click());
    let k = ui::ease_in_out_cubic(ctx.animate_bool_with_time(ui.scope_id().with(id), on, 0.28));
    let lerp = |a: egui::Color32, b: egui::Color32| {
        let m = |x: u8, y: u8| (x as f32 + (y as f32 - x as f32) * k) as u8;
        egui::Color32::from_rgb(m(a.r(), b.r()), m(a.g(), b.g()), m(a.b(), b.b()))
    };
    p.rect_filled(r.rect, 20.0, FIELD());
    p.rect_stroke(r.rect, 20.0, egui::Stroke::new(1.0, if r.hovered() { ui::DIM() } else { FIELD_EDGE() }), egui::StrokeKind::Inside);
    p.text(r.rect.left_center() + egui::vec2(18.0, 0.0), egui::Align2::LEFT_CENTER, label, ui::bold(13.0), lerp(ui::DIM(), ui::TEXT()));
    let track = egui::Rect::from_center_size(r.rect.right_center() - egui::vec2(32.0, 0.0), egui::vec2(36.0, 20.0));
    p.rect_filled(track, 10.0, lerp(if ui::is_light() { egui::Color32::from_rgb(208, 208, 213) } else { egui::Color32::from_rgb(52, 53, 58) }, ui::TEXT()));
    p.circle_filled(egui::pos2(track.left() + 10.0 + 16.0 * k, track.center().y), 7.0, lerp(ui::DIM(), FIELD()));
    r.on_hover_text(hint).on_hover_cursor(egui::CursorIcon::PointingHand).clicked()
}

use eframe::egui::Rect;

/// 도구가 하는 일을 사용자가 알아볼 말로 바꾼다. 명령어나 긴 경로를 그대로 보여 주지 않는다.
fn plain(activity: &str) -> String {
    let (kind, detail) = activity.split_once(": ").unwrap_or((activity, ""));
    // 경로는 파일 이름만
    let name = detail.trim_end_matches('…').rsplit(['\\', '/']).next().unwrap_or("").trim();
    let short = |s: &str| s.chars().take(24).collect::<String>();
    let with = |what: &str, extra: &str| if extra.is_empty() { what.to_string() } else { format!("{what} · {}", short(extra)) };
    match kind {
        "명령 실행" => crate::sys::tr("컴퓨터에서 작업하는 중", "Working on the computer").to_string(),
        "파일 읽기" => with(crate::sys::tr("파일을 읽는 중", "Reading a file"), name),
        "파일 쓰기" => with(crate::sys::tr("파일을 만드는 중", "Writing a file"), name),
        "파일 고치기" => with(crate::sys::tr("파일을 고치는 중", "Editing a file"), name),
        "폴더 보기" => with(crate::sys::tr("폴더를 살펴보는 중", "Looking through a folder"), name),
        "글자 찾기" => crate::sys::tr("파일에서 찾는 중", "Searching files").to_string(),
        "기억 찾기" | "기억 읽기" => crate::sys::tr("기억을 찾아보는 중", "Checking memory").to_string(),
        "기억 쓰기" | "기억 덧붙이기" => crate::sys::tr("기억해 두는 중", "Saving to memory").to_string(),
        "페이지 읽기" | "페이지 조작" => crate::sys::tr("페이지를 살펴보는 중", "Looking at the page").to_string(),
        "웹 검색" => with(crate::sys::tr("웹에서 찾는 중", "Searching the web"), detail),
        "웹 페이지 읽기" => crate::sys::tr("웹 페이지를 읽는 중", "Reading a web page").to_string(),
        "작업 맡기기" => with(crate::sys::tr("작업자에게 맡기는 중", "Handing off to a worker"), detail),
        "작업자에게 전하기" | "작업 멈추기" | "작업 결과 보기" | "맡긴 작업 확인" => crate::sys::tr("맡긴 작업을 확인하는 중", "Checking delegated work").to_string(),
        "장소 찾기" => with(crate::sys::tr("장소를 찾는 중", "Finding a place"), detail),
        "길 찾기" => crate::sys::tr("길을 찾는 중", "Finding the route").to_string(),
        "그림 보기" => crate::sys::tr("그림을 보는 중", "Looking at a picture").to_string(),
        "보드 읽기" => crate::sys::tr("화면 내용을 확인하는 중", "Checking the board").to_string(),
        "웹 검색 중" => crate::sys::tr(crate::sys::tr("웹에서 찾는 중", "Searching the web"), "Searching the web").to_string(),
        "웹 페이지 읽는 중" => crate::sys::tr(crate::sys::tr("웹 페이지를 읽는 중", "Reading a web page"), "Reading a web page").to_string(),
        // 영어 화면에서는 이름을 모르는 일도 한국어로 내보이지 않는다.
        _ if crate::sys::english() => "Working".to_string(),
        _ => activity.to_string(),
    }
}

impl App {
    pub fn chat_panel(&mut self, ui: &mut egui::Ui, ctx: &egui::Context, talking: bool) {
        egui::Panel::left("chat")
            .exact_size(WIDTH)
            .resizable(false)
            .show_separator_line(false)
            .frame(egui::Frame::new().fill(ui::BG()).inner_margin(egui::Margin { left: 18, right: 0, top: 18, bottom: 18 }))
            .show(ui, |ui| {
                egui::Frame::new()
                    .fill(PANEL())
                    .stroke(egui::Stroke::new(1.0, PANEL_EDGE()))
                    .corner_radius(26)
                    .inner_margin(egui::Margin::same(18))
                    .show(ui, |ui| {
                        ui.set_min_size(ui.available_size());
                        // 내가 할 일이 있으면 이 구역을 위아래로 반씩 나눈다: 위는 crate::sys::tr("내 차례", "My turn")(지금 처리할 일 하나), 아래는 대화.
                        let whole = ui.available_rect_before_wrap();
                        let asks = self.board.cards.iter().filter(|c| c.kind.asks()).count();
                        // 사용자가 카드를 골랐으면 할 일이 없어도 이 자리를 연다 (고른 카드를 놓는다).
                        let lower = if asks > 0 || self.view.picked.is_some() {
                            let (top, bottom) = whole.split_top_bottom_at_fraction(0.5);
                            ui.scope_builder(egui::UiBuilder::new().max_rect(top), |ui| self.task_slot(ui, asks));
                            Rect::from_min_max(bottom.min + egui::vec2(0.0, 14.0), bottom.max)
                        } else {
                            self.view.task_rect = None;
                            whole
                        };
                        ui.scope_builder(egui::UiBuilder::new().max_rect(lower), |ui| {
                        ui.set_min_size(ui.available_size());
                        // 아래에서 위로 쌓는다: 맨 아래에 상태 줄, 그 위에 버튼, 입력창, 진행 표시. 남는 위쪽이 대화.
                        ui.with_layout(egui::Layout::bottom_up(egui::Align::Min), |ui| {
                            self.reset_row(ui);
                            ui.label(egui::RichText::new(self.status_line()).size(10.5).color(ui::FAINT()));
                            ui.add_space(8.0);
                            self.buttons(ui, ctx);
                            ui.add_space(8.0);
                            self.shelf_row(ui);
                            self.input_box(ui, ctx);
                            self.pics_row(ui);
                            if self.cfg.demo() && crate::sys::var("DEMO").is_err() {
                                self.demo_row(ui);
                            }
                            ui.add_space(8.0);
                            if let Some(e) = self.error.clone() {
                                ui.label(egui::RichText::new(e).size(12.5).color(ui::RED()));
                                ui.add_space(4.0);
                            }
                            self.progress(ui, ctx, talking);
                            ui.add_space(6.0);
                            self.history(ui);
                        });
                        });
                    });
            });
    }

    /// crate::sys::tr("내 차례", "My turn"): 내가 답하거나 승인해야 할 일이 하나씩 여기로 온다. 처리하면 다음 일이 온다.
    /// 여기서는 머리 줄만 그리고 카드 자리를 잡아 둔다. 카드는 보드가 그 자리에 그린다.
    fn task_slot(&mut self, ui: &mut egui::Ui, asks: usize) {
        let now = ui.input(|i| i.time);
        let (r, p) = ui.allocate_painter(egui::vec2(ui.available_width(), 30.0), egui::Sense::hover());
        if self.view.picked.is_some() {
            // 사용자가 고른 카드: 머리 줄에 "고른 카드"와 닫기. 기다리는 일이 있으면 그 수를 옆에 적어 둔다.
            let dot = r.rect.left_center() + egui::vec2(8.0, 0.0);
            p.circle_filled(dot, 4.0, ui::DIM());
            let tr = p.text(r.rect.left_center() + egui::vec2(22.0, 0.0), egui::Align2::LEFT_CENTER, crate::sys::tr("고른 카드", "Picked card"), ui::bold(15.0), ui::TEXT());
            if asks > 0 {
                p.text(egui::pos2(tr.right() + 10.0, tr.center().y), egui::Align2::LEFT_CENTER, if crate::sys::english() { format!("{asks} to do") } else { format!("할 일 {asks}건") }, ui::bold(12.5), ui::ORANGE());
            }
            let br = Rect::from_min_size(egui::pos2(r.rect.right() - 62.0, r.rect.top() + 2.0), egui::vec2(60.0, 26.0));
            let b = ui.interact(br, ui.scope_id().with("pick-close"), egui::Sense::click());
            p.rect_stroke(br, 13.0, egui::Stroke::new(1.0, if b.hovered() { ui::DIM() } else { FIELD_EDGE() }), egui::StrokeKind::Inside);
            p.text(br.center(), egui::Align2::CENTER_CENTER, crate::sys::tr("닫기", "Close"), ui::bold(12.0), if b.hovered() { ui::TEXT() } else { ui::DIM() });
            if b.on_hover_text(crate::sys::tr("판을 처음 보기로 되돌립니다 (Esc)", "Back to the whole board (Esc)")).on_hover_cursor(egui::CursorIcon::PointingHand).clicked() {
                self.unpick();
            }
            ui.add_space(8.0);
            let rest = ui.available_rect_before_wrap();
            ui.painter().hline(rest.x_range(), rest.bottom() + 6.0, egui::Stroke::new(1.0, PANEL_EDGE()));
            self.view.task_rect = Some(rest);
            return;
        }
        let pulse = ((now * 2.4).sin() * 0.5 + 0.5) as f32;
        let dot = r.rect.left_center() + egui::vec2(8.0, 0.0);
        p.circle_filled(dot, 4.0 + 3.0 * pulse, ui::ORANGE().gamma_multiply(0.25 * (1.0 - pulse)));
        p.circle_filled(dot, 4.0, ui::ORANGE());
        let tr = p.text(r.rect.left_center() + egui::vec2(22.0, 0.0), egui::Align2::LEFT_CENTER, crate::sys::tr("내 차례", "My turn"), ui::bold(15.0), ui::TEXT());
        // 남은 일의 수. 지금 앞에 있는 것까지 센다.
        p.text(egui::pos2(tr.right() + 10.0, tr.center().y), egui::Align2::LEFT_CENTER, if crate::sys::english() { format!("{asks} left") } else { format!("남은 {asks}건") }, ui::bold(12.5), ui::ORANGE());
        // 다른 일부터 보고 싶으면 넘긴다.
        if asks > 1 {
            let br = Rect::from_min_size(egui::pos2(r.rect.right() - 62.0, r.rect.top() + 2.0), egui::vec2(60.0, 26.0));
            let b = ui.interact(br, ui.scope_id().with("task-next"), egui::Sense::click());
            p.rect_stroke(br, 13.0, egui::Stroke::new(1.0, if b.hovered() { ui::DIM() } else { FIELD_EDGE() }), egui::StrokeKind::Inside);
            p.text(br.center(), egui::Align2::CENTER_CENTER, crate::sys::tr("다음 ›", "Next ›"), ui::bold(12.0), if b.hovered() { ui::TEXT() } else { ui::DIM() });
            if b.on_hover_text(crate::sys::tr("이 일은 나중에 하고 다음 일부터 봅니다", "Leave this for later and see the next one")).on_hover_cursor(egui::CursorIcon::PointingHand).clicked() {
                self.next_task();
            }
        }
        ui.add_space(8.0);
        let rest = ui.available_rect_before_wrap();
        // 아래 대화 구역과의 경계선
        ui.painter().hline(rest.x_range(), rest.bottom() + 6.0, egui::Stroke::new(1.0, PANEL_EDGE()));
        self.view.task_rect = Some(rest);
        if self.view.task.is_none() {
            // 방금 한 답을 반영하는 중: 끝나면 다음 일이 이 자리로 온다.
            ui.painter().text(rest.left_top() + egui::vec2(4.0, 10.0), egui::Align2::LEFT_TOP, crate::sys::tr("답을 반영하는 중입니다. 끝나면 다음 일이 옵니다.", "Applying your answer. The next one comes when it is done."), ui::font(12.5), ui::FAINT());
        }
        ui.ctx().request_repaint_after(std::time::Duration::from_millis(50)); // 점이 깜빡인다
    }

    /// 맨 아래의 초기화. 실수로 지우지 않게 두 번 눌러야 한다: 한 번 누르면 확인을 묻고, 6초 안에 다시 눌러야 지운다.
    fn reset_row(&mut self, ui: &mut egui::Ui) {
        let (r, p) = ui.allocate_painter(egui::vec2(ui.available_width(), 20.0), egui::Sense::hover());
        // 왼쪽 끝: 밝은 화면 / 어두운 화면
        let t = Rect::from_min_size(r.rect.left_top(), egui::vec2(60.0, 20.0));
        let tr = ui.interact(t, ui.scope_id().with("theme"), egui::Sense::click());
        let label = if ui::is_light() { crate::sys::tr("어둡게", "Dark") } else { crate::sys::tr("밝게", "Light") };
        p.text(t.left_center(), egui::Align2::LEFT_CENTER, label, ui::font(11.0), if tr.hovered() { ui::TEXT() } else { ui::FAINT() });
        if tr.on_hover_text(crate::sys::tr("밝은 화면과 어두운 화면을 바꿉니다", "Switch between light and dark")).on_hover_cursor(egui::CursorIcon::PointingHand).clicked() {
            crate::set_theme(ui.ctx(), !ui::is_light(), true);
            self.titlebar_done = false;
        }
        // 그 옆: 대화 언어 (AI 의 대답과 받아쓰기)
        let l = Rect::from_min_size(egui::pos2(t.right() + 4.0, r.rect.top()), egui::vec2(60.0, 20.0));
        let lr = ui.interact(l, ui.scope_id().with("lang"), egui::Sense::click());
        let label = if crate::sys::english() { "한국어" } else { "English" };
        p.text(l.left_center(), egui::Align2::LEFT_CENTER, label, ui::font(11.0), if lr.hovered() { ui::TEXT() } else { ui::FAINT() });
        if lr
            .on_hover_text(if crate::sys::english() { "대화를 한국어로 바꿉니다" } else { "Talk in English: answers, cards and voice" })
            .on_hover_cursor(egui::CursorIcon::PointingHand)
            .clicked()
        {
            crate::sys::set_english(!crate::sys::english(), true);
        }
        // 그 옆: 설정 (엔진, 모델, 키, 사용 한도)
        let k = Rect::from_min_size(egui::pos2(l.right() + 4.0, r.rect.top()), egui::vec2(54.0, 20.0));
        let kr = ui.interact(k, ui.scope_id().with("keys"), egui::Sense::click());
        p.text(k.left_center(), egui::Align2::LEFT_CENTER, crate::sys::tr("설정", "Settings"), ui::font(11.0), if kr.hovered() { ui::TEXT() } else { ui::FAINT() });
        if kr.on_hover_text(crate::sys::tr("엔진, 모델, 키, 사용 한도를 정합니다", "Engine, model, keys and spending limit")).on_hover_cursor(egui::CursorIcon::PointingHand).clicked() {
            self.key_popup = true;
        }
        if self.cfg.demo() {
            ui.add_space(2.0);
            return;
        }
        let armed = self.reset_armed.map(|t| t.elapsed().as_secs() < 6).unwrap_or(false);
        if !armed {
            self.reset_armed = None;
        }
        if armed {
            // 왼쪽에 경고, 오른쪽에 두 버튼. 서로 겹치지 않게 경고 글은 버튼 왼쪽까지만 그린다.
            let yes = Rect::from_min_size(egui::pos2(r.rect.right() - 118.0, r.rect.top()), egui::vec2(66.0, 20.0));
            let no = Rect::from_min_size(egui::pos2(r.rect.right() - 46.0, r.rect.top()), egui::vec2(46.0, 20.0));
            let warn = p.with_clip_rect(Rect::from_min_max(r.rect.min, egui::pos2(yes.left() - 8.0, r.rect.bottom())));
            warn.text(r.rect.left_center(), egui::Align2::LEFT_CENTER, crate::sys::tr("화면·대화·기억·기록을 전부 지웁니다", "Erases the board, chat, memory and records"), ui::bold(11.5), ui::RED());
            let y = ui.interact(yes, ui.scope_id().with("reset-yes"), egui::Sense::click());
            let n = ui.interact(no, ui.scope_id().with("reset-no"), egui::Sense::click());
            p.rect_stroke(yes, 10.0, egui::Stroke::new(1.0, ui::RED()), egui::StrokeKind::Inside);
            p.text(yes.center(), egui::Align2::CENTER_CENTER, crate::sys::tr("지우기", "Erase"), ui::bold(11.5), ui::RED());
            p.text(no.center(), egui::Align2::CENTER_CENTER, crate::sys::tr("취소", "Cancel"), ui::bold(11.5), if n.hovered() { ui::TEXT() } else { ui::DIM() });
            if y.on_hover_cursor(egui::CursorIcon::PointingHand).clicked() {
                self.reset_armed = None;
                self.reset_all();
            } else if n.on_hover_cursor(egui::CursorIcon::PointingHand).clicked() {
                self.reset_armed = None;
            }
            ui.ctx().request_repaint_after(std::time::Duration::from_millis(300));
        } else {
            let b = Rect::from_min_size(egui::pos2(r.rect.right() - 46.0, r.rect.top()), egui::vec2(46.0, 20.0));
            let resp = ui.interact(b, ui.scope_id().with("reset"), egui::Sense::click());
            p.text(b.right_center(), egui::Align2::RIGHT_CENTER, crate::sys::tr("초기화", "Reset"), ui::font(11.0), if resp.hovered() { ui::TEXT() } else { ui::FAINT() });
            if resp.on_hover_text(crate::sys::tr("화면, 대화, 기억, 기록을 전부 지우고 처음부터 시작합니다 (API 키와 설정은 남습니다)", "Erase the board, chat, memory and records and start over (API keys and settings stay)")).on_hover_cursor(egui::CursorIcon::PointingHand).clicked() {
                self.reset_armed = Some(std::time::Instant::now());
            }
        }
        ui.add_space(2.0);
    }

    /// 보관함으로 가는 한 줄. 보관된 주제가 있을 때만 보인다.
    fn shelf_row(&mut self, ui: &mut egui::Ui) {
        let n = self.board.shelf.len();
        if n == 0 {
            self.show_shelf = false;
            return;
        }
        let (r, p) = ui.allocate_painter(egui::vec2(ui.available_width(), 24.0), egui::Sense::click());
        let label = if self.show_shelf { crate::sys::tr("← 대화로 돌아가기", "← Back to chat").to_string() } else { if crate::sys::english() { format!("{n} shelved  ›") } else { format!("보관된 주제 {n}개  ›") } };
        p.text(r.rect.left_center() + egui::vec2(4.0, 0.0), egui::Align2::LEFT_CENTER, label, ui::bold(12.5), if r.hovered() { ui::TEXT() } else { ui::DIM() });
        if r.on_hover_cursor(egui::CursorIcon::PointingHand).clicked() {
            self.show_shelf = !self.show_shelf;
        }
        ui.add_space(4.0);
    }

    /// 보관함 목록. 누르면 그 주제가 화면으로 돌아온다.
    fn shelf_list(&mut self, ui: &mut egui::Ui) {
        let width = ui.available_width();
        let mut back = None;
        egui::ScrollArea::vertical().auto_shrink(false).max_height(ui.available_height()).show(ui, |ui| {
            ui.with_layout(egui::Layout::top_down(egui::Align::Min), |ui| {
                ui.set_width(width);
                ui.label(egui::RichText::new(crate::sys::tr("보관된 주제 · 누르면 화면으로 다시 꺼냅니다", "Shelved topics · click one to bring it back")).size(12.0).color(ui::FAINT()));
                ui.add_space(8.0);
                for s in self.board.shelf.iter().rev() {
                    let (r, p) = ui.allocate_painter(egui::vec2(width - 6.0, 46.0), egui::Sense::click());
                    p.rect_filled(r.rect, 14.0, FIELD());
                    p.rect_stroke(r.rect, 14.0, egui::Stroke::new(1.0, if r.hovered() { ui::DIM() } else { FIELD_EDGE() }), egui::StrokeKind::Inside);
                    // 이름이 길어도 줄 밖으로 나가지 않게 이 줄 안에서만 그린다.
                    let inner = p.with_clip_rect(r.rect.shrink2(egui::vec2(12.0, 0.0)));
                    inner.text(r.rect.left_top() + egui::vec2(14.0, 8.0), egui::Align2::LEFT_TOP, &s.topic.name, ui::bold(13.5), ui::TEXT());
                    let titles: Vec<&str> = s.cards.iter().take(4).map(|c| c.title.as_str()).collect();
                    inner.text(r.rect.left_top() + egui::vec2(14.0, 27.0), egui::Align2::LEFT_TOP, if crate::sys::english() { format!("{} cards · {}", s.cards.len(), titles.join(", ")) } else { format!("카드 {}장 · {}", s.cards.len(), titles.join(", ")) }, ui::font(11.0), ui::FAINT());
                    if r.on_hover_cursor(egui::CursorIcon::PointingHand).clicked() {
                        back = Some(s.topic.id.clone());
                    }
                    ui.add_space(6.0);
                }
            });
        });
        if let Some(id) = back {
            self.apply_line(&format!("restore {id}"), crate::core::Author::User);
            self.view.want_topic(id);
            if self.board.shelf.is_empty() {
                self.show_shelf = false;
            }
        }
    }

    /// 주고받은 말. 화면에 글을 쌓지 않는 것이 이 프로그램의 원칙이므로, AI 쪽은 한마디(say)만 남는다.
    fn history(&mut self, ui: &mut egui::Ui) {
        if self.show_shelf && !self.board.shelf.is_empty() {
            return self.shelf_list(ui);
        }
        let width = ui.available_width();
        egui::ScrollArea::vertical().stick_to_bottom(true).auto_shrink(false).max_height(ui.available_height()).show(ui, |ui| {
            ui.with_layout(egui::Layout::top_down(egui::Align::Min), |ui| {
                ui.set_width(width);
                let mine = |ui: &mut egui::Ui, text: &str| {
                    ui.label(egui::RichText::new(text).size(13.0).color(ui::DIM()));
                };
                let yours = |ui: &mut egui::Ui, text: &str, strong: bool| {
                    let t = egui::RichText::new(text).font(ui::bold(15.0)).color(if strong { ui::TEXT() } else { ui::DIM() });
                    ui.label(t);
                    ui.add_space(14.0);
                };
                let n = self.recent.len();
                if n == 0 && !self.busy {
                    if self.board.say.is_empty() {
                        ui.label(egui::RichText::new(crate::sys::tr("무엇이든 말하세요.\n답은 오른쪽 화면에 카드로 나타납니다.", "Say anything.\nThe answer appears as cards on the right.")).size(13.5).color(ui::FAINT()));
                    } else {
                        yours(ui, &self.said(), true);
                    }
                }
                for (i, (u, a)) in self.recent.iter().enumerate() {
                    mine(ui, u);
                    ui.add_space(3.0);
                    yours(ui, a, i + 1 == n && !self.busy);
                }
                // 답하는 중에 더 적은 말: 아직 전해지지 않은 것
                let waiting: Vec<String> = self.queue.lock().map(|q| q.clone()).unwrap_or_default();
                if self.busy {
                    mine(ui, &self.cur_input.clone());
                    ui.add_space(3.0);
                    // 지금 오고 있는 답: 말하는 중이면 그 문장, 아니면 흘러 들어오는 한마디
                    let live = self
                        .caption
                        .clone()
                        .or_else(|| self.say_live.clone())
                        .or_else(|| self.turn_said.then(|| self.said()))
                        // 아직 한마디가 없는데 일은 시작했으면, 말없이 일하는 것처럼 보이지 않게 한다.
                        .or_else(|| (!self.activity.is_empty() && !self.tidying).then(|| crate::sys::tr("확인하고 있어요.", "Looking into it.").to_string()));
                    if let Some(text) = live.filter(|t| !t.is_empty()) {
                        yours(ui, &text, true);
                    }
                } else if let Some(c) = self.caption.clone() {
                    // 읽고 있는 문장이 방금 한 말과 같으면 두 번 보이지 않게 한다.
                    if self.recent.last().map(|(_, a)| a.trim() != c.trim()).unwrap_or(true) {
                        yours(ui, &c, true);
                    }
                }
                if self.standby {
                    ui.label(egui::RichText::new(crate::sys::tr("기다리는 중 · 부르거나 하던 얘기로 돌아오면 다시 들어요", "Waiting · call me or come back to the topic and I will listen")).size(13.0).color(ui::FAINT()));
                    ui.add_space(3.0);
                }
                for w in &waiting {
                    ui.label(egui::RichText::new(if crate::sys::english() { format!("Queued · {w}") } else { format!("곧 전함 · {w}") }).size(13.0).color(ui::FAINT()));
                    ui.add_space(3.0);
                }
            });
        });
    }

    /// 진행 표시: 지금 무엇을 하고 있는지와 흐른 시간. 한가할 때는 자리를 차지하지 않는다.
    fn progress(&mut self, ui: &mut egui::Ui, ctx: &egui::Context, talking: bool) {
        let now = ui.input(|i| i.time);
        let listening = self.mic.is_some();
        let writing_down = self.stt.is_some();
        let active = self.busy || talking || self.react_at.is_some() || listening || writing_down;
        if !active {
            return;
        }
        let (r, p) = ui.allocate_painter(egui::vec2(ui.available_width(), 22.0), egui::Sense::hover());
        let dot = r.rect.left_center() + egui::vec2(8.0, 0.0);
        if listening && !self.busy {
            // 듣는 중: 들어오는 소리의 크기만큼 고리가 커진다.
            let level = self.mic.as_ref().map(|m| m.level()).unwrap_or(0.0);
            let size = (level * 60.0).clamp(0.0, 7.0);
            p.circle_stroke(dot, 4.0 + size, egui::Stroke::new(1.3, ui::TEXT().gamma_multiply(0.6)));
            p.circle_filled(dot, 3.0, ui::TEXT());
            let text = if self.heard { crate::sys::tr("듣는 중 · 말이 끝나면 알아서 보냅니다", "Listening · I send it when you stop") } else { crate::sys::tr("듣는 중 · 말씀하세요", "Listening · go ahead") };
            p.with_clip_rect(r.rect).text(r.rect.left_center() + egui::vec2(24.0, 0.0), egui::Align2::LEFT_CENTER, text, egui::FontId::proportional(12.5), ui::TEXT());
            ctx.request_repaint();
            return;
        }
        if talking {
            // 말하는 중: 목소리처럼 일렁이는 고리
            for (i, speed) in [5.3_f64, 7.9, 3.7].iter().enumerate() {
                let wave = ((now * speed + i as f64 * 1.3).sin() * 0.5 + 0.5) as f32;
                p.circle_stroke(dot, 3.5 + i as f32 * 2.0 + wave * 2.0, egui::Stroke::new(1.1, ui::TEXT().gamma_multiply(0.75 - i as f32 * 0.22)));
            }
            p.circle_filled(dot, 3.0, ui::TEXT());
        } else {
            let k = ui::ease_out_cubic((now * 0.9).fract() as f32);
            p.circle_stroke(dot, 3.0 + 6.0 * k, egui::Stroke::new(1.2, ui::ORANGE().gamma_multiply(0.6 * (1.0 - k))));
            p.circle_filled(dot, 3.0, ui::ORANGE());
        }
        let what = if !self.busy {
            if talking {
                crate::sys::tr("말하는 중", "Speaking").to_string()
            } else if writing_down {
                crate::sys::tr("받아쓰는 중", "Transcribing").to_string()
            } else {
                crate::sys::tr("받았어요", "Got it").to_string()
            }
        } else if !self.activity.is_empty() {
            plain(&self.activity)
        } else if !self.got_output {
            match self.pre.as_ref().and_then(|p| p.kind.clone()) {
                Some(_) if crate::sys::english() => "Thinking".to_string(),
                Some(kind) => format!("{kind} · 생각하는 중"),
                None if self.tidying => crate::sys::tr("분류와 주제 정리 중", "Tidying topics").to_string(),
                None => crate::sys::tr("생각하는 중", "Thinking").to_string(),
            }
        } else {
            // 지금 쓰고 있는 카드와, 이번 턴에 그 카드에 넣은 항목 수
            let last_card = self.turn_raw.lines().rev().find_map(|l| {
                let mut w = l.split_whitespace();
                matches!(w.next(), Some("card" | "set" | "add" | "upd" | "del")).then(|| w.next().unwrap_or("").to_string())
            });
            match last_card.and_then(|id| self.board.card(&id).map(|c| (id, c.title.clone()))) {
                Some((id, title)) => {
                    let n = self.turn_raw.lines().filter(|l| l.starts_with(&format!("add {id} ")) || l.starts_with(&format!("upd {id} "))).count();
                    let title: String = title.chars().take(18).collect();
                    if crate::sys::english() {
                        if n > 0 { format!("Writing '{title}' · {n} lines") } else { format!("Writing '{title}'") }
                    } else if n > 0 {
                        format!("'{title}' 쓰는 중 · {n}줄")
                    } else {
                        format!("'{title}' 쓰는 중")
                    }
                }
                None => crate::sys::tr("화면에 쓰는 중", "Writing to the board").to_string(),
            }
        };
        let text = if self.busy { format!("{what} · {}{}", self.sent_at.elapsed().as_secs(), crate::sys::tr("초", "s")) } else { what };
        // 멈춤: 도는 턴을 그만두게 한다. 글자는 이 버튼 왼쪽까지만 그려서 겹치지 않게 한다.
        let mut right = r.rect.right();
        if self.busy {
            let stopping = self.stop.load(std::sync::atomic::Ordering::Relaxed);
            let b = Rect::from_min_size(egui::pos2(r.rect.right() - 50.0, r.rect.top()), egui::vec2(50.0, 22.0));
            right = b.left() - 8.0;
            let resp = ui.interact(b, ui.scope_id().with("stop-turn"), egui::Sense::click());
            p.rect_stroke(b, 11.0, egui::Stroke::new(1.0, if resp.hovered() { ui::TEXT() } else { FIELD_EDGE() }), egui::StrokeKind::Inside);
            p.text(b.center(), egui::Align2::CENTER_CENTER, if stopping { crate::sys::tr("멈추는 중", "Stopping") } else { crate::sys::tr("멈춤", "Stop") }, ui::bold(if stopping { 10.5 } else { 11.5 }), if resp.hovered() { ui::TEXT() } else { ui::DIM() });
            if resp.on_hover_text(crate::sys::tr("지금 하는 일을 그만두게 합니다", "Stop what it is doing now")).on_hover_cursor(egui::CursorIcon::PointingHand).clicked() {
                self.stop.store(true, std::sync::atomic::Ordering::Relaxed);
            }
        }
        let clip = p.with_clip_rect(Rect::from_min_max(r.rect.min, egui::pos2(right, r.rect.bottom())));
        clip.text(r.rect.left_center() + egui::vec2(24.0, 0.0), egui::Align2::LEFT_CENTER, text, egui::FontId::proportional(12.5), ui::ORANGE());
        ctx.request_repaint();
    }

    /// 입력창 위: 붙여 둔 사진들. 누르면 뺀다.
    fn pics_row(&mut self, ui: &mut egui::Ui) {
        if self.pics.is_empty() {
            return;
        }
        let mut remove = None;
        ui.add_space(6.0);
        ui.allocate_ui_with_layout(egui::vec2(ui.available_width(), 58.0), egui::Layout::left_to_right(egui::Align::Center), |ui| {
            ui.spacing_mut().item_spacing.x = 8.0;
            for (n, (pic, tex)) in self.pics.iter().enumerate() {
                let (r, p) = ui.allocate_painter(egui::vec2(56.0, 56.0), egui::Sense::click());
                // 그림의 가운데를 정사각형으로 잘라 보여 준다.
                let (w, h) = (pic.thumb.0 as f32, pic.thumb.1 as f32);
                let side = w.min(h);
                let uv = Rect::from_min_max(egui::pos2((w - side) / 2.0 / w, (h - side) / 2.0 / h), egui::pos2((w + side) / 2.0 / w, (h + side) / 2.0 / h));
                egui::Image::new((tex.id(), egui::vec2(56.0, 56.0))).uv(uv).corner_radius(12).paint_at(ui, r.rect);
                p.rect_stroke(r.rect, 12.0, egui::Stroke::new(1.0, if r.hovered() { ui::TEXT() } else { FIELD_EDGE() }), egui::StrokeKind::Inside);
                if r.hovered() {
                    p.rect_filled(r.rect, 12.0, egui::Color32::from_black_alpha(140));
                    p.text(r.rect.center(), egui::Align2::CENTER_CENTER, crate::sys::tr("빼기", "Remove"), ui::bold(12.0), ui::TEXT());
                }
                if r.on_hover_cursor(egui::CursorIcon::PointingHand).clicked() {
                    remove = Some(n);
                }
            }
        });
        if let Some(n) = remove {
            let _ = self.pics.remove(n);
        }
    }

    fn input_box(&mut self, ui: &mut egui::Ui, ctx: &egui::Context) {
        let now = ui.input(|i| i.time);
        let pill = egui::Frame::new()
            .fill(FIELD())
            .stroke(egui::Stroke::new(1.0, FIELD_EDGE()))
            .corner_radius(20)
            .inner_margin(egui::Margin::symmetric(16, 12))
            .show(ui, |ui| {
                ui.add(
                    egui::TextEdit::multiline(&mut self.input)
                        .hint_text(if self.busy { crate::sys::tr("답하는 중에도 적으면 이어서 전합니다", "Keep typing, it will be passed along") } else { crate::sys::tr("무엇이든 말하세요 · 사진은 붙여넣거나 끌어다 놓으세요", "Say anything · paste or drop pictures here") })
                        .desired_width(f32::INFINITY)
                        .desired_rows(2)
                        // Enter 는 보내기, 줄을 바꾸려면 Shift+Enter
                        .return_key(egui::KeyboardShortcut::new(egui::Modifiers::SHIFT, egui::Key::Enter))
                        .font(egui::FontId::proportional(15.0))
                        .text_color(ui::TEXT())
                        .frame(egui::Frame::NONE),
                )
            });
        let edit = pill.inner;
        // 보낸 순간부터 답이 끝날 때까지 입력창 테두리에 주황색 선이 돈다.
        let run = ctx.animate_bool_with_time(ui.scope_id().with("input-busy"), self.busy, 0.3);
        if run > 0.01 {
            ui::draw_runner(ui.painter(), pill.response.rect, 20.0, now, run);
            ctx.request_repaint();
        }
        if self.focus_input {
            edit.request_focus();
            self.focus_input = false;
        }
        let enter = ui.input(|i| i.key_pressed(egui::Key::Enter) && !i.modifiers.shift);
        if edit.has_focus() && enter {
            self.send(ctx);
            self.focus_input = true;
        }
    }

    /// "기록" 버튼과 음성 스위치.
    fn buttons(&mut self, ui: &mut egui::Ui, ctx: &egui::Context) {
        let mut toggle = None;
        let mut talk = None;
        // 높이를 정해 주지 않으면 이 줄이 남은 세로 공간을 전부 차지한다.
        ui.allocate_ui_with_layout(egui::vec2(ui.available_width(), 40.0), egui::Layout::left_to_right(egui::Align::Center), |ui| {
            ui.spacing_mut().item_spacing.x = 8.0;
            // 기록: 오늘의 기록 노트를 옵시디언에서 연다 (옵시디언이 없으면 보관함 폴더를 연다).
            // 사진: 파일을 골라 붙인다 (붙여넣기와 끌어다 놓기도 된다).
            let (pr, pp) = ui.allocate_painter(egui::vec2(64.0, 40.0), egui::Sense::click());
            pp.rect_filled(pr.rect, 20.0, FIELD());
            pp.rect_stroke(pr.rect, 20.0, egui::Stroke::new(1.0, if pr.hovered() { ui::DIM() } else { FIELD_EDGE() }), egui::StrokeKind::Inside);
            pp.text(pr.rect.center(), egui::Align2::CENTER_CENTER, crate::sys::tr("사진", "Photo"), ui::bold(13.0), if pr.hovered() { ui::TEXT() } else { ui::DIM() });
            if pr.on_hover_text(crate::sys::tr("사진 파일을 골라 붙입니다 · 캡처한 그림은 Ctrl+V 로 바로 붙습니다", "Attach picture files · paste a capture with Ctrl+V")).on_hover_cursor(egui::CursorIcon::PointingHand).clicked() {
                let picked = rfd::FileDialog::new().add_filter(crate::sys::tr("사진", "Pictures"), &["png", "jpg", "jpeg", "webp", "gif", "bmp"]).pick_files().unwrap_or_default();
                for path in picked {
                    self.add_pic(ctx, crate::pics::from_file(&path.display().to_string()));
                }
            }
            let (nr, np) = ui.allocate_painter(egui::vec2(64.0, 40.0), egui::Sense::click());
            np.rect_filled(nr.rect, 20.0, FIELD());
            np.rect_stroke(nr.rect, 20.0, egui::Stroke::new(1.0, if nr.hovered() { ui::DIM() } else { FIELD_EDGE() }), egui::StrokeKind::Inside);
            np.text(nr.rect.center(), egui::Align2::CENTER_CENTER, crate::sys::tr("기록", "Log"), ui::bold(13.0), if nr.hovered() { ui::TEXT() } else { ui::DIM() });
            if nr.on_hover_text(crate::sys::tr("오늘의 기록과 기억 보관함 열기", "Open today's record and the memory vault")).on_hover_cursor(egui::CursorIcon::PointingHand).clicked() {
                let (date, _) = sys::now_local();
                let today = vault::root().join("기록").join(format!("{date}.md"));
                sys::open_note(&vault::root(), &today);
            }
            // 음성: AI 의 답을 소리로 듣는다.
            let hint = if self.voice { crate::sys::tr("음성 켜짐 · 누르면 끕니다", "Voice on · click to turn off") } else { crate::sys::tr("음성 꺼짐 · 누르면 켭니다", "Voice off · click to turn on") };
            if switch(ui, ctx, "voice-toggle", crate::sys::tr("음성", "Voice"), self.voice, hint) {
                toggle = Some(!self.voice);
            }
            // 대화: 마이크로 말해서 시킨다. 말이 끝나면 알아서 보내고, 답을 다 들으면 다시 듣는다.
            let hint = if crate::eleven::key().is_none() {
                crate::sys::tr("말로 대화하려면 ElevenLabs 키가 필요합니다 (설정 → 음성)", "Talking needs an ElevenLabs key (Settings → Voice)")
            } else if self.converse {
                crate::sys::tr("말로 대화하는 중 · 누르면 마이크를 끕니다", "Talking · click to turn the microphone off")
            } else {
                crate::sys::tr("누르면 마이크를 켜고 말로 대화합니다", "Click to turn the microphone on and talk")
            };
            if switch(ui, ctx, "mic-toggle", crate::sys::tr("대화", "Talk"), self.converse, hint) && crate::eleven::key().is_some() {
                talk = Some(!self.converse);
            }
        });
        if let Some(on) = toggle {
            self.set_voice(on);
            if !on {
                self.converse = false; // 소리를 끄면 말로 하는 대화도 끝낸다
            }
        }
        if let Some(on) = talk {
            self.converse = on;
            if on && !self.voice {
                self.set_voice(true); // 말로 대화할 때는 답도 소리로 듣는다
            }
        }
    }

    /// 키가 없을 때 입력창 위에 놓는 줄: 지금은 데모라는 것을 알리고, 키를 넣는 창을 다시 열 수 있게 한다.
    fn demo_row(&mut self, ui: &mut egui::Ui) {
        let (r, p) = ui.allocate_painter(egui::vec2(ui.available_width(), 30.0), egui::Sense::hover());
        p.rect_filled(r.rect, 12.0, ui::ORANGE().gamma_multiply(0.12));
        let tag = Rect::from_min_size(r.rect.min + egui::vec2(8.0, 6.0), egui::vec2(48.0, 18.0));
        p.rect_filled(tag, 9.0, ui::ORANGE());
        p.text(tag.center(), egui::Align2::CENTER_CENTER, "DEMO", ui::bold(10.5), PANEL());
        let button = Rect::from_min_size(egui::pos2(r.rect.right() - 112.0, r.rect.top() + 5.0), egui::vec2(106.0, 20.0));
        let words = p.with_clip_rect(Rect::from_min_max(r.rect.min, egui::pos2(button.left() - 6.0, r.rect.bottom())));
        words.text(
            egui::pos2(tag.right() + 8.0, r.rect.center().y),
            egui::Align2::LEFT_CENTER,
            crate::sys::tr("API 키 없음 · 준비된 장면만 재생", "No API key · prepared scenes only"),
            ui::font(11.5),
            ui::TEXT(),
        );
        let br = ui.interact(button, ui.scope_id().with("open-key"), egui::Sense::click());
        p.rect_stroke(button, 10.0, egui::Stroke::new(1.0, if br.hovered() { ui::TEXT() } else { ui::DIM() }), egui::StrokeKind::Inside);
        p.text(button.center(), egui::Align2::CENTER_CENTER, crate::sys::tr("API 키 넣기", "Enter API key"), ui::bold(11.0), ui::TEXT());
        if br.on_hover_cursor(egui::CursorIcon::PointingHand).clicked() {
            self.key_popup = true;
        }
        ui.add_space(6.0);
    }

    /// The Settings window: who answers, and how. One wide window, everything in view, nothing to unfold or scroll.
    /// Left: the ways in. Middle: what the chosen way needs. Right: spending, and the optional keys.
    /// Keys go to the app's encrypted store (secret.rs); the rest to small files next to the program.
    pub fn key_window(&mut self, ctx: &egui::Context) {
        if !self.key_popup {
            return;
        }
        let cc_version = crate::cc::version();
        let current = self.current_engine();
        if !self.keys_loaded {
            let asked = self.engine_pick.clone();
            self.load_key_window(current);
            // APERIT_KEY_WINDOW=local opens on "Local or other model".
            if asked.as_deref() == Some("local") {
                self.engine_pick = Some("openai".into());
                self.openai_by_hand = true;
            } else if asked.is_some() {
                self.engine_pick = asked;
            }
        }
        let screen = ctx.content_rect();
        let width = (screen.width() - 48.0).clamp(660.0, 1080.0);
        let frame = egui::Frame::new().fill(PANEL()).stroke(egui::Stroke::new(1.0, FIELD_EDGE())).corner_radius(24).inner_margin(egui::Margin::same(26));
        let (mut save, mut close) = (false, false);
        // Save on a server: test first (the button), or the test just passed (save now).
        let (mut test_then_save, mut save_now) = (false, false);
        egui::Modal::new(egui::Id::unique("key-window")).frame(frame).backdrop_color(egui::Color32::from_black_alpha(170)).show(ctx, |ui| {
            let inner = width - 52.0;
            ui.set_width(inner);
            let first = self.cfg.demo();
            ui.label(egui::RichText::new(if first { crate::sys::tr("APERIT에 오신 것을 환영합니다", "Welcome to APERIT") } else { crate::sys::tr("설정", "Settings") }).font(ui::bold(21.0)).color(ui::TEXT()));
            ui.add_space(4.0);
            ui.label(
                egui::RichText::new(crate::sys::tr(
                    "누가 답할지 고르세요. 키는 암호화해서 앱 안에만 두고(이 컴퓨터의 이 계정에서만 열림), 그 키의 서비스 말고는 어디로도 보내지 않습니다.",
                    "Choose who answers. Keys are kept encrypted inside the app, readable only by this account on this computer, and go nowhere but to the service they belong to.",
                ))
                .size(12.0)
                .color(ui::DIM()),
            );
            ui.add_space(18.0);
            let gap = 24.0;
            let rest = inner - 2.0 * gap;
            let (w1, w2) = (rest * 0.29, rest * 0.42);
            let w3 = rest - w1 - w2;
            ui.horizontal_top(|ui| {
                // The same height whichever way is chosen, so the window does not jump.
                ui.set_min_height(470.0);
                ui.spacing_mut().item_spacing.x = 0.0;
                ui.vertical(|ui| {
                    ui.set_width(w1);
                    self.keys_ways(ui, ctx, &cc_version, current);
                });
                ui.add_space(gap);
                ui.vertical(|ui| {
                    ui.set_width(w2);
                    self.keys_details(ui, ctx, &cc_version);
                });
                ui.add_space(gap);
                ui.vertical(|ui| {
                    ui.set_width(w3);
                    self.keys_extras(ui);
                });
            });
            ui.add_space(20.0);
            let ready = self.keys_ready(current, &cc_version);
            // On a server, Save first tests the connection with what is typed, and saves only when it works.
            // A test is good only for the fields it was run with.
            let on_server = self.engine_pick.as_deref() == Some("ssh");
            if on_server && self.ssh_tested != self.ssh_input.join("\u{1}") && !self.ssh_test.lock().unwrap().is_empty() && self.ssh_test.lock().unwrap().as_str() != "…" {
                self.ssh_test.lock().unwrap().clear();
                self.ssh_save_waiting = false;
            }
            let status = self.ssh_test.lock().unwrap().clone();
            let (testing, tested_ok, failed) = (status == "…", status.starts_with("ok "), !status.is_empty() && status != "…" && !status.starts_with("ok "));
            if on_server && self.ssh_save_waiting && !testing {
                self.ssh_save_waiting = false;
                if tested_ok {
                    save_now = true;
                }
            }
            let save_label = if !on_server {
                if first { crate::sys::tr("저장하고 시작", "Save and start") } else { crate::sys::tr("저장", "Save") }
            } else if testing && self.ssh_save_waiting {
                crate::sys::tr("연결 확인 중…", "Checking…")
            } else if failed {
                crate::sys::tr("그래도 저장", "Save anyway")
            } else if first {
                crate::sys::tr("확인하고 시작", "Check and start")
            } else {
                crate::sys::tr("확인하고 저장", "Check and save")
            };
            if first {
                ui.label(egui::RichText::new(crate::sys::tr("데모에서는 무엇을 입력해도 준비된 장면이 재생됩니다. AI가 실제로 답하지는 않습니다.", "In the demo, anything you type plays prepared scenes. The AI does not actually answer.")).size(11.5).color(ui::FAINT()));
                ui.add_space(8.0);
            }
            ui.allocate_ui_with_layout(egui::vec2(inner, 40.0), egui::Layout::right_to_left(egui::Align::Center), |ui| {
                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                    let (r, p) = ui.allocate_painter(egui::vec2(170.0, 40.0), egui::Sense::click());
                    p.rect_filled(r.rect, 20.0, if ready { ui::TEXT() } else { FIELD() });
                    p.text(r.rect.center(), egui::Align2::CENTER_CENTER, save_label, ui::bold(13.5), if ready { PANEL() } else { ui::FAINT() });
                    if ready && !testing && r.on_hover_cursor(egui::CursorIcon::PointingHand).clicked() {
                        if on_server && !failed && !tested_ok {
                            test_then_save = true;
                        } else {
                            save = true;
                        }
                    }
                    ui.add_space(10.0);
                    let label = if first { crate::sys::tr("키 없이 데모 둘러보기", "Look around the demo without a key") } else { crate::sys::tr("닫기", "Close") };
                    let wide = ui.fonts_mut(|f| f.layout_no_wrap(label.to_string(), ui::bold(13.0), ui::TEXT()).size().x) + 44.0;
                    let (r, p) = ui.allocate_painter(egui::vec2(wide.max(120.0), 40.0), egui::Sense::click());
                    p.rect_stroke(r.rect, 20.0, egui::Stroke::new(1.0, if r.hovered() { ui::DIM() } else { FIELD_EDGE() }), egui::StrokeKind::Inside);
                    p.text(r.rect.center(), egui::Align2::CENTER_CENTER, label, ui::bold(13.0), ui::TEXT());
                    if r.on_hover_cursor(egui::CursorIcon::PointingHand).clicked() {
                        close = true;
                    }
                });
            });
        });
        if test_then_save {
            self.start_ssh_test(ctx);
            self.ssh_save_waiting = true;
        }
        if save || save_now {
            self.save_keys();
        }
        if close {
            self.key_popup = false;
            self.keys_loaded = false;
            self.engine_pick = None;
            self.perm_pick = None;
            self.level_pick = None;
            self.media_pick = None;
            self.model_pick = None;
        }
    }

    /// Open the Settings window filled with what is saved, on the given way in (api, claude-code, ssh, openai,
    /// local, agent) when one is named. Whatever is set after this shows in the window until it is saved or closed.
    pub fn open_settings(&mut self, engine: Option<&str>) {
        let current = self.current_engine();
        self.load_key_window(current);
        match engine {
            Some("local") => {
                self.engine_pick = Some("openai".into());
                self.openai_by_hand = true;
            }
            Some(e) => self.engine_pick = Some(e.to_string()),
            None => {}
        }
        self.key_popup = true;
    }

    /// A change of settings the model asked for because the user asked it to (the `settings` tool). Harmless ones
    /// are made at once. Who answers, what may be done without asking, a higher or no spending limit, and keys are
    /// the user's to decide: the Settings window opens with the change made, and nothing changes until they save.
    pub fn settings_request(&mut self, change: &str, value: &str) -> String {
        let v = value.trim().to_lowercase();
        // What the model is told back ends with one short line to say, so that the caption (80 characters) is not cut.
        let say = |ko: &'static str, en: &'static str| format!(" Say this as it is, nothing longer: \"{}\"", crate::sys::tr(ko, en));
        let in_window = format!("The Settings window is open with this change made; it takes effect when the user presses Save.{}", say("설정 창에 맞춰 뒀어요. 저장을 누르면 바뀝니다.", "It is set in Settings. Press Save to apply it."));
        match change.trim() {
            "language" => {
                let english = match v.as_str() {
                    "en" | "english" | "영어" => true,
                    "ko" | "korean" | "한국어" => false,
                    _ => return format!("error: language is ko or en (got '{value}')"),
                };
                crate::sys::set_english(english, true);
                format!("The language is now {}. Write from now on in that language.", if english { "English" } else { "Korean" })
            }
            "theme" => {
                let light = match v.as_str() {
                    "light" | "밝게" | "밝은" => true,
                    "dark" | "어둡게" | "어두운" => false,
                    _ => return format!("error: theme is light or dark (got '{value}')"),
                };
                crate::set_theme(&self.wake.clone(), light, true);
                self.titlebar_done = false;
                format!("The screen is now {}.", if light { "light" } else { "dark" })
            }
            "model" => {
                let id = match v.as_str() {
                    "opus" | "claude-opus-5-5" => "claude-opus-5-5",
                    "sonnet" | "claude-sonnet-5-5" => "claude-sonnet-5-5",
                    "haiku" | "claude-haiku-5-5" => "claude-haiku-5-5",
                    _ => return format!("error: model is opus, sonnet or haiku (got '{value}')"),
                };
                let file = sys::base_dir().join("model.txt");
                let _ = if id == llm::DEFAULT_MODEL { std::fs::remove_file(&file) } else { std::fs::write(&file, id) };
                self.cfg = llm::Config::load();
                let other = self.cfg.cc || self.cfg.openai.is_some() || self.cfg.external || self.cfg.agui.is_some();
                format!(
                    "The Claude API engine uses {id} from the next request.{}",
                    if other { " The engine in use now is not the Claude API, so this matters only when the user switches to it." } else { "" }
                )
            }
            "daily_limit" => {
                let new = if matches!(v.as_str(), "" | "none" | "off" | "no" | "0" | "없음") {
                    None
                } else {
                    match v.trim_start_matches('$').replace(',', "").parse::<f64>() {
                        Ok(n) if n > 0.0 => Some(n),
                        _ => return format!("error: daily_limit is a number of dollars, or none (got '{value}')"),
                    }
                };
                let now = crate::usage::limit();
                match new {
                    // Lower, or a first limit: only spends less.
                    Some(n) if now.map(|c| n <= c).unwrap_or(true) => {
                        crate::usage::set_limit(Some(n));
                        format!("The daily limit is now ${n:.2}.")
                    }
                    // Higher, or none: the user's call.
                    _ => {
                        self.open_settings(None);
                        self.limit_input = new.map(|n| format!("{n}")).unwrap_or_default();
                        in_window
                    }
                }
            }
            "engine" => {
                let pick = match v.as_str() {
                    "api" | "claude api" | "key" => "api",
                    "claude-code" | "claude code" | "local claude code" => "claude-code",
                    "local" | "openai" | "other" => "local",
                    "agent" | "mcp" | "ag-ui" => "agent",
                    "ssh" | "server" => "ssh",
                    // A new server the user named: "ssh root@1.2.3.4:2222", "me@example.com", "1.2.3.4".
                    _ if crate::ssh::find(value.trim()).is_none() && looks_like_host(value) => {
                        let target = value.trim().trim_start_matches("ssh ").trim();
                        let (user, rest) = target.split_once('@').map(|(u, r)| (u.to_string(), r)).unwrap_or((String::new(), target));
                        let (host, port) = match rest.rsplit_once(':') {
                            Some((h, p)) if p.parse::<u16>().is_ok() => (h.to_string(), p.to_string()),
                            _ => (rest.to_string(), String::new()),
                        };
                        self.open_settings(Some("ssh"));
                        self.fill_ssh(None);
                        self.ssh_input = [host, user, port, String::new(), String::new(), String::new(), String::new()];
                        return format!(
                            "The Settings window is open on a new server with the address filled in. The password or key file goes there, never into the conversation.{}",
                            say("설정 창에 주소를 넣었어요. 비밀번호를 넣고 저장을 누르세요.", "The address is in Settings. Add the password and press Save.")
                        );
                    }
                    _ => match crate::ssh::find(value.trim()) {
                        Some(s) => {
                            self.open_settings(Some("ssh"));
                            self.fill_ssh(Some(s));
                            return in_window;
                        }
                        None => {
                            let servers: Vec<String> = crate::ssh::servers().0.iter().map(|s| s.id()).collect();
                            return format!("error: engine is api, claude-code, local, agent, or a saved server ({})", if servers.is_empty() { "none saved".into() } else { servers.join(", ") });
                        }
                    },
                };
                self.open_settings(Some(pick));
                in_window
            }
            "permission" => {
                let level = match v.as_str() {
                    "ask" | "default" => "ask",
                    "edits" | "acceptedits" => "edits",
                    "all" | "everything" | "bypasspermissions" => "all",
                    _ => return format!("error: permission is ask, edits or all (got '{value}')"),
                };
                self.open_settings(None);
                // Claude Code has its own names for the same three.
                if matches!(self.current_engine(), "claude-code" | "ssh") {
                    self.perm_pick = Some(match level { "ask" => "default", "edits" => "acceptEdits", _ => "bypassPermissions" }.into());
                } else {
                    self.level_pick = Some(level.into());
                }
                in_window
            }
            "open" | "" => {
                self.open_settings(None);
                "The Settings window is open.".into()
            }
            other => format!("error: unknown change '{other}'"),
        }
    }

    /// The engine saved now: api, claude-code, ssh, openai or agent.
    fn current_engine(&self) -> &'static str {
        if self.cfg.ssh {
            "ssh"
        } else if self.cfg.cc {
            "claude-code"
        } else if self.cfg.openai.is_some() {
            "openai"
        } else if self.cfg.external || self.cfg.agui.is_some() {
            "agent"
        } else {
            "api"
        }
    }

    /// Fill the window with what is saved, so that opening it and saving changes nothing.
    fn load_key_window(&mut self, current: &str) {
        self.engine_pick = Some(current.to_string());
        // Whether the OpenAI-compatible service is one typed by hand ("Local or other model"), not one known from its key.
        self.openai_by_hand = false;
        if let Some(p) = crate::openai::settings() {
            self.openai_by_hand = crate::openai::known_by_url(&p.url).is_none();
            self.openai_input = [p.url, p.model, String::new()];
        }
        self.agui_input = crate::agui::settings().map(|s| s.url).unwrap_or_default();
        self.fill_ssh(crate::ssh::settings());
        self.model_pick = Some(self.cfg.model.clone());
        self.limit_input = crate::usage::limit().map(|v| format!("{v}")).unwrap_or_default();
        self.keys_loaded = true;
    }

    /// Connect with what is in the SSH fields and ask the server's Claude Code for its version, in the background.
    /// The answer goes to `ssh_test`: "…" while it runs, then "ok …" or what went wrong.
    fn start_ssh_test(&mut self, ctx: &egui::Context) {
        let server = server_from(&self.ssh_input);
        let password = self.ssh_input[5].trim().to_string();
        let out = self.ssh_test.clone();
        *out.lock().unwrap() = "…".into();
        self.ssh_tested = self.ssh_input.join("\u{1}");
        let ctx = ctx.clone();
        std::thread::spawn(move || {
            let said = match crate::ssh::check(&server, Some(&password)) {
                Ok(v) => format!("ok {} · Claude Code {v}", crate::sys::tr("연결됨", "Connected")),
                Err(e) => e,
            };
            *out.lock().unwrap() = said;
            ctx.request_repaint();
        });
    }

    /// Show a saved server in the SSH fields (None: empty fields for a new one).
    fn fill_ssh(&mut self, s: Option<crate::ssh::Server>) {
        match s {
            Some(s) => {
                let port = if s.port == 0 { String::new() } else { s.port.to_string() };
                self.ssh_pick = Some(s.id());
                self.ssh_input = [s.host.clone(), s.user, port, s.key, s.dir, String::new(), s.name];
            }
            None => {
                self.ssh_pick = None;
                self.ssh_input = Default::default();
            }
        }
        *self.ssh_test.lock().unwrap() = String::new();
    }

    /// Left column: the ways in.
    fn keys_ways(&mut self, ui: &mut egui::Ui, ctx: &egui::Context, cc_version: &Option<String>, current: &str) {
        section(ui, crate::sys::tr("누가 답할지", "Who answers"));
        let pick = self.engine_pick.clone().unwrap_or_default();
        let signed = cc_version.is_some() && crate::cc::signed_in();
        let cc_sub = match (cc_version, signed) {
            (None, _) => crate::sys::tr("Claude Code 설치가 필요합니다", "Needs Claude Code installed").to_string(),
            (Some(_), true) => crate::sys::tr("로그인됨 · 키 필요 없음", "signed in · no key needed").to_string(),
            (Some(_), false) => crate::sys::tr("누르면 브라우저에서 로그인", "press to sign in with your browser").to_string(),
        };
        let key_on = pick == "api" || (pick == "openai" && !self.openai_by_hand);
        let ways: [(&str, &str, String, bool); 5] = [
            ("claude-code", crate::sys::tr("Claude로 로그인", "Sign in with Claude"), cc_sub, pick == "claude-code"),
            ("key", crate::sys::tr("API 키", "An API key"), crate::sys::tr("Claude · OpenAI · Gemini 등 · 쓴 만큼 과금", "Claude, OpenAI, Gemini, … · pay per use").to_string(), key_on),
            ("openai", crate::sys::tr("로컬·다른 모델", "Local or other model"), crate::sys::tr("주소와 모델을 직접 적기", "give an address and a model").to_string(), pick == "openai" && self.openai_by_hand),
            ("ssh", crate::sys::tr("서버 (SSH)", "A server (SSH)"), crate::sys::tr("서버의 Claude Code", "Claude Code on a server").to_string(), pick == "ssh"),
            ("agent", crate::sys::tr("내 에이전트", "Your own agent"), crate::sys::tr("MCP 또는 AG-UI로 연결", "connects over MCP or AG-UI").to_string(), pick == "agent"),
        ];
        let w = ui.available_width();
        for (id, title, sub, on) in ways {
            let in_use = match id {
                "key" => current == "api" || (current == "openai" && self.cfg.openai.as_ref().map(|p| crate::openai::known_by_url(&p.url).is_some()).unwrap_or(false)),
                "openai" => current == "openai" && self.cfg.openai.as_ref().map(|p| crate::openai::known_by_url(&p.url).is_none()).unwrap_or(false),
                _ => current == id,
            } && !self.cfg.demo();
            if tile(ui, w, 54.0, title, &sub, on, in_use) {
                match id {
                    "claude-code" => {
                        if cc_version.is_none() {
                            ctx.open_url(egui::OpenUrl::new_tab("https://claude.com/claude-code"));
                        } else {
                            if !signed {
                                crate::cc::sign_in();
                            }
                            self.engine_pick = Some(id.into());
                        }
                    }
                    "key" => {
                        let typed = crate::openai::recognize(&self.key_input);
                        self.engine_pick = Some(if typed.map(|k| !k.is_claude()).unwrap_or(false) || (current == "openai" && !self.openai_by_hand && self.key_input.trim().is_empty()) { "openai" } else { "api" }.into());
                        self.openai_by_hand = false;
                    }
                    "openai" => {
                        self.engine_pick = Some(id.into());
                        if !self.openai_by_hand && crate::openai::known_by_url(&self.openai_input[0]).is_some() {
                            self.openai_input = Default::default();
                        }
                        self.openai_by_hand = true;
                    }
                    _ => self.engine_pick = Some(id.into()),
                }
            }
            ui.add_space(8.0);
        }
    }

    /// Middle column: what the chosen way needs.
    fn keys_details(&mut self, ui: &mut egui::Ui, ctx: &egui::Context, cc_version: &Option<String>) {
        let pick = self.engine_pick.clone().unwrap_or_default();
        let note = |ui: &mut egui::Ui, text: &str| {
            ui.label(egui::RichText::new(text).size(12.0).color(ui::DIM()));
            ui.add_space(10.0);
        };
        match pick.as_str() {
            "claude-code" => {
                section(ui, crate::sys::tr("이 컴퓨터의 Claude Code", "Claude Code on this computer"));
                match cc_version {
                    Some(v) => small(ui, &format!("Claude Code {v} · {}", if crate::cc::signed_in() { crate::sys::tr("로그인됨", "signed in") } else { crate::sys::tr("로그인 필요", "not signed in") }), crate::cc::signed_in()),
                    None => small(ui, crate::sys::tr("설치되어 있지 않습니다", "Not installed"), false),
                }
                ui.add_space(6.0);
                note(ui, crate::sys::tr(
                    "턴마다 Claude Code에 맡깁니다. Claude Code의 로그인, CLAUDE.md, 스킬, 훅, MCP 서버, 권한 설정이 그대로 적용되고, 경로 지도·기억·작업자 같은 APERIT 기능도 그대로 씁니다. API 키는 필요 없습니다.",
                    "Each turn goes to Claude Code. Its login, CLAUDE.md, skills, hooks, MCP servers and permission settings apply, and APERIT's own features (route maps, memory, workers) keep working. No API key is needed.",
                ));
                self.permission_cc(ui);
            }
            "ssh" => {
                section(ui, crate::sys::tr("서버의 Claude Code (SSH)", "Claude Code on a server (SSH)"));
                note(ui, crate::sys::tr(
                    "저장한 서버의 Claude Code에 턴을 맡깁니다. 서버를 여러 개 두면 작업자를 어느 서버로든 보낼 수 있습니다. 기억과 APERIT 도구는 이 컴퓨터에 남습니다.",
                    "Turns go to the Claude Code on the server saved here. Keep several, and workers can be sent to any of them. Memory and APERIT's tools stay on this computer.",
                ));
                // The saved servers (the green dot: the one turns go to), and a new one.
                let (saved, using) = crate::ssh::servers();
                if !saved.is_empty() {
                    let mut clicked: Option<Option<crate::ssh::Server>> = None;
                    ui.horizontal_wrapped(|ui| {
                        ui.spacing_mut().item_spacing = egui::vec2(6.0, 6.0);
                        for s in &saved {
                            if chip(ui, &s.id(), self.ssh_pick.as_deref() == Some(s.id().as_str()), s.id() == using) {
                                clicked = Some(Some(s.clone()));
                            }
                        }
                        if chip(ui, crate::sys::tr("+ 새 서버", "+ New server"), self.ssh_pick.is_none(), false) {
                            clicked = Some(None);
                        }
                    });
                    if let Some(c) = clicked {
                        self.fill_ssh(c);
                    }
                    ui.add_space(10.0);
                }
                let saved_password = self.ssh_pick.as_deref().and_then(crate::ssh::find).and_then(|s| s.password()).is_some();
                let [host, user, port, key, dir, password, name] = &mut self.ssh_input;
                fields(
                    ui,
                    vec![
                        (crate::sys::tr("이름", "Name"), name, crate::sys::tr("비우면 주소", "empty: the address"), false, 0.28),
                        (crate::sys::tr("서버 주소", "Server address"), host, "example.com", false, 0.52),
                        (crate::sys::tr("포트", "Port"), port, "22", false, 0.2),
                    ],
                );
                pair(ui, (crate::sys::tr("사용자", "User"), user, "ubuntu", false, 0.4), (crate::sys::tr("키 파일 (선택)", "Key file (optional)"), key, crate::sys::tr("비우면 ssh 에이전트", "empty: the ssh agent"), false, 0.6));
                pair(
                    ui,
                    (crate::sys::tr("비밀번호 (선택)", "Password (optional)"), password, if saved_password { crate::sys::tr("저장됨", "saved") } else { crate::sys::tr("로그인 비밀번호", "sign-in password") }, true, 0.5),
                    (crate::sys::tr("작업 폴더 (선택)", "Folder (optional)"), dir, "~/work", false, 0.5),
                );
                // Try it before saving: connect and ask the server's Claude Code for its version.
                let status = self.ssh_test.lock().unwrap().clone();
                let testing = status == "…";
                ui.horizontal(|ui| {
                    let (r, p) = ui.allocate_painter(egui::vec2(150.0, 30.0), if testing { egui::Sense::hover() } else { egui::Sense::click() });
                    p.rect_stroke(r.rect, 15.0, egui::Stroke::new(1.0, if r.hovered() { ui::DIM() } else { FIELD_EDGE() }), egui::StrokeKind::Inside);
                    p.text(r.rect.center(), egui::Align2::CENTER_CENTER, if testing { crate::sys::tr("연결하는 중…", "Connecting…") } else { crate::sys::tr("연결 시험", "Test the connection") }, ui::bold(12.0), ui::TEXT());
                    if !testing && !self.ssh_input[0].trim().is_empty() && r.on_hover_cursor(egui::CursorIcon::PointingHand).clicked() {
                        self.start_ssh_test(ctx);
                    }
                    // Forget the server being shown (and its password).
                    if let Some(id) = self.ssh_pick.clone().filter(|_| status.is_empty() || testing) {
                        ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                            let link = ui.add(egui::Label::new(egui::RichText::new(crate::sys::tr("이 서버 지우기", "Remove this server")).size(11.5).color(ui::FAINT())).sense(egui::Sense::click()));
                            if link.on_hover_cursor(egui::CursorIcon::PointingHand).clicked() {
                                if let Err(e) = crate::ssh::remove(&id) {
                                    self.error = Some(e);
                                }
                                self.fill_ssh(crate::ssh::settings());
                            }
                        });
                    }
                });
                // What the test said, under the button, wrapped to the column (an error can be long).
                if !status.is_empty() && !testing {
                    let (good, text) = match status.strip_prefix("ok ") {
                        Some(t) => (true, t.to_string()),
                        None => (false, status.clone()),
                    };
                    ui.add_space(6.0);
                    ui.add(egui::Label::new(egui::RichText::new(text).size(11.5).color(if good { ui::GREEN() } else { ui::RED() })).wrap());
                }
                ui.add_space(12.0);
                self.permission_cc(ui);
            }
            "openai" if self.openai_by_hand => {
                section(ui, crate::sys::tr("로컬·다른 모델", "Local or other model"));
                note(ui, crate::sys::tr(
                    "OpenAI 형식(chat/completions)을 받는 곳이면 됩니다: Ollama·LM Studio의 로컬 모델, OpenRouter, 사내 게이트웨이. APERIT의 기억·작업자·도구는 그대로 씁니다. 웹 검색은 Claude에서만 됩니다.",
                    "Anything that takes the OpenAI format (chat/completions): a local model in Ollama or LM Studio, OpenRouter, a gateway of your own. APERIT's memory, workers and tools stay. Web search is Claude only.",
                ));
                let saved = self.cfg.openai.is_some();
                let [url, model, key] = &mut self.openai_input;
                label(ui, crate::sys::tr("주소", "Base address"));
                input(ui, url, "http://localhost:11434/v1", false);
                ui.add_space(8.0);
                pair(ui, (crate::sys::tr("모델", "Model"), model, "qwen3 · gpt-5", false, 0.5), (crate::sys::tr("키", "Key"), key, if saved { crate::sys::tr("비우면 그대로", "empty: keep") } else { crate::sys::tr("로컬이면 비움", "empty for local") }, true, 0.5));
                self.permission_own(ui);
            }
            "agent" => {
                section(ui, crate::sys::tr("내 에이전트", "Your own agent"));
                note(ui, crate::sys::tr(
                    "APERIT 안의 모델은 쉬고, 바깥의 에이전트가 이 보드를 화면으로 씁니다. 여기서 치는 말과 누르는 선택은 그 에이전트에게 갑니다.",
                    "No model runs inside APERIT; an agent outside uses this board as its screen. What you type and pick here goes to that agent.",
                ));
                let open = crate::mcp::outside();
                label(ui, "MCP");
                small(ui, &match &open { Some((port, _)) => format!("http://127.0.0.1:{port}/mcp · screen, board_read, events, guide"), None => crate::sys::tr("저장하면 열립니다", "Opens when you save").to_string() }, open.is_some());
                if let Some((port, token)) = &open {
                    ui.add_space(6.0);
                    ui.horizontal(|ui| {
                        ui.spacing_mut().item_spacing.x = 6.0;
                        let half = (ui.available_width() - 6.0) / 2.0;
                        for (text, copy) in [
                            (crate::sys::tr("Claude Code 명령 복사", "Copy the Claude Code command"), crate::mcp::claude_code_command(*port, token)),
                            (crate::sys::tr("설정(JSON) 복사", "Copy the config (JSON)"), crate::mcp::client_config_text(*port, token)),
                        ] {
                            let (r, p) = ui.allocate_painter(egui::vec2(half, 30.0), egui::Sense::click());
                            p.rect_stroke(r.rect, 10.0, egui::Stroke::new(1.0, if r.hovered() { ui::DIM() } else { FIELD_EDGE() }), egui::StrokeKind::Inside);
                            p.text(r.rect.center(), egui::Align2::CENTER_CENTER, text, ui::bold(11.5), ui::TEXT());
                            if r.on_hover_cursor(egui::CursorIcon::PointingHand).clicked() {
                                ctx.copy_text(copy);
                            }
                        }
                    });
                }
                ui.add_space(12.0);
                // Cards that load an address or a file the agent chose: off unless allowed.
                let media = *self.media_pick.get_or_insert_with(crate::mcp::outside_media);
                ui.horizontal(|ui| {
                    let (r, p) = ui.allocate_painter(egui::vec2(34.0, 20.0), egui::Sense::click());
                    p.rect_filled(r.rect, 10.0, if media { ui::ORANGE() } else { FIELD_EDGE() });
                    p.circle_filled(egui::pos2(if media { r.rect.right() - 10.0 } else { r.rect.left() + 10.0 }, r.rect.center().y), 7.0, ui::TEXT());
                    if r.on_hover_cursor(egui::CursorIcon::PointingHand).clicked() {
                        self.media_pick = Some(!media);
                    }
                    ui.label(egui::RichText::new(crate::sys::tr("웹 페이지·그림·파일 카드를 허용 (믿는 에이전트에만)", "Allow web, image and file cards (trusted agents only)")).size(12.0).color(ui::TEXT()));
                });
                ui.add_space(12.0);
                label(ui, crate::sys::tr("AG-UI 주소 (선택)", "AG-UI address (optional)"));
                input(ui, &mut self.agui_input, "http://localhost:8000/agent", false);
                ui.add_space(4.0);
                ui.label(egui::RichText::new(crate::sys::tr("LangGraph, CrewAI, Pydantic AI 같은 에이전트의 주소. 비우면 MCP로 오는 에이전트를 기다립니다.", "An agent built with LangGraph, CrewAI, Pydantic AI and the like. Empty: wait for an agent over MCP.")).size(11.5).color(ui::FAINT()));
            }
            _ => {
                // An API key: the key says whose it is.
                section(ui, crate::sys::tr("API 키", "An API key"));
                let has_key = self.cfg.key.is_some();
                input(ui, &mut self.key_input, if has_key { crate::sys::tr("Claude 키 저장됨 · 비우면 그대로", "A Claude key is saved · leave empty to keep it") } else { "sk-ant-… · sk-… · AIza…" }, true);
                let typed = self.key_input.trim().to_string();
                let known = crate::openai::recognize(&typed);
                if let Some(k) = known {
                    if k.is_claude() {
                        self.engine_pick = Some("api".into());
                    } else {
                        self.engine_pick = Some("openai".into());
                        self.openai_input = [k.url.to_string(), String::new(), typed.clone()];
                        self.openai_by_hand = false;
                    }
                }
                ui.add_space(4.0);
                match known {
                    Some(k) if k.is_claude() => small(ui, crate::sys::tr("Claude 키입니다.", "A Claude key."), true),
                    Some(k) => small(ui, &format!("{} {}", k.name, crate::sys::tr("키입니다. 모델은 알아서 고릅니다.", "key. APERIT picks the model.")), true),
                    None if typed.len() > 8 => small(ui, crate::sys::tr("처음 보는 키입니다. '로컬·다른 모델'에서 주소를 적어 주세요.", "A key APERIT does not know: give its address under \"Local or other model\"."), false),
                    None => {
                        if let Some(p) = self.cfg.openai.as_ref().filter(|_| self.engine_pick.as_deref() == Some("openai")) {
                            small(ui, &format!("{} · {} {}", crate::openai::known_by_url(&p.url).map(|k| k.name).unwrap_or(p.url.as_str()), p.model, crate::sys::tr("저장됨", "saved")), true);
                        }
                    }
                }
                let link = ui.add(egui::Label::new(egui::RichText::new(crate::sys::tr("Claude 키 만들기 ↗", "Create a Claude key ↗")).size(12.0).color(ui::ORANGE())).sense(egui::Sense::click()));
                if link.on_hover_cursor(egui::CursorIcon::PointingHand).clicked() {
                    ctx.open_url(egui::OpenUrl::new_tab("https://console.anthropic.com/settings/keys"));
                }
                ui.add_space(12.0);
                if self.engine_pick.as_deref() == Some("api") {
                    self.model_choice(ui);
                }
                self.permission_own(ui);
            }
        }
    }

    /// Which Claude model answers, with what a turn roughly costs.
    fn model_choice(&mut self, ui: &mut egui::Ui) {
        label(ui, crate::sys::tr("모델", "Model"));
        let now = self.model_pick.clone().unwrap_or_else(|| self.cfg.model.clone());
        ui.horizontal(|ui| {
            ui.spacing_mut().item_spacing.x = 6.0;
            let third = (ui.available_width() - 12.0) / 3.0;
            for (id, name) in [("claude-opus-5-5", "Opus 5.5"), ("claude-sonnet-5-5", "Sonnet 5.5"), ("claude-haiku-5-5", "Haiku 5.5")] {
                let cost = crate::usage::per_turn(id).map(crate::usage::money).unwrap_or_default();
                let sub = format!("{cost} {}", crate::sys::tr("/ 턴", "a turn"));
                if tile(ui, third, 46.0, name, &sub, now == id, false) {
                    self.model_pick = Some(id.into());
                }
            }
        });
        ui.add_space(4.0);
        ui.label(egui::RichText::new(crate::sys::tr("Opus가 가장 뛰어나고, Sonnet은 그 절반 값, Haiku는 가장 빠르고 쌉니다. 턴 값은 지시문을 캐시에서 읽는 보통의 턴 기준 대략치입니다.", "Opus is the most capable; Sonnet costs half as much; Haiku is the fastest and cheapest. A turn's cost is a rough figure for an ordinary turn with the instructions read from the cache.")).size(11.5).color(ui::FAINT()));
        ui.add_space(12.0);
    }

    /// Right column: spending, voice, and the decision model.
    fn keys_extras(&mut self, ui: &mut egui::Ui) {
        section(ui, crate::sys::tr("사용 금액", "Spending"));
        let (today, month) = crate::usage::totals();
        ui.horizontal(|ui| {
            for (name, value) in [(crate::sys::tr("오늘", "Today"), today), (crate::sys::tr("이번 달", "This month"), month)] {
                ui.vertical(|ui| {
                    ui.label(egui::RichText::new(name).size(11.5).color(ui::FAINT()));
                    ui.label(egui::RichText::new(format!("${value:.2}")).font(ui::bold(18.0)).color(ui::TEXT()));
                });
                ui.add_space(22.0);
            }
        });
        ui.add_space(12.0);
        label(ui, crate::sys::tr("하루 한도 ($, 선택)", "Daily limit ($, optional)"));
        input(ui, &mut self.limit_input, crate::sys::tr("비우면 한도 없음", "empty: no limit"), false);
        ui.add_space(4.0);
        ui.label(egui::RichText::new(crate::sys::tr("내 Claude API 키로 쓴 대략의 금액입니다. 한도에 닿으면 그날은 새 일을 시작하지 않습니다. Claude Code와 다른 서비스는 세지 않습니다.", "An estimate of what your own Claude API key has spent. At the limit, no new work starts that day. Claude Code and other services are not counted.")).size(11.5).color(ui::FAINT()));
        ui.add_space(16.0);
        section(ui, crate::sys::tr("음성 (선택)", "Voice (optional)"));
        let has_eleven = crate::eleven::key().is_some();
        input(ui, &mut self.eleven_input, if has_eleven { crate::sys::tr("ElevenLabs 키 저장됨 · 비우면 그대로", "ElevenLabs key saved · empty: keep") } else { crate::sys::tr("ElevenLabs 키 · 말로 대화할 때만", "ElevenLabs key · only to talk") }, true);
        ui.add_space(16.0);
        section(ui, crate::sys::tr("판단 모델 (선택)", "Decision model (optional)"));
        let has_jev = crate::jev::key().is_some();
        input(ui, &mut self.jev_input, if has_jev { crate::sys::tr("Jev 키 저장됨 · 비우면 그대로", "Jev key saved · empty: keep") } else { crate::sys::tr("TypeSafe Jev 키", "TypeSafe Jev key") }, true);
        ui.add_space(4.0);
        ui.label(egui::RichText::new(crate::sys::tr("주제 고르기, 카드 순서, 옛 대화 찾기를 빠르게 합니다. 없어도 그대로 동작합니다.", "Picks topics, orders cards and recalls past conversations faster. APERIT works the same without it.")).size(11.5).color(ui::FAINT()));
    }

    /// What Claude Code may do without asking (its own permission modes).
    fn permission_cc(&mut self, ui: &mut egui::Ui) {
        label(ui, crate::sys::tr("묻지 않고 해도 되는 일", "What it may do without asking"));
        let now = self.perm_pick.get_or_insert_with(crate::cc::permission).clone();
        if let Some(v) = three(ui, &now, [("default", crate::sys::tr("내 설정대로", "My settings")), ("acceptEdits", crate::sys::tr("파일 수정", "File edits")), ("bypassPermissions", crate::sys::tr("전부", "Everything"))]) {
            self.perm_pick = Some(v);
        }
        ui.add_space(4.0);
        ui.label(
            egui::RichText::new(match now.as_str() {
                "acceptEdits" => crate::sys::tr("파일을 만들고 고치는 것은 묻지 않습니다. 그 밖의 명령은 Claude Code 설정을 따릅니다.", "Creates and edits files without asking. Other commands follow Claude Code's settings."),
                "bypassPermissions" => crate::sys::tr("명령 실행과 파일 수정을 전부 묻지 않고 합니다.", "Runs commands and edits files without asking."),
                _ => crate::sys::tr("Claude Code에서 허용해 둔 것만 합니다. 나머지는 거절되고, 그렇다고 알려 줍니다.", "Does only what Claude Code's settings allow. The rest is refused, and it says so."),
            })
            .size(11.5)
            .color(ui::FAINT()),
        );
    }

    /// What APERIT's own engine may do on this computer without asking first.
    fn permission_own(&mut self, ui: &mut egui::Ui) {
        label(ui, crate::sys::tr("묻지 않고 해도 되는 일", "What it may do without asking"));
        let now = self.level_pick.get_or_insert_with(|| crate::permit::level().name().to_string()).clone();
        if let Some(v) = three(ui, &now, [("ask", crate::sys::tr("먼저 묻기", "Ask first")), ("edits", crate::sys::tr("파일 수정", "File edits")), ("all", crate::sys::tr("전부", "Everything"))]) {
            self.level_pick = Some(v);
        }
        ui.add_space(4.0);
        ui.label(
            egui::RichText::new(match now.as_str() {
                "edits" => crate::sys::tr("파일을 만들고 고치는 것은 묻지 않습니다. 명령을 실행하기 전에는 카드로 묻습니다.", "Creates and changes files without asking. Asks with a card before running a command."),
                "all" => crate::sys::tr("명령 실행과 파일 수정을 전부 묻지 않고 합니다.", "Runs commands and changes files without asking."),
                _ => crate::sys::tr("명령 실행이나 파일 수정 전에 카드로 묻습니다. 읽기와 검색은 묻지 않습니다.", "Asks with a card before running a command or changing a file. Reading and searching are not asked about."),
            })
            .size(11.5)
            .color(ui::FAINT()),
        );
    }

    /// Can Save be pressed: is there something complete and new to save?
    fn keys_ready(&self, current: &str, cc_version: &Option<String>) -> bool {
        let pick = self.engine_pick.clone().unwrap_or_default();
        let has_key = self.cfg.key.is_some();
        let extras = self.jev_input.trim().len() > 8
            || self.eleven_input.trim().len() > 8
            || self.level_pick.as_deref().map(|l| l != crate::permit::level().name()).unwrap_or(false)
            || self.perm_pick.as_deref().map(|p| p != crate::cc::permission()).unwrap_or(false)
            || self.limit_input.trim() != crate::usage::limit().map(|v| format!("{v}")).unwrap_or_default();
        let limit_ok = self.limit_input.trim().is_empty() || self.limit_input.trim().trim_start_matches('$').parse::<f64>().is_ok();
        let changed = pick != current || extras;
        limit_ok
            && match pick.as_str() {
                "claude-code" => cc_version.is_some() && (changed || self.cfg.demo()),
                "ssh" => !self.ssh_input[0].trim().is_empty() && (self.ssh_input[2].trim().is_empty() || self.ssh_input[2].trim().parse::<u16>().is_ok()),
                "openai" => {
                    let [url, model, key] = &self.openai_input;
                    let (url, model) = (url.trim().trim_end_matches('/'), model.trim());
                    (url.starts_with("http://") || url.starts_with("https://")) && (!model.is_empty() || crate::openai::known_by_url(url).is_some()) && (changed || !key.trim().is_empty() || self.cfg.openai.as_ref().map(|p| p.url != url || p.model != model).unwrap_or(true))
                }
                "agent" => {
                    let url = self.agui_input.trim();
                    (url.is_empty() || url.starts_with("http://") || url.starts_with("https://")) && (changed || self.media_pick.map(|m| m != crate::mcp::outside_media()).unwrap_or(false) || self.cfg.agui.as_ref().map(|s| s.url.as_str()).unwrap_or("") != url)
                }
                _ => {
                    let model_changed = self.model_pick.as_deref().map(|m| m != self.cfg.model).unwrap_or(false);
                    crate::openai::recognize(&self.key_input).map(|k| k.is_claude()).unwrap_or(false) || (self.key_input.trim().is_empty() && has_key && (changed || model_changed))
                }
            }
    }

    fn save_keys(&mut self) {
        // An error from before (a refused sign-in, a missing key) is about the settings being replaced now.
        self.error = None;
        let base = sys::base_dir();
        let was_demo = self.cfg.demo();
        let engine = match self.engine_pick.as_deref() {
            Some("claude-code") if crate::cc::version().is_some() => "claude-code",
            Some("openai") => {
                // The address and the model go to openai.txt; the key is kept by the app. An empty key box keeps the saved key.
                let [url, model, key] = &self.openai_input;
                let key = if key.trim().is_empty() { crate::secret::get(crate::secret::OPENAI).unwrap_or_default() } else { key.trim().to_string() };
                let url = url.trim().trim_end_matches('/');
                // A service APERIT knows: it picks the model from the service's own list.
                let model = match crate::openai::known_by_url(url) {
                    Some(k) if model.trim().is_empty() => k.model(&key),
                    _ => model.trim().to_string(),
                };
                let _ = std::fs::write(base.join("openai.txt"), format!("{url}\n{model}\n"));
                if let Err(e) = crate::secret::set(crate::secret::OPENAI, &key) {
                    self.error = Some(e);
                }
                "openai"
            }
            Some("agent") if !self.agui_input.trim().is_empty() => {
                let _ = std::fs::write(base.join("agui.txt"), format!("{}\n", self.agui_input.trim()));
                "ag-ui"
            }
            Some("agent") => "external",
            Some("ssh") => {
                // Saved under its name (replacing the one being edited) and made the one turns go to.
                let server = server_from(&self.ssh_input);
                if let Err(e) = crate::ssh::save_as(self.ssh_pick.as_deref(), &server) {
                    self.error = Some(e);
                }
                if !self.ssh_input[5].trim().is_empty() {
                    if let Err(e) = server.set_password(self.ssh_input[5].trim()) {
                        self.error = Some(e);
                    }
                }
                self.ssh_input[5].clear();
                self.ssh_pick = Some(server.id());
                "ssh"
            }
            _ => "api",
        };
        let _ = std::fs::write(base.join("engine.txt"), engine);
        if let Some(p) = self.perm_pick.take() {
            let _ = std::fs::write(base.join("claude-permission.txt"), p);
        }
        if let Some(l) = self.level_pick.take() {
            let _ = std::fs::write(base.join("permission.txt"), l);
        }
        if let Some(m) = self.media_pick.take() {
            let _ = std::fs::write(base.join("outside-media.txt"), if m { "yes" } else { "no" });
        }
        if let Some(m) = self.model_pick.take() {
            if engine == "api" {
                if m == llm::DEFAULT_MODEL {
                    let _ = std::fs::remove_file(base.join("model.txt"));
                } else {
                    let _ = std::fs::write(base.join("model.txt"), &m);
                }
            }
        }
        crate::usage::set_limit(self.limit_input.trim().trim_start_matches('$').parse::<f64>().ok());
        // Keys go to the app's encrypted store (secret.rs), never to a file.
        for (name, typed, wanted) in [(crate::secret::CLAUDE, &self.key_input, engine == "api"), (crate::secret::JEV, &self.jev_input, true), (crate::secret::ELEVEN, &self.eleven_input, true)] {
            if wanted && !typed.trim().is_empty() {
                if let Err(e) = crate::secret::set(name, typed) {
                    self.error = Some(e);
                }
            }
        }
        self.key_input.clear();
        self.jev_input.clear();
        self.eleven_input.clear();
        self.openai_input[2].clear();
        self.cfg = llm::Config::load();
        // With an agent outside, the MCP door has to be open for it to come in.
        if self.cfg.external {
            if let Err(e) = crate::mcp::start_outside(crate::mcp::outside_port_setting().unwrap_or(crate::mcp::DEFAULT_PORT)) {
                self.error = Some(e);
            }
        }
        // 데모에서 넘어올 때만 화면을 비우고 실제 모드로 들어간다. 키만 바꾼 경우에는 하던 일을 그대로 둔다.
        if was_demo && !self.cfg.demo() {
            self.recent.clear();
            self.view = ui::View::default();
            self.start_real_mode();
        }
        self.key_popup = false;
        self.keys_loaded = false;
        self.engine_pick = None;
    }
}

/// A column's heading.
fn section(ui: &mut egui::Ui, text: &str) {
    ui.label(egui::RichText::new(text).font(ui::bold(13.5)).color(ui::TEXT()));
    ui.add_space(8.0);
}

/// A field's name.
fn label(ui: &mut egui::Ui, text: &str) {
    ui.label(egui::RichText::new(text).font(ui::bold(12.0)).color(ui::TEXT()));
    ui.add_space(3.0);
}

/// One short line, green when it is good news.
fn small(ui: &mut egui::Ui, text: &str, good: bool) {
    ui.label(egui::RichText::new(text).size(11.5).color(if good { ui::GREEN() } else { ui::FAINT() }));
}

/// A text box in the window's style.
fn input(ui: &mut egui::Ui, value: &mut String, hint: &str, secret: bool) {
    egui::Frame::new().fill(FIELD()).stroke(egui::Stroke::new(1.0, FIELD_EDGE())).corner_radius(12).inner_margin(egui::Margin::symmetric(12, 8)).show(ui, |ui| {
        ui.add(egui::TextEdit::singleline(value).password(secret).hint_text(hint).desired_width(f32::INFINITY).font(egui::FontId::proportional(13.0)).text_color(ui::TEXT()).frame(egui::Frame::NONE))
    });
}

/// Two fields side by side: (name, value, hint, secret, share of the width).
fn pair(ui: &mut egui::Ui, a: (&str, &mut String, &str, bool, f32), b: (&str, &mut String, &str, bool, f32)) {
    fields(ui, vec![a, b]);
}

/// Fields side by side: (name, value, hint, secret, share of the width).
fn fields(ui: &mut egui::Ui, row: Vec<(&str, &mut String, &str, bool, f32)>) {
    let w = ui.available_width() - 10.0 * (row.len() as f32 - 1.0);
    ui.horizontal_top(|ui| {
        ui.spacing_mut().item_spacing.x = 10.0;
        for (name, value, hint, secret, share) in row {
            ui.vertical(|ui| {
                ui.set_width(w * share);
                label(ui, name);
                input(ui, value, hint, secret);
            });
        }
    });
    ui.add_space(8.0);
}

/// "root@1.2.3.4:22", "example.com", "ssh me@host": an address of a server, not a name.
fn looks_like_host(value: &str) -> bool {
    let v = value.trim().trim_start_matches("ssh ").trim();
    !v.is_empty() && !v.contains(char::is_whitespace) && (v.contains('@') || v.contains('.') || v.contains(':'))
}

/// A small choice drawn as a pill, as wide as its text. `in_use` marks what is saved now.
fn chip(ui: &mut egui::Ui, text: &str, on: bool, in_use: bool) -> bool {
    let tw = ui.fonts_mut(|f| f.layout_no_wrap(text.to_string(), ui::bold(12.0), ui::TEXT()).size().x);
    let w = tw + 28.0 + if in_use { 12.0 } else { 0.0 };
    let (r, p) = ui.allocate_painter(egui::vec2(w, 28.0), egui::Sense::click());
    p.rect_filled(r.rect, 14.0, if on { ui::ORANGE().gamma_multiply(0.14) } else { FIELD() });
    p.rect_stroke(r.rect, 14.0, egui::Stroke::new(1.0, if on { ui::ORANGE() } else { FIELD_EDGE() }), egui::StrokeKind::Inside);
    p.text(r.rect.left_center() + egui::vec2(14.0, 0.0), egui::Align2::LEFT_CENTER, text, ui::bold(12.0), ui::TEXT());
    if in_use {
        p.circle_filled(egui::pos2(r.rect.right() - 14.0, r.rect.center().y), 3.5, ui::GREEN());
    }
    r.on_hover_cursor(egui::CursorIcon::PointingHand).clicked()
}

/// A choice drawn as a tile: a title and a line under it. `in_use` marks what is saved now.
fn tile(ui: &mut egui::Ui, w: f32, h: f32, title: &str, sub: &str, on: bool, in_use: bool) -> bool {
    let (r, p) = ui.allocate_painter(egui::vec2(w, h), egui::Sense::click());
    p.rect_filled(r.rect, 14.0, if on { ui::ORANGE().gamma_multiply(0.14) } else { FIELD() });
    p.rect_stroke(r.rect, 14.0, egui::Stroke::new(if on { 1.5 } else { 1.0 }, if on { ui::ORANGE() } else { FIELD_EDGE() }), egui::StrokeKind::Inside);
    p.text(r.rect.left_top() + egui::vec2(14.0, h * 0.36), egui::Align2::LEFT_CENTER, title, ui::bold(13.0), ui::TEXT());
    p.text(r.rect.left_top() + egui::vec2(14.0, h * 0.70), egui::Align2::LEFT_CENTER, sub, ui::font(11.0), ui::FAINT());
    if in_use {
        p.circle_filled(egui::pos2(r.rect.right() - 14.0, r.rect.top() + h * 0.36), 4.0, ui::GREEN());
    }
    r.on_hover_cursor(egui::CursorIcon::PointingHand).clicked()
}

/// Three buttons in a row, one of them chosen. Returns the one pressed.
fn three(ui: &mut egui::Ui, now: &str, options: [(&str, &str); 3]) -> Option<String> {
    let mut picked = None;
    ui.horizontal(|ui| {
        ui.spacing_mut().item_spacing.x = 6.0;
        let third = (ui.available_width() - 12.0) / 3.0;
        for (value, name) in options {
            let on = now == value;
            let (r, p) = ui.allocate_painter(egui::vec2(third, 30.0), egui::Sense::click());
            p.rect_filled(r.rect, 10.0, if on { ui::ORANGE().gamma_multiply(0.14) } else { FIELD() });
            p.rect_stroke(r.rect, 10.0, egui::Stroke::new(1.0, if on { ui::ORANGE() } else { FIELD_EDGE() }), egui::StrokeKind::Inside);
            p.text(r.rect.center(), egui::Align2::CENTER_CENTER, name, ui::bold(12.0), ui::TEXT());
            if r.on_hover_cursor(egui::CursorIcon::PointingHand).clicked() {
                picked = Some(value.to_string());
            }
        }
    });
    picked
}

/// The server typed in the Settings window: host, user, port, key file, folder, -, name (the password is kept apart).
fn server_from(input: &[String; 7]) -> crate::ssh::Server {
    let name: String = input[6].trim().chars().filter(|c| !"[]=\r\n".contains(*c)).collect();
    let host = input[0].trim().to_string();
    let claude = crate::ssh::find(if name.is_empty() { &host } else { &name }).map(|s| s.claude).unwrap_or_default();
    crate::ssh::Server { name, host, user: input[1].trim().into(), port: input[2].trim().parse().unwrap_or(0), key: input[3].trim().into(), dir: input[4].trim().into(), claude }
}
