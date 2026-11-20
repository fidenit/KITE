// "내 차례" 칸에 무엇을 붙이나 (memory/specs/aperit-pro-grid-dock-v1.md §C1). 안드로이드·Compose 없이 카드만 다룬다.
// 데스크톱 src/main.rs 의 pick_task / next_task 를 옮기고 폰 규칙을 더했다:
// 답하면 기다리는 다음 일이 바로 붙고(대표 msg3478 "컴펌하거나 처리하면 바로 다음 할일 붙고"), 기다리는 일이 없을 때만 "반영하는 중"으로 남는다.
// 새로 온 질문은 줄에 설 뿐 붙어 있는 질문을 밀어내지 않는다 (밀어내는 것은 AI 의 focus 하나).
package com.fidenit.kite.core

object Todo {
    /** 답을 기다리는 카드: decision·input 이고 chosen·sent 가 비어 있다. 체크리스트는 사용자 자신의 목록이라 넣지 않는다. */
    fun waiting(card: Card): Boolean = card.kind.asks && card.field("chosen").isEmpty() && card.field("sent").isEmpty()

    /** 보드(보관함은 빼고)에서 답을 기다리는 카드, 보드 순서(만든 순서). 모든 주제. */
    fun asks(board: Board): List<Card> = board.cards.filter(::waiting)
}

/** 내 차례 칸에 붙일 카드 하나를 고른다. 화면 모델이 보드가 바뀔 때마다, 턴이 시작·끝날 때, 켤 때 [update] 를 부른다. */
class TodoPicker {
    /** 칸에 붙은 카드 (없으면 칸이 없다) */
    var docked: String? = null
        private set

    /** 붙은 카드에 답했고 기다리는 다른 일이 없어 AI 가 반영하는 동안 그대로 보인다 (규칙 4) */
    var applying = false
        private set

    /** 마지막으로 붙인 카드의 주제 (답한 뒤 같은 주제의 일부터) */
    var lastTopic: String? = null
        private set

    /** 마지막으로 고른 까닭 (기록용): focus, keep, next-topic, next-new, next-oldest, applying, none, tap, cycle */
    var reason = ""
        private set

    /** 이미 본 질문 (처음 부를 때 그때 있던 질문으로 시작한다: 켤 때는 가장 오래된 것이 붙는다) */
    private var known: MutableSet<String>? = null
    private var focusSeen: String? = null

    /**
     * `asks` = [Todo.asks] (보드 순서), `allCards` = 보드의 모든 카드, `busy` = AI 가 도는 중(또는 방금 한 답을 넘기는 중),
     * `focus` = 보드의 focus, `editing(id)` = 그 카드의 글 칸에 포커스가 있거나 보내지 않은 글이 있다. 붙은 카드의 ID 를 돌려준다.
     */
    fun update(asks: List<Card>, allCards: List<Card>, busy: Boolean, focus: String?, editing: (String) -> Boolean): String? {
        val seen = known ?: asks.mapTo(HashSet()) { it.id }.also { known = it }
        try {
            // 1. AI 가 새로 focus 한 질문: 붙은 카드에 적는 중이 아니면 그것을 붙인다 (붙은 질문을 밀어낼 수 있는 것은 이것뿐)
            if (focus != focusSeen) {
                focusSeen = focus
                val f = asks.firstOrNull { it.id == focus }
                val d = docked
                if (f != null && f.id != d && (d == null || !editing(d))) {
                    dock(f, "focus")
                    return docked
                }
            }
            val d = docked
            // 2. 붙은 질문이 아직 기다린다: 그대로 둔다. 새 질문은 줄에 선다 (남은 건수만 는다)
            if (d != null && asks.any { it.id == d }) {
                applying = false
                reason = "keep"
                return d
            }
            // 3. 답했거나 지워졌다 (또는 아무것도 없었다): 기다리는 일이 있으면 바로 붙인다. 같은 주제 → 처음 보는 것 → 가장 오래된 것
            if (asks.isNotEmpty()) {
                val same = asks.firstOrNull { it.topic == lastTopic }
                val fresh = asks.firstOrNull { it.id !in seen }
                when {
                    same != null -> dock(same, "next-topic")
                    fresh != null -> dock(fresh, "next-new")
                    else -> dock(asks.first(), "next-oldest")
                }
                return docked
            }
            // 4. 기다리는 일이 없고, 답한 카드가 아직 보드에 있고, AI 가 반영하는 중: "반영하는 중"으로 남긴다
            if (d != null && busy && allCards.any { it.id == d }) {
                applying = true
                reason = "applying"
                return d
            }
            // 5. 칸을 닫는다
            docked = null
            applying = false
            reason = "none"
            return null
        } finally {
            asks.mapTo(seen) { it.id }
        }
    }

    /** 다음 ›: 기다리는 일이 둘 이상이면 보드 순서로 다음 것 (끝이면 처음으로). */
    fun next(asks: List<Card>): String? {
        if (asks.size < 2) return docked
        val at = asks.indexOfFirst { it.id == docked }
        dock(asks[(at + 1) % asks.size], "cycle")
        return docked
    }

    /** 보드의 답하기: 그 질문을 붙인다 (기다리는 질문일 때만). */
    fun dockManually(id: String, asks: List<Card>): String? {
        asks.firstOrNull { it.id == id }?.let { dock(it, "tap") }
        return docked
    }

    private fun dock(card: Card, why: String) {
        docked = card.id
        lastTopic = card.topic
        applying = false
        reason = why
    }
}
