// Claude API 엔진: 폰이 Claude API 를 직접 부른다.
// 무료 앱(KITE)은 사용자가 넣은 API 키로 Anthropic 에, 유료 앱(KITE Pro)은 같은 요청을 우리 중계 서버에 보낸다.
// 흐름은 데스크톱 src/agent.rs 와 같다: 스트리밍으로 받으며 글자를 바로 화면에 흘리고,
// 모델이 도구를 요청하면 실행해서 결과를 돌려주기를 모델이 끝낼 때까지 반복한다.
package com.fidenit.kite.engine

import com.anthropic.client.AnthropicClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.helpers.MessageAccumulator
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageContentParam
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.ToolResultBlockParam
import com.anthropic.models.messages.ToolUseBlock
import com.anthropic.models.messages.WebFetchTool20260209
import com.anthropic.models.messages.WebSearchTool20260209
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/** 이 엔진에서만 덧붙이는 지시 (데스크톱 지시문 src/prompt.md 뒤에 붙는다). */
const val PHONE_NOTE = """

# Running directly on a phone

You are the engine of KITE running directly on the user's Android phone. The app calls you through the Claude API and shows your output on the phone. This changes the following.

1. Your tools here are: screen, web_search, web_fetch, note_search, note_read, note_write, note_append. These tools named above do not exist here: shell, read_file, write_file, edit_file, list_dir, find_text, view_image, route_find, place_find, board_read, browser_text, browser_eval, page_open, page_read, page_run, page_show, job_start, job_list, job_tell, job_stop, job_result, contact_find, message_send, phone_call, message_read. There is no computer, no file system and no shell to work in. Do not offer or promise what needs them (running commands, editing files, booking on a site, sending messages, handing work to workers). Where the text above tells you to hand work to a worker, do the part you can with your own tools in this turn instead. The whole board always arrives in `<board>`. Do not make route cards.
2. The phone screen is narrow. One topic fills the screen and its cards are stacked in one column at full width, so card sizes and `move` have no effect. Use few, compact cards, and keep tables to three columns. `web` and `image` cards show only a link that opens in the phone's browser.
3. Memory notes live in the app's own note vault and the note_* tools work on it. The note `기억/핵심.md` is what arrives as `<core>`. Nothing else maintains it on the phone: when the user tells you a lasting fact about themselves or about how they want things done, add one short line to it with note_append in the same turn.
"""

class ApiConfig(
    /** 모델 ID (예: claude-opus-5-5) */
    val model: String,
    /** low / medium / high */
    val effort: String,
    /** 중계 서버를 거치는가 (오류를 알릴 때 말이 다르다) */
    val viaRelay: Boolean,
)

/** 한 번에 도구를 주고받는 횟수의 상한 (데스크톱과 같다) */
private const val MAX_STEPS = 80

class Api(
    private val client: AnthropicClient,
    private val system: String,
    private val cfg: ApiConfig,
    private val tools: PhoneTools,
) : Runner {
    override val label = "Claude API"

    /** 거절된 요청을 서버가 다른 모델로 이어서 답하게 한다. 이 계정에서 안 받아들여지면 끄고 다시 보낸다. */
    @Volatile
    private var fallbacks = true

    override fun run(turnText: String, stopped: () -> Boolean, send: (Ev) -> Unit): Stats {
        val started = System.currentTimeMillis()
        val stats = Stats().apply { model = cfg.model }
        val messages = mutableListOf(MessageParam.ofUser(turnText))
        try {
            for (step in 0 until MAX_STEPS) {
                val msg = streamOnce(messages, stats, started, stopped, send)
                send(Ev.Delta("\n")) // 응답 사이에 줄이 붙지 않게 한다
                when (msg.stopReason().orElse(null)) {
                    StopReason.TOOL_USE -> {
                        messages += assistant(msg)
                        val results = msg.content().mapNotNull { it.toolUse().orElse(null) }.map { tu ->
                            val (out, isError) = call(tu, send)
                            ContentBlockParam.ofToolResult(ToolResultBlockParam.builder().toolUseId(tu.id()).content(out).isError(isError).build())
                        }
                        if (stopped()) throw Exception(STOPPED)
                        // 도구 결과는 한 메시지에 모두 담아 돌려준다.
                        messages += MessageParam.ofUserOfContentBlockParams(results)
                        send(Ev.Activity(""))
                    }
                    // 서버 쪽 도구(웹 검색 등)가 길어져 잠시 멈춘 것: 받은 것을 그대로 붙여 다시 보내면 이어서 한다.
                    StopReason.PAUSE_TURN -> messages += assistant(msg)
                    StopReason.REFUSAL -> throw Exception("모델이 이 요청을 거절했습니다")
                    StopReason.MAX_TOKENS -> throw Exception("출력이 한도에서 잘렸습니다")
                    else -> {
                        stats.totalMs = System.currentTimeMillis() - started
                        return stats
                    }
                }
            }
            throw Exception("한 번에 ${MAX_STEPS}단계를 넘겨 중단했습니다")
        } catch (e: AnthropicServiceException) {
            if (stopped()) throw Exception(STOPPED)
            throw Exception(explain(e), e)
        } catch (e: AnthropicIoException) {
            if (stopped()) throw Exception(STOPPED)
            throw Exception("연결 실패: ${e.message}", e)
        }
    }

    private fun params(messages: List<MessageParam>): MessageCreateParams {
        val b = MessageCreateParams.builder()
            .model(cfg.model)
            .maxTokens(32000L)
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.of(cfg.effort)).build())
            // 도구 정의와 지시문은 매 턴 같으므로 여기까지를 캐시한다. 폰은 띄엄띄엄 쓰이니 1시간 동안 둔다.
            .systemOfTextBlockParams(
                listOf(TextBlockParam.builder().text(system).cacheControl(CacheControlEphemeral.builder().ttl(CacheControlEphemeral.Ttl.TTL_1H).build()).build()),
            )
            // 한 턴 안에서 도구를 여러 번 쓰는 동안 쌓이는 내용도 캐시한다 (5분).
            .cacheControl(CacheControlEphemeral.builder().build())
            .messages(messages)
        tools.definitions.forEach { b.addTool(it) }
        b.addTool(WebSearchTool20260209.builder().maxUses(8L).build())
        b.addTool(WebFetchTool20260209.builder().maxUses(8L).build())
        if (fallbacks) {
            b.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
            b.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        }
        return b.build()
    }

    /** 한 번 묻고, 오는 대로 화면에 흘리면서 응답 전체를 모은다. */
    private fun streamOnce(messages: List<MessageParam>, stats: Stats, started: Long, stopped: () -> Boolean, send: (Ev) -> Unit): Message {
        for (attempt in 0..1) {
            try {
                client.messages().createStreaming(params(messages)).use { resp ->
                    // 멈춤 버튼: 따로 지켜보다가 연결을 끊는다 (생각하느라 글자가 안 오는 동안에도 바로 멈추게).
                    val done = AtomicBoolean(false)
                    Thread {
                        while (!done.get()) {
                            if (stopped()) {
                                try {
                                    resp.close()
                                } catch (_: Exception) {
                                }
                                return@Thread
                            }
                            Thread.sleep(120)
                        }
                    }.apply { isDaemon = true }.start()
                    try {
                        val acc = MessageAccumulator.create()
                        val events = resp.stream().iterator()
                        while (events.hasNext()) {
                            val ev = acc.accumulate(events.next())
                            ev.contentBlockStart().ifPresent { s ->
                                val block = s.contentBlock()
                                block.toolUse().ifPresent { send(Ev.Activity(tools.describe(it.name(), emptyMap()))) }
                                block.serverToolUse().ifPresent { send(Ev.Activity(if (it.name().toString().contains("fetch")) "웹 페이지 읽는 중" else "웹 검색 중")) }
                            }
                            ev.contentBlockDelta().flatMap { it.delta().text() }.ifPresent { d ->
                                val t = d.text()
                                if (t.isNotEmpty()) {
                                    if (stats.firstMs == 0L) stats.firstMs = System.currentTimeMillis() - started
                                    send(Ev.Delta(t))
                                }
                            }
                        }
                        val msg = acc.message()
                        count(msg, stats)
                        return msg
                    } finally {
                        done.set(true)
                    }
                }
            } catch (e: BadRequestException) {
                if (attempt == 0 && fallbacks && (e.message ?: "").contains("fallback")) {
                    fallbacks = false
                    continue
                }
                throw e
            } catch (e: Exception) {
                if (stopped()) throw Exception(STOPPED)
                throw e
            }
        }
        throw Exception("요청을 보내지 못했습니다")
    }

    /** 받은 응답을 다음 요청에 되돌릴 형태로 바꾼다. 빈 글 블록은 되돌려 보낼 수 없어서 뺀다 (생각 블록은 고치지 않고 그대로 둔다). */
    private fun assistant(msg: Message): MessageParam {
        val blocks = msg.content().filter { b -> !b.text().map { it.text().isEmpty() }.orElse(false) }.map { it.toParam() }
        return MessageParam.ofAssistant(MessageContentParam.ofContentBlockParams(blocks))
    }

    /** 도구 하나를 실행한다. (결과, 오류인가) */
    private fun call(tu: ToolUseBlock, send: (Ev) -> Unit): Pair<String, Boolean> {
        @Suppress("UNCHECKED_CAST")
        val input = try {
            tu._input().convert(Map::class.java) as? Map<String, Any?> ?: emptyMap()
        } catch (e: Exception) {
            return "INVALID_JSON: 도구 입력을 읽지 못했습니다" to true
        }
        if (tu.name() == "screen") {
            // 화면은 화면 쪽이 고친다. 거부된 명령이 있으면 그 이유를 받아 온다.
            val reply = CompletableDeferred<String>()
            send(Ev.Screen(input["ops"]?.toString().orEmpty(), reply))
            return (runBlocking { withTimeoutOrNull(15_000) { reply.await() } } ?: "반영됨") to false
        }
        send(Ev.Activity(tools.describe(tu.name(), input)))
        return tools.call(tu.name(), input)
    }

    /** 사용량과 어림 비용을 더한다 (요청마다 더한다: 도구를 여러 번 쓴 턴도 전부 센다). */
    private fun count(msg: Message, stats: Stats) {
        val u = msg.usage()
        val written = u.cacheCreationInputTokens().orElse(0L)
        val read = u.cacheReadInputTokens().orElse(0L)
        stats.inputTokens += u.inputTokens() + written + read
        stats.cachedTokens += read
        stats.outputTokens += u.outputTokens()
        stats.model = msg.model().toString()
        val p = price(cfg.model)
        val w1h = u.cacheCreation().map { it.ephemeral1hInputTokens() }.orElse(0L)
        val w5m = written - w1h
        val searches = u.serverToolUse().map { it.webSearchRequests() }.orElse(0L)
        stats.costUsd += (u.inputTokens() * p.input + w5m * p.write5m + w1h * p.write1h + read * p.read + u.outputTokens() * p.output) / 1_000_000.0 + searches * 0.01
    }

    private fun explain(e: AnthropicServiceException): String {
        val detail = (e.message ?: "").take(200)
        return when (e.statusCode()) {
            401 -> if (cfg.viaRelay) "구독을 확인하지 못했습니다. 앱을 다시 열어 주세요." else "Claude API 키가 맞지 않습니다. 설정에서 확인해 주세요."
            402 -> "이번 달 사용량을 모두 썼습니다. 다음 달에 다시 채워집니다."
            403 -> "이 키로는 할 수 없는 요청입니다. ($detail)"
            429 -> "요청이 많아 잠시 막혔습니다. 조금 뒤 다시 해 주세요."
            in 500..599 -> "Claude 서버가 바쁩니다. 조금 뒤 다시 해 주세요."
            else -> "HTTP ${e.statusCode()}: $detail"
        }
    }

    /** 연결 시험: 무료 앱은 모델 목록(요금 없음)을 받아 키가 맞는지 본다. */
    fun check(): String = try {
        val n = client.models().list().data().size
        "연결됨 · Claude API · 쓸 수 있는 모델 ${n}개"
    } catch (e: AnthropicServiceException) {
        throw Exception(explain(e), e)
    } catch (e: AnthropicIoException) {
        throw Exception("연결 실패: ${e.message}", e)
    }
}

/** 100만 토큰당 달러 (어림 비용 표시용. 정확한 청구액은 Anthropic 콘솔 기준). */
private class Price(val input: Double, val output: Double, val read: Double, val write5m: Double, val write1h: Double)

private fun price(model: String): Price = when {
    model.contains("haiku") -> Price(0.10, 0.50, 0.01, 0.125, 0.20)
    model.contains("sonnet") -> Price(2.0, 10.0, 0.10, 2.5, 4.0)
    else -> Price(4.0, 20.0, 0.20, 5.0, 8.0) // Opus 5.5
}
