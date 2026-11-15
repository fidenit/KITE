package com.fidenit.ali

import com.fidenit.ali.core.Author
import com.fidenit.ali.core.Board
import com.fidenit.ali.core.Chunk
import com.fidenit.ali.core.Lang
import com.fidenit.ali.core.LineStream
import com.fidenit.ali.core.PARTS
import com.fidenit.ali.core.Rejected
import com.fidenit.ali.core.partsTable
import com.fidenit.ali.engine.ClaudeCode
import com.fidenit.ali.engine.EngineConfig
import com.fidenit.ali.engine.Ev
import com.fidenit.ali.engine.Server
import com.fidenit.ali.engine.Ssh
import com.fidenit.ali.engine.StreamParser
import com.fidenit.ali.voice.Words
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CoreTest {
    private fun run(b: Board, src: String, by: Author = Author.Ai): List<String> {
        val errs = mutableListOf<String>()
        for (l in src.lines()) {
            try {
                Lang.parse(l)?.let { b.apply(it, by) }
            } catch (e: Rejected) {
                errs.add(e.message ?: "")
            }
        }
        return errs
    }

    @Test
    fun buildsAndSerializesBack() {
        val b = Board()
        val src = "topic t1 여행\ncard k1 table t1 4x3 항공편 비교\nset k1 cols 항공사 | 가격\nadd k1 r1 A | 100\nadd k1 r2 B | 120\ncard k2 status t1 준비\nset k2 state run\nsay 정리했어요"
        assertEquals(emptyList<String>(), run(b, src))
        assertEquals(2, b.cards.size)
        assertEquals("정리했어요", b.say)
        assertEquals(2 to 1, b.card("k2")!!.let { it.w to it.h }) // 크기를 생략하면 기본 크기
        assertEquals("준비", b.card("k2")!!.title)
        val again = Lang.load(Lang.serialize(b))
        assertEquals(Lang.serialize(b), Lang.serialize(again))
    }

    @Test
    fun rejectsWhatDesktopRejects() {
        val b = Board()
        run(b, "topic t 일\ncard c checklist t 할 일\ncard s status t 상태\ncard g chart t 수량")
        assertEquals(1, run(b, "add c i1 maybe | 내용").size) // 첫 칸은 done/todo
        assertEquals(1, run(b, "set s state great").size)
        assertEquals(1, run(b, "add g a 이름 | 많이").size) // 숫자가 아님
        assertEquals(0, run(b, "add g a 이름 | 1,200").size)
        assertEquals(1, run(b, "card c note t 또").size) // 이미 있는 ID
        assertEquals(1, run(b, "card x nothing t 없음").size)
        assertEquals(1, run(b, "pin c").size) // pin 은 사용자만
        assertEquals(1, run(b, "hello there").size)
        assertNull(Lang.parse("tell 소리로만 읽는 말"))
    }

    @Test
    fun clipsLongTextAndNotesIt() {
        val b = Board()
        run(b, "topic t 일\ncard n note t 메모\nset n text " + "가".repeat(300))
        assertEquals(240, b.card("n")!!.field("text").length)
        assertTrue(b.card("n")!!.field("text").endsWith("…"))
        assertEquals(1, b.notes.size)
    }

    @Test
    fun archiveSurvivesSaveAndLoad() {
        val b = Board()
        run(b, "group g 회사\ntopic a 가\nput a g\ncard k1 note a 메모\nset k1 text 내용\ntopic z 나\ncard k2 note z 다른 것\nlet people 12")
        run(b, "archive a", Author.User)
        assertEquals(1, b.shelf.size)
        val again = Lang.load(Lang.save(b))
        assertEquals(1, again.shelf.size)
        assertEquals("내용", again.shelf[0].cards[0].field("text"))
        assertEquals("g", again.shelf[0].topic.group)
        assertEquals(listOf("z"), again.topics.map { it.id })
        assertEquals("12명", again.show("{people}명"))
    }

    @Test
    fun userActionsAreAccepted() {
        val b = Board()
        run(b, "topic t 일\ncard d decision t 고르기\nadd d o1 강릉 | 멀다\ncard f input t 묻기\nadd f a 출발일 | date\ncard c checklist t 할 일\nadd c i1 todo | 예약")
        assertEquals(emptyList<String>(), run(b, "set d chosen o1\nupd f a 출발일 | date | 12/3\nset f sent yes\nupd c i1 done | 예약", Author.User))
        assertEquals("o1", b.card("d")!!.field("chosen"))
        assertEquals("12/3", b.card("f")!!.items[0].cells[2])
    }

    @Test
    fun lineStreamSplitsAcrossChunks() {
        val s = LineStream()
        val got = mutableListOf<String>()
        for (d in listOf("topic t 가\nset k te", "xt 안녕", "하세요\nsay 끝")) {
            for (c in s.feed(d)) if (c is Chunk.Line) got.add(c.text)
        }
        assertEquals(listOf("topic t 가", "set k text 안녕하세요"), got)
        assertEquals("say 끝", s.finish())
        assertEquals(Triple("k", "text", "안녕"), Lang.partialText("set k text 안녕"))
    }

    /** 부품 표는 데스크톱(src/core.rs)과 같아야 한다: 같은 지시문(prompt.md)에 끼워 넣기 때문이다. */
    @Test
    fun partsMatchDesktop() {
        val rust = File("../../src/core.rs").readText()
        assertEquals(Regex("Part \\{").findAll(rust.substringBefore("pub fn parts_table")).count() - 1, PARTS.size) // 하나는 struct 선언
        for (p in PARTS) {
            for (s in listOf(p.name, p.purpose, p.items) + p.fields.flatMap { listOf(it.name, it.doc) }) {
                assertTrue("core.rs 에 없음: $s", s.isEmpty() || rust.contains("\"$s\""))
            }
            assertTrue("크기가 다름: ${p.name}", rust.contains("name: \"${p.name}\", size: (${p.w}, ${p.h})") || Regex("name: \"${p.name}\",\\s+size: \\(${p.w}, ${p.h}\\)").containsMatchIn(rust))
            for (f in p.fields) assertTrue("한도가 다름: ${p.name}.${f.name}", rust.contains("(\"${f.name}\", ${f.limit}, \"${f.doc}\")"))
        }
        assertTrue(partsTable().lines().size == PARTS.size + 3)
    }

    @Test
    fun streamJsonBecomesScreenEvents() {
        val out = mutableListOf<Ev>()
        val p = StreamParser({ out.add(it) })
        p.feed("ALIPID 4242")
        p.feed("""{"type":"system","subtype":"init","model":"claude-x"}""")
        p.feed("""{"type":"stream_event","event":{"type":"content_block_start","content_block":{"type":"text"}}}""")
        p.feed("""{"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"say 확인해 볼게요"}}}""")
        p.feed("""{"type":"stream_event","event":{"type":"content_block_start","content_block":{"type":"tool_use","name":"Bash"}}}""")
        p.feed("""{"type":"assistant","message":{"content":[{"type":"tool_use","name":"Bash","input":{"command":"df -h"}}]}}""")
        p.feed("""{"type":"user","message":{}}""")
        p.feed("""{"type":"stream_event","event":{"type":"content_block_start","content_block":{"type":"text"}}}""")
        p.feed("""{"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"topic s 서버\n"}}}""")
        p.feed("not json at all")
        p.feed("""{"type":"result","is_error":false,"result":"ok","total_cost_usd":0.01,"usage":{"input_tokens":10,"cache_read_input_tokens":5,"output_tokens":7}}""")
        p.finish()
        assertEquals("4242", p.pid)
        assertEquals("Claude Code · claude-x", p.stats.model)
        assertTrue(p.gotResult)
        assertNull(p.failure)
        assertEquals(15L, p.stats.inputTokens)
        // 도구를 쓰고 난 뒤 이어지는 글은 새 줄에서 시작한다
        assertEquals("say 확인해 볼게요\ntopic s 서버\n", out.filterIsInstance<Ev.Delta>().joinToString("") { it.text })
        assertEquals(listOf("명령 실행: ", "명령 실행: df -h", ""), out.filterIsInstance<Ev.Activity>().map { it.text })
    }

    /** 서버에 보내는 명령 줄을 이 컴퓨터의 셸로 돌려 본다: 가짜 claude 가 받은 인자와 입력을 그대로 돌려준다. */
    @Test
    fun remoteCommandRunsUnderBash() {
        val home = File.createTempFile("ali", "home").let { it.delete(); it.mkdirs(); it }
        File(home, ".local/bin").mkdirs()
        File(home, "work dir").mkdirs()
        File(home, ".ali").mkdirs()
        File(home, ".ali/system.txt").writeText("x")
        val fake = File(home, ".local/bin/claude")
        fake.writeText("#!/bin/sh\necho \"ARGS \$*\"\necho \"PWD \$(pwd)\"\necho \"STDIN \$(cat)\"\n")
        fake.setExecutable(true)
        val ssh = Ssh(Server("h", 22, "u", "", "", "", "")) {}
        val cc = ClaudeCode(ssh, EngineConfig("claude", "~/work dir", "acceptEdits", "low", "--model sonnet"))
        val pb = ProcessBuilder("sh", "-c", cc.command())
        pb.environment()["HOME"] = home.absolutePath
        pb.redirectErrorStream(true)
        val proc = pb.start()
        proc.outputStream.use { it.write("<input>it's \"quoted\" \$HOME</input>".toByteArray()) }
        val lines = proc.inputStream.bufferedReader().readLines()
        proc.waitFor()
        val text = lines.joinToString("\n")
        assertTrue(text, lines.any { it.matches(Regex("ALIPID \\d+")) })
        assertTrue(text, lines.any { it.startsWith("ARGS -p --output-format stream-json --verbose --include-partial-messages --no-session-persistence --append-system-prompt-file ${home.absolutePath}/.ali/system.txt --effort low --tools Bash,Read,Edit,Write,Glob,Grep,WebSearch,WebFetch,ToolSearch --permission-mode acceptEdits --model sonnet") })
        assertTrue(text, lines.any { it.startsWith("PWD ") && it.endsWith("/work dir") })
        assertTrue(text, lines.contains("STDIN <input>it's \"quoted\" \$HOME</input>"))
        home.deleteRecursively()
    }

    @Test
    fun shortWords() {
        for (w in listOf("그만.", "그만해!", "멈춰", "스톱", "조용히 해", "Stop.", "말 그만하라고")) assertTrue(w, Words.isStop(w))
        for (w in listOf("그만 찾고 다른 걸 보여줘 공모전 말고", "이 영상 만들 수 있어?", "")) assertFalse(w, Words.isStop(w))
        for (w in listOf("잠깐만.", "잠깐 기다려 봐", "내가 확인해 볼게", "이거 읽어 볼게요", "잠시만요")) assertTrue(w, Words.isWait(w))
        for (w in listOf("잠깐, 이거 찾아봐", "잠깐만 그거 보여줘", "공모전 조사해 줘")) assertFalse(w, Words.isWait(w))
        assertTrue(Words.isWake("알리, 다 봤어"))
        assertTrue(Words.isWake("Hey ALI"))
        assertFalse(Words.isWake("The quality is fine"))
        assertTrue(Words.similar("회의가 두 개 있고 첫 회의는 열 시예요", "회의가 두개 있고 첫 회의는 열시에요"))
    }
}
