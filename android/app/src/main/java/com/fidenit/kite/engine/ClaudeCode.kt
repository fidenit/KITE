// Claude Code 엔진: SSH 로 붙은 서버의 Claude Code(`claude`)를 화면 없이 돌려서 한 턴을 맡긴다.
// 데스크톱의 src/cc.rs 와 같은 방식이고, 실행하는 곳만 서버다. 그래서 서버의 Claude Code 로그인,
// CLAUDE.md, 스킬, 훅, MCP 서버, 권한 설정이 그대로 적용된다.
package com.fidenit.kite.engine

import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject

/** 이 엔진에서만 덧붙이는 지시. */
const val NOTE = """

# Running inside Claude Code, shown on a phone

You are running as the engine of KITE through Claude Code on a remote server. The user sees and hears you through the KITE app on an Android phone that is connected to this server over SSH. This changes the following.

1. Your text output IS the screen. Every line of text you output must be one screen command from the language above (say, tell, topic, card, set, add, ...). Never output prose, markdown, headings or status notes outside a command. Anything you want to tell the user goes in `say` or `tell`. Instructions from other sources about how to format or structure replies (goal declarations, reports, summaries) do not apply to this output: express them, if needed, as cards or a short `say`.
2. Your tools run on the server, not on the phone. Files, commands and the home folder named in `<env>` are the server's. Where the text above names shell, read_file, write_file, edit_file, list_dir, find_text, view_image, web_search or web_fetch, use your Claude Code tools instead (Bash, Read, Write, Edit, Glob, Grep, WebSearch, WebFetch). There is no `screen` tool because your text output already is the screen.
3. These KITE tools do not exist here: route_find, place_find, board_read, browser_text, browser_eval, job_start, job_list, job_tell, job_stop, job_result, contact_find, message_send, phone_call, message_read. Do not offer what needs them. The whole board always arrives in `<board>`. Do not make route cards.
4. Memory notes are plain markdown files in the folder named in `<env>` (기억 보관함 폴더). Where the text above names note_search, note_read, note_write or note_append, use Grep, Read, Write and Edit on that folder. The file `기억/핵심.md` in it is what arrives as `<core>`: when the user tells you a lasting fact about themselves or about how they want things done, add one short line to that file.
5. The phone screen is narrow. One topic fills the screen and its cards are stacked in one column at full width, so card sizes and `move` have no effect. Use few, compact cards, and keep tables to three columns. `web` and `image` cards show only a link that opens in the phone's browser.
6. If a tool is refused for lack of permission, do not retry it and do not ask the user to grant it in a card: say in one short `say` that Claude Code's permission setting blocked it and that it can be changed in the app's settings.
7. Before your first tool call, output one short `say` line telling the user what you are about to do, so the screen is not silent while tools run.
"""

class Stats {
    var model: String = "Claude Code"
    var firstMs: Long = 0
    var totalMs: Long = 0
    var inputTokens: Long = 0
    var cachedTokens: Long = 0
    var outputTokens: Long = 0
    var costUsd: Double = 0.0
    var limit: String = ""
}

sealed class Ev {
    /** 화면 수정 언어의 글자 조각 */
    data class Delta(val text: String) : Ev()

    /** 지금 하는 일 (빈 글이면 끝남) */
    data class Activity(val text: String) : Ev()

    /** 일하는 도중에 화면을 고쳐 달라는 요청 (`screen` 도구). 거부된 명령의 이유를 `reply` 로 돌려준다. */
    data class Screen(val ops: String, val reply: CompletableDeferred<String>) : Ev()
}

/** 한 턴을 맡는 엔진: 서버의 Claude Code(SSH) 또는 Claude API 직접 호출. */
interface Runner {
    /** 엔진 이름 (통계 줄에 보인다) */
    val label: String

    /** 한 턴을 돌린다. 글자는 나오는 대로 `send` 로 보낸다. `stopped` 가 참이 되면 멈추고 [STOPPED] 를 던진다. */
    fun run(turnText: String, stopped: () -> Boolean, send: (Ev) -> Unit): Stats
}

/** 도구 사용을 진행 표시에 쓸 말로 바꾼다. */
fun describe(name: String, input: JSONObject?): String {
    fun s(k: String) = (input?.optString(k) ?: "").take(80)
    return when (name) {
        "Bash", "PowerShell" -> "명령 실행: ${s("command")}"
        "Read" -> "파일 읽기: ${s("file_path")}"
        "Write" -> "파일 쓰기: ${s("file_path")}"
        "Edit", "NotebookEdit" -> "파일 고치기: ${s("file_path")}"
        "Glob" -> "폴더 보기: ${s("pattern")}"
        "Grep" -> "글자 찾기: ${s("pattern")}"
        "WebSearch" -> "웹 검색: ${s("query")}"
        "WebFetch" -> "웹 읽기: ${s("url")}"
        else -> name
    }
}

/** `claude -p --output-format stream-json` 이 내는 줄을 읽어 화면 쪽 사건으로 바꾼다. */
class StreamParser(private val send: (Ev) -> Unit, private val now: () -> Long = System::currentTimeMillis) {
    val stats = Stats()
    var failure: String? = null
    var gotResult = false
    var lastText = ""

    /** 서버에서 도는 Claude Code 의 프로세스 번호 (멈출 때 쓴다) */
    @Volatile
    var pid: String? = null
    private val started = now()
    private var lineOpen = false // 마지막으로 보낸 글자가 줄 끝이 아닌가

    fun feed(line: String) {
        if (line.startsWith("KITEPID ")) {
            pid = line.removePrefix("KITEPID ").trim().takeIf { p -> p.isNotEmpty() && p.all { it.isDigit() } }
            return
        }
        val v = try {
            JSONObject(line)
        } catch (_: Exception) {
            return
        }
        when (v.optString("type")) {
            "system" -> if (v.optString("subtype") == "init") {
                v.optString("model").takeIf { it.isNotEmpty() }?.let { stats.model = "Claude Code · $it" }
            }
            "stream_event" -> {
                val e = v.optJSONObject("event") ?: return
                when (e.optString("type")) {
                    "content_block_start" -> {
                        val block = e.optJSONObject("content_block")
                        when (block?.optString("type")) {
                            // 도구를 쓰고 난 뒤 이어지는 글은 새 줄에서 시작해야 한다.
                            "text" -> if (lineOpen) {
                                send(Ev.Delta("\n"))
                                lineOpen = false
                            }
                            "tool_use" -> send(Ev.Activity(describe(block.optString("name"), null)))
                        }
                    }
                    "content_block_delta" -> {
                        val d = e.optJSONObject("delta") ?: return
                        if (d.optString("type") == "text_delta") {
                            val t = d.optString("text")
                            if (t.isNotEmpty()) {
                                if (stats.firstMs == 0L) stats.firstMs = now() - started
                                lineOpen = !t.endsWith("\n")
                                send(Ev.Delta(t))
                            }
                        }
                    }
                }
            }
            // 완성된 응답: 도구에 넘긴 내용까지 알 수 있으므로 진행 표시를 자세히 고친다.
            "assistant" -> {
                val content = v.optJSONObject("message")?.optJSONArray("content") ?: return
                for (i in 0 until content.length()) {
                    val b = content.optJSONObject(i) ?: continue
                    if (b.optString("type") == "tool_use") send(Ev.Activity(describe(b.optString("name"), b.optJSONObject("input"))))
                }
            }
            "user" -> send(Ev.Activity(""))
            "rate_limit_event" -> {
                val r = v.optJSONObject("rate_limit_info") ?: return
                val kind = r.optString("rateLimitType").replace('_', ' ')
                val status = r.optString("status")
                if (kind.isNotEmpty()) stats.limit = if (status == "allowed") kind else "$kind: $status"
            }
            "result" -> {
                gotResult = true
                val u = v.optJSONObject("usage")
                fun n(k: String) = u?.optLong(k) ?: 0L
                stats.cachedTokens = n("cache_read_input_tokens")
                stats.inputTokens = n("input_tokens") + n("cache_creation_input_tokens") + stats.cachedTokens
                stats.outputTokens = n("output_tokens")
                stats.costUsd = v.optDouble("total_cost_usd", 0.0)
                lastText = v.optString("result")
                if (v.optBoolean("is_error")) failure = v.optString("result").ifEmpty { "Claude Code error" }.take(400)
            }
        }
    }

    /** 스트림이 끝났다: 열린 줄을 닫는다. */
    fun finish() {
        if (lineOpen) {
            send(Ev.Delta("\n"))
            lineOpen = false
        }
        stats.totalMs = now() - started
    }
}

class EngineConfig(
    /** 서버에서 Claude Code 를 부르는 이름이나 경로 */
    val claude: String,
    /** Claude Code 를 돌릴 폴더. 비어 있으면 홈 폴더. */
    val dir: String,
    /** default(서버의 Claude Code 설정 그대로), acceptEdits(파일 수정 허용), bypassPermissions(전부 허용) */
    val permission: String,
    val effort: String,
    /** 사용자가 더 주고 싶은 옵션 (예: --model sonnet) */
    val extra: String,
)

const val STOPPED = "__stopped"

class ClaudeCode(private val ssh: Ssh, private val cfg: EngineConfig) : Runner {
    override val label = "Claude Code"

    /** 로그인 셸의 설정을 읽고, 흔한 설치 자리를 PATH 에 더한다 (ssh 로 바로 돌린 명령은 PATH 가 짧다). */
    private fun shell(script: String): String =
        "bash -lc " + shq("export PATH=\"\$HOME/.local/bin:\$HOME/.claude/local:\$HOME/.npm-global/bin:/usr/local/bin:\$PATH\"; $script")

    private val claude: String get() = cfg.claude.trim().ifEmpty { "claude" }

    /** 서버의 Claude Code 버전. 없으면 예외. */
    fun version(): String = ssh.run(shell("$claude --version")).trim().lineSequence().lastOrNull().orEmpty()

    /** 서버의 환경: (운영체제, 홈 폴더). */
    fun where(): Pair<String, String> {
        val lines = ssh.run("uname -sm; echo \"\$HOME\"").trim().lines()
        return (lines.getOrNull(0) ?: "") to (lines.getOrNull(1) ?: "~")
    }

    /** 지시문을 서버에 올리고 기억 보관함 폴더를 만든다. */
    fun prepare(system: String) {
        ssh.run("mkdir -p ~/.kite/vault/기억 && cat > ~/.kite/system.txt", system)
    }

    /** 늘 기억하고 있는 것 (`기억/핵심.md`). 없으면 빈 글. */
    fun core(): String = try {
        ssh.run("cat ~/.kite/vault/기억/핵심.md 2>/dev/null || true").trim()
    } catch (_: Exception) {
        ""
    }

    fun command(): String {
        val a = StringBuilder("$claude -p --output-format stream-json --verbose --include-partial-messages --no-session-persistence --append-system-prompt-file \"\$HOME/.kite/system.txt\"")
        val extra = cfg.extra.trim()
        if (!extra.contains("--effort") && cfg.effort.isNotEmpty()) a.append(" --effort ${shq(cfg.effort)}")
        // Claude Code 의 도구는 꼭 필요한 것만 싣는다. ToolSearch 를 빼면 MCP 도구의 설명이 전부 매 턴 실려 간다.
        if (!extra.contains("--tools")) a.append(" --tools Bash,Read,Edit,Write,Glob,Grep,WebSearch,WebFetch,ToolSearch")
        if (!extra.contains("--permission-mode") && cfg.permission != "default") a.append(" --permission-mode ${shq(cfg.permission)}")
        if (extra.isNotEmpty()) a.append(" ").append(extra)
        val dir = cfg.dir.trim()
        val cd = if (dir.isEmpty() || dir == "~") "cd" else "cd ${if (dir.startsWith("~/")) "\"\$HOME\"/" + shq(dir.removePrefix("~/")) else shq(dir)}"
        // 프로세스 번호를 먼저 알려 주고 그 번호 그대로 Claude Code 로 바뀐다 (멈출 때 그 번호를 끝낸다).
        return shell("$cd && echo \"KITEPID \$\$\" && exec $a")
    }

    /**
     * 한 턴을 돌린다. 글자는 나오는 대로 `send` 로 보낸다. `stopped` 가 참이 되면 서버의 Claude Code 를 끝낸다.
     */
    override fun run(turnText: String, stopped: () -> Boolean, send: (Ev) -> Unit): Stats {
        val parser = StreamParser(send)
        var done = false
        // 멈춤 버튼: 따로 지켜보다가 서버의 Claude Code 를 끝낸다.
        val watcher = Thread {
            while (!done) {
                if (stopped()) {
                    val pid = parser.pid
                    if (pid != null) {
                        try {
                            ssh.run("kill $pid 2>/dev/null; true")
                        } catch (_: Exception) {
                        }
                        return@Thread
                    }
                }
                Thread.sleep(120)
            }
        }
        watcher.start()
        val (code, errors) = try {
            ssh.exec(command(), turnText) { parser.feed(it) }
        } catch (e: Exception) {
            done = true
            ssh.close() // 끊긴 연결은 버리고 다음 턴에 다시 붙는다
            if (stopped()) throw Exception(STOPPED)
            throw Exception("서버와의 연결이 끊겼습니다: ${e.message}")
        }
        done = true
        parser.finish()
        if (stopped()) throw Exception(STOPPED)
        parser.failure?.let { throw Exception("Claude Code: $it") }
        if (!parser.gotResult) {
            val tail = errors.trim().takeLast(300)
            throw Exception(
                if (code == 127 || tail.contains("command not found")) "서버에서 Claude Code 를 찾지 못했습니다. 설정에서 claude 경로를 확인해 주세요. $tail"
                else "Claude Code 가 답 없이 끝났습니다. 서버에서 로그인되어 있는지 확인해 주세요. ($code) $tail"
            )
        }
        return parser.stats
    }
}
