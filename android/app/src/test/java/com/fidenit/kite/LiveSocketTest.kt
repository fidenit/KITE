package com.fidenit.kite

import com.fidenit.kite.voice.LiveEvents
import com.fidenit.kite.voice.LiveTalk
import com.fidenit.kite.voice.LiveWire
import com.fidenit.kite.voice.LiveWire.In
import com.fidenit.kite.voice.OkLiveListen
import com.fidenit.kite.voice.OkLiveSpeak
import com.fidenit.kite.voice.liveClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** The app's own realtime sockets against a fake gateway (OkHttp MockWebServer WebSocket). JVM only, loopback only. */
class LiveSocketTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    private val server = MockWebServer()
    private val client = liveClient()

    @Before
    fun up() = server.start()

    @After
    fun down() {
        try {
            server.shutdown()
        } catch (_: Exception) {
        }
    }

    private val gateway: String get() = server.url("/").toString()

    /** What the phone's socket reported. */
    private class Seen : LiveEvents {
        val q = LinkedBlockingQueue<Any>()
        override fun opened(ms: Long) {
            q.add("open")
        }

        override fun message(m: In) {
            q.add(m)
        }

        override fun audio(pcm: ShortArray) {
            q.add(pcm)
        }

        override fun closed(c: LiveWire.Close) {
            q.add(c)
        }

        fun next(): Any = q.poll(10, TimeUnit.SECONDS) ?: error("nothing from the socket")
    }

    /** The fake gateway's side of one socket: what the phone sent. */
    private open class Gw : WebSocketListener() {
        val got = LinkedBlockingQueue<Any>()
        @Volatile
        var ws: WebSocket? = null
        override fun onOpen(webSocket: WebSocket, response: Response) {
            ws = webSocket
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            got.add(JSONObject(text).getString("type"))
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            got.add(bytes.toByteArray())
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            got.add("close $code")
            webSocket.close(1000, null)
        }

        fun next(): Any = got.poll(10, TimeUnit.SECONDS) ?: error("nothing from the phone")
    }

    @Test
    fun listenSendsHeadersAndPcmAndReadsFramesThenEndCloses() {
        val gw = object : Gw() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                super.onOpen(webSocket, response)
                webSocket.send("""{"type":"ready"}""")
                webSocket.send("""{"type":"alignment","x":1}""")
                webSocket.send("""{"type":"partial","text":"안녕"}""")
                webSocket.send("""{"type":"final","text":"안녕하세요","lang":"kor","new":true}""")
            }
        }
        server.enqueue(MockResponse().withWebSocketUpgrade(gw))
        val seen = Seen()
        val l = OkLiveListen(client, gateway, { "dev-secret-test" }, "0.5.0-pro").open(true, seen)
        assertEquals("open", seen.next())
        assertEquals(In.Ready(), seen.next())
        assertEquals("unknown type skipped", In.Partial("안녕"), seen.next())
        assertEquals(In.Final("안녕하세요", "kor"), seen.next())
        val req = server.takeRequest()
        assertEquals("/v1/voice/live/listen?lang=eng", req.path)
        assertEquals("Bearer dev-secret-test", req.getHeader("Authorization"))
        assertEquals("0.5.0-pro", req.getHeader("X-Aperit-App"))
        assertTrue(l.audio(shortArrayOf(1, -2, 300), 3))
        assertArrayEquals(byteArrayOf(1, 0, -2, -1, 44, 1), gw.next() as ByteArray)
        l.commit()
        assertEquals("commit", gw.next())
        l.end()
        assertEquals("end", gw.next())
        assertEquals("close 1000", gw.next())
        val c = seen.next() as LiveWire.Close
        assertTrue(c.byUs)
        assertFalse("nothing sent after close", l.audio(shortArrayOf(1), 1))
    }

    @Test
    fun anErrorFrameAndCloseCodeReachThePhoneAsOneClose() {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : Gw() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("""{"type":"error","error":"voice_off"}""")
                webSocket.close(4503, "voice_off")
            }
        }))
        val seen = Seen()
        OkLiveListen(client, gateway, { "s" }, "").open(false, seen)
        assertEquals("open", seen.next())
        assertEquals(In.Error("voice_off"), seen.next())
        val c = seen.next() as LiveWire.Close
        assertEquals("voice_off", c.word)
        assertEquals(4503, c.code)
        assertFalse(c.byUs)
        assertEquals(LiveWire.Fault.OFF, c.fault)
        assertEquals("closed exactly once", null, seen.q.poll(500, TimeUnit.MILLISECONDS))
    }

    @Test
    fun aGatewayThatCannotBeReachedIsACloseWithCodeMinusOne() {
        val url = gateway
        server.shutdown()
        val seen = Seen()
        OkLiveListen(client, url, { "s" }, "").open(false, seen)
        val c = seen.next() as LiveWire.Close
        assertEquals(-1, c.code)
        assertEquals(LiveWire.Fault.NETWORK, c.fault)
    }

    @Test
    fun speakSendsPrepareSayStopAndReadsAudioBetweenStartAndDone() {
        val gw = object : Gw() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                super.onMessage(webSocket, text)
                val j = JSONObject(text)
                if (j.getString("type") == "say") {
                    val id = j.getInt("id")
                    webSocket.send("""{"type":"start","id":$id}""")
                    webSocket.send(LiveWire.pcmBytes(shortArrayOf(7, -7), 2).toByteString())
                    webSocket.send("""{"type":"done","id":$id,"chars":5}""")
                }
                if (j.getString("type") == "stop") webSocket.send("""{"type":"stopped","upto":1}""")
            }
        }
        server.enqueue(MockResponse().withWebSocketUpgrade(gw))
        val seen = Seen()
        val sp = OkLiveSpeak(client, gateway, { "s" }, "app").open(seen)
        assertEquals("open", seen.next())
        assertEquals("/v1/voice/live/speak", server.takeRequest().path)
        assertTrue(sp.prepare())
        assertEquals("prepare", gw.next())
        assertTrue(sp.say(1, "안녕하세요"))
        assertEquals("say", gw.next())
        assertEquals(In.Start(1), seen.next())
        assertArrayEquals(shortArrayOf(7, -7), seen.next() as ShortArray)
        assertEquals(In.Done(1, 5), seen.next())
        assertTrue(sp.stop())
        assertEquals("stop", gw.next())
        assertEquals(In.Stopped(1), seen.next())
        sp.end()
        assertEquals("end", gw.next())
    }

    /** The listen flow end to end: the app's LiveTalk over the app's own socket against the fake gateway. */
    @Test
    fun liveTalkOverTheRealSocketShowsPartialsAndSendsTheFinalOnce() {
        val gw = object : Gw() {
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                super.onMessage(webSocket, bytes)
                if (got.size == 1) {
                    webSocket.send("""{"type":"partial","text":"내일 일정"}""")
                    webSocket.send("""{"type":"final","text":"내일 일정 알려 줘","lang":"kor"}""")
                    webSocket.send("""{"type":"final","text":"","lang":""}""")
                }
            }

            override fun onOpen(webSocket: WebSocket, response: Response) {
                super.onOpen(webSocket, response)
                webSocket.send("""{"type":"ready"}""")
            }
        }
        server.enqueue(MockResponse().withWebSocketUpgrade(gw))
        val work = LinkedBlockingQueue<() -> Unit>()
        val tails = mutableListOf<String?>()
        val sent = mutableListOf<String>()
        val host = object : LiveTalk.Host {
            override val english = false
            override val busy = false
            override val speaking = false
            override val reading: String? = null
            override val spoken = emptyList<String>()
            override fun post(block: () -> Unit) {
                work.add(block)
            }

            override fun heard(tail: String?) {
                tails.add(tail)
            }

            override fun final(text: String) {
                sent.add(text)
            }

            override fun hush() {}
            override fun barge() {}
            override fun fallback(why: String, notice: Boolean) {}
            override fun limit() {}
            override fun device(word: String) {}
            override fun log(line: String) {}
        }
        val talk = LiveTalk(OkLiveListen(client, gateway, { "s" }, ""), host)
        talk.start()
        val end = System.currentTimeMillis() + 10_000
        var framed = false
        while (sent.isEmpty() || tails.size < 2) {
            check(System.currentTimeMillis() < end) { "timed out" }
            work.poll(50, TimeUnit.MILLISECONDS)?.invoke()
            if (!framed && talk.listening) {
                talk.frame(ShortArray(320) { 3000 }, 320) // real sound, so the final counts (D10)
                framed = true
            }
        }
        while (true) work.poll(300, TimeUnit.MILLISECONDS)?.invoke() ?: break
        assertEquals(listOf("내일 일정", null), tails.take(2))
        assertEquals(listOf("내일 일정 알려 줘"), sent)
        talk.stop()
        assertEquals(320 * 2, (gw.next() as ByteArray).size)
        assertEquals("end", gw.next())
        assertNotNull(gw.ws)
    }
}
