package com.fidenit.kite

import com.fidenit.kite.engine.ClaudeCode
import com.fidenit.kite.engine.DeviceKeys
import com.fidenit.kite.engine.Enroller
import com.fidenit.kite.engine.EngineConfig
import com.fidenit.kite.engine.Ev
import com.fidenit.kite.engine.GatewayLink
import com.fidenit.kite.engine.RemoteFail
import com.fidenit.kite.engine.STOPPED
import com.fidenit.kite.engine.SshFatal
import com.fidenit.kite.engine.readSse
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.TimeUnit

/** Pro 의 게이트웨이 길: 가짜 게이트웨이(MockWebServer)로 등록·따라 읽기·끊김·거절을 본다. JVM 만 (free 변형에서 돈다). */
class GatewayLinkTest {
    private val server = MockWebServer()
    private val tok = "apd_" + "T".repeat(43)

    @Before
    fun up() = server.start()

    @After
    fun down() = server.shutdown()

    private fun base() = server.url("/").toString()

    private fun link(effort: String = "low", en: Boolean = false) = GatewayLink(base(), { tok }, effort, { en })

    private fun sse(vararg data: String, crlf: Boolean = false): String {
        val nl = if (crlf) "\r\n" else "\n"
        return data.joinToString("") { "data: $it$nl$nl" }
    }

    private val init = """{"type":"system","subtype":"init","model":"m1"}"""
    private val delta = """{"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"say 안녕\n"}}}"""
    private val result = """{"type":"result","result":"ok","usage":{"input_tokens":3,"output_tokens":4},"total_cost_usd":0.01}"""

    // ------------------------------------------------------------ 등록

    @Test
    fun enrollStatesAndTheSecretNeverLeaves() {
        server.enqueue(MockResponse().setBody("""{"status":"approved"}"""))
        server.enqueue(MockResponse().setBody("""{"status":"pending","code":"4821"}"""))
        server.enqueue(MockResponse().setBody("""{"status":"revoked"}"""))
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":"conflict"}"""))
        server.enqueue(MockResponse().setResponseCode(429))
        val e = Enroller(base())
        assertEquals("approved", e.enroll("id-1", tok, "Pixel", "14", "0.2.0").status)
        val p = e.enroll("id-1", tok, "Pixel", "14", "0.2.0")
        assertEquals("pending", p.status)
        assertEquals("4821", p.code)
        assertEquals("revoked", e.enroll("id-1", tok, "Pixel", "14", "0.2.0").status)
        assertEquals("conflict", e.enroll("id-1", tok, "Pixel", "14", "0.2.0").status)
        assertEquals("limited", e.enroll("id-1", tok, "M".repeat(300), "14", "0.2.0").status)
        repeat(5) { i ->
            val r = server.takeRequest()
            assertEquals("POST", r.method)
            assertEquals("/v1/enroll", r.path)
            assertEquals(null, r.getHeader("Authorization"))
            val body = r.body.readUtf8()
            assertFalse("the secret itself is never sent", body.contains(tok))
            val j = JSONObject(body)
            assertEquals("id-1", j.getString("device_id"))
            assertEquals(DeviceKeys.sha256(tok), j.getString("token_sha256"))
            assertEquals(64, j.getString("token_sha256").length)
            assertTrue(j.getString("model").length <= 200)
            assertFalse(j.has("public_key"))
            if (i == 4) assertEquals(200, j.getString("model").length)
        }
    }

    @Test
    fun meSendsTheBearerAndUnknownIs401() {
        server.enqueue(MockResponse().setBody("""{"status":"approved","code":"","role":"owner","user":"owner","voice":false,"jev":false,"gateway":"1"}"""))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"unknown"}"""))
        val e = Enroller(base())
        val s = e.me(tok)
        assertEquals("approved", s.status)
        assertFalse(s.voice)
        assertFalse(s.jev)
        assertEquals("unknown", e.me(tok).status)
        val r = server.takeRequest()
        assertEquals("/v1/me", r.path)
        assertEquals("Bearer $tok", r.getHeader("Authorization"))
    }

    @Test
    fun keysHaveTheContractShape() {
        val s = DeviceKeys.newSecret()
        assertTrue(s, Regex("apd_[A-Za-z0-9_-]{43}").matches(s))
        assertTrue(DeviceKeys.newSecret() != s)
        assertTrue(Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}").matches(DeviceKeys.newId()))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", DeviceKeys.sha256("abc"))
    }

    // ------------------------------------------------------------ SSE

    @Test
    fun sseEventsSplitAcrossChunksArriveWholeAndInOrder() {
        for (crlf in listOf(false, true)) {
            val nl = if (crlf) "\r\n" else "\n"
            val body = "id: 1${nl}data: >$init$nl$nl: keep-alive$nl${nl}data: APERITBEAT$nl${nl}id: 2${nl}data: >$delta$nl$nl" + sse("APERITEXIT 0", crlf = crlf)
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body).throttleBody(3, 2, TimeUnit.MILLISECONDS))
            val got = mutableListOf<String>()
            val (rc, _) = link().follow("r-1", 5) { got.add(it) }
            assertEquals(0, rc)
            assertEquals(listOf(">$init", "APERITBEAT", ">$delta", "APERITEXIT 0"), got)
            val r = server.takeRequest()
            assertEquals("/v1/runs/r-1/follow?n=5", r.path)
            assertEquals("Bearer $tok", r.getHeader("Authorization"))
            assertEquals("text/event-stream", r.getHeader("Accept"))
        }
    }

    @Test
    fun sseParserRules() {
        val got = mutableListOf<String>()
        readSse(Buffer().writeUtf8("data: a\ndata: b\n\ndata:c\n\nevent: x\n\ndata: >half")) { got.add(it) }
        assertEquals(listOf("a\nb", "c"), got) // an event without data is not delivered; the half event at the end is dropped
    }

    @Test
    fun cutInTheMiddleOfAnEventDeliversOnlyWholeEventsAndCountsAsADrop() {
        val body = sse(">short") + "data: >" + "B".repeat(400) + "\n\n"
        server.enqueue(MockResponse().setBody(body).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        val got = mutableListOf<String>()
        val (rc, _) = link().follow("r-1", 0) { got.add(it) }
        assertTrue("a broken stream is a drop (negative), got $rc", rc < 0)
        assertEquals(listOf(">short"), got)
    }

    // ------------------------------------------------------------ 짧은 호출

    @Test
    fun shortCallsUseTheContractPathsAndCheckIdsFirst() {
        repeat(4) { server.enqueue(MockResponse().setResponseCode(204)) }
        server.enqueue(MockResponse().setBody("tail of err"))
        server.enqueue(MockResponse().setBody("core line\n"))
        server.enqueue(MockResponse().setBody("""{"claude":"2.1.328 (Claude Code)","dir":"/root","gateway":"1","time":"t"}"""))
        server.enqueue(MockResponse().setBody("""{"claude":"x","dir":"/root","gateway":"1","time":"t"}"""))
        val l = link()
        assertEquals(0 to "", l.upload("r-1", "turn 글"))
        l.stop("r-1")
        l.clean("r-1")
        l.prepare("system 글")
        assertEquals("tail of err", l.errTail("r-1"))
        assertEquals("core line\n", l.core())
        assertEquals("2.1.328 (Claude Code)", l.version())
        assertEquals("" to "/root", l.where())
        val want = listOf("PUT /v1/runs/r-1/input turn 글", "POST /v1/runs/r-1/stop ", "DELETE /v1/runs/r-1 ", "PUT /v1/system system 글", "GET /v1/runs/r-1/err ", "GET /v1/core ", "GET /v1/info ", "GET /v1/info ")
        for (w in want) {
            val r = server.takeRequest()
            assertEquals(w, "${r.method} ${r.path} ${r.body.readUtf8()}")
            assertEquals("Bearer $tok", r.getHeader("Authorization"))
        }
        for (bad in listOf("a b", "../x", "a/b", "x".repeat(65), "한글", "")) {
            for (f in listOf<() -> Any>({ l.upload(bad, "t") }, { l.start(bad) {} }, { l.follow(bad, 0) {} }, { l.stop(bad) }, { l.clean(bad) }, { l.errTail(bad) })) {
                assertTrue(runCatching { f() }.exceptionOrNull() is IllegalArgumentException)
            }
        }
        assertEquals(8, server.requestCount)
    }

    @Test
    fun refusalsAreFatalAndOtherErrorsAreNot() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"unknown"}"""))
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"pending","code":"4821"}"""))
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"revoked"}"""))
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"role_not_supported"}"""))
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"internal"}"""))
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"bad_id"}"""))
        server.enqueue(MockResponse().setResponseCode(502))
        server.enqueue(MockResponse().setResponseCode(500))
        val seen = Collections.synchronizedList(mutableListOf<String>())
        val l = link(en = true).also { it.onRefused = { k, c -> seen.add("$k:$c") } }
        val e1 = runCatching { l.clean("r-1") }.exceptionOrNull()
        assertTrue(e1 is SshFatal)
        assertTrue(e1!!.message!!, e1.message!!.contains("does not know this phone"))
        val e2 = runCatching { l.upload("r-1", "t") }.exceptionOrNull()
        assertTrue(e2 is SshFatal)
        assertTrue(e2!!.message!!.contains("4821"))
        assertTrue(runCatching { l.follow("r-1", 0) {} }.exceptionOrNull()!!.message!!.contains("blocked"))
        assertTrue(runCatching { l.start("r-1") {} }.exceptionOrNull() is SshFatal)
        assertEquals(listOf("unknown:", "pending:4821", "revoked:", "role_not_supported:"), seen)
        assertTrue(runCatching { l.stop("r-1") }.exceptionOrNull() is RemoteFail) // 500: refused, not a drop
        assertTrue(runCatching { l.stop("r-1") }.exceptionOrNull() is java.io.IOException) // 503: a drop
        assertEquals(400, l.upload("r-1", "t").first) // a clear refusal: positive
        assertEquals(-1, l.upload("r-1", "t").first) // gateway down behind the tunnel: retry
        assertEquals(2, l.follow("r-1", 0) {}.first) // follow broken
        // Korean texts
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"pending","code":"1234"}"""))
        assertEquals("이 폰은 아직 허용되지 않았습니다 (허용 대기 1234) → 이 말은 보내지 않았습니다 → 허용된 뒤 다시 보내 주세요.", runCatching { link().clean("r-1") }.exceptionOrNull()!!.message)
    }

    // ------------------------------------------------------------ ClaudeCode 의 고리 그대로

    /** 가짜 게이트웨이. 실행 번호는 ClaudeCode 가 만든다. */
    private inner class Fake : Dispatcher() {
        val log: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val follows: MutableList<Int> = Collections.synchronizedList(mutableListOf())
        var inputs = 0
        var inputPlan: (Int) -> MockResponse? = { null }
        var startAnswer: (String) -> MockResponse = { id -> MockResponse().setBody("APERITRUN $id\n") }
        var followAnswer: (Int, Int) -> MockResponse = { call, n -> MockResponse().setBody(if (n == 0) sse(">$init", ">$delta", ">$result", "APERITEXIT 0") else sse(">$result", "APERITEXIT 0")) }
        var errBody = ""

        override fun dispatch(request: RecordedRequest): MockResponse {
            val p = request.path!!
            log.add("${request.method} ${p.substringBefore('?').replace(Regex("/runs/[^/]+"), "/runs/ID")} ${request.getHeader("Authorization") == "Bearer $tok"}")
            val id = Regex("/v1/runs/([^/?]+)").find(p)?.groupValues?.get(1) ?: ""
            return when {
                p.endsWith("/input") -> {
                    inputs++
                    log.add("body " + request.body.readUtf8())
                    inputPlan(inputs) ?: MockResponse().setResponseCode(204)
                }
                p.endsWith("/start") -> {
                    log.add("start " + request.body.readUtf8())
                    startAnswer(id)
                }
                p.contains("/follow") -> {
                    val n = request.requestUrl!!.queryParameter("n")!!.toInt()
                    follows.add(n)
                    followAnswer(follows.size, n)
                }
                p.endsWith("/stop") -> MockResponse().setResponseCode(204)
                p.endsWith("/err") -> MockResponse().setBody(errBody)
                request.method == "DELETE" -> MockResponse().setResponseCode(204)
                else -> MockResponse().setResponseCode(404).setBody("""{"error":"not_found"}""")
            }
        }
    }

    private fun cc(fake: Fake, effort: String = "low"): ClaudeCode {
        server.dispatcher = fake
        return ClaudeCode(link(effort), EngineConfig("claude", "~", "default", effort, ""))
    }

    @Test
    fun aTurnRunsAndReattachesFromNAfterTheStreamEndsWithoutExit() {
        val f = Fake()
        f.followAnswer = { call, n ->
            when (call) {
                // 두 줄과 셋째 줄의 반쪽 뒤에 끊긴다 (APERITEXIT 없이)
                1 -> MockResponse().setBody(sse(">$init", ">$delta") + "data: >" + result.take(12))
                else -> MockResponse().setBody(sse(">$result", "APERITEXIT 0"))
            }
        }
        val c = cc(f)
        val text = StringBuilder()
        val acts = mutableListOf<String>()
        val s = c.run("hello 글", { false }) { ev ->
            when (ev) {
                is Ev.Delta -> text.append(ev.text)
                is Ev.Activity -> acts.add(ev.text)
                else -> {}
            }
        }
        assertEquals("say 안녕\n", text.toString())
        assertEquals(listOf(0, 2), f.follows.toList())
        assertEquals(3, c.linesReceived)
        assertEquals(4L, s.outputTokens)
        assertEquals("Claude Code · m1", s.model)
        assertTrue(acts.toString(), acts.any { it.startsWith("연결이 끊겼습니다") })
        assertTrue(f.log.contains("body hello 글"))
        assertTrue(f.log.toString(), f.log.contains("start {\"effort\":\"low\"}"))
        assertTrue(f.log.contains("DELETE /v1/runs/ID true"))
        assertTrue("every call carries the bearer", f.log.filter { it.startsWith("PUT") || it.startsWith("POST") || it.startsWith("GET") || it.startsWith("DELETE") }.all { it.endsWith(" true") })
    }

    @Test
    fun resumeFollowsFromTheGivenLine() {
        val f = Fake()
        val c = cc(f)
        val s = c.resume("20261120-010000-abcd", "t", 2, { false }) {}
        assertEquals(listOf(2), f.follows.toList())
        assertEquals(0, f.inputs)
        assertEquals("Claude Code", s.model.substringBefore(" ·"))
    }

    @Test
    fun aBrokenConnectionIsRetriedAndARefusalIsNot() {
        val f = Fake()
        f.inputPlan = { k -> if (k == 1) MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START) else null }
        var starts = 0
        f.startAnswer = { id -> if (++starts == 1) MockResponse().setResponseCode(503) else MockResponse().setBody("APERITRUN $id\n") }
        cc(f).run("t", { false }) {}
        assertEquals(2, f.inputs)
        assertEquals(2, starts)

        val g = Fake()
        g.inputPlan = { MockResponse().setResponseCode(403).setBody("""{"error":"pending","code":"4821"}""") }
        val t0 = System.currentTimeMillis()
        val e = runCatching { cc(g).run("t", { false }) {} }.exceptionOrNull()
        assertTrue(e is SshFatal)
        assertTrue(e!!.message!!.contains("허용 대기 4821"))
        assertEquals("no retry after a refusal", 1, g.inputs)
        assertTrue(System.currentTimeMillis() - t0 < 900) // no back-off wait

        val h = Fake()
        h.followAnswer = { _, _ -> MockResponse().setResponseCode(401).setBody("""{"error":"unknown"}""") }
        assertTrue(runCatching { cc(h).run("t", { false }) {} }.exceptionOrNull() is SshFatal)
        assertEquals(1, h.follows.size)
    }

    @Test
    fun startFailKindsGiveTheirTexts() {
        for ((kind, text) in listOf("busy" to "다른 말이 여럿", "noin" to "제대로 올라가지", "cd" to "작업 폴더", "start" to "시작하지 못했습니다")) {
            val f = Fake()
            f.startAnswer = { MockResponse().setBody("APERITFAIL $kind\n") }
            val e = runCatching { cc(f).run("t", { false }) {} }.exceptionOrNull()
            assertTrue("$kind: ${e?.message}", e!!.message!!.contains(text))
            assertTrue(f.follows.isEmpty())
        }
    }

    @Test
    fun stopReachesTheServerThenCleans() {
        val f = Fake()
        f.followAnswer = { _, _ ->
            MockResponse().setBody(sse(">$init") + sse(*Array(200) { "APERITBEAT" })).throttleBody(16, 100, TimeUnit.MILLISECONDS)
        }
        val c = cc(f)
        val e = runCatching { c.run("t", { c.linesReceived >= 1 }) {} }.exceptionOrNull()
        assertEquals(STOPPED, e?.message)
        assertTrue(f.log.toString(), f.log.contains("POST /v1/runs/ID/stop true"))
        assertTrue(f.log.contains("DELETE /v1/runs/ID true"))
    }

    @Test
    fun aFailedRunReadsTheErrTailAndCleans() {
        val f = Fake()
        f.followAnswer = { _, _ -> MockResponse().setBody(sse(">$init", "APERITEXIT 1")) }
        f.errBody = "boom: not logged in"
        val e = runCatching { cc(f).run("t", { false }) {} }.exceptionOrNull()
        assertTrue(e!!.message!!, e.message!!.contains("답 없이") && e.message!!.contains("boom: not logged in"))
        assertTrue(f.log.contains("GET /v1/runs/ID/err true"))
        assertTrue(f.log.contains("DELETE /v1/runs/ID true"))
    }

    @Test
    fun lostAndNoneAndGoneMapLikeTheSshPath() {
        val f = Fake()
        f.followAnswer = { _, _ -> MockResponse().setBody(sse(">$init", "APERITEXIT lost")) }
        assertTrue(runCatching { cc(f).run("t", { false }) {} }.exceptionOrNull()!!.message!!.contains("사라졌습니다"))
        val g = Fake()
        g.followAnswer = { _, _ -> MockResponse().setBody(sse("APERITEXIT gone")) }
        assertEquals(com.fidenit.kite.engine.GONE, runCatching { cc(g).resume("a-1", null, 3, { false }) {} }.exceptionOrNull()!!.message)
        val h = Fake()
        h.followAnswer = { call, _ -> MockResponse().setBody(if (call == 1) sse("APERITEXIT none") else sse(">$init", ">$result", "APERITEXIT 0")) }
        cc(h).resume("a-2", "again", 0, { false }) {}
        assertEquals(1, h.inputs) // none + nothing received: started once more with the same id
    }
}
