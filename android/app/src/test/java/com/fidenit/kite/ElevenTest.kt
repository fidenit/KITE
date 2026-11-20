package com.fidenit.kite

import com.fidenit.kite.voice.Eleven
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Transcription against a fake ElevenLabs server (never the real api.elevenlabs.io). */
class ElevenTest {
    private lateinit var server: MockWebServer
    private val real = Eleven.api

    @Before
    fun up() {
        server = MockWebServer().apply { start() }
        Eleven.api = server.url("/").toString().trimEnd('/')
    }

    @After
    fun down() {
        Eleven.api = real
        server.shutdown()
    }

    private val pcm = ShortArray(16000) { (it % 100).toShort() }

    private fun failure(): String? = try {
        Eleven.transcribe("test-key-not-real", pcm, false)
        null
    } catch (e: Exception) {
        e.message
    }

    /** A speaker that only counts what it was handed. */
    private class FakeSink : Eleven.Sink {
        var samples = 0

        override fun push(pcm: ShortArray, n: Int) {
            samples += n
        }

        override fun pendingSecs(): Float = 0f

        override fun stop() {}
    }

    private fun speakFailure(sink: Eleven.Sink): Eleven.Failure? = try {
        Eleven.speak("test-key-not-real", "", "안녕하세요", sink) { false }
        null
    } catch (e: Eleven.Failure) {
        e
    }

    @Test
    fun aSentenceThatArrivesWholeIsHandedToTheSpeaker() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("a".repeat(9600)))
        val sink = FakeSink()
        assertNull(speakFailure(sink))
        assertEquals(4800, sink.samples)
        assertEquals("test-key-not-real", server.takeRequest(1, TimeUnit.SECONDS)!!.getHeader("xi-api-key"))
    }

    /** V1: the stream drops in the middle of a sentence: the failure says that sound of this sentence was already handed to the speaker. */
    @Test
    fun aStreamThatDropsMidSentenceSaysThatSoundWasAlreadyPlayed() {
        server.enqueue(MockResponse().setResponseCode(200).setChunkedBody("a".repeat(96000), 4800).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        val sink = FakeSink()
        val f = speakFailure(sink)!!
        assertTrue("some sound was handed over before the drop", sink.samples > 0)
        assertTrue(f.played)
        assertEquals(Eleven.Why.NETWORK, f.why)
    }

    /** A failure before any sound says that nothing was played, and carries its cause as a value. */
    @Test
    fun aFailureBeforeAnySoundSaysSoAndCarriesItsCause() {
        val cases = listOf(
            Triple(401, """{"detail":{"status":"invalid_api_key","message":"Invalid API key"}}""", Eleven.Why.KEY),
            Triple(401, """{"detail":{"status":"quota_exceeded","message":"This request exceeds your quota."}}""", Eleven.Why.LIMIT),
            Triple(403, "", Eleven.Why.KEY),
            Triple(429, """{"detail":{"status":"too_many_concurrent_requests"}}""", Eleven.Why.LIMIT),
            Triple(404, """{"detail":"Not Found"}""", Eleven.Why.OTHER),
            Triple(500, "busy", Eleven.Why.OTHER),
        )
        for ((code, body, why) in cases) {
            server.enqueue(MockResponse().setResponseCode(code).setBody(body))
            val sink = FakeSink()
            val f = speakFailure(sink)!!
            assertEquals("HTTP $code $body", why, f.why)
            assertFalse(f.played)
            assertEquals(0, sink.samples)
        }
        // nobody answers at that address
        val dead = MockWebServer().apply { start() }
        Eleven.api = dead.url("/").toString().trimEnd('/')
        dead.shutdown()
        val f = speakFailure(FakeSink())!!
        assertEquals(Eleven.Why.NETWORK, f.why)
        assertFalse(f.played)
    }

    /** V3: a failed transcription carries its cause as a value (the text with the status number is only for the record). */
    @Test
    fun aFailedTranscriptionCarriesItsCause() {
        fun cause(): Eleven.Why = try {
            Eleven.transcribe("test-key-not-real", pcm, false)
            throw AssertionError("no failure")
        } catch (e: Eleven.Failure) {
            assertFalse(e.played)
            e.why
        }
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":{"status":"invalid_api_key"}}"""))
        assertEquals(Eleven.Why.KEY, cause())
        server.enqueue(MockResponse().setResponseCode(429).setBody("slow down"))
        assertEquals(Eleven.Why.LIMIT, cause())
        server.enqueue(MockResponse().setResponseCode(500).setBody("busy"))
        server.enqueue(MockResponse().setResponseCode(502).setBody("still busy"))
        assertEquals(Eleven.Why.OTHER, cause())
        val dead = MockWebServer().apply { start() }
        Eleven.api = dead.url("/").toString().trimEnd('/')
        dead.shutdown()
        assertEquals(Eleven.Why.NETWORK, cause())
    }

    /** S1: sound arrived but the phone could not play it: that is its own cause, and nothing counts as played. */
    @Test
    fun aSpeakerThatCannotPlayIsItsOwnCause() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("a".repeat(9600)))
        val f = speakFailure(object : Eleven.Sink {
            override fun push(pcm: ShortArray, n: Int) {
                throw IllegalStateException("no speaker")
            }

            override fun pendingSecs(): Float = 0f

            override fun stop() {}
        })!!
        assertEquals(Eleven.Why.SPEAKER, f.why)
        assertFalse(f.played)
    }

    @Test
    fun theDefaultAddressIsHttps() {
        assertEquals("https://api.elevenlabs.io", real)
    }

    @Test
    fun aGoodAnswerIsReadAndSentWithTheKey() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"text":" 안녕하세요 "}"""))
        assertEquals("안녕하세요", Eleven.transcribe("test-key-not-real", pcm, false))
        val r = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/v1/speech-to-text", r.path)
        assertEquals("test-key-not-real", r.getHeader("xi-api-key"))
        assertTrue(r.body.readUtf8().contains("scribe_v3"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aRefusedKeyOrLimitEndsAtOnceWithTheFirstErrorText() {
        for (code in listOf(401, 403, 429)) {
            val s = MockWebServer().apply { start() }
            try {
                Eleven.api = s.url("/").toString().trimEnd('/')
                s.enqueue(MockResponse().setResponseCode(code).setBody("first answer $code"))
                s.enqueue(MockResponse().setResponseCode(500).setBody("second answer"))
                val m = failure()!!
                // 업로드를 흘려 보내는 중에 거절되면 응답 본문은 읽을 수 없다: 처음 오류의 상태 번호가 글에 있어야 하고, 둘째 응답의 글은 없어야 한다
                assertTrue(m, m.startsWith("HTTP $code") && !m.contains("second"))
                assertEquals("HTTP $code: one request only (no second model)", 1, s.requestCount)
            } finally {
                s.shutdown()
            }
        }
    }

    @Test
    fun anotherFailureTriesTheSecondModelAndTheSecondCanSucceed() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("busy"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"text":"됐어요"}"""))
        assertEquals("됐어요", Eleven.transcribe("k", pcm, false))
        assertEquals(2, server.requestCount)
        assertTrue(server.takeRequest(1, TimeUnit.SECONDS)!!.body.readUtf8().contains("scribe_v3"))
        assertTrue(server.takeRequest(1, TimeUnit.SECONDS)!!.body.readUtf8().contains("scribe_v2"))
    }

    @Test
    fun whenBothFailTheFirstErrorTextIsKept() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("first problem"))
        server.enqueue(MockResponse().setResponseCode(502).setBody("second problem"))
        val m = failure()!!
        assertTrue(m, m.startsWith("HTTP 500") && m.contains("first problem") && !m.contains("second"))
        assertEquals(2, server.requestCount)
    }
}
