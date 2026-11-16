//! 보드를 그린다. 배치 계산, 전환, 부품별 그리기, 사용자의 직접 조작.
//! 사용자의 조작은 화면 수정 언어 한 줄로 바꿔서 돌려준다 (AI 의 조작과 같은 경로를 탄다).
//!
//! 디자인: 무광 검정. 채도 없는 회색조, 큰 둥근 모서리, 굵은 제목과 숫자,
//! "굵은 항목명 + 옅은 값", 테두리만 있는 알약형 버튼.
//! 모든 변화는 시간 기반 이징(ease in/out)으로 움직인다.

use std::collections::{HashMap, HashSet};

use eframe::egui::{
    self, Align2, Color32, CornerRadius, CursorIcon, FontFamily, FontId, Painter, Pos2, Rect, Sense, Stroke, StrokeKind,
    Vec2, pos2, vec2,
};

use crate::core::{Board, Card, Kind};

pub const CW: f32 = 120.0;
pub const CH: f32 = 104.0;
pub const GAP: f32 = 14.0;
const HEAD: f32 = 46.0;
const PAD: f32 = 22.0;
const RADIUS: u8 = 20;

/// 밝은 화면인가. 처음에는 OS 설정을 따르고, 사용자가 바꾸면 data/theme.txt 에 남는다.
static LIGHT: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);

pub fn set_light(on: bool) {
    LIGHT.store(on, std::sync::atomic::Ordering::Relaxed);
}

pub fn is_light() -> bool {
    LIGHT.load(std::sync::atomic::Ordering::Relaxed)
}

/// (어두울 때, 밝을 때)
const fn pick(dark: (u8, u8, u8), light: (u8, u8, u8)) -> [Color32; 2] {
    [Color32::from_rgb(dark.0, dark.1, dark.2), Color32::from_rgb(light.0, light.1, light.2)]
}

macro_rules! palette {
    ($($(#[$m:meta])* $vis:vis $name:ident = $dark:expr, $light:expr;)*) => {
        $(
            $(#[$m])*
            #[allow(non_snake_case)]
            $vis fn $name() -> Color32 {
                const C: [Color32; 2] = pick($dark, $light);
                C[is_light() as usize]
            }
        )*
    };
}

palette! {
    pub BG = (16, 17, 19), (243, 243, 241);
    CARD = (28, 29, 32), (255, 255, 255);
    RAISED = (41, 42, 46), (236, 236, 238);
    BORDER = (50, 51, 56), (221, 221, 225);
    EDGE = (104, 105, 111), (150, 151, 158);
    pub TEXT = (218, 219, 223), (26, 27, 30);
    SOFT = (184, 185, 190), (64, 65, 71);
    pub DIM = (138, 139, 145), (108, 109, 116);
    pub FAINT = (88, 89, 95), (160, 161, 168);
    pub GREEN = (142, 198, 162), (34, 135, 80);
    AMBER = (216, 186, 130), (168, 112, 20);
    pub RED = (222, 134, 128), (196, 64, 56);
    pub ZONE = (21, 22, 25), (234, 234, 236);
    pub ZONE_EDGE = (33, 34, 38), (220, 220, 224);
    pub ORANGE = (255, 150, 50), (232, 116, 12);
}


// 전환 시간 (초). 자리 이동은 시간이 아니라 용수철로 움직인다 (`Tween`).
const T_IN: f64 = 0.38;
const T_OUT: f64 = 0.26;
const T_ITEM: f64 = 0.34;
const T_NUM: f64 = 0.65;
const T_FLASH: f64 = 1.1;
/// 어느 분류에도 해당하지 않는 이름 (분류 화면에서 주제를 하나도 놓지 않을 때 쓴다)
const NO_GROUP: &str = "{1}none";

// ---- 이징 함수 (easings.net 의 표준 곡선) ----
pub fn ease_out_cubic(t: f32) -> f32 {
    1.0 - (1.0 - t).powi(3)
}
pub fn ease_in_cubic(t: f32) -> f32 {
    t * t * t
}
pub fn ease_in_out_cubic(t: f32) -> f32 {
    if t < 0.5 { 4.0 * t * t * t } else { 1.0 - (-2.0 * t + 2.0).powi(3) / 2.0 }
}
fn unit(now: f64, t0: f64, dur: f64) -> f32 {
    (((now - t0) / dur) as f32).clamp(0.0, 1.0)
}

fn mix(a: Color32, b: Color32, t: f32) -> Color32 {
    let l = |x: u8, y: u8| (x as f32 + (y as f32 - x as f32) * t.clamp(0.0, 1.0)) as u8;
    Color32::from_rgb(l(a.r(), b.r()), l(a.g(), b.g()), l(a.b(), b.b()))
}

pub fn font(size: f32) -> FontId {
    FontId::proportional(size)
}
pub fn bold(size: f32) -> FontId {
    FontId::new(size, FontFamily::Name("bold".into()))
}

/// 목표값을 따라가는 2차원 값. 임계 감쇠 용수철처럼 움직인다.
/// 가는 도중에 목표가 바뀌어도(창 크기를 끌어 바꾸면 매 프레임 바뀐다) 지금의 속도를 이어받으므로 끊기지 않는다.
/// (예전에는 목표가 바뀔 때마다 이징을 처음부터 다시 시작해서, 속도가 0으로 떨어졌다 붙었다 하며 끊겨 보였다.)
/// 시간에 대한 식으로 풀어 두어서 프레임 간격과 상관없이 같은 길을 간다.
struct Tween {
    /// t0 때의 위치와 속도
    from: Vec2,
    vel: Vec2,
    to: Vec2,
    t0: f64,
    /// 빠르기 (ω)
    w: f32,
}

/// 카드·구역 용수철의 빠르기. 멈춘 상태에서 출발하면 0.34초에 95%, 0.47초에 99% 간다.
const SPRING: f32 = 14.0;
/// 카메라 용수철의 빠르기. 창 끝을 끄는 손을 카드보다 바짝(0.1초 안팎 늦게) 따라온다.
const CAM_SPRING: f32 = 22.0;

impl Tween {
    fn fixed(v: Vec2) -> Self {
        Self::spring(v, SPRING)
    }
    fn spring(v: Vec2, w: f32) -> Self {
        Tween { from: v, vel: Vec2::ZERO, to: v, t0: 0.0, w }
    }
    /// (위치, 속도). x(t) = 목표 + (a + b·t)·e^(-ωt)
    fn state(&self, now: f64) -> (Vec2, Vec2) {
        let t = (now - self.t0).max(0.0) as f32;
        let a = self.from - self.to;
        let b = self.vel + a * self.w;
        let e = (-self.w * t).exp();
        (self.to + (a + b * t) * e, (self.vel - b * (self.w * t)) * e)
    }
    fn at(&self, now: f64) -> Vec2 {
        if self.busy(now) { self.state(now).0 } else { self.to }
    }
    fn retarget(&mut self, to: Vec2, now: f64) {
        if (to - self.to).length() > 0.5 {
            let (from, vel) = self.state(now);
            *self = Tween { from, vel, to, t0: now, w: self.w };
        }
    }
    fn busy(&self, now: f64) -> bool {
        let (p, v) = self.state(now);
        (p - self.to).length() > 0.2 || v.length() > 2.0
    }
}

struct CardView {
    pos: Tween,
    size: Tween,
    born: f64,
    rev: u64,
    flash: f64,
    dragging: bool,
    resizing: bool,
    /// 마지막으로 그린 내용. 카드가 지워지면 이것으로 사라지는 모습을 그린다.
    snap: Card,
    dying: Option<f64>,
    /// 화면을 내려도 제자리에 붙어 있는 카드인가 ("내 차례" 구역)
    sticky: bool,
    /// 내용이 실제로 차지한 높이 (다음 프레임의 카드 높이 계산에 쓴다)
    content_h: f32,
    /// 항목별 등장 시각
    items: HashMap<String, f64>,
    /// 숫자 값의 전환 (막대 길이, 진행률)
    nums: HashMap<String, (f32, f32, f64)>,
    /// 입력 카드의 칸에 사용자가 적고 있는 글 (항목 ID → 글)
    inputs: HashMap<String, String>,
}

#[derive(Default)]
pub struct View {
    cards: HashMap<String, CardView>,
    /// 주제 구역의 위치·크기 전환과 등장 시각
    zones: HashMap<String, (Tween, Tween, f64)>,
    /// 카드별 중요도 (판단 모델이 매긴 것). 구역 안에서 중요한 카드를 앞에 놓는 데 쓴다.
    importance: HashMap<String, f64>,
    /// 판에서 지금 보고 있는 범위 (확대·축소와 이동의 결과)
    scene: Option<Rect>,
    /// 보는 범위가 벗어날 수 없는 한계
    limit: Option<Rect>,
    /// 지난 프레임의 "전체 보기" 범위
    fit: Option<Rect>,
    /// 다른 곳으로 날아가는 중: (출발 범위, 도착 범위, 시작 시각)
    fly: Option<(Rect, Rect, f64)>,
    /// 지금의 이동이 "더 축소할 수 없어서 튕겨 돌아오는 것"인가
    bounce: bool,
    /// "내 차례" 구역을 스크롤한 거리
    inbox_scroll: f32,
    /// 지금의 확대 비율 (1 = 제 크기)
    pub zoom: f32,
    /// 다음 프레임에 화면에 들어오게 할 주제
    goto: Option<String>,
    /// 지금 사용자 앞에 가져다 놓은 일 (결정 카드의 ID). 이 카드는 판이 아니라 왼쪽의 "내 차례" 자리에 그린다.
    pub task: Option<String>,
    /// 사용자가 눌러서 고른 카드. 있으면 "내 차례" 자리에 일 대신 이 카드를 놓고, 판에는 같은 주제의 카드만 보인다.
    pub picked: Option<String>,
    /// 처음 보기로 돌아가 달라는 예약 (고른 카드를 닫았을 때)
    home_req: bool,
    /// 왼쪽 "내 차례" 자리의 화면 위치 (대화 구역이 정해 준다)
    pub task_rect: Option<Rect>,
    /// 이 주제의 구역을 "내 차례" 자리 바로 옆에 붙여 보여 달라는 예약
    dock: Option<String>,
    /// 지금 화면을 맞춰 둔 주제와 그때의 보는 범위. 그 구역의 크기가 바뀌면(카드가 늘거나 줄면) 다시 맞춘다.
    /// 사용자가 손으로 화면을 옮기면 그만둔다.
    docked: Option<(String, Rect)>,
    /// 이 카드가 제 크기(100%)로 화면 안에 다 들어오게 해 달라는 예약
    card_goto: Option<String>,
    /// 지난 프레임의 판 크기. 창 크기가 바뀐 것을 알아채는 데 쓴다.
    area: Option<Vec2>,
    /// 지금 보는 단계: 0 카드, 1 주제(이름만), 2 분류(이름만). 스크롤 한 번에 한 단계씩 옮긴다.
    /// 무엇을 보일지는 확대 비율이 아니라 이 단계가 정한다.
    pub level: u8,
    /// 단계별 보이는 정도의 전환 (카드, 주제 이름, 분류 이름)
    blend: HashMap<String, (f32, f32, f64)>,
    /// 아직 한 단계를 채우지 못한 스크롤 양, 마지막으로 스크롤이 들어온 시각
    wheel: (f32, f64),
    /// 이 시각까지는 단계를 다시 옮기지 않는다 (한 번의 손짓이 이어지는 동안 여러 단계를 건너뛰지 않게)
    step_lock: f64,
    /// 창 크기를 바꾸는 동안의 카메라: (보는 범위의 왼쪽 위, 확대 비율×1000) 을 따라가는 용수철.
    /// 이때는 정해진 시간에 날아가지 않는다 (목표가 매 프레임 바뀌므로). 가라앉으면 없어진다.
    follow: Option<(Tween, Tween)>,
    /// 사용자가 "+N개 더"를 눌러 접힌 카드까지 다 펼쳐 둔 주제
    unfolded: HashSet<String>,
    /// 카드별로 이 창 크기에서 잰 가장 큰 내용 높이 (카드 rev, 높이). 몇 장을 접을지 정할 때만 쓴다:
    /// 접는 수가 바뀌면 카드 폭이 바뀌고, 폭이 바뀌면 높이가 바뀌어서, 지금 높이로만 정하면 접었다 폈다를 되풀이할 수 있다.
    tall: HashMap<String, (u64, f32)>,
}

impl View {
    /// 이 주제가 화면에 보이게 해 달라고 예약한다 (안 보이거나 너무 작으면 그쪽으로 날아간다).
    /// 지금 보고 있는 범위와, 벗어날 수 없는 범위 (확인용으로 밖에서 읽는다).
    pub fn seen(&self) -> Option<(Rect, Rect)> {
        Some((self.scene?, self.limit?))
    }

    /// 이 카드가 제 크기로 화면에 다 보이게 한다 (웹 카드는 그래야 페이지가 보인다).
    pub fn show_card(&mut self, card: String) {
        self.card_goto = Some(card);
    }

    /// 사용자 앞에 가져온 일이 속한 주제를, 그 일 바로 옆(판의 왼쪽 위)에 붙여 보여 준다.
    pub fn dock_topic(&mut self, topic: String) {
        self.dock = Some(topic);
    }

    pub fn want_topic(&mut self, topic: String) {
        self.goto = Some(topic);
    }

    /// 카드를 골라 왼쪽 자리에 놓는다 (None 이면 닫고 처음 보기로 돌아간다). `topic` 은 그 카드의 주제.
    /// 고른 카드의 주제가 판의 왼쪽 위, 그 카드 바로 옆에 붙는다.
    pub fn pick(&mut self, card: Option<String>, topic: Option<String>) {
        match (card, topic) {
            (Some(c), Some(t)) => {
                self.picked = Some(c);
                self.dock = Some(t);
                self.level = 0;
            }
            _ => {
                if self.picked.take().is_some() {
                    self.home_req = true;
                }
            }
        }
    }

    /// 왼쪽 "내 차례" 자리에 놓을 카드: 사용자가 고른 카드, 없으면 AI 가 가져온 일.
    pub fn slot_card(&self) -> Option<&str> {
        self.picked.as_deref().or(self.task.as_deref())
    }

    /// 지금 단계의 이름 (상태 줄에 쓴다)
    pub fn level_name(&self) -> &'static str {
        match self.level {
            2 => crate::sys::tr("분류", "groups"),
            1 => crate::sys::tr("주제", "topics"),
            _ => crate::sys::tr("카드", "cards"),
        }
    }

    pub fn set_importance(&mut self, m: HashMap<String, f64>) {
        // 이번에 검토한 카드의 값만 고친다 (나머지 카드의 값은 그대로 둔다).
        self.importance.extend(m);
    }
}

/// 웹 카드 하나가 화면에서 차지한 자리.
pub struct WebSlot {
    pub id: String,
    pub rect: Rect,
    pub url: String,
    pub visible: bool,
}


/// 카드가 실제로 차지할 세로 칸 수. 내용이 짧으면 줄어든다 (사용자가 크기를 정한 카드는 그대로).
fn eff_h(c: &Card, view: &View) -> i32 {
    let h = c.h as i32;
    if c.pinned {
        return h;
    }
    match view.cards.get(&c.id) {
        Some(v) if v.content_h > 0.0 => {
            let need = ((HEAD + v.content_h + 12.0 + GAP) / (CH + GAP)).ceil() as i32;
            need.clamp(1, h)
        }
        _ => h,
    }
}

// 주제 구역: 안쪽 여백, 제목 줄 높이, 구역 사이 간격
const ZONE_PAD: f32 = 14.0;
const ZONE_HEAD: f32 = 44.0;
const ZONE_GAP: f32 = 16.0;

/// 카드 한 장이 놓일 자리.
struct Slot {
    /// 보드 기준 위치 (픽셀)
    pos: Vec2,
    w: i32,
    #[allow(dead_code)]
    h: i32,
    col: i32,
    row: i32,
    /// 화면을 내려도 제자리에 붙어 있는 카드인가 ("내 차례" 구역의 카드)
    sticky: bool,
    /// 이 카드가 속한 주제 구역 안에서 격자가 시작되는 지점
    origin: Vec2,
    /// 이 구역에서 한 칸의 가로 폭. 구역이 화면 폭을 채우도록 칸을 늘리므로 구역마다 다르다.
    cw: f32,
    /// 카드의 세로 크기 (픽셀). 줄 높이는 그 줄에 놓인 카드들의 내용에 맞추므로 칸 수만으로는 정해지지 않는다.
    ph: f32,
}

/// 주제 하나의 구역.
struct Zone {
    id: String,
    name: String,
    min: Vec2,
    size: Vec2,
    /// 답이 필요한 카드가 모이는 "내 차례" 구역인가
    inbox: bool,
    /// 읽을 수 있는 크기로 한 화면에 넣으려고 접은(또는 펼쳐 두지 않았다면 접었을) 카드 수
    hidden: usize,
    /// 사용자가 펼쳐 두어서 접지 않고 다 보여 주는 중인가
    unfolded: bool,
}

/// 배치. 주제마다 구역을 하나씩 만들고, 카드는 자기 주제의 구역 안에서만 격자에 놓는다.
/// 구역 안에서는 위치가 정해진 카드를 먼저 놓고 나머지를 빈 곳에 채운다.
/// 화면이 넓으면 구역을 두 줄로 나란히 놓아 여러 주제가 한 화면에 보이게 한다.
fn layout(board: &Board, width: f32, screen: Vec2, view: &View, only: Option<&str>, with_inbox: bool) -> (HashMap<String, Slot>, Vec<Zone>) {
    let cells = |avail: f32| (((avail - 2.0 * ZONE_PAD + GAP) / (CW + GAP)).floor() as i32).max(2);
    // 답이 필요한 카드(decision)는 주제 구역에 두지 않고 오른쪽 "내 차례" 구역으로 모은다.
    let asks: Vec<&Card> = board.cards.iter().filter(|c| view.slot_card() == Some(c.id.as_str())).collect();
    let away = |c: &Card| view.slot_card() == Some(c.id.as_str());
    // 사용자가 카드를 골랐으면 판에는 그 카드와 같은 주제의 카드만 놓는다.
    let only_topic = view.picked.as_deref().and_then(|id| board.card(id)).map(|c| c.topic.as_str());
    let topics: Vec<&crate::core::Topic> = board
        .topics
        .iter()
        .filter(|t| only.map(|g| t.group.as_deref().unwrap_or("") == g).unwrap_or(true))
        .filter(|t| only_topic.map(|o| o == t.id).unwrap_or(true))
        .filter(|t| board.cards.iter().any(|c| c.topic == t.id && !away(c)))
        .collect();
    // 한 주제의 카드는 스크롤 없이 한 화면에 들어와야 한다. 그래서 구역마다 가로 칸 수를 몇 가지로 놓아 보고,
    // 화면에 가장 크게 들어오는 배치를 고른다. 칸을 늘리면 구역이 넓고 낮아져서, 조금 축소하면 통째로 보인다.
    // 제 크기(100%)로 들어오면 그것이 가장 좋고, 아니면 FIT_MIN 까지 축소해서 들어오는 것을 찾는다.
    let room = vec2((screen.x - 32.0).max(CW * 3.0), (screen.y - 32.0).max(CH * 2.0));
    let (lo, hi) = (cells(room.x), cells(room.x / FIT_MIN));

    let mut slots = HashMap::new();
    let mut zones = Vec::new();
    let (mut x, mut y, mut shelf_h) = (0.0_f32, 0.0_f32, 0.0_f32);
    for t in topics {
        let mine: Vec<&Card> = board.cards.iter().filter(|c| c.topic == t.id && !away(c)).collect();
        // `show`(mine 의 번호, 작은 것부터) 카드만 가로 `limit` 칸으로 놓아 본다. (놓인 자리들, 쓴 가로 칸 수, 쓴 세로 칸 수)
        let place = |show: &[usize], limit: i32| -> (Vec<(&Card, i32, i32, i32, i32)>, i32, i32) {
            let mine: Vec<&Card> = show.iter().map(|i| mine[*i]).collect();
            let mut used: HashSet<(i32, i32)> = HashSet::new();
            let mut placed: Vec<(&Card, i32, i32, i32, i32)> = Vec::new();
            let width_of = |c: &Card| (c.w as i32).min(limit);
            let occupy = |used: &mut HashSet<(i32, i32)>, col: i32, row: i32, w: i32, h: i32| {
                for cx in col..col + w {
                    for cy in row..row + h {
                        used.insert((cx, cy));
                    }
                }
            };
            for c in mine.iter().filter(|c| c.grid.is_some()) {
                let (col, row) = c.grid.unwrap();
                let (w, h) = (width_of(c), eff_h(c, view));
                let col = col.min((limit - w).max(0));
                occupy(&mut used, col, row, w, h);
                placed.push((*c, col, row, w, h));
            }
            // 자리가 정해지지 않은 카드는 중요한 것부터 놓는다 (세 단계로만 나눠서 작은 차이로 자리가 바뀌지 않게 한다).
            let mut free: Vec<&&Card> = mine.iter().filter(|c| c.grid.is_none()).collect();
            free.sort_by_key(|c| -((view.importance.get(&c.id).copied().unwrap_or(0.5) * 2.99) as i32));
            for c in free {
                let (w, h) = (width_of(c), eff_h(c, view));
                let mut row = 0;
                let (col, row) = 'find: loop {
                    for col in 0..=(limit - w) {
                        if (col..col + w).all(|cx| (row..row + h).all(|cy| !used.contains(&(cx, cy)))) {
                            break 'find (col, row);
                        }
                    }
                    row += 1;
                };
                occupy(&mut used, col, row, w, h);
                placed.push((*c, col, row, w, h));
            }
            let ncols = placed.iter().map(|p| p.1 + p.3).max().unwrap_or(1);
            let nrows = placed.iter().map(|p| p.2 + p.4).max().unwrap_or(1);
            // 오른쪽에 빈 칸이 남은 카드는 그만큼 넓힌다 (구역 안에 구멍이 남지 않게). 사용자가 자리를 정한 카드는 그대로 둔다.
            let mut order: Vec<usize> = (0..placed.len()).collect();
            order.sort_by_key(|i| (placed[*i].2, placed[*i].1));
            for i in order {
                let (c, col, row, mut w, h) = placed[i];
                if c.pinned || c.grid.is_some() {
                    continue;
                }
                while col + w < ncols && (row..row + h).all(|cy| !used.contains(&(col + w, cy))) {
                    for cy in row..row + h {
                        used.insert((col + w, cy));
                    }
                    w += 1;
                }
                placed[i].3 = w;
            }
            (placed, ncols, nrows)
        };
        // 줄 높이는 칸 단위로 끊지 않고 그 줄에 놓인 카드의 내용에 맞춘다. 칸 단위로 끊으면 카드마다 아래에 빈 자리가 남는다.
        // 카드가 필요한 높이: 내용의 높이 (카드가 정한 칸 수를 넘지는 않는다). 사용자가 크기를 정한 카드는 정한 그대로.
        // `sure` 이면 이 창 크기에서 잰 가장 큰 높이를 쓴다 (몇 장을 접을지 정할 때. `View::tall` 참고).
        let need_px = |c: &Card, h: i32, sure: bool| -> f32 {
            let full = h as f32 * (CH + GAP) - GAP;
            if c.pinned {
                return full;
            }
            let now_h = view.cards.get(&c.id).map(|v| v.content_h).filter(|h| *h > 0.0);
            let most = view.tall.get(&c.id).filter(|t| sure && t.0 == c.rev && t.1 > 0.0).map(|t| t.1);
            match (now_h, most) {
                (Some(a), Some(b)) => Some(a.max(b)),
                (a, b) => a.or(b),
            }
            // 내용이 카드가 정한 크기보다 길면, 화면 한 장에 들어오는 데까지는 카드를 키운다 (카드 안에서 잘리지 않게).
            .map(|ch| (HEAD + ch + 14.0).clamp(72.0, full.max(room.y - ZONE_HEAD - ZONE_PAD)))
            .unwrap_or(full)
        };
        // 각 줄의 높이: 끝나는 줄이 이른 카드부터, 카드가 걸친 줄들의 합이 필요한 높이가 되도록 마지막 줄을 늘린다.
        let row_heights = |placed: &Vec<(&Card, i32, i32, i32, i32)>, nrows: i32, sure: bool| -> Vec<f32> {
            let mut rh = vec![0.0_f32; nrows.max(1) as usize];
            let mut order: Vec<usize> = (0..placed.len()).collect();
            order.sort_by_key(|i| placed[*i].2 + placed[*i].4);
            for i in order {
                let (c, _, row, _, h) = placed[i];
                let span = row as usize..(row + h) as usize;
                let have: f32 = rh[span.clone()].iter().sum::<f32>() + (h - 1) as f32 * GAP;
                let need = need_px(c, h, sure);
                if have < need {
                    rh[span.end - 1] += need - have;
                }
            }
            rh
        };
        let natural = |ncols: i32, rh: &Vec<f32>| vec2(ncols as f32 * (CW + GAP) - GAP + 2.0 * ZONE_PAD, ZONE_HEAD + rh.iter().sum::<f32>() + (rh.len() as f32 - 1.0) * GAP + ZONE_PAD);
        // 칸 수 후보마다 놓아 보고 화면에 가장 크게 들어오는 배치를 고른다. (축소 비율, 칸 수, 놓인 자리, 쓴 칸 수, 줄 높이)
        let arrange = |show: &[usize], sure: bool| {
            let mut best: Option<(f32, i32, Vec<(&Card, i32, i32, i32, i32)>, i32, Vec<f32>)> = None;
            for limit in lo..=hi.max(lo) {
                let (placed, ncols, nrows) = place(show, limit);
                let rh = row_heights(&placed, nrows, sure);
                let n = natural(ncols, &rh);
                let k = (room.x / n.x).min(room.y / n.y).min(1.0);
                // 눈에 띄게 더 크게 들어올 때만 칸을 늘린 배치로 바꾼다 (같으면 카드가 큰 쪽).
                if best.as_ref().map(|b| k > b.0 + 0.02).unwrap_or(true) {
                    best = Some((k, limit, placed, ncols, rh));
                }
                if k >= 1.0 {
                    break;
                }
            }
            best.expect("칸 수 후보는 하나 이상이다")
        };
        // 읽을 수 있는 크기(FIT_MIN)로 줄여도 한 화면에 다 안 들어가면, 덜 중요한 카드부터 접는다 (구역 머리에 "+N개 더").
        // 남기는 순서: AI 가 짚은 카드, 답이 필요한 카드, 사용자가 자리나 크기를 정한 카드, 중요도, 먼저 생긴 카드.
        // 중요도가 같으면 먼저 생긴 카드를 남긴다: AI 는 보통 가장 중요한 카드부터 만들고,
        // 새 카드가 들어올 때마다 있던 카드가 빠지면 판이 계속 뒤섞인다 (새 카드는 "+N개 더"로 간다).
        let mut rank: Vec<usize> = (0..mine.len()).collect();
        let keep = |i: usize| {
            let c = mine[i];
            let imp = view.importance.get(&c.id).copied().unwrap_or(0.5);
            (board.focus.as_deref() == Some(c.id.as_str()), c.kind.asks(), c.pinned || c.grid.is_some(), (imp * 1000.0) as i64, std::cmp::Reverse(i))
        };
        rank.sort_by_key(|i| std::cmp::Reverse(keep(*i)));
        let first = |n: usize| {
            let mut s = rank[..n].to_vec();
            s.sort_unstable();
            s
        };
        let fits = |n: usize| arrange(&first(n), true).0 >= FIT_MIN - 0.005;
        let mut shown = mine.len();
        if shown > 1 && !fits(shown) {
            // 적게 놓을수록 잘 들어가므로, 들어가는 가장 많은 수를 반으로 나눠 찾는다. 한 장은 늘 보인다.
            let (mut ok, mut bad) = (1, shown);
            while bad - ok > 1 {
                let mid = (ok + bad) / 2;
                if fits(mid) { ok = mid } else { bad = mid }
            }
            shown = ok;
        }
        let hidden = mine.len() - shown;
        let unfolded = hidden > 0 && view.unfolded.contains(&t.id);
        let (k, _, placed, ncols, rh) = arrange(&first(if unfolded { mine.len() } else { shown }), false);
        // 펼쳐 둔 주제는 읽을 수 있는 크기를 지키고 세로로 넘치게 둔다 (끌어서 본다).
        let k = if unfolded { k.max(FIT_MIN) } else { k };
        // 각 줄이 시작하는 위치
        let mut tops = Vec::with_capacity(rh.len() + 1);
        let mut acc = 0.0_f32;
        for h in &rh {
            tops.push(acc);
            acc += h + GAP;
        }
        tops.push(acc);
        // 그 축소 비율에서 화면의 가로를 다 쓰도록 칸을 늘린다. 카드가 너무 넓적해지지 않게 1.6배까지만.
        let cw = (((room.x / k - 2.0 * ZONE_PAD) - (ncols - 1) as f32 * GAP) / ncols as f32).clamp(CW, CW * 1.6 / k);
        let size = vec2(ncols as f32 * (cw + GAP) - GAP + 2.0 * ZONE_PAD, natural(ncols, &rh).y);
        if x > 0.0 && x + size.x > width + 1.0 {
            x = 0.0;
            y += shelf_h + ZONE_GAP;
            shelf_h = 0.0;
        }
        let min = vec2(x, y);
        let origin = min + vec2(ZONE_PAD, ZONE_HEAD);
        for (c, col, row, w, h) in placed {
            let (top, end) = (tops[row as usize], tops[(row + h) as usize] - GAP);
            let pos = origin + vec2(col as f32 * (cw + GAP), top);
            slots.insert(c.id.clone(), Slot { pos, w, h, col, row, origin, sticky: false, cw, ph: end - top });
        }
        zones.push(Zone { id: t.id.clone(), name: t.name.clone(), min, size, inbox: false, hidden, unfolded });
        x += size.x + ZONE_GAP;
        shelf_h = shelf_h.max(size.y);
    }
    // "내 차례": 지금 사용자 앞에 가져온 일 하나. 판이 아니라 왼쪽 자리에 그리므로 위치는 그 자리의 왼쪽 위(0, 0) 기준이다.
    if with_inbox {
        if let Some(c) = asks.first() {
            let h = match view.cards.get(&c.id) {
                Some(v) if v.content_h > 0.0 => (((HEAD + v.content_h + 12.0 + GAP) / (CH + GAP)).ceil() as i32).clamp(1, 12),
                _ => eff_h(c, view),
            };
            slots.insert(c.id.clone(), Slot { pos: vec2(0.0, 0.0), w: INBOX_COLS, h, col: 0, row: 0, origin: vec2(0.0, 0.0), sticky: true, cw: CW, ph: h as f32 * (CH + GAP) - GAP });
            let size = vec2(INBOX_COLS as f32 * (CW + GAP) - GAP, h as f32 * (CH + GAP) - GAP);
            zones.push(Zone { id: INBOX_ID.into(), name: "내 차례".into(), min: vec2(0.0, 0.0), size, inbox: true, hidden: 0, unfolded: false });
        }
    }
    (slots, zones)
}

/// "내 차례" 구역의 가로 칸 수와 내부 이름
const INBOX_COLS: i32 = 3;
const INBOX_ID: &str = "\u{1}inbox";


/// 이름 붙은 숫자 하나를 목표값으로 이징한다. 반환값은 (현재 값, 아직 움직이는 중인가).
fn tween_num(map: &mut HashMap<String, (f32, f32, f64)>, key: &str, target: f32, now: f64, dur: f64) -> (f32, bool) {
    let e = map.entry(key.to_string()).or_insert((target, target, now - dur));
    if (e.1 - target).abs() > 1e-4 {
        *e = (e.0 + (e.1 - e.0) * ease_out_cubic(unit(now, e.2, dur)), target, now);
    }
    let t = unit(now, e.2, dur);
    (e.0 + (e.1 - e.0) * ease_out_cubic(t), t < 1.0)
}

/// 둥근 사각형 둘레를 시계 방향으로 d 만큼 간 지점.
fn perimeter_point(r: Rect, rad: f32, d: f32) -> Pos2 {
    use std::f32::consts::{FRAC_PI_2, PI};
    let (w, h) = (r.width() - 2.0 * rad, r.height() - 2.0 * rad);
    let arc = FRAC_PI_2 * rad;
    let mut d = d.rem_euclid(2.0 * (w + h) + 4.0 * arc);
    let on = |center: Pos2, a: f32| center + vec2(a.cos(), a.sin()) * rad;
    if d < w {
        return pos2(r.left() + rad + d, r.top());
    }
    d -= w;
    if d < arc {
        return on(pos2(r.right() - rad, r.top() + rad), -FRAC_PI_2 + d / rad);
    }
    d -= arc;
    if d < h {
        return pos2(r.right(), r.top() + rad + d);
    }
    d -= h;
    if d < arc {
        return on(pos2(r.right() - rad, r.bottom() - rad), d / rad);
    }
    d -= arc;
    if d < w {
        return pos2(r.right() - rad - d, r.bottom());
    }
    d -= w;
    if d < arc {
        return on(pos2(r.left() + rad, r.bottom() - rad), FRAC_PI_2 + d / rad);
    }
    d -= arc;
    if d < h {
        return pos2(r.left(), r.bottom() - rad - d);
    }
    on(pos2(r.left() + rad, r.top() + rad), PI + (d - h) / rad)
}

/// 처리 중 표시: 주황색 선이 꼬리를 끌며 카드 테두리를 돈다. `k` 는 표시 강도(0~1).
pub fn draw_runner(p: &Painter, rect: Rect, rad: f32, now: f64, k: f32) {
    let r = rect.shrink(0.75);
    let rad = rad - 0.75;
    let total = 2.0 * (r.width() + r.height() - 4.0 * rad) + std::f32::consts::TAU * rad;
    p.rect_stroke(rect, rad, Stroke::new(1.0, ORANGE().gamma_multiply(0.22 * k)), StrokeKind::Inside);
    let head = ((now / 1.7).fract() as f32) * total;
    let len = total * 0.3;
    let n = 44;
    for i in 0..n {
        let (a, b) = (i as f32 / n as f32, (i + 1) as f32 / n as f32);
        let alpha = b * b * k;
        p.line_segment(
            [perimeter_point(r, rad, head - len + len * a), perimeter_point(r, rad, head - len + len * b)],
            Stroke::new(2.0, ORANGE().gamma_multiply(alpha)),
        );
    }
    p.circle_filled(perimeter_point(r, rad, head), 2.2, ORANGE().gamma_multiply(k));
}

/// 본문을 그릴 때 필요한 것들.
struct Ctx<'a> {
    ui: &'a egui::Ui,
    now: f64,
    id: egui::Id,
    /// 카드 전체의 불투명도 (등장·퇴장 중이면 1보다 작다)
    alpha: f32,
    /// 사라지는 중인 카드는 조작을 받지 않는다
    live: bool,
    lines: &'a mut Vec<String>,
    moving: &'a mut bool,
    items: &'a mut HashMap<String, f64>,
    nums: &'a mut HashMap<String, (f32, f32, f64)>,
    /// 웹 카드가 차지한 자리 (브라우저 화면을 그 위에 올린다)
    webs: &'a mut Vec<WebSlot>,
    /// 카드를 끌거나 크기를 바꾸는 중인가
    lifted: bool,
    /// 입력 카드의 글자 칸이 놓일 자리 (자리, 항목 ID, 안내 글, 처음 값). 실제 입력 칸은 본문을 그린 뒤에 그 자리에 올린다.
    fields: &'a mut Vec<(Rect, String, String, String, bool)>,
    /// 입력 카드에서 누르거나 끌어서 정한 값 (달력, 슬라이더). 본문을 그린 뒤에 반영한다.
    input_ops: &'a mut Vec<(String, String)>,
    /// 입력 카드에 지금 적혀 있는 글
    inputs: &'a HashMap<String, String>,
}

impl Ctx<'_> {
    /// 항목 하나를 그릴 붓. 새로 생긴 항목은 서서히 나타난다. 두 번째 값은 아래에서 올라오는 거리.
    fn row(&mut self, p: &Painter, item: &str) -> (Painter, f32) {
        let now = self.now;
        let t0 = *self.items.entry(item.to_string()).or_insert(now);
        let t = unit(now, t0, T_ITEM);
        if t < 1.0 {
            *self.moving = true;
        }
        let e = ease_out_cubic(t);
        let mut q = p.clone();
        q.set_opacity(self.alpha * e);
        (q, (1.0 - e) * 8.0)
    }
    /// 숫자 값이 바뀌면 이전 값에서 새 값으로 부드럽게 간다.
    fn num(&mut self, key: &str, target: f32) -> f32 {
        let now = self.now;
        let e = self.nums.entry(key.to_string()).or_insert((0.0, target, now));
        if (e.1 - target).abs() > 1e-4 {
            let cur = e.0 + (e.1 - e.0) * ease_out_cubic(unit(now, e.2, T_NUM));
            *e = (cur, target, now);
        }
        let t = unit(now, e.2, T_NUM);
        if t < 1.0 {
            *self.moving = true;
        }
        e.0 + (e.1 - e.0) * ease_out_cubic(t)
    }
    fn click(&self, rect: Rect, salt: (&str, &str)) -> Option<egui::Response> {
        self.live.then(|| self.ui.interact(rect, self.id.with(salt), Sense::click()).on_hover_cursor(CursorIcon::PointingHand))
    }
}

/// 보드 전체를 그린다. `lines` 에 사용자의 조작이 수정 언어로 쌓이고,
/// `clicked` 에 사용자가 짚은 카드가 들어간다. 반환값은 "아직 움직이는 중인가".
pub fn board(
    ui: &mut egui::Ui,
    board: &Board,
    view: &mut View,
    selected: &Option<String>,
    pending: &HashSet<String>,
    lines: &mut Vec<String>,
    clicked: &mut Option<String>,
    hot_zone: Option<&str>,
    webs: &mut Vec<WebSlot>,
) -> bool {
    let now = ui.input(|i| i.time);
    let mut moving = false;

    // 보드에서 사라진 카드는 바로 지우지 않고 서서히 사라지게 한다.
    for (id, v) in view.cards.iter_mut() {
        if board.card(id).is_none() && v.dying.is_none() {
            v.dying = Some(now);
        }
    }
    view.cards.retain(|_, v| v.dying.map(|t| now - t < T_OUT).unwrap_or(true));

    {
        // 하나의 넓은 판. 스크롤로 확대·축소하고 빈 곳을 끌어 옮긴다 (그래프 보기처럼).
        // 멀리서 보면 분류 덩어리와 이름만, 가까이 가면 주제, 더 가까이 가면 카드 내용이 보인다.
        let full_area = ui.available_rect_before_wrap();
        // 창 크기가 바뀌었는가. 바뀌는 동안은 카메라가 날아가지 않고 바로 따라간다 (아래 `resized`).
        let prev_area = view.area.replace(full_area.size());
        let resized = prev_area.map(|a| (a - full_area.size()).length() > 0.5).unwrap_or(false);
        if resized {
            // 카드 폭이 바뀌므로 재어 둔 높이는 새로 잰다.
            view.tall.clear();
        }
        view.tall.retain(|id, _| board.card(id).is_some());
        if let (true, Some(prev)) = (resized, prev_area) {
            // 확대 비율과 왼쪽 위는 그대로 두고, 보는 범위만 새 창 크기로 바꾼다.
            // (그대로 두면 예전 범위를 새 창에 억지로 맞추느라 판 전체가 잠깐 커졌다 작아지고, 날아가던 목표도 어긋난다.)
            let k = vec2(full_area.width() / prev.x.max(1.0), full_area.height() / prev.y.max(1.0));
            let keep = |r: Rect| Rect::from_min_size(r.min, vec2(r.width() * k.x, r.height() * k.y));
            if let Some(s) = view.scene.as_mut().filter(|s| s.is_finite() && s.width() > 1.0) {
                *s = keep(*s);
            }
            if let Some(f) = view.fit.as_mut() {
                *f = keep(*f);
            }
            if let Some((a, b, _)) = view.fly.as_mut() {
                (*a, *b) = (keep(*a), keep(*b));
            }
            if let Some((_, r)) = view.docked.as_mut() {
                *r = keep(*r);
            }
        }

        // "내 차례": 판 위가 아니라 화면 오른쪽에 고정된다 (확대·축소와 상관없이 항상 같은 자리, 같은 크기).
        let (inbox_slots, inbox_zones) = layout(board, INBOX_COLS as f32 * (CW + GAP) - GAP + 2.0 * ZONE_PAD, full_area.size(), view, Some(NO_GROUP), true);
        let inbox = inbox_zones.into_iter().find(|z| z.inbox);
        // "내 차례" 구역이 오른쪽을 가리므로, 내용은 그 왼쪽 자리에 맞춘다.
        let covered = 0.0_f32;
        let task_id = view.slot_card().map(str::to_string);
        let task_topic = task_id.as_ref().and_then(|id| board.card(id)).map(|c| c.topic.clone());
        // 판은 "내 차례" 구역의 왼쪽만 쓴다. 판 위의 내용이 그 구역 밑으로 들어가 가려지는 일이 없다.
        let area = Rect::from_min_max(full_area.min, pos2((full_area.right() - covered).max(full_area.left() + 200.0), full_area.bottom()));
        let open = area.size();
        let (mut slots, zones, regions, bounds) = world(board, view, open.x - 36.0, open);
        slots.extend(inbox_slots);
        // 화면에서 빠진 카드(접힌 카드 등)는 서서히 사라지게 한다. 다 사라지면 위에서 전환 상태를 버린다.
        for (id, v) in view.cards.iter_mut() {
            if v.dying.is_none() && !slots.contains_key(id) {
                v.dying = Some(now);
            }
        }

        // 왼쪽의 열린 자리에 맞춘 범위를, 가려진 오른쪽만큼 넓혀서 실제로 볼 범위로 만든다.
        let widen = |r: Rect| Rect::from_min_size(r.min, vec2(r.width() * area.width() / open.x, r.height()));
        let everything = if bounds.width() <= open.x - 20.0 && bounds.height() <= open.y - 20.0 {
            Rect::from_min_size(bounds.min - vec2(4.0, 4.0), area.size()) // 다 들어오면 제 크기로, 왼쪽 위부터
        } else {
            // 세로로 남으면 가운데가 아니라 위쪽에 붙인다.
            let f = frame(bounds.expand(30.0), open, 1.0);
            let lift = ((f.height() - bounds.height()) * 0.5 - 30.0).max(0.0);
            widen(f.translate(vec2(0.0, lift)))
        };
        // 볼 수 있는 범위의 끝. 내용이 있는 곳까지만 갈 수 있고, 그 밖의 빈 곳으로는 넘어가지 않는다.
        let limit = everything.union(bounds.expand(30.0));
        view.limit = Some(limit);
        // 보는 범위를 한계 안으로 되돌린 자리. 보는 범위가 한계보다 크면(전체 보기) 왼쪽 위에 맞춘다.
        let inside = |r: Rect| {
            let axis = |min: f32, size: f32, lo: f32, hi: f32| if size >= hi - lo { lo } else { min.clamp(lo, hi - size) };
            Rect::from_min_size(pos2(axis(r.min.x, r.width(), limit.min.x, limit.max.x), axis(r.min.y, r.height(), limit.min.y, limit.max.y)), r.size())
        };
        // 평소의 보기: 내용의 가로가 화면에 들어오면 제 크기(100%)로 위·왼쪽부터 보여 준다 (세로로 길면 끌어서 본다).
        // 가로로도 넘칠 때만 전체가 들어오게 줄인다. 화면의 위와 좌우가 비지 않는다.
        // 분류가 없는 판이 가로로 넘치면 가로만 맞춘다 (세로로 넘치면 끌어서 본다).
        // 판 전체를 한 화면에 넣으려고 글자를 읽을 수 없을 만큼 줄이지 않는다. 분류가 있는 판은 전체를 멀리서 본다.
        let home = if bounds.width() <= open.x - 8.0 {
            Rect::from_min_size(bounds.min - vec2(16.0, 16.0), area.size())
        } else if regions.is_empty() {
            let k = (open.x / (bounds.width() + 32.0)).clamp(FIT_MIN * 0.95, 1.0);
            inside(widen(Rect::from_min_size(bounds.min - vec2(16.0, 16.0), open / k)))
        } else {
            everything
        };
        // 있는 단계: 카드는 늘, 주제는 둘 이상일 때, 분류는 있을 때. 한 주제만 있으면 위 단계가 없다.
        let can_topics = zones.iter().filter(|z| !z.inbox).count() >= 2;
        let can_groups = !regions.is_empty();
        let levels: Vec<u8> = [(0, true), (1, can_topics), (2, can_groups)].into_iter().filter(|l| l.1).map(|l| l.0).collect();
        let mut scene = view.scene.unwrap_or(Rect::NOTHING);
        if !(scene.is_finite() && scene.width() > 1.0) {
            scene = home;
            // 처음 보기: 분류가 있는 판은 전체를 멀리서(분류 단계), 없는 판은 카드부터.
            view.level = if can_groups { 2 } else { 0 };
            // 확인용: LYBOARD_ZOOM=0.3 처럼 주면 그 비율로 왼쪽 위부터 보여 준다.
            if let Some(z) = std::env::var("LYBOARD_ZOOM").ok().and_then(|v| v.parse::<f32>().ok()) {
                scene = Rect::from_min_size(bounds.min - vec2(20.0, 20.0), area.size() / z.clamp(0.05, 1.0));
            }
        }
        // 지금 단계가 없어졌으면(주제가 하나로 줄었거나 분류가 없어졌으면) 처음 보기로 돌아간다.
        if !levels.contains(&view.level) {
            view.level = levels.iter().rev().copied().find(|l| *l < view.level).unwrap_or(0);
            view.fly = Some((scene, home, now));
        }
        // 주제 하나가 다 들어오는 보는 범위 (왼쪽 위에 붙여서). 읽을 수 있는 크기 아래로는 줄이지 않는다.
        let fit_zone = |zr: Rect| {
            let k = (open.x / (zr.width() + 32.0)).min(open.y / (zr.height() + 32.0)).clamp(FIT_MIN * 0.9, 1.0);
            inside(widen(Rect::from_min_size(zr.min - vec2(16.0, 16.0), open / k)))
        };
        // ---- 스크롤: 한 번에 한 단계 ----
        // 휠 한 칸, 두 손가락 쓸기 한 번, 오므리기·벌리기 한 번이 한 단계다. 확대 비율을 조금씩 바꾸지 않는다.
        // 축소하면 바로 위 단계(카드 → 주제 → 분류), 확대하면 바로 아래 단계. 위나 아래 단계가 없으면 그대로 둔다.
        if let Some(pointer) = ui.input(|i| i.pointer.hover_pos()).filter(|p| area.contains(*p)) {
            // 판 위의 스크롤은 여기서 쓰고 지운다 (판이 스스로 확대·축소하거나 밀리지 않게).
            let (wheel, pinch) = ui.ctx().input_mut(|i| {
                let w = i.smooth_scroll_delta.x + i.smooth_scroll_delta.y;
                i.smooth_scroll_delta = Vec2::ZERO;
                (w, i.zoom_delta())
            });
            // 휠 한 칸 ≈ 1 (양수면 확대 쪽)
            let push = wheel / 50.0 + pinch.max(0.01).ln() * 8.0;
            if push.abs() > 1e-3 {
                if now < view.step_lock {
                    // 같은 손짓(관성 포함)이 이어지는 동안은 한 번만 옮긴다.
                    view.step_lock = view.step_lock.max(now + 0.12);
                    view.wheel = (0.0, now);
                } else {
                    // 한동안 멈췄다가 다시 들어온 스크롤은 처음부터 센다.
                    let carry = if now - view.wheel.1 > 0.3 { 0.0 } else { view.wheel.0 };
                    view.wheel = (carry + push, now);
                }
            }
            if view.wheel.0.abs() >= 0.6 {
                let up = view.wheel.0 < 0.0;
                view.wheel = (0.0, now);
                view.step_lock = now + 0.35;
                let next = if up { levels.iter().copied().find(|l| *l > view.level) } else { levels.iter().rev().copied().find(|l| *l < view.level) };
                if let Some(next) = next {
                    // 가리킨 곳(판 위의 점)에 가장 가까운 분류·주제로 들어간다.
                    let at = scene.min + (pointer - area.min) * (scene.width() / area.width());
                    let near_zone = zones.iter().filter(|z| !z.inbox).min_by(|a, b| {
                        let d = |z: &Zone| Rect::from_min_size(z.min.to_pos2(), z.size).distance_to_pos(at);
                        d(a).total_cmp(&d(b))
                    });
                    let near_region = regions.iter().filter(|r| !r.name.is_empty()).min_by(|a, b| a.rect.distance_to_pos(at).total_cmp(&b.rect.distance_to_pos(at)));
                    let mut docked = None;
                    let target = match next {
                        2 => everything,
                        1 => match (can_groups, near_region) {
                            (true, Some(r)) => inside(frame(r.rect.expand(40.0), open, 1.0)),
                            _ => everything,
                        },
                        _ => match near_zone {
                            Some(z) => {
                                let zr = Rect::from_min_size(z.min.to_pos2(), z.size);
                                // 처음 보기가 카드를 읽을 수 있는 보기이고 그 주제가 다 보이면 처음 보기로, 아니면 그 주제에 맞춰 들어간다.
                                // (분류가 있는 판의 처음 보기는 멀리서 보는 전체라서 카드 단계로 쓰지 않는다.)
                                if home.contains_rect(zr) && area.width() / home.width() >= FIT_MIN * 0.9 {
                                    home
                                } else {
                                    let t = fit_zone(zr);
                                    docked = Some((z.id.clone(), t));
                                    t
                                }
                            }
                            None => home,
                        },
                    };
                    view.level = next;
                    view.docked = docked;
                    view.goto = None;
                    view.bounce = false;
                    // 용수철로 간다: 가는 도중에 한 번 더 스크롤해도 속도가 이어진다.
                    if view.follow.is_none() {
                        let (m0, z0) = cam_of(scene, area.size());
                        view.follow = Some((Tween::spring(m0, CAM_SPRING), Tween::spring(z0, CAM_SPRING)));
                    }
                    view.fly = Some((scene, target, now));
                }
            }
        }
        // 카메라가 용수철로 따라가는 중이면, "지금 어디를 보고 있는가"는 가고 있는 목표로 판단한다.
        let aim = view.follow.as_ref().map(|(m, z)| cam_rect(m.to, z.to, area.size())).unwrap_or(scene);
        // 전체를 보고 있었는데 내용이 늘거나 줄었으면(주제가 생김, 보관됨, 다시 꺼냄) 새 전체에 다시 맞춘다.
        if let Some(prev) = view.fit {
            let same = |a: Rect, b: Rect| (a.min - b.min).length() < 2.0 && (a.max - b.max).length() < 2.0;
            if same(aim, prev) && !same(prev, home) && view.fly.is_none() {
                view.fly = Some((scene, home, now));
            }
        }
        view.fit = Some(home);
        if let Some(id) = view.card_goto.clone() {
            // 카드의 자리가 정해진 뒤에 간다 (방금 만든 카드는 다음 프레임에 자리가 생긴다).
            if let Some(s) = slots.get(&id).filter(|s| !s.sticky) {
                view.card_goto = None;
                let size = vec2(s.w as f32 * (s.cw + GAP) - GAP, s.ph);
                let card = Rect::from_min_size(s.pos.to_pos2(), size);
                let zoom_now = area.width() / scene.width();
                if zoom_now < 0.985 || !scene.shrink(8.0).contains_rect(card) {
                    // 카드가 화면보다 크지 않으면 가운데에, 크면 왼쪽 위부터.
                    let min = pos2(
                        if size.x + 40.0 <= open.x { card.center().x - open.x / 2.0 } else { card.left() - 20.0 },
                        if size.y + 40.0 <= open.y { card.center().y - open.y / 2.0 } else { card.top() - 20.0 },
                    );
                    view.fly = Some((scene, inside(Rect::from_min_size(min, open)), now));
                    view.bounce = false;
                    view.goto = None;
                }
                view.level = 0;
            } else if board.card(&id).is_none() {
                view.card_goto = None;
            }
        }
        // 고른 카드를 닫았으면 처음 보기로 돌아간다 (곧이어 다른 일을 가져왔으면 아래에서 그 일 쪽으로 간다).
        if std::mem::take(&mut view.home_req) {
            view.docked = None;
            view.level = if can_groups { 2 } else { 0 };
            view.fly = Some((scene, home, now));
        }
        // 사용자 앞에 일을 가져왔으면, 그 일이 속한 구역을 판의 왼쪽 위(그 일 바로 옆)에 붙인다. 주변의 다른 일이 함께 보인다.
        if let Some(topic) = view.dock.take() {
            if let Some(z) = zones.iter().find(|z| z.id == topic) {
                // 구역이 다 들어오는 크기로, 그러나 읽을 수 있는 크기 아래로는 줄이지 않는다.
                let target = fit_zone(Rect::from_min_size(z.min.to_pos2(), z.size));
                view.fly = Some((scene, target, now));
                view.bounce = false;
                view.goto = None;
                view.docked = Some((topic, target));
                view.level = 0;
            }
        }
        // 맞춰 둔 구역의 크기가 달라졌으면 다시 맞춘다 (카드가 생기거나 내용에 맞게 줄어든 뒤에도 한 화면에 들어오게).
        if view.fly.is_none() {
            if let Some((topic, last)) = view.docked.clone() {
                let close = |a: Rect, b: Rect| (a.min - b.min).length() < 3.0 && (a.max - b.max).length() < 3.0;
                match zones.iter().find(|z| z.id == topic) {
                    Some(z) if close(aim, last) => {
                        let target = fit_zone(Rect::from_min_size(z.min.to_pos2(), z.size));
                        if !close(last, target) {
                            view.fly = Some((scene, target, now));
                            view.docked = Some((topic, target));
                        }
                    }
                    _ => view.docked = None, // 사용자가 화면을 옮겼거나 그 구역이 없어졌다
                }
            }
        }
        // AI 가 방금 고친 주제가 화면 밖에 있거나 너무 작게 보이면 그쪽으로 날아간다.
        if let Some(topic) = view.goto.take() {
            if let Some(z) = zones.iter().find(|z| z.id == topic) {
                let zr = Rect::from_min_size(z.min.to_pos2(), z.size);
                let zoom_now = area.width() / scene.width();
                // 구역 전체가 화면에 들어오는 크기로, 왼쪽 위에 붙여서 본다. 이미 그렇게 보고 있으면 움직이지 않는다.
                // 주제·분류 단계(카드가 안 보이는 단계)에 있으면 바뀐 카드를 볼 수 있게 카드 단계로 들어간다.
                let target = fit_zone(zr);
                let there = (scene.min - target.min).length() < 3.0 && (scene.width() - target.width()).abs() < 3.0;
                if view.level != 0 || (!there && (!scene.contains_rect(zr) || zoom_now < 0.55)) {
                    if !there {
                        view.fly = Some((scene, target, now));
                        view.docked = Some((topic.clone(), target));
                    }
                    view.level = 0;
                }
            }
        }
        // 창 크기를 바꾸는 동안(과 그 뒤 가라앉는 동안)은 정해진 시간에 날아가지 않고 용수철로 따라간다.
        // 목표가 매 프레임 바뀌어도 속도가 이어져서 끊기지 않는다 (카드를 접거나 펴서 확대 비율이 바뀔 때도).
        // 카드와 구역은 제 용수철로 새 자리를 찾아간다.
        if resized || view.follow.is_some() {
            let size = area.size();
            if let Some((_, to, _)) = view.fly.take() {
                let (m0, z0) = cam_of(scene, size);
                let f = view.follow.get_or_insert_with(|| (Tween::spring(m0, CAM_SPRING), Tween::spring(z0, CAM_SPRING)));
                let (m1, z1) = cam_of(to, size);
                f.0.retarget(m1, now);
                f.1.retarget(z1, now);
                view.bounce = false;
            }
            let cam = view.follow.as_ref().map(|(m, z)| (m.at(now), z.at(now), m.busy(now) || z.busy(now)));
            if let Some((m, z, busy)) = cam {
                scene = cam_rect(m, z, size);
                moving = true;
                if !busy && !resized {
                    view.follow = None;
                }
            }
        }
        if let Some((from, to, t0)) = view.fly {
            // 튕겨 돌아올 때는 목표를 살짝 지나쳤다가 제자리로 온다.
            let t = unit(now, t0, if view.bounce { T_BOUNCE } else { T_FLY });
            let k = if view.bounce { ease_out_back(t) } else { ease_in_out_cubic(t) };
            scene = Rect::from_min_max(from.min.lerp(to.min, k), from.max.lerp(to.max, k));
            moving = true;
            if t >= 1.0 {
                view.fly = None;
                view.bounce = false;
            }
        }
        let zoom = (area.width() / scene.width()).clamp(0.01, 4.0);
        // 이보다 더 축소하면 새로 보이는 것이 없다 (전체가 이미 다 보인다).
        let fit_zoom = (area.width() / everything.width()).min(1.0);
        // 확대 정도에 따라 무엇까지 보일지


        // 단계에 따라 카드, 주제 이름, 분류 이름 중 하나가 보인다. 단계가 바뀌면 서서히 넘어간다.
        let mut blend = |key: &str, on: bool| {
            let (v, going) = tween_num(&mut view.blend, key, if on { 1.0 } else { 0.0 }, now, T_LEVEL);
            moving |= going;
            v
        };
        let cards_seen = blend("cards", view.level == 0);
        let topics_seen = blend("topics", view.level == 1);
        let groups_seen = blend("groups", view.level == 2);

        let mut busy = false;
        let webs_start = webs.len();
        let scene_now = scene;
        // `seen`: 카드가 보이는 정도 (카드 단계면 1). 왼쪽 "내 차례" 자리는 늘 1.
        let mut draw_cards = |ui: &mut egui::Ui, sticky_pass: bool, origin: Pos2, seen: f32| {
            // 위 단계로 갈 때 내용 → 제목 → 상자 순으로 사라진다 (돌아올 때는 거꾸로 나타난다).
            let detail = smooth(seen, 0.55, 1.0);
            let title_a = smooth(seen, 0.3, 0.8);
            let box_a = smooth(seen, 0.0, 0.5);
            let handy = detail > 0.6; // 이만큼 가까워야 카드를 누르고 끌 수 있다
        // 그리는 순서: 사라지는 카드 → 보통 카드 → 끌고 있는 카드
        let mut order: Vec<(u8, String)> = view
            .cards
            .iter()
            .filter(|(_, v)| v.dying.is_some() && v.sticky == sticky_pass)
            .map(|(id, _)| (0, id.clone()))
            .chain(board.cards.iter().filter(|c| slots.get(&c.id).map(|s| s.sticky == sticky_pass).unwrap_or(false)).map(|c| {
                let lifted = view.cards.get(&c.id).map(|v| v.dragging || v.resizing).unwrap_or(false);
                (if lifted { 2 } else { 1 }, c.id.clone())
            }))
            .collect();
        order.sort_by_key(|o| o.0);

        for (_, cid) in order {
            // 보드에는 있어도 자리가 없으면(접힌 카드) 사라지는 중인 카드로 그린다.
            let live = board.card(&cid).filter(|c| slots.contains_key(&c.id));
            if let Some(c) = live {
                let s = &slots[&c.id];
                let w = s.w as f32;
                let target_pos = s.pos;
                let cell_w = s.cw;
                let target_size = vec2(w * cell_w + (w - 1.0) * GAP, s.ph);
                let v = view.cards.entry(c.id.clone()).or_insert_with(|| CardView {
                    pos: Tween::fixed(target_pos),
                    size: Tween::fixed(target_size),
                    born: now,
                    rev: c.rev,
                    flash: now,
                    dragging: false,
                    resizing: false,
                    snap: c.clone(),
                    dying: None,
                    inputs: Default::default(),
                    sticky: false,
                    content_h: 0.0,
                    items: HashMap::new(),
                    nums: HashMap::new(),
                });
                v.dying = None;
                if c.rev != v.rev {
                    v.rev = c.rev;
                    v.flash = now;
                }
                if !v.dragging {
                    v.pos.retarget(target_pos, now);
                }
                if !v.resizing {
                    v.size.retarget(target_size, now);
                }
                v.snap = c.clone();
                v.sticky = s.sticky;
            }
            let Some(v) = view.cards.get_mut(&cid) else { continue };
            // 글 안의 {이름} 은 이름 붙은 값으로 바꿔서 그린다.
            let c = if board.vars.is_empty() { v.snap.clone() } else { with_values(&v.snap, &board.vars) };

            // 등장: 아래에서 살짝 올라오며 나타남. 퇴장: 살짝 줄어들며 사라짐.
            let t_in = ease_out_cubic(unit(now, v.born, T_IN));
            let t_out = v.dying.map(|t| ease_in_cubic(unit(now, t, T_OUT))).unwrap_or(0.0);
            let alpha = t_in * (1.0 - t_out);
            let flash = 1.0 - ease_out_cubic(unit(now, v.flash, T_FLASH));
            if v.pos.busy(now) || v.size.busy(now) || t_in < 1.0 || v.dying.is_some() || flash > 0.0 {
                busy = true;
            }

            let full = Rect::from_min_size(origin + v.pos.at(now), v.size.at(now));
            let scale = 0.97 + 0.03 * t_in - 0.04 * t_out;
            let rect = Rect::from_center_size(full.center(), full.size() * scale).translate(vec2(0.0, (1.0 - t_in) * 12.0));
            let id = ui.scope_id().with(("card", &c.id));
            let alive = live.is_some();

            // ---- 직접 조작 ----
            let mut hovered = false;
            if alive && handy {
                let s = &slots[&c.id];
                let (col, row) = (s.col, s.row);
                let body_resp = ui.interact(rect, id, Sense::click());
                hovered = body_resp.hovered();
                let head_rect = Rect::from_min_size(rect.min, vec2(rect.width() - 30.0, HEAD));
                // 카드는 끌어서 옮기지 않는다: 카드 위에서 끌어도 판이 움직인다 (누르기만 받는다).
                let head = ui.interact(head_rect, id.with("head"), Sense::click()).on_hover_cursor(CursorIcon::PointingHand);
                // 누르면 그 카드를 왼쪽 자리로 가져온다. 이미 왼쪽 자리에 있는 카드는 누르지 않는다 (닫기로 돌려보낸다).
                if (body_resp.clicked() || head.clicked()) && !s.sticky {
                    *clicked = Some(c.id.clone());
                }
                let grip = Rect::from_min_max(rect.max - vec2(22.0, 22.0), rect.max);
                let rs = ui.interact(grip, id.with("grip"), Sense::drag()).on_hover_cursor(CursorIcon::ResizeNwSe);
                if rs.dragged() {
                    v.resizing = true;
                    let s = (v.size.at(now) + rs.drag_delta()).max(vec2(CW, CH));
                    v.size = Tween::fixed(s);
                    busy = true;
                }
                if rs.drag_stopped() {
                    v.resizing = false;
                    let s = v.size.at(now);
                    let nw = (((s.x + GAP) / (slots.get(&c.id).map(|x| x.cw).unwrap_or(CW) + GAP)).round() as i32).clamp(1, 8);
                    let nh = (((s.y + GAP) / (CH + GAP)).round() as i32).clamp(1, 8);
                    if c.grid.is_none() {
                        lines.push(format!("move {} {} {}", c.id, col, row)); // 지금 자리에 고정한 뒤 크기를 바꾼다
                    }
                    lines.push(format!("size {} {}x{}", c.id, nw, nh));
                }
                if c.pinned {
                    let pin = Rect::from_center_size(pos2(rect.right() - 20.0, rect.top() + 25.0), vec2(20.0, 20.0));
                    let pr = ui.interact(pin, id.with("pin"), Sense::click()).on_hover_text(crate::sys::tr("고정 해제 (자동 배치로 되돌림)", "Unpin (back to automatic placement)"));
                    if pr.clicked() {
                        lines.push(format!("unpin {}", c.id));
                    }
                }
            }

            // ---- 그리기 ----
            let mut p = ui.painter().with_clip_rect(rect.expand(40.0).intersect(ui.clip_rect()));
            p.set_opacity(alpha * box_a);
            let is_sel = selected.as_deref() == Some(c.id.as_str());
            let is_focus = board.focus.as_deref() == Some(c.id.as_str());
            let lifted = v.dragging || v.resizing;
            let radius = CornerRadius::same(RADIUS);
            let shadow = egui::epaint::Shadow {
                offset: [0, if lifted { 14 } else { 6 }],
                blur: if lifted { 36 } else { 22 },
                spread: 0,
                color: Color32::from_black_alpha(match (is_light(), lifted) { (true, true) => 45, (true, false) => 18, (false, true) => 130, (false, false) => 70 }),
            };
            p.add(shadow.as_shape(rect, radius));
            p.rect_filled(rect, radius, if lifted { mix(CARD(), RAISED(), 0.5) } else { CARD() });
            let rest = if is_sel { TEXT() } else if is_focus { EDGE() } else { BORDER() };
            p.rect_stroke(rect, radius, Stroke::new(if is_sel { 1.5 } else { 1.0 }, mix(rest, TEXT(), flash * 0.55)), StrokeKind::Inside);

            // 사용자가 이 카드를 조작했고 AI 가 아직 반응하는 중이면 테두리에 주황색 선이 돈다.
            let (pk, pbusy) = tween_num(&mut v.nums, "pend", if pending.contains(&c.id) { 1.0 } else { 0.0 }, now, 0.3);
            if pk > 0.01 || pbusy {
                busy = true;
                draw_runner(&p, rect, RADIUS as f32, now, pk);
            }

            // 제목 오른쪽에 붙는 것(주제 이름, 완료 개수, 고정 표시)의 폭만큼 제목 자리를 줄여서 글자가 겹치지 않게 한다.
            let mut topic_label = if sticky_pass { board.topics.iter().find(|t| t.id == c.topic).map(|t| t.name.clone()).unwrap_or_default() } else { String::new() };
            // 체크리스트의 완료 개수·고리 자리. 주제 이름이 함께 있으면 이름은 그 왼쪽에 놓는다 (겹치지 않게).
            let ring_w = if c.kind == Kind::Checklist && !c.items.is_empty() { 64.0 } else { 0.0 };
            // 제목과 주제 이름이 한 줄에 다 들어가지 않으면 주제 이름을 뺀다 (제목이 글자 중간에서 잘리지 않게).
            // 주제 이름은 판에서도 보인다 (고른 카드의 주제가 바로 옆에 붙는다).
            if !topic_label.is_empty() {
                let title_w = p.layout_no_wrap(c.title.clone(), bold(16.0), TEXT()).size().x;
                let label_w = p.layout_no_wrap(topic_label.clone(), font(11.0), DIM()).size().x;
                if PAD + title_w + 12.0 + label_w + 14.0 + ring_w + PAD > rect.width() {
                    topic_label.clear();
                }
            }
            let reserved = if !topic_label.is_empty() { p.layout_no_wrap(topic_label.clone(), font(11.0), DIM()).size().x + 14.0 } else { 0.0 }
                + ring_w
                + if c.pinned { 16.0 } else { 0.0 };
            let title_clip = Rect::from_min_max(rect.min, pos2(rect.right() - PAD - reserved, rect.top() + HEAD));
            let side_clip = Rect::from_min_max(rect.min, pos2(rect.right() - 6.0, rect.top() + HEAD));
            let mut tp = p.with_clip_rect(title_clip.intersect(p.clip_rect()));
            tp.set_opacity(alpha * title_a);
            tp.text(rect.min + vec2(PAD, 15.0), Align2::LEFT_TOP, &c.title, bold(16.0), TEXT());
            // 제목 옆에 붙는 것들은 제목과 다른 자리에 그린다.
            let mut sp = p.with_clip_rect(side_clip.intersect(p.clip_rect()));
            sp.set_opacity(alpha * title_a);
            // "내 차례" 구역의 카드는 주제 구역 밖에 있으므로, 어느 주제의 질문인지 제목 옆에 적는다.
            if !topic_label.is_empty() {
                sp.text(pos2(rect.right() - if c.pinned { 34.0 } else { PAD } - ring_w, rect.top() + 19.0), Align2::RIGHT_TOP, &topic_label, font(11.0), ORANGE().gamma_multiply(0.85));
            }
            let right_x = rect.right() - if c.pinned { 34.0 } else { PAD };
            if c.kind == Kind::Checklist && !c.items.is_empty() {
                // 체크리스트: 완료 개수와 채워지는 고리. 체크하는 즉시 바뀐다.
                let total = c.items.len();
                let done = c.items.iter().filter(|(_, cells)| cells[0] == "done").count();
                let e = v.nums.entry("ring".into()).or_insert((0.0, 0.0, now));
                let target = done as f32 / total as f32;
                if (e.1 - target).abs() > 1e-4 {
                    *e = (e.0 + (e.1 - e.0) * ease_out_cubic(unit(now, e.2, T_NUM)), target, now);
                }
                let k = unit(now, e.2, T_NUM);
                if k < 1.0 {
                    busy = true;
                }
                let frac = e.0 + (e.1 - e.0) * ease_out_cubic(k);
                let all = done == total;
                let col = if all { GREEN() } else { SOFT() };
                let center = pos2(right_x - 8.0, rect.top() + 24.0);
                sp.circle_stroke(center, 7.0, Stroke::new(2.0, RAISED()));
                let n = 40;
                let pts: Vec<Pos2> = (0..=((n as f32 * frac) as usize))
                    .map(|i| {
                        let a = -std::f32::consts::FRAC_PI_2 + std::f32::consts::TAU * i as f32 / n as f32;
                        center + vec2(a.cos(), a.sin()) * 7.0
                    })
                    .collect();
                if pts.len() > 1 {
                    sp.line(pts, Stroke::new(2.0, col));
                }
                sp.text(pos2(right_x - 22.0, rect.top() + 24.0), Align2::RIGHT_CENTER, format!("{done}/{total}"), bold(12.5), if all { GREEN() } else { DIM() });
            }
            if c.pinned {
                p.circle_filled(pos2(rect.right() - 20.0, rect.top() + 25.0), 3.0, DIM());
            }
            if hovered || lifted {
                let m = rect.max - vec2(9.0, 9.0);
                for d in [4.0, 8.0] {
                    p.line_segment([m - vec2(d, 0.0), m - vec2(0.0, d)], Stroke::new(1.0, FAINT()));
                }
            }

            let body = Rect::from_min_max(rect.min + vec2(PAD, HEAD), rect.max - vec2(PAD, 12.0));
            if body.height() > 4.0 && detail > 0.02 {
                let mut bp = p.with_clip_rect(body.expand2(vec2(2.0, 0.0)).intersect(p.clip_rect()));
                bp.set_opacity(alpha * detail);
                let mut fields: Vec<(Rect, String, String, String, bool)> = Vec::new();
                let mut input_ops: Vec<(String, String)> = Vec::new();
                let mut cx = Ctx {
                    fields: &mut fields,
                    input_ops: &mut input_ops,
                    inputs: &v.inputs,
                    ui,
                    now,
                    id,
                    alpha: alpha * detail,
                    live: alive && handy,
                    lines,
                    moving: &mut busy,
                    items: &mut v.items,
                    nums: &mut v.nums,
                    webs: &mut *webs,
                    lifted,
                };
                let used = draw_body(&mut cx, &bp, body, &c);
                // 입력 카드: 그려 둔 자리에 실제 글자 칸을 올린다. Enter 를 누르면 보낸다.
                let mut submit = false;
                for (key, value) in input_ops {
                    v.inputs.insert(key, value);
                }
                for (r, item, hint, initial, numeric) in fields {
                    if !body.expand(1.0).contains_rect(r) {
                        continue;
                    }
                    let buf = v.inputs.entry(item).or_insert(initial);
                    if numeric {
                        buf.retain(|ch| ch.is_ascii_digit() || ".,-".contains(ch));
                    }
                    let edit = egui::TextEdit::singleline(buf).frame(egui::Frame::NONE).hint_text(hint).font(font(14.0)).text_color(TEXT()).desired_width(r.width());
                    let resp = ui.put(r, edit);
                    if resp.lost_focus() && ui.input(|i| i.key_pressed(egui::Key::Enter)) {
                        submit = true;
                    }
                }
                if submit {
                    lines.extend(input_lines(&c, &v.inputs));
                }
                if alive {
                    v.content_h = used;
                    let t = view.tall.entry(c.id.clone()).or_insert((c.rev, 0.0));
                    *t = if t.0 == c.rev { (c.rev, t.1.max(used)) } else { (c.rev, used) };
                }
                if used > body.height() + 3.0 {
                    // 내용이 카드보다 길다는 표시
                    for i in 0..3 {
                        p.circle_filled(pos2(rect.center().x - 6.0 + i as f32 * 6.0, rect.bottom() - 8.0), 1.3, DIM());
                    }
                }
            }
        }
        };

        // ---- 판: 분류 영역, 주제 구역, 카드 ----
        let parent_layer = ui.layer_id();
        let shown = ui.scope_builder(egui::UiBuilder::new().max_rect(area), |ui| egui::Scene::new().zoom_range(zoom..=zoom).scroll_zooms(true).show(ui, &mut scene, |ui| {
            let base = ui.painter().clone();
            // 단계에 따라 보인다 (`View::level`):
            //   분류 (far)   분류 이름만 크게
            //   주제 (mid)   주제 이름만 크게
            //   카드         카드
            let far = groups_seen;
            let mid = topics_seen;
            // 한 줄 글자가 주어진 폭에 들어가는 가장 큰 크기 (한글 한 글자의 폭을 글자 크기와 같다고 본다).
            let fit = |name: &str, width: f32, want: f32| want.min(width / (name.chars().count().max(1) as f32 * 1.05)).max(8.0);
            // 분류 영역: 넓은 바탕. 이름은 영역 바로 위에 적어서 안쪽 내용과 겹치지 않는다.
            for r in &regions {
                if r.name.is_empty() {
                    continue;
                }
                base.rect_filled(r.rect, 44.0, mix(BG(), ZONE(), 0.35 + 0.65 * far));
                base.rect_stroke(r.rect, 44.0, Stroke::new((1.0 / zoom).min(8.0), mix(ZONE_EDGE(), EDGE(), far * 0.6)), StrokeKind::Inside);
                // 이 분류 안에서 내 답을 기다리는 수: 영역 위 오른쪽 끝
                let rad = (13.0 / zoom).clamp(13.0, REGION_GAP * 0.3);
                let badge = if r.asks > 0 { rad * 2.0 + 12.0 / zoom } else { 0.0 };
                if r.asks > 0 {
                    let c = pos2(r.rect.right() - rad - 8.0, r.rect.top() - rad - 6.0 / zoom);
                    base.circle_filled(c, rad, ORANGE());
                    base.text(c, Align2::CENTER_CENTER, format!("{}", r.asks), bold(rad * 1.1), CARD());
                }
                if far < 0.55 {
                    // 가까이·중간: 영역 위 왼쪽에 이름 (화면에서 늘 같은 크기로 보이게 확대 비율만큼 키운다)
                    let size = fit(&r.name, r.rect.width() - badge - 16.0, (24.0 / zoom).min(REGION_GAP * 0.6));
                    let mut lp = base.clone();
                    lp.set_opacity((1.0 - far * 1.8).max(0.0)); // 가운데 이름이 나타나기 전에 먼저 사라진다
                    lp.text(pos2(r.rect.left() + 8.0, r.rect.top() - 6.0 / zoom), Align2::LEFT_BOTTOM, &r.name, bold(size), SOFT());
                }
                if far > 0.45 {
                    // 멀리: 영역 한가운데에 이름을 크게. 이때 주제 이름은 보이지 않는다.
                    let size = fit(&r.name, r.rect.width() * 0.8, (30.0 / zoom).min(r.rect.height() * 0.35));
                    let mut lp = base.clone();
                    lp.set_opacity((far * 1.8 - 0.8).max(0.0));
                    lp.text(r.rect.center(), Align2::CENTER_CENTER, &r.name, bold(size), TEXT());
                }
            }
            // 주제 구역: 이름이 붙은 바탕 판. 카드는 자기 주제의 판 위에만 놓인다.
            view.zones.retain(|id, _| zones.iter().any(|z| &z.id == id));
            for z in &zones {
                let zv = view.zones.entry(z.id.clone()).or_insert_with(|| (Tween::fixed(z.min), Tween::fixed(z.size), now));
                zv.0.retarget(z.min, now);
                zv.1.retarget(z.size, now);
                let appear = ease_out_cubic(unit(now, zv.2, T_IN));
                if zv.0.busy(now) || zv.1.busy(now) || appear < 1.0 {
                    moving = true;
                }
                let r = Rect::from_min_size(zv.0.at(now).to_pos2(), zv.1.at(now));
                let mut zp = base.clone();
                // 분류 이름만 보이는 단계에서는 주제 판이 옅은 덩어리로만 남는다.
                let grouped = !regions.is_empty();
                zp.set_opacity(appear * if grouped { 1.0 - far * 0.75 } else { 1.0 });
                zp.rect_filled(r, 26.0, mix(ZONE(), RAISED(), (1.0 - cards_seen) * 0.6));
                zp.rect_stroke(r, 26.0, Stroke::new((1.0 / zoom).min(5.0), ZONE_EDGE()), StrokeKind::Inside);
                // 지금 사용자 앞에 가져온 일이 속한 구역: 주황색 테두리로 "이 일의 자리"임을 보인다.
                if task_topic.as_deref() == Some(z.id.as_str()) {
                    zp.rect_stroke(r, 26.0, Stroke::new((1.5 / zoom).min(7.0), ORANGE().gamma_multiply(0.75)), StrokeKind::Inside);
                }
                let count = board.cards.iter().filter(|c| c.topic == z.id && task_id.as_deref() != Some(c.id.as_str())).count();
                if cards_seen > 0.02 {
                    // 가까이: 구역 위쪽에 이름과 카드 수 (카드가 보이는 단계)
                    let mut np = zp.with_clip_rect(Rect::from_min_size(r.min, vec2(r.width() - 8.0, ZONE_HEAD)).intersect(zp.clip_rect()));
                    np.set_opacity(appear * cards_seen);
                    // 조금 축소된 상태에서도 읽히게, 구역 머리 높이 안에서 글자를 키운다.
                    let name_size = (15.0 / zoom).clamp(15.0, 24.0);
                    let tr = np.text(r.min + vec2(ZONE_PAD + 8.0, (ZONE_HEAD - name_size) * 0.5 - 2.0), Align2::LEFT_TOP, &z.name, bold(name_size), SOFT());
                    let cr = np.text(pos2(tr.right() + 10.0, tr.center().y), Align2::LEFT_CENTER, format!("{count}"), font(12.0), FAINT());
                    // 접힌 카드가 있으면 카드 수 바로 옆에 "+N개 더" 단추. 누르면 다 펼치고, 펼친 뒤에는 "접기".
                    // 오른쪽 끝에 두면 구역이 화면보다 넓을 때 화면 밖으로 나가므로 이름 쪽에 붙인다.
                    if z.hidden > 0 && !z.inbox {
                        let label = match (z.unfolded, crate::sys::english()) {
                            (true, en) => (if en { "Fold" } else { "접기" }).to_string(),
                            (false, true) => format!("+{} more", z.hidden),
                            (false, false) => format!("+{}개 더", z.hidden),
                        };
                        let size = (12.5 / zoom).clamp(12.5, 18.0);
                        let g = np.layout_no_wrap(label, font(size), SOFT());
                        let pill = Rect::from_min_size(pos2(cr.right() + 12.0, tr.center().y - (size + 12.0) * 0.5), vec2(g.size().x + size * 1.8, size + 12.0));
                        let resp = (cards_seen > 0.6).then(|| {
                            ui.interact(pill, ui.scope_id().with(("fold", &z.id)), Sense::click()).on_hover_cursor(CursorIcon::PointingHand).on_hover_text(if z.unfolded {
                                crate::sys::tr("읽을 수 있는 크기로 한 화면에 들어오게 덜 중요한 카드를 다시 접습니다", "Fold the less important cards again so the topic fits one screen at a readable size")
                            } else {
                                crate::sys::tr("글자가 너무 작아지지 않게 덜 중요한 카드를 접어 두었습니다. 눌러서 다 보기", "Less important cards are folded so the text stays readable. Click to show all")
                            })
                        });
                        let hover = resp.as_ref().map(|r| r.hovered()).unwrap_or(false);
                        if resp.map(|r| r.clicked()).unwrap_or(false) && !view.unfolded.remove(&z.id) {
                            view.unfolded.insert(z.id.clone());
                        }
                        if hover {
                            np.rect_filled(pill, CornerRadius::same(255), RAISED());
                        }
                        np.rect_stroke(pill, CornerRadius::same(255), Stroke::new(1.0, if hover { EDGE() } else { BORDER() }), StrokeKind::Inside);
                        np.galley(pill.center() - g.size() / 2.0, g, SOFT());
                    }
                }
                let mid_here = if grouped { mid } else { 1.0 - cards_seen };
                if mid_here > 0.02 {
                    // 중간: 카드가 안 보이므로 구역 한가운데에 이름을 크게
                    // 이름이 길면 줄을 바꿔서 상자 폭을 다 쓴다. 한 줄에 억지로 넣느라 글자가 작아지지 않게 한다.
                    // 화면에서 20픽셀쯤으로 보이는 크기에서 시작해, 상자에 들어갈 때까지만 줄인다.
                    let mut np = zp.with_clip_rect(r.shrink(6.0).intersect(zp.clip_rect()));
                    np.set_opacity(appear * mid_here);
                    let (room_w, room_h) = (r.width() - 28.0 / zoom.max(0.2), r.height() - 20.0 / zoom.max(0.2));
                    let mut size = (20.0 / zoom).min(r.height() * 0.3).max(8.0);
                    let mut galley = np.layout(z.name.clone(), bold(size), TEXT(), room_w.max(20.0));
                    for _ in 0..8 {
                        if galley.size().y <= room_h && galley.size().x <= room_w + 1.0 {
                            break;
                        }
                        size *= 0.88;
                        galley = np.layout(z.name.clone(), bold(size), TEXT(), room_w.max(20.0));
                    }
                    // 여러 줄이면 가운데로 모은다.
                    let mut job = egui::text::LayoutJob::simple(z.name.clone(), bold(size), TEXT(), room_w.max(20.0));
                    job.halign = egui::Align::Center;
                    let galley = np.fonts_mut(|f| f.layout_job(job));
                    np.galley(pos2(r.center().x, r.center().y - galley.size().y / 2.0), galley, TEXT());
                }
                // 방금 한 말이 이 주제의 이야기라고 판단되면, 답이 오기 전부터 이 구역 테두리에 선이 돈다.
                if hot_zone == Some(z.id.as_str()) {
                    moving = true;
                    draw_runner(&zp, r, 26.0, now, 1.0);
                }
            }
            draw_cards(ui, false, Pos2::ZERO, cards_seen);
        })).inner;
        let drawn = scene; // 이번 프레임에 실제로 보여 준 범위 (확인용 기록에 쓴다)
        // 손으로 끌어 옮기면 자동 이동은 그만둔다. 빈 곳을 두 번 누르면 처음 보기로 돌아간다.
        // 판은 손으로 확대·축소되지 않는다 (스크롤은 위에서 단계 이동으로 쓴다). 판은 확대 입력이 오면 비율이 그대로여도
        // "바뀌었다"고 알리므로, 사용자가 옮겼는지는 실제로 끌었는지로 본다.
        let panned = shown.response.dragged() && shown.response.drag_delta() != Vec2::ZERO;
        let pulling = ui.input(|i| i.smooth_scroll_delta.y.abs() > 0.1 || i.zoom_delta() != 1.0);
        // 창 크기를 바꾸는 중에는 튕기지 않는다: 목표만 정해 두면 다음 프레임에 용수철이 따라간다.
        let following = resized || view.follow.is_some();
        if panned {
            view.follow = None;
        }
        if area.width() / scene.width() < fit_zoom * 0.995 && !pulling && view.fly.is_none() && view.follow.is_none() {
            view.fly = Some((scene, everything, now));
            view.bounce = !following;
        } else if panned && !view.bounce {
            view.fly = None;
        }
        if shown.response.double_clicked() {
            view.level = if can_groups { 2 } else { 0 };
            view.docked = None;
            view.fly = Some((scene, home, now));
        }
        // 끌어서 옮길 때: 내용의 끝을 넘어가지 않는다. 끝에서는 조금만 당겨지고(화면에서 70픽셀까지), 손을 떼면 튕겨 돌아온다.
        if view.fly.is_none() && area.width() / scene.width() >= fit_zoom * 0.995 {
            let home = inside(scene);
            let over = scene.min - home.min;
            if over.length() > 0.5 && following {
                // 창 크기를 바꾸다 내용 끝을 넘어갔다: 튕기지 않고 용수철로 안쪽으로
                if view.follow.is_none() {
                    view.fly = Some((scene, home, now));
                }
            } else if over.length() > 0.5 {
                let give = 70.0 / zoom;
                scene = home.translate(vec2(over.x.clamp(-give, give), over.y.clamp(-give, give)));
                let held = ui.input(|i| i.pointer.any_down());
                if !held && !pulling {
                    view.fly = Some((scene, home, now));
                    view.bounce = true;
                }
                moving = true;
            }
        }
        view.scene = Some(scene);
        view.zoom = zoom;

        // ---- "내 차례": 왼쪽 대화 구역이 비워 둔 자리에, 지금 처리할 일 하나를 그린다 ----
        if let (Some(z), Some(slot)) = (&inbox, view.task_rect) {
            let over_layer = egui::LayerId::new(parent_layer.order, ui.scope_id().with("inbox_layer"));
            ui.ctx().set_sublayer(parent_layer, over_layer);
            let mut over = ui.new_child(egui::UiBuilder::new().layer_id(over_layer).max_rect(slot).sense(Sense::hover()));
            over.set_clip_rect(slot);
            // 선택지가 많아 자리보다 길면, 이 자리 위에서 스크롤해 넘겨 본다.
            let reach = (z.size.y - slot.height()).max(0.0);
            if ui.input(|i| i.pointer.hover_pos()).map(|p| slot.contains(p)).unwrap_or(false) {
                view.inbox_scroll -= ui.input(|i| i.smooth_scroll_delta.y);
            }
            view.inbox_scroll = view.inbox_scroll.clamp(0.0, reach);
            draw_cards(&mut over, true, slot.min - vec2(0.0, view.inbox_scroll), 1.0);
        }
        drop(draw_cards);
        // 확인용: LYBOARD_TRACE 가 있으면 프레임마다 판 크기, 확대 비율, 카드의 화면 위치를 data\trace.log 에 남긴다.
        static TRACE: std::sync::OnceLock<bool> = std::sync::OnceLock::new();
        if *TRACE.get_or_init(|| std::env::var("LYBOARD_TRACE").is_ok()) {
            let mut ids: Vec<&String> = view.cards.iter().filter(|(_, v)| v.dying.is_none() && !v.sticky).map(|(id, _)| id).collect();
            ids.sort();
            let z = area.width() / drawn.width();
            let mut line = format!("{now:.4} {:.0} {:.0} {z:.4}", area.width(), area.height());
            for id in ids {
                let v = &view.cards[id];
                let p = area.min + (v.pos.at(now) - drawn.min.to_vec2()) * z;
                let s = v.size.at(now) * z;
                line.push_str(&format!(" {id}:{:.1},{:.1},{:.1},{:.1}", p.x, p.y, s.x, s.y));
            }
            let hidden: usize = zones.iter().map(|z| z.hidden).sum();
            line.push_str(&format!(" lv={} seen={cards_seen:.2},{topics_seen:.2},{groups_seen:.2} hidden={hidden}", view.level));
            use std::io::Write;
            if let Ok(mut f) = std::fs::OpenOptions::new().create(true).append(true).open(crate::sys::data_dir().join("trace.log")) {
                let _ = writeln!(f, "{line}");
            }
        }
        moving |= busy;
        // 판 위의 웹 카드 자리를 화면 좌표로 바꾼다. 축소된 상태에서는 브라우저 화면을 띄우지 않는다.
        for w in webs.iter_mut().skip(webs_start) {
            let to_screen = |p: Pos2| area.min + (p - scene_now.min) * zoom;
            w.rect = Rect::from_min_max(to_screen(w.rect.min), to_screen(w.rect.max));
            w.visible = w.visible && zoom > 0.97 && area.contains_rect(w.rect);
        }

        if board.cards.is_empty() {
            ui.painter().text(area.center(), Align2::CENTER_CENTER, crate::sys::tr("왼쪽에 말을 걸어 보세요", "Say something on the left"), bold(20.0), FAINT());
        }
    }
    moving
}

/// 0~1 사이로 부드럽게 이어지는 계단: x 가 a 이하면 0, b 이상이면 1.
/// 목표를 조금 지나쳤다가 돌아오는 곡선 (easings.net 의 easeOutBack).
fn ease_out_back(t: f32) -> f32 {
    let (c1, c3) = (1.70158_f32, 2.70158_f32);
    1.0 + c3 * (t - 1.0).powi(3) + c1 * (t - 1.0).powi(2)
}

fn smooth(x: f32, a: f32, b: f32) -> f32 {
    let t = ((x - a) / (b - a)).clamp(0.0, 1.0);
    t * t * (3.0 - 2.0 * t)
}

/// 주어진 자리를 다 채우는 카드(웹, 지도, 그림)가 알려 주는 내용 높이: 카드가 정한 칸 수만큼의 본문 높이.
/// 지금 본문 높이를 그대로 알려 주면 "내용이 길다 → 카드를 키운다 → 본문이 커진다"가 되풀이되어
/// 카드가 화면 높이까지 조금씩 계속 자란다 (그동안 화면이 쉬지 않고 움직이고, 접히는 카드 수도 바뀐다).
fn fill_h(c: &Card) -> f32 {
    c.h as f32 * (CH + GAP) - GAP - HEAD - 14.0
}

/// 보는 범위 → 카메라 용수철의 값: (왼쪽 위, (확대 비율×1000, 0)).
/// 확대 비율은 1000배로 적는다: 용수철이 0.5 아래의 변화는 무시하므로 그대로 두면 비율 변화를 놓친다.
fn cam_of(r: Rect, size: Vec2) -> (Vec2, Vec2) {
    (r.min.to_vec2(), vec2(size.x / r.width().max(1.0) * 1000.0, 0.0))
}

/// 카메라 용수철의 값 → 보는 범위
fn cam_rect(min: Vec2, zoom: Vec2, size: Vec2) -> Rect {
    Rect::from_min_size(min.to_pos2(), size / (zoom.x / 1000.0).max(0.01))
}

/// `target` 이 다 들어오면서 화면과 가로세로 비율이 같은 범위. `max_zoom` 보다 더 확대하지는 않는다.
fn frame(target: Rect, view: Vec2, max_zoom: f32) -> Rect {
    let scale = (view.x / target.width().max(1.0)).min(view.y / target.height().max(1.0)).min(max_zoom);
    Rect::from_center_size(target.center(), view / scale)
}

/// 분류 하나가 판 위에서 차지하는 영역.
struct Region {
    name: String,
    rect: Rect,
    /// 이 분류 안에서 내 답을 기다리는 카드 수
    asks: usize,
}

const T_FLY: f64 = 0.6;
/// 단계가 바뀔 때 카드·주제 이름·분류 이름이 넘어가는 시간
const T_LEVEL: f64 = 0.3;
const T_BOUNCE: f64 = 0.5;
/// 한 화면에 맞춘 구역이 둘쯤 나란히 놓이는 폭
const REGION_W: f32 = 2400.0;
/// 한 주제를 한 화면에 넣으려고 축소할 때의 한도. 이보다 작아지면 글자를 읽기 어렵다 (본문 12.5~13.5 → 화면에서 11px 안팎).
/// 이만큼 줄여도 안 들어가면 덜 중요한 카드를 접는다.
const FIT_MIN: f32 = 0.85;
const REGION_GAP: f32 = 200.0;
const REGION_PAD: f32 = 40.0;
/// 분류 이름은 영역 위에 적으므로 영역 안쪽 위 여백은 작다.
const REGION_HEAD: f32 = 40.0;

/// 판 전체의 배치. 분류마다 영역을 하나씩 잡고, 그 안에 그 분류의 주제 구역들을 놓는다.
/// 분류가 하나도 없으면 영역 없이 주제 구역만 놓인다.
fn world(board: &Board, view: &View, plain_width: f32, screen: Vec2) -> (HashMap<String, Slot>, Vec<Zone>, Vec<Region>, Rect) {
    if board.groups.is_empty() {
        // 분류가 없을 때는 화면 폭에 맞춰 놓아서 제 크기 그대로 보이게 한다.
        let (slots, zones) = layout(board, plain_width, screen, view, None, false);
        let max = zones.iter().fold(Vec2::ZERO, |m, z| m.max(z.min + z.size));
        return (slots, zones, Vec::new(), Rect::from_min_size(Pos2::ZERO, max));
    }
    let mut keys: Vec<(String, String)> = board.groups.iter().map(|g| (g.id.clone(), g.name.clone())).collect();
    keys.push((String::new(), crate::sys::tr("미분류", "Ungrouped").to_string()));
    let (mut slots, mut zones, mut regions) = (HashMap::new(), Vec::new(), Vec::new());
    // 맨 윗줄 분류의 이름이 놓일 자리를 위에 남긴다.
    let (mut x, mut y, mut row_h) = (0.0_f32, REGION_GAP * 0.7, 0.0_f32);
    // 화면이 가로로 넓으므로 분류를 한 줄에 셋까지 놓는다 (전체를 볼 때 덜 축소된다).
    let wrap_at = REGION_W * 3.0 + REGION_GAP * 2.0 + 6.0 * REGION_PAD;
    let mut max = Vec2::ZERO;
    for (gid, name) in keys {
        let (s, z) = layout(board, REGION_W, screen, view, Some(gid.as_str()), false);
        if z.is_empty() {
            continue;
        }
        let content = z.iter().fold(Vec2::ZERO, |m, zz| m.max(zz.min + zz.size));
        let size = vec2(content.x.max(520.0) + 2.0 * REGION_PAD, content.y + REGION_HEAD + REGION_PAD);
        if x > 0.0 && x + size.x > wrap_at {
            x = 0.0;
            y += row_h + REGION_GAP;
            row_h = 0.0;
        }
        let rect = Rect::from_min_size(pos2(x, y), size);
        let shift = vec2(x + REGION_PAD, y + REGION_HEAD);
        let topic_ids: Vec<&str> = z.iter().map(|zz| zz.id.as_str()).collect();
        let asks = board.cards.iter().filter(|c| c.kind.asks() && board.topics.iter().any(|t| t.id == c.topic && t.group.as_deref().unwrap_or("") == gid)).count();
        let _ = topic_ids;
        for (id, mut slot) in s {
            slot.pos += shift;
            slot.origin += shift;
            slots.insert(id, slot);
        }
        for mut zz in z {
            zz.min += shift;
            zones.push(zz);
        }
        regions.push(Region { name, rect, asks });
        max = max.max(rect.max.to_vec2());
        x += size.x + REGION_GAP;
        row_h = row_h.max(size.y);
    }
    (slots, zones, regions, Rect::from_min_size(Pos2::ZERO, max))
}

/// 값 뒤에 단위를 붙인다. "%", "k", "km", "만원"처럼 짧은 단위는 붙여 쓰고,
/// "subs", "signups", "min"처럼 세 글자 이상인 영어 단어 단위는 한 칸 띄운다 ("180 signups").
fn with_unit(value: &str, unit: &str) -> String {
    let word = unit.chars().count() >= 3 && unit.chars().next().map(|ch| ch.is_ascii_alphabetic()).unwrap_or(false);
    if word { format!("{value} {unit}") } else { format!("{value}{unit}") }
}

fn wrapped(p: &Painter, pos: Pos2, width: f32, text: &str, size: f32, color: Color32) -> f32 {
    if text.is_empty() {
        return 0.0;
    }
    let g = p.layout(text.to_owned(), font(size), color, width.max(20.0));
    let h = g.size().y;
    p.galley(pos, g, color);
    h
}

fn check_mark(p: &Painter, c: Pos2, color: Color32) {
    let s = Stroke::new(1.7, color);
    p.line_segment([c + vec2(-3.6, 0.0), c + vec2(-1.0, 2.8)], s);
    p.line_segment([c + vec2(-1.0, 2.8), c + vec2(3.8, -2.8)], s);
}

fn state_color(s: &str) -> Color32 {
    match s {
        "ok" | "done" => GREEN(),
        "run" => TEXT(),
        "wait" => AMBER(),
        "fail" => RED(),
        _ => FAINT(),
    }
}

fn state_dot(p: &Painter, c: Pos2, state: &str, now: f64, moving: &mut bool) {
    let col = state_color(state);
    match state {
        "done" | "ok" => {
            p.circle_filled(c, 7.0, col);
            check_mark(p, c, CARD());
        }
        "run" => {
            // 숨 쉬듯 퍼졌다 사라지는 고리
            *moving = true;
            let t = ((now * 0.8).fract()) as f32;
            let e = ease_out_cubic(t);
            p.circle_stroke(c, 4.0 + 6.0 * e, Stroke::new(1.2, col.gamma_multiply(0.5 * (1.0 - e))));
            p.circle_filled(c, 4.0, col);
        }
        "fail" => {
            p.circle_filled(c, 7.0, col);
            let s = Stroke::new(1.7, CARD());
            p.line_segment([c + vec2(-2.8, -2.8), c + vec2(2.8, 2.8)], s);
            p.line_segment([c + vec2(-2.8, 2.8), c + vec2(2.8, -2.8)], s);
        }
        "wait" => {
            p.circle_stroke(c, 6.0, Stroke::new(1.5, col));
            p.circle_filled(c, 2.0, col);
        }
        _ => {
            p.circle_stroke(c, 6.0, Stroke::new(1.4, col));
        }
    }
}

/// 부품 종류별 본문. 반환값은 내용이 실제로 차지한 높이.
/// 입력 칸의 종류. 항목의 둘째 칸이 date / number / slider 0~100 단위 이면 그 종류, 아니면 글을 적는 칸(둘째 칸은 안내 글).
enum InputKind {
    Text,
    Number,
    Date,
    Slider(f32, f32, String),
}

fn input_kind(spec: &str) -> InputKind {
    let s = spec.trim();
    match s {
        "date" | "날짜" => return InputKind::Date,
        "number" | "숫자" => return InputKind::Number,
        _ => {}
    }
    if let Some(rest) = s.strip_prefix("slider") {
        let mut words = rest.split_whitespace();
        if let Some((a, b)) = words.next().and_then(|r| r.split_once('~')) {
            if let (Ok(lo), Ok(hi)) = (a.trim().parse::<f32>(), b.trim().parse::<f32>()) {
                if hi > lo {
                    return InputKind::Slider(lo, hi, words.collect::<Vec<_>>().join(" "));
                }
            }
        }
    }
    InputKind::Text
}

/// 카드의 글 안에 있는 {이름} 을 이름 붙은 값으로 바꾼 사본.
fn with_values(card: &Card, vars: &std::collections::BTreeMap<String, String>) -> Card {
    let mut c = card.clone();
    let fill = |s: &mut String| {
        if s.contains('{') {
            for (name, value) in vars {
                *s = s.replace(&format!("{{{name}}}"), value);
            }
        }
    };
    fill(&mut c.title);
    c.fields.values_mut().for_each(fill);
    c.items.iter_mut().flat_map(|(_, cells)| cells.iter_mut()).for_each(fill);
    c
}

/// 입력 카드를 보낼 때 나가는 조작: 칸마다 적은 값을 항목의 셋째 칸에 넣고, 보냈다는 표시를 한다.
fn input_lines(c: &Card, inputs: &HashMap<String, String>) -> Vec<String> {
    let clean = |s: &str| s.replace(['|', '\n', '\r'], " ").trim().chars().take(crate::core::CELL_LIMIT).collect::<String>();
    let mut out = Vec::new();
    for (item, cells) in &c.items {
        let value = inputs.get(item).map(|v| clean(v)).unwrap_or_default();
        if !value.is_empty() {
            let hint = cells.get(1).map(String::as_str).filter(|h| !h.trim().is_empty()).unwrap_or("-");
            out.push(format!("upd {} {} {} | {} | {}", c.id, item, cells[0], hint, value));
        }
    }
    if !out.is_empty() {
        out.push(format!("set {} sent yes", c.id));
    }
    out
}

/// 숫자를 군더더기 없이 적는다 (12.0 → 12, 12.5 → 12.5).
fn trim_num(v: f32) -> String {
    if (v - v.round()).abs() < 0.05 { format!("{v:.0}") } else { format!("{v:.1}") }
}

fn draw_body(cx: &mut Ctx, p: &Painter, body: Rect, c: &Card) -> f32 {
    let (x, top, w) = (body.left(), body.top(), body.width());
    let now = cx.now;
    match c.kind {
        Kind::Note => wrapped(p, body.min, w, c.field("text"), 15.0, SOFT()),

        Kind::Web => {
            // 이 자리 위에 실제 브라우저 화면이 올라간다. 여기서는 자리와 주소만 알려 준다.
            let url = c.field("url").to_string();
            p.rect_filled(body, 10.0, RAISED());
            if url.is_empty() {
                p.text(body.center(), Align2::CENTER_CENTER, crate::sys::tr("주소가 아직 없습니다", "No address yet"), font(12.0), FAINT());
            } else {
                // 페이지는 제 크기(100%)로 화면 안에 다 들어와야 보인다. 그렇지 않을 때는 누르면 그렇게 보이는 자리로 간다.
                p.text(body.center() - vec2(0.0, 10.0), Align2::CENTER_CENTER, crate::sys::tr("눌러서 페이지 보기", "Click to view the page"), bold(14.0), SOFT());
                p.text(body.center() + vec2(0.0, 12.0), Align2::CENTER_CENTER, crate::sys::tr("페이지는 카드가 제 크기로 화면에 다 들어와야 보입니다", "The page shows when the card is at full size and fully on screen"), font(11.5), FAINT());
                if cx.ui.interact(body, cx.id.with("web-show"), Sense::click()).on_hover_cursor(CursorIcon::PointingHand).clicked() {
                    cx.lines.push(format!("@show {}", c.id));
                }
            }
            let shown = body.intersect(cx.ui.clip_rect());
            // 화면 밖으로 반쯤 나갔거나, 끌고 있거나, 나타나고 사라지는 중이면 브라우저 화면은 잠시 숨긴다.
            let visible = cx.live && !cx.lifted && cx.alpha > 0.98 && shown.height() > body.height() - 2.0 && shown.width() > body.width() - 2.0;
            cx.webs.push(WebSlot { id: c.id.clone(), rect: body, url, visible });
            fill_h(c)
        }

        Kind::Status => {
            let state = c.field("state");
            let label = match state {
                "ok" => crate::sys::tr("정상", "OK"),
                "run" => crate::sys::tr("진행 중", "Running"),
                "wait" => crate::sys::tr("대기", "Waiting"),
                "fail" => crate::sys::tr("실패", "Failed"),
                _ => crate::sys::tr("멈춤", "Idle"),
            };
            state_dot(p, pos2(x + 8.0, top + 12.0), state, now, cx.moving);
            p.text(pos2(x + 26.0, top - 1.0), Align2::LEFT_TOP, label, bold(18.0), state_color(state));
            let h = wrapped(p, pos2(x, top + 26.0), w, c.field("text"), 12.5, DIM());
            26.0 + h
        }

        Kind::Metric => {
            let r = p.text(pos2(x, top - 8.0), Align2::LEFT_TOP, c.field("value"), bold(46.0), TEXT());
            p.text(pos2(r.right() + 8.0, r.bottom() - 13.0), Align2::LEFT_BOTTOM, c.field("unit"), bold(12.0), DIM());
            let mut y = r.bottom() + 2.0;
            let delta = c.field("delta");
            if !delta.is_empty() {
                let col = if delta.starts_with('-') {
                    RED()
                } else if delta.starts_with('+') {
                    GREEN()
                } else {
                    DIM()
                };
                p.text(pos2(x, y), Align2::LEFT_TOP, delta, bold(13.0), col);
                y += 21.0;
            }
            y += wrapped(p, pos2(x, y), w, c.field("text"), 12.5, DIM());
            y - top
        }

        Kind::Chart if matches!(c.field("form"), "line" | "donut" | "pie") => {
            let vals: Vec<f32> =
                c.items.iter().map(|(_, cells)| cells.get(1).and_then(|v| v.replace(',', "").parse().ok()).unwrap_or(0.0)).collect();
            let unit = c.field("unit").to_string();
            // 차트는 폭에 어울리는 높이까지만 쓴다. 카드가 더 커도 그 아래를 비워 두지 않고 카드가 줄어든다.
            let cap = if c.field("form") == "line" { (w * 0.34).clamp(170.0, 300.0) } else { (c.items.len() as f32 * 24.0 + 40.0).clamp(190.0, 240.0) };
            let h = body.height().max(150.0).min(cap);
            let k = cx.num("draw", 1.0);
            if k < 1.0 {
                *cx.moving = true;
            }
            if vals.is_empty() {
                return h;
            }
            if c.field("form") == "line" {
                // 꺾은선: 시간에 따라 어떻게 변했는지
                let plot = Rect::from_min_max(pos2(x + 8.0, top + 24.0), pos2(body.right() - 8.0, top + h - 26.0));
                let (lo, hi) = vals.iter().fold((f32::MAX, f32::MIN), |m, v| (m.0.min(*v), m.1.max(*v)));
                let lo = if lo >= 0.0 { 0.0 } else { lo };
                let span = (hi - lo).max(1e-6);
                let n = vals.len();
                let px = |i: usize| if n == 1 { plot.center().x } else { plot.left() + plot.width() * i as f32 / (n - 1) as f32 };
                let pts: Vec<Pos2> = vals.iter().enumerate().map(|(i, v)| pos2(px(i), plot.bottom() - plot.height() * (v - lo) / span)).collect();
                p.line_segment([plot.left_bottom(), plot.right_bottom()], Stroke::new(1.0, BORDER()));
                // 왼쪽에서부터 그려져 나간다.
                let upto = (n as f32 - 1.0) * k;
                let mut drawn: Vec<Pos2> = pts.iter().take(upto.floor() as usize + 1).copied().collect();
                if (upto.floor() as usize) + 1 < n {
                    let i = upto.floor() as usize;
                    drawn.push(pts[i].lerp(pts[i + 1], upto.fract()));
                }
                if drawn.len() >= 2 {
                    p.add(egui::Shape::line(drawn, Stroke::new(2.2, SOFT())));
                }
                let slot = if n > 1 { plot.width() / (n - 1) as f32 } else { plot.width() };
                let peak = vals.iter().cloned().fold(f32::MIN, f32::max);
                for (i, (item, cells)) in c.items.iter().enumerate() {
                    let (rp, _) = cx.row(p, item);
                    let top_one = vals[i] == peak;
                    rp.circle_filled(pts[i], if top_one { 4.5 } else { 3.2 }, if top_one { ORANGE() } else { TEXT() });
                    // 이름과 값은 자기 칸 폭 안에서만 그려서 옆의 것과 겹치지 않게 한다.
                    let cell = Rect::from_center_size(pos2(pts[i].x, plot.center().y), vec2((slot - 4.0).max(24.0), h + 40.0));
                    let lp = rp.with_clip_rect(cell.intersect(rp.clip_rect()));
                    lp.text(pos2(pts[i].x, plot.bottom() + 6.0), Align2::CENTER_TOP, &cells[0], font(11.0), DIM());
                    if n <= 12 {
                        lp.text(pts[i] - vec2(0.0, 8.0), Align2::CENTER_BOTTOM, with_unit(cells.get(1).map(String::as_str).unwrap_or(""), &unit), font(11.0), if top_one { TEXT() } else { SOFT() });
                    }
                }
            } else {
                // 도넛: 전체에서 차지하는 몫
                let total: f32 = vals.iter().map(|v| v.max(0.0)).sum::<f32>().max(1e-6);
                let rad = ((h - 16.0) / 2.0).min(w * 0.22).max(30.0);
                let center = pos2(x + rad + 8.0, top + h / 2.0);
                let shades = [ORANGE(), SOFT(), DIM(), GREEN(), AMBER(), RED(), FAINT(), EDGE()];
                let mut start = -std::f32::consts::FRAC_PI_2;
                for (i, v) in vals.iter().enumerate() {
                    let sweep = std::f32::consts::TAU * v.max(0.0) / total * k;
                    let steps = ((sweep / 0.06).ceil() as usize).max(2);
                    let gap = if vals.len() > 1 { 0.025 } else { 0.0 };
                    let arc: Vec<Pos2> = (0..=steps).map(|s| start + gap + (sweep - 2.0 * gap).max(0.0) * s as f32 / steps as f32).map(|a| center + vec2(a.cos(), a.sin()) * (rad - 8.0)).collect();
                    p.add(egui::Shape::line(arc, Stroke::new(15.0, shades[i % shades.len()])));
                    start += sweep;
                }
                p.text(center, Align2::CENTER_CENTER, with_unit(&trim_num(total), &unit), bold(14.0), TEXT());
                let lx = center.x + rad + 22.0;
                let rh = 24.0_f32.min((h - 8.0) / vals.len() as f32);
                let ly = top + (h - rh * vals.len() as f32) / 2.0;
                for (i, (item, cells)) in c.items.iter().enumerate() {
                    let (rp, _) = cx.row(p, item);
                    let y = ly + i as f32 * rh + rh / 2.0;
                    rp.circle_filled(pos2(lx + 5.0, y), 4.5, shades[i % shades.len()]);
                    let right = rp.text(pos2(body.right(), y), Align2::RIGHT_CENTER, if unit.trim() == "%" { format!("{}%", cells.get(1).map(String::as_str).unwrap_or("")) } else { format!("{}{}  {:.0}%", cells.get(1).map(String::as_str).unwrap_or(""), unit, vals[i].max(0.0) / total * 100.0) }, font(12.5), SOFT());
                    rp.with_clip_rect(Rect::from_min_max(pos2(lx + 16.0, y - rh / 2.0), pos2(right.left() - 8.0, y + rh / 2.0)).intersect(rp.clip_rect())).text(pos2(lx + 18.0, y), Align2::LEFT_CENTER, &cells[0], bold(13.0), TEXT());
                }
            }
            h
        }

        Kind::Route => {
            // 지도 서비스 화면 대신 필요한 것만 직접 그린다: 들르는 곳의 위치, 길의 모양, 구간별 거리와 시간.
            let stops: Vec<(String, (f64, f64), String)> = c
                .items
                .iter()
                .filter_map(|(_, cells)| {
                    let (la, lo) = cells.get(1)?.split_once(',')?;
                    Some((cells[0].clone(), (la.trim().parse().ok()?, lo.trim().parse().ok()?), cells.get(2).cloned().unwrap_or_default()))
                })
                .collect();
            let legs = stops.len().saturating_sub(1);
            let note = c.field("text").to_string();
            let list_h = if body.height() > 300.0 && legs > 0 { legs as f32 * 22.0 + 10.0 + if note.is_empty() { 0.0 } else { 20.0 } } else { 0.0 };
            let map = Rect::from_min_max(body.min, pos2(body.right(), (body.bottom() - list_h).max(body.top() + 170.0)));
            p.rect_filled(map, 14.0, mix(CARD(), RAISED(), 0.45));
            if stops.len() < 2 {
                p.text(map.center(), Align2::CENTER_CENTER, crate::sys::tr("들르는 곳이 둘 이상 있어야 길을 그립니다", "A route needs at least two stops"), font(12.5), FAINT());
                return map.height();
            }
            // 저장해 둔 길의 모양. 없거나 지금의 출발·도착과 맞지 않으면 곧은 선으로 잇는다.
            let straight: Vec<(f64, f64)> = stops.iter().map(|s| s.1).collect();
            let near = |a: (f64, f64), b: (f64, f64)| (a.0 - b.0).abs() < 0.03 && (a.1 - b.1).abs() < 0.03;
            let shape: Vec<(f64, f64)> = match crate::route::path(&c.id) {
                Some(s) if near(s[0], straight[0]) && near(s[s.len() - 1], straight[straight.len() - 1]) => s.to_vec(),
                _ => straight.clone(),
            };
            // 위도·경도를 km 단위의 평면으로 편다 (이만한 넓이에서는 이것으로 충분하다).
            let all = || shape.iter().chain(straight.iter());
            let (la0, la1) = all().fold((f64::MAX, f64::MIN), |m, q| (m.0.min(q.0), m.1.max(q.0)));
            let (lo0, lo1) = all().fold((f64::MAX, f64::MIN), |m, q| (m.0.min(q.1), m.1.max(q.1)));
            let kx = 111.32 * ((la0 + la1) / 2.0).to_radians().cos();
            let ky = 110.57;
            let inner = Rect::from_min_max(map.min + vec2(52.0, 52.0), map.max - vec2(52.0, 40.0));
            let (wkm, hkm) = (((lo1 - lo0) * kx).max(0.05) as f32, ((la1 - la0) * ky).max(0.05) as f32);
            let scale = (inner.width() / wkm).min(inner.height() / hkm);
            let to_px = |q: (f64, f64)| {
                inner.center() + vec2((((q.1 - (lo0 + lo1) / 2.0) * kx) as f32) * scale, -(((q.0 - (la0 + la1) / 2.0) * ky) as f32) * scale)
            };
            let pts: Vec<Pos2> = shape.iter().map(|q| to_px(*q)).collect();
            let mut along = vec![0.0_f32];
            for i in 1..pts.len() {
                along.push(along[i - 1] + (pts[i] - pts[i - 1]).length());
            }
            let total_len = along[along.len() - 1].max(1.0);
            let at_len = |d: f32| {
                let i = along.partition_point(|a| *a < d).clamp(1, pts.len() - 1);
                let seg = (along[i] - along[i - 1]).max(1e-3);
                pts[i - 1].lerp(pts[i], ((d - along[i - 1]) / seg).clamp(0.0, 1.0))
            };
            // 길은 출발지에서부터 그려져 나간다.
            let k = cx.num("draw", 1.0);
            let shown = total_len * k;
            let mut drawn: Vec<Pos2> = pts.iter().zip(&along).take_while(|(_, a)| **a <= shown).map(|(q, _)| *q).collect();
            drawn.push(at_len(shown));
            p.add(egui::Shape::line(drawn.clone(), Stroke::new(6.0, mix(CARD(), RAISED(), 0.45))));
            p.add(egui::Shape::line(drawn, Stroke::new(2.6, SOFT())));
            // 들르는 곳이 길 위의 어디쯤인지 (길을 따라 앞에서부터 가장 가까운 점)
            let mut from = 0;
            let mut stop_at: Vec<f32> = Vec::new();
            for s in &stops {
                let q = to_px(s.1);
                let i = (from..pts.len()).min_by(|a, b| (pts[*a] - q).length_sq().total_cmp(&(pts[*b] - q).length_sq())).unwrap_or(from);
                stop_at.push(along[i]);
                from = i;
            }
            let mut taken: Vec<Rect> = Vec::new();
            let marks: Vec<Pos2> = stops.iter().map(|s| to_px(s.1)).collect();
            for m in &marks {
                taken.push(Rect::from_center_size(*m, vec2(22.0, 22.0)));
            }
            // 이름표: 표식의 오른쪽, 왼쪽, 위, 아래 가운데 다른 것과 겹치지 않는 자리에 놓는다.
            let frame = map.shrink(6.0);
            for (n, s) in stops.iter().enumerate() {
                let size = p.layout_no_wrap(s.0.clone(), bold(12.0), TEXT()).size();
                let m = marks[n];
                // 옆, 위아래 가운데, 그리고 위아래에서 왼쪽·오른쪽으로 치우친 자리까지 본다 (곳들이 붙어 있을 때 겹치지 않게).
                let spots = [
                    Rect::from_min_size(m + vec2(14.0, -size.y / 2.0), size),
                    Rect::from_min_size(m + vec2(-14.0 - size.x, -size.y / 2.0), size),
                    Rect::from_min_size(m + vec2(-size.x / 2.0, -16.0 - size.y), size),
                    Rect::from_min_size(m + vec2(-size.x / 2.0, 16.0), size),
                    Rect::from_min_size(m + vec2(8.0 - size.x, -16.0 - size.y), size),
                    Rect::from_min_size(m + vec2(8.0 - size.x, 16.0), size),
                    Rect::from_min_size(m + vec2(-8.0, -16.0 - size.y), size),
                    Rect::from_min_size(m + vec2(-8.0, 16.0), size),
                    Rect::from_min_size(m + vec2(8.0 - size.x, -34.0 - size.y), size),
                    Rect::from_min_size(m + vec2(8.0 - size.x, 34.0), size),
                ];
                let free = |r: &Rect| frame.contains_rect(*r) && !taken.iter().any(|t| t.expand(2.0).intersects(*r));
                let r = spots.iter().find(|r| free(r)).or_else(|| spots.iter().find(|r| frame.contains_rect(**r))).copied().unwrap_or(spots[0]);
                let lp = p.with_clip_rect(frame.intersect(p.clip_rect()));
                lp.rect_filled(r.expand2(vec2(4.0, 1.0)), 5.0, mix(CARD(), RAISED(), 0.45).gamma_multiply(0.85));
                lp.text(r.min, Align2::LEFT_TOP, &s.0, bold(12.0), TEXT());
                taken.push(r.expand2(vec2(4.0, 1.0)));
            }
            // 구간마다 거리·시간: 그 구간의 한가운데에. 자리가 없으면 그리지 않는다 (아래 목록에 있다).
            for n in 0..legs {
                let leg = &stops[n].2;
                if leg.is_empty() || k < 0.98 {
                    continue;
                }
                let mid = at_len((stop_at[n] + stop_at[n + 1]) / 2.0);
                let size = p.layout_no_wrap(leg.clone(), font(11.0), SOFT()).size() + vec2(14.0, 6.0);
                let spots = [Rect::from_center_size(mid, size), Rect::from_center_size(mid + vec2(0.0, -18.0), size), Rect::from_center_size(mid + vec2(0.0, 18.0), size)];
                if let Some(r) = spots.iter().find(|r| frame.contains_rect(**r) && !taken.iter().any(|t| t.expand(2.0).intersects(**r))) {
                    p.rect_filled(*r, r.height() / 2.0, CARD());
                    p.rect_stroke(*r, r.height() / 2.0, Stroke::new(1.0, BORDER()), StrokeKind::Inside);
                    p.text(r.center(), Align2::CENTER_CENTER, leg, font(11.0), SOFT());
                    taken.push(*r);
                }
            }
            // 표식: 출발과 도착은 채우고, 들르는 곳은 테두리만.
            for (n, m) in marks.iter().enumerate() {
                let end = n == 0 || n + 1 == marks.len();
                let seen = stop_at[n] <= shown + 1.0;
                let a = if seen { 1.0 } else { 0.35 };
                p.circle_filled(*m, 10.0, if end { if n == 0 { ORANGE().gamma_multiply(a) } else { TEXT().gamma_multiply(a) } } else { CARD() });
                if !end {
                    p.circle_stroke(*m, 10.0, Stroke::new(1.6, SOFT().gamma_multiply(a)));
                }
                p.text(*m, Align2::CENTER_CENTER, format!("{}", n + 1), bold(11.0), if end { CARD() } else { SOFT().gamma_multiply(a) });
            }
            // 전체 거리·시간, 그리고 축척
            let total = c.field("total");
            if !total.is_empty() {
                p.with_clip_rect(frame.intersect(p.clip_rect())).text(map.min + vec2(16.0, 13.0), Align2::LEFT_TOP, total, bold(15.5), TEXT());
            }
            let nice = [200.0_f32, 100.0, 50.0, 20.0, 10.0, 5.0, 2.0, 1.0, 0.5, 0.2, 0.1];
            if let Some(km) = nice.iter().find(|km| **km * scale <= map.width() * 0.22) {
                let (a, b) = (pos2(map.right() - 16.0 - km * scale, map.bottom() - 16.0), pos2(map.right() - 16.0, map.bottom() - 16.0));
                p.line_segment([a, b], Stroke::new(1.2, FAINT()));
                p.line_segment([a - vec2(0.0, 3.0), a + vec2(0.0, 3.0)], Stroke::new(1.2, FAINT()));
                p.line_segment([b - vec2(0.0, 3.0), b + vec2(0.0, 3.0)], Stroke::new(1.2, FAINT()));
                let label = if *km >= 1.0 { format!("{km:.0}km") } else { format!("{:.0}m", km * 1000.0) };
                p.text(pos2((a.x + b.x) / 2.0, a.y - 4.0), Align2::CENTER_BOTTOM, label, font(10.0), FAINT());
            }
            if k < 1.0 {
                *cx.moving = true;
            }
            // 아래 목록: 구간별로 어디에서 어디까지 얼마인지
            if list_h > 0.0 {
                for n in 0..legs {
                    let y = map.bottom() + 10.0 + n as f32 * 22.0 + 11.0;
                    let right = p.text(pos2(body.right(), y), Align2::RIGHT_CENTER, &stops[n].2, font(12.5), SOFT());
                    let names = p.with_clip_rect(Rect::from_min_max(pos2(x, y - 11.0), pos2(right.left() - 10.0, y + 11.0)).intersect(p.clip_rect()));
                    names.text(pos2(x + 2.0, y), Align2::LEFT_CENTER, format!("{}  {} → {}  {}", n + 1, stops[n].0, n + 2, stops[n + 1].0), font(12.5), DIM());
                }
                if !note.is_empty() {
                    // 값의 출처와 덧붙일 말 (한 줄, 넘치면 잘린다)
                    let y = map.bottom() + 10.0 + legs as f32 * 22.0 + 12.0;
                    p.with_clip_rect(Rect::from_min_max(pos2(x, y - 10.0), pos2(body.right(), y + 10.0)).intersect(p.clip_rect())).text(pos2(x + 2.0, y), Align2::LEFT_CENTER, &note, font(11.0), FAINT());
                }
            }
            fill_h(c)
        }

        Kind::Draw => {
            // AI 가 직접 그리는 그림. 좌표는 카드 안쪽을 가로·세로 0~100 으로 본 값이다.
            let (bw, bh) = (w, body.height().max(60.0));
            let pair = |t: &str| -> Option<(f32, f32)> {
                let (a, b) = t.split_once(',')?;
                Some((a.trim().parse().ok()?, b.trim().parse().ok()?))
            };
            // 좌표 0~100 은 카드가 정한 크기 전체를 뜻한다. 그림이 그중 일부만 쓰면 나머지는 빈 자리가 되므로,
            // 그림이 실제로 차지하는 세로 범위만 남기고 카드도 그만큼만 차지하게 한다 (세로 비율은 그대로).
            let full_h = (c.h as f32 * (CH + GAP) - GAP - HEAD - 14.0).max(60.0);
            let unit = full_h / 100.0;
            let (mut lo, mut hi) = (f32::MAX, f32::MIN);
            for (_, cells) in &c.items {
                let toks: Vec<&str> = cells.get(1).map(|s| s.split_whitespace().collect()).unwrap_or_default();
                let mut see = |y: f32, pad: f32| {
                    lo = lo.min(y.clamp(0.0, 100.0) - pad);
                    hi = hi.max(y.clamp(0.0, 100.0) + pad);
                };
                match cells[0].as_str() {
                    "rect" if toks.len() >= 2 => {
                        if let (Some(min), Some(wh)) = (pair(toks[0]), pair(toks[1])) {
                            see(min.1, 0.0);
                            see(min.1 + wh.1, 0.0);
                        }
                    }
                    "circle" if toks.len() >= 2 => {
                        if let (Some(center), Ok(radius)) = (pair(toks[0]), toks[1].parse::<f32>()) {
                            see(center.1, bw.min(full_h) * radius.clamp(0.5, 50.0) / 100.0 / unit);
                        }
                    }
                    "text" => toks.iter().filter_map(|t| pair(t)).for_each(|q| see(q.1, 11.0 / unit)),
                    // 선에 붙는 이름표는 선보다 조금 위에 놓인다
                    _ => toks.iter().filter_map(|t| pair(t)).for_each(|q| see(q.1, 16.0 / unit)),
                }
            }
            if lo > hi {
                (lo, hi) = (0.0, 100.0);
            }
            let (lo, hi) = (lo.max(0.0), hi.min(100.0).max(lo.max(0.0) + 1.0));
            let pad = 10.0;
            let want = (hi - lo) * unit + 2.0 * pad;
            // 자리가 더 넓으면 가운데에 두고, 좁으면 그 안에 들어오게 줄인다.
            let scale = unit.min((bh - 2.0 * pad).max(20.0) / (hi - lo));
            let y0 = top + (bh - (hi - lo) * scale) / 2.0;
            let at = |q: (f32, f32)| pos2(x + bw * q.0.clamp(0.0, 100.0) / 100.0, y0 + (q.1.clamp(0.0, 100.0) - lo) * scale);
            for (item, cells) in &c.items {
                let (rp, _) = cx.row(p, item);
                let toks: Vec<&str> = cells.get(1).map(|s| s.split_whitespace().collect()).unwrap_or_default();
                let label = cells.get(2).map(String::as_str).unwrap_or("");
                let color = match cells.get(3).map(|s| s.trim()).unwrap_or("") {
                    "accent" | "orange" | "주황" | "강조" => ORANGE(),
                    "green" | "초록" => GREEN(),
                    "red" | "빨강" => RED(),
                    "amber" | "yellow" | "노랑" => AMBER(),
                    "dim" | "gray" | "회색" => DIM(),
                    "faint" | "옅게" => FAINT(),
                    "white" | "밝게" => TEXT(),
                    _ => SOFT(),
                };
                let size = match cells.get(4).map(|s| s.trim()).unwrap_or("") {
                    "s" | "작게" => 10.5,
                    "l" | "크게" => 17.0,
                    _ => 12.5,
                };
                let points: Vec<Pos2> = toks.iter().filter_map(|t| pair(t)).map(at).collect();
                match cells[0].as_str() {
                    "line" | "arrow" if points.len() >= 2 => {
                        rp.add(egui::Shape::line(points.clone(), Stroke::new(2.0, color)));
                        let (a, b) = (points[points.len() - 2], points[points.len() - 1]);
                        if cells[0] == "arrow" {
                            let d = (b - a).normalized();
                            let n = vec2(-d.y, d.x);
                            rp.add(egui::Shape::convex_polygon(vec![b, b - d * 10.0 + n * 5.0, b - d * 10.0 - n * 5.0], color, Stroke::NONE));
                        }
                        if !label.is_empty() {
                            let mid = points[0].lerp(points[points.len() - 1], 0.5);
                            let sz = rp.layout_no_wrap(label.to_string(), font(size), color).size();
                            let r = Rect::from_center_size(mid + vec2(0.0, -10.0), sz + vec2(8.0, 2.0));
                            rp.rect_filled(r, 4.0, CARD().gamma_multiply(0.9));
                            rp.text(r.center(), Align2::CENTER_CENTER, label, font(size), color);
                        }
                    }
                    "rect" if toks.len() >= 2 => {
                        if let (Some(min), Some(wh)) = (pair(toks[0]), pair(toks[1])) {
                            let r = Rect::from_min_max(at(min), at((min.0 + wh.0, min.1 + wh.1)));
                            rp.rect_filled(r, 8.0, color.gamma_multiply(0.12));
                            rp.rect_stroke(r, 8.0, Stroke::new(1.4, color), StrokeKind::Inside);
                            rp.with_clip_rect(r.shrink(4.0).intersect(rp.clip_rect())).text(r.center(), Align2::CENTER_CENTER, label, bold(size), TEXT());
                        }
                    }
                    "circle" if toks.len() >= 2 => {
                        if let (Some(center), Ok(radius)) = (pair(toks[0]), toks[1].parse::<f32>()) {
                            let rad = bw.min(full_h) * radius.clamp(0.5, 50.0) / 100.0 * (scale / unit);
                            rp.circle_filled(at(center), rad, color.gamma_multiply(0.12));
                            rp.circle_stroke(at(center), rad, Stroke::new(1.4, color));
                            rp.text(at(center), Align2::CENTER_CENTER, label, bold(size), TEXT());
                        }
                    }
                    "text" if !points.is_empty() => {
                        rp.text(points[0], Align2::CENTER_CENTER, label, bold(size), color);
                    }
                    _ => {}
                }
            }
            want
        }

        Kind::Chart => {
            let vals: Vec<f32> =
                c.items.iter().map(|(_, cells)| cells.get(1).and_then(|v| v.replace(',', "").parse().ok()).unwrap_or(0.0)).collect();
            let max = vals.iter().cloned().fold(0.0_f32, f32::max).max(1e-6);
            let rh = 30.0;
            let label_w = c
                .items
                .iter()
                .map(|(_, cells)| p.layout_no_wrap(cells[0].clone(), bold(13.0), TEXT()).size().x)
                .fold(0.0_f32, f32::max)
                .min(w * 0.38)
                + 16.0;
            let value_w = 70.0;
            let unit_s = c.field("unit").to_string();
            for (i, (item, cells)) in c.items.iter().enumerate() {
                let (rp, dy) = cx.row(p, item);
                let y = top + i as f32 * rh + dy;
                let lr = Rect::from_min_size(pos2(x, y), vec2(label_w - 10.0, rh));
                rp.with_clip_rect(lr.intersect(rp.clip_rect())).text(pos2(x, y + rh / 2.0), Align2::LEFT_CENTER, &cells[0], bold(13.0), TEXT());
                let track = Rect::from_min_size(pos2(x + label_w, y + rh / 2.0 - 3.0), vec2((w - label_w - value_w).max(10.0), 6.0));
                rp.rect_filled(track, 3.0, RAISED());
                let frac = cx.num(item, vals[i] / max);
                rp.rect_filled(Rect::from_min_size(track.min, vec2((track.width() * frac).max(6.0), 6.0)), 3.0, SOFT());
                rp.text(
                    pos2(body.right(), y + rh / 2.0),
                    Align2::RIGHT_CENTER,
                    with_unit(cells.get(1).map(String::as_str).unwrap_or(""), &unit_s),
                    font(13.0),
                    SOFT(),
                );
            }
            c.items.len() as f32 * rh
        }

        Kind::Table => {
            let cols: Vec<&str> = c.field("cols").split('|').map(str::trim).filter(|s| !s.is_empty()).collect();
            let n = cols.len().max(c.items.iter().map(|(_, cells)| cells.len()).max().unwrap_or(1)).max(1);
            // 열 너비는 내용 길이에 맞춘다. 다 들어가면 남는 폭을 고르게 나누고, 넘치면 긴 열부터 줄인다.
            let mut want = vec![40.0_f32; n];
            for (i, name) in cols.iter().enumerate() {
                want[i] = want[i].max(p.layout_no_wrap(name.to_string(), font(11.0), FAINT()).size().x);
            }
            for (_, cells) in &c.items {
                for (i, text) in cells.iter().enumerate().take(n) {
                    let f = if i == 0 { bold(13.5) } else { font(13.5) };
                    want[i] = want[i].max(p.layout_no_wrap(text.clone(), f, TEXT()).size().x);
                }
            }
            let pad = 16.0;
            let avail = (w - pad * (n as f32 - 1.0)).max(40.0);
            let total: f32 = want.iter().sum();
            let widths: Vec<f32> = if total <= avail {
                let extra = (avail - total) / n as f32;
                want.iter().map(|x| x + extra).collect()
            } else {
                // 평균보다 짧은 열은 그대로 두고, 긴 열들이 남은 폭을 비율대로 나눠 갖는다.
                let fair = avail / n as f32;
                let short: f32 = want.iter().filter(|x| **x <= fair).sum();
                let long: f32 = want.iter().filter(|x| **x > fair).sum();
                want.iter().map(|x| if *x <= fair { *x } else { x / long * (avail - short) }).collect()
            };
            let lefts: Vec<f32> = widths.iter().scan(x, |acc, wd| {
                let l = *acc;
                *acc += wd + pad;
                Some(l)
            }).collect();
            let mut y = top;
            let cell = |q: &Painter, col: usize, y: f32, text: &str, f: FontId, color: Color32| {
                if col >= n {
                    return;
                }
                let r = Rect::from_min_size(pos2(lefts[col], y), vec2(widths[col], 28.0));
                q.with_clip_rect(r.intersect(q.clip_rect())).text(pos2(r.left(), r.center().y), Align2::LEFT_CENTER, text, f, color);
            };
            if !cols.is_empty() {
                for (i, name) in cols.iter().enumerate() {
                    cell(p, i, y - 4.0, name, font(11.0), FAINT());
                }
                y += 24.0;
            }
            // 첫 칸은 굵은 항목명, 나머지는 옅은 값
            for (item, cells) in &c.items {
                let (rp, dy) = cx.row(p, item);
                for (i, text) in cells.iter().enumerate() {
                    if i == 0 {
                        cell(&rp, i, y + dy, text, bold(13.5), TEXT());
                    } else {
                        cell(&rp, i, y + dy, text, font(13.5), DIM());
                    }
                }
                y += 28.0;
            }
            y - top
        }

        Kind::Progress => {
            let pct = cx.num("pct", c.field("pct").parse().unwrap_or(0.0));
            let r = p.text(pos2(x, top - 6.0), Align2::LEFT_TOP, format!("{pct:.0}%"), bold(30.0), TEXT());
            let tx = r.right() + 12.0;
            let tp = p.with_clip_rect(Rect::from_min_max(pos2(tx, top), pos2(body.right(), top + 30.0)).intersect(p.clip_rect()));
            tp.text(pos2(tx, r.bottom() - 9.0), Align2::LEFT_BOTTOM, c.field("text"), font(12.5), DIM());
            let track = Rect::from_min_size(pos2(x, top + 38.0), vec2(w, 6.0));
            p.rect_filled(track, 3.0, RAISED());
            p.rect_filled(Rect::from_min_size(track.min, vec2((w * pct / 100.0).max(6.0), 6.0)), 3.0, SOFT());
            let mut y = top + 58.0;
            for (item, cells) in &c.items {
                let (rp, dy) = cx.row(p, item);
                let state = cells[0].as_str();
                state_dot(&rp, pos2(x + 8.0, y + 13.0 + dy), state, now, cx.moving);
                let label = cells.get(1).map(String::as_str).unwrap_or("");
                if state == "run" {
                    rp.text(pos2(x + 26.0, y + 13.0 + dy), Align2::LEFT_CENTER, label, bold(13.5), TEXT());
                } else {
                    rp.text(pos2(x + 26.0, y + 13.0 + dy), Align2::LEFT_CENTER, label, font(13.5), DIM());
                }
                y += 28.0;
            }
            y - top
        }

        Kind::Checklist => {
            let mut y = top;
            for (item, cells) in &c.items {
                let (rp, dy) = cx.row(p, item);
                let done = cells[0] == "done";
                let label = cells.get(1).map(String::as_str).unwrap_or("");
                let row = Rect::from_min_size(pos2(x, y), vec2(w, 28.0));
                let r = cx.click(row, ("chk", item));
                let hover = r.as_ref().map(|r| r.hovered()).unwrap_or(false);
                // 마우스가 이 줄 위에 있는가 (줄 위의 × 에 올려도 참)
                let over = r.as_ref().map(|r| r.contains_pointer()).unwrap_or(false);
                if r.map(|r| r.clicked()).unwrap_or(false) {
                    cx.lines.push(format!("upd {} {} {} | {}", c.id, item, if done { "todo" } else { "done" }, label));
                }
                // 체크 상태가 바뀌면 채움이 부드럽게 들어오고 나간다.
                let fill = cx.num(&format!("chk:{item}"), if done { 1.0 } else { 0.0 });
                let bx = Rect::from_center_size(pos2(x + 9.0, y + 14.0 + dy), vec2(17.0, 17.0));
                rp.rect_stroke(bx, 5.0, Stroke::new(1.3, if hover { TEXT() } else { mix(EDGE(), SOFT(), fill) }), StrokeKind::Inside);
                if fill > 0.01 {
                    rp.rect_filled(bx.shrink(8.5 * (1.0 - fill)), 5.0, SOFT());
                    if fill > 0.6 {
                        check_mark(&rp, bx.center(), CARD());
                    }
                }
                rp.text(pos2(x + 28.0, y + 14.0 + dy), Align2::LEFT_CENTER, label, font(13.5), mix(TEXT(), FAINT(), fill));
                // 항목 지우기: 줄에 마우스를 올리면 오른쪽 끝에 × 가 나온다. 줄 전체(체크)보다 나중에 두어서 × 자리에서는 × 가 눌린다.
                let xr = Rect::from_center_size(pos2(x + w - 11.0, y + 14.0 + dy), vec2(22.0, 22.0));
                if over {
                    let del = cx.click(xr, ("chk-del", item)).map(|r| r.on_hover_text(crate::sys::tr("이 항목 지우기", "Remove this item")));
                    let on = del.as_ref().map(|r| r.hovered()).unwrap_or(false);
                    if del.map(|r| r.clicked()).unwrap_or(false) {
                        cx.lines.push(format!("del {} {}", c.id, item));
                    }
                    // 긴 글 위에 겹쳐도 보이게 카드 바탕색으로 덮고 그린다.
                    rp.circle_filled(xr.center(), 10.0, if on { RAISED() } else { CARD() });
                    let (k, s) = (4.0, Stroke::new(1.5, if on { TEXT() } else { DIM() }));
                    rp.line_segment([xr.center() + vec2(-k, -k), xr.center() + vec2(k, k)], s);
                    rp.line_segment([xr.center() + vec2(-k, k), xr.center() + vec2(k, -k)], s);
                }
                y += 28.0;
            }
            y - top
        }

        Kind::Input => {
            // 사용자가 값을 직접 넣는 카드: 항목마다 이름과 입력 칸(글, 숫자, 날짜, 슬라이더), 맨 아래에 보내기.
            let mut y = top + wrapped(p, body.min, w, c.field("text"), 14.5, SOFT()) + 12.0;
            let sent = !c.field("sent").is_empty();
            let editable = !sent && cx.live && cx.alpha >= 0.9;
            for (item, cells) in &c.items {
                let (rp, dy) = cx.row(p, item);
                rp.text(pos2(x + 2.0, y + dy), Align2::LEFT_TOP, &cells[0], font(12.0), DIM());
                let spec = cells.get(1).map(|s| s.trim()).unwrap_or("");
                let answer = cells.get(2).cloned().unwrap_or_default();
                let value = cx.inputs.get(item).cloned().filter(|v| !v.is_empty()).unwrap_or_else(|| answer.clone());
                let r = Rect::from_min_size(pos2(x, y + dy + 19.0), vec2(w, 36.0));
                let mut extra = 0.0;
                match input_kind(spec) {
                    InputKind::Slider(lo, hi, unit) => {
                        // 범위 안에서 끌어서 정한다. 손대기 전에는 값이 없는 것으로 친다(보내지 않는다).
                        let touched = !value.is_empty();
                        let cur = value.parse::<f32>().unwrap_or(lo).clamp(lo, hi);
                        let track = Rect::from_min_max(pos2(r.left() + 8.0, r.center().y - 3.0), pos2(r.right() - 92.0, r.center().y + 3.0));
                        let k = if hi > lo { (cur - lo) / (hi - lo) } else { 0.0 };
                        let knob = pos2(track.left() + track.width() * k, track.center().y);
                        rp.rect_filled(track, 3.0, RAISED());
                        rp.rect_filled(Rect::from_min_max(track.min, pos2(knob.x, track.max.y)), 3.0, if touched { SOFT() } else { BORDER() });
                        rp.circle_filled(knob, 8.0, if touched { TEXT() } else { DIM() });
                        let shown = if touched { format!("{}{}", trim_num(cur), if unit.is_empty() { String::new() } else { format!(" {unit}") }) } else { format!("{}~{} {unit}", trim_num(lo), trim_num(hi)) };
                        rp.with_clip_rect(Rect::from_min_max(pos2(track.right() + 12.0, r.top()), r.max).intersect(rp.clip_rect())).text(pos2(r.right(), r.center().y), Align2::RIGHT_CENTER, shown, bold(13.0), if touched { TEXT() } else { FAINT() });
                        if editable {
                            let grab = Rect::from_min_max(pos2(track.left() - 10.0, r.top()), pos2(track.right() + 10.0, r.bottom()));
                            let resp = cx.ui.interact(grab, cx.id.with(("slider", item.as_str())), Sense::click_and_drag()).on_hover_cursor(CursorIcon::PointingHand);
                            if resp.dragged() || resp.clicked() || resp.is_pointer_button_down_on() {
                                if let Some(pt) = resp.interact_pointer_pos() {
                                    let t = ((pt.x - track.left()) / track.width()).clamp(0.0, 1.0);
                                    let step = if hi - lo >= 20.0 { 1.0 } else if hi - lo >= 2.0 { 0.1 } else { 0.01 };
                                    let v = ((lo + (hi - lo) * t) / step).round() * step;
                                    cx.input_ops.push((item.clone(), if step >= 1.0 { format!("{v:.0}") } else if step >= 0.1 { format!("{v:.1}") } else { format!("{v:.2}") }));
                                }
                            }
                        }
                    }
                    InputKind::Date => {
                        // 달력에서 고른다. 칸을 누르면 아래에 달력이 펼쳐진다.
                        rp.rect_filled(r, 11.0, RAISED());
                        rp.rect_stroke(r, 11.0, Stroke::new(1.0, BORDER()), StrokeKind::Inside);
                        let open_key = format!("{item}#open");
                        let open = editable && cx.inputs.get(&open_key).map(|v| v == "1").unwrap_or(false);
                        if value.is_empty() {
                            rp.text(pos2(r.left() + 12.0, r.center().y), Align2::LEFT_CENTER, crate::sys::tr("날짜 고르기", "Pick a date"), font(14.0), FAINT());
                        } else {
                            rp.text(pos2(r.left() + 12.0, r.center().y), Align2::LEFT_CENTER, &value, font(14.0), TEXT());
                        }
                        rp.text(pos2(r.right() - 14.0, r.center().y), Align2::RIGHT_CENTER, if open { crate::sys::tr("닫기", "Close") } else { crate::sys::tr("달력", "Calendar") }, font(11.0), DIM());
                        if editable && cx.click(r, ("date", item.as_str())).map(|x| x.clicked()).unwrap_or(false) {
                            cx.input_ops.push((open_key.clone(), if open { "0".into() } else { "1".into() }));
                        }
                        if open {
                            let today = crate::sys::now_local().0;
                            let ym_key = format!("{item}#ym");
                            let ym = cx.inputs.get(&ym_key).cloned().unwrap_or_else(|| if value.len() >= 7 { value[..7].to_string() } else { today.chars().take(7).collect() });
                            let (yy, mm) = ym.split_once('-').and_then(|(a, b)| Some((a.parse::<i32>().ok()?, b.parse::<u32>().ok()?))).unwrap_or((2026, 1));
                            let days = match mm {
                                1 | 3 | 5 | 7 | 8 | 10 | 12 => 31,
                                4 | 6 | 9 | 11 => 30,
                                _ => if (yy % 4 == 0 && yy % 100 != 0) || yy % 400 == 0 { 29 } else { 28 },
                            };
                            // 그 달 1일의 요일 (0 = 일요일)
                            let offs = [0, 3, 2, 5, 0, 3, 5, 1, 4, 6, 2, 4];
                            let y2 = if mm < 3 { yy - 1 } else { yy };
                            let first = ((y2 + y2 / 4 - y2 / 100 + y2 / 400 + offs[(mm as usize - 1) % 12] + 1) % 7) as u32;
                            let rows = (first + days).div_ceil(7);
                            let cal_top = r.bottom() + 8.0;
                            let (cw, chh) = (w / 7.0, 26.0);
                            // 머리: 이전 달, 연·월, 다음 달
                            rp.text(pos2(x + w / 2.0, cal_top + 11.0), Align2::CENTER_CENTER, if crate::sys::english() { format!("{} {yy}", ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"][(mm as usize + 11) % 12]) } else { format!("{yy}년 {mm}월") }, bold(13.0), TEXT());
                            for (dir, label, bx) in [(-1i32, crate::sys::tr("이전", "Prev"), x), (1, crate::sys::tr("다음", "Next"), x + w - 44.0)] {
                                let br = Rect::from_min_size(pos2(bx, cal_top), vec2(44.0, 22.0));
                                let hov = cx.click(br, (if dir < 0 { "calprev" } else { "calnext" }, item.as_str()));
                                rp.text(br.center(), Align2::CENTER_CENTER, label, font(11.5), if hov.as_ref().map(|h| h.hovered()).unwrap_or(false) { TEXT() } else { DIM() });
                                if hov.map(|h| h.clicked()).unwrap_or(false) {
                                    let total = yy * 12 + (mm as i32 - 1) + dir;
                                    cx.input_ops.push((ym_key.clone(), format!("{:04}-{:02}", total.div_euclid(12), total.rem_euclid(12) + 1)));
                                }
                            }
                            let names = if crate::sys::english() { ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"] } else { ["일", "월", "화", "수", "목", "금", "토"] };
                            for (i, n) in names.iter().enumerate() {
                                rp.text(pos2(x + cw * (i as f32 + 0.5), cal_top + 34.0), Align2::CENTER_CENTER, *n, font(10.5), FAINT());
                            }
                            for d in 1..=days {
                                let idx = first + d - 1;
                                let cell = Rect::from_min_size(pos2(x + cw * (idx % 7) as f32, cal_top + 46.0 + chh * (idx / 7) as f32), vec2(cw, chh));
                                let date = format!("{yy:04}-{mm:02}-{d:02}");
                                let picked = value.starts_with(&date);
                                let resp = cx.click(cell, ("day", &format!("{item}{d}")));
                                let hov = resp.as_ref().map(|h| h.hovered()).unwrap_or(false);
                                if picked || hov {
                                    rp.rect_filled(cell.shrink(2.0), 8.0, if picked { SOFT() } else { RAISED() });
                                }
                                if date == today && !picked {
                                    rp.rect_stroke(cell.shrink(2.0), 8.0, Stroke::new(1.0, EDGE()), StrokeKind::Inside);
                                }
                                rp.text(cell.center(), Align2::CENTER_CENTER, format!("{d}"), font(12.5), if picked { CARD() } else { TEXT() });
                                if resp.map(|h| h.clicked()).unwrap_or(false) {
                                    cx.input_ops.push((item.clone(), format!("{date} ({})", names[(idx % 7) as usize])));
                                    cx.input_ops.push((open_key.clone(), "0".into()));
                                }
                            }
                            extra = 46.0 + chh * rows as f32 + 8.0;
                        }
                    }
                    kind => {
                        // 글 또는 숫자를 적는 칸
                        rp.rect_filled(r, 11.0, RAISED());
                        rp.rect_stroke(r, 11.0, Stroke::new(1.0, BORDER()), StrokeKind::Inside);
                        let numeric = matches!(kind, InputKind::Number);
                        let hint = if numeric { crate::sys::tr("숫자", "Number").to_string() } else if spec == "-" { String::new() } else { spec.to_string() };
                        if !editable {
                            // 이미 보냈거나 멀리서 보는 중: 적힌 값만 보여 준다.
                            let inner = rp.with_clip_rect(r.shrink2(vec2(12.0, 0.0)).intersect(rp.clip_rect()));
                            if value.is_empty() {
                                inner.text(pos2(r.left() + 12.0, r.center().y), Align2::LEFT_CENTER, &hint, font(14.0), FAINT());
                            } else {
                                inner.text(pos2(r.left() + 12.0, r.center().y), Align2::LEFT_CENTER, &value, font(14.0), TEXT());
                            }
                        } else {
                            cx.fields.push((r.shrink2(vec2(12.0, 7.0)), item.clone(), hint, answer, numeric));
                        }
                    }
                }
                y += 19.0 + 36.0 + 10.0 + extra;
            }
            let br = Rect::from_min_size(pos2(x + w - 92.0, y + 2.0), vec2(92.0, 34.0));
            if sent {
                p.text(br.right_center(), Align2::RIGHT_CENTER, crate::sys::tr("보냈습니다", "Sent"), font(12.5), DIM());
            } else {
                let ready = c.items.iter().any(|(i, _)| cx.inputs.get(i).map(|v| !v.trim().is_empty()).unwrap_or(false));
                let resp = cx.click(br, ("send", ""));
                let hover = resp.as_ref().map(|r| r.hovered()).unwrap_or(false);
                p.rect_filled(br, 17.0, if ready { mix(SOFT(), TEXT(), if hover { 1.0 } else { 0.0 }) } else { RAISED() });
                p.text(br.center(), Align2::CENTER_CENTER, crate::sys::tr("보내기", "Send"), bold(13.5), if ready { CARD() } else { FAINT() });
                if ready && resp.map(|r| r.clicked()).unwrap_or(false) {
                    let out = input_lines(c, cx.inputs);
                    cx.lines.extend(out);
                }
            }
            y + 36.0 - top
        }

        Kind::Image => {
            // 그림 파일이나 그림 주소를 그대로 보여 준다. 카드 안에 비율을 지켜 가장 크게.
            let src = c.field("src").to_string();
            let caption = c.field("text").to_string();
            let h = body.height().max(120.0);
            let area = Rect::from_min_max(body.min, pos2(body.right(), top + h - if caption.is_empty() { 0.0 } else { 24.0 }));
            if src.is_empty() {
                p.rect_filled(area, 10.0, RAISED());
                p.text(area.center(), Align2::CENTER_CENTER, crate::sys::tr("그림이 아직 없습니다", "No picture yet"), font(12.0), FAINT());
            } else {
                match crate::pics::show(cx.ui.ctx(), &src) {
                    crate::pics::Shown::Ready(id, size) => {
                        let k = (area.width() / size[0]).min(area.height() / size[1]).min(1.5);
                        let r = Rect::from_center_size(area.center(), vec2(size[0] * k, size[1] * k));
                        p.image(id, r, Rect::from_min_max(pos2(0.0, 0.0), pos2(1.0, 1.0)), Color32::WHITE);
                    }
                    crate::pics::Shown::Loading => {
                        p.rect_filled(area, 10.0, RAISED());
                        p.text(area.center(), Align2::CENTER_CENTER, crate::sys::tr("그림을 불러오는 중", "Loading the picture"), font(12.0), FAINT());
                        *cx.moving = true;
                    }
                    crate::pics::Shown::Failed(why) => {
                        p.rect_filled(area, 10.0, RAISED());
                        p.with_clip_rect(area.shrink(8.0).intersect(p.clip_rect())).text(area.center(), Align2::CENTER_CENTER, why, font(12.0), RED());
                    }
                }
            }
            if !caption.is_empty() {
                p.with_clip_rect(Rect::from_min_max(pos2(x, area.bottom()), pos2(body.right(), top + h)).intersect(p.clip_rect())).text(pos2(x + 2.0, area.bottom() + 13.0), Align2::LEFT_CENTER, &caption, font(12.0), DIM());
            }
            fill_h(c).max(120.0)
        }

        Kind::Decision => {
            let mut y = top + wrapped(p, body.min, w, c.field("text"), 14.5, SOFT()) + 12.0;
            let chosen = c.field("chosen").to_string();
            for (item, cells) in &c.items {
                let (rp, dy) = cx.row(p, item);
                let has_desc = cells.len() > 1 && !cells[1].is_empty();
                let h = if has_desc { 52.0 } else { 36.0 };
                let r = Rect::from_min_size(pos2(x, y + dy), vec2(w, h));
                let resp = cx.click(r, ("opt", item));
                let hover = resp.as_ref().map(|r| r.hovered()).unwrap_or(false);
                if resp.map(|r| r.clicked()).unwrap_or(false) {
                    cx.lines.push(format!("set {} chosen {}", c.id, item));
                }
                // 테두리만 있는 알약. 고르면 밝게 채워진다.
                let on = cx.num(&format!("opt:{item}"), if chosen == *item { 1.0 } else { 0.0 });
                let glow = cx.num(&format!("hov:{item}"), if hover { 1.0 } else { 0.0 });
                let rad = if has_desc { 16.0 } else { 18.0 };
                rp.rect_filled(r, rad, mix(mix(CARD(), RAISED(), glow), SOFT(), on));
                rp.rect_stroke(r, rad, Stroke::new(1.0, mix(mix(BORDER(), EDGE(), glow), SOFT(), on)), StrokeKind::Inside);
                let inner = rp.with_clip_rect(r.shrink2(vec2(14.0, 0.0)).intersect(rp.clip_rect()));
                let (c1, c2) = (mix(TEXT(), CARD(), on), mix(DIM(), RAISED(), on));
                if has_desc {
                    inner.text(pos2(x + 16.0, y + dy + 9.0), Align2::LEFT_TOP, &cells[0], bold(14.0), c1);
                    inner.text(pos2(x + 16.0, y + dy + 29.0), Align2::LEFT_TOP, &cells[1], font(12.0), c2);
                } else {
                    inner.text(pos2(x + 16.0, y + dy + h / 2.0), Align2::LEFT_CENTER, &cells[0], bold(14.0), c1);
                }
                y += h + 8.0;
            }
            y - 8.0 - top
        }

        Kind::Timeline => {
            let n = c.items.len();
            let rh = 30.0;
            if n > 1 {
                p.line_segment([pos2(x + 6.0, top + rh / 2.0), pos2(x + 6.0, top + rh * (n as f32 - 0.5))], Stroke::new(1.2, BORDER()));
            }
            let when_w = c
                .items
                .iter()
                .map(|(_, cells)| p.layout_no_wrap(cells[0].clone(), bold(12.5), TEXT()).size().x)
                .fold(0.0_f32, f32::max)
                .min(w * 0.4);
            for (i, (item, cells)) in c.items.iter().enumerate() {
                let (rp, dy) = cx.row(p, item);
                let y = top + i as f32 * rh + rh / 2.0 + dy;
                let state = cells.get(2).map(String::as_str).unwrap_or("todo");
                rp.circle_filled(pos2(x + 6.0, y), 6.0, CARD());
                match state {
                    "done" => {
                        rp.circle_filled(pos2(x + 6.0, y), 4.5, SOFT());
                    }
                    "run" => state_dot(&rp, pos2(x + 6.0, y), "run", now, cx.moving),
                    _ => {
                        rp.circle_stroke(pos2(x + 6.0, y), 4.0, Stroke::new(1.4, EDGE()));
                    }
                }
                // 굵은 시점 + 옅은 내용
                rp.text(pos2(x + 22.0, y), Align2::LEFT_CENTER, &cells[0], bold(12.5), if state == "done" { DIM() } else { TEXT() });
                rp.text(
                    pos2(x + 36.0 + when_w, y),
                    Align2::LEFT_CENTER,
                    cells.get(1).map(String::as_str).unwrap_or(""),
                    font(13.5),
                    if state == "done" { FAINT() } else { SOFT() },
                );
            }
            n as f32 * rh
        }
    }
}
