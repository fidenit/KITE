package com.fidenit.kite

import com.fidenit.kite.voice.Eleven
import com.fidenit.kite.voice.LiveEvents
import com.fidenit.kite.voice.LiveRead
import com.fidenit.kite.voice.LiveSpeak
import com.fidenit.kite.voice.LiveWire
import com.fidenit.kite.voice.LiveWire.In
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The realtime reading socket logic (spec aperit-pro-voice-realtime-v1 §3.2 Reading, §3.4) with a fake socket. JVM only. */
class LiveReadTest {
    private var clock = 5_000_000L

    private class Sock(val events: LiveEvents) : LiveSpeak {
        val sent = mutableListOf<String>()
        override fun prepare() = sent.add("prepare")
        override fun say(id: Int, text: String) = sent.add("say $id $text")
        override fun stop() = sent.add("stop")
        override fun end() {
            sent.add("end")
        }
    }

    private val socks = mutableListOf<Sock>()
    private var openThrows = false
    private val opener = LiveSpeak.Opener { ev ->
        if (openThrows) throw java.io.IOException("no route")
        Sock(ev).also { socks.add(it) }
    }

    private class Sink : Eleven.Sink {
        val got = mutableListOf<Short>()
        override fun push(pcm: ShortArray, n: Int) {
            for (i in 0 until n) got.add(pcm[i])
        }

        override fun pendingSecs() = 0f
        override fun stop() {}
    }

    private inner class Host : LiveRead.Host {
        val queue = ArrayDeque<() -> Unit>()
        val sinkImpl = Sink()
        val started = mutableListOf<Int>()
        val done = mutableListOf<Int>()
        val rest = mutableListOf<String>()
        val fallbacks = mutableListOf<Pair<String, Boolean>>()
        val devices = mutableListOf<String>()
        val logs = mutableListOf<String>()
        override fun post(block: () -> Unit) {
            queue.addLast(block)
        }

        fun run() {
            while (queue.isNotEmpty()) queue.removeFirst()()
        }

        override val sink: Eleven.Sink get() = sinkImpl
        override fun started(id: Int) {
            started.add(id)
        }

        override fun done(id: Int) {
            done.add(id)
        }

        override fun rest(why: String) {
            rest.add(why)
        }

        override fun fallback(why: String, notice: Boolean) {
            fallbacks.add(why to notice)
        }

        override fun device(word: String) {
            devices.add(word)
        }

        override fun log(line: String) {
            logs.add(line)
        }
    }

    private val host = Host()
    private val read = LiveRead(opener, host) { clock }

    private fun gw(m: In, s: Sock = socks.last()) {
        s.events.message(m)
        host.run()
    }

    private fun audio(vararg v: Short, s: Sock = socks.last()) {
        s.events.audio(shortArrayOf(*v))
        host.run()
    }

    private fun opened(): Sock {
        read.open()
        socks.last().events.opened(30)
        host.run()
        return socks.last()
    }

    private fun drop(word: String?, code: Int, s: Sock = socks.last()) {
        s.events.closed(LiveWire.Close(word, code, false))
        host.run()
    }

    @Test
    fun sentencesAreSentAheadAndStartDoneComeInOrder() {
        val s = opened()
        read.prepare()
        assertEquals(1, read.say("첫 문장"))
        assertEquals(2, read.say("둘째 문장"))
        assertEquals(3, read.say(""))
        assertEquals(listOf("prepare", "say 1 첫 문장", "say 2 둘째 문장", "say 3 "), s.sent)
        assertTrue(read.outstanding)
        clock += 120
        gw(In.Start(1))
        audio(1, 2)
        gw(In.Done(1, 3))
        gw(In.Start(2))
        audio(3)
        gw(In.Done(2, 4))
        gw(In.Start(3))
        gw(In.Done(3, 0))
        assertEquals(listOf(1, 2, 3), host.started)
        assertEquals(listOf(1, 2, 3), host.done)
        assertEquals(listOf<Short>(1, 2, 3), host.sinkImpl.got)
        assertFalse(read.outstanding)
        assertTrue(host.logs.contains("live speak first_audio_ms=120 say_to_audio_ms=120"))
    }

    @Test
    fun afterStopStaleAudioAndStartsAreIgnoredAndNewIdsWork() {
        val s = opened()
        read.say("하나")
        read.say("둘")
        gw(In.Start(1))
        audio(1)
        read.stop()
        assertEquals("stop", s.sent.last())
        audio(2) // still sentence 1, after stop
        gw(In.Done(1, 2))
        gw(In.Start(2))
        audio(3)
        gw(In.Stopped(2))
        assertEquals(listOf(1), host.started)
        assertEquals(listOf<Short>(1), host.sinkImpl.got)
        assertEquals(3, read.say("셋"))
        gw(In.Start(3))
        audio(4)
        assertEquals(listOf(1, 3), host.started)
        assertEquals(listOf<Short>(1, 4), host.sinkImpl.got)
        read.stop()
        read.stop()
        assertEquals("one stop per batch", 2, s.sent.count { it == "stop" })
    }

    @Test
    fun aSkippedSentenceSendsTheRestOfTheReplyToTheHttpPath() {
        opened()
        read.say("하나")
        read.say("둘")
        gw(In.Start(1))
        gw(In.Done(1, 2))
        gw(In.Skipped(2, "limit"))
        assertEquals(listOf("skipped_limit"), host.rest)
        assertTrue(read.usable)
    }

    @Test
    fun aDropMidReplySendsTheRestToHttpAndTheNextSayReopens() {
        opened()
        read.say("하나")
        gw(In.Start(1))
        drop(null, -1)
        assertEquals(listOf("closed_network"), host.rest)
        assertTrue("it had opened: not a failed open", read.usable)
        assertEquals(1, read.say("다음 답"))
        assertEquals(2, socks.size)
        assertEquals(listOf("say 1 다음 답"), socks.last().sent)
    }

    @Test
    fun twoFailedOpensInARowFallBackWithD9() {
        read.open()
        drop(null, -1)
        assertTrue(read.usable)
        read.prepare()
        drop("upstream", 4502)
        assertFalse(read.usable)
        assertEquals(listOf("upstream" to true), host.fallbacks)
        assertEquals(0, read.say("x"))
        assertEquals(2, socks.size)
    }

    @Test
    fun anOpenThatThrowsCountsAsAFailedOpen() {
        openThrows = true
        read.open()
        host.run()
        read.prepare()
        host.run()
        assertEquals(listOf("network" to true), host.fallbacks)
    }

    @Test
    fun anOpenSocketResetsTheCount() {
        read.open()
        drop(null, -1)
        opened()
        drop(null, -1)
        read.open()
        drop(null, -1)
        assertTrue(read.usable)
    }

    @Test
    fun errorWordsOnTheSpeakSocket() {
        fun case(word: String, code: Int): Host {
            val h = Host()
            val r = LiveRead(opener, h) { clock }
            r.open()
            socks.last().events.opened(10)
            h.run()
            socks.last().events.closed(LiveWire.Close(word, code, false))
            h.run()
            return h
        }
        assertEquals(listOf("voice_off" to false), case("voice_off", 4503).fallbacks)
        assertEquals(listOf("voice_key" to false), case("voice_key", 4502).fallbacks)
        case("revoked", 4403).let {
            assertEquals(listOf("revoked"), it.devices)
            assertEquals(listOf("revoked" to false), it.fallbacks)
        }
        for ((w, c) in listOf("idle" to 4408, "session_max" to 4408, "replaced" to 4409, "limit" to 4429, "busy" to 4429, "upstream" to 4502)) {
            assertTrue(w, case(w, c).fallbacks.isEmpty())
        }
        val h = Host()
        val r = LiveRead(opener, h) { clock }
        r.open()
        socks.last().events.closed(LiveWire.Close("bad_request", 4400, false))
        h.run()
        r.open()
        socks.last().events.closed(LiveWire.Close("bad_request", 4400, false))
        h.run()
        assertEquals("bad_request: once more, then the HTTP path", listOf("bad_request" to true), h.fallbacks)
    }

    @Test
    fun endClosesAndLateFramesAreIgnored() {
        val s = opened()
        read.say("하나")
        read.end()
        assertEquals("end", s.sent.last())
        gw(In.Start(1), s)
        audio(9, s = s)
        s.events.closed(LiveWire.Close(null, 1000, true))
        host.run()
        assertTrue(host.started.isEmpty())
        assertTrue(host.sinkImpl.got.isEmpty())
        assertTrue(host.rest.isEmpty())
    }

    @Test
    fun gapsAreCountedWhileMoreIsExpected() {
        opened()
        read.prepare()
        read.play(more = true, pendingSecs = 0.3f)
        read.play(more = true, pendingSecs = 0f)
        clock += 180
        read.play(more = true, pendingSecs = 0f)
        clock += 20
        read.play(more = true, pendingSecs = 0.2f)
        read.play(more = false, pendingSecs = 0f)
        read.replyDone()
        assertTrue(host.logs.contains("live speak gaps=1 gap_ms_max=200"))
    }
}
