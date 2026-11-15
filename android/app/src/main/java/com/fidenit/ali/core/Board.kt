// 화면 트리(보드)와 조작. 데스크톱의 src/core.rs 를 그대로 옮긴 것이다.
// 부품 목록(PARTS)과 검사 규칙은 core.rs 와 같아야 한다: 모델에게 주는 부품 표가 여기서 만들어진다.
package com.fidenit.ali.core

import java.util.TreeMap

enum class Kind {
    Note, Status, Progress, Decision, Input, Checklist, Table, Metric, Chart, Timeline, Route, Draw, Image, Web;

    val part: Part get() = PARTS.first { it.kind == this }
    val label: String get() = part.name

    /** 사용자의 답을 기다리는 카드인가 ("내 차례"로 가져갈 것) */
    val asks: Boolean get() = this == Decision || this == Input
    val hasItems: Boolean get() = part.items.isNotEmpty()

    /** 필드별 글자 수 한도. 이 종류에 없는 필드면 null. */
    fun fieldLimit(field: String): Int? {
        if (field == "title") return 40
        return part.fields.firstOrNull { it.name == field }?.limit
    }

    companion object {
        fun parse(s: String): Kind? = PARTS.firstOrNull { it.name == s }?.kind
    }
}

/** 필드: 이름, 글자 한도, 모델에게 보일 설명 (비어 있으면 프로그램이 안에서만 쓰는 필드). */
class Field(val name: String, val limit: Int, val doc: String)

class Part(val kind: Kind, val name: String, val w: Int, val h: Int, val purpose: String, val fields: List<Field>, val items: String)

/** 등록된 부품들. 여기 적힌 순서대로 모델에게 소개된다. */
val PARTS: List<Part> = listOf(
    Part(Kind.Status, "status", 2, 1, "상태 한 가지", listOf(Field("state", 8, "state: ok/run/wait/fail/idle"), Field("text", 80, "text(80)")), ""),
    Part(Kind.Metric, "metric", 2, 2, "숫자 하나", listOf(Field("value", 14, "value(14)"), Field("unit", 8, "unit(8)"), Field("delta", 14, "delta(14, +/-로 시작하면 색이 붙음)"), Field("text", 60, "text(60)")), ""),
    Part(Kind.Chart, "chart", 4, 3, "수량을 그림으로: 비교(막대), 변화(꺾은선), 몫(도넛)", listOf(Field("unit", 8, "unit(8)"), Field("form", 8, "form: bar(기본)/line/donut")), "이름 | 숫자"),
    Part(Kind.Route, "route", 6, 5, "경로·동선: 들르는 곳의 위치와 길의 모양, 구간별 거리·시간을 직접 그린다", listOf(Field("total", 40, "total(전체 거리·시간, 40)"), Field("text", 80, "text(80)")), "이름 | 위도,경도 | 다음 곳까지의 거리·시간"),
    Part(Kind.Draw, "draw", 5, 4, "다른 부품으로 안 되는 그림을 직접 그린다: 구조도, 흐름도, 자리 배치, 관계도", listOf(Field("text", 80, "text(80)")), "도형 | 좌표 | 글 | 색 | 크기"),
    Part(Kind.Image, "image", 4, 3, "그림 파일이나 그림 주소를 그대로 보여 준다: 사진, 캡처, 만든 그림", listOf(Field("src", 600, "src: 그림 파일의 절대 경로 또는 그림의 웹 주소"), Field("text", 80, "text(그림 설명, 80)")), ""),
    Part(Kind.Table, "table", 4, 3, "여러 속성의 비교·목록", listOf(Field("cols", 200, "cols: 열이름 | 열이름 ...")), "칸 | 칸 ... (최대 6칸)"),
    Part(Kind.Progress, "progress", 3, 3, "진행 중인 작업", listOf(Field("pct", 3, "pct(0~100)"), Field("text", 60, "text(60)")), "done/run/todo/fail | 단계 이름"),
    Part(Kind.Checklist, "checklist", 3, 3, "할 일 (사용자가 직접 체크할 수 있음)", listOf(), "done/todo | 내용"),
    Part(Kind.Decision, "decision", 3, 3, "사용자의 결정이 필요한 것 (고르기)", listOf(Field("text", 120, "text(질문, 120)"), Field("chosen", 24, "")), "선택지 이름 | 한 줄 설명"),
    Part(Kind.Input, "input", 3, 3, "사용자에게서 값을 받아야 하는 것 (적어 넣기): 날짜, 이름, 숫자, 주소", listOf(Field("text", 120, "text(무엇을 왜 묻는지, 120)"), Field("sent", 8, "")), "칸 이름 | 안내 글(예시) 또는 칸의 종류: date / number / slider 0~100 단위"),
    Part(Kind.Timeline, "timeline", 3, 3, "시간 순서", listOf(), "시점 | 내용 | done/run/todo(생략 가능)"),
    Part(Kind.Note, "note", 3, 2, "위 어느 것으로도 안 되는 짧은 글", listOf(Field("text", 240, "text(240)")), ""),
    Part(Kind.Web, "web", 6, 5, "웹 페이지나 HTML 파일을 그대로 보여 주는 작은 브라우저", listOf(Field("url", 600, "url: 웹 주소 또는 내 컴퓨터의 파일 절대 경로")), ""),
)

/** 모델에게 줄 부품 표 (마크다운). 등록된 내용에서 만든다. */
fun partsTable(): String {
    val t = StringBuilder("| 종류 | 용도 | 필드 (글자 한도) | 항목 형식 | 기본 크기 |\n|---|---|---|---|---|\n")
    fun cell(s: String) = if (s.isEmpty()) "없음" else s.replace("|", "\\|")
    for (p in PARTS) {
        val fields = p.fields.map { it.doc }.filter { it.isNotEmpty() }
        t.append("| ${p.name} | ${p.purpose} | ${cell(fields.joinToString(", "))} | ${cell(p.items)} | ${p.w}x${p.h} |\n")
    }
    return t.toString()
}

const val MAX_ITEMS = 24
const val MAX_CELLS = 6
const val CELL_LIMIT = 60
const val MAX_W = 8
const val MAX_H = 8

enum class Author { Ai, User }

sealed class Op {
    data class Topic(val id: String, val name: String) : Op()
    data class Group(val id: String, val name: String) : Op()
    data class Mv(val card: String, val topic: String) : Op()
    data class Put(val topic: String, val group: String) : Op()
    data class Card(val id: String, val kind: String, val topic: String, val size: Pair<Int, Int>?, val title: String) : Op()
    data class Set(val id: String, val field: String, val value: String) : Op()
    data class Add(val id: String, val item: String, val cells: List<String>) : Op()
    data class Upd(val id: String, val item: String, val cells: List<String>) : Op()
    data class Del(val id: String, val item: String?) : Op()
    data class Size(val id: String, val w: Int, val h: Int) : Op()
    data class Move(val id: String, val col: Int, val row: Int) : Op()
    data class Pin(val id: String, val on: Boolean) : Op()
    data class Focus(val id: String) : Op()
    data class Say(val text: String) : Op()
    data class Let(val name: String, val value: String) : Op()
    data class Archive(val topic: String) : Op()
    data class Restore(val topic: String) : Op()
}

class Group(val id: String, var name: String)
class Topic(val id: String, var name: String, var group: String? = null)
class Item(val id: String, var cells: List<String>)

class Card(
    val id: String,
    val kind: Kind,
    var topic: String,
    var title: String,
    var w: Int,
    var h: Int,
) {
    val fields: TreeMap<String, String> = TreeMap()
    val items: MutableList<Item> = mutableListOf()
    var grid: Pair<Int, Int>? = null
    var pinned: Boolean = false
    var rev: Long = 1

    /** 아직 줄이 끝나지 않은 채 흘러 들어오는 중인 값 (필드, 부분 문자열). */
    var live: Pair<String, String>? = null

    fun field(name: String): String {
        live?.let { if (it.first == name) return it.second }
        return fields[name] ?: ""
    }
}

class Shelved(val topic: Topic, val cards: List<Card>)

/** 조작이 거부된 이유. */
class Rejected(message: String) : Exception(message)

fun chars(s: String): Int = s.codePointCount(0, s.length)

fun clip(s: String, limit: Int): String {
    if (chars(s) <= limit) return s
    val keep = (limit - 1).coerceAtLeast(0)
    return s.substring(0, s.offsetByCodePoints(0, keep)) + "…"
}

private fun validId(id: String): Boolean =
    id.isNotEmpty() && chars(id) <= 24 && id.codePoints().allMatch { Character.isLetterOrDigit(it) || it == '_'.code || it == '-'.code }

class Board {
    val groups: MutableList<Group> = mutableListOf()
    val topics: MutableList<Topic> = mutableListOf()
    val cards: MutableList<Card> = mutableListOf()
    val shelf: MutableList<Shelved> = mutableListOf()
    val vars: TreeMap<String, String> = TreeMap()
    var say: String = ""
    var focus: String? = null

    /** 한도 초과로 잘린 것 등, 다음 턴에 모델에게 알려줄 내용. */
    val notes: MutableList<String> = mutableListOf()

    fun card(id: String): Card? = cards.firstOrNull { it.id == id }
    private fun cardMut(id: String): Card = card(id) ?: throw Rejected("카드 '$id' 없음")

    /** 글 안의 {이름} 을 이름 붙은 값으로 바꿔 보여 준다. */
    fun show(s: String): String {
        if (vars.isEmpty() || !s.contains('{')) return s
        return Regex("\\{([^{}\\s]+)\\}").replace(s) { m -> vars[m.groupValues[1]] ?: m.value }
    }

    /** 조작 하나를 검사하고 반영한다. 거부되면 Rejected 를 던진다. */
    fun apply(op: Op, by: Author) {
        when (op) {
            is Op.Topic -> {
                if (!validId(op.id)) throw Rejected("주제 ID '${op.id}' 형식 오류")
                if (shelf.any { it.topic.id == op.id }) throw Rejected("주제 '${op.id}' 는 보관되어 있음. 다시 쓰려면 restore ${op.id} 로 꺼낸다")
                val name = clip(op.name.ifEmpty { op.id }, 24)
                val t = topics.firstOrNull { it.id == op.id }
                if (t != null) t.name = name else topics.add(Topic(op.id, name))
            }
            is Op.Group -> {
                if (!validId(op.id)) throw Rejected("분류 ID '${op.id}' 형식 오류")
                if (topics.any { it.id == op.id } || card(op.id) != null) throw Rejected("'${op.id}' 는 이미 주제나 카드의 ID 로 쓰이고 있음")
                val name = clip(op.name.ifEmpty { op.id }, 16)
                val g = groups.firstOrNull { it.id == op.id }
                if (g != null) {
                    g.name = name
                } else {
                    if (groups.size >= 8) throw Rejected("분류는 8개까지. 더 넓게 묶을 것")
                    groups.add(Group(op.id, name))
                }
            }
            is Op.Mv -> {
                if (topics.none { it.id == op.topic }) throw Rejected("주제 '${op.topic}' 없음")
                val c = cardMut(op.card)
                c.topic = op.topic
                c.grid = null
                c.pinned = false
                c.rev++
            }
            is Op.Put -> {
                if (groups.none { it.id == op.group }) throw Rejected("분류 '${op.group}' 없음 — 먼저 group 으로 만들 것")
                val t = topics.firstOrNull { it.id == op.topic } ?: throw Rejected("주제 '${op.topic}' 없음")
                t.group = op.group
            }
            is Op.Card -> {
                if (shelf.any { s -> s.cards.any { it.id == op.id } }) throw Rejected("카드 ID '${op.id}' 는 보관된 주제의 카드가 쓰고 있음. 다른 ID 를 쓴다")
                if (!validId(op.id)) throw Rejected("카드 ID '${op.id}' 형식 오류")
                if (card(op.id) != null) throw Rejected("카드 '${op.id}' 는 이미 있음 — 새로 만들지 말고 set/add/upd 로 고칠 것")
                val k = Kind.parse(op.kind) ?: throw Rejected("부품 종류 '${op.kind}' 없음")
                if (topics.none { it.id == op.topic }) throw Rejected("주제 '${op.topic}' 없음 — 먼저 topic 으로 만들 것")
                val (w, h) = op.size ?: (k.part.w to k.part.h)
                if (chars(op.title) > 40) notes.add("${op.id}.title 40자 초과로 잘림")
                cards.add(Card(op.id, k, op.topic, clip(op.title, 40), w.coerceIn(1, MAX_W), h.coerceIn(1, MAX_H)))
            }
            is Op.Set -> {
                val c = cardMut(op.id)
                val limit = c.kind.fieldLimit(op.field) ?: throw Rejected("${c.kind.label} 에는 '${op.field}' 필드 없음")
                if (c.kind == Kind.Status && op.field == "state" && op.value !in listOf("ok", "run", "wait", "fail", "idle")) {
                    throw Rejected("status.state 는 ok|run|wait|fail|idle 중 하나 ('${op.value}')")
                }
                if (c.kind == Kind.Progress && op.field == "pct" && (op.value.toIntOrNull()?.let { it !in 0..100 } ?: true)) {
                    throw Rejected("progress.pct 는 0~100 정수 ('${op.value}')")
                }
                if (chars(op.value) > limit) notes.add("${op.id}.${op.field} ${limit}자 초과로 잘림 — 더 짧게 쓰거나 다른 부품을 쓸 것")
                val v = clip(op.value, limit)
                if (op.field == "title") c.title = v else c.fields[op.field] = v
                c.live = null
                c.rev++
            }
            is Op.Add -> putItem(op.id, op.item, op.cells, false)
            is Op.Upd -> putItem(op.id, op.item, op.cells, true)
            is Op.Del -> {
                if (op.item != null) {
                    val c = cardMut(op.id)
                    if (!c.items.removeAll { it.id == op.item }) throw Rejected("${op.id} 에 항목 '${op.item}' 없음")
                    c.rev++
                } else {
                    if (!cards.removeAll { it.id == op.id }) {
                        // 카드가 아니면 주제 삭제로 본다 (비어 있을 때만).
                        if (topics.any { it.id == op.id }) {
                            if (cards.any { it.topic == op.id }) throw Rejected("주제 '${op.id}' 에 카드가 남아 있음")
                            topics.removeAll { it.id == op.id }
                        } else if (groups.any { it.id == op.id }) {
                            groups.removeAll { it.id == op.id }
                            topics.filter { it.group == op.id }.forEach { it.group = null }
                        } else {
                            throw Rejected("'${op.id}' 없음")
                        }
                    }
                    if (focus == op.id) focus = null
                }
            }
            is Op.Size -> {
                val c = cardMut(op.id)
                if (by == Author.Ai && c.pinned) throw Rejected("${op.id} 는 사용자가 고정함 — 크기 변경 불가")
                c.w = op.w.coerceIn(1, MAX_W)
                c.h = op.h.coerceIn(1, MAX_H)
                if (by == Author.User) c.pinned = true
                c.rev++
            }
            is Op.Move -> {
                val c = cardMut(op.id)
                if (by == Author.Ai && c.pinned) throw Rejected("${op.id} 는 사용자가 고정함 — 이동 불가")
                c.grid = op.col.coerceAtLeast(0) to op.row.coerceAtLeast(0)
                if (by == Author.User) c.pinned = true
            }
            is Op.Pin -> {
                if (by == Author.Ai) throw Rejected("pin 은 사용자만 가능")
                val c = cardMut(op.id)
                c.pinned = op.on
                if (!op.on) c.grid = null
            }
            is Op.Focus -> {
                cardMut(op.id)
                focus = op.id
            }
            is Op.Let -> {
                if (!validId(op.name)) throw Rejected("값의 이름 '${op.name}' 형식 오류 (영문·숫자로 짧게)")
                if (op.value.isBlank()) {
                    vars.remove(op.name)
                } else {
                    if (!vars.containsKey(op.name) && vars.size >= 64) throw Rejected("이름 붙은 값은 64개까지")
                    if (chars(op.value) > CELL_LIMIT) notes.add("값 ${op.name} ${CELL_LIMIT}자 초과로 잘림")
                    vars[op.name] = clip(op.value.trim(), CELL_LIMIT)
                }
            }
            is Op.Archive -> {
                val t = topics.firstOrNull { it.id == op.topic } ?: throw Rejected("주제 '${op.topic}' 없음")
                topics.remove(t)
                val gone = cards.filter { it.topic == op.topic }
                cards.removeAll(gone)
                if (gone.any { it.id == focus }) focus = null
                shelf.add(Shelved(t, gone))
            }
            is Op.Restore -> {
                val s = shelf.firstOrNull { it.topic.id == op.topic } ?: throw Rejected("보관함에 주제 '${op.topic}' 없음")
                shelf.remove(s)
                // 보관해 둔 사이에 분류가 없어졌으면 미분류로 꺼낸다.
                if (s.topic.group != null && groups.none { it.id == s.topic.group }) s.topic.group = null
                topics.add(s.topic)
                for (c in s.cards) {
                    c.rev++
                    cards.add(c)
                }
            }
            is Op.Say -> {
                if (chars(op.text) > 80) notes.add("say 80자 초과로 잘림")
                say = clip(op.text, 80)
            }
        }
    }

    private fun putItem(id: String, item: String, cells: List<String>, update: Boolean) {
        val c = cardMut(id)
        if (!c.kind.hasItems) throw Rejected("${c.kind.label} 에는 항목을 넣을 수 없음")
        if (!validId(item)) throw Rejected("항목 ID '$item' 형식 오류")
        if (cells.isEmpty() || cells.size > MAX_CELLS) throw Rejected("항목의 칸 수는 1~$MAX_CELLS")
        val states = when (c.kind) {
            Kind.Checklist -> listOf("done", "todo")
            Kind.Progress -> listOf("done", "run", "todo", "fail")
            else -> emptyList()
        }
        if (states.isNotEmpty() && cells[0] !in states) throw Rejected("${c.kind.label} 항목의 첫 칸은 ${states.joinToString("|")} 중 하나 ('${cells[0]}')")
        if (c.kind == Kind.Route) {
            val ok = cells.getOrNull(1)?.split(',', limit = 2)?.let { p -> p.size == 2 && p[0].trim().toDoubleOrNull() != null && p[1].trim().toDoubleOrNull() != null } ?: false
            if (!ok) throw Rejected("route 항목은 '이름 | 위도,경도 | 다음 곳까지의 거리·시간(생략 가능)'. 좌표는 route_find 가 알려 준 것을 쓴다")
        }
        if (c.kind == Kind.Draw && cells[0] !in listOf("line", "arrow", "rect", "circle", "text")) {
            throw Rejected("draw 항목의 첫 칸은 line|arrow|rect|circle|text 중 하나 ('${cells[0]}')")
        }
        if (c.kind == Kind.Chart && cells.getOrNull(1)?.replace(",", "")?.toDoubleOrNull() == null) throw Rejected("chart 항목은 '이름 | 숫자'")
        if (cells.any { chars(it) > CELL_LIMIT }) notes.add("$id.$item 칸 ${CELL_LIMIT}자 초과로 잘림")
        val clipped = cells.map { clip(it, CELL_LIMIT) }
        val slot = c.items.firstOrNull { it.id == item }
        if (slot != null) {
            slot.cells = clipped // add 가 기존 ID 에 오면 덮어쓴다 (중복 생성 방지)
        } else {
            if (update) throw Rejected("$id 에 항목 '$item' 없음 — add 로 추가할 것")
            // 그림 카드는 도형 하나가 항목 하나라서 더 많이 받는다.
            val limit = if (c.kind == Kind.Draw) 80 else MAX_ITEMS
            if (c.items.size >= limit) throw Rejected("$id 항목 수 한도($limit) 초과")
            c.items.add(Item(item, clipped))
        }
        c.rev++
    }
}
