// APERIT Pro 실시간 대화의 흐름 (spec aperit-pro-voice-realtime-v1 §3.2): 듣기 소켓을 열어 두고 마이크 소리를 계속 보낸다,
// partial → 상태 줄·끼어들기·일하는 중 조용히, final → 오늘의 규칙(heardText), 45초 commit, 끊기면 다시 잇기 → 안 되면 예전 방식.
// 뷰모델이 하나 가지고, 뷰모델의 스레드에서만 부른다 ([frame] 만 마이크 스레드에서). 안드로이드 API 를 쓰지 않는다 (JVM 에서 시험한다).
package com.fidenit.kite.voice

class LiveTalk(private val listenOpener: LiveListen.Opener, private val host: Host, private val now: () -> Long = System::currentTimeMillis) {
    /** 뷰모델 쪽 */
    interface Host {
        val english: Boolean
        val busy: Boolean
        val speaking: Boolean

        /** 지금 읽는 문장 (없으면 null) */
        val reading: String?

        /** 이번 답에서 읽은 문장들 (에코 거르기는 마지막 셋) */
        val spoken: List<String>

        /** 소켓 스레드에서 온 것을 뷰모델의 스레드로 옮긴다 */
        fun post(block: () -> Unit)

        /** 상태 줄에 보일 들은 말의 끝 (null = 아무것도 안 들림) */
        fun heard(tail: String?)

        /** 한 말이 끝났다: 오늘의 규칙대로 (부르는 말, 잠깐·그만, 에코, 보내기) */
        fun final(text: String)

        /** 일하는 중에 사용자가 말했다: 남은 말은 소리 내지 않는다 */
        fun hush()

        /** 말하는 중에 사용자가 말로 끼어들었다: 읽기를 멈춘다 */
        fun barge()

        /** 실시간 듣기를 이번 대화에서 그만두고 예전 방식으로. [notice] 면 D9 (대화에 한 번). */
        fun fallback(why: String, notice: Boolean)

        /** 받아쓰기 한도: D4 + 말로 대화하기를 끈다 */
        fun limit()

        /** 게이트웨이가 이 기기를 거절했다 (unauthorized / pending / revoked): 보통의 상태 확인 */
        fun device(word: String)

        fun log(line: String)
    }

    /** 실시간으로 듣는 중 (켜졌고 예전 방식으로 돌아가지 않았다) */
    var listening = false
        private set

    /** 이번 대화에서 예전 방식으로 돌아갔다 */
    var fallen = false
        private set

    @Volatile
    private var sock: LiveListen? = null
    private var gen = 0
    private var ready = false
    private var openedAt = 0L
    private var retryAt: Long? = null
    private val retry = LiveRules.Retry()
    private var badOnce = false
    private val clock = LiveRules.CommitClock()
    private var lastPartialAt: Long? = null

    fun start() {
        if (listening || fallen) return
        listening = true
        open()
    }

    private fun open() {
        val g = ++gen
        ready = false
        openedAt = now()
        val ev = object : LiveEvents {
            override fun opened(ms: Long) = host.post { if (g == gen) host.log("live listen open ms=$ms") }
            override fun message(m: LiveWire.In) = host.post { if (g == gen) got(m) }
            override fun closed(c: LiveWire.Close) = host.post { if (g == gen) ended(c) }
        }
        val s = try {
            listenOpener.open(host.english, ev)
        } catch (_: Exception) {
            null
        }
        sock = s
        if (s == null) host.post { if (g == gen) ended(LiveWire.Close(null, -1, false)) } // 열지도 못했다: 끊긴 것과 같이
    }

    /** 마이크에서 읽은 조각 하나 (마이크 스레드). 소켓이 없으면 버린다. */
    fun frame(pcm: ShortArray, n: Int) {
        sock?.audio(pcm, n)
    }

    private fun got(m: LiveWire.In) {
        when (m) {
            is LiveWire.In.Ready -> {
                ready = true
                host.log("live listen ready ms=${now() - openedAt}")
            }
            is LiveWire.In.Partial -> partial(m.text)
            is LiveWire.In.Final -> final(m.text)
            else -> {}
        }
    }

    private fun partial(text: String) {
        val t = now()
        lastPartialAt = t
        clock.partial(text, t)
        val spoken = host.spoken
        if (text.isBlank() || LiveRules.echo(text, host.reading, spoken)) {
            host.heard(null)
            return
        }
        host.heard(LiveRules.tail(text))
        if (LiveRules.barge(text, host.speaking, host.reading, spoken)) host.barge()
        else if (LiveRules.hush(text, host.busy, host.speaking, spoken)) host.hush()
    }

    private fun final(text: String) {
        clock.final()
        host.heard(null)
        val after = lastPartialAt?.let { now() - it } ?: -1
        lastPartialAt = null
        host.log("live final chars=${text.length} after_partial_ms=$after")
        if (text.isNotBlank()) host.final(text)
    }

    /** 뷰모델의 tick 마다: 다시 열 때가 됐는지, 45초 commit 을 보낼 때인지 */
    fun tick() {
        if (!listening) return
        val t = now()
        retryAt?.let {
            if (t >= it) {
                retryAt = null
                open()
            }
        }
        val s = sock
        if (s != null && ready && clock.due(t)) {
            s.commit()
            host.log("live commit")
        }
    }

    private fun ended(c: LiveWire.Close) {
        val hadReady = ready
        sock = null
        ready = false
        host.heard(null)
        clock.final()
        lastPartialAt = null
        host.log("live listen close=${c.logWord} ms=${now() - openedAt}")
        if (!listening) return
        when (LiveRules.listenAct(c)) {
            LiveRules.Act.NOTHING -> {}
            LiveRules.Act.REOPEN -> open()
            LiveRules.Act.RETRY -> {
                val d = retry.failed(hadReady)
                if (d == null) fallback("listen_retry", true) else retryAt = now() + d
            }
            LiveRules.Act.RETRY_ONCE -> if (!badOnce) {
                badOnce = true
                open()
            } else fallback("bad_request", true)
            LiveRules.Act.DEVICE -> {
                host.device(c.word ?: "unauthorized")
                fallback(c.logWord, false)
            }
            LiveRules.Act.HTTP -> fallback(c.logWord, false)
            LiveRules.Act.LIMIT -> {
                stop()
                host.log("live listen limit")
                host.limit()
            }
        }
    }

    private fun fallback(why: String, notice: Boolean) {
        stop()
        fallen = true
        host.log("live fallback listen why=$why")
        host.fallback(why, notice)
    }

    /** 말로 대화하기를 끈다 (앱이 뒤로 가도): `end` 를 보내고 닫는다. 그 소켓에서 늦게 온 것은 버린다. */
    fun stop() {
        listening = false
        gen++
        retryAt = null
        ready = false
        val s = sock
        sock = null
        try {
            s?.end()
        } catch (_: Exception) {
        }
    }
}
