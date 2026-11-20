package com.fidenit.kite

import com.fidenit.kite.voice.LivePlay
import com.fidenit.kite.voice.LiveRead
import com.fidenit.kite.voice.LiveWire
import com.fidenit.kite.voice.OkLiveSpeak
import com.fidenit.kite.voice.liveClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** The realtime playback queue (spec v1.2 (d)): the socket thread never blocks on the speaker; audio of a stopped reply never plays. JVM only. */
class LivePlayTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    /** A speaker whose write blocks until the test releases it (like a full AudioTrack in blocking mode). `buffer` = what is in it now. */
    private class Blocking : LivePlay.Out {
        val gate = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val buffer = java.util.Collections.synchronizedList(mutableListOf<Short>())

        @Volatile
        var blocking = true
        override fun writeSome(pcm: ShortArray, off: Int, n: Int): Int {
            if (blocking) {
                entered.countDown()
                gate.await(20, TimeUnit.SECONDS)
            }
            for (i in off until off + n) buffer.add(pcm[i])
            return n
        }

        override fun played() = 0L
        override fun flush() {
            buffer.clear()
        }
    }

    private class Ev : LivePlay.Events {
        val got = LinkedBlockingQueue<String>()
        override fun start(gen: Int, id: Int) = got.add("start $id").let {}
        override fun done(gen: Int, id: Int) = got.add("done $id").let {}
        override fun skipped(gen: Int, id: Int, error: String) = got.add("skipped $id").let {}
        override fun broke(gen: Int, why: String) = got.add("broke").let {}
        override fun overflow(gen: Int) = got.add("overflow").let {}
    }

    @Test
    fun audioOfAStoppedReplyNeverStaysInTheSpeakerEvenIfAWriteWasBlocked() {
        val out = Blocking()
        val ev = Ev()
        val p = LivePlay(out, ev)
        p.start(1)
        assertTrue(p.audio(1, ShortArray(4800) { 7 })) // the socket thread returns at once
        assertTrue(out.entered.await(5, TimeUnit.SECONDS))
        p.cut(1) // stop while the player thread is inside a write
        out.blocking = false
        out.gate.countDown()
        p.start(2)
        p.audio(2, ShortArray(960) { 9 })
        val end = System.currentTimeMillis() + 5_000
        while (out.buffer.size < 960 && System.currentTimeMillis() < end) Thread.sleep(10)
        Thread.sleep(100)
        assertEquals("only the new reply is in the speaker", List<Short>(960) { 9 }, out.buffer.toList())
        p.release()
    }

    @Test
    fun aStoppedIdIsDroppedEvenWhenItArrivesAfterTheStop() {
        val out = LiveReadTest.Out()
        val ev = Ev()
        val p = LivePlay(out, ev, thread = false)
        p.start(1)
        p.cut(2)
        p.audio(1, shortArrayOf(1))
        p.done(1)
        p.start(2)
        p.audio(2, shortArrayOf(2))
        p.start(3)
        p.audio(3, shortArrayOf(3))
        p.pump()
        assertEquals(listOf<Short>(3), out.got)
        assertEquals("start 3", ev.got.poll())
        assertTrue(ev.got.isEmpty())
    }

    @Test
    fun slicesAreAtMost40msAndOverflowIsReported() {
        val sizes = mutableListOf<Int>()
        val out = object : LivePlay.Out {
            override fun writeSome(pcm: ShortArray, off: Int, n: Int): Int = n.also { sizes.add(it) }
            override fun played() = 0L
            override fun flush() {}
        }
        val ev = Ev()
        val p = LivePlay(out, ev, maxSamples = 3000, thread = false)
        p.start(1)
        assertTrue(p.audio(1, ShortArray(2400)))
        assertFalse(p.audio(1, ShortArray(1000)))
        assertTrue(ev.got.contains("overflow"))
        p.pump()
        assertEquals(listOf(960, 960, 480), sizes)
    }

    /** The app's own speak socket with a speaker that blocks: the socket thread still reads `stopped` and the close (the same reader answers pings). */
    @Test
    fun aBlockedSpeakerDoesNotStallTheSocketReader() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text.contains("\"say\"")) {
                        webSocket.send("""{"type":"start","id":1}""")
                        repeat(50) { webSocket.send(LiveWire.pcmBytes(ShortArray(4800) { 3 }, 4800).toByteString()) } // 10 s of audio at once
                        webSocket.send("""{"type":"stopped","upto":1}""")
                        webSocket.close(4502, "upstream")
                    }
                }

                override fun onOpen(webSocket: WebSocket, response: Response) {}
            }))
            val out = Blocking()
            val logs = LinkedBlockingQueue<String>()
            val work = LinkedBlockingQueue<() -> Unit>()
            val read = LiveRead(OkLiveSpeak(liveClient(), server.url("/").toString(), { "s" }, ""), object : LiveRead.Host {
                override fun post(block: () -> Unit) = work.add(block).let {}
                override fun started(id: Int) {}
                override fun done(id: Int) {}
                override fun rest(why: String, brokeOff: Boolean) = logs.add("rest $why").let {}
                override fun fallback(why: String, notice: Boolean) {}
                override fun device(word: String) {}
                override fun log(line: String) = logs.add(line).let {}
            }, out)
            read.open()
            read.say("길게 읽을 문장")
            val end = System.currentTimeMillis() + 15_000
            var stopped = false
            var closed = false
            while ((!stopped || !closed) && System.currentTimeMillis() < end) {
                work.poll(50, TimeUnit.MILLISECONDS)?.invoke()
                while (true) {
                    val l = logs.poll() ?: break
                    if (l.startsWith("live speak stopped")) stopped = true
                    if (l.startsWith("live speak close=code4502")) closed = true
                }
            }
            assertTrue("the speaker is still blocked", out.gate.count == 1L)
            assertTrue("stopped read while the speaker was blocked", stopped)
            assertTrue("close read while the speaker was blocked", closed)
            out.gate.countDown()
            read.release()
        } finally {
            try {
                server.shutdown()
            } catch (_: Exception) {
            }
        }
    }
}
