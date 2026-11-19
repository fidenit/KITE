package com.fidenit.kite

import com.fidenit.kite.voice.Eleven
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
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
