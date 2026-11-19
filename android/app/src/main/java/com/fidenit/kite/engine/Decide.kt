// 메인 모델의 앞뒤에 놓이는 작은 결정들. 전부 Jev(판단 전용 모델)에 맡긴다. 데스크톱의 src/decide.rs 에서 옮겼다:
// 질문 글과 기준 숫자는 거기 것과 같다 (새 숫자를 만들지 않았다).
//   앞 (before): 입력이 들어온 직후. 요청의 종류, 어느 주제의 이야기인가, 필요한 생각의 깊이를 정하고, 받았다는 한마디를 고른다.
//   뒤 (after):  메인 모델이 화면을 고친 뒤. 건드린 카드가 화면만 보고 분명한지, 얼마나 중요한지를 정한다.
// Jev 키가 없거나 응답이 늦으면 null 을 돌려주고, 앱은 기본값으로 동작한다. 안드로이드 API 를 쓰지 않는다 (JVM 에서 시험한다).
package com.fidenit.kite.engine

import org.json.JSONObject
import java.util.Locale

object Decide {
    /** 질문을 고르는 기준 (decide.rs:89-94, 170): 확신이 이 값 이상일 때만 따른다. */
    const val EFFORT_MIN = 0.5
    const val KIND_MIN = 0.5
    const val TOPIC_MIN = 0.6

    /** 카드가 분명할 확률이 이 값보다 낮으면 "뚜렷하지 않음" (decide.rs:170). */
    const val CLEAR_BELOW = 0.4

    /** 한 번에 묻는 카드 수 (decide.rs:145). */
    const val MAX_CARDS = 20

    /** 직전 대화로 함께 보내는 턴 수 (main.rs:1571). */
    const val RECENT_TURNS = 3

    private val EFFORTS = listOf("low", "medium", "high")
    private val KINDS = listOf("대화", "화면 수정", "작업", "기억 찾기")

    /** 앞 판단의 결과. 확신이 모자란 것은 null. `ack` 는 일을 시킨 말에 먼저 건네는 한마디. */
    class Pre(val effort: String?, val kind: String?, val topic: String?, val ack: String?)

    /** 뒤 판단의 결과: 화면만 봐서는 무엇인지 알기 어려운 카드 (ID, 분명할 확률)와 카드별 중요도(0~1). */
    class Post(val unclear: List<Pair<String, Double>>, val importance: Map<String, Double>)

    /** 뒤 판단에 넘기는 카드 하나: ID, 주제 이름, 제목. */
    class CardRef(val id: String, val topic: String, val title: String)

    /** 직전 대화 (최근 [RECENT_TURNS] 턴) 를 판단 모델에게 보여 줄 글로 만든다. */
    fun recentText(recent: List<Pair<String, String>>): String =
        recent.takeLast(RECENT_TURNS).joinToString("") { (u, a) -> "사용자: $u\n응답: $a\n" }

    /** 응답에서 앞 판단의 결과를 고른다 (decide.rs:89-123). 시험할 수 있게 부르기와 나눴다. */
    fun pre(a: Map<String, JevAnswer>, english: Boolean): Pre {
        fun pick(name: String, min: Double) = a[name]?.takeIf { it.confidence >= min }?.choice
        val kind = pick("kind", KIND_MIN)?.takeIf { it in KINDS }
        return Pre(
            effort = pick("effort", EFFORT_MIN)?.takeIf { it in EFFORTS },
            kind = kind,
            topic = pick("topic", TOPIC_MIN)?.takeIf { it != "new" },
            ack = when (kind) {
                "작업" -> if (english) "Sure, on it." else "네, 바로 해볼게요."
                "화면 수정" -> if (english) "Okay, updating." else "네, 고칠게요."
                "기억 찾기" -> if (english) "One moment, let me look." else "잠깐만요, 찾아볼게요."
                else -> null
            },
        )
    }

    /**
     * 턴 앞의 판단 (decide.rs:35). 키가 없거나 입력이 비었으면 부르지 않고 null.
     * `topics` 는 화면에 있는 (ID, 이름), `recent` 는 [recentText]. 입출력 스레드에서 부른다.
     */
    fun before(
        key: String,
        input: String,
        topics: List<Pair<String, String>>,
        recent: String,
        english: Boolean,
        url: String = Jev.URL,
        limitMs: Long = Jev.LIMIT_MS,
        log: (String) -> Unit = {},
    ): Pre? {
        if (key.isBlank() || input.isBlank()) return null
        val state = StringBuilder("사용자가 방금 한 말:\n$input\n")
        if (recent.isNotEmpty()) state.append("\n직전 대화:\n$recent\n")
        if (topics.isNotEmpty()) {
            state.append("\n지금 화면에 있는 주제:\n")
            for ((id, name) in topics) state.append("- $id: $name\n")
        }
        val q = JSONObject()
        q.put(
            "effort",
            Jev.choice(
                "이 요청을 제대로 처리하는 데 필요한 생각의 깊이",
                listOf(
                    "low" to "인사, 간단한 질문, 화면의 작은 수정",
                    "medium" to "비교나 계획처럼 따져 볼 것이 있는 요청, 도구를 몇 번 쓰는 일",
                    "high" to "복잡한 분석, 여러 단계의 긴 작업, 코드나 문서를 새로 만드는 일",
                ),
            ),
        )
        q.put(
            "kind",
            Jev.choice(
                "이 요청의 종류",
                listOf(
                    "대화" to "아는 것으로 답할 수 있는 질문이나 이야기",
                    "화면 수정" to "이미 화면에 있는 카드를 고치거나 지우거나 정리해 달라는 요청",
                    "작업" to "파일, 명령 실행, 웹 검색 등 컴퓨터에서 실제로 무언가를 해야 하는 요청",
                    "기억 찾기" to "예전에 말한 것이나 예전에 한 일을 떠올려야 하는 요청",
                ),
            ),
        )
        if (topics.isNotEmpty()) q.put("topic", Jev.choice("이 말이 속하는 주제", topics + ("new" to "위 주제 어디에도 속하지 않는 새로운 이야기")))
        val started = System.nanoTime()
        val a = Jev.decide(key, state.toString(), q, url, limitMs) { log("실패: $it") } ?: return null
        val ms = (System.nanoTime() - started) / 1_000_000
        val pre = pre(a, english)
        fun raw(name: String) = a[name]?.let { "${it.choice.orEmpty()}(${"%.2f".format(Locale.ROOT, it.confidence)})" }.orEmpty()
        log("앞 ${ms}ms  입력=\"${input.take(40)}\"  깊이=${raw("effort")}  종류=${raw("kind")}  주제=${raw("topic")}  → 깊이=${pre.effort} 종류=${pre.kind} 주제=${pre.topic}")
        return pre
    }

    /** 응답에서 뒤 판단의 결과를 고른다 (decide.rs:167-178). */
    fun post(a: Map<String, JevAnswer>, cards: List<CardRef>): Post {
        val unclear = mutableListOf<Pair<String, Double>>()
        val importance = LinkedHashMap<String, Double>()
        for (c in cards.take(MAX_CARDS)) {
            a["clear_${c.id}"]?.value?.let { p -> if (p < CLEAR_BELOW) unclear.add(c.id to p) }
            // 점수는 단계의 번호(0, 1, 2) 위에서의 위치로 온다. 0~1 로 맞춘다.
            a["imp_${c.id}"]?.value?.let { s -> importance[c.id] = (s / 2.0).coerceIn(0.0, 1.0) }
        }
        return Post(unclear, importance)
    }

    /**
     * 턴 뒤의 판단 (decide.rs:136). `board` 는 화면 전체(수정 언어), `cards` 는 이번 턴에 AI 가 건드린 카드.
     * 키가 없거나 카드가 없으면 부르지 않고 null. 입출력 스레드에서 부른다.
     */
    fun after(
        key: String,
        board: String,
        cards: List<CardRef>,
        url: String = Jev.URL,
        limitMs: Long = Jev.LIMIT_MS,
        log: (String) -> Unit = {},
    ): Post? {
        if (key.isBlank() || cards.isEmpty()) return null
        val state = "한 화면에 여러 주제의 카드가 놓여 있다. 사용자는 대화 과정을 보지 않고 이 화면만 본다. 아래는 화면 전체를 명령 형식으로 적은 것이다.\n\n$board"
        val q = JSONObject()
        for (c in cards.take(MAX_CARDS)) {
            q.put("clear_${c.id}", Jev.noul("'${c.topic}' 주제의 카드 '${c.title}'(${c.id})은 주제 이름과 카드 내용만 보고도 무엇에 관한 것인지 분명하다"))
            q.put(
                "imp_${c.id}",
                Jev.score(
                    "'${c.topic}' 주제의 카드 '${c.title}'(${c.id})이 지금 사용자에게 중요한 정도",
                    listOf("참고용이라 안 봐도 된다", "알아 두면 좋다", "지금 꼭 봐야 하거나 사용자의 결정이 필요하다"),
                ),
            )
        }
        val started = System.nanoTime()
        val a = Jev.decide(key, state, q, url, limitMs) { log("실패: $it") } ?: return null
        val ms = (System.nanoTime() - started) / 1_000_000
        val post = post(a, cards)
        val imp = post.importance.entries.sortedBy { it.key }.joinToString(" ") { "${it.key}=${"%.2f".format(Locale.ROOT, it.value)}" }
        log("뒤 ${ms}ms  카드 ${cards.size}장  불분명=${post.unclear.map { it.first to "%.2f".format(Locale.ROOT, it.second) }}  중요도 $imp")
        return post
    }

    /** 다음 턴 글에 붙일 `<review>` 블록 (main.rs:1429, 1966-1970). 뚜렷하지 않은 카드가 없으면 null. */
    fun reviewBlock(unclear: List<Pair<String, Double>>): String? {
        if (unclear.isEmpty()) return null
        val lines = unclear.joinToString("\n") { (id, p) -> "card $id: hard to tell what it is about from the screen alone (probability it is clear: ${"%.2f".format(Locale.ROOT, p)})" }
        return "<review>\n$lines\n</review>\n"
    }

    /** 턴 글 끝에 붙일 `<already_said>` 블록 (llm.rs:170-172): 이미 한 말을 알려 같은 인사를 되풀이하지 않게 한다. */
    fun alreadySaid(ack: String): String = "\n<already_said>$ack</already_said>"

    /** 카드 순서의 3단계 (ui.rs:410): 점수가 없으면 0.5. 작은 차이로 자리가 바뀌지 않게 세 단계로만 나눈다. 클수록 먼저. */
    fun level(importance: Double?): Int = ((importance ?: 0.5) * 2.99).toInt()
}
