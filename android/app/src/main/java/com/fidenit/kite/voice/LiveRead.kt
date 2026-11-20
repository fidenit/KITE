// APERIT Pro 실시간 읽기 (spec aperit-pro-voice-realtime-v1 §3.2 Reading, §3.4, v1.2): 읽기 소켓 하나를 대화 내내 열어 두고,
// 문장이 생기는 대로 `say` 로 미리 보낸다. 게이트웨이는 문장마다 start → 소리 → done 을 차례로 보낸다.
// 소켓 스레드는 받은 것을 재생 줄([LivePlay])에 넣기만 한다 (스피커를 만지지 않아 ping 이 막히지 않는다).
// 재생 스레드가 줄을 따라 틀다가 start/done/skipped/끊김 표시에 닿으면 뷰모델의 스레드로 알린다 (카드 줄은 그때 나간다).
// 뷰모델의 스레드에서 부른다. 안드로이드 API 를 쓰지 않는다 (JVM 에서 시험한다).
package com.fidenit.kite.voice

class LiveRead(
    private val opener: LiveSpeak.Opener,
    private val host: Host,
    out: LivePlay.Out,
    /** 재생 스레드를 띄운다 (시험은 끄고 `play.pump()` 로) */
    thread: Boolean = true,
    private val now: () -> Long = System::currentTimeMillis,
) {
    interface Host {
        /** 소켓·재생 스레드에서 온 것을 뷰모델의 스레드로 */
        fun post(block: () -> Unit)

        /** 문장 [id] 의 소리가 스피커에 닿기 시작한다: 줄의 맨 앞 문장이어야 한다 */
        fun started(id: Int)

        /** 문장 [id] 의 소리를 다 스피커에 넣었다 */
        fun done(id: Int)

        /**
         * 이번 답의 남은 문장은 예전 방식으로 읽는다: 문장 하나를 못 읽었거나, 소켓이 답 중간에 끊겼거나, 멈춘 채 10초가 지났다.
         * [brokeOff] = 읽던 문장이 중간에 끊겼다 (다시 읽지 않는다, D7).
         */
        fun rest(why: String, brokeOff: Boolean)

        /** 이번 대화는 예전 방식으로 읽는다. [notice] 면 D9 (대화에 한 번). */
        fun fallback(why: String, notice: Boolean)

        /** 게이트웨이가 이 기기를 거절했다 */
        fun device(word: String)

        fun log(line: String)
    }

    /** 이번 대화에서 실시간으로 읽을 수 있다 */
    val usable: Boolean get() = !fallen

    var fallen = false
        private set

    @Volatile
    private var sock: LiveSpeak? = null
    private var gen = 0
    private var opened = false
    private var openedAt = 0L
    private val opens = LiveRules.Opens()
    private var badOnce = false

    /** 이 소켓에서 보낸 마지막 문장 번호 (소켓마다 1부터) */
    private var lastId = 0

    /** 재생 스레드가 done 에 닿은 마지막 번호, start 에 닿은 마지막 번호 */
    private var lastDone = 0
    private var lastStarted = 0

    /** 이 번호까지는 버렸다 (stop) */
    private var cutUpTo = 0

    /** 지금 소리가 오는 문장 (소켓 스레드만 쓴다) */
    @Volatile
    private var currentId = 0

    // 답 하나의 숫자 (pro.log)
    private var prepareAt: Long? = null
    private var firstSayAt: Long? = null

    @Volatile
    private var firstAudioPending = false
    private var gaps = 0
    private var gapMax = 0L
    private var dryAt: Long? = null

    // 감시: 움직임이 없던 때부터
    private var lastProgress = -1L
    private var stillSince = 0L

    private val playEvents = object : LivePlay.Events {
        override fun start(gen: Int, id: Int) = host.post { if (gen == play.gen) playStarted(id) }
        override fun done(gen: Int, id: Int) = host.post { if (gen == play.gen) playDone(id) }
        override fun skipped(gen: Int, id: Int, error: String) = host.post { if (gen == play.gen) playSkipped(id, error) }
        override fun broke(gen: Int, why: String) = host.post { if (gen == play.gen) failRest(why) }
        override fun overflow(gen: Int) = host.post { if (gen == play.gen) failNow("overflow") }
    }

    /** 재생 줄 (시험이 [LivePlay.pump] 를 부른다) */
    val play = LivePlay(out, playEvents, thread = thread)

    /** 대화를 켤 때: 소켓을 미리 연다 */
    fun open() {
        if (fallen || sock != null) return
        val g = ++gen
        opened = false
        openedAt = now()
        lastId = 0
        lastDone = 0
        lastStarted = 0
        cutUpTo = 0
        currentId = 0
        play.renew()
        val ev = object : LiveEvents {
            override fun opened(ms: Long) = host.post { if (g == gen) wasOpened(ms) }
            override fun message(m: LiveWire.In) {
                if (g != gen) return
                // 소켓 스레드: 차례대로 재생 줄에 넣기만 한다
                when (m) {
                    is LiveWire.In.Start -> {
                        currentId = m.id
                        play.start(m.id)
                    }
                    is LiveWire.In.Done -> play.done(m.id)
                    is LiveWire.In.Skipped -> play.skipped(m.id, m.error)
                    is LiveWire.In.Stopped -> host.post { if (g == gen) host.log("live speak stopped upto=${m.upto}") }
                    else -> {}
                }
            }

            override fun audio(pcm: ShortArray) {
                if (g != gen) return
                val id = currentId
                if (id == 0) return
                if (firstAudioPending) {
                    firstAudioPending = false
                    val t = now()
                    host.post { if (g == gen) firstAudio(t) }
                }
                play.audio(id, pcm)
            }

            override fun closed(c: LiveWire.Close) = host.post { if (g == gen) ended(c) }
        }
        val s = try {
            opener.open(ev)
        } catch (_: Exception) {
            null
        }
        sock = s
        if (s == null) host.post { if (g == gen) ended(LiveWire.Close(null, -1, false)) }
    }

    private fun wasOpened(ms: Long) {
        opened = true
        opens.opened()
        host.log("live speak open ms=$ms")
    }

    /** 턴이 시작된다 (말로 대화하는 중): 게이트웨이가 미리 이어 둔다. 답 하나의 숫자를 새로 잰다. */
    fun prepare() {
        if (fallen) return
        open()
        prepareAt = now()
        firstSayAt = null
        firstAudioPending = true
        gaps = 0
        gapMax = 0
        dryAt = null
        sock?.prepare()
    }

    /** 문장 하나를 보낸다. 번호를 돌려준다 (실시간으로 못 읽으면 0). */
    fun say(text: String): Int {
        if (fallen) return 0
        open()
        val s = sock ?: return 0
        val id = ++lastId
        if (firstSayAt == null) {
            firstSayAt = now()
            if (prepareAt == null) firstAudioPending = true
        }
        s.say(id, text)
        return id
    }

    /** 보낸 문장 가운데 아직 재생 스레드가 done 에 닿지 않은 것이 있다 */
    val outstanding: Boolean get() = lastId > maxOf(lastDone, cutUpTo)

    /** 지금 읽던 문장이 끝나지 않았다 (start 에는 닿았고 done 에는 아직) */
    private val midSentence: Boolean get() = lastStarted > maxOf(lastDone, cutUpTo)

    /** 줄에도 스피커에도 남은 소리가 없다 */
    val idle: Boolean get() = play.idle

    /**
     * 끼어들기·멈춤: 세대와 버릴 번호를 먼저 정하고, 줄을 비우고, [flush] 면 스피커도 비운다. 게이트웨이에도 남은 것을 버리게 한다.
     * [flush] 가 거짓이면 이미 스피커에 들어간 소리는 마저 나간다 (남은 문장을 예전 방식으로 이어 읽을 때).
     */
    fun stop(flush: Boolean = true) {
        val send = outstanding
        val upTo = lastId
        if (upTo > cutUpTo) cutUpTo = upTo
        play.cut(upTo, flush)
        if (send) sock?.stop()
    }

    /** 말로 대화하기를 끈다: `end` 를 보내고 닫는다. 늦게 온 것은 버린다. */
    fun end() {
        stop(flush = true)
        gen++
        val s = sock
        sock = null
        try {
            s?.end()
        } catch (_: Exception) {
        }
    }

    fun release() {
        end()
        play.release()
    }

    /**
     * 뷰모델의 tick 마다: 다음 문장을 기다리는데 스피커가 비면 끊김 하나로 센다 ([more] = 아직 소리가 올 문장이 있다).
     * [active] (말하는 중이거나 기다리는 문장이 있다) 인데 받은 소리도 튼 소리도 10초 동안 그대로면 감시가 끝낸다.
     */
    fun tick(more: Boolean, active: Boolean) {
        val t = now()
        val pending = play.pendingSecs() + play.queuedSecs()
        val d = dryAt
        if (more && pending <= 0f) {
            if (d == null) dryAt = t
        } else if (d != null) {
            dryAt = null
            if (pending > 0f) {
                gaps++
                gapMax = maxOf(gapMax, t - d)
            }
        }
        val p = play.progress()
        if (!active || p != lastProgress) {
            lastProgress = p
            stillSince = t
            return
        }
        if (t - stillSince >= WATCHDOG_MS) {
            stillSince = t
            host.log("live speak watchdog")
            failNow("watchdog")
        }
    }

    /** 답 하나를 다 읽었다: 끊김 숫자를 남긴다 */
    fun replyDone() {
        host.log("live speak gaps=$gaps gap_ms_max=$gapMax")
        gaps = 0
        gapMax = 0
        dryAt = null
        prepareAt = null
        firstSayAt = null
    }

    private fun firstAudio(t: Long) {
        val p = prepareAt?.let { t - it } ?: -1
        val s = firstSayAt?.let { t - it } ?: -1
        host.log("live speak first_audio_ms=$p say_to_audio_ms=$s")
    }

    private fun playStarted(id: Int) {
        if (id <= cutUpTo) return
        lastStarted = maxOf(lastStarted, id)
        host.started(id)
    }

    private fun playDone(id: Int) {
        lastDone = maxOf(lastDone, id)
        if (id > cutUpTo) host.done(id)
    }

    private fun playSkipped(id: Int, error: String) {
        if (id <= cutUpTo) return
        val after = id == lastStarted && id > lastDone
        host.log("live speak skipped error=$error after_start=$after")
        if (after) lastDone = id // 읽던 문장은 여기서 끝: 다시 읽지 않는다
        host.rest("skipped_$error", after)
    }

    /** 이미 받은 소리를 다 튼 뒤 (재생 줄의 끊김 표시): 남은 문장은 예전 방식으로 */
    private fun failRest(why: String) {
        val broke = midSentence
        if (broke) lastDone = lastStarted
        host.rest(why, broke)
    }

    /** 지금 바로 (넘침·감시): 줄과 스피커를 비우고 남은 문장은 예전 방식으로 */
    private fun failNow(why: String) {
        host.log("live speak fail why=$why")
        val broke = midSentence
        if (broke) lastDone = lastStarted
        stop(flush = true)
        host.rest(why, broke)
    }

    private fun ended(c: LiveWire.Close) {
        val wasOpen = opened
        val pending = outstanding
        sock = null
        opened = false
        host.log("live speak close=${c.logWord} ms=${now() - openedAt}")
        if (c.byUs) return
        var notice = false
        var drop = false
        when (c.fault) {
            LiveWire.Fault.REPLACED, LiveWire.Fault.IDLE, LiveWire.Fault.LIMIT -> {}
            LiveWire.Fault.OFF, LiveWire.Fault.KEY -> drop = true
            LiveWire.Fault.DEVICE -> {
                host.device(c.word ?: "unauthorized")
                drop = true
            }
            LiveWire.Fault.BAD -> if (badOnce) {
                drop = true
                notice = true
            } else badOnce = true
            else -> if (!wasOpen && opens.failed()) {
                drop = true
                notice = true
            }
        }
        if (drop) {
            fallen = true
            host.log("live fallback speak why=${c.logWord}")
        }
        // 답 중간에 끊겼다: 이미 받은 소리를 다 튼 뒤 그 답의 남은 문장은 예전 방식으로 (재생 줄의 끊김 표시)
        if (pending) {
            if (drop) {
                // 이번 대화는 더 쓰지 않는다: 줄에 남은 것을 기다리지 않고 바로 (스피커에 이미 들어간 소리는 마저 나간다)
                val broke = midSentence
                if (broke) lastDone = lastStarted
                stop(flush = false)
                host.rest("closed_${c.logWord}", broke)
            } else if (!play.broke("closed_${c.logWord}")) failNow("closed_${c.logWord}")
        }
        if (drop) host.fallback(c.logWord, notice)
    }

    companion object {
        /** 말하는 중에 받은 소리도 튼 소리도 이만큼 그대로면 끝낸다 */
        const val WATCHDOG_MS = 10_000L
    }
}
