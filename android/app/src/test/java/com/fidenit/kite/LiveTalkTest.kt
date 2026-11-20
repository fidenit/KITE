package com.fidenit.kite

import com.fidenit.kite.voice.LiveEvents
import com.fidenit.kite.voice.LiveListen
import com.fidenit.kite.voice.LiveTalk
import com.fidenit.kite.voice.LiveWire
import com.fidenit.kite.voice.LiveWire.In
import com.fidenit.kite.voice.Words
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The realtime listen flow (spec aperit-pro-voice-realtime-v1 §3.2, §3.4) with a fake socket and a fake view model. JVM only. */
class LiveTalkTest {
    private var clock = 1_000_000L

    /** One fake listen socket: what the phone sent, and a way to play the gateway. */
    private class Sock(val events: LiveEvents) : LiveListen {
        val audio = mutableListOf<Int>()
        var commits = 0
        var ended = false
        override fun audio(pcm: ShortArray, n: Int): Boolean {
            audio.add(n)
            return true
        }

        override fun commit() {
            commits++
        }

        override fun end() {
            ended = true
        }
    }

    private val socks = mutableListOf<Sock>()
    private var openFails = false
    private val opener = LiveListen.Opener { _, ev ->
        if (openFails) throw java.io.IOException("no route")
        Sock(ev).also { socks.add(it) }
    }

    /** The view model side: runs posted work only when the test says so (like the main thread). */
    private inner class Host : LiveTalk.Host {
        override var english = false
        override var busy = false
        override var speaking = false
        override var reading: String? = null
        override val spoken = mutableListOf<String>()
        var standby = false
        val queue = ArrayDeque<() -> Unit>()
        val tails = mutableListOf<String?>()
        val sent = mutableListOf<String>()
        val routed = mutableListOf<Words.Heard>()
        var hushed = 0
        var barged = 0
        val fallbacks = mutableListOf<Pair<String, Boolean>>()
        var limits = 0
        val devices = mutableListOf<String>()
        val logs = mutableListOf<String>()
        override fun post(block: () -> Unit) {
            queue.addLast(block)
        }

        fun run() {
            while (queue.isNotEmpty()) queue.removeFirst()()
        }

        override fun heard(tail: String?) {
            tails.add(tail)
        }

        // the view model's heardText: today's rules
        override fun final(text: String) {
            val r = Words.route(text, standby, spoken)
            routed.add(r)
            when (r) {
                Words.Heard.SEND -> sent.add(text)
                Words.Heard.WAIT -> standby = true
                else -> {}
            }
        }

        override fun hush() {
            hushed++
        }

        override fun barge() {
            barged++
        }

        override fun fallback(why: String, notice: Boolean) {
            fallbacks.add(why to notice)
        }

        override fun limit() {
            limits++
        }

        override fun device(word: String) {
            devices.add(word)
        }

        override fun log(line: String) {
            logs.add(line)
        }
    }

    private val host = Host()
    private val talk = LiveTalk(opener, host) { clock }

    private fun gw(m: In, s: Sock = socks.last()) {
        s.events.message(m)
        host.run()
    }

    private fun drop(word: String? = null, code: Int = -1, s: Sock = socks.last()) {
        s.events.closed(LiveWire.Close(word, code, false))
        host.run()
    }

    private fun after(ms: Long) {
        clock += ms
        talk.tick()
        host.run()
    }

    private val loudFrame = ShortArray(320) { 3000 }
    private val quietFrame = ShortArray(320)

    /** 20 ms of real sound from the mic (level 0.09 > threshold 0.02) */
    private fun loud(ms: Int = 20) = repeat(ms / 20) { talk.frame(loudFrame, 320) }

    /** 20 ms frames of silence, with the view model's tick between them */
    private fun quiet(ms: Int) = repeat(ms / 20) {
        talk.frame(quietFrame, 320)
        talk.tick()
        host.run()
    }

    /** The owner said something (real sound), then the gateway's final */
    private fun fin(text: String) {
        loud()
        gw(In.Final(text, "kor"))
    }

    private fun started(): Sock {
        talk.start()
        gw(In.Ready())
        return socks.last()
    }

    @Test
    fun partialsShowTheTailAndOneFinalIsSentOnce() {
        val s = started()
        assertTrue(talk.listening)
        talk.frame(ShortArray(320), 320)
        talk.frame(ShortArray(320), 320)
        assertEquals(listOf(320, 320), s.audio)
        gw(In.Partial("회의"))
        gw(In.Partial("회의 일정"))
        clock += 300
        fin("회의 일정 정리해 줘")
        assertEquals(listOf("회의", "회의 일정", null), host.tails)
        assertEquals(listOf("회의 일정 정리해 줘"), host.sent)
        assertTrue(host.logs.any { it == "live final chars=11 after_partial_ms=300" })
        gw(In.Final("", ""))
        assertEquals("an empty final is ignored", 1, host.routed.size)
        assertEquals(1, host.sent.size)
    }

    @Test
    fun standbyWakeWaitStopAndEchoGoThroughTodaysRules() {
        started()
        fin("잠깐만")
        assertTrue(host.standby)
        fin("이거 정리해 줘")
        assertEquals("waiting: not sent", emptyList<String>(), host.sent)
        fin("아페리트 이제 말해")
        assertEquals(listOf("아페리트 이제 말해"), host.sent)
        host.standby = false
        fin("그만")
        host.spoken.add("오늘 회의는 세 시에 시작합니다")
        fin("오늘 회의는 세 시에 시작합니다")
        assertEquals(listOf(Words.Heard.WAIT, Words.Heard.IGNORE, Words.Heard.SEND, Words.Heard.STOP, Words.Heard.IGNORE), host.routed)
        assertEquals(1, host.sent.size)
    }

    @Test
    fun anEchoPartialIsNotShownAndDoesNotHushOrBarge() {
        started()
        host.busy = true
        host.spoken.add("지금 찾아볼게요")
        gw(In.Partial("찾아볼게요"))
        assertEquals(listOf<String?>(null), host.tails)
        assertEquals(0, host.hushed)
        gw(In.Partial("아니 그거 말고"))
        assertEquals("busy + a non-echo partial -> hush", 1, host.hushed)
        assertEquals(0, host.barged)
    }

    @Test
    fun whileSpeakingANonEchoPartialThatHeldWithSoundBargesIn() {
        val s = started()
        host.speaking = true
        host.busy = true
        host.reading = "오늘 회의는 세 시에 시작합니다"
        loud()
        gw(In.Partial("오늘 회의는"))
        gw(In.Partial("오늘 회의는 세"))
        assertEquals("echo of the sentence being read, with sound", 0, host.barged)
        gw(In.Final("오늘 회의는 세", "kor"))
        loud()
        gw(In.Partial("회의는"))
        gw(In.Partial("회의는 세"))
        assertEquals("a fragment", 0, host.barged)
        gw(In.Final("회의는 세", "kor"))
        loud()
        gw(In.Partial("아니 그거"))
        assertEquals("first partial of the utterance: not yet", 0, host.barged)
        gw(In.Partial("아니 그거 말고"))
        assertEquals(1, host.barged)
        assertEquals("speaking: no hush", 0, host.hushed)
        assertEquals(0, s.commits)
    }

    @Test
    fun aSingleGhostPartialInSilenceDoesNotBargeIn() {
        started()
        host.speaking = true
        host.reading = "오늘 회의는 세 시에 시작합니다"
        quiet(1_000)
        gw(In.Partial("클로드."))
        assertEquals(0, host.barged)
        gw(In.Final("", ""))
        loud()
        quiet(600)
        gw(In.Partial("내 말 좀"))
        gw(In.Partial("내 말 좀 들어"))
        assertEquals("two partials but the last sound was 0.6 s ago", 0, host.barged)
        loud()
        gw(In.Partial("내 말 좀 들어 봐"))
        assertEquals(1, host.barged)
    }

    @Test
    fun aShorterSecondPartialDoesNotBargeIn() {
        started()
        host.speaking = true
        loud()
        gw(In.Partial("아니 그거 말고"))
        gw(In.Partial("아니"))
        assertEquals(0, host.barged)
    }

    @Test
    fun aFinalWithNoRealSoundSinceTheLastFinalIsDropped() {
        started()
        quiet(2_000)
        gw(In.Partial("네."))
        gw(In.Final("네.", "kor"))
        assertTrue(host.sent.isEmpty())
        assertTrue(host.logs.contains("live final dropped quiet"))
        fin("내일 일정 알려 줘")
        assertEquals(listOf("내일 일정 알려 줘"), host.sent)
        gw(In.Final("그 다음은", "kor"))
        assertEquals("sound is counted again from each final", 1, host.sent.size)
    }

    private fun earlyAfter(tp: Double?, vad: Double? = null): Pair<Sock, Int?> {
        talk.start()
        gw(In.Ready(vad))
        val s = socks.last()
        loud(200)
        gw(In.Partial("내일 일정 알려 줘", tp))
        var at: Int? = null
        for (k in 1..100) {
            quiet(20)
            if (s.commits > 0) {
                at = k * 20
                break
            }
        }
        return s to at
    }

    @Test
    fun earlyCommitWaitsVadTimesOneMinusTp() {
        assertEquals("tp 0.9 -> 0.15 s, floor 0.4 s (v1.2)", 400, earlyAfter(0.9).second)
        assertTrue(host.logs.contains("live commit early tp=0.90 quiet_ms=400"))
    }

    @Test
    fun earlyCommitTp05WaitsHalfTheBackstop() {
        assertEquals("tp 0.5 -> 0.75 s", 760, earlyAfter(0.5).second)
    }

    @Test
    fun earlyCommitUsesVadFromReady() {
        assertEquals("vad 1.0, tp 0.5 -> 0.5 s", 500, earlyAfter(0.5, 1.0).second)
    }

    @Test
    fun aMidSentencePauseWaitsAlmostTheWholeBackstopAndNoTpMeansNoEarlyCommit() {
        assertEquals("tp 0.02 -> 1.47 s", 1480, earlyAfter(0.02).second)
        val t2 = LiveTalk(opener, host) { clock }
        t2.start()
        gw(In.Ready(null))
        val s = socks.last()
        repeat(10) { t2.frame(loudFrame, 320) }
        gw(In.Partial("내일 일정", null))
        repeat(150) {
            t2.frame(quietFrame, 320)
            t2.tick()
        }
        assertEquals("no tp -> the upstream's own backstop", 0, s.commits)
    }

    @Test
    fun oneEarlyCommitPerUtteranceAndAMaybeCutIsLogged() {
        val (s, at) = earlyAfter(0.9)
        assertEquals(400, at)
        gw(In.Partial("내일 일정 알려 줘", 0.95))
        quiet(1_000)
        assertEquals("one per utterance", 1, s.commits)
        gw(In.Final("내일 일정 알려 줘", "kor"))
        clock += 400
        loud()
        gw(In.Partial("그리고", 0.1))
        assertTrue(host.logs.any { it == "live commit maybe-cut gap_ms=400" })
    }

    @Test
    fun noEarlyCommitWhileSpeakingOrWithoutSound() {
        talk.start()
        gw(In.Ready())
        val s = socks.last()
        quiet(200)
        gw(In.Partial("응.", 0.9))
        quiet(1_000)
        assertEquals("a ghost partial in silence", 0, s.commits)
        gw(In.Final("", ""))
        host.speaking = true
        loud(200)
        gw(In.Partial("내일 일정", 0.9))
        quiet(1_000)
        assertEquals("while the AI speaks", 0, s.commits)
    }

    @Test
    fun aCommitIsSentAfter45sOfOneUtteranceWithNoFinal() {
        val s = started()
        gw(In.Partial("아주 긴 말"))
        after(44_000)
        assertEquals(0, s.commits)
        after(1_000)
        assertEquals(1, s.commits)
        after(40)
        assertEquals(1, s.commits)
        fin("아주 긴 말 끝")
        after(60_000)
        assertEquals(1, s.commits)
    }

    @Test
    fun aDropReopensAfterHalfOneTwoSecondsThenFallsBackWithD9Once() {
        val first = started()
        drop()
        assertEquals(1, socks.size)
        after(499)
        assertEquals(1, socks.size)
        after(1)
        assertEquals("reopened after 0.5 s", 2, socks.size)
        assertTrue(first !== socks.last())
        drop()
        after(999)
        assertEquals(2, socks.size)
        after(1)
        assertEquals(3, socks.size)
        drop()
        after(2_000)
        assertEquals(4, socks.size)
        drop()
        assertEquals(listOf("listen_retry" to true), host.fallbacks)
        assertFalse(talk.listening)
        assertTrue(talk.fallen)
        after(10_000)
        assertEquals("no more reopening in this conversation", 4, socks.size)
        talk.start()
        assertEquals("start does nothing once fallen", 4, socks.size)
    }

    @Test
    fun aSocketThatGotReadyStartsTheCountOver() {
        started()
        repeat(5) {
            drop()
            after(500)
            gw(In.Ready())
        }
        assertEquals(6, socks.size)
        assertTrue(host.fallbacks.isEmpty())
    }

    @Test
    fun anOpenThatThrowsCountsAsAFailedAttempt() {
        openFails = true
        talk.start()
        host.run()
        after(500)
        after(1_000)
        after(2_000)
        assertEquals(listOf("listen_retry" to true), host.fallbacks)
        assertEquals(0, socks.size)
    }

    @Test
    fun sessionMaxReopensAtOnceAndIdleCountsLikeADrop() {
        started()
        drop("session_max", 4408)
        drop("session_max", 4408)
        assertEquals("session_max: at once, not counted", 3, socks.size)
        gw(In.Ready())
        drop("idle", 4408)
        assertEquals(3, socks.size)
        after(500)
        assertEquals("idle: after 0.5 s", 4, socks.size)
        drop("idle", 4408)
        after(1_000)
        drop("idle", 4408)
        after(2_000)
        drop("idle", 4408)
        assertEquals("3 idles in a row with nothing heard -> D9", listOf("listen_retry" to true), host.fallbacks)
    }

    @Test
    fun anIdleAfterSomethingWasHeardStartsTheCountOver() {
        started()
        repeat(5) {
            gw(In.Partial("말"))
            drop("idle", 4408)
            after(500)
        }
        assertTrue(host.fallbacks.isEmpty())
        assertEquals(6, socks.size)
    }

    @Test
    fun badRequestIsAllowedOnceMoreAfterTheSocketHeardSomething() {
        started()
        drop("bad_request", 4400)
        gw(In.Ready())
        gw(In.Partial("말"))
        drop("bad_request", 4400)
        assertTrue("reset after a working socket", host.fallbacks.isEmpty())
        drop("bad_request", 4400)
        assertEquals(listOf("bad_request" to true), host.fallbacks)
    }

    @Test
    fun minQuietFromReadyIsTheFloor() {
        talk.start()
        gw(In.Ready(1.5, 0.6))
        val s = socks.last()
        loud(200)
        gw(In.Partial("내일 일정 알려 줘", 0.95))
        var at = 0
        for (k in 1..100) {
            quiet(20)
            if (s.commits > 0) {
                at = k * 20
                break
            }
        }
        assertEquals(600, at)
    }

    @Test
    fun aGhostPartialLeavesTheStatusLineAfterVadOfQuiet() {
        started()
        loud()
        gw(In.Partial("네."))
        assertEquals("네.", host.tails.last())
        quiet(1_480)
        assertEquals("네.", host.tails.last())
        quiet(20)
        assertEquals(null, host.tails.last())
    }

    /** D10 v1.2: while the AI speaks, its own echo at the level of a normal voice is not 'real sound' */
    @Test
    fun theAisEchoDoesNotCountAsRealSound() {
        started()
        host.speaking = true
        val echo = ShortArray(320) { 800 } // level 0.024: over the threshold 0.02, under today's barge-in loudness 1.5 x threshold
        repeat(50) { talk.frame(echo, 320) }
        host.speaking = false
        gw(In.Final("오늘 회의는", "kor"))
        assertTrue(host.logs.contains("live final dropped quiet"))
        assertTrue(host.sent.isEmpty())
    }

    /** D12: a backchannel said over the AI is dropped; a stop word over the AI acts at once; a real barge-in counts. */
    @Test
    fun aBackchannelOverTheAiIsDroppedAsEcho() {
        started()
        host.speaking = true
        host.reading = "오늘 회의는 세 시에 시작합니다"
        loud()
        gw(In.Partial("응"))
        loud()
        gw(In.Final("응", "kor"))
        assertTrue(host.logs.contains("live final dropped echo"))
        assertTrue(host.sent.isEmpty())
        assertEquals(0, host.barged)
    }

    @Test
    fun aStopWordOverTheAiActsFromItsFirstPartial() {
        started()
        host.speaking = true
        host.reading = "오늘 회의는 세 시에 시작합니다"
        loud()
        gw(In.Partial("그만"))
        assertEquals(listOf(Words.Heard.STOP), host.routed)
        loud()
        gw(In.Final("그만", "kor"))
        assertEquals("not acted twice", 1, host.routed.size)
        assertTrue(host.logs.contains("live final acted"))
    }

    @Test
    fun aStopWordThatTheAiIsSayingDoesNotAct() {
        started()
        host.speaking = true
        host.reading = "이제 그만 정리하겠습니다"
        loud()
        gw(In.Partial("그만"))
        loud()
        gw(In.Final("그만", "kor"))
        assertTrue(host.routed.isEmpty())
    }

    @Test
    fun aRealBargeInIsSentAsTheOwnersWords() {
        started()
        host.speaking = true
        host.reading = "오늘 회의는 세 시에 시작합니다"
        loud()
        gw(In.Partial("아니 그거"))
        loud()
        gw(In.Partial("아니 그거 말고 내일"))
        assertEquals(1, host.barged)
        host.speaking = false
        loud()
        gw(In.Final("아니 그거 말고 내일 거", "kor"))
        assertEquals(listOf("아니 그거 말고 내일 거"), host.sent)
    }

    @Test
    fun replacedDoesNothing() {
        started()
        drop("replaced", 4409)
        after(5_000)
        assertEquals(1, socks.size)
        assertTrue(host.fallbacks.isEmpty())
    }

    @Test
    fun badRequestReopensOnceThenFallsBack() {
        started()
        drop("bad_request", 4400)
        assertEquals(2, socks.size)
        drop("bad_request", 4400)
        assertEquals(listOf("bad_request" to true), host.fallbacks)
    }

    @Test
    fun deviceWordsRecheckTheDeviceAndUseTheHttpPathWithoutD9() {
        for (w in listOf("unauthorized", "pending", "revoked")) {
            val h = Host()
            val t = LiveTalk(opener, h) { clock }
            t.start()
            socks.last().events.closed(LiveWire.Close(w, if (w == "unauthorized") 4401 else 4403, false))
            h.run()
            assertEquals(listOf(w), h.devices)
            assertEquals(listOf(w to false), h.fallbacks)
        }
    }

    @Test
    fun voiceOffAndVoiceKeyUseTheHttpPathWithoutD9() {
        for ((w, c) in listOf("voice_off" to 4503, "voice_key" to 4502)) {
            val h = Host()
            val t = LiveTalk(opener, h) { clock }
            t.start()
            socks.last().events.closed(LiveWire.Close(w, c, false))
            h.run()
            assertEquals(listOf(w to false), h.fallbacks)
            assertTrue(h.devices.isEmpty())
        }
    }

    @Test
    fun upstreamAndBusyFollowTheReconnectRule() {
        started()
        drop("upstream", 4502)
        after(500)
        drop("busy", 4429)
        after(1_000)
        assertEquals(3, socks.size)
        assertTrue(host.fallbacks.isEmpty())
    }

    @Test
    fun limitShowsD4AndEndsTalking() {
        started()
        drop("limit", 4429)
        assertEquals(1, host.limits)
        assertFalse(talk.listening)
        assertTrue(host.fallbacks.isEmpty())
        after(5_000)
        assertEquals(1, socks.size)
    }

    @Test
    fun talkingOffSendsEndAndLateFramesAreIgnored() {
        val s = started()
        talk.stop()
        assertTrue(s.ended)
        assertFalse(talk.listening)
        s.events.message(In.Final("늦게 온 말", "kor"))
        s.events.closed(LiveWire.Close(null, 1000, true))
        host.run()
        assertTrue(host.sent.isEmpty())
        talk.frame(ShortArray(320), 320)
        assertTrue("no socket: frames are dropped", s.audio.isEmpty())
        assertTrue(host.fallbacks.isEmpty())
    }

    @Test
    fun framesFromAnOldSocketAreIgnoredAfterAReopen() {
        val old = started()
        drop(s = old)
        after(500)
        old.events.message(In.Final("옛 소켓의 말", "kor"))
        host.run()
        assertTrue(host.sent.isEmpty())
        fin("새 소켓의 말")
        assertEquals(listOf("새 소켓의 말"), host.sent)
    }

    @Test
    fun noReadyNoCommit() {
        talk.start()
        gw(In.Partial("말"))
        after(50_000)
        assertEquals(0, socks.last().commits)
        assertEquals("말", host.tails.last())
    }
}
