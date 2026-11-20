package com.fidenit.kite

import com.fidenit.kite.voice.LiveRules
import com.fidenit.kite.voice.LiveWire
import com.fidenit.kite.voice.LiveWire.Fault
import com.fidenit.kite.voice.LiveWire.In
import com.fidenit.kite.voice.Words
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** spec aperit-pro-voice-realtime-v1 §1 frames and §3.2/§3.4 rules, JVM only. */
class LiveWireTest {
    @Test
    fun urlsSwapTheSchemeAndKeepThePlainEmulatorUrl() {
        assertEquals("wss://aperit.fidenit.com/v1/voice/live/listen?lang=kor", LiveWire.listenUrl("https://aperit.fidenit.com/", false))
        assertEquals("wss://aperit.fidenit.com/v1/voice/live/listen?lang=eng", LiveWire.listenUrl("https://aperit.fidenit.com", true))
        assertEquals("ws://10.0.2.2:8787/v1/voice/live/speak", LiveWire.speakUrl("http://10.0.2.2:8787"))
        assertNull(LiveWire.speakUrl(""))
        assertNull(LiveWire.speakUrl("ftp://x"))
    }

    @Test
    fun phoneFramesAreOneJsonObjectWithAType() {
        assertEquals("commit", JSONObject(LiveWire.commit()).getString("type"))
        assertEquals("end", JSONObject(LiveWire.end()).getString("type"))
        assertEquals("prepare", JSONObject(LiveWire.prepare()).getString("type"))
        assertEquals("stop", JSONObject(LiveWire.stop()).getString("type"))
        val say = JSONObject(LiveWire.say(3, "안녕 \"hi\"\n"))
        assertEquals("say", say.getString("type"))
        assertEquals(3, say.getInt("id"))
        assertEquals("안녕 \"hi\"\n", say.getString("text"))
    }

    @Test
    fun gatewayFramesParseAndUnknownOnesAreIgnored() {
        assertEquals(In.Ready(), LiveWire.parse("""{"type":"ready"}"""))
        assertEquals(In.Partial("안녕하"), LiveWire.parse("""{"type":"partial","text":"안녕하","extra":1}"""))
        assertEquals(In.Partial(""), LiveWire.parse("""{"type":"partial","text":""}"""))
        assertEquals(In.Final("hello", "eng"), LiveWire.parse("""{"type":"final","text":"hello","lang":"eng"}"""))
        assertEquals(In.Final("x", ""), LiveWire.parse("""{"type":"final","text":"x"}"""))
        assertEquals(In.Start(2), LiveWire.parse("""{"type":"start","id":2}"""))
        assertEquals(In.Done(2, 17), LiveWire.parse("""{"type":"done","id":2,"chars":17}"""))
        assertEquals(In.Skipped(4, "limit"), LiveWire.parse("""{"type":"skipped","id":4,"error":"limit"}"""))
        assertEquals(In.Stopped(9), LiveWire.parse("""{"type":"stopped","upto":9}"""))
        assertEquals(In.Error("voice_off"), LiveWire.parse("""{"type":"error","error":"voice_off"}"""))
        assertNull(LiveWire.parse("""{"type":"alignment","chars":[]}"""))
        assertNull(LiveWire.parse("""{"text":"no type"}"""))
        assertNull(LiveWire.parse("not json"))
        assertNull("start without an id", LiveWire.parse("""{"type":"start"}"""))
        assertNull(LiveWire.parse("""{"type":"stopped"}"""))
    }

    /** v1.1: `ready.vad_s` and `partial.tp` (additive; missing or out of range -> null) */
    @Test
    fun readyVadAndPartialTpParse() {
        assertEquals(In.Ready(1.5), LiveWire.parse("""{"type":"ready","vad_s":1.5}"""))
        assertEquals(In.Ready(2.0), LiveWire.parse("""{"type":"ready","vad_s":2}"""))
        assertEquals(In.Ready(null), LiveWire.parse("""{"type":"ready","vad_s":"x"}"""))
        assertEquals(In.Ready(null), LiveWire.parse("""{"type":"ready","vad_s":0}"""))
        assertEquals(In.Partial("응", 0.91), LiveWire.parse("""{"type":"partial","text":"응","tp":0.91}"""))
        assertEquals(In.Partial("응", null), LiveWire.parse("""{"type":"partial","text":"응"}"""))
        assertEquals(In.Partial("응", null), LiveWire.parse("""{"type":"partial","text":"응","tp":1.7}"""))
        assertEquals(150L, LiveRules.earlyQuietMs(1.5, 0.9))
        assertEquals(750L, LiveRules.earlyQuietMs(1.5, 0.5))
        assertEquals(1470L, LiveRules.earlyQuietMs(1.5, 0.02))
        assertEquals(0f, LiveRules.level(ShortArray(320), 320))
        assertTrue(LiveRules.level(ShortArray(320) { 3000 }, 320) > 0.09f)
    }

    @Test
    fun pcmIsSigned16BitLittleEndianBothWays() {
        val s = shortArrayOf(0, 1, -1, 32767, -32768, 0x1234)
        val b = LiveWire.pcmBytes(s, s.size)
        assertArrayEquals(byteArrayOf(0, 0, 1, 0, -1, -1, -1, 0x7F, 0, -128, 0x34, 0x12), b)
        assertArrayEquals(s, LiveWire.pcmShorts(b))
        assertEquals(2, LiveWire.pcmShorts(byteArrayOf(1, 0, 2, 0, 9)).size)
        assertEquals(4, LiveWire.pcmBytes(s, 2).size)
    }

    @Test
    fun everyErrorWordAndCloseCodeHasItsAction() {
        val words = mapOf(
            "unauthorized" to Fault.DEVICE, "pending" to Fault.DEVICE, "revoked" to Fault.DEVICE, "voice_off" to Fault.OFF, "voice_key" to Fault.KEY,
            "upstream" to Fault.UPSTREAM, "limit" to Fault.LIMIT, "busy" to Fault.BUSY, "bad_request" to Fault.BAD, "idle" to Fault.IDLE,
            "session_max" to Fault.IDLE, "replaced" to Fault.REPLACED,
        )
        for ((w, f) in words) assertEquals(w, f, LiveWire.fault(w, 4000))
        val codes = mapOf(1000 to Fault.NONE, 4401 to Fault.DEVICE, 4403 to Fault.DEVICE, 4503 to Fault.OFF, 4502 to Fault.UPSTREAM, 4429 to Fault.BUSY, 4400 to Fault.BAD, 4408 to Fault.IDLE, 4409 to Fault.REPLACED, 1006 to Fault.NETWORK, -1 to Fault.NETWORK)
        for ((c, f) in codes) assertEquals("code $c", f, LiveWire.fault(null, c))
        assertEquals("an unknown word falls back to the code", Fault.OFF, LiveWire.fault("new_word", 4503))
        val acts = mapOf(
            "unauthorized" to LiveRules.Act.DEVICE, "pending" to LiveRules.Act.DEVICE, "revoked" to LiveRules.Act.DEVICE, "voice_off" to LiveRules.Act.HTTP,
            "voice_key" to LiveRules.Act.HTTP, "upstream" to LiveRules.Act.RETRY, "limit" to LiveRules.Act.LIMIT, "busy" to LiveRules.Act.RETRY,
            "bad_request" to LiveRules.Act.RETRY_ONCE, "idle" to LiveRules.Act.REOPEN, "session_max" to LiveRules.Act.REOPEN, "replaced" to LiveRules.Act.NOTHING,
        )
        for ((w, a) in acts) assertEquals(w, a, LiveRules.listenAct(LiveWire.Close(w, 4000, false)))
        assertEquals(LiveRules.Act.RETRY, LiveRules.listenAct(LiveWire.Close(null, -1, false)))
        assertEquals("the gateway closing normally while we still listen is a drop", LiveRules.Act.RETRY, LiveRules.listenAct(LiveWire.Close(null, 1000, false)))
        assertEquals("we closed it", LiveRules.Act.NOTHING, LiveRules.listenAct(LiveWire.Close("upstream", 4502, true)))
        assertEquals("network", LiveWire.Close(null, -1, false).logWord)
        assertEquals("code1006", LiveWire.Close(null, 1006, false).logWord)
        assertEquals("idle", LiveWire.Close("idle", 4408, false).logWord)
    }

    @Test
    fun bargeInNeedsTwoHangulSyllablesOrThreeLatinLettersThatAreNotEcho() {
        assertFalse(LiveRules.longEnough("음"))
        assertFalse(LiveRules.longEnough("음 . !"))
        assertTrue(LiveRules.longEnough("잠깐"))
        assertTrue(LiveRules.longEnough("잠 깐!"))
        assertFalse(LiveRules.longEnough("ok"))
        assertTrue(LiveRules.longEnough("o k a"))
        assertFalse(LiveRules.longEnough("12345"))
        val reading = "오늘 회의는 세 시에 시작합니다"
        assertFalse("not speaking", LiveRules.barge("잠깐만 멈춰", false, reading, emptyList()))
        assertTrue(LiveRules.barge("잠깐만 멈춰", true, reading, emptyList()))
        assertFalse("echo of the sentence being read", LiveRules.barge("오늘 회의는 세 시에", true, reading, emptyList()))
        assertFalse("a fragment of it", LiveRules.barge("회의는", true, reading, emptyList()))
        assertFalse("fragment of one of the last 3 spoken", LiveRules.barge("내일 아침", true, null, listOf("a", "b", "내일 아침에 다시 알려 드릴게요")))
        assertTrue("older than the last 3 is not echo", LiveRules.barge("내일 아침", true, null, listOf("내일 아침에 다시 알려 드릴게요", "a", "b", "c")))
        assertFalse("too short", LiveRules.barge("응", true, reading, emptyList()))
        assertTrue(LiveRules.barge("wait stop", true, "Here is the plan", emptyList()))
        assertFalse(LiveRules.barge("the plan", true, "Here is the plan", emptyList()))
    }

    @Test
    fun hushOnlyWhileWorkingAndNotSpeaking() {
        assertTrue(LiveRules.hush("이거 말고", busy = true, speaking = false, spoken = emptyList()))
        assertFalse(LiveRules.hush("이거 말고", busy = false, speaking = false, spoken = emptyList()))
        assertFalse(LiveRules.hush("이거 말고", busy = true, speaking = true, spoken = emptyList()))
        assertFalse(LiveRules.hush(" ", busy = true, speaking = false, spoken = emptyList()))
        assertFalse(LiveRules.hush("찾아볼게요", busy = true, speaking = false, spoken = listOf("지금 찾아볼게요")))
    }

    @Test
    fun theStatusTailShowsTheNewestWords() {
        assertEquals("안녕", LiveRules.tail(" 안녕\n"))
        val long = "가".repeat(10) + " 마지막 낱말까지 보이게 해 주세요 정말로"
        val t = LiveRules.tail(long)
        assertTrue(t.startsWith("…"))
        assertTrue(t.endsWith("정말로"))
        assertTrue(t.length <= LiveRules.TAIL + 1)
    }

    @Test
    fun commitIsDueAfter45sOfOneUtteranceAndAgainIfNoFinalComes() {
        val c = LiveRules.CommitClock()
        assertFalse(c.due(0))
        c.partial("", 0)
        assertFalse("an empty partial does not start the clock", c.due(50_000))
        c.partial("a", 1_000)
        c.partial("ab", 30_000)
        assertFalse(c.due(45_999))
        assertTrue(c.due(46_000))
        assertFalse("once", c.due(46_040))
        assertTrue("again after another 45 s", c.due(91_000))
        c.final()
        assertFalse(c.due(200_000))
    }

    @Test
    fun listenRetriesAfterHalfOneTwoSecondsThenGivesUpAndReadyResets() {
        val r = LiveRules.Retry()
        assertEquals(500L, r.failed(hadReady = true))
        assertEquals(1000L, r.failed(hadReady = false))
        assertEquals(2000L, r.failed(hadReady = false))
        assertNull("3 failed reopen attempts in a row", r.failed(hadReady = false))
        val r2 = LiveRules.Retry()
        assertEquals(500L, r2.failed(true))
        assertEquals(1000L, r2.failed(false))
        assertEquals("a socket that got ready starts over", 500L, r2.failed(true))
        val o = LiveRules.Opens()
        assertFalse(o.failed())
        o.opened()
        assertFalse(o.failed())
        assertTrue("2 failed opens in a row", o.failed())
    }

    @Test
    fun finalsFollowTodaysRules() {
        assertEquals(Words.Heard.IGNORE, Words.route(" ", false, emptyList()))
        assertEquals(Words.Heard.SEND, Words.route("회의 일정 정리해 줘", false, emptyList()))
        assertEquals(Words.Heard.IGNORE, Words.route("회의 일정 정리해 줘", true, emptyList()))
        assertEquals(Words.Heard.SEND, Words.route("아페리트 다 봤어", true, emptyList()))
        assertEquals(Words.Heard.WAIT, Words.route("잠깐만", false, emptyList()))
        assertEquals(Words.Heard.STOP, Words.route("그만", false, emptyList()))
        assertEquals(Words.Heard.IGNORE, Words.route("오늘 회의는 세 시에 시작합니다", false, listOf("오늘 회의는 세 시에 시작합니다")))
    }
}
