package com.fidenit.kite

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.fidenit.kite.engine.Api
import com.fidenit.kite.engine.ApiConfig
import com.fidenit.kite.engine.Ev
import com.fidenit.kite.engine.PhoneTools
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** 가짜 Claude 서버로 API 엔진의 한 턴을 돌려 본다: 스트리밍 → 도구 실행 → 결과를 돌려 다시 묻기 → 끝. */
class ApiTest {
    private lateinit var server: MockWebServer
    private lateinit var vault: File

    @Before
    fun up() {
        server = MockWebServer().apply { start() }
        vault = File.createTempFile("kite", "vault").let { it.delete(); it.mkdirs(); it }
    }

    @After
    fun down() {
        server.shutdown()
        vault.deleteRecursively()
    }

    private fun sse(vararg events: String) =
        MockResponse().setHeader("content-type", "text/event-stream").setBody(events.joinToString("") { e -> "event: ${JSONObject(e).getString("type")}\ndata: $e\n\n" })

    private fun start(input: Int, written: Int, read: Int) =
        """{"type":"message_start","message":{"id":"msg_x","type":"message","role":"assistant","model":"claude-opus-5-5","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":$input,"cache_creation_input_tokens":$written,"cache_read_input_tokens":$read,"output_tokens":1}}}"""

    private fun text(i: Int, t: String) = listOf(
        """{"type":"content_block_start","index":$i,"content_block":{"type":"text","text":""}}""",
        """{"type":"content_block_delta","index":$i,"delta":{"type":"text_delta","text":${JSONObject.quote(t)}}}""",
        """{"type":"content_block_stop","index":$i}""",
    )

    private fun tool(i: Int, id: String, name: String, input: String) = listOf(
        """{"type":"content_block_start","index":$i,"content_block":{"type":"tool_use","id":"$id","name":"$name","input":{}}}""",
        """{"type":"content_block_delta","index":$i,"delta":{"type":"input_json_delta","partial_json":${JSONObject.quote(input)}}}""",
        """{"type":"content_block_stop","index":$i}""",
    )

    private fun end(stop: String, output: Int) = listOf(
        """{"type":"message_delta","delta":{"stop_reason":"$stop","stop_sequence":null},"usage":{"output_tokens":$output}}""",
        """{"type":"message_stop"}""",
    )

    private fun api(): Api {
        val client = AnthropicOkHttpClient.builder().apiKey("sk-test").baseUrl(server.url("/").toString().trimEnd('/')).maxRetries(0).build()
        return Api(client, "SYSTEM", ApiConfig("claude-opus-5-5", "low", viaRelay = false), PhoneTools(vault))
    }

    @Test
    fun toolLoopRunsToTheEnd() {
        // 첫 응답: 한마디 → screen 도구 + 기억 덧붙이기 도구. 두 번째 응답: 마지막 화면 명령.
        server.enqueue(
            sse(
                start(100, 1000, 0),
                *text(0, "say 찾아볼게요\n").toTypedArray(),
                *tool(1, "toolu_1", "screen", """{"ops": "topic t 테스트"}""").toTypedArray(),
                *tool(2, "toolu_2", "note_append", """{"path": "기억/핵심.md", "content": "사용자는 커피를 좋아한다"}""").toTypedArray(),
                *end("tool_use", 50).toTypedArray(),
            ),
        )
        server.enqueue(sse(start(50, 0, 1000), *text(0, "card k1 note t 메모\n").toTypedArray(), *end("end_turn", 20).toTypedArray()))

        val deltas = StringBuilder()
        val screens = mutableListOf<String>()
        val stats = api().run("<input>커피 좋아해</input>", { false }) { ev ->
            when (ev) {
                is Ev.Delta -> deltas.append(ev.text)
                is Ev.Screen -> {
                    screens += ev.ops
                    ev.reply.complete("반영됨")
                }
                is Ev.Activity -> {}
            }
        }

        assertTrue(deltas.toString(), deltas.contains("say 찾아볼게요\n"))
        assertTrue(deltas.toString(), deltas.contains("card k1 note t 메모\n"))
        assertEquals(listOf("topic t 테스트"), screens)
        assertEquals("사용자는 커피를 좋아한다\n", File(vault, "기억/핵심.md").readText())

        // 요청마다 사용량을 더한다 (도구를 쓴 턴도 전부 센다)
        assertEquals(100L + 1000 + 50 + 1000, stats.inputTokens)
        assertEquals(1000L, stats.cachedTokens)
        assertEquals(70L, stats.outputTokens)
        assertTrue(stats.costUsd > 0)

        // 첫 요청: 지시문 1시간 캐시, 도구, 거절 시 다른 모델로 잇기
        val first = server.takeRequest()
        val b1 = JSONObject(first.body.readUtf8())
        assertEquals("1h", b1.getJSONArray("system").getJSONObject(0).getJSONObject("cache_control").getString("ttl"))
        val names = (0 until b1.getJSONArray("tools").length()).map { b1.getJSONArray("tools").getJSONObject(it).getString("name") }
        assertEquals(listOf("screen", "note_search", "note_read", "note_write", "note_append", "web_search", "web_fetch"), names)
        assertEquals("default", b1.getString("fallbacks"))
        assertTrue(first.getHeader("anthropic-beta").orEmpty().contains("server-side-fallback-2026-07-01"))
        assertEquals("low", b1.getJSONObject("output_config").getString("effort"))

        // 두 번째 요청: 받은 응답(도구 요청 포함)과 도구 결과가 이어 붙어 있다
        val b2 = JSONObject(server.takeRequest().body.readUtf8())
        val msgs = b2.getJSONArray("messages")
        assertEquals(3, msgs.length())
        assertEquals("assistant", msgs.getJSONObject(1).getString("role"))
        val results = msgs.getJSONObject(2).getJSONArray("content")
        assertEquals("toolu_1", results.getJSONObject(0).getString("tool_use_id"))
        assertEquals("반영됨", results.getJSONObject(0).getString("content"))
        assertEquals("toolu_2", results.getJSONObject(1).getString("tool_use_id"))
        assertFalse(results.getJSONObject(1).optBoolean("is_error", false))
    }

    @Test
    fun retriesWithoutFallbacksWhenRejected() {
        server.enqueue(MockResponse().setResponseCode(400).setHeader("content-type", "application/json").setBody("""{"type":"error","error":{"type":"invalid_request_error","message":"fallbacks: not available for this account"}}"""))
        server.enqueue(sse(start(10, 0, 0), *text(0, "say 네\n").toTypedArray(), *end("end_turn", 5).toTypedArray()))
        val deltas = StringBuilder()
        api().run("<input>안녕</input>", { false }) { if (it is Ev.Delta) deltas.append(it.text) }
        assertTrue(deltas.contains("say 네"))
        server.takeRequest()
        val second = server.takeRequest()
        assertFalse(JSONObject(second.body.readUtf8()).has("fallbacks"))
        assertFalse(second.getHeader("anthropic-beta").orEmpty().contains("server-side-fallback"))
    }

    @Test
    fun budgetExceededIsExplained() {
        server.enqueue(MockResponse().setResponseCode(402).setHeader("content-type", "application/json").setBody("""{"type":"error","error":{"type":"budget_exceeded","message":"monthly budget used"}}"""))
        val e = runCatching { api().run("<input>x</input>", { false }) {} }.exceptionOrNull()
        assertTrue(e?.message.orEmpty(), e?.message.orEmpty().contains("이번 달 사용량"))
    }

    @Test
    fun vaultPathsStayInside() {
        val t = PhoneTools(vault)
        assertTrue(t.call("note_read", mapOf("path" to "../../etc/passwd")).second)
        assertTrue(t.call("note_write", mapOf("path" to "/../x.md", "content" to "a")).second)
        assertFalse(t.call("note_write", mapOf("path" to "작업/여행.md", "content" to "---\ndescription: 제주 여행 준비\n---\n짐 싸기")).second)
        assertTrue(t.list().contains("작업/여행.md — 제주 여행 준비"))
        assertTrue(t.call("note_search", mapOf("query" to "짐 싸기")).first.contains("작업/여행.md:4"))
        assertTrue(t.call("shell", mapOf("command" to "ls")).second)
    }
}
