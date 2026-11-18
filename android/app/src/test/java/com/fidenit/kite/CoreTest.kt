package com.fidenit.kite

import com.fidenit.kite.core.Author
import com.fidenit.kite.core.Board
import com.fidenit.kite.core.Chunk
import com.fidenit.kite.core.Lang
import com.fidenit.kite.core.LineStream
import com.fidenit.kite.core.PARTS
import com.fidenit.kite.core.Rejected
import com.fidenit.kite.core.partsTable
import com.fidenit.kite.engine.ClaudeCode
import com.fidenit.kite.engine.EngineConfig
import com.fidenit.kite.engine.Ev
import com.fidenit.kite.engine.Server
import com.fidenit.kite.engine.Ssh
import com.fidenit.kite.engine.StreamParser
import com.fidenit.kite.voice.Words
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
                errs.add(e.code)
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
        assertEquals(listOf("bad-value"), run(b, "add c i1 maybe | 내용")) // 첫 칸은 done/todo
        assertEquals(listOf("bad-value"), run(b, "set s state great"))
        assertEquals(listOf("bad-value"), run(b, "add g a 이름 | 많이")) // 숫자가 아님
        assertEquals(emptyList<String>(), run(b, "add g a 이름 | 1,200"))
        assertEquals(listOf("exists"), run(b, "card c note t 또")) // 이미 있는 ID
        assertEquals(listOf("unknown-kind"), run(b, "card x nothing t 없음"))
        assertEquals(listOf("user-only"), run(b, "pin c")) // pin 은 사용자만
        assertEquals(listOf("unknown-verb"), run(b, "hello there"))
        assertNull(Lang.parse("tell 소리로만 읽는 말"))
    }

    @Test
    fun clipsLongTextAndNotesIt() {
        val b = Board()
        run(b, "topic t 일\ncard n note t 메모\nset n text " + "가".repeat(300))
        assertEquals(240, b.card("n")!!.field("text").length)
        assertTrue(b.card("n")!!.field("text").endsWith("…"))
        assertEquals(listOf("clipped n.text 240"), b.notes)
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

    /**
     * The protocol's conformance cases (protocol/conformance), the same files the reference implementation runs.
     * Each line has an author and the verdict expected; the board at the end is compared as written back.
     */
    @Test
    fun protocolConformance() {
        fun expand(s: String) = Regex("<(\\d+) (.)>").replace(s) { it.groupValues[2].repeat(it.groupValues[1].toInt()) }
        fun tidy(s: String) = s.lines().joinToString("\n") { it.trimEnd() }.trimEnd()
        val files = File("../../protocol/conformance").listFiles { f -> f.extension == "txt" }!!.sortedBy { it.name }
        assertTrue("the conformance cases are missing", files.size >= 7)
        for (file in files) {
            val text = file.readText().replace("\r\n", "\n")
            val script = text.substringBefore("\nboard:\n")
            val want = expand(text.substringAfter("\nboard:\n"))
            val b = Board()
            val lines = script.lines()
            var i = 0
            while (i < lines.size) {
                val line = lines[i++]
                val by = when {
                    line.startsWith("agent:") -> Author.Ai
                    line.startsWith("user:") -> Author.User
                    else -> continue
                }
                val op = expand(line.substringAfter(":").removePrefix(" "))
                val expected = lines[i++].trim().removePrefix("-> ")
                val got = try {
                    val parsed = Lang.parse(op)
                    if (parsed == null) {
                        "skip"
                    } else {
                        b.apply(parsed, by)
                        val notes = b.notes.toList()
                        b.notes.clear()
                        if (notes.isEmpty()) "ok" else "ok " + notes.joinToString("; ")
                    }
                } catch (e: Rejected) {
                    "err ${e.code}"
                }
                assertEquals("${file.name}:$i: $line", expected, got)
            }
            assertEquals("${file.name}: the board at the end", tidy(want), tidy(Lang.serialize(b)))
        }
    }

    /** The card type table must be the reference implementation's: the same guide and prompt are filled from it. */
    @Test
    fun partsMatchDesktop() {
        val rust = File("../../protocol/rust/src/core.rs").readText()
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
        p.feed("KITEPID 4242")
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
        // 서버(리눅스·맥)의 셸을 흉내 내는 시험이라 Windows 에서는 건너뛴다 (sh 가 없고, 있어도 경로와 인자 다루는 법이 다르다).
        org.junit.Assume.assumeFalse(System.getProperty("os.name").orEmpty().startsWith("Windows"))
        val home = File.createTempFile("kite", "home").let { it.delete(); it.mkdirs(); it }
        File(home, ".local/bin").mkdirs()
        File(home, "work dir").mkdirs()
        File(home, ".kite").mkdirs()
        File(home, ".kite/system.txt").writeText("x")
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
        assertTrue(text, lines.any { it.matches(Regex("KITEPID \\d+")) })
        assertTrue(text, lines.any { it.startsWith("ARGS -p --output-format stream-json --verbose --include-partial-messages --no-session-persistence --append-system-prompt-file ${home.absolutePath}/.kite/system.txt --effort low --tools Bash,Read,Edit,Write,Glob,Grep,WebSearch,WebFetch,ToolSearch --permission-mode acceptEdits --model sonnet") })
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
        assertTrue(Words.isWake("카이트, 다 봤어"))
        assertTrue(Words.isWake("Hey KITE"))
        assertFalse(Words.isWake("The quality is fine"))
        assertTrue(Words.similar("회의가 두 개 있고 첫 회의는 열 시예요", "회의가 두개 있고 첫 회의는 열시에요"))
    }
}
