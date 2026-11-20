package com.fidenit.kite

import com.fidenit.kite.voice.LiveEvents
import com.fidenit.kite.voice.LivePlay
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

    /** A fake speaker: `got` = everything ever written, `buffer` = what is in it now (flush empties it), `played` set by the test. */
    class Out : LivePlay.Out {
        val got = mutableListOf<Short>()
        val buffer = mutableListOf<Short>()
        var room = Int.MAX_VALUE
        var played = 0L
        var flushes = 0
        override fun writeSome(pcm: ShortArray, off: Int, n: Int): Int {
            val w = minOf(n, room)
            for (i in off until off + w) {
                got.add(pcm[i])
                buffer.add(pcm[i])
            }
            return w
        }

        override fun played() = played
        override fun flush() {
            flushes++
            buffer.clear()
            played = 0
        }
    }

    private inner class Host : LiveRead.Host {
        val queue = ArrayDeque<() -> Unit>()
        val started = mutableListOf<Int>()
        val done = mutableListOf<Int>()
        val rest = mutableListOf<Pair<String, Boolean>>()
        val fallbacks = mutableListOf<Pair<String, Boolean>>()
        val devices = mutableListOf<String>()
        val logs = mutableListOf<String>()
        override fun post(block: () -> Unit) {
            queue.addLast(block)
        }

        fun run() {
            while (queue.isNotEmpty()) queue.removeFirst()()
        }

        override fun started(id: Int) {
            started.add(id)
        }

        override fun done(id: Int) {
            done.add(id)
        }

        override fun rest(why: String, brokeOff: Boolean) {
            rest.add(why to brokeOff)
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
    private val out = Out()
    private val read = LiveRead(opener, host, out, thread = false) { clock }

    /** the player thread's work, then the view model's */
    private fun pump() {
        read.play.pump()
        host.run()
    }

    private fun gw(m: In, s: Sock = socks.last()) {
        s.events.message(m)
        pump()
    }

    private fun audio(vararg v: Short, s: Sock = socks.last()) {
        s.events.audio(shortArrayOf(*v))
        pump()
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
        pump()
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
        assertEquals(listOf<Short>(1, 2, 3), out.got)
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
        assertEquals(listOf<Short>(1), out.got)
        assertEquals(3, read.say("셋"))
        gw(In.Start(3))
        audio(4)
        assertEquals(listOf(1, 3), host.started)
        assertEquals(listOf<Short>(1, 4), out.got)
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
        assertEquals(listOf("skipped_limit" to false), host.rest)
        assertTrue(read.usable)
    }

    @Test
    fun aDropMidReplySendsTheRestToHttpAndTheNextSayReopens() {
        opened()
        read.say("하나")
        gw(In.Start(1))
        drop(null, -1)
        assertEquals("the sentence being read broke off", listOf("closed_network" to true), host.rest)
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
            val r = LiveRead(opener, h, Out(), thread = false) { clock }
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
        val r = LiveRead(opener, h, Out(), thread = false) { clock }
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
        assertTrue(out.got.isEmpty())
        assertTrue(host.rest.isEmpty())
    }

    @Test
    fun gapsAreCountedWhileMoreIsExpected() {
        opened()
        read.prepare()
        read.say("하나")
        gw(In.Start(1))
        audio(*ShortArray(2400) { 1 })
        read.tick(more = true, active = true)
        out.played = 2400
        read.tick(more = true, active = true)
        clock += 200
        read.tick(more = true, active = true)
        audio(*ShortArray(2400) { 1 })
        read.tick(more = true, active = true)
        read.tick(more = false, active = true)
        read.replyDone()
        assertTrue(host.logs.toString(), host.logs.contains("live speak gaps=1 gap_ms_max=200"))
    }

    /** v1.2 (a): `skipped` after `start` of the same sentence = it broke off mid-way: not re-read, D7, the rest goes on */
    @Test
    fun aSkippedAfterStartIsABrokenSentence() {
        opened()
        read.say("하나")
        read.say("둘")
        read.say("셋")
        gw(In.Start(1))
        gw(In.Done(1, 2))
        gw(In.Start(2))
        audio(5, 6)
        gw(In.Skipped(2, "upstream"))
        assertEquals(listOf("skipped_upstream" to true), host.rest)
        assertEquals(listOf(1, 2), host.started)
    }

    /** The audio already received plays first; the 'broke' marker reaches the view model after it. */
    @Test
    fun aDropMidReplyWaitsForTheAudioAlreadyReceived() {
        opened()
        read.say("하나")
        gw(In.Start(1))
        out.room = 0
        audio(1, 2, 3)
        socks.last().events.closed(LiveWire.Close(null, -1, false))
        host.run()
        pump()
        assertTrue("the speaker is full: the marker waits behind the audio", host.rest.isEmpty())
        out.room = Int.MAX_VALUE
        pump()
        assertEquals(listOf<Short>(1, 2, 3), out.got)
        assertEquals(listOf("closed_network" to true), host.rest)
    }

    @Test
    fun theWatchdogEndsASentenceWhoseDoneNeverComes() {
        opened()
        read.say("하나")
        read.say("둘")
        gw(In.Start(1))
        audio(1, 2)
        out.played = 2
        read.tick(more = true, active = true)
        clock += 9_999
        read.tick(more = true, active = true)
        assertTrue(host.rest.isEmpty())
        clock += 1
        read.tick(more = true, active = true)
        assertTrue(host.logs.contains("live speak watchdog"))
        assertEquals(listOf("watchdog" to true), host.rest)
        assertEquals("stop", socks.last().sent.last())
        assertTrue("the speaker was emptied", out.flushes >= 1)
    }

    @Test
    fun noWatchdogWhenNothingIsGoingOn() {
        opened()
        repeat(5) {
            clock += 10_000
            read.tick(more = false, active = false)
        }
        assertTrue(host.rest.isEmpty())
    }
}
