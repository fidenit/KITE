package com.fidenit.kite

import android.app.Application
import android.os.Looper
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
}
