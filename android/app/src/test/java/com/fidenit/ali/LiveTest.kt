package com.fidenit.ali

import com.fidenit.ali.core.Author
import com.fidenit.ali.core.Board
import com.fidenit.ali.core.Chunk
import com.fidenit.ali.core.Lang
import com.fidenit.ali.core.LineStream
import com.fidenit.ali.core.partsTable
import com.fidenit.ali.engine.ClaudeCode
import com.fidenit.ali.engine.EngineConfig
import com.fidenit.ali.engine.Ev
import com.fidenit.ali.engine.NOTE
import com.fidenit.ali.engine.STOPPED
import com.fidenit.ali.engine.Server
import com.fidenit.ali.engine.Ssh
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 실제 SSH 서버에 붙어 Claude Code 로 턴을 돌려 본다. 앱이 쓰는 것과 같은 코드다.
 * 환경 변수가 있을 때만 돈다:
 *   ALI_SSH_HOST, ALI_SSH_PORT, ALI_SSH_USER
 *   ALI_SSH_KEY       개인 키 파일. 없으면 새로 만들고, 공개 키를 ALI_SSH_AUTHKEYS 파일에 덧붙인다.
 *   ALI_SSH_PASSWORD  암호로 붙을 때
 */
class LiveTest {
    private fun env(k: String) = System.getenv(k).orEmpty()

    private fun server(fingerprint: String = ""): Server {
        var key = env("ALI_SSH_KEY").takeIf { it.isNotEmpty() }?.let { File(it).readText() }.orEmpty()
        if (key.isEmpty() && env("ALI_SSH_AUTHKEYS").isNotEmpty()) {
            val (prv, pub) = Ssh.newKey("ali-test")
            File(env("ALI_SSH_AUTHKEYS")).appendText(pub + "\n")
            key = prv
        }
        return Server(env("ALI_SSH_HOST"), env("ALI_SSH_PORT").toIntOrNull() ?: 22, env("ALI_SSH_USER"), env("ALI_SSH_PASSWORD"), key, "", fingerprint)
    }

    @Test
    fun turnOverSsh() {
        assumeTrue(env("ALI_SSH_HOST").isNotEmpty())
        var seen = ""
        val srv = server()
        val ssh = Ssh(srv) { seen = it }
        val cc = ClaudeCode(ssh, EngineConfig("claude", "~", "default", "low", env("ALI_EXTRA")))
        ssh.connect()
        assertTrue("서버 지문을 기억해야 한다", seen.startsWith("SHA256:"))
        val (os, home) = cc.where()
        println("서버: $os, 홈 $home, Claude Code ${cc.version()}")

        // 기억한 지문과 다르면 붙지 않는다
        val wrong = Ssh(Server(srv.host, srv.port, srv.user, srv.password, srv.privateKey, "", "SHA256:wrong")) {}
        assertTrue(runCatching { wrong.connect() }.exceptionOrNull()?.message.orEmpty().contains("지문"))

        cc.prepare(File("../../src/prompt.md").readText().replace("{{PARTS}}", partsTable().trimEnd()) + NOTE)

        // 한 턴: 화면 수정 언어가 줄 단위로 와서 보드에 반영되어야 한다
        val board = Board()
        val stream = LineStream()
        val rejected = mutableListOf<String>()
        val tells = mutableListOf<String>()
        val activity = mutableListOf<String>()
        fun line(l: String) {
            if (l.trim().startsWith("tell ")) tells.add(l)
            try {
                Lang.parse(l)?.let { board.apply(it, Author.Ai) }
            } catch (e: Exception) {
                rejected.add("$l → ${e.message}")
            }
        }
        val text = "<board>\n</board>\n<voice>on</voice>\n<env>\n지금: 2026-11-15 (일) 10:00\n운영체제: $os (Claude Code 가 도는 서버. 사용자의 화면은 안드로이드 폰)\n사용자 홈 폴더: $home\n기억 보관함 폴더: $home/.ali/vault\n</env>\n<input>다음 주 제주 2박 3일 여행 준비물을 체크리스트로 만들어 줘. 다섯 개만.</input>"
        val stats = cc.run(text, { false }) { ev ->
            when (ev) {
                is Ev.Delta -> stream.feed(ev.text).forEach { if (it is Chunk.Line) line(it.text) }
                is Ev.Activity -> if (ev.text.isNotEmpty()) activity.add(ev.text)
            }
        }
        stream.finish()?.let { line(it) }
        println("${stats.model}  첫 반응 ${stats.firstMs}ms  전체 ${stats.totalMs}ms  입력 ${stats.inputTokens} 출력 ${stats.outputTokens}  ${stats.limit}")
        println("도구: $activity")
        println("거부된 줄: $rejected")
        println("소리로 읽을 말: $tells")
        println("say: ${board.say}")
        println(Lang.serialize(board))
        assertTrue("카드가 만들어져야 한다", board.cards.isNotEmpty())
        assertTrue("say 나 tell 이 있어야 한다", board.say.isNotEmpty() || tells.isNotEmpty())

        // 멈춤: 서버의 Claude Code 가 끝나야 한다
        var stop = false
        val started = System.currentTimeMillis()
        Thread {
            Thread.sleep(2500)
            stop = true
        }.start()
        val err = runCatching {
            cc.run("<board>\n</board>\n<input>1부터 400까지의 숫자를 한 줄에 하나씩, 각각 note 카드로 만들어 줘.</input>", { stop }) {}
        }.exceptionOrNull()
        println("멈춤까지 ${System.currentTimeMillis() - started}ms: ${err?.message}")
        assertEquals(STOPPED, err?.message)
        Thread.sleep(500)
        val left = ssh.run("pgrep -f 'append-system-prompt-file.*\\.ali/system.txt' || true").trim()
        assertEquals("남은 프로세스가 없어야 한다", "", left)
        ssh.close()
    }
}
