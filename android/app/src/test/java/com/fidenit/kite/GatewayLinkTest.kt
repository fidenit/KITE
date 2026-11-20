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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.util.Collections
import java.util.concurrent.TimeUnit

/** Pro 의 게이트웨이 길: 가짜 게이트웨이(MockWebServer)로 등록·따라 읽기·끊김·거절을 본다. JVM 만 (free 변형에서 돈다). */
class GatewayLinkTest {
    /** 엔진 고리가 끝없이 다시 붙으면 실패로 끝낸다 (시험 전체가 매달리지 않게). */
    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

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

    @Test
    fun everyRequestCarriesTheAppHeaderAndBusyIsItsOwnLine() {
        repeat(3) { server.enqueue(MockResponse().setBody("""{"status":"approved"}""")) }
        server.enqueue(MockResponse().setBody("APERITFAIL busy\n"))
        val http = com.fidenit.kite.engine.gatewayClient("0.2.0-pro")
        val e = Enroller(base(), http)
        e.enroll("id-1", tok, "m", "14", "0.2.0", ticket = "tkt-1")
        e.enroll("id-1", tok, "m", "14", "0.2.0")
        e.me(tok)
        val l = GatewayLink(base(), { tok }, "", { true }, http)
        val busy = runCatching { l.start("r-1") {} }.exceptionOrNull()
        assertTrue(busy is SshFatal)
        assertEquals(com.fidenit.kite.engine.GatewayText.busy(true), busy!!.message)
        val r1 = server.takeRequest()
        assertEquals("tkt-1", JSONObject(r1.body.readUtf8()).getString("ticket"))
        assertFalse(JSONObject(server.takeRequest().also { assertEquals("0.2.0-pro", it.getHeader("X-Aperit-App")) }.body.readUtf8()).has("ticket"))
        assertEquals("0.2.0-pro", r1.getHeader("X-Aperit-App"))
        assertEquals("0.2.0-pro", server.takeRequest().getHeader("X-Aperit-App"))
        val st = server.takeRequest()
        assertEquals("0.2.0-pro", st.getHeader("X-Aperit-App"))
        assertEquals("{}", st.body.readUtf8()) // no explicit effort: none sent
        assertEquals(30_000, com.fidenit.kite.engine.gatewayClient().readTimeoutMillis) // 30 s for short calls
        assertTrue(com.fidenit.kite.engine.FOLLOW_READ_MS > com.fidenit.kite.engine.Detached.IDLE_MS)
    }

    // ------------------------------------------------------------ SSE

    @Test
    fun sseEventsSplitAcrossChunksArriveWholeAndInOrder() {
        for (crlf in listOf(false, true)) {
            val nl = if (crlf) "\r\n" else "\n"
            val body = "id: 6${nl}data: >$init$nl$nl: keep-alive$nl${nl}data: APERITBEAT$nl${nl}id: 7${nl}data: >$delta$nl$nl" + sse("APERITEXIT 0", crlf = crlf)
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
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
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
        assertEquals("이 폰은 아직 허용되지 않았습니다 (허용 대기 1234) → 서버에 멈춤·치우기를 전하지 못했습니다 → 허용된 뒤 다시 보내 주세요.", runCatching { link().clean("r-1") }.exceptionOrNull()!!.message)
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"pending","code":"1234"}"""))
        assertEquals("이 폰은 아직 허용되지 않았습니다 (허용 대기 1234) → 이 말은 보내지 않았습니다 → 허용된 뒤 다시 보내 주세요.", runCatching { link().upload("r-1", "t") }.exceptionOrNull()!!.message)
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
                    followAnswer(follows.size, n).also { if (it.headers["Content-Type"] == null) it.setHeader("Content-Type", "text/event-stream") }
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
        f.inputPlan = { k -> if (k == 1) MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) else null }
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
        for ((kind, text) in listOf("busy" to "서버가 지금 바쁩니다", "noin" to "제대로 올라가지", "cd" to "작업 폴더", "start" to "시작하지 못했습니다")) {
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

    /**
     * 끝난 값(lost / gone / 1) 은 따라 읽기에서 SSH 와 같은 줄로 온다. 엔진 고리가 이 끝을 어떻게 다루는지는 f045 의 몫이라 여기서는
     * 고리를 거치지 않고 길만 본다 (지금 고리는 finish() 의 예외를 끊김처럼 다시 해 본다: 손넘김 맨 위 참고).
     */
    @Test
    fun endValuesErrTailAndCleanTravelLikeTheSshPath() {
        val f = Fake()
        server.dispatcher = f
        val l = link()
        for (end in listOf("lost", "gone", "1", "stopped")) {
            f.followAnswer = { _, _ -> MockResponse().setBody(sse(">$init", "APERITEXIT $end")) }
            val got = mutableListOf<String>()
            assertEquals(0, l.follow("a-1", 0) { got.add(it) }.first)
            assertEquals(listOf(">$init", "APERITEXIT $end"), got)
            assertEquals(com.fidenit.kite.engine.FollowLine.Exit(end), com.fidenit.kite.engine.Detached.classify(got.last()))
        }
        f.errBody = "boom: not logged in"
        assertEquals("boom: not logged in", l.errTail("a-1"))
        l.clean("a-1")
        assertTrue(f.log.contains("GET /v1/runs/ID/err true"))
        assertTrue(f.log.contains("DELETE /v1/runs/ID true"))
    }

    @Test
    fun noneWithNothingReceivedStartsOnceMoreWithTheSameId() {
        val h = Fake()
        h.followAnswer = { call, _ -> MockResponse().setBody(if (call == 1) sse("APERITEXIT none") else sse(">$init", ">$result", "APERITEXIT 0")) }
        cc(h).resume("a-2", "again", 0, { false }) {}
        assertEquals(1, h.inputs) // none + nothing received: started once more with the same id
    }

    /** 끝난 값은 엔진 고리 전체를 지나 그 값으로 끝난다 (다시 붙지 않는다). `>` 줄에는 게이트웨이처럼 `id:` 를 붙인다. */
    @Test
    fun endValuesLeaveTheLoopWithTheirMessage() {
        fun withIds(vararg data: String): String {
            var n = 0
            return data.joinToString("") { if (it.startsWith(">")) "id: ${++n}\ndata: $it\n\n" else "data: $it\n\n" }
        }
        val f = Fake()
        f.followAnswer = { _, _ -> MockResponse().setBody(withIds(">$init", "APERITEXIT lost")) }
        val lost = runCatching { cc(f).run("t", { false }) {} }.exceptionOrNull()
        assertTrue(lost?.message, lost!!.message!!.contains("사라졌습니다"))
        assertEquals("not followed again", 1, f.follows.size)
        assertEquals("no second upload after APERITEXIT", 1, f.inputs)
        assertEquals("no second start after APERITEXIT", 1, f.log.count { it.startsWith("start ") })

        val g = Fake()
        g.followAnswer = { _, _ -> MockResponse().setBody(withIds("APERITEXIT gone")) }
        assertEquals(com.fidenit.kite.engine.GONE, runCatching { cc(g).resume("a-1", null, 3, { false }) {} }.exceptionOrNull()!!.message)
        assertEquals(1, g.follows.size)

        val h = Fake()
        h.followAnswer = { _, _ -> MockResponse().setBody(withIds(">$init", "APERITEXIT 1")) }
        h.errBody = "boom: not logged in"
        val failed = runCatching { cc(h).run("t", { false }) {} }.exceptionOrNull()
        assertTrue(failed?.message, failed!!.message!!.contains("답 없이") && failed.message!!.contains("boom: not logged in"))
        assertEquals(1, h.follows.size)
        assertEquals(1, h.inputs)
        assertEquals(1, h.log.count { it.startsWith("start ") })
        assertTrue(h.log.contains("GET /v1/runs/ID/err true"))
        assertTrue(h.log.contains("DELETE /v1/runs/ID true"))

        val k = Fake()
        val err = """{"type":"result","is_error":true,"result":"model overloaded","usage":{}}"""
        k.followAnswer = { _, _ -> MockResponse().setBody(withIds(">$init", ">$err", "APERITEXIT 0")) }
        val isErr = runCatching { cc(k).run("t", { false }) {} }.exceptionOrNull()
        assertEquals("Claude Code: model overloaded", isErr!!.message)
        assertEquals(1, k.follows.size)
        assertEquals(1, k.inputs)

        // a stream that closes without APERITEXIT is still a drop: re-attached from n
        val d = Fake()
        d.followAnswer = { call, _ -> MockResponse().setBody(if (call == 1) withIds(">$init") else "id: 2\ndata: >$result\n\ndata: APERITEXIT 0\n\n") }
        cc(d).run("t", { false }) {}
        assertEquals(listOf(0, 1), d.follows.toList())
    }

    // ------------------------------------------------------------ review rv4b

    @Test
    fun redirectsAreNotFollowedAndAreRefusals() {
        val other = MockWebServer()
        other.start()
        try {
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/v1/runs/r-1/input").toString()))
            server.enqueue(MockResponse().setResponseCode(301).setHeader("Location", other.url("/v1/system").toString()))
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", other.url("/v1/enroll").toString()))
            val l = link()
            assertEquals(302, l.upload("r-1", "t").first)
            assertTrue(runCatching { l.prepare("s") }.exceptionOrNull() is RemoteFail)
            assertEquals("refused", Enroller(base(), com.fidenit.kite.engine.gatewayClient("0.2.0-pro")).enroll("i", tok, "m", "a", "b").status)
            assertEquals(0, other.requestCount)
        } finally {
            other.shutdown()
        }
    }

    @Test
    fun a401MidFollowSaysTheTurnWasSent() {
        val f = Fake()
        f.followAnswer = { _, _ -> MockResponse().setResponseCode(401).setBody("""{"error":"unknown"}""") }
        val e = runCatching { cc(f).run("t", { false }) {} }.exceptionOrNull()
        assertTrue(e is SshFatal)
        assertTrue(e!!.message!!, e.message!!.contains("보냈지만 결과를 받지 못해"))
    }

    @Test
    fun anIdGapIsADropAndNoLineIsDeliveredTwice() {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("id: 1\ndata: >a\n\nid: 3\ndata: >c\n\ndata: APERITEXIT 0\n\n"))
        val got = mutableListOf<String>()
        assertEquals(-1, link().follow("r-1", 0) { got.add(it) }.first)
        assertEquals(listOf(">a"), got)
        // through the loop: re-attached from n=1, the repeated line 1 is not delivered twice
        val f = Fake()
        f.followAnswer = { call, n ->
            MockResponse().setBody(
                if (call == 1) "id: 1\ndata: >$init\n\nid: 3\ndata: >$result\n\n"
                else "id: ${n + 1}\ndata: >$delta\n\nid: ${n + 2}\ndata: >$result\n\ndata: APERITEXIT 0\n\n",
            )
        }
        val c = cc(f)
        val fed = mutableListOf<String>()
        c.onDataLine = { fed.add(it) }
        c.run("t", { false }) {}
        assertEquals(listOf(0, 1), f.follows.toList())
        assertEquals(listOf(init, delta, result), fed)
    }

    @Test
    fun answersThatAreNotTheGatewaysAreRefusals() {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/html").setBody("<html>login</html>"))
        assertEquals(2, link().follow("r-1", 0) {}.first)
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>login</html>"))
        assertTrue(link().upload("r-1", "t").first > 0)
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>login</html>"))
        assertTrue(link().start("r-1") {}.first > 0)
    }

    @Test
    fun aBadBaseUrlIsAConfigurationErrorNotADrop() {
        val l = GatewayLink("not a url", { tok })
        val e = runCatching { l.upload("r-1", "t") }.exceptionOrNull()
        assertTrue("$e", e is SshFatal)
        val h = GatewayLink(base(), { "apd_bad\nvalue" })
        assertTrue(runCatching { h.clean("r-1") }.exceptionOrNull() is SshFatal)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun anOverlongSseLineIsLost() {
        val got = mutableListOf<String>()
        val r = runCatching { com.fidenit.kite.engine.readSseEvents(Buffer().writeUtf8("data: >ok\n\ndata: >" + "x".repeat(200) + "\n\n"), maxLine = 100) { _, d -> got.add(d) } }
        assertTrue(r.exceptionOrNull() is com.fidenit.kite.engine.SseTooLong)
        assertEquals(listOf(">ok"), got)
    }

    @Test
    fun followFailuresMapToRetryOrBroken() {
        // 429 and 503 are drops (re-attach), three 500s in a row end the turn with the gateway's own text
        val f = Fake()
        f.followAnswer = { call, _ -> if (call == 1) MockResponse().setResponseCode(429) else MockResponse().setResponseCode(500).setBody("""{"error":"internal"}""") }
        val e = runCatching { cc(f).run("t", { false }) {} }.exceptionOrNull()
        assertTrue(e!!.message!!, e.message!!.contains("세 번 이어서 읽지 못했습니다"))
        assertEquals(4, f.follows.size)
        // 413 at upload: the gateway's text, not "folder permissions"
        val g = Fake()
        g.inputPlan = { MockResponse().setResponseCode(413).setBody("""{"error":"too_large"}""") }
        val u = runCatching { cc(g).run("t", { false }) {} }.exceptionOrNull()
        assertTrue(u!!.message!!, u.message!!.contains("서버가 이 말을 받지 않았습니다 (HTTP 413 too_large)"))
    }

    @Test
    fun lostWithNoLineReceivedRunsExactlyOnce() {
        val f = Fake()
        f.followAnswer = { _, _ -> MockResponse().setBody(sse("APERITEXIT lost")) }
        val e = runCatching { cc(f).run("t", { false }) {} }.exceptionOrNull()
        assertTrue(e!!.message!!, e.message!!.contains("사라졌습니다"))
        assertEquals(1, f.inputs)
        assertEquals(1, f.log.count { it.startsWith("start ") })
        assertEquals(1, f.follows.size)
    }
}
