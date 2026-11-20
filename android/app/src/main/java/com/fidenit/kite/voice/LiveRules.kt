// APERIT Pro 실시간 음성의 판단 (spec aperit-pro-voice-realtime-v1 §3.2·§3.4): 말로 끼어들기, 자기 목소리(에코) 거르기,
// 45초 commit, 다시 잇기 횟수, 오류 낱말 → 할 일. 안드로이드 API 를 쓰지 않는다 (JVM 에서 시험한다).
package com.fidenit.kite.voice

object LiveRules {
    /** 한 말이 45초 넘게 끝나지 않으면 commit 을 보낸다 */
    const val COMMIT_MS = 45_000L

    /** 듣기 소켓이 뜻밖에 끊기면 다시 여는 간격. 이만큼 이어서 실패하면 이번 대화는 예전 방식으로. */
    val LISTEN_RETRY_MS = longArrayOf(500, 1000, 2000)

    /** 읽기 소켓을 이어서 이만큼 못 열면 이번 대화는 예전 방식으로 읽는다 */
    const val SPEAK_OPEN_FAILS = 2

    /** 상태 줄에 보일 들은 말의 끝 부분 길이 (가장 새 낱말이 보이게) */
    const val TAIL = 24

    private fun squash(text: String): String = text.filter { it.isLetterOrDigit() }.lowercase()

    /** 끼어든 말로 칠 만큼 긴가: 띄어쓰기·문장 부호를 빼고 한글 두 음절 이상, 또는 라틴 글자 세 개 이상. */
    fun longEnough(partial: String): Boolean {
        var hangul = 0
        var latin = 0
        for (c in partial) {
            if (c in '가'..'힣') hangul++ else if (c in 'a'..'z' || c in 'A'..'Z') latin++
        }
        return hangul >= 2 || latin >= 3
    }

    /** AI 가 읽은(읽는) 문장의 조각인가: 거의 같거나([Words.similar]) 그 문장 안에 그대로 들어 있다. */
    fun fragmentOf(sentence: String, partial: String): Boolean {
        val p = squash(partial)
        if (p.isEmpty()) return true
        return squash(sentence).contains(p) || Words.similar(sentence, partial)
    }

    /** 스피커에서 되돌아온 AI 자기 목소리인가: 읽는 중인 문장이나 마지막 세 문장과 거의 같거나 그 조각이다. */
    fun echo(partial: String, reading: String?, spoken: List<String>): Boolean =
        (listOfNotNull(reading) + spoken.takeLast(3)).any { fragmentOf(it, partial) }

    /** AI 가 말하는 중에 들은 말이 사용자가 끼어든 것인가 (D4: 소리 크기가 아니라 말로). */
    fun barge(partial: String, speaking: Boolean, reading: String?, spoken: List<String>): Boolean =
        speaking && longEnough(partial) && !echo(partial, reading, spoken)

    /** AI 가 일하는 중(말하지는 않는 중)에 말이 들렸다: 남은 말은 소리 내지 않는다 (오늘의 소리 크기 규칙과 같은 뜻). */
    fun hush(partial: String, busy: Boolean, speaking: Boolean, spoken: List<String>): Boolean =
        busy && !speaking && partial.isNotBlank() && !echo(partial, null, spoken)

    /** 상태 줄: 들은 말의 끝 부분 한 줄 (길면 앞을 "…" 로) */
    fun tail(partial: String, n: Int = TAIL): String {
        val t = partial.replace('\n', ' ').trim()
        return if (t.length <= n) t else "…" + t.takeLast(n).trimStart()
    }

    /** 한 말이 45초 넘게 끝나지 않는지 본다. partial 이 처음 보인 때부터 재고, final 이 오면 다시. */
    class CommitClock(private val maxMs: Long = COMMIT_MS) {
        private var since: Long? = null

        fun partial(text: String, now: Long) {
            if (since == null && text.isNotBlank()) since = now
        }

        fun final() {
            since = null
        }

        /** commit 을 보낼 때인가 (보낼 때마다 다시 잰다: final 이 끝내 안 오면 45초 뒤 또) */
        fun due(now: Long): Boolean {
            val s = since ?: return false
            if (now - s < maxMs) return false
            since = now
            return true
        }
    }

    /**
     * 듣기 소켓 다시 열기: 끊길 때마다 [failed] 를 부른다 (그 소켓이 `ready` 까지 갔는지와 함께).
     * `ready` 까지 갔던 소켓이 끊기면 처음부터 0.5초 · 1초 · 2초 뒤에 다시 열고, `ready` 없이 세 번 이어 실패하면 null (예전 방식으로).
     */
    class Retry(private val delays: LongArray = LISTEN_RETRY_MS) {
        var fails = 0
            private set

        fun failed(hadReady: Boolean): Long? {
            if (hadReady) fails = 0
            if (fails >= delays.size) return null
            return delays[fails++]
        }
    }

    /** 읽기 소켓 열기: 이어서 [limit] 번 못 열면 참 (예전 방식으로). 열리면 다시 0. */
    class Opens(private val limit: Int = SPEAK_OPEN_FAILS) {
        var fails = 0
            private set

        fun opened() {
            fails = 0
        }

        fun failed(): Boolean = ++fails >= limit
    }

    /** 듣기 소켓이 끝났을 때 할 일 (§1 표) */
    enum class Act {
        /** 폰이 닫았거나 다른 소켓이 넘겨받았다: 아무것도 안 함 */
        NOTHING,

        /** 다시 열기 규칙 (§3.4) */
        RETRY,

        /** 조용히 바로 다시 연다 (idle / session_max, 횟수에 넣지 않음) */
        REOPEN,

        /** 한 번만 다시 열고, 또 그러면 예전 방식 */
        RETRY_ONCE,

        /** 기기 상태를 다시 묻고 예전 방식으로 (그 길이 제 줄을 띄운다) */
        DEVICE,

        /** 예전 방식으로 (그 길이 D8·D1/D3 을 띄운다) */
        HTTP,

        /** D4 한 줄 + 말로 대화하기를 끈다 (오늘과 같이) */
        LIMIT,
    }

    fun listenAct(c: LiveWire.Close): Act {
        if (c.byUs) return Act.NOTHING
        return when (c.fault) {
            LiveWire.Fault.REPLACED -> Act.NOTHING
            LiveWire.Fault.IDLE -> Act.REOPEN
            LiveWire.Fault.BAD -> Act.RETRY_ONCE
            LiveWire.Fault.DEVICE -> Act.DEVICE
            LiveWire.Fault.OFF, LiveWire.Fault.KEY -> Act.HTTP
            LiveWire.Fault.LIMIT -> Act.LIMIT
            LiveWire.Fault.UPSTREAM, LiveWire.Fault.BUSY, LiveWire.Fault.NETWORK, LiveWire.Fault.NONE -> Act.RETRY
        }
    }
}
