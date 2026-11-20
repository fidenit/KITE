// Claude Code 엔진: SSH 로 붙은 서버의 Claude Code(`claude`)를 화면 없이 돌려서 한 턴을 맡긴다.
// 데스크톱의 src/cc.rs 와 같은 방식이고, 실행하는 곳만 서버다. 그래서 서버의 Claude Code 로그인,
// CLAUDE.md, 스킬, 훅, MCP 서버, 권한 설정이 그대로 적용된다.
package com.fidenit.kite.engine

import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/** 이 엔진에서만 덧붙이는 지시. */
const val NOTE = """

# Running inside Claude Code, shown on a phone

You are running as the engine of APERIT through Claude Code on a remote server. The user sees and hears you through the APERIT app on an Android phone that is connected to this server over SSH. This changes the following.

1. Your text output IS the screen. Every line of text you output must be one screen command from the language above (say, tell, topic, card, set, add, ...). Never output prose, markdown, headings or status notes outside a command. Anything you want to tell the user goes in `say` or `tell`. Instructions from other sources about how to format or structure replies (goal declarations, reports, summaries) do not apply to this output: express them, if needed, as cards or a short `say`.
2. Your tools run on the server, not on the phone. Files, commands and the home folder named in `<env>` are the server's. Where the text above names shell, read_file, write_file, edit_file, list_dir, find_text, view_image, web_search or web_fetch, use your Claude Code tools instead (Bash, Read, Write, Edit, Glob, Grep, WebSearch, WebFetch). There is no `screen` tool because your text output already is the screen.
3. These APERIT tools do not exist here: route_find, place_find, board_read, browser_text, browser_eval, job_start, job_list, job_tell, job_stop, job_result, contact_find, message_send, phone_call, message_read. Do not offer what needs them. The whole board always arrives in `<board>`. Do not make route cards.
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

    private val started = now()
    private var lineOpen = false // 마지막으로 보낸 글자가 줄 끝이 아닌가

    fun feed(line: String) {
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
                // 한 프로세스가 result 를 두 번 낼 수 있다 (도구가 뒤로 밀렸다가 끝난 뒤 이어 갈 때): 마지막 것이 맞다.
                failure = if (v.optBoolean("is_error")) v.optString("result").ifEmpty { "Claude Code error" }.take(400) else null
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

/**
 * 서버에서 claude 가 결과 없이, 또는 오류로 끝났을 때 보여 줄 글. `code` 는 끝난 코드, `tail` 은 서버가 남긴 표준 오류의 끝.
 * 서버 계정이 root 이면 Claude Code 가 권한 '전부'를 거절하는데, 그때 나는 글자를 알아보고 이유를 말해 준다.
 */
fun failureMessage(code: String, gotResult: Boolean, tail: String): String = when {
    code != "0" && tail.contains("cannot be used with root") ->
        "서버 계정이 root라 권한 '전부'는 Claude Code가 거절합니다 → 이 말은 실행되지 않았습니다 → 설정의 권한을 '파일 수정'이나 '기본'으로 바꾸세요."
    !gotResult && (code == "127" || tail.contains("command not found")) -> "서버에서 Claude Code 를 찾지 못했습니다. 설정에서 claude 경로를 확인해 주세요. $tail"
    !gotResult -> "Claude Code 가 답 없이 끝났습니다. 서버에서 로그인되어 있는지 확인해 주세요. ($code) $tail"
    else -> "Claude Code 가 오류로 끝났습니다 → 받은 데까지는 화면에 반영했습니다 → 같은 말을 다시 보내 보세요. $tail"
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

/** 이 실행은 이미 끝나 서버에서 치워졌다 (끝난 코드만 남았다): 새로 시작하지 않는다. 앱은 저장해 둔 보드를 그대로 쓴다. */
const val GONE = "__gone"

/** 앱 쪽이 이 엔진을 버렸다 (화면이 닫혀 뷰모델이 없어졌다): 서버의 실행은 그대로 두고 따라 읽기만 그만둔다. */
const val ABANDONED = "__abandoned"

class ClaudeCode(private val ssh: Ssh, private val cfg: EngineConfig) : Runner {
    override val label = "Claude Code"

    private val cmds = DetachedCommands(cfg)
    private val waker = Waker()
    private val leftover = CopyOnWriteArrayList<String>()

    /**
     * 새 실행 번호가 정해지면 (네트워크를 쓰기 전에) 불린다. 앱은 이 번호를 디스크에 적어 두었다가 다시 켜졌을 때 [resume] 에 쓴다.
     * 여기서 예외가 나면 (번호를 적지 못했다) 서버에 아무것도 올리지 않고 그 예외로 턴이 끝난다.
     */
    @Volatile
    var onRunId: ((String) -> Unit)? = null

    /** 서버에서 실행이 시작된 것을 확인하면 불린다. 앱은 기록에 적어 두어, 그 뒤로는 서버에 실행이 없다고 해도 다시 시작하지 않는다. */
    @Volatile
    var onStarted: ((String) -> Unit)? = null

    /** 지금(또는 마지막으로) 돌린 실행의 번호 */
    @Volatile
    var runId: String? = null
        private set

    /** 지금(또는 마지막으로) 돌린 실행에서 받은 자료 줄 수. 앱은 이 수를 저장해 두었다가 [resume] 에 넘길 수 있다. */
    @Volatile
    var linesReceived: Int = 0
        private set

    /** 시험용: 받아서 처리한 `.out` 줄을 하나씩 알려 준다 (서버의 파일과 줄마다 맞춰 보려고). */
    @Volatile
    var onDataLine: ((String) -> Unit)? = null

    /** 끝난 실행을 서버에서 치울까. 시험에서만 끈다 (서버의 `.out` 을 읽어 보려고). */
    @Volatile
    var cleanAfterRun = true

    /** 멈췄지만 서버에 닿지 않아 멈춤·치우기를 못 한 실행의 번호. 다음에 붙을 때 처리한다. */
    val toClean: List<String> get() = leftover.toList()

    fun addToClean(id: String) {
        if (Detached.valid(id)) leftover.addIfAbsent(id)
    }

    /** 다른 연결로 이미 멈추고 치운 실행은 목록에서 뺀다. */
    fun forget(ids: Collection<String>) {
        leftover.removeAll(ids.toSet())
    }

    /** 서버에서 끝난 것이 확인된 실행 (끝났다는 줄을 받았거나, 멈춤이 서버에 닿았다) */
    private val ended: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** 이 실행이 서버에서 끝난 것을 확인했는가. 멈춤을 눌렀는데 거짓이면 멈춤이 서버에 닿지 않은 것이다. */
    fun endedOnServer(id: String): Boolean = ended.contains(id)

    @Volatile
    private var abandoned = false

    /**
     * 이 엔진을 더 쓰지 않는다: 돌고 있는 [run]·[resume] 은 서버의 실행을 멈추지 않고 따라 읽기만 그만두고 [ABANDONED] 를 던진다.
     * (서버의 실행은 `inflight.json` 으로 다음에 다시 붙는다.)
     */
    fun abandon() {
        abandoned = true
        waker.wake()
    }

    /** 다시 붙기를 기다리는 중이면 바로 시도한다 (앱이 앞으로 돌아왔을 때). */
    fun wake() = waker.wake()

    private fun shell(script: String): String = serverShell(script)

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

    /** 서버에서 떼어 돌린 실행의 파일을 치운다. */
    fun clean(id: String) {
        ssh.run(cmds.clean(id))
    }

    /**
     * 앱이 보드를 저장하고 기록을 지운 뒤에 부른다 (그 전에 치우면 앱이 그 사이에 꺼졌을 때 턴이 다시 돌 수 있다).
     * 5초 안에 못 하면 치울 목록에 둔다. 치웠으면 참. 짧은 명령 둘이 5초를 넘겼다면 그 연결은 다음 턴에도 못 쓴다: 연결을 끊어
     * 다음 턴이 바로 다시 붙게 한다 (그대로 두면 다음 턴이 죽은 연결에서 1분쯤 매달린다; 끊긴 연결은 턴이 스스로 다시 붙어 받은 줄 다음부터 이어 읽는다).
     */
    fun cleanRun(id: String): Boolean {
        val ok = bounded(5000) {
            // 멈춤이 닿지 않았던 실행은 먼저 멈춤 요청을 적는다. 감싼 bash 가 끝내기를 기다리지 않는다 (돌고 있는 실행을 치워도 멈춤 요청은 남아 그 실행은 끝난다)
            if (leftover.contains(id) && !ended.contains(id)) sendStop(id, java.util.concurrent.atomic.AtomicBoolean(), wait = false)
            ssh.run(cmds.clean(id))
        }
        if (ok) {
            leftover.remove(id)
            ended.remove(id)
        } else {
            addToClean(id)
        }
        return ok
    }

    /**
     * 한 턴을 서버에서 떼어 돌린다: 올리기 → 시작 → 따라 읽기. 연결이 끊겨도 서버의 Claude Code 는 계속 돌고,
     * 이 함수는 다시 붙어 받은 줄 다음부터 이어 읽는다. 글자는 나오는 대로 `send` 로 보낸다.
     * `stopped` 가 참이 되면 서버의 Claude Code 와 그 자식을 끝내고 [STOPPED] 를 던진다.
     */
    override fun run(turnText: String, stopped: () -> Boolean, send: (Ev) -> Unit): Stats {
        val id = Detached.newId()
        runId = id
        onRunId?.invoke(id) // 번호를 적지 못했으면 여기서 끝난다: 적히지 않은 실행을 서버에 만들지 않는다
        return Drive(id, turnText, 0, true, stopped, send).go()
    }

    /**
     * 이미 서버에 있는 실행에 다시 붙는다 (앱이 다시 켜진 뒤). `fromLine` 줄은 이미 받았다고 본다.
     * 서버에 그 실행이 없으면(`none`) `fromLine` 이 0 이고 `turnText` 가 있을 때만 같은 번호로 한 번 새로 시작한다.
     */
    fun resume(id: String, turnText: String?, fromLine: Int, stopped: () -> Boolean, send: (Ev) -> Unit): Stats {
        Detached.check(id)
        runId = id
        return Drive(id, turnText, fromLine, false, stopped, send).go()
    }

    /** 멈춤을 못 한 채 남은 실행들에 멈춤·치우기를 한다. 연결이 안 되면 목록에 그대로 둔다. */
    fun settle(except: String? = null) {
        for (id in leftover.toList()) {
            if (id == except) continue
            if (bounded(8000) {
                    sendStop(id, java.util.concurrent.atomic.AtomicBoolean(), wait = false)
                    ssh.run(cmds.clean(id))
                }
            ) {
                leftover.remove(id)
            }
        }
    }

    /** 멈춤 요청이 적혔다는 줄을 받았으니 멈춤 명령을 더 기다리지 않는다 (서버의 명령은 남아서 끝까지 돈다). */
    private class StopSent : RuntimeException()

    /**
     * 멈춤 명령을 돌린다. 멈춤 요청이 서버에 적혔다는 줄을 받으면 `sent` 가 참이 된다 (그 뒤에 연결이 끊기거나 시간이 넘어도 멈춤은 닿은 것이다).
     * `wait` 가 참이면 명령이 끝날 때까지 (감싼 bash 가 실행을 끝내기를 3초쯤 기다린다), 거짓이면 그 줄까지만 기다린다.
     * 줄 없이 0 으로 끝나면 멈출 것이 없는 실행이다 (모르는 번호거나 이미 끝났다). 그 밖에는 예외.
     */
    private fun sendStop(id: String, sent: java.util.concurrent.atomic.AtomicBoolean, wait: Boolean) {
        try {
            val (code, err) = ssh.exec(cmds.stop(id), null) {
                if (it.trim() == Detached.STOP_SENT) {
                    sent.set(true)
                    if (!wait) throw StopSent()
                }
            }
            if (sent.get()) return
            if (code < 0) throw java.io.IOException("서버와의 연결이 끊겼습니다")
            if (code != 0) throw RemoteFail(err.trim().takeLast(300).ifEmpty { "끝난 코드 $code" })
        } catch (_: StopSent) {
        }
    }

    /**
     * 서버에 짧은 명령을 `ms` 안에 돌린다. 못 하면 연결을 끊어 막힌 곳을 풀고 거짓을 돌려준다 (반쯤 죽은 연결에서 오래 매달리지 않으려고:
     * 같은 연결을 쓰는 턴은 끊긴 것을 바로 알고 다시 붙는다).
     */
    private fun bounded(ms: Long, work: () -> Unit): Boolean {
        var ok = false
        val t = Thread {
            try {
                work()
                ok = true
            } catch (_: Exception) {
            }
        }.apply {
            isDaemon = true
            start()
        }
        t.join(ms)
        if (t.isAlive) {
            ssh.close()
            t.join(500)
            return false
        }
        return ok
    }

    /**
     * 서버에 멈춤을 보낸다. 닿지 않으면 8초까지 다시 붙어 보고 포기한다. 멈춤 요청이 서버에 적혔으면 참:
     * 감싼 bash 가 실행을 끝내는 데 더 걸려 명령이 시간 안에 끝나지 않아도 닿은 것이다.
     */
    private fun stopRun(id: String): Boolean {
        val end = System.currentTimeMillis() + 8_000
        // 다른 스레드가 연결하는 중이면 그 연결을 그만두게 한다: 그 연결이 끝나기를 기다리느라 멈춤에 쓸 시간을 잃지 않는다.
        // 이미 연결돼 있으면 그대로 쓴다.
        if (!ssh.connected) {
            ssh.close()
            Thread.sleep(100) // 그만둔 쪽이 자기 뒷정리(연결 끊기)를 마칠 틈
        }
        val sent = java.util.concurrent.atomic.AtomicBoolean(false)
        while (true) {
            val left = end - System.currentTimeMillis()
            if (left < 1500) return false
            var give = false
            val ok = bounded(left) {
                try {
                    ssh.connect(minOf(left, 5000L).toInt())
                    sendStop(id, sent, wait = true)
                } catch (e: SshFatal) {
                    give = true
                    throw e
                }
            }
            if (ok || sent.get()) return true
            if (give) return false
            ssh.close()
            if (end - System.currentTimeMillis() < 2500) return false
            Thread.sleep(1000)
        }
    }

    private class Fatal(message: String) : Exception(message)

    private enum class Phase { UPLOAD, START, FOLLOW }

    private inner class Drive(
        val id: String,
        val turnText: String?,
        fromLine: Int,
        fresh: Boolean,
        val stopped: () -> Boolean,
        val send: (Ev) -> Unit,
    ) {
        val parser = StreamParser(send)

        @Volatile private var phase = if (fresh) Phase.UPLOAD else Phase.FOLLOW

        @Volatile private var reading = false

        @Volatile private var lastLine = System.currentTimeMillis()

        @Volatile private var idleCut = false

        @Volatile private var done = false

        @Volatile private var attempt = 0
        private var received = fromLine

        init {
            linesReceived = fromLine
        }

        private var uploadTried = false
        private var restarted = false
        private var settled = false
        private var dropShown = false
        private var broken = 0
        private var stopTried = false
        private var stopOk = false

        /** 멈춤을 보내기 시작한 때 (0 = 아직 아니다): 멈춤 뒤의 치우기에 쓸 시간을 여기서부터 10초 안으로 잡는다. */
        @Volatile private var stopAt = 0L

        @Synchronized
        private fun stopOnce(): Boolean {
            if (!stopTried) {
                stopTried = true
                stopAt = System.currentTimeMillis()
                stopOk = stopRun(id)
                if (stopOk) ended.add(id)
            }
            return stopOk
        }

        /** 멈춤 버튼을 지켜보고, 읽는 중 40초 동안 아무 줄도 없으면 연결을 끊는다. */
        private val watcher = Thread {
            try {
                while (!done) {
                    if (stopped()) {
                        if (phase == Phase.FOLLOW) {
                            // 서버에 멈춤을 보낸다. 닿지 않으면 바로 연결을 끊어 읽기를 풀어 준다.
                            if (stopOnce()) waitDone(1500) // 서버가 끝났다고 알려 주면 읽기가 저절로 끝난다
                        } else {
                            // 올리기·시작 중에는 여기서 서버에 멈춤을 보내지 않는다 (시작과 겹치면 놓친다): 막힌 단계만 풀어 주고 본 흐름이 멈춘다.
                            waitDone(500)
                        }
                        if (!done) ssh.close()
                        return@Thread
                    }
                    if (reading && !idleCut && System.currentTimeMillis() - lastLine > Detached.IDLE_MS) {
                        idleCut = true
                        ssh.close()
                    }
                    Thread.sleep(120)
                }
            } catch (_: InterruptedException) {
            }
        }.apply { isDaemon = true }

        private fun waitDone(ms: Long) {
            val end = System.currentTimeMillis() + ms
            while (!done && System.currentTimeMillis() < end) Thread.sleep(50)
        }

        fun go(): Stats {
            watcher.start()
            try {
                return loop()
            } finally {
                done = true
                watcher.interrupt()
            }
        }

        private fun loop(): Stats {
            while (true) {
                if (abandoned) throw Exception(ABANDONED)
                if (stopped()) return stoppedEnd()
                // 이 try 안에서 난 예외만 끊긴 연결로 보고 다시 붙는다. 서버가 끝났다고 답한 뒤의 처리(아래)는 try 밖이다:
                // 그 결과(GONE·STOPPED·사라짐·실패 글·Stats)는 한 번만 나가고, 다시 붙거나 다시 시작하지 않는다.
                val code: String? = try {
                    ssh.connect()
                    if (!settled) {
                        settled = true
                        settle(id)
                    }
                    if (phase == Phase.UPLOAD) {
                        uploadTried = true
                        val (code, err) = ssh.exec(cmds.upload(id), turnText) {}
                        when (val st = Detached.judgeUpload(code, err)) {
                            is Step.Ok -> phase = Phase.START
                            is Step.Retry -> throw java.io.IOException("upload cut")
                            is Step.Fail -> throw Fatal("서버에 이 말을 저장하지 못했습니다 → 이번 말은 보내지 않았습니다 → 서버의 저장 공간과 폴더 권한을 확인한 뒤 다시 보내 주세요. (${st.detail})")
                        }
                    }
                    if (stopped()) continue
                    if (phase == Phase.START) {
                        val out = mutableListOf<String>()
                        val (code, err) = ssh.exec(cmds.start(id), null) { out.add(it) }
                        when (val st = Detached.judgeStart(code, out, err, id)) {
                            is Step.Ok -> {
                                phase = Phase.FOLLOW
                                try {
                                    onStarted?.invoke(id)
                                } catch (_: Exception) {
                                }
                            }
                            // 서버에서 이미 시작됐을 수 있다: 같은 번호로 다시 시작해도 두 번 돌지 않는다
                            is Step.Retry -> throw java.io.IOException("start cut")
                            is Step.Fail -> throw Fatal(startFailure(st.kind, st.detail))
                        }
                    }
                    if (stopped()) continue
                    follow()
                } catch (e: Fatal) {
                    throw e
                } catch (e: SshFatal) {
                    throw e
                } catch (e: Exception) {
                    dropped()
                    continue
                }
                when (val end = Detached.judgeEnd(code, !restarted && turnText != null && received == 0 && !stopped())) {
                    // 끝났다는 줄 없이 읽기가 끝났다 = 연결이 끊겼다
                    is End.Dropped -> dropped()
                    is End.Gone -> throw Exception(GONE)
                    is End.Restart -> {
                        restarted = true
                        phase = Phase.UPLOAD
                        uploadTried = false
                    }
                    is End.NoRecord -> throw Fatal("서버에 이 작업의 기록이 없습니다 (이미 끝나 치워졌거나 7일이 지났습니다) → 화면에는 반영하지 않았습니다 → 같은 말을 다시 보내 주세요.")
                    is End.Over -> return finish(end.code)
                }
            }
        }

        /** 끊긴 연결은 버리고, 다시 붙기 전에 쉰다 (멈춤이 눌렸으면 쉬지 않는다). 엔진이 버려졌으면 [ABANDONED] 를 던진다. */
        private fun dropped() {
            ssh.close()
            if (abandoned) throw Exception(ABANDONED)
            if (stopped()) return
            val wait = Detached.delayMs(attempt)
            attempt++
            dropShown = true
            val what = if (idleCut) "서버에서 한동안 소식이 없습니다 → 연결을 끊고 다시 붙는 중입니다 → 할 일은 없습니다" else "연결이 끊겼습니다 → 서버에서는 계속 일하는 중입니다 → 다시 연결되면 이어서 받습니다"
            idleCut = false
            send(Ev.Activity(what))
            waker.sleep(wait) { stopped() || abandoned }
        }

        /** 따라 읽는다. 서버가 끝났다고 알리면 그 값(숫자·stopped·lost·none), 끝났다는 줄 없이 끝나면 null. */
        private fun follow(): String? {
            var exit: String? = null
            var any = false
            waker.clear()
            lastLine = System.currentTimeMillis()
            idleCut = false
            reading = true
            if (dropShown) {
                dropShown = false
                send(Ev.Activity(""))
            }
            try {
                val (rc, _) = ssh.exec(cmds.follow(id, received), null, completeLinesOnly = true) { raw ->
                    lastLine = System.currentTimeMillis()
                    attempt = 0
                    when (val c = Detached.classify(raw)) {
                        is FollowLine.Data -> {
                            any = true
                            received++
                            linesReceived = received
                            try {
                                onDataLine?.invoke(c.text)
                                parser.feed(c.text)
                            } catch (e: Exception) {
                                addToClean(id)
                                throw Fatal("받은 내용을 화면에 반영하지 못했습니다 → 이 말을 중단했고 서버의 작업은 다음에 연결할 때 멈춥니다 → 같은 말을 다시 보내 주세요.")
                            }
                        }
                        is FollowLine.Exit -> exit = c.code
                        is FollowLine.Beat -> any = true
                        else -> {}
                    }
                }
                // 이어 읽는 명령 자체가 고장 났는지는 끝난 코드로만 판단한다 (글자가 났다고 고장이 아니다). 모르는 코드는 끊김으로 본다.
                broken = if (exit == null && !any && Detached.followBroken(rc)) broken + 1 else 0
                if (broken >= 3) {
                    addToClean(id)
                    throw Fatal("서버에서 결과를 읽어 오지 못합니다 → 서버의 작업은 다음에 연결할 때 멈춥니다 → 서버의 bash 설정을 확인한 뒤 같은 말을 다시 보내 주세요.")
                }
                return exit
            } finally {
                reading = false
            }
        }

        private fun cleanQuiet() {
            if (!cleanAfterRun) return
            // 멈춤을 누른 뒤라면 누른 때부터 10초쯤 안에 돌아가야 한다: 남은 시간만 쓰고, 모자라면 치울 목록에 두어 다음에 치운다
            val limit = if (stopAt == 0L) 5000L else minOf(5000L, stopAt + 10_000 - System.currentTimeMillis())
            if (limit < 500 || !bounded(limit) { ssh.run(cmds.clean(id)) }) addToClean(id)
        }

        private fun errTail(): String {
            var t = ""
            bounded(5000) { t = ssh.run(cmds.errTail(id)).trim().takeLast(300) }
            return t
        }

        /** 멈춤: 서버에 아무것도 올리지 않았으면 그냥 끝내고, 아니면 서버의 실행을 멈춘다 (못 하면 나중에 치울 목록에). */
        private fun stoppedEnd(): Nothing {
            parser.finish()
            if (phase == Phase.UPLOAD && !uploadTried) {
                ended.add(id) // 서버에 올린 것이 없다: 멈출 것도 없다
                throw Exception(STOPPED)
            }
            if (stopOnce()) cleanQuiet() else addToClean(id)
            throw Exception(STOPPED)
        }

        private fun finish(code: String): Stats {
            ended.add(id) // 끝났다는 줄을 받았다
            parser.finish()
            if (stopped() || code == "stopped") {
                cleanQuiet()
                throw Exception(STOPPED)
            }
            if (code == "lost") {
                cleanQuiet()
                throw Exception("서버에서 이 작업이 중간에 사라졌습니다 (서버가 다시 켜졌을 수 있습니다) → 받은 데까지는 화면에 반영했습니다 → 같은 말을 다시 보내 주세요.")
            }
            val ok = code == "0" && parser.gotResult
            val tail = if (ok) "" else errTail()
            cleanQuiet()
            parser.failure?.let { throw Exception("Claude Code: $it") }
            if (!ok) throw Exception(failureMessage(code, parser.gotResult, tail))
            return parser.stats
        }

        private fun startFailure(kind: String, detail: String): String = when (kind) {
            "cd" -> "서버에서 작업 폴더(${cfg.dir.trim().ifEmpty { "~" }})로 갈 수 없습니다 → 이번 말은 실행되지 않았습니다 → 설정에서 작업 폴더를 확인해 주세요."
            "noin" -> "서버에 말이 제대로 올라가지 않았습니다 → 이번 말은 실행되지 않았습니다 → 다시 보내 주세요."
            else -> "서버에서 Claude Code 를 시작하지 못했습니다 → 이번 말은 실행되지 않았습니다 → 서버를 확인한 뒤 다시 보내 주세요.${if (detail.isNotEmpty()) " ($detail)" else ""}"
        }
    }
}
