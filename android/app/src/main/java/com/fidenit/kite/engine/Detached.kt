// 서버에서 떼어 돌리는 턴: 안드로이드 API 없이 명령 글과 줄 분류만 만든다 (JVM 시험 가능).
// 서버 폴더 ~/.kite/runs/ 에 실행 하나당 파일 여섯: <id>.in(턴 글) .out(stream-json) .err .exit(끝난 코드) .pid(감싼 bash 의 번호, 살아 있는지 볼 때만 쓴다) .stop(멈춤 요청, 빈 파일).
// 신호는 감싼 bash 가 자기가 띄운 자식에게만 보낸다. 멈춤·치우기·따라 읽기 명령은 어떤 신호도 보내지 않는다: 파일에 적힌 번호가 낡았거나 틀려도 다른 프로세스가 다칠 수 없다.
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

/** 따라 읽기 한 번이 끝난 뒤 할 일. [Dropped] 만 다시 붙는다: 나머지는 서버가 끝났다고 답한 것이라 다시 붙지 않는다. */
sealed class End {
    /** 끝났다는 줄 없이 읽기가 끝났다 = 연결이 끊겼다: 다시 붙어 이어 읽는다. */
    object Dropped : End()

    /** 이미 끝나 치워진 실행이다. */
    object Gone : End()

    /** 서버가 이 실행을 모르고 받은 줄도 없다: 같은 번호로 한 번만 올리고 시작한다. */
    object Restart : End()

    /** 서버가 이 실행을 모르는데 새로 시작할 수도 없다. */
    object NoRecord : End()

    /** 실행이 끝났다: 숫자 코드 / stopped / lost. */
    data class Over(val code: String) : End()
}

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

    /**
     * 따라 읽기가 돌려준 값(`APERITEXIT` 뒤의 글, 그 줄이 없었으면 null)으로 할 일을 정한다.
     * `mayRestart`: 아직 한 번도 다시 시작하지 않았고, 턴 글이 있고, 받은 줄이 없고, 멈춤이 눌리지 않았다.
     */
    fun judgeEnd(code: String?, mayRestart: Boolean): End = when (code) {
        null -> End.Dropped
        "gone" -> End.Gone
        "none" -> if (mayRestart) End.Restart else End.NoRecord
        else -> End.Over(code)
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

    /** 번호 `$1` 이 이 실행의 감싼 bash 인가: 명령 글에 이 실행의 파일 이름이 있는지 본다 (신호는 보내지 않는다). 숫자가 아니거나 없는 프로세스면 아니다. */
    private fun ours(id: String): String =
        "ours() { case \"\$1\" in ''|*[!0-9]*) return 1;; esac; if [ -r \"/proc/\$1/cmdline\" ]; then a=\$(tr '\\0' ' ' < \"/proc/\$1/cmdline\" 2>/dev/null); else a=\$(ps -ww -p \"\$1\" -o args= 2>/dev/null); fi; case \"\$a\" in *\"runs/${Detached.check(id)}.\"*) return 0;; esac; return 1; }"

    /** 올리기. 표준 입력 = 턴 글. 이레 넘은 옛 파일은 지운다. */
    fun upload(id: String): String = serverShell(
        "mkdir -p ~/.kite/runs && find ~/.kite/runs -type f -mtime +7 -delete 2>/dev/null; cat > ${path(id, "in")}",
    )

    /**
     * 서버에서 `bash -c` 로 도는 감싼 글. `claude` 를 자기 자식으로 띄우고 그 번호를 셸 변수(`$!`)로만 갖고 있다가, 0.2초마다 `.stop` 이 생겼는지 본다.
     * 자식이 살아 있는지는 bash 가 스스로 아는 것으로 본다 (`mine`: 자기 자식 `$!` 에 `kill -0`, 신호는 가지 않는다; bash 는 끝난 자식을 바로 거둔다).
     * `ps` 가 답하면 (안 되면 `/proc/<번호>/stat` 을 셸 내장 `read` 로) 부모가 자기이고 좀비가 아닌지도 본다. `ps` 가 실패하거나 그 옵션을 모르면
     * 그 검사만 건너뛴다: 지켜보기를 그만두지 않는다. 세 번 잇달아 없다고 나와야 지켜보기를 끝낸다.
     * 생겼으면 자식과 그 자손(`pgrep -P`, 자손부터)에 TERM, 1초쯤 뒤 아직 자기 자식으로 살아 있으면 KILL. 그 뒤 자기 세션에 남은 것
     * (`pgrep -s $$`: setsid 로 띄웠을 때만 있다)도 끝내고, 자식의 끝난 코드를 `.exit` 에 적는다.
     * 신호를 받는 번호는 `$!` 이거나 `pgrep` 이 방금 준 것뿐이고, 쓰기 전에 `ok` 로 숫자이고 1보다 크고 자기 번호가 아닌지 본다. 파일에서 읽은 번호는 쓰지 않는다.
     * 자기가 TERM 을 받아도(서버가 꺼질 때) 죽지 않고 `.exit` 를 적는다.
     */
    fun runner(id: String): String = """
        g=0; trap 'g=1' TERM
        echo ${'$'}${'$'} > ${path(id, "pid")}
        if [ -e ${path(id, "stop")} ]; then echo stopped > ${path(id, "exit")}; exit 0; fi
        ${claudeLine()} < ${path(id, "in")} > ${path(id, "out")} 2> ${path(id, "err")} &
        c=${'$'}!
        ok() { case "${'$'}1" in ''|*[!0-9]*) return 1;; esac; [ "${'$'}1" -gt 1 ] && [ "${'$'}1" != "${'$'}${'$'}" ]; }
        k1() { ok "${'$'}1" && kill -"${'$'}2" "${'$'}1" 2>/dev/null; }
        kt() { ok "${'$'}1" || return 1; for k in ${'$'}(exec pgrep -P "${'$'}1" 2>/dev/null); do kt "${'$'}k" "${'$'}2"; done; k1 "${'$'}1" "${'$'}2"; }
        sw() { o=0; for k in ${'$'}(exec pgrep -s "${'$'}${'$'}" 2>/dev/null); do k1 "${'$'}k" "${'$'}1" && o=1; done; return 0; }
        mine() {
          ok "${'$'}c" && kill -0 "${'$'}c" 2>/dev/null || return 1
          set -- ${'$'}(ps -o ppid= -o stat= -p "${'$'}c" 2>/dev/null)
          if [ ${'$'}# != 2 ] && [ -r "/proc/${'$'}c/stat" ]; then l=; { read -r l < "/proc/${'$'}c/stat"; } 2>/dev/null; set -- ${'$'}{l##*) }; set -- "${'$'}2" "${'$'}1"; fi
          case "${'$'}1" in ''|*[!0-9]*) return 0;; esac
          [ "${'$'}1" = "${'$'}${'$'}" ] || return 1
          case "${'$'}2" in Z*) return 1;; esac
          return 0
        }
        s=0; m=0
        while [ ${'$'}m -lt 3 ]; do
          if mine; then m=0; else m=${'$'}((m+1)); sleep 0.05; continue; fi
          if [ -e ${path(id, "stop")} ]; then
            s=1; kt "${'$'}c" TERM
            i=0; while [ ${'$'}i -lt 5 ] && mine; do sleep 0.2; i=${'$'}((i+1)); done
            mine && kt "${'$'}c" KILL
            break
          fi
          sleep 0.2
        done
        g=0; wait "${'$'}c"; x=${'$'}?
        while [ "${'$'}g" = 1 ]; do g=0; wait "${'$'}c"; y=${'$'}?; [ "${'$'}y" = 127 ] || x=${'$'}y; done
        [ -e ${path(id, "stop")} ] && s=1
        if [ "${'$'}s" = 1 ]; then sw TERM; if [ "${'$'}o" = 1 ]; then sleep 0.4; sw KILL; fi; fi
        echo ${'$'}x > ${path(id, "exit")}
    """.trimIndent()

    /**
     * 시작. 서버에서 떼어 돌리고 바로 돌아온다: `APERITRUN <id>` 또는 `APERITFAIL <이유>`.
     * 멈춤 요청(`.stop`)이 이미 있으면 아무것도 띄우지 않고 `.exit` 에 `stopped` 를 적는다 (멈춘 뒤 늦게 닿은 시작이 돌지 않게).
     */
    fun start(id: String): String {
        return serverShell(
            """
            [ -f ${path(id, "in")} ] || { echo "APERITFAIL noin"; exit 0; }
            [ -s ${path(id, "pid")} ] && { echo "APERITRUN $id"; exit 0; }
            [ -e ${path(id, "stop")} ] && { [ -s ${path(id, "exit")} ] || echo stopped > ${path(id, "exit")}; echo "APERITRUN $id"; exit 0; }
            ${cd()} 2>/dev/null || { echo "APERITFAIL cd"; exit 0; }
            S=; command -v setsid >/dev/null 2>&1 && S=setsid
            ( ${'$'}S nohup bash -c ${shq(runner(id))} > /dev/null 2>&1 < /dev/null & )
            i=0; while [ ! -s ${path(id, "pid")} ] && [ ${'$'}i -lt 100 ]; do sleep 0.05; i=${'$'}((i+1)); done
            if [ -s ${path(id, "pid")} ]; then echo "APERITRUN $id"; else echo "APERITFAIL start"; fi
            """.trimIndent(),
        )
    }

    /**
     * 따라 읽기: `n` 줄까지 받은 뒤부터. 출력은 이 루프 하나만 쓴다. 돌고 있는지는 `.pid` 의 번호가 이 실행의 감싼 bash 인지로만 본다 (신호를 보내지 않는다).
     * 명령 글을 한 번 확인한 번호는 그 뒤로 있는지만 본다 (`/proc` 이 있으면 프로세스를 띄우지 않고, 없으면 `ps -p`), 10초쯤마다 명령 글을 다시 확인한다.
     * 검사 한 번이 실패했다고 `lost` 라 하지 않는다: 0.3초 간격으로 세 번 잇달아 없고 그 사이 `.exit` 도 생기지 않았을 때만이다.
     */
    fun follow(id: String, n: Int): String {
        require(n >= 0)
        Detached.check(id)
        return serverShell(
            """
            f="${'$'}HOME/.kite/runs/$id"; n=$n; last=-1; beat=0; v=; m=0; q=0
            ${ours(id)}
            [ -f "${'$'}f.in" ] || { if [ -s "${'$'}f.exit" ]; then echo "APERITEXIT gone"; else echo "APERITEXIT none"; fi; exit 0; }
            if [ ! -s "${'$'}f.pid" ] && [ ! -s "${'$'}f.exit" ]; then echo "APERITEXIT none"; exit 0; fi
            while :; do
              fin=0; [ -s "${'$'}f.exit" ] && fin=1
              size=${'$'}(( ${'$'}({ wc -c < "${'$'}f.out"; } 2>/dev/null || echo 0) ))
              if [ "${'$'}size" != "${'$'}last" ]; then
                last=${'$'}size; total=${'$'}(( ${'$'}({ wc -l < "${'$'}f.out"; } 2>/dev/null || echo 0) ))
                if [ "${'$'}total" -gt "${'$'}n" ]; then sed -n "${'$'}((n+1)),${'$'}{total}s/^/>/p" "${'$'}f.out"; n=${'$'}total; beat=0; fi
              fi
              if [ "${'$'}fin" = 1 ]; then echo "APERITEXIT ${'$'}(cat "${'$'}f.exit")"; exit 0; fi
              p=; { read -r p < "${'$'}f.pid"; } 2>/dev/null
              q=${'$'}((q+1))
              if [ -n "${'$'}p" ] && [ "${'$'}p" = "${'$'}v" ] && [ ${'$'}((q % 40)) != 0 ]; then
                if [ -d "/proc/${'$'}${'$'}" ]; then [ -e "/proc/${'$'}p" ] || v=; else ps -p "${'$'}p" > /dev/null 2>&1 || v=; fi
              elif ours "${'$'}p"; then v=${'$'}p
              else v=
              fi
              if [ -z "${'$'}v" ]; then
                m=${'$'}((m+1)); sleep 0.3
                if [ ${'$'}m -ge 3 ] && [ ! -s "${'$'}f.exit" ]; then echo "APERITEXIT lost"; exit 0; fi
                continue
              fi
              m=0
              beat=${'$'}((beat+1)); [ ${'$'}((beat % 40)) = 0 ] && echo "APERITBEAT"
              sleep 0.25
            done
            """.trimIndent(),
        )
    }

    /**
     * 멈춤: 신호를 보내지 않는다. 이 서버가 아는 실행(`.in` 이나 `.pid` 가 있다)이고 아직 끝나지 않았으면 멈춤 요청 파일 `.stop` 을 만들고,
     * 감싼 bash 가 자기 자식을 끝내고 `.exit` 를 적기를 3초쯤 기다린다. `.pid` 의 번호가 이 실행의 감싼 bash 가 아니거나(서버가 다시 켜져
     * 번호가 남의 것이 됐을 수 있다) 시작된 적이 없으면 `.exit` 에 `stopped` 를 적는다. 모르는 번호나 이미 끝난 실행에는 아무것도 하지 않는다.
     */
    fun stop(id: String): String {
        Detached.check(id)
        return serverShell(
            """
            f="${'$'}HOME/.kite/runs/$id"
            ${ours(id)}
            [ -f "${'$'}f.in" ] || [ -f "${'$'}f.pid" ] || exit 0
            [ -s "${'$'}f.exit" ] && exit 0
            : > "${'$'}f.stop"
            i=0; p=
            while [ ${'$'}i -lt 15 ] && [ ! -s "${'$'}f.exit" ]; do
              p=; { read -r p < "${'$'}f.pid"; } 2>/dev/null
              if [ -n "${'$'}p" ]; then ours "${'$'}p" || break; elif [ ${'$'}i -ge 5 ]; then break; fi
              sleep 0.2; i=${'$'}((i+1))
            done
            if [ ! -s "${'$'}f.exit" ] && ! ours "${'$'}p"; then sleep 0.3; [ -s "${'$'}f.exit" ] || echo stopped > "${'$'}f.exit"; fi
            exit 0
            """.trimIndent(),
        )
    }

    /**
     * 치우기: `.in .out .err .pid` 를 지운다. 아직 도는 실행(`.pid` 가 있고 `.exit` 가 없다)이면 멈춤 요청 `.stop` 을 남겨 감싼 bash 가 끝내게 하고,
     * 아니면 `.stop` 도 지운다. `.exit` 는 남긴다 (이레 뒤 정리에서 지워진다): 이미 끝나 치워진 실행에 다시 붙으면
     * `APERITEXIT gone` 이 나오고, 같은 말이 처음부터 다시 돌지 않는다.
     */
    fun clean(id: String): String = serverShell(
        "if [ -s ${path(id, "pid")} ] && [ ! -s ${path(id, "exit")} ]; then : > ${path(id, "stop")}; else rm -f ${path(id, "stop")}; fi; " +
            "rm -f ${path(id, "in")} ${path(id, "out")} ${path(id, "err")} ${path(id, "pid")}",
    )

    /** 표준 오류 끝부분 (치우기 전에 읽는다). */
    fun errTail(id: String): String = serverShell("tail -c 400 ${path(id, "err")} 2>/dev/null; true")
}
