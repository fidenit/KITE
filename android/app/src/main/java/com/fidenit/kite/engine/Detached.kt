// 서버에서 떼어 돌리는 턴: 안드로이드 API 없이 명령 글과 줄 분류만 만든다 (JVM 시험 가능).
// 서버 폴더 ~/.kite/runs/ 에 실행 하나당 파일 다섯: <id>.in(턴 글) .out(stream-json) .err .exit(끝난 코드) .pid(감싼 bash 의 번호).
package com.fidenit.kite.engine

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** 따라 읽기가 내는 줄의 종류. */
sealed class FollowLine {
    /** `.out` 의 줄 하나 (서버가 앞에 `>` 를 붙여 보낸다). 이 줄만 줄 수에 들어간다. */
    data class Data(val text: String) : FollowLine()

    /** 프로세스가 끝났다: 숫자 코드 / stopped / lost / none */
    data class Exit(val code: String) : FollowLine()
    object Beat : FollowLine()
    data class Run(val id: String) : FollowLine()
    data class Fail(val reason: String) : FollowLine()

    /** 로그인 셸이 내는 인사말 따위, 제어 줄도 `>` 줄도 아닌 것 — 무시하고 줄 수에 넣지 않는다. */
    object Other : FollowLine()
}

/** 올리기·시작 명령의 결과를 어떻게 다룰지. */
sealed class Step {
    object Ok : Step()

    /** 연결이 끊겨 결과를 모른다: 다시 붙어 같은 단계를 다시 한다 (시작은 같은 번호로 다시 해도 두 번 돌지 않는다). */
    object Retry : Step()

    /** 서버가 분명히 거절했다: 다시 해도 소용없다. `kind` 는 cd / noin / start / remote. */
    data class Fail(val kind: String, val detail: String) : Step()
}

/** 로그인 셸의 설정을 읽고, 흔한 설치 자리를 PATH 에 더한다 (ssh 로 바로 돌린 명령은 PATH 가 짧다). */
fun serverShell(script: String): String =
    "bash -lc " + shq("export PATH=\"\$HOME/.local/bin:\$HOME/.claude/local:\$HOME/.npm-global/bin:/usr/local/bin:\$PATH\"; $script")

object Detached {
    private val ID = Regex("[A-Za-z0-9-]+")

    /** 새 실행 번호: `20261120-013000-a1b2` */
    fun newId(now: Long = System.currentTimeMillis(), random: Random = Random.Default): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now)) + "-" + "%04x".format(random.nextInt(0x10000))

    fun valid(id: String): Boolean = id.isNotEmpty() && id.length <= 64 && ID.matches(id)

    /** 셸에 넣기 전에 검사한다. 다른 글자가 있으면 거부. */
    fun check(id: String): String {
        require(valid(id)) { "bad run id" }
        return id
    }

    /** 줄 한 개를 분류한다. `.out` 의 줄은 `>` 로 시작하고, `APERIT…` 는 제어 줄이다. 그 밖의 것은 무시한다. */
    fun classify(line: String): FollowLine {
        val l = line.trimEnd('\r', '\n')
        if (l.startsWith(">")) return FollowLine.Data(l.substring(1))
        if (!l.startsWith("APERIT")) return FollowLine.Other
        val word = l.substringBefore(' ')
        val rest = l.substringAfter(' ', "").trim()
        return when (word) {
            "APERITEXIT" -> if (rest.isEmpty()) FollowLine.Other else FollowLine.Exit(rest)
            "APERITBEAT" -> FollowLine.Beat
            "APERITRUN" -> FollowLine.Run(rest)
            "APERITFAIL" -> FollowLine.Fail(rest)
            else -> FollowLine.Other
        }
    }

    /** 받은 줄 중 자료 줄의 수. */
    fun countData(lines: Iterable<String>): Int = lines.count { classify(it) is FollowLine.Data }

    /** 다시 붙는 간격(ms): 1·2·4·8·15·30초, 그 뒤 30초. `attempt` 는 0부터. */
    fun delayMs(attempt: Int): Long = longArrayOf(1, 2, 4, 8, 15, 30)[attempt.coerceIn(0, 5)] * 1000

    /** 올리기 명령의 끝난 코드로 판단한다. 끝난 코드가 없으면(-1, 연결이 끊김) 다시 한다. */
    fun judgeUpload(code: Int, err: String): Step = when {
        code == 0 -> Step.Ok
        code < 0 -> Step.Retry
        else -> Step.Fail("remote", err.trim().takeLast(200))
    }

    /** 시작 명령의 결과로 판단한다: `APERITRUN` 이면 됐고, `APERITFAIL` 이나 분명한 오류 코드만 실패, 나머지는 다시. */
    fun judgeStart(code: Int, out: List<String>, err: String, id: String): Step {
        val lines = out.map { classify(it) }
        if (lines.contains(FollowLine.Run(id))) return Step.Ok
        lines.filterIsInstance<FollowLine.Fail>().firstOrNull()?.let { return Step.Fail(it.reason.ifEmpty { "start" }, "") }
        return if (code > 0) Step.Fail("remote", err.trim().takeLast(200)) else Step.Retry
    }

    /** 이어 읽는 명령이 고장 난 끝난 코드(문법 오류 2, 실행 못 함 126, 명령 없음 127)인가. 그 밖에는 끊김으로 본다. */
    fun followBroken(code: Int): Boolean = code == 2 || code == 126 || code == 127

    /** 40초 동안 아무 줄도 없으면 연결이 죽은 것으로 본다. */
    const val IDLE_MS = 40_000L
}

/** 쉬는 동안 멈춤이나 깨우기 신호가 오면 바로 깬다. */
class Waker {
    private val gate = Semaphore(0)

    fun wake() {
        gate.release()
    }

    fun clear() {
        gate.drainPermits()
    }

    /** `ms` 만큼 쉰다. 깨우기 신호나 `stopped` 가 오면 일찍 돌아온다. 깨우기로 돌아오면 참. */
    fun sleep(ms: Long, stopped: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (true) {
            if (stopped()) return false
            val left = end - System.currentTimeMillis()
            if (left <= 0) return false
            if (gate.tryAcquire(minOf(left, 100L), TimeUnit.MILLISECONDS)) return true
        }
    }
}

/** 서버에 보내는 명령 다섯 가지(와 오류 읽기). 작업 폴더·claude 경로·옵션은 설정에서 온다. */
class DetachedCommands(private val cfg: EngineConfig) {
    private val claude: String get() = cfg.claude.trim().ifEmpty { "claude" }

    private fun path(id: String, ext: String) = "\"\$HOME/.kite/runs/${Detached.check(id)}.$ext\""

    /** 서버에서 도는 `claude` 한 줄 (입출력 방향은 붙이지 않는다). */
    fun claudeLine(): String {
        val a = StringBuilder("$claude -p --output-format stream-json --verbose --include-partial-messages --no-session-persistence --append-system-prompt-file \"\$HOME/.kite/system.txt\"")
        val extra = cfg.extra.trim()
        if (!extra.contains("--effort") && cfg.effort.isNotEmpty()) a.append(" --effort ${shq(cfg.effort)}")
        // Claude Code 의 도구는 꼭 필요한 것만 싣는다. ToolSearch 를 빼면 MCP 도구의 설명이 전부 매 턴 실려 간다.
        if (!extra.contains("--tools")) a.append(" --tools Bash,Read,Edit,Write,Glob,Grep,WebSearch,WebFetch,ToolSearch")
        if (!extra.contains("--permission-mode") && cfg.permission != "default") a.append(" --permission-mode ${shq(cfg.permission)}")
        if (extra.isNotEmpty()) a.append(" ").append(extra)
        return a.toString()
    }

    private fun cd(): String {
        val dir = cfg.dir.trim()
        return if (dir.isEmpty() || dir == "~") "cd" else "cd ${if (dir.startsWith("~/")) "\"\$HOME\"/" + shq(dir.removePrefix("~/")) else shq(dir)}"
    }

    /** 올리기. 표준 입력 = 턴 글. 이레 넘은 옛 파일은 지운다. */
    fun upload(id: String): String = serverShell(
        "mkdir -p ~/.kite/runs && find ~/.kite/runs -type f -mtime +7 -delete 2>/dev/null; cat > ${path(id, "in")}",
    )

    /** 시작. 서버에서 떼어 돌리고 바로 돌아온다: `APERITRUN <id>` 또는 `APERITFAIL <이유>`. */
    fun start(id: String): String {
        val inner = "trap : TERM; echo \$\$ > ${path(id, "pid")}; ${claudeLine()} < ${path(id, "in")} > ${path(id, "out")} 2> ${path(id, "err")}; echo \$? > ${path(id, "exit")}"
        return serverShell(
            """
            [ -f ${path(id, "in")} ] || { echo "APERITFAIL noin"; exit 0; }
            [ -s ${path(id, "pid")} ] && { echo "APERITRUN $id"; exit 0; }
            ${cd()} 2>/dev/null || { echo "APERITFAIL cd"; exit 0; }
            S=; command -v setsid >/dev/null 2>&1 && S=setsid
            ( ${'$'}S nohup bash -c ${shq(inner)} > /dev/null 2>&1 < /dev/null & )
            i=0; while [ ! -s ${path(id, "pid")} ] && [ ${'$'}i -lt 100 ]; do sleep 0.05; i=${'$'}((i+1)); done
            if [ -s ${path(id, "pid")} ]; then echo "APERITRUN $id"; else echo "APERITFAIL start"; fi
            """.trimIndent(),
        )
    }

    /** 따라 읽기: `n` 줄까지 받은 뒤부터. 출력은 이 루프 하나만 쓴다. */
    fun follow(id: String, n: Int): String {
        require(n >= 0)
        Detached.check(id)
        return serverShell(
            """
            f="${'$'}HOME/.kite/runs/$id"; n=$n; last=-1; beat=0; ver=0
            ours() { if [ -r "/proc/${'$'}1/cmdline" ]; then a=${'$'}(tr '\0' ' ' < "/proc/${'$'}1/cmdline" 2>/dev/null); else a=${'$'}(ps -ww -p "${'$'}1" -o args= 2>/dev/null); fi; case "${'$'}a" in *"runs/$id."*) return 0;; esac; return 1; }
            [ -f "${'$'}f.in" ] || { echo "APERITEXIT none"; exit 0; }
            if [ ! -s "${'$'}f.pid" ] && [ ! -s "${'$'}f.exit" ]; then echo "APERITEXIT none"; exit 0; fi
            while :; do
              fin=0; [ -s "${'$'}f.exit" ] && fin=1
              size=${'$'}(( ${'$'}(wc -c < "${'$'}f.out" 2>/dev/null || echo 0) ))
              if [ "${'$'}size" != "${'$'}last" ]; then
                last=${'$'}size; total=${'$'}(( ${'$'}(wc -l < "${'$'}f.out" 2>/dev/null || echo 0) ))
                if [ "${'$'}total" -gt "${'$'}n" ]; then sed -n "${'$'}((n+1)),${'$'}{total}s/^/>/p" "${'$'}f.out"; n=${'$'}total; beat=0; fi
              fi
              if [ "${'$'}fin" = 1 ]; then echo "APERITEXIT ${'$'}(cat "${'$'}f.exit")"; exit 0; fi
              p=; { read -r p < "${'$'}f.pid"; } 2>/dev/null
              alive=0
              if kill -0 "${'$'}p" 2>/dev/null; then if [ "${'$'}ver" = 1 ] || ours "${'$'}p"; then ver=1; alive=1; fi; fi
              if [ "${'$'}alive" = 0 ]; then
                sleep 0.3; [ -s "${'$'}f.exit" ] && continue
                echo "APERITEXIT lost"; exit 0
              fi
              beat=${'$'}((beat+1)); [ ${'$'}((beat % 40)) = 0 ] && echo "APERITBEAT"
              sleep 0.25
            done
            """.trimIndent(),
        )
    }

    /**
     * 멈춤: `.pid` 의 번호가 이 실행의 감싼 bash 인지 확인한 뒤에만 끝낸다 (서버가 다시 켜져 번호가 남의 것이 됐을 수 있다:
     * 아니면 아무것도 끝내지 않고 `stopped` 만 적는다). 묶음에 TERM (감싼 bash 는 `trap : TERM` 이라 살아서 `.exit` 에 143 을 적는다). 1초 안에 안 끝나면
     * 묶음과 자식에 KILL. 그래도 `.exit` 가 없으면 `stopped` 를 적는다.
     */
    fun stop(id: String): String {
        Detached.check(id)
        return serverShell(
        """
        f="${'$'}HOME/.kite/runs/$id"
        ours() { if [ -r "/proc/${'$'}1/cmdline" ]; then a=${'$'}(tr '\0' ' ' < "/proc/${'$'}1/cmdline" 2>/dev/null); else a=${'$'}(ps -ww -p "${'$'}1" -o args= 2>/dev/null); fi; case "${'$'}a" in *"runs/$id."*) return 0;; esac; return 1; }
        [ -s "${'$'}f.exit" ] && exit 0
        i=0; while [ ! -s "${'$'}f.pid" ] && [ -f "${'$'}f.in" ] && [ ${'$'}i -lt 10 ]; do sleep 0.1; i=${'$'}((i+1)); done
        p=${'$'}(cat "${'$'}f.pid" 2>/dev/null)
        case "${'$'}p" in ''|*[!0-9]*) exit 0;; esac
        [ -s "${'$'}f.exit" ] && exit 0
        if ! { kill -0 "${'$'}p" 2>/dev/null && ours "${'$'}p"; }; then sleep 0.3; [ -s "${'$'}f.exit" ] || echo stopped > "${'$'}f.exit"; exit 0; fi
        kt() { for c in ${'$'}(pgrep -P "${'$'}1" 2>/dev/null); do kt "${'$'}c" "${'$'}2"; done; kill -"${'$'}2" "${'$'}1" 2>/dev/null; }
        kill -TERM -- -"${'$'}p" 2>/dev/null || kt "${'$'}p" TERM
        i=0; while [ ${'$'}i -lt 10 ] && [ ! -s "${'$'}f.exit" ] && kill -0 "${'$'}p" 2>/dev/null; do sleep 0.1; i=${'$'}((i+1)); done
        if [ ! -s "${'$'}f.exit" ] && kill -0 "${'$'}p" 2>/dev/null; then kt "${'$'}p" KILL; fi
        kill -KILL -- -"${'$'}p" 2>/dev/null
        sleep 0.2
        [ -s "${'$'}f.exit" ] || echo stopped > "${'$'}f.exit"
        exit 0
        """.trimIndent(),
        )
    }

    /** 치우기: 그 실행의 파일 다섯을 지운다. */
    fun clean(id: String): String = serverShell(
        "rm -f ${path(id, "in")} ${path(id, "out")} ${path(id, "err")} ${path(id, "exit")} ${path(id, "pid")}",
    )

    /** 표준 오류 끝부분 (치우기 전에 읽는다). */
    fun errTail(id: String): String = serverShell("tail -c 400 ${path(id, "err")} 2>/dev/null; true")
}
