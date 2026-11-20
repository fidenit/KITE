package com.fidenit.kite

import com.fidenit.kite.engine.Enroller
import com.fidenit.kite.voice.Eleven
import com.fidenit.kite.voice.ProVoice
import com.fidenit.kite.voice.Speaking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.util.concurrent.TimeUnit

/** Pro voice through the gateway (spec 10.1/10.2, texts D): a fake gateway only (never the real gateway or api.elevenlabs.io). */
class ProVoiceTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    private lateinit var server: MockWebServer
    private val secret = "apd_test-secret-not-real"

    @Before
    fun up() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun down() = server.shutdown()

    private fun target(base: String = server.url("/").toString()) = Eleven.Target.pro(base, secret, "0.3.0-pro")

    private val pcm = ShortArray(16000) { (it % 100).toShort() }

    private class Sink : Eleven.Sink {
        var samples = 0

        override fun push(pcm: ShortArray, n: Int) {
            samples += n
        }

        override fun pendingSecs(): Float = 0f

        override fun stop() {}
    }

    private fun take(): RecordedRequest = server.takeRequest(1, TimeUnit.SECONDS)!!

    private fun assertProHeaders(r: RecordedRequest) {
        assertEquals("Bearer $secret", r.getHeader("Authorization"))
        assertEquals("0.3.0-pro", r.getHeader("X-Aperit-App"))
        assertNull("no ElevenLabs key header in pro", r.getHeader("xi-api-key"))
    }

    @Test
    fun theProTargetSendsTheBearerAndTheAppNoKeyAndHitsTheVoicePaths() {
        for (base in listOf(server.url("/").toString(), server.url("/").toString().trimEnd('/'))) {
            server.enqueue(MockResponse().setResponseCode(200).setBody("a".repeat(9600)))
            val sink = Sink()
            Eleven.speak(target(base), "", "안녕하세요", sink, { false })
            assertEquals(4800, sink.samples)
            val tts = take()
            assertEquals("POST", tts.method)
            assertEquals("/v1/voice/text-to-speech/${Eleven.DEFAULT_VOICE}/stream?output_format=pcm_24000", tts.path)
            assertProHeaders(tts)
            val j = JSONObject(tts.body.readUtf8())
            assertEquals("안녕하세요", j.getString("text"))
            assertEquals(Eleven.TTS_MODEL, j.getString("model_id"))

            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"text":" 안녕하세요 "}"""))
            assertEquals("안녕하세요", Eleven.transcribe(target(base), pcm, false))
            val stt = take()
            assertEquals("/v1/voice/speech-to-text", stt.path)
            assertProHeaders(stt)
            assertTrue(stt.getHeader("Content-Type")!!.startsWith("multipart/form-data; boundary="))
            val body = stt.body.readUtf8()
            for (f in listOf("model_id" to "scribe_v3", "file_format" to "pcm_s16le_16", "tag_audio_events" to "false", "language_code" to "kor")) {
                assertTrue(f.first, body.contains("name=\"${f.first}\"\r\n\r\n${f.second}\r\n"))
            }
            assertEquals(1, Regex("name=\"file\"").findAll(body).count())
        }
    }

    @Test
    fun theFreeTargetIsTodaysCall() {
        val real = Eleven.api
        try {
            Eleven.api = server.url("/").toString().trimEnd('/')
            server.enqueue(MockResponse().setResponseCode(200).setBody("aa"))
            Eleven.speak(Eleven.Target.free("test-key-not-real"), "v1234567890", "x", Sink(), { false })
            val r = take()
            assertEquals("/v1/text-to-speech/v1234567890/stream?output_format=pcm_24000", r.path)
            assertEquals("test-key-not-real", r.getHeader("xi-api-key"))
            assertNull(r.getHeader("Authorization"))
            assertNull(r.getHeader("X-Aperit-App"))
        } finally {
            Eleven.api = real
        }
    }

    /** (status, body) -> (gate, why, device kind, device code) */
    private val table = listOf(
        Triple(503, """{"error":"voice_off"}""", listOf(Eleven.Gate.OFF, Eleven.Why.OTHER, "", "")),
        Triple(502, """{"error":"voice_key"}""", listOf(Eleven.Gate.KEY, Eleven.Why.OTHER, "", "")),
        Triple(429, """{"error":"voice_limit"}""", listOf(Eleven.Gate.LIMIT, Eleven.Why.LIMIT, "", "")),
        Triple(429, """{"error":"rate_limited"}""", listOf(Eleven.Gate.RATE, Eleven.Why.LIMIT, "", "")),
        Triple(502, """{"error":"voice_refused","upstream":500}""", listOf(Eleven.Gate.REFUSED, Eleven.Why.OTHER, "", "")),
        Triple(502, """{"error":"voice_unreachable"}""", listOf(Eleven.Gate.UNREACHABLE, Eleven.Why.NETWORK, "", "")),
        Triple(413, """{"error":"too_large"}""", listOf(Eleven.Gate.TOO_LARGE, Eleven.Why.OTHER, "", "")),
        Triple(400, """{"error":"bad_request"}""", listOf(Eleven.Gate.BAD_REQUEST, Eleven.Why.OTHER, "", "")),
        Triple(401, """{"error":"unknown"}""", listOf(Eleven.Gate.DEVICE, Eleven.Why.OTHER, "unknown", "")),
        Triple(403, """{"error":"pending","code":"482913"}""", listOf(Eleven.Gate.DEVICE, Eleven.Why.OTHER, "pending", "482913")),
        Triple(403, """{"error":"revoked"}""", listOf(Eleven.Gate.DEVICE, Eleven.Why.OTHER, "revoked", "")),
        Triple(403, """{"error":"role_not_supported"}""", listOf(Eleven.Gate.DEVICE, Eleven.Why.OTHER, "role_not_supported", "")),
        // D-A4: a 5xx that is not the gateway's JSON (a proxy page) is a connection problem (D5a/D5b), not D1/D3
        Triple(502, "<html>bad gateway</html>", listOf(Eleven.Gate.UNREACHABLE, Eleven.Why.NETWORK, "", "")),
        Triple(504, "", listOf(Eleven.Gate.UNREACHABLE, Eleven.Why.NETWORK, "", "")),
        Triple(404, "<html>not found</html>", listOf(Eleven.Gate.OTHER, Eleven.Why.OTHER, "", "")),
    )

    private fun speakFailure(): Eleven.Failure = try {
        Eleven.speak(target(), "", "안녕하세요", Sink(), { false })
        throw AssertionError("no failure")
    } catch (e: Eleven.Failure) {
        e
    }

    private fun hearFailure(): Eleven.Failure = try {
        Eleven.transcribe(target(), pcm, false)
        throw AssertionError("no failure")
    } catch (e: Eleven.Failure) {
        e
    }

    private fun check(f: Eleven.Failure, want: List<Any>, label: String) {
        assertEquals(label, want[0], f.gate)
        assertEquals(label, want[1], f.why)
        assertEquals(label, want[2], f.deviceKind)
        assertEquals(label, want[3], f.deviceCode)
        assertFalse(label, f.played)
        assertFalse("the secret never goes into a failure text", f.message.orEmpty().contains(secret) || f.toString().contains(secret))
        assertFalse("no body text is carried", f.message.orEmpty().contains("<html>"))
    }

    @Test
    fun everyGatewayErrorBodyGivesItsReasonAndItsLine() {
        for ((code, body, want) in table) {
            val label = "$code $body"
            val before = server.requestCount
            server.enqueue(MockResponse().setResponseCode(code).setBody(body))
            val s = speakFailure()
            check(s, want, "speak $label")
            // two-model retry only where another model may help; the rest stop at once
            val retried = want[0] in setOf(Eleven.Gate.REFUSED, Eleven.Gate.BAD_REQUEST, Eleven.Gate.OTHER)
            server.enqueue(MockResponse().setResponseCode(code).setBody(body))
            if (retried) server.enqueue(MockResponse().setResponseCode(code).setBody(body))
            val h = hearFailure()
            check(h, want, "hear $label")
            assertEquals(label, if (retried) 3 else 2, server.requestCount - before)
            take()
            take()
            if (retried) assertTrue(label, take().body.readUtf8().contains("name=\"model_id\"\r\n\r\nscribe_v2\r\n"))

            for (en in listOf(false, true)) {
                val speakLine = ProVoice.speakNotice(Speaking.Outcome(s, false), en)
                val hearLine = ProVoice.heardNotice(h, en)
                when (want[0]) {
                    Eleven.Gate.LIMIT, Eleven.Gate.RATE -> {
                        assertEquals(label, ProVoice.speakLimit(en), speakLine)
                        assertEquals(label, ProVoice.hearLimit(en), hearLine)
                        assertTrue(label, ProVoice.stopsTalking(h))
                    }
                    Eleven.Gate.UNREACHABLE -> {
                        // D5a / D5b; listening goes on
                        assertEquals(label, ProVoice.unreachableSpeak(en), speakLine)
                        assertEquals(label, ProVoice.unreachableHear(en), hearLine)
                        assertFalse(label, ProVoice.stopsTalking(h))
                    }
                    else -> {
                        assertEquals(label, ProVoice.cannotSpeak(en), speakLine)
                        assertEquals(label, ProVoice.cannotHear(en), hearLine)
                        assertTrue(label, ProVoice.stopsTalking(h))
                    }
                }
            }
            // D-A2: a device refusal starts the status check with (kind, code); nothing else does
            assertEquals(label, if (want[0] == Eleven.Gate.DEVICE) want[2] to want[3] else null, ProVoice.deviceProblem(s))
        }
    }

    @Test
    fun aRefusedFirstModelIsTriedOnceMoreWithTheSecond() {
        server.enqueue(MockResponse().setResponseCode(502).setBody("""{"error":"voice_refused","upstream":422}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"text":"됐어요"}"""))
        assertEquals("됐어요", Eleven.transcribe(target(), pcm, true))
        assertTrue(take().body.readUtf8().contains("scribe_v3"))
        val second = take().body.readUtf8()
        assertTrue(second.contains("scribe_v2"))
        assertTrue(second.contains("name=\"language_code\"\r\n\r\neng\r\n"))
    }

    @Test
    fun aNetworkFailureIsD5aAndD5bAndKeepsListening() {
        val dead = MockWebServer().apply { start() }
        val base = dead.url("/").toString()
        dead.shutdown()
        val s = try {
            Eleven.speak(target(base), "", "x", Sink(), { false })
            null
        } catch (e: Eleven.Failure) {
            e
        }!!
        assertEquals(Eleven.Why.NETWORK, s.why)
        assertNull(s.gate)
        val h = try {
            Eleven.transcribe(target(base), pcm, false)
            null
        } catch (e: Eleven.Failure) {
            e
        }!!
        assertEquals(Eleven.Why.NETWORK, h.why)
        assertFalse(ProVoice.stopsTalking(h))
        for (en in listOf(false, true)) {
            assertEquals(ProVoice.unreachableSpeak(en), ProVoice.speakNotice(Speaking.Outcome(s, false), en))
            assertEquals(ProVoice.unreachableHear(en), ProVoice.heardNotice(h, en))
        }
    }

    @Test
    fun noProVoiceLineMentionsAKeyOrSettings() {
        val failures = Eleven.Gate.values().map { g -> Eleven.Failure(if (g == Eleven.Gate.UNREACHABLE) Eleven.Why.NETWORK else if (g == Eleven.Gate.LIMIT || g == Eleven.Gate.RATE) Eleven.Why.LIMIT else Eleven.Why.OTHER, false, "HTTP", gate = g) } +
            Eleven.Why.values().flatMap { w -> listOf(false, true).map { p -> Eleven.Failure(w, p, "x") } }
        val lines = mutableListOf<String>()
        for (en in listOf(false, true)) {
            for (f in failures) {
                for (phoneFailed in listOf(false, true)) ProVoice.speakNotice(Speaking.Outcome(f, phoneFailed), en)?.let { lines.add(it) }
                lines.add(ProVoice.heardNotice(f, en))
            }
            ProVoice.speakNotice(Speaking.Outcome(null, true), en)?.let { lines.add(it) }
            lines.add(ProVoice.heardNotice(RuntimeException("x"), en))
            lines.add(ProVoice.talkingOff(en))
        }
        assertTrue(lines.size > 100)
        // the one allowed mention of a settings screen: the PHONE's own text-to-speech setting, only in D6
        val phoneSetting = mapOf(false to "폰 설정의 '텍스트 음성 변환'", true to "'Text-to-speech' in the phone's settings")
        for (en in listOf(false, true)) assertTrue(ProVoice.noVoiceAtAll(en).contains(phoneSetting.getValue(en)))
        for (l in lines) {
            val d6 = l == ProVoice.noVoiceAtAll(false) || l == ProVoice.noVoiceAtAll(true)
            val rest = if (d6) l.replace(phoneSetting.getValue(false), "").replace(phoneSetting.getValue(true), "") else l
            for (bad in listOf("ElevenLabs", "키", "key", "Key", "인터넷", "internet", "설정", "Settings", "settings", "gateway", "게이트웨이", "HTTP", "voice_")) assertFalse("$bad in: $l", rest.contains(bad))
            assertEquals(l, 2, l.count { it == '→' })
        }
        assertNull(ProVoice.speakNotice(Speaking.Outcome(null, false), false))
    }

    @Test
    fun whenThePhoneVoiceAlsoFailsTheLineIsD6() {
        val f = Eleven.Failure(Eleven.Why.OTHER, false, "HTTP 503 voice_off", gate = Eleven.Gate.OFF)
        for (en in listOf(false, true)) {
            for (why in Eleven.Why.values()) assertEquals("$why", ProVoice.noVoiceAtAll(en), ProVoice.speakNotice(Speaking.Outcome(Eleven.Failure(why, false, "x"), true), en))
            assertEquals(ProVoice.noVoiceAtAll(en), ProVoice.speakNotice(Speaking.Outcome(f, true), en))
            // D-A4: a later sentence of the same answer goes straight to the phone's voice; if that fails too: D6
            assertEquals(ProVoice.noVoiceAtAll(en), ProVoice.speakNotice(Speaking.Outcome(null, true), en))
        }
    }

    @Test
    fun aVoiceThatBrokeOffAfterSomeSoundIsD7NeverALineThatSaysThePhoneReadIt() {
        for (en in listOf(false, true)) for (why in Eleven.Why.values()) for (phoneFailed in listOf(false, true)) {
            assertEquals("$why", ProVoice.brokeOff(en), ProVoice.speakNotice(Speaking.Outcome(Eleven.Failure(why, true, "x"), phoneFailed), en))
        }
        // the stream breaks mid-sentence through the gateway: played = true -> D7
        server.enqueue(MockResponse().setResponseCode(200).setChunkedBody("a".repeat(96000), 4800).setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        val f = speakFailure()
        assertTrue(f.played)
        assertEquals(ProVoice.brokeOff(false), ProVoice.speakNotice(Speaking.Outcome(f, false), false))
    }

    @Test
    fun afterAServerVoiceFailureTheRestOfTheAnswerSkipsTheServer() {
        assertTrue(ProVoice.tryServer(voice = true, failedThisAnswer = false))
        assertFalse(ProVoice.tryServer(voice = true, failedThisAnswer = true))
        assertFalse(ProVoice.tryServer(voice = false, failedThisAnswer = false))
    }

    @Test
    fun theCapabilityTurningFalseEndsTheConversationWithD8() {
        assertTrue(ProVoice.endsConversation(voice = false, conversing = true))
        assertFalse(ProVoice.endsConversation(voice = false, conversing = false))
        assertFalse(ProVoice.endsConversation(voice = true, conversing = true))
    }

    @Test
    fun theCapabilityDrivesTalkingAndTheControls() {
        // pro: the server's voice decides, a key does not
        assertFalse(ProVoice.controlsShown(pro = true, voice = false))
        assertTrue(ProVoice.controlsShown(pro = true, voice = true))
        assertFalse(ProVoice.mayTalk(pro = true, voice = false, key = "sk_some-key"))
        assertTrue(ProVoice.mayTalk(pro = true, voice = true, key = ""))
        // free: always shown; talking needs the user's key exactly as before
        assertTrue(ProVoice.controlsShown(pro = false, voice = false))
        assertTrue(ProVoice.controlsShown(pro = false, voice = true))
        assertTrue(ProVoice.mayTalk(pro = false, voice = false, key = "k"))
        assertFalse(ProVoice.mayTalk(pro = false, voice = true, key = " "))
        assertFalse(ProVoice.mayTalk(pro = false, voice = true, key = ""))
    }

    private class Mem : DeviceStore {
        override var deviceId = ""
        override var deviceSecret = ""
        override var proStatus = ""
        override var proCode = ""
        override var ticketUsed = ""
        override var proVoice = ""
    }

    @Test
    fun theCapabilityIsTheLastMeAnswer() {
        val m = Mem()
        var clock = 1_000_000L
        val d = ProDevice(m, Enroller(server.url("/").toString()), { Triple("Pixel 8", "14", "0.3.0") }, "", { clock }, 0)
        server.enqueue(MockResponse().setBody("""{"status":"approved"}""")) // enroll: no voice field
        server.enqueue(MockResponse().setBody("""{"status":"approved","code":"","role":"owner","user":"o","voice":true,"jev":false}"""))
        server.enqueue(MockResponse().setBody("""{"status":"approved","voice":false}"""))
        server.enqueue(MockResponse().setBody("""{"status":"approved","voice":true}"""))
        server.enqueue(MockResponse().setResponseCode(401)) // forgotten: enroll again
        server.enqueue(MockResponse().setBody("""{"status":"pending","code":"111111"}"""))
        assertEquals("approved", d.check().status)
        assertFalse(d.voice)
        clock += 1
        d.check()
        assertTrue(d.voice)
        assertEquals("1", m.proVoice)
        clock += 1
        d.check()
        assertFalse(d.voice)
        clock += 1
        d.check()
        assertTrue(d.voice)
        clock += 1
        assertEquals("pending", d.check().status)
        assertFalse("a forgotten phone has no server voice", d.voice)
        assertEquals("/v1/enroll", take().path)
        repeat(4) { assertEquals("/v1/me", take().path) }
        assertEquals("/v1/enroll", take().path)
    }

    @Test
    fun anApprovalFromEnrollingAsksMeOnceMoreForTheVoice() {
        val m = Mem()
        var clock = 1_000_000L
        val d = ProDevice(m, Enroller(server.url("/").toString()), { Triple("Pixel 8", "14", "0.3.0") }, "tkt-1", { clock }, 0)
        server.enqueue(MockResponse().setBody("""{"status":"approved","ticket":"ok"}"""))
        server.enqueue(MockResponse().setBody("""{"status":"approved","voice":true}"""))
        assertEquals("approved", d.check().status)
        assertFalse(d.voice)
        assertTrue(d.voiceAfterApproval())
        assertTrue(d.voice)
        assertFalse("only once per approval", d.voiceAfterApproval())
        assertEquals("/v1/enroll", take().path)
        val me = take()
        assertEquals("/v1/me", me.path)
        assertEquals("Bearer ${m.deviceSecret}", me.getHeader("Authorization"))
        // approved through /v1/me (the waiting number was approved): that answer already had the voice, no extra call
        server.enqueue(MockResponse().setBody("""{"status":"approved","voice":false}"""))
        clock += 1
        d.check()
        assertFalse(d.voiceAfterApproval())
        assertEquals(3, server.requestCount)
    }

    @Test
    fun theReadLimitWaitsAtLeastAMinuteForTheFirstSoundAndTheTargetNeverShowsTheSecret() {
        assertTrue(Eleven.READ_MS >= 60_000)
        assertFalse(target().toString().contains(secret))
        // a slow first byte (a proxy that holds the audio back) still plays
        server.enqueue(MockResponse().setResponseCode(200).setHeadersDelay(1500, TimeUnit.MILLISECONDS).setBody("a".repeat(4800)))
        val sink = Sink()
        Eleven.speak(target(), "", "x", sink, { false })
        assertEquals(2400, sink.samples)
    }

    @Test
    fun aRedirectIsNotFollowedInPro() {
        val other = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", other.url("/elsewhere").toString()))
            val f = speakFailure()
            assertEquals(Eleven.Gate.OTHER, f.gate)
            assertEquals(0, other.requestCount)
        } finally {
            other.shutdown()
        }
    }

    @Test
    fun theLogPartHasNumbersAndWordsOnly() {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"pending","code":"482913"}"""))
        val w = ProVoice.logWhy(speakFailure())
        assertEquals("fail why=OTHER gate=DEVICE played=false HTTP 403 pending", w)
        assertEquals("ok", ProVoice.logWhy(null))
        assertFalse(w.contains(secret))
    }
}
