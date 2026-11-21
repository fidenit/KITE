package com.fidenit.kite

import android.app.Application
import android.os.Looper
import com.fidenit.kite.engine.Ev
import com.fidenit.kite.engine.Runner
import com.fidenit.kite.engine.Stats
import com.fidenit.kite.voice.LiveEvents
import com.fidenit.kite.voice.LiveListen
import com.fidenit.kite.voice.LiveSpeak
import com.fidenit.kite.voice.LiveWire
import com.fidenit.kite.voice.LiveWire.In
import com.fidenit.kite.voice.ProVoice
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The realtime listen path as wired in the view model (spec aperit-pro-voice-realtime-v1 §3.2), on the JVM (Robolectric).
 * The test build is the free flavor, so the gateway's listen socket is replaced by a fake through `liveListenOverride`
 * (with no override the free flavor never opens one). No server, no network; the main looper's delayed ticks are not run.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KiteViewModelLiveTest {
    private lateinit var app: Application

    private class Sock(val events: LiveEvents) : LiveListen {
        var ended = false
        override fun audio(pcm: ShortArray, n: Int) = true
        override fun commit() {}
        override fun end() {
            ended = true
        }
    }

    private val socks = mutableListOf<Sock>()

    private class Speak(val events: LiveEvents) : LiveSpeak {
        val sent = mutableListOf<String>()
        override fun prepare() = sent.add("prepare")
        override fun say(id: Int, text: String) = sent.add("say $id $text")
        override fun stop() = sent.add("stop")
        override fun end() {
            sent.add("end")
        }
    }

    private val speaks = mutableListOf<Speak>()

    private val sink = LiveReadTest.Out()
    private lateinit var vm: KiteViewModel

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        app.filesDir.listFiles()?.forEach { it.deleteRecursively() }
        for (n in listOf("kite-settings", "kite-settings-plain")) app.getSharedPreferences(n, 0).edit().clear().commit()
        Store(app).elevenKey = "test-key-not-real" // the free flavor needs a key to switch talking on
        vm = KiteViewModel(app)
        vm.liveListenOverride = LiveListen.Opener { _, ev -> Sock(ev).also { socks.add(it) } }
        vm.liveSpeakOverride = LiveSpeak.Opener { ev -> Speak(ev).also { speaks.add(it) } }
        vm.sinkOverride = sink
        vm.livePlayThread = false // the playback queue runs when the test pumps it
        vm.micFramesOn = false // Robolectric's microphone sends silence as fast as it can: the test feeds the frames itself
    }

    @After
    fun tearDown() {
        vm.converse(false)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun gw(m: In) {
        socks.last().events.message(m)
        idle()
    }

    /** the speak socket's gateway side */
    private fun sp(m: In) {
        speaks.last().events.message(m)
        idle()
        vm.livePump()
        idle()
    }

    private fun spAudio(vararg v: Short) {
        speaks.last().events.audio(shortArrayOf(*v))
        idle()
        vm.livePump()
        idle()
    }

    /** 20 ms of real sound through the same path the mic frames take */
    private fun loud() = vm.liveTalk!!.frame(ShortArray(320) { 3000 }, 320)

    /** talking on, both sockets open, two sentences with a card each queued and sent ahead */
    private fun twoSentences() {
        vm.converse(true)
        idle()
        speaks.last().events.opened(5)
        gw(In.Ready())
        vm.aiLine("topic t1 일")
        vm.aiLine("tell 첫 문장입니다")
        vm.aiLine("card k1 note t1 2x1 첫 카드")
        vm.aiLine("tell 둘째 문장입니다")
        vm.aiLine("card k2 note t1 2x1 둘째 카드")
        idle()
    }

    @Test
    fun partialsShowInTheStatusAndAFinalFollowsTodaysRules() {
        vm.converse(true)
        idle()
        assertEquals("talking on opens one listen socket", 1, socks.size)
        gw(In.Ready())
        gw(In.Partial("잠깐"))
        assertEquals("잠깐", vm.liveHeard)
        assertTrue(vm.heard)
        gw(In.Final("잠깐만", "kor"))
        assertFalse("no real sound since the last final: dropped (D10)", vm.standby)
        loud()
        gw(In.Final("잠깐만", "kor"))
        assertNull(vm.liveHeard)
        assertFalse(vm.heard)
        assertTrue("a wait word through a final", vm.standby)
        vm.converse(false)
        assertTrue("talking off sends end", socks.last().ended)
        assertNull(vm.liveHeard)
    }

    @Test
    fun voiceOffFallsBackWithoutD9() {
        vm.converse(true)
        idle()
        socks.last().events.closed(LiveWire.Close("voice_off", 4503, false))
        idle()
        assertNull(vm.error)
        assertTrue(vm.converse)
    }

    @Test
    fun aSecondBadRequestFallsBackAndShowsD9Once() {
        vm.converse(true)
        idle()
        socks.last().events.closed(LiveWire.Close("bad_request", 4400, false))
        idle()
        assertEquals("reopened once", 2, socks.size)
        socks.last().events.closed(LiveWire.Close("bad_request", 4400, false))
        idle()
        assertEquals(ProVoice.liveDropped(false), vm.error)
        assertTrue("the conversation goes on the earlier way", vm.converse)
        assertEquals(2, socks.size)
    }

    @Test
    fun withoutVoiceLiveNoSocketIsOpened() {
        vm.liveListenOverride = null
        vm.converse(true)
        idle()
        assertTrue(vm.converse)
        assertEquals(0, socks.size)
    }

    @Test
    fun sentencesAreSentAheadAndCardsComeWithTheirSentencesStart() {
        twoSentences()
        assertEquals(listOf("say 1 첫 문장입니다", "say 2 둘째 문장입니다"), speaks.last().sent)
        assertNull("the card waits for its sentence", vm.board.card("k1"))
        assertFalse(vm.speaking)
        sp(In.Start(1))
        assertTrue(vm.speaking)
        assertNotNull(vm.board.card("k1"))
        assertNull(vm.board.card("k2"))
        spAudio(1, 2)
        assertEquals(listOf<Short>(1, 2), sink.got.take(2))
        sp(In.Done(1, 8))
        sp(In.Start(2))
        assertNotNull(vm.board.card("k2"))
        spAudio(*ShortArray(4800) { 3 }) // 0.2 s
        sp(In.Done(2, 8))
        vm.liveReadTick()
        assertTrue("still playing", vm.speaking)
        sink.played = 4802
        vm.liveReadTick()
        assertFalse("played out, nothing more pending", vm.speaking)
        vm.converse(false)
        assertEquals("end", speaks.last().sent.last())
    }

    @Test
    fun anEmptySentenceStillAppliesItsCards() {
        vm.converse(true)
        idle()
        vm.aiLine("topic t1 일")
        vm.aiLine("tell …")
        vm.aiLine("card k1 note t1 2x1 첫 카드")
        idle()
        sp(In.Start(1))
        sp(In.Done(1, 0))
        assertNotNull(vm.board.card("k1"))
    }

    @Test
    fun aTurnInTalkingModeSendsPrepare() {
        vm.converse(true)
        idle()
        vm.send("안녕")
        idle()
        assertEquals("prepare", speaks.last().sent.firstOrNull())
        vm.stop()
        idle()
    }

    @Test
    fun aBargeInByWordsStopsReadingSendsStopAndIgnoresStaleAudio() {
        twoSentences()
        sp(In.Start(1))
        spAudio(1)
        loud()
        gw(In.Partial("아니 그거"))
        assertTrue("one partial is not enough", vm.speaking)
        loud()
        gw(In.Partial("아니 그거 말고"))
        assertFalse(vm.speaking)
        assertEquals("stop", speaks.last().sent.last())
        assertTrue("the speaker was emptied", sink.flushes >= 1)
        assertNotNull("queued cards are still shown", vm.board.card("k2"))
        spAudio(9)
        sp(In.Done(1, 8))
        sp(In.Start(2))
        spAudio(9)
        sp(In.Stopped(2))
        assertEquals(listOf<Short>(1), sink.got)
        assertFalse(vm.speaking)
    }

    @Test
    fun anEchoPartialWithSoundDoesNotBargeIn() {
        twoSentences()
        sp(In.Start(1))
        loud()
        gw(In.Partial("첫 문장"))
        loud()
        gw(In.Partial("첫 문장입니"))
        assertTrue(vm.speaking)
        assertFalse(speaks.last().sent.contains("stop"))
    }

    @Test
    fun aSkippedSentenceGoesTheEarlierWayWithTheRest() {
        twoSentences()
        vm.store.elevenKey = "" // the earlier way then uses the phone's own voice (no network in the test)
        sp(In.Skipped(1, "limit"))
        assertEquals("stop", speaks.last().sent.last())
        assertNotNull("the head left the queue through speak()", vm.board.card("k1"))
        assertNull(vm.board.card("k2"))
        sp(In.Start(2))
        assertNull("nothing live after the skip", vm.board.card("k2"))
    }

    @Test
    fun aSpeakSocketDropMidReplyGoesTheEarlierWay() {
        twoSentences()
        vm.store.elevenKey = ""
        sp(In.Start(1))
        speaks.last().events.closed(LiveWire.Close(null, -1, false))
        idle()
        vm.livePump() // the audio already received plays first, then the 'broke' marker
        idle()
        assertNotNull(vm.board.card("k1"))
        assertNotNull("the second sentence was popped by speak()", vm.board.card("k2"))
        assertEquals("the sentence being read broke off: D7, not D9", ProVoice.brokeOff(false), vm.error)
    }

    @Test
    fun twoFailedSpeakOpensShowD9OnceAndReadTheEarlierWay() {
        vm.converse(true)
        idle()
        speaks.last().events.closed(LiveWire.Close(null, -1, false))
        idle()
        vm.store.elevenKey = ""
        vm.aiLine("topic t1 일")
        vm.aiLine("tell 첫 문장입니다")
        idle()
        assertEquals(2, speaks.size)
        speaks.last().events.closed(LiveWire.Close(null, -1, false))
        idle()
        assertEquals(ProVoice.liveDropped(false), vm.error)
        vm.error = null
        vm.aiLine("tell 둘째 문장입니다")
        idle()
        assertEquals("no more speak sockets", 2, speaks.size)
        assertNull("D9 once", vm.error)
    }

    /** v1.2 (a): `skipped` after `start` = the sentence broke off: D7 once, not re-read, the rest goes the earlier way */
    @Test
    fun aSkippedAfterStartShowsD7OnceAndTheRestGoesOn() {
        twoSentences()
        vm.store.elevenKey = ""
        sp(In.Start(1))
        spAudio(1)
        sp(In.Skipped(1, "upstream"))
        assertEquals(ProVoice.brokeOff(false), vm.error)
        assertNotNull("the second sentence was popped by speak()", vm.board.card("k2"))
        assertEquals("stop", speaks.last().sent.last())
    }

    @Test
    fun theWatchdogEndsSpeakingWhenDoneNeverComes() {
        twoSentences()
        vm.store.elevenKey = ""
        sp(In.Start(1))
        spAudio(1)
        sink.played = 1
        vm.liveReadTick()
        assertTrue(vm.speaking)
        Thread.sleep(10_100)
        vm.liveReadTick()
        idle()
        assertNotNull("the rest went the earlier way", vm.board.card("k2"))
        assertEquals(ProVoice.brokeOff(false), vm.error)
    }

    /** an engine that holds the turn until [gate] opens, then gives [lines] */
    private class Held(val lines: String) : Runner {
        override val label = "fake"
        val started = java.util.concurrent.CountDownLatch(1)
        val gate = java.util.concurrent.CountDownLatch(1)

        override fun run(turnText: String, stopped: () -> Boolean, send: (Ev) -> Unit): Stats {
            started.countDown()
            gate.await(10, java.util.concurrent.TimeUnit.SECONDS)
            if (!stopped()) send(Ev.Delta(lines))
            return Stats()
        }
    }

    /** Runs the main looper one task at a time until `cond` holds. */
    private fun until(what: String, cond: () -> Boolean) {
        val looper = shadowOf(Looper.getMainLooper())
        val end = System.currentTimeMillis() + 10_000
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "timed out: $what" }
            if (looper.isIdle) Thread.sleep(10) else looper.runOneTask()
        }
    }

    private val say1 = "네, 잘 들려요. 말씀하세요."
    private val tell1 = "네, 잘 들려요. 무엇을 도와드릴까요?"
    private val reply1 = "say $say1\ntell $tell1\n"

    /** talking on with both sockets open; a final starts a turn the engine holds */
    private fun turnHeld(fake: Held) {
        vm.converse(true)
        idle()
        speaks.last().events.opened(5)
        gw(In.Ready())
        vm.runnerOverride = fake
        loud()
        gw(In.Final("테스트.", "kor"))
        until("the engine has the turn") { fake.started.count == 0L }
        assertTrue(vm.busy)
    }

    /** the line under the screen (Screen.kt BottomBar) */
    private fun underScreen() = vm.liveSay ?: vm.board.show(vm.board.say)

    /** T1, gateway events 2026-11-21 00:16Z: a phantom partial while the AI works must not silence the reply */
    @Test
    fun t1APartialWhileTheAiWorksStillReadsTheReplyAndShowsTheTell() {
        vm.talkAlwaysReads = true // the Pro default (the test build is the free flavor)
        val fake = Held(reply1)
        turnHeld(fake)
        gw(In.Partial("네.")) // no loud audio, busy, not speaking
        fake.gate.countDown()
        until("turn ends") { !vm.busy }
        val sent = speaks.last().sent
        assertTrue(sent.toString(), sent.any { it.startsWith("say ") && it.endsWith(" $tell1") })
        assertTrue(sent.toString(), sent.any { it.startsWith("say ") && it.endsWith(" $say1") })
        assertEquals(tell1, underScreen())
        assertTrue(vm.recent.last().second, vm.recent.last().second.contains(tell1))
    }

    /** R2: the talk log has say then tell; the line under the screen keeps the tell, also after a restart */
    @Test
    fun r2TheTalkLogHasSayAndTellAndARestartShowsTheSame() {
        t1APartialWhileTheAiWorksStillReadsTheReplyAndShowsTheTell()
        assertEquals("$say1\n$tell1", vm.recent.last().second)
        vm.converse(false)
        assertTrue(vm.flushWrites())
        val again = KiteViewModel(app)
        assertEquals("$say1\n$tell1", again.recent.last().second)
        assertEquals(tell1, again.board.show(again.board.say))
        again.flushWrites()
    }

    /** R2: the same answer twice (or one inside the other) is kept once */
    @Test
    fun r2TheSameSayAndTellIsLoggedOnce() {
        vm.talkAlwaysReads = true
        val fake = Held("say 좋아요\ntell 좋아요. 다 됐어요\n")
        turnHeld(fake)
        fake.gate.countDown()
        until("turn ends") { !vm.busy }
        assertEquals("좋아요. 다 됐어요", vm.recent.last().second)
        assertEquals("좋아요. 다 됐어요", underScreen())
    }

    /** R2: a turn without tell stays as today */
    @Test
    fun r2ATurnWithoutTellKeepsTheSay() {
        vm.talkAlwaysReads = true
        val fake = Held("say $say1\n")
        turnHeld(fake)
        fake.gate.countDown()
        until("turn ends") { !vm.busy }
        // today's rule: the log takes the line under the screen as the turn ends (the say is applied when its sentence starts)
        assertEquals(underScreen(), vm.recent.last().second)
        assertTrue(speaks.last().sent.any { it.endsWith(" $say1") })
    }

    /** T2: the earlier mic (no realtime listen): a loud sound while the AI works does not silence the reply */
    @Test
    fun t2OnTheEarlierMicALoudSoundWhileTheAiWorksStillReadsTheReply() {
        vm.talkAlwaysReads = true
        assertEquals(listOf(tell1), earlierMicTurn())
        assertEquals(tell1, underScreen())
        assertEquals(tell1, vm.recent.last().second)
    }

    /** talking on the earlier way, a turn with a loud sound while the engine holds it; what went to speak() */
    private fun earlierMicTurn(): List<String> {
        vm.liveListenOverride = null
        vm.converse(true)
        idle()
        vm.store.elevenKey = "" // the earlier way then uses the phone's own voice (no network in the test)
        val read = mutableListOf<String>()
        vm.onSpeak = { read.add(it) }
        val fake = Held("tell $tell1\n")
        vm.runnerOverride = fake
        vm.send("테스트.")
        until("the engine has the turn") { fake.started.count == 0L }
        vm.loudWhileBusy()
        fake.gate.countDown()
        until("turn ends") { !vm.busy }
        return read
    }

    /** T3: with the Pro rule on, barge-in by words while the AI speaks still cuts */
    @Test
    fun t3BargeInWhileSpeakingStillCuts() {
        vm.talkAlwaysReads = true
        aBargeInByWordsStopsReadingSendsStopAndIgnoresStaleAudio()
    }

    /** T3: "그만" while the AI works stops the work; nothing is read */
    @Test
    fun t3AStopWordWhileTheAiWorksStops() {
        vm.talkAlwaysReads = true
        val fake = Held(reply1)
        turnHeld(fake)
        loud()
        gw(In.Final("그만", "kor"))
        fake.gate.countDown()
        until("turn ends") { !vm.busy }
        assertFalse(speaks.last().sent.toString(), speaks.last().sent.any { it.startsWith("say ") })
    }

    /** T3: "잠깐만" while the AI works waits (standby): the reply is not read, the tell is still shown */
    @Test
    fun t3AWaitWordWhileTheAiWorksWaits() {
        vm.talkAlwaysReads = true
        val fake = Held(reply1)
        turnHeld(fake)
        loud()
        gw(In.Final("잠깐만", "kor"))
        assertTrue(vm.standby)
        fake.gate.countDown()
        until("turn ends") { !vm.busy }
        assertFalse(speaks.last().sent.toString(), speaks.last().sent.any { it.startsWith("say ") })
        assertEquals(tell1, underScreen())
    }

    /** kept: words said while the AI works are queued and sent after the turn */
    @Test
    fun wordsSaidWhileTheAiWorksAreSentAfter() {
        vm.talkAlwaysReads = true
        val fake = Held(reply1)
        turnHeld(fake)
        loud()
        gw(In.Final("다른 말", "kor"))
        fake.gate.countDown()
        until("the queued words' turn ends") { !vm.busy && vm.recent.any { it.first == "다른 말" } }
    }

    /** T4: the free flavor keeps today's rule: a partial while the AI works silences the reply, tell is not shown */
    @Test
    fun t4FlagOffAPartialWhileTheAiWorksSilencesTheReply() {
        vm.talkAlwaysReads = false
        val fake = Held(reply1)
        turnHeld(fake)
        gw(In.Partial("네."))
        fake.gate.countDown()
        until("turn ends") { !vm.busy }
        assertEquals(listOf("prepare"), speaks.last().sent)
        assertEquals(say1, underScreen())
        assertEquals(say1, vm.recent.last().second)
        assertFalse(proLog().contains("hush-would"))
    }

    /** T4: the free flavor on the earlier mic: a loud sound while the AI works silences the reply */
    @Test
    fun t4FlagOffOnTheEarlierMicALoudSoundSilencesTheReply() {
        vm.talkAlwaysReads = false
        assertEquals(emptyList<String>(), earlierMicTurn())
    }

    private fun proLog(): String = java.io.File(app.filesDir, "pro.log").takeIf { it.exists() }?.readText().orEmpty()

    /** pro.log is written on its own thread */
    private fun proLogHas(s: String): String {
        val end = System.currentTimeMillis() + 5_000
        while (!proLog().contains(s) && System.currentTimeMillis() < end) Thread.sleep(10)
        return proLog()
    }

    /** T5: where the old rule would have silenced the reply, one pro.log line with numbers only */
    @Test
    fun t5TheOldRuleLeavesOneLogLineWithoutTheWords() {
        t1APartialWhileTheAiWorksStillReadsTheReplyAndShowsTheTell()
        val log = proLogHas("live hush-would ms=")
        val lines = log.lines().filter { it.contains("hush-would") }
        assertEquals(log, 1, lines.size)
        assertTrue(lines[0], Regex("live hush-would ms=\\d+$").containsMatchIn(lines[0]))
    }

    /** T5: the earlier mic path writes its own line */
    @Test
    fun t5TheEarlierMicLeavesAVoiceLine() {
        vm.talkAlwaysReads = true
        earlierMicTurn()
        assertTrue(Regex("voice hush-would ms=\\d+").containsMatchIn(proLogHas("voice hush-would ms=")))
    }
}
