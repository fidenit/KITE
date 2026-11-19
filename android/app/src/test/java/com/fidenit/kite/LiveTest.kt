package com.fidenit.kite

import com.fidenit.kite.core.Author
import com.fidenit.kite.core.Board
import com.fidenit.kite.core.Chunk
import com.fidenit.kite.core.Lang
import com.fidenit.kite.core.LineStream
import com.fidenit.kite.core.partsTable
import com.fidenit.kite.engine.ClaudeCode
import com.fidenit.kite.engine.EngineConfig
import com.fidenit.kite.engine.Ev
import com.fidenit.kite.engine.NOTE
import com.fidenit.kite.engine.STOPPED
import com.fidenit.kite.engine.Server
import com.fidenit.kite.engine.Ssh
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 실제 SSH 서버에 붙어 Claude Code 로 턴을 돌려 본다. 앱이 쓰는 것과 같은 코드다.
 * 환경 변수가 있을 때만 돈다:
 *   APERIT_SSH_HOST, APERIT_SSH_PORT, APERIT_SSH_USER
 *   APERIT_SSH_KEY       개인 키 파일. 없으면 새로 만들고, 공개 키를 APERIT_SSH_AUTHKEYS 파일에 덧붙인다.
 *   APERIT_SSH_PASSWORD  암호로 붙을 때
 *   APERIT_EXTRA         claude 에 더 줄 옵션 (예: --model sonnet)
 */
class LiveTest {
    private fun env(k: String) = System.getenv(k).orEmpty()

    /** 이 서버를 쓰는 사람의 말로 기록되지 않게 시험 입력 앞에 붙인다. */
    private val tag = "[f045 시험 — 대표 말 아님] "

    private fun server(fingerprint: String = ""): Server {
        var key = env("APERIT_SSH_KEY").takeIf { it.isNotEmpty() }?.let { File(it).readText() }.orEmpty()
        if (key.isEmpty() && env("APERIT_SSH_AUTHKEYS").isNotEmpty()) {
            val (prv, pub) = Ssh.newKey("kite-test")
            File(env("APERIT_SSH_AUTHKEYS")).appendText(pub + "\n")
            key = prv
        }
        return Server(env("APERIT_SSH_HOST"), env("APERIT_SSH_PORT").toIntOrNull() ?: 22, env("APERIT_SSH_USER"), env("APERIT_SSH_PASSWORD"), key, "", fingerprint)
    }

    /** 앱이 하는 것처럼 줄을 보드에 반영한다. */
    private class Applier {
        val board = Board()
        private val stream = LineStream()
        val rejected = mutableListOf<String>()
        val tells = mutableListOf<String>()
        val activity = mutableListOf<String>()

        private fun line(l: String) {
            if (l.trim().startsWith("tell ")) tells.add(l)
            try {
                Lang.parse(l)?.let { board.apply(it, Author.Ai) }
            } catch (e: Exception) {
                rejected.add("$l → ${e.message}")
            }
        }

        fun send(ev: Ev) {
            when (ev) {
                is Ev.Delta -> stream.feed(ev.text).forEach { if (it is Chunk.Line) line(it.text) }
                is Ev.Activity -> if (ev.text.isNotEmpty()) activity.add(ev.text)
                is Ev.Screen -> ev.reply.complete("반영됨") // Claude Code 엔진은 screen 도구를 쓰지 않는다
            }
        }

        fun finish() {
            stream.finish()?.let { line(it) }
        }
    }

    private class Rig(val ssh: Ssh, val cc: ClaudeCode, val os: String, val home: String)

    private fun rig(): Rig {
        assumeTrue(env("APERIT_SSH_HOST").isNotEmpty())
        val ssh = Ssh(server()) {}
        val cc = ClaudeCode(ssh, EngineConfig("claude", "~", "default", "low", env("APERIT_EXTRA")))
        ssh.connect()
        val (os, home) = cc.where()
        cc.prepare(File("../../src/prompt.md").readText().replace("{{PARTS}}", partsTable().trimEnd()) + NOTE)
        return Rig(ssh, cc, os, home)
    }

    private fun turn(r: Rig, input: String) =
        "<board>\n</board>\n<voice>on</voice>\n<env>\n지금: 2026-11-15 (일) 10:00\n운영체제: ${r.os} (Claude Code 가 도는 서버. 사용자의 화면은 안드로이드 폰)\n사용자 홈 폴더: ${r.home}\n기억 보관함 폴더: ${r.home}/.kite/vault\n</env>\n<input>$tag$input</input>"

    /** 서버에 그 실행의 프로세스(감싼 bash, claude)가 남았는지. 패턴의 `[.]` 는 이 검사 명령 자신이 걸리지 않게 하는 것. */
    private fun leftOf(r: Rig, id: String): String = r.ssh.run("pgrep -af '[.]kite/runs/$id[.]' || true").trim()

    @Test
    fun turnOverSsh() {
        assumeTrue(env("APERIT_SSH_HOST").isNotEmpty())
        var seen = ""
        val srv = server()
        val ssh = Ssh(srv) { seen = it }
        val cc = ClaudeCode(ssh, EngineConfig("claude", "~", "default", "low", env("APERIT_EXTRA")))
        ssh.connect()
        assertTrue("서버 지문을 기억해야 한다", seen.startsWith("SHA256:"))
        val (os, home) = cc.where()
        println("서버: $os, 홈 $home, Claude Code ${cc.version()}")

        // 기억한 지문과 다르면 붙지 않는다
        val wrong = Ssh(Server(srv.host, srv.port, srv.user, srv.password, srv.privateKey, "", "SHA256:wrong")) {}
        assertTrue(runCatching { wrong.connect() }.exceptionOrNull()?.message.orEmpty().contains("지문"))

        cc.prepare(File("../../src/prompt.md").readText().replace("{{PARTS}}", partsTable().trimEnd()) + NOTE)

        // 한 턴: 화면 수정 언어가 줄 단위로 와서 보드에 반영되어야 한다
        val a = Applier()
        val text = "<board>\n</board>\n<voice>on</voice>\n<env>\n지금: 2026-11-15 (일) 10:00\n운영체제: $os (Claude Code 가 도는 서버. 사용자의 화면은 안드로이드 폰)\n사용자 홈 폴더: $home\n기억 보관함 폴더: $home/.kite/vault\n</env>\n<input>${tag}다음 주 제주 2박 3일 여행 준비물을 체크리스트로 만들어 줘. 다섯 개만.</input>"
        val stats = cc.run(text, { false }, a::send)
        a.finish()
        println("${stats.model}  첫 반응 ${stats.firstMs}ms  전체 ${stats.totalMs}ms  입력 ${stats.inputTokens} 출력 ${stats.outputTokens}  ${stats.limit}")
        println("도구: ${a.activity}")
        println("거부된 줄: ${a.rejected}")
        println("소리로 읽을 말: ${a.tells}")
        println("say: ${a.board.say}")
        println(Lang.serialize(a.board))
        assertTrue("카드가 만들어져야 한다", a.board.cards.isNotEmpty())
        assertTrue("say 나 tell 이 있어야 한다", a.board.say.isNotEmpty() || a.tells.isNotEmpty())
        assertEquals("끝난 실행은 서버에서 치워져야 한다", "", ssh.run("ls ~/.kite/runs/${cc.runId}.* 2>/dev/null || true").trim())

        // 멈춤: 서버의 Claude Code 가 끝나야 한다
        var stop = false
        val started = System.currentTimeMillis()
        Thread {
            Thread.sleep(2500)
            stop = true
        }.start()
        val err = runCatching {
            cc.run("<board>\n</board>\n<input>${tag}1부터 400까지의 숫자를 한 줄에 하나씩, 각각 note 카드로 만들어 줘.</input>", { stop }) {}
        }.exceptionOrNull()
        println("멈춤까지 ${System.currentTimeMillis() - started}ms: ${err?.message}")
        assertEquals(STOPPED, err?.message)
        Thread.sleep(500)
        // 패턴의 `[a]` 는 이 검사 명령 자신이 걸리지 않게 하는 것
        val left = ssh.run("pgrep -f '[a]ppend-system-prompt-file.*[.]kite/system.txt' || true").trim()
        assertEquals("남은 프로세스가 없어야 한다", "", left)
        assertEquals("멈춘 실행의 프로세스가 없어야 한다", "", leftOf(Rig(ssh, cc, os, home), cc.runId!!))
        assertEquals("멈춘 실행도 서버에서 치워져야 한다", "", ssh.run("ls ~/.kite/runs/${cc.runId}.* 2>/dev/null || true").trim())
        ssh.close()
    }

    /** (a) 도구를 쓰는 동안 연결을 끊는다 → 엔진이 스스로 다시 붙어 끝까지 받는다. (b) 끝난 실행을 0번 줄부터 다시 받으면 같은 보드. (d) 없는 번호. */
    @Test
    fun dropReplayUnknown() {
        val r = rig()
        r.cc.cleanAfterRun = false
        val a = Applier()
        var dropped = 0L
        var gotTool = false
        val fed = mutableListOf<String>() // 엔진이 처리한 `.out` 줄 그대로
        val t0 = System.currentTimeMillis()
        val input = "먼저 say 한 줄을 내고, Bash 로 \"for i in 1 2 3 4 5 6 7 8; do date; sleep 3; done\" 를 한 번 실행한 다음, 카드 한 장에 '끝'이라고 써 줘."
        r.cc.onDataLine = { fed.add(it) }
        val stats = r.cc.run(turn(r, input), { false }) { ev ->
            if (ev is Ev.Activity && ev.text.startsWith("명령 실행") && !gotTool) {
                gotTool = true
                dropped = System.currentTimeMillis()
                // 연결을 끊는다 (읽는 스레드가 아닌 곳에서)
                Thread { r.ssh.close() }.start()
            }
            a.send(ev)
        }
        a.finish()
        val id = r.cc.runId!!
        println("(a) 실행 $id 도구 보임 ${dropped - t0}ms 뒤 연결 끊음, 끝남 ${System.currentTimeMillis() - t0}ms")
        println("(a) 상태 줄: ${a.activity.filter { it.contains("끊") || it.contains("다시 붙") }}")
        println("(a) 거부된 줄: ${a.rejected}")
        println(Lang.serialize(a.board))
        assertTrue("도구를 쓰는 것이 보여야 한다: ${a.activity}", gotTool)
        assertTrue("스스로 다시 붙었다고 알려야 한다: ${a.activity}", a.activity.any { it.contains("다시 붙습니다") })
        assertTrue("카드가 있어야 한다", a.board.cards.isNotEmpty())
        assertTrue("result 의 사용량을 받아야 한다: ${stats.model}", stats.outputTokens > 0 && stats.model.startsWith("Claude Code · "))
        val serverOut = r.ssh.run("cat ~/.kite/runs/$id.out").removeSuffix("\n").lines()
        val exit = r.ssh.run("cat ~/.kite/runs/$id.exit").trim()
        println("(a) 서버 .exit=$exit 서버 .out ${serverOut.size}줄, 앱이 받은 줄 ${r.cc.linesReceived}")
        assertEquals("끝난 코드", "0", exit)
        assertEquals("앱이 받은 줄 수 = 서버 .out 줄 수", serverOut.size, r.cc.linesReceived)
        assertEquals("앱이 처리한 줄이 서버 .out 과 줄마다 같아야 한다 (빠지거나 겹치면 안 된다)", serverOut, fed)
        assertTrue("result 줄이 서버에 있어야 한다", serverOut.any { it.contains("\"type\":\"result\"") })

        // (b) 처음부터 다시 받기
        val b = Applier()
        val fed2 = mutableListOf<String>()
        r.cc.onDataLine = { fed2.add(it) }
        r.cc.resume(id, null, 0, { false }, b::send)
        b.finish()
        println("(b) 다시 받은 줄 ${r.cc.linesReceived}")
        assertEquals("(b) 다시 받은 줄 수", serverOut.size, r.cc.linesReceived)
        assertEquals("(b) 다시 받은 줄이 서버 .out 과 줄마다 같아야 한다", serverOut, fed2)
        assertEquals("(b) 처음부터 다시 받은 보드가 처음과 같아야 한다", Lang.serialize(a.board), Lang.serialize(b.board))
        println("(b) 같음: 카드 ${b.board.cards.size}장")

        // 치우면 그 번호는 서버에 없다 → (d) 분명한 오류
        r.cc.clean(id)
        val gone = runCatching { r.cc.resume(id, null, 0, { false }) {} }.exceptionOrNull()
        println("(d) 치운 번호: ${gone?.message}")
        assertTrue(gone?.message.orEmpty(), gone?.message.orEmpty().contains("기록이 없습니다"))
        val made = runCatching { r.cc.resume("20260101-000000-dead", null, 0, { false }) {} }.exceptionOrNull()
        println("(d) 없는 번호: ${made?.message}")
        assertTrue(made?.message.orEmpty(), made?.message.orEmpty().contains("기록이 없습니다"))
        // 이상한 번호는 서버에 닿기 전에 거부
        assertTrue(runCatching { r.cc.resume("a;b", null, 0, { false }) {} }.exceptionOrNull() is IllegalArgumentException)
        r.ssh.close()
    }

    /** (c) 도구가 도는 긴 턴을 멈춘다 → STOPPED, 서버에 그 실행의 프로세스(감싼 bash·claude·도구)가 남지 않는다. */
    @Test
    fun stopLeavesNothing() {
        val r = rig()
        r.cc.cleanAfterRun = false
        var stop = false
        var tool = 0L
        val t0 = System.currentTimeMillis()
        val input = "Bash 로 \"for i in \$(seq 1 40); do date; sleep 2; done # F045STOPMARK\" 를 한 번 실행하고 끝나면 카드 한 장에 '끝'이라고 써 줘."
        val err = runCatching {
            r.cc.run(turn(r, input), { stop }) { ev ->
                if (ev is Ev.Activity && ev.text.startsWith("명령 실행") && tool == 0L) {
                    tool = System.currentTimeMillis()
                    Thread {
                        Thread.sleep(4000)
                        stop = true
                    }.start()
                }
            }
        }.exceptionOrNull()
        val id = r.cc.runId!!
        val took = System.currentTimeMillis() - (tool + 4000)
        println("(c) 실행 $id 도구 보임 ${tool - t0}ms, 멈춤 눌린 뒤 ${took}ms 에 돌아옴: ${err?.message}")
        assertTrue("도구가 보여야 한다", tool > 0)
        assertEquals(STOPPED, err?.message)
        Thread.sleep(500)
        val exit = r.ssh.run("cat ~/.kite/runs/$id.exit").trim()
        println("(c) 서버 .exit=$exit")
        assertTrue("끝난 값: $exit", exit in listOf("143", "137", "stopped"))
        assertEquals("그 실행의 프로세스가 없어야 한다", "", leftOf(r, id))
        assertEquals("도구가 띄운 프로세스가 없어야 한다", "", r.ssh.run("pgrep -af '[F]045STOPMARK' || true").trim())
        println("(c) 남은 프로세스: 없음")
        r.cc.clean(id)
        r.ssh.close()
    }
}
