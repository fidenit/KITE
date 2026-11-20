// APERIT Pro 실시간 읽기 (spec aperit-pro-voice-realtime-v1 §3.2 Reading, §3.4): 읽기 소켓 하나를 대화 내내 열어 두고,
// 문장이 생기는 대로 `say` 로 미리 보낸다. 게이트웨이는 문장마다 start → 소리 → done 을 차례로 보낸다.
// 소리는 소켓 스레드에서 바로 스피커로 (밀리면 소켓이 기다린다), start/done/skipped 와 끊김은 뷰모델의 스레드로 옮겨 알린다.
// 뷰모델의 스레드에서 부른다 (소리만 소켓 스레드). 안드로이드 API 를 쓰지 않는다 (JVM 에서 시험한다).
package com.fidenit.kite.voice

class LiveRead(private val opener: LiveSpeak.Opener, private val host: Host, private val now: () -> Long = System::currentTimeMillis) {
    interface Host {
        /** 소켓 스레드에서 온 것을 뷰모델의 스레드로 */
        fun post(block: () -> Unit)

        /** 받은 소리를 틀 곳 (소켓 스레드에서 부른다, 자리가 날 때까지 기다려도 된다) */
        val sink: Eleven.Sink?

        /** 문장 [id] 의 소리가 시작된다: 줄의 맨 앞 문장이어야 한다 */
        fun started(id: Int)

        /** 문장 [id] 의 소리를 다 받았다 */
        fun done(id: Int)

        /** 이번 답의 남은 문장 (맨 앞부터) 은 예전 방식으로 읽는다: 문장 하나를 못 읽었거나 소켓이 답 중간에 끊겼다 */
        fun rest(why: String)

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

    /** 마지막으로 done 이 온 번호 */
    private var lastDone = 0

    /** 이 번호까지는 버린다 (stop 을 보냈다) — 소켓 스레드도 읽는다 */
    @Volatile
    private var dropUpTo = 0

    /** 지금 소리가 오는 문장 (소켓 스레드가 start 에서 정한다) */
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

    /** 대화를 켤 때: 소켓을 미리 연다 */
    fun open() {
        if (fallen || sock != null) return
        val g = ++gen
        opened = false
        openedAt = now()
        lastId = 0
        lastDone = 0
        dropUpTo = 0
        currentId = 0
        val ev = object : LiveEvents {
            override fun opened(ms: Long) = host.post { if (g == gen) wasOpened(ms) }
            override fun message(m: LiveWire.In) {
                // 소리가 어느 문장 것인지는 소켓 스레드에서 바로 안다 (start 다음에 오는 소리)
                if (g == gen && m is LiveWire.In.Start) currentId = m.id
                host.post { if (g == gen) got(m) }
            }

            override fun audio(pcm: ShortArray) {
                if (g != gen) return
                val id = currentId
                if (id == 0 || id <= dropUpTo) return
                if (firstAudioPending) {
                    firstAudioPending = false
                    val t = now()
                    host.post { if (g == gen) firstAudio(t) }
                }
                try {
                    host.sink?.push(pcm, pcm.size)
                } catch (_: Exception) {
                }
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

    /** 보낸 문장 가운데 아직 done 이 오지 않은 것이 있다 */
    val outstanding: Boolean get() = lastId > maxOf(lastDone, dropUpTo)

    /** 아직 틀지 않은 것을 모두 버린다 (끼어들기, 멈춤, 남은 문장을 예전 방식으로). 그 번호까지의 소리는 받아도 버린다. */
    fun stop() {
        if (lastId <= dropUpTo) return
        val send = outstanding // 다 받은 문장뿐이면 게이트웨이에 버릴 것이 없다 (스피커는 뷰모델이 멈춘다)
        dropUpTo = lastId
        if (send) sock?.stop()
    }

    /** 말로 대화하기를 끈다: `end` 를 보내고 닫는다. 늦게 온 것은 버린다. */
    fun end() {
        gen++
        val s = sock
        sock = null
        dropUpTo = lastId
        try {
            s?.end()
        } catch (_: Exception) {
        }
    }

    /**
     * 뷰모델의 tick 마다 (말하는 중): 다음 문장을 기다리는데 스피커가 비었으면 끊김 하나로 센다.
     * [more] = 아직 소리가 올 문장이 있다, [pendingSecs] = 스피커에 남은 소리.
     */
    fun play(more: Boolean, pendingSecs: Float) {
        val t = now()
        val d = dryAt
        if (more && pendingSecs <= 0f) {
            if (d == null) dryAt = t
        } else if (d != null) {
            dryAt = null
            if (pendingSecs > 0f) {
                gaps++
                gapMax = maxOf(gapMax, t - d)
            }
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

    private fun got(m: LiveWire.In) {
        when (m) {
            is LiveWire.In.Start -> if (m.id > dropUpTo) host.started(m.id)
            is LiveWire.In.Done -> {
                lastDone = maxOf(lastDone, m.id)
                if (m.id > dropUpTo) host.done(m.id)
            }
            is LiveWire.In.Skipped -> if (m.id > dropUpTo) {
                host.log("live speak skipped error=${m.error}")
                host.rest("skipped_${m.error}")
            }
            is LiveWire.In.Stopped -> host.log("live speak stopped upto=${m.upto}")
            else -> {}
        }
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
        // 답 중간에 끊겼다: 그 답의 남은 문장은 예전 방식으로 (번호는 새 소켓에서 다시 1부터)
        if (pending) host.rest("closed_${c.logWord}")
        if (drop) host.fallback(c.logWord, notice)
    }
}
