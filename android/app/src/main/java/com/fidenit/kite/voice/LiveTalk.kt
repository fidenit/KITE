// APERIT Pro 실시간 대화의 흐름 (spec aperit-pro-voice-realtime-v1 §3.2): 듣기 소켓을 열어 두고 마이크 소리를 계속 보낸다,
// partial → 상태 줄·끼어들기·일하는 중 조용히, final → 오늘의 규칙(heardText), 45초 commit, 끊기면 다시 잇기 → 안 되면 예전 방식.
// 뷰모델이 하나 가지고, 뷰모델의 스레드에서만 부른다 ([frame] 만 마이크 스레드에서). 안드로이드 API 를 쓰지 않는다 (JVM 에서 시험한다).
package com.fidenit.kite.voice

/** [threshold] = 사용자의 마이크 민감도 (소리 크기, 오늘의 `micThreshold`). */
class LiveTalk(
    private val listenOpener: LiveListen.Opener,
    private val host: Host,
    private val threshold: Float = 0.02f,
    private val now: () -> Long = System::currentTimeMillis,
) {
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

    // 마이크 조각에서 잰 것 (마이크 스레드가 쓰고 뷰모델 스레드가 읽는다; 시간은 받은 소리의 길이로 잰다)
    /** 지난 final 뒤로 문턱보다 큰 소리가 한 번이라도 있었다 (D10) */
    @Volatile
    private var loudSinceFinal = false

    /** 마지막으로 큰 소리가 난 뒤 받은 소리 (샘플 수). 아직 없으면 아주 큰 값. */
    @Volatile
    private var samplesSinceLoud = Long.MAX_VALUE / 2

    // 지금 말(utterance)의 partial
    private var partials = 0
    private var prevLen = 0
    private var tp: Double? = null
    private var hasText = false
    private var committed = false
    private var vadS = LiveRules.VAD_S
    private var earlyAt: Long? = null

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
        if (LiveRules.level(pcm, n) > threshold) {
            loudSinceFinal = true
            samplesSinceLoud = 0
        } else {
            samplesSinceLoud += n
        }
        sock?.audio(pcm, n)
    }

    private fun got(m: LiveWire.In) {
        when (m) {
            is LiveWire.In.Ready -> {
                ready = true
                vadS = m.vadS ?: LiveRules.VAD_S
                host.log("live listen ready ms=${now() - openedAt} vad_s=$vadS")
            }
            is LiveWire.In.Partial -> partial(m.text, m.tp)
            is LiveWire.In.Final -> final(m.text)
            else -> {}
        }
    }

    private fun partial(text: String, p: Double?) {
        val t = now()
        lastPartialAt = t
        clock.partial(text, t)
        val blank = text.isBlank()
        val held = !blank && partials >= 1 && text.length >= prevLen // 같은 말의 둘째 partial 부터, 글이 줄지 않았다
        if (!blank) {
            // 앞서 일찍 끝낸 말 바로 뒤에 새 말이 이어졌다: 일찍 끝낸 것이 말을 잘랐을 수 있다 (폰에서 맞추려고 남긴다)
            val e = earlyAt
            if (partials == 0 && e != null) {
                if (t - e <= LiveRules.MAYBE_CUT_MS) host.log("live commit maybe-cut gap_ms=${t - e}")
                earlyAt = null
            }
            partials++
            prevLen = text.length
            hasText = true
        }
        tp = p
        val spoken = host.spoken
        if (blank || LiveRules.echo(text, host.reading, spoken)) {
            host.heard(null)
            return
        }
        host.heard(LiveRules.tail(text))
        val sound = samplesSinceLoud <= LiveRules.BARGE_SOUND_SAMPLES
        if (held && sound && LiveRules.barge(text, host.speaking, host.reading, spoken)) host.barge()
        else if (LiveRules.hush(text, host.busy, host.speaking, spoken)) host.hush()
    }

    /** 새 말을 기다린다 (final 이 왔거나 소켓이 바뀌었다) */
    private fun nextUtterance() {
        partials = 0
        prevLen = 0
        tp = null
        hasText = false
        committed = false
    }

    private fun final(text: String) {
        clock.final()
        nextUtterance()
        host.heard(null)
        val after = lastPartialAt?.let { now() - it } ?: -1
        lastPartialAt = null
        val loud = loudSinceFinal
        loudSinceFinal = false
        host.log("live final chars=${text.length} after_partial_ms=$after")
        if (text.isBlank()) return
        // D10: 마이크가 그사이 문턱을 넘는 소리를 한 번도 듣지 못했다 → 조용한 데서 생긴 헛것: 보내지 않는다
        if (!loud) {
            host.log("live final dropped quiet")
            return
        }
        host.final(text)
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
            committed = true
            host.log("live commit")
            return
        }
        // D11: 말이 끝났을 확률(tp)이 높을수록 짧게 조용하면 일찍 끝낸다 (vad_s × (1 − tp)), 말하는 중이 아닐 때, 말마다 한 번
        val p = tp ?: return
        if (s == null || !ready || committed || !hasText || host.speaking || !loudSinceFinal) return
        val quiet = samplesSinceLoud
        if (quiet <= 0 || quiet >= Long.MAX_VALUE / 4) return
        if (quiet * 1000 >= LiveRules.earlyQuietMs(vadS, p) * MIC_RATE) {
            s.commit()
            committed = true
            earlyAt = t
            host.log("live commit early tp=${"%.2f".format(java.util.Locale.ROOT, p)} quiet_ms=${quiet * 1000 / MIC_RATE}")
        }
    }

    private fun ended(c: LiveWire.Close) {
        val hadReady = ready
        sock = null
        ready = false
        host.heard(null)
        clock.final()
        nextUtterance()
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
