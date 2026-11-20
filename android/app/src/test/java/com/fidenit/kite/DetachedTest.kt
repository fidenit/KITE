package com.fidenit.kite

import com.fidenit.kite.engine.Detached
import com.fidenit.kite.engine.DetachedCommands
import com.fidenit.kite.engine.End
import com.fidenit.kite.engine.EngineConfig
import com.fidenit.kite.engine.FollowLine
import com.fidenit.kite.engine.Step
import com.fidenit.kite.engine.Waker
import com.fidenit.kite.engine.readLines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class DetachedTest {
    private companion object {
        /** 이 JVM 에서 한 번 고르는 다섯 자리: 가짜 실행의 sleep 값에 넣어, 같은 컴퓨터에서 동시에 도는 다른 시험 실행과 서로 보이지 않게 한다. */
        val RUN: String = "%05d".format(Random.nextInt(100000))

        /** 이 시험 실행만 쓰는 sleep 명령 (40초쯤 뒤 스스로 끝난다). */
        fun mark(n: Int): String = "sleep 39.$RUN$n"
    }

    private val cfg = EngineConfig("claude", "~/work dir", "acceptEdits", "low", "--model sonnet")

    @Test
    fun idsAreMadeAndChecked() {
        val id = Detached.newId(0L, Random(1))
        assertTrue(id, Regex("\\d{8}-\\d{6}-[0-9a-f]{4}").matches(id))
        assertTrue(Detached.valid(id))
        assertTrue(Detached.valid("A-b-9"))
        for (bad in listOf("", "a b", "a;rm -rf ~", "../x", "a\$(id)", "a`id`", "a'b", "a\"b", "a\nb", "한글", "a/b", "a.b", "x".repeat(65))) {
            assertFalse("'$bad'", Detached.valid(bad))
        }
        val c = DetachedCommands(cfg)
        for (bad in listOf("a b", "x;y", "")) {
            for (f in listOf<() -> String>({ c.upload(bad) }, { c.start(bad) }, { c.follow(bad, 0) }, { c.stop(bad) }, { c.clean(bad) }, { c.errTail(bad) })) {
                assertTrue(runCatching { f() }.exceptionOrNull() is IllegalArgumentException)
            }
        }
        // 같은 때에 만든 두 번호도 다르다
        assertTrue(Detached.newId() != Detached.newId())
    }

    @Test
    fun linesAreClassifiedAndCounted() {
        assertEquals(FollowLine.Data("""{"type":"result"}"""), Detached.classify(""">{"type":"result"}"""))
        assertEquals(FollowLine.Data("not json"), Detached.classify(">not json")) // 서버가 센 줄은 JSON 이 아니어도 센다
        assertEquals(FollowLine.Data(""), Detached.classify(">"))
        assertEquals(FollowLine.Data("APERITEXIT 0"), Detached.classify(">APERITEXIT 0")) // .out 안의 글이 제어 줄로 오해되지 않는다
        assertEquals(FollowLine.Other, Detached.classify("""{"type":"result"}""")) // 앞에 > 가 없으면 인사말 같은 것: 무시
        assertEquals(FollowLine.Exit("0"), Detached.classify("APERITEXIT 0"))
        assertEquals(FollowLine.Exit("stopped"), Detached.classify("APERITEXIT stopped"))
        assertEquals(FollowLine.Exit("none"), Detached.classify("APERITEXIT none\r"))
        assertEquals(FollowLine.Beat, Detached.classify("APERITBEAT"))
        assertEquals(FollowLine.Run("a-1"), Detached.classify("APERITRUN a-1"))
        assertEquals(FollowLine.Fail(""), Detached.classify("APERITFAIL"))
        assertEquals(FollowLine.Fail("cd"), Detached.classify("APERITFAIL cd"))
        assertEquals(FollowLine.Other, Detached.classify("Welcome to the server"))
        assertEquals(FollowLine.Other, Detached.classify(""))
        assertEquals(FollowLine.Other, Detached.classify("APERITEXIT"))
        val lines = listOf("APERITRUN x", ">{\"a\":1}", "motd", "APERITBEAT", ">{\"a\":2}", "", ">oops", "APERITEXIT 0")
        assertEquals(3, Detached.countData(lines))
    }

    /** 이어 읽기 채널에서 줄 한가운데서 끊기면: 반쪽 줄은 세지도 처리하지도 않고, 다시 붙으면 그 줄이 통째로 온다. */
    @Test
    fun cutInTheMiddleOfALineIsNeitherCountedNorActedOn() {
        val l1 = ">" + """{"type":"stream_event","n":1}"""
        val l2 = ">" + """{"type":"result","result":"ok"}"""
        fun read(text: String, n0: Int, complete: Boolean): Triple<Int, String?, List<String>> {
            var n = n0
            var exit: String? = null
            val fed = mutableListOf<String>()
            readLines(java.io.StringReader(text), complete) { raw ->
                when (val c = Detached.classify(raw)) {
                    is FollowLine.Data -> { n++; fed.add(c.text) }
                    is FollowLine.Exit -> exit = c.code
                    else -> {}
                }
            }
            return Triple(n, exit, fed)
        }
        // 첫 연결: 줄 하나와 둘째 줄의 반쪽
        val (n1, e1, f1) = read("motd\n" + l1 + "\n" + l2.take(15), 0, true)
        assertEquals(1, n1)
        assertEquals(null, e1)
        assertEquals(listOf(l1.substring(1)), f1)
        // 다시 붙음: 서버는 n1 줄 다음부터 다시 준다 -> 둘째 줄이 통째로 온다
        val (n2, e2, f2) = read("Last login: x\n" + l2 + "\nAPERITEXIT 0\n", n1, true)
        assertEquals(2, n2)
        assertEquals("0", e2)
        assertEquals(listOf(l2.substring(1)), f2)
        // 끝나는 줄이 `APERITEXIT 14` 에서 잘렸다: 143 으로 읽지도 14 로 읽지도 않는다
        assertEquals(null, read("APERITEXIT 14", 0, true).second)
        assertEquals("143", read("APERITEXIT 143\n", 0, true).second)
        // JSON 이 아닌 .out 줄도 한 줄로 센다 (서버도 센다): 다시 붙어도 한 줄이 두 번 오지 않는다
        assertEquals(3, read(">{\"a\":1}\n>not json\n>{\"b\":2}\n", 0, true).first)
        // 짧은 명령(Ssh.run)은 마지막 줄에 줄바꿈이 없어도 받는다
        val got = mutableListOf<String>()
        readLines(java.io.StringReader("a\r\nb"), false) { got.add(it) }
        assertEquals(listOf("a", "b"), got)
    }

    /** 올리기·시작 중에 연결이 끊기면 실패가 아니라 다시 하기: 서버가 분명히 거절했을 때만 실패. */
    @Test
    fun uploadAndStartDecisions() {
        assertEquals(Step.Ok, Detached.judgeUpload(0, ""))
        assertEquals(Step.Retry, Detached.judgeUpload(-1, ""))
        assertEquals(Step.Fail("remote", "No space left"), Detached.judgeUpload(1, " No space left\n"))
        val id = "a-1"
        assertEquals(Step.Ok, Detached.judgeStart(0, listOf("APERITRUN a-1"), "", id))
        assertEquals(Step.Ok, Detached.judgeStart(-1, listOf("APERITRUN a-1"), "", id)) // 줄은 왔는데 코드만 못 받음: 시작은 됐다
        assertEquals(Step.Retry, Detached.judgeStart(-1, emptyList(), "", id))
        assertEquals(Step.Retry, Detached.judgeStart(0, emptyList(), "", id))
        assertEquals(Step.Retry, Detached.judgeStart(0, listOf("APERITRUN other"), "", id)) // 남의 번호
        assertEquals(Step.Fail("cd", ""), Detached.judgeStart(0, listOf("APERITFAIL cd"), "", id))
        assertEquals(Step.Fail("noin", ""), Detached.judgeStart(-1, listOf("Last login", "APERITFAIL noin"), "", id))
        assertEquals(Step.Fail("start", ""), Detached.judgeStart(0, listOf("APERITFAIL"), "", id))
        assertEquals(Step.Fail("remote", "bash: x: not found"), Detached.judgeStart(127, emptyList(), "bash: x: not found", id))
    }

    /** 이어 읽는 명령이 고장 난 것은 끝난 코드로만 안다. 글자가 났다고 고장이 아니고, 모르는 코드는 끊김이다. */
    @Test
    fun brokenFollowIsDecidedByExitStatus() {
        for (c in listOf(2, 126, 127)) assertTrue(c.toString(), Detached.followBroken(c))
        for (c in listOf(-1, 0, 1, 255, 143)) assertFalse(c.toString(), Detached.followBroken(c))
    }

    /** 서버가 끝났다고 답한 것(끝난 줄이 있다)은 다시 붙지 않는다: 끝난 줄 없이 읽기가 끝났을 때만 끊김이다. */
    @Test
    fun onlyAFollowWithoutAnEndLineIsADroppedConnection() {
        assertEquals(End.Dropped, Detached.judgeEnd(null, true))
        assertEquals(End.Dropped, Detached.judgeEnd(null, false))
        assertEquals(End.Gone, Detached.judgeEnd("gone", true))
        assertEquals(End.Gone, Detached.judgeEnd("gone", false))
        assertEquals(End.Restart, Detached.judgeEnd("none", true))
        assertEquals(End.NoRecord, Detached.judgeEnd("none", false))
        for (c in listOf("0", "1", "143", "137", "stopped", "lost")) {
            assertEquals(End.Over(c), Detached.judgeEnd(c, true))
            assertEquals(End.Over(c), Detached.judgeEnd(c, false))
        }
    }

    @Test
    fun reconnectDelays() {
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 15000L, 30000L, 30000L, 30000L), (0..7).map { Detached.delayMs(it) })
        assertEquals(1000L, Detached.delayMs(-3))
    }

    @Test
    fun wakerWakesAndStops() {
        val w = Waker()
        var t = System.currentTimeMillis()
        Thread { Thread.sleep(150); w.wake() }.start()
        assertTrue(w.sleep(10_000) { false })
        assertTrue("깨우기로 바로 깬다", System.currentTimeMillis() - t < 3000)
        t = System.currentTimeMillis()
        var stop = false
        Thread { Thread.sleep(150); stop = true }.start()
        assertFalse(w.sleep(10_000) { stop })
        assertTrue("멈춤으로 바로 깬다", System.currentTimeMillis() - t < 3000)
        w.wake()
        w.clear()
        t = System.currentTimeMillis()
        assertFalse(w.sleep(300) { false })
        assertTrue(System.currentTimeMillis() - t >= 250)
    }

    // ---------------------------------------------------------------- 이 컴퓨터의 셸로 돌려 본다

    private class Out(val lines: List<String>, val ms: Long)

    private fun sh(home: File, cmd: String, stdin: String? = null, timeoutSec: Long = 60): Out {
        val t0 = System.currentTimeMillis()
        val pb = ProcessBuilder("sh", "-c", cmd)
        pb.environment()["HOME"] = home.absolutePath
        pb.redirectErrorStream(true)
        val proc = pb.start()
        // 출력은 다른 스레드가 읽는다: 여기서 읽으면 명령이 끝나지 않을 때 시간 제한에 닿지 못한다
        val lines = java.util.Collections.synchronizedList(mutableListOf<String>())
        val reader = kotlin.concurrent.thread(isDaemon = true) {
            try {
                proc.inputStream.bufferedReader().forEachLine { lines.add(it) }
            } catch (_: Exception) {
            }
        }
        try {
            proc.outputStream.use { if (stdin != null) it.write(stdin.toByteArray()) }
        } catch (_: java.io.IOException) {
        }
        val done = proc.waitFor(timeoutSec, TimeUnit.SECONDS)
        if (!done) {
            // 시간이 넘었다: 이 시험이 띄운 그 프로세스를 그 핸들로 끝내고, 읽는 쪽도 풀어 준다
            proc.destroyForcibly()
            runCatching { proc.inputStream.close() }
        }
        reader.join(if (done) 10_000 else 2000)
        assertTrue("시간 초과(${timeoutSec}초): $cmd", done)
        return Out(lines.toList(), System.currentTimeMillis() - t0)
    }

    private fun newHome(fake: String, dir: String = "work dir"): File {
        val home = File.createTempFile("kite", "home").let { it.delete(); it.mkdirs(); it }
        File(home, ".local/bin").mkdirs()
        File(home, dir).mkdirs()
        File(home, ".kite").mkdirs()
        File(home, ".kite/system.txt").writeText("x")
        val f = File(home, ".local/bin/claude")
        f.writeText(fake)
        f.setExecutable(true)
        return home
    }

    private val okFake = "#!/bin/sh\nmkdir -p \"\$HOME/cap\"\necho \"\$*\" > \"\$HOME/cap/args\"\npwd > \"\$HOME/cap/pwd\"\ncat > \"\$HOME/cap/stdin\"\n" +
        "echo '{\"type\":\"system\",\"subtype\":\"init\"}'\nfor i in 1 2 3 4; do echo \"{\\\"type\\\":\\\"stream_event\\\",\\\"n\\\":\$i}\"; sleep 0.4; done\necho '{\"type\":\"result\",\"result\":\"ok\"}'\n"

    /** 이 시험의 가짜 실행에서 아직 살아 있는 것: 명령 글에 시험 폴더나 이 시험 실행만 쓰는 sleep 값([mark])이 있는 프로세스 (읽기만 한다). */
    private fun alive(home: File, mark: String): List<String> = sh(File("/"), "ps -eo args").lines.filter { (it.contains(home.absolutePath) || it.contains(mark)) && !it.contains("ps -eo") }

    private fun waitGone(home: File, mark: String, ms: Long): List<String> {
        val end = System.currentTimeMillis() + ms
        var left = alive(home, mark)
        while (left.isNotEmpty() && System.currentTimeMillis() < end) {
            Thread.sleep(100)
            left = alive(home, mark)
        }
        return left
    }

    /** `.pid` 에 적힌 감싼 bash 가 아직 있는가 (읽기만 한다). */
    private fun runnerAlive(pid: String): Boolean = pid.isNotEmpty() && pid.all { it.isDigit() } && sh(File("/"), "ps -p $pid -o pid= 2>/dev/null; true").lines.any { it.trim() == pid }

    /**
     * 시험 뒤 정리. 신호를 보내지 않는다: 남은 가짜 실행에는 멈춤 요청 파일만 남기고 (감싼 bash 가 자기 자식을 끝낸다) 잠깐 기다린다.
     * 그래도 남은 것은 가짜 claude 가 길어야 40초 뒤 스스로 끝난다.
     */
    private fun endRuns(home: File, mark: String, vararg ids: String) {
        for (id in ids) runCatching { File(home, ".kite/runs/$id.stop").createNewFile() }
        waitGone(home, mark, 4000)
        home.deleteRecursively()
    }

    /** 끝난 값이 적히기를 `ms` 까지 기다려 읽는다 (없으면 빈 글). */
    private fun waitExit(home: File, id: String, ms: Long = 3000): String {
        val end = System.currentTimeMillis() + ms
        while (exitOf(home, id).isEmpty() && System.currentTimeMillis() < end) Thread.sleep(100)
        return exitOf(home, id)
    }

    private fun exitOf(home: File, id: String): String = File(home, ".kite/runs/$id.exit").let { if (it.exists()) it.readText().trim() else "" }

    @Test
    fun uploadStartFollowRunUnderShell() {
        // 서버(리눅스·맥)의 셸을 흉내 내는 시험이라 Windows 에서는 건너뛴다 (sh 가 없고, 있어도 경로와 인자 다루는 법이 다르다).
        assumeFalse(System.getProperty("os.name").orEmpty().startsWith("Windows"))
        val home = newHome(okFake)
        val c = DetachedCommands(cfg)
        val id = "20261120-013000-a1b2"
        val text = "<input>it's \"quoted\" \$HOME</input>"
        try {
            assertEquals(emptyList<String>(), sh(home, c.upload(id), text).lines)
            assertEquals(text, File(home, ".kite/runs/$id.in").readText())

            // 시작은 바로 돌아온다 (가짜 claude 는 1.6초 넘게 돈다)
            val st = sh(home, c.start(id))
            assertEquals(listOf("APERITRUN $id"), st.lines)
            assertTrue("시작이 ${st.ms}ms 걸림", st.ms < 1500)
            assertTrue(File(home, ".kite/runs/$id.pid").readText().trim().all { it.isDigit() })
            // 두 번 시작하지 않는다
            assertEquals(listOf("APERITRUN $id"), sh(home, c.start(id)).lines)

            // 처음부터 따라 읽기: 줄 6개와 끝
            val all = sh(home, c.follow(id, 0)).lines
            assertEquals(all.toString(), "APERITEXIT 0", all.last())
            val data = all.filter { Detached.classify(it) is FollowLine.Data }.map { (Detached.classify(it) as FollowLine.Data).text }
            assertEquals(all.toString(), 6, data.size)
            assertTrue(all.toString(), all.filter { it.startsWith(">") }.size == 6)
            assertEquals(File(home, ".kite/runs/$id.out").readLines(), data)
            assertTrue(data.last().contains("\"result\""))
            // k 줄 뒤부터
            val rest = sh(home, c.follow(id, 4)).lines
            assertEquals(data.drop(4).map { ">$it" } + "APERITEXIT 0", rest)
            assertEquals(listOf("APERITEXIT 0"), sh(home, c.follow(id, 6)).lines)

            // 옛 command() 시험에서 옮겨 온 것: 인자·작업 폴더·표준 입력
            val args = File(home, "cap/args").readText().trim()
            assertEquals("-p --output-format stream-json --verbose --include-partial-messages --no-session-persistence --append-system-prompt-file ${home.absolutePath}/.kite/system.txt --effort low --tools Bash,Read,Edit,Write,Glob,Grep,WebSearch,WebFetch,ToolSearch --permission-mode acceptEdits --model sonnet", args)
            assertTrue(File(home, "cap/pwd").readText().trim().endsWith("/work dir"))
            assertEquals(text, File(home, "cap/stdin").readText())

            // 모르는 번호
            assertEquals(listOf("APERITEXIT none"), sh(home, c.follow("20261120-999999-zzzz", 0)).lines)

            // 치우기
            sh(home, c.clean(id))
            // 끝난 코드만 남는다: 이미 끝나 치워진 실행에 다시 붙으면 `gone` (`none` 이 아니다 — 같은 말이 다시 돌지 않게)
            assertEquals(listOf("$id.exit"), File(home, ".kite/runs").list()!!.toList())
            assertEquals(listOf("APERITEXIT gone"), sh(home, c.follow(id, 0)).lines)
            assertEquals(listOf("APERITEXIT none"), sh(home, c.follow("20261120-999999-zzzz", 0)).lines)
            assertEquals(emptyList<String>(), sh(home, c.stop(id)).lines) // 치운 실행에 멈춤은 아무 일도 없다: 파일도 줄도 남기지 않는다
            assertEquals("0", File(home, ".kite/runs/$id.exit").readText().trim())
            assertEquals(listOf("$id.exit"), File(home, ".kite/runs").list()!!.toList())
            sh(home, c.stop("20261120-999999-zzzz")) // 모르는 번호도 마찬가지
            assertEquals(listOf("$id.exit"), File(home, ".kite/runs").list()!!.toList())
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun startFailsClearly() {
        assumeFalse(System.getProperty("os.name").orEmpty().startsWith("Windows"))
        val home = newHome(okFake)
        try {
            // 올리지 않고 시작
            assertEquals(listOf("APERITFAIL noin"), sh(home, DetachedCommands(cfg).start("x1")).lines)
            // 없는 작업 폴더
            val bad = DetachedCommands(EngineConfig("claude", "~/nope", "default", "", ""))
            sh(home, bad.upload("x2"), "hi")
            assertEquals(listOf("APERITFAIL cd"), sh(home, bad.start("x2")).lines)
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun folderNameWithQuoteAndSpace() {
        assumeFalse(System.getProperty("os.name").orEmpty().startsWith("Windows"))
        val home = newHome(okFake, "it's a \"dir\"")
        val c = DetachedCommands(EngineConfig("claude", "~/it's a \"dir\"", "default", "", ""))
        try {
            sh(home, c.upload("q1"), "hi")
            assertEquals(listOf("APERITRUN q1"), sh(home, c.start("q1")).lines)
            val all = sh(home, c.follow("q1", 0)).lines
            assertEquals("APERITEXIT 0", all.last())
            assertTrue(File(home, "cap/pwd").readText().trim().endsWith("/it's a \"dir\""))
        } finally {
            home.deleteRecursively()
        }
    }

    /**
     * 서버에 보내는 글을 글자로만 검사한다 (아무것도 실행하지 않는다): 올리기·따라 읽기·멈춤·치우기·오류 읽기에는 신호를 보내는 명령이 아예 없고,
     * 감싼 글의 신호는 한 곳(`k1`)에서만 나가며 그 번호는 `$!` 이거나 `pgrep` 이 준 것이고 쓰기 전에 검사한다.
     */
    @Test
    fun commandTextsSendNoSignalToANumberFromAFile() {
        val c = DetachedCommands(cfg)
        val id = "20261120-013000-a1b2"
        val quiet = mapOf("upload" to c.upload(id), "follow" to c.follow(id, 0), "stop" to c.stop(id), "clean" to c.clean(id), "errTail" to c.errTail(id))
        for ((name, text) in quiet) {
            println("==== $name\n$text")
            assertFalse("$name 에 kill 이 있다", text.lowercase().contains("kill"))
            assertFalse("$name 에 pgrep 이 있다", text.contains("pgrep"))
        }
        val r = c.runner(id)
        println("==== runner\n$r")
        println("==== start\n${c.start(id)}")
        fun count(text: String, part: String) = text.windowed(part.length).count { it == part }
        // 신호는 한 곳(k1)에서만 나간다: 번호 검사(ok) 바로 뒤, 신호 이름과 번호는 함수 인자.
        // 또 하나의 kill 은 살아 있는지만 묻는 `kill -0` (신호가 가지 않는다)이고, 그 번호는 `$!` 을 담은 변수뿐이며 역시 ok 뒤다.
        assertEquals(2, count(r, "kill"))
        assertEquals(1, count(r, "kill -0"))
        assertTrue(r.contains("ok \"\$c\" && kill -0 \"\$c\" 2>/dev/null || return 1\n"))
        // 대문자 KILL 은 신호 이름으로만, 정해진 두 곳에만 있다
        assertEquals(2, count(r.replace("kt \"\$c\" KILL", "").replace("sw KILL;", "").lowercase(), "kill"))
        assertTrue(r.contains("k1() { ok \"\$1\" && kill -\"\$2\" \"\$1\" 2>/dev/null; }"))
        // 번호 검사: 숫자만, 1보다 크고, 자기 번호가 아니다
        assertTrue(r.contains("ok() { case \"\$1\" in ''|*[!0-9]*) return 1;; esac; [ \"\$1\" -gt 1 ] && [ \"\$1\" != \"\$\$\" ]; }"))
        // 묶음이나 음수로 보내는 꼴, 파일에서 번호를 읽는 꼴이 없다
        assertFalse(r.contains("kill --"))
        assertFalse(r.contains("-- -"))
        // 읽는 것은 한 곳뿐: 자기 자식의 /proc 상태 줄 (부모·좀비 검사에만 쓰고, 신호 번호로 쓰지 않는다)
        val procRead = "{ read -r l < \"/proc/\$c/stat\"; } 2>/dev/null; set -- \${l##*) }; set -- \"\$2\" \"\$1\"; fi\n"
        assertEquals(1, count(r, procRead))
        assertFalse(Regex("\\b(cat|read|killpg)\\b").containsMatchIn(r.replace(procRead, "")))
        assertFalse(Regex("<\\s*\"\\\$HOME/\\.kite/runs/$id\\.(pid|stop|exit|out|err)\"").containsMatchIn(r))
        // k1 을 부르는 곳: 나무 훑기(kt, 뿌리를 먼저 검사)와 세션 훑기(sw)뿐
        assertEquals(3, count(r, "k1 ") + count(r, "k1("))
        assertTrue(r.contains("kt() { ok \"\$1\" || return 1; for k in \$(exec pgrep -P \"\$1\" 2>/dev/null); do kt \"\$k\" \"\$2\"; done; k1 \"\$1\" \"\$2\"; }"))
        assertTrue(r.contains("sw() { o=0; for k in \$(exec pgrep -s \"\$\$\" 2>/dev/null); do k1 \"\$k\" \"\$1\" && o=1; done; return 0; }"))
        assertEquals(2, count(r, "pgrep"))
        // kt 의 뿌리는 `$!` 을 담은 변수뿐이고, 그 변수는 한 번만 정해진다
        val roots = Regex("kt \"\\$([a-z0-9]+)\" (\\S+)").findAll(r).map { it.groupValues[1] to it.groupValues[2] }.toList()
        assertEquals(listOf("k" to "\"\$2\";", "c" to "TERM;", "c" to "KILL;"), roots)
        assertEquals(1, Regex("(^|[\\s;])c=").findAll(r).count())
        assertTrue(r.contains("\nc=\$!\n"))
        assertEquals(listOf("TERM;", "KILL;"), Regex("\\bsw (\\S+)").findAll(r).map { it.groupValues[1] }.toList())
        // 신호 함수들이 불리는 곳을 빠짐없이 적는다: 따옴표 없는 번호, 숫자로 적은 번호, 다른 변수는 여기서 걸린다
        fun uses(name: String) = Regex("\\b$name\\b[^;\\n]*").findAll(r).map { it.value.trim() }.toList()
        assertEquals(listOf("kill -\"\$2\" \"\$1\" 2>/dev/null", "kill -0 \"\$c\" 2>/dev/null || return 1"), uses("kill"))
        assertEquals(listOf("k1() { ok \"\$1\" && kill -\"\$2\" \"\$1\" 2>/dev/null", "k1 \"\$1\" \"\$2\"", "k1 \"\$k\" \"\$1\" && o=1"), uses("k1"))
        assertEquals(listOf("kt() { ok \"\$1\" || return 1", "kt \"\$k\" \"\$2\"", "kt \"\$c\" TERM", "kt \"\$c\" KILL"), uses("kt"))
        assertEquals(listOf("sw() { o=0", "sw TERM", "sw KILL"), uses("sw"))
        // 번호가 나오는 곳은 셋뿐이다: `$!`, 그리고 pgrep 을 바로 도는 for 두 개. `k` 는 그 for 에서만 정해진다
        assertEquals(0, Regex("(^|[\\s;])k=").findAll(r).count())
        assertEquals(listOf("for k in \$(exec pgrep -P \"\$1\" 2>/dev/null)", "for k in \$(exec pgrep -s \"\$\$\" 2>/dev/null)"), uses("for"))
        // 다른 명령의 출력을 받는 곳도 그 둘과, 부모·좀비 검사의 ps 하나뿐이다 (`$((` 는 셈). 역따옴표는 없다
        assertEquals(
            listOf("\$(exec pgrep -P \"\$1\" 2>/dev/null)", "\$(exec pgrep -s \"\$\$\" 2>/dev/null)", "\$(ps -o ppid= -o stat= -p \"\$c\" 2>/dev/null)"),
            Regex("\\$\\((?!\\()[^)]*\\)").findAll(r).map { it.value }.toList(),
        )
        assertFalse(r.contains("`"))
        // 파일을 읽는 곳: claude 의 입력(.in)과 자기 자식의 /proc 상태 줄뿐
        assertEquals(listOf("\"\$HOME/.kite/runs/$id.in\"", "\"/proc/\$c/stat\";"), Regex("<\\s*(\\S+)").findAll(r).map { it.groupValues[1] }.toList())
        // 번호를 파일이나 다른 출력에서 뽑아낼 만한 명령, 이름으로 여럿을 끝내는 명령이 없다 (claude 한 줄은 빼고 본다)
        val own = r.replace(c.claudeLine(), "").replace(procRead, "")
        assertEquals(1, count(r, c.claudeLine()))
        assertFalse(Regex("\\b(cat|head|tail|sed|awk|cut|grep|xargs|tr|read|source|eval|pkill|killall|killpg|skill|fuser)\\b").containsMatchIn(own))
        assertEquals(3, count(r, "set --"))
        // 살아 있는지 확인하지 못한 것(ps 도 /proc 도 답이 없다, 읽는 사이 없어졌다)은 2 로 답하고, 0 으로 답하는 길은 부모·좀비 검사를 지난 뒤 하나뿐이다
        assertTrue(r.contains("case \"\$1\" in ''|*[!0-9]*) return 2;; esac\n"))
        assertFalse(r.contains("*[!0-9]*) return 0"))
        assertEquals(1, count(r, "return 0\n"))
        // 신호 둘은 바로 앞의 mine 이 0 일 때만 나간다: 같은 if 안, 그 사이에 다른 명령이 없다
        assertTrue(
            r.contains(
                "  mine; r=\$?\n  if [ \$r = 1 ]; then m=\$((m+1)); sleep 0.05; continue; fi\n  m=0\n" +
                    "  if [ \$r = 0 ] && [ -e \"\$HOME/.kite/runs/$id.stop\" ]; then\n    s=1\n" +
                    "    if [ \$t = 0 ]; then kt \"\$c\" TERM; elif [ \$t -ge 5 ]; then kt \"\$c\" KILL; fi\n",
            ),
        )
        assertEquals(1, count(r, "mine;"))
        // 시작 글은 감싼 글을 그대로 싣고, 그 밖에는 신호를 보내는 명령이 없다
        assertEquals(2, count(c.start(id).replace("kt \"\$c\" KILL", "").replace("sw KILL;", "").lowercase(), "kill"))
        assertEquals(2, count(c.start(id), "pgrep"))
    }

    private val longFake = "#!/bin/sh\ncat > /dev/null\necho '{\"type\":\"system\"}'\n"

    @Test
    fun stopEndsWrapperClaudeAndChildren() {
        assumeFalse(System.getProperty("os.name").orEmpty().startsWith("Windows"))
        val mark = mark(1)
        val home = newHome(longFake + "$mark\n")
        val c = DetachedCommands(cfg)
        val id = "stop-1"
        try {
            sh(home, c.upload(id), "hi")
            assertEquals(listOf("APERITRUN $id"), sh(home, c.start(id)).lines)
            val pid = File(home, ".kite/runs/$id.pid").readText().trim()
            Thread.sleep(600)
            assertTrue("돌고 있어야 한다: ${alive(home, mark)}", alive(home, mark).any { it.contains(mark) })
            assertTrue(runnerAlive(pid))
            val t0 = System.currentTimeMillis()
            // 멈춤 요청을 적은 바로 뒤에 그 줄을 낸다 (앱은 이 줄로 멈춤이 닿았다고 본다)
            assertEquals(listOf(Detached.STOP_SENT), sh(home, c.stop(id)).lines)
            val took = System.currentTimeMillis() - t0
            val exit = waitExit(home, id)
            assertTrue("끝난 값: $exit", exit in listOf("143", "137", "stopped"))
            assertTrue("멈춤이 ${took}ms 걸림", took < 5000)
            Thread.sleep(300)
            assertEquals("남은 프로세스", emptyList<String>(), alive(home, mark))
            assertFalse("감싼 bash 가 남았다", runnerAlive(pid))
            assertEquals(listOf("APERITEXIT $exit"), sh(home, c.follow(id, 2)).lines.filter { it.startsWith("APERITEXIT") })
            // 끝난 뒤 다시 멈춰도 아무 일 없다
            val again = sh(home, c.stop(id))
            assertTrue("두 번째 멈춤이 ${again.ms}ms 걸림", again.ms < 3000)
            assertEquals("이미 끝난 실행에는 멈춤 요청을 적지 않는다", emptyList<String>(), again.lines)
            assertEquals(exit, exitOf(home, id))
            // 같은 번호로 다시 시작해도 돌지 않는다
            assertEquals(listOf("APERITRUN $id"), sh(home, c.start(id)).lines)
            Thread.sleep(300)
            assertEquals(emptyList<String>(), alive(home, mark))
        } finally {
            endRuns(home, mark, id)
        }
    }

    /** 이 컴퓨터의 진짜 명령 자리 (가짜를 PATH 맨 앞에 둘 때 그 가짜가 부를 것). */
    private fun real(name: String): String = sh(File("/"), "command -v $name").lines.first().trim()

    /** 시험 폴더의 `.local/bin` 에 가짜 명령을 둔다 (서버 명령은 PATH 맨 앞에 이 자리를 둔다). 가짜는 신호를 보내지 않는다: 부른 것을 적고 실패하거나 진짜를 부른다. */
    private fun shim(home: File, name: String, body: String) {
        val f = File(home, ".local/bin/$name")
        f.writeText("#!/bin/sh\necho x >> \"\$HOME/$name.calls\"\n$body\n")
        f.setExecutable(true)
    }

    private fun calls(home: File, name: String): Int = File(home, "$name.calls").let { if (it.exists()) it.readLines().size else 0 }

    /** `ps` 가 실패해도 감싼 bash 는 멈춤 요청을 계속 지켜본다: 멈춤 뒤 실행이 끝나고 남는 프로세스가 없다. */
    private fun stopEndsTheRunWithPs(psBody: String, id: String, mark: String) {
        assumeFalse(System.getProperty("os.name").orEmpty().startsWith("Windows"))
        val home = newHome(longFake + "$mark\n")
        shim(home, "ps", psBody)
        val c = DetachedCommands(cfg)
        try {
            sh(home, c.upload(id), "hi")
            assertEquals(listOf("APERITRUN $id"), sh(home, c.start(id)).lines)
            val pid = File(home, ".kite/runs/$id.pid").readText().trim()
            Thread.sleep(1000)
            assertTrue("가짜 ps 를 불렀어야 한다", calls(home, "ps") >= 1)
            assertTrue("돌고 있어야 한다: ${alive(home, mark)}", alive(home, mark).any { it.contains(mark) })
            assertTrue(runnerAlive(pid))
            assertEquals("", exitOf(home, id))
            assertEquals(listOf(Detached.STOP_SENT), sh(home, c.stop(id)).lines)
            val exit = waitExit(home, id)
            assertTrue("끝난 값: '$exit' (가짜 ps ${calls(home, "ps")}번 불림)", exit in listOf("143", "137"))
            assertEquals("남은 프로세스", emptyList<String>(), waitGone(home, mark, 2000))
            Thread.sleep(300)
            assertFalse("감싼 bash 가 남았다", runnerAlive(pid))
        } finally {
            endRuns(home, mark, id)
        }
    }

    /** `ps` 가 처음 한 번만 실패한다 (프로세스를 못 띄운 순간 같은 것). */
    @Test
    fun stopEndsTheRunWhenPsFailsOnce() {
        assumeFalse(System.getProperty("os.name").orEmpty().startsWith("Windows"))
        val ps = real("ps")
        stopEndsTheRunWithPs("n=\$(wc -l < \"\$HOME/ps.calls\")\nif [ \"\$n\" -le 1 ]; then exit 1; fi\nexec $ps \"\$@\"", "ps-1", mark(8))
    }

    /** `ps` 가 늘 실패한다 (`-p` 를 모르는 BusyBox 의 ps 처럼). */
    @Test
    fun stopEndsTheRunWhenPsAlwaysFails() {
        stopEndsTheRunWithPs("echo \"ps: invalid option -- 'p'\" >&2\nexit 1", "ps-2", mark(9))
    }

    /**
     * 멈춤 명령 안에서 감싼 bash 를 알아보는 검사(`tr`)가 두 번 실패해도, 멈춤은 살아 있는 감싼 bash 를 없다고 보고 `stopped` 를 적지 않는다.
     * 바로 치워도 멈춤 요청은 남고, 실행은 감싼 bash 가 끝낸다 (끝난 값은 감싼 bash 가 적은 코드다).
     */
    @Test
    fun stopStillEndsTheRunWhenItsCheckFailsTwice() {
        assumeFalse(System.getProperty("os.name").orEmpty().startsWith("Windows"))
        assumeTrue("명령 글을 /proc 에서 tr 로 읽는 컴퓨터에서만", File("/proc/self/cmdline").canRead())
        val tr = real("tr")
        val mark = mark(0)
        val home = newHome(longFake + "$mark\n")
        val c = DetachedCommands(cfg)
        val id = "stop-5"
        try {
            sh(home, c.upload(id), "hi")
            assertEquals(listOf("APERITRUN $id"), sh(home, c.start(id)).lines)
            val pid = File(home, ".kite/runs/$id.pid").readText().trim()
            Thread.sleep(600)
            assertTrue("돌고 있어야 한다", alive(home, mark).any { it.contains(mark) })
            shim(home, "tr", "n=\$(wc -l < \"\$HOME/tr.calls\")\nif [ \"\$n\" -le 2 ]; then exit 1; fi\nexec $tr \"\$@\"")
            assertEquals(listOf(Detached.STOP_SENT), sh(home, c.stop(id)).lines)
            assertTrue("가짜 tr 를 불렀어야 한다", calls(home, "tr") >= 1)
            sh(home, c.clean(id))
            val exit = waitExit(home, id)
            assertTrue("끝난 값: '$exit' (가짜 tr ${calls(home, "tr")}번 불림)", exit in listOf("143", "137"))
            assertEquals("남은 프로세스", emptyList<String>(), waitGone(home, mark, 2000))
            Thread.sleep(300)
            assertFalse("감싼 bash 가 남았다", runnerAlive(pid))
        } finally {
            endRuns(home, mark, id)
        }
    }

    /** 멈춤을 누르지 않았는데 서버에서 멈춤으로 끝난 실행은 말없이 끝나지 않는다: 앱이 가려내는 낱말(STOPPED 등)이 아닌, 세 토막으로 된 한 줄이다. */
    @Test
    fun aStopTheOwnerDidNotPressHasALineOfItsOwn() {
        val line = com.fidenit.kite.engine.STOPPED_ELSEWHERE
        assertTrue(line !in listOf(com.fidenit.kite.engine.STOPPED, com.fidenit.kite.engine.GONE, com.fidenit.kite.engine.ABANDONED))
        assertEquals(3, line.split(" → ").size)
    }

    /** 따라 읽기에서 `tr` 가 한 번 실패해도 (명령 글을 못 읽었다) 멀쩡한 실행을 lost 라 하지 않는다. */
    @Test
    fun followDoesNotSayLostWhenTrFailsOnce() {
        assumeFalse(System.getProperty("os.name").orEmpty().startsWith("Windows"))
        assumeTrue("명령 글을 /proc 에서 tr 로 읽는 컴퓨터에서만", File("/proc/self/cmdline").canRead())
        val tr = real("tr")
        val mark = mark(4)
        val home = newHome(okFake)
        shim(home, "tr", "n=\$(wc -l < \"\$HOME/tr.calls\")\nif [ \"\$n\" -le 1 ]; then exit 1; fi\nexec $tr \"\$@\"")
        val c = DetachedCommands(cfg)
        val id = "tr-1"
        try {
            sh(home, c.upload(id), "hi")
            assertEquals(listOf("APERITRUN $id"), sh(home, c.start(id)).lines)
            val all = sh(home, c.follow(id, 0)).lines
            assertTrue("가짜 tr 를 불렀어야 한다", calls(home, "tr") >= 1)
            assertEquals(all.toString(), "APERITEXIT 0", all.last())
            assertEquals(all.toString(), 6, Detached.countData(all))
        } finally {
            endRuns(home, mark, id)
        }
    }

    /** TERM 을 무시하는 claude 도 멈춤 뒤 3초쯤 안에 없다 (1초 뒤 KILL). */
    @Test
    fun stopEndsAClaudeThatIgnoresTerm() {
        assumeFalse(System.getProperty("os.name").orEmpty().startsWith("Windows"))
        val mark = mark(2)
        val home = newHome("#!/bin/sh\ntrap '' TERM\ncat > /dev/null\necho '{\"type\":\"system\"}'\n$mark\n")
        val c = DetachedCommands(cfg)
        val id = "stop-2"
        try {
            sh(home, c.upload(id), "hi")
            assertEquals(listOf("APERITRUN $id"), sh(home, c.start(id)).lines)
            val pid = File(home, ".kite/runs/$id.pid").readText().trim()
            Thread.sleep(600)
            assertTrue("돌고 있어야 한다", alive(home, mark).any { it.contains(mark) })
            val t0 = System.currentTimeMillis()
            val st = sh(home, c.stop(id))
            val left = waitGone(home, mark, 3000)
            val gone = System.currentTimeMillis() - t0
            assertEquals("남은 프로세스 (${gone}ms)", emptyList<String>(), left)
            assertTrue("멈춤이 ${st.ms}ms 걸림", st.ms < 5000)
            assertTrue("없어지기까지 ${gone}ms", gone < 5000)
            val exit = waitExit(home, id)
            assertTrue("끝난 값: $exit", exit in listOf("137", "stopped"))
            Thread.sleep(300)
            assertFalse(runnerAlive(pid))
        } finally {
            endRuns(home, mark, id)
        }
    }

    /** 두 번 갈라져 나가 부모가 없어진 손자(TERM 도 무시한다)도 멈춤 뒤에 남지 않는다: 감싼 bash 가 자기 세션을 훑는다 (setsid 가 있는 서버). */
    @Test
    fun stopEndsADoubleForkedGrandchild() {
        assumeFalse(System.getProperty("os.name").orEmpty().startsWith("Windows"))
        assumeTrue(sh(File("/"), "command -v setsid >/dev/null 2>&1 && echo yes").lines == listOf("yes"))
        val mark = mark(5)
        val home = newHome("#!/bin/sh\ncat > /dev/null\n( trap '' TERM; ${mark}1 & )\n( ${mark}2 & )\necho '{\"type\":\"system\"}'\n${mark}3\n")
        val c = DetachedCommands(cfg)
        val id = "stop-3"
        try {
            sh(home, c.upload(id), "hi")
            assertEquals(listOf("APERITRUN $id"), sh(home, c.start(id)).lines)
            Thread.sleep(600)
            val before = alive(home, mark)
            assertTrue("손자가 돌고 있어야 한다: $before", before.any { it.contains("${mark}1") } && before.any { it.contains("${mark}2") } && before.any { it.contains("${mark}3") })
            val st = sh(home, c.stop(id))
            assertTrue("멈춤이 ${st.ms}ms 걸림", st.ms < 5000)
            assertEquals("남은 프로세스", emptyList<String>(), waitGone(home, mark, 3000))
            val exit = waitExit(home, id)
            assertTrue("끝난 값: $exit", exit in listOf("143", "137", "stopped"))
        } finally {
            endRuns(home, mark, id)
        }
    }

    /** 시작 전에 멈춤이 먼저 닿았다: 그 뒤의 시작은 아무것도 띄우지 않고, 따라 읽으면 stopped 다. */
    @Test
    fun stopBeforeStartLaunchesNothing() {
        assumeFalse(System.getProperty("os.name").orEmpty().startsWith("Windows"))
        val mark = mark(7)
        val home = newHome(okFake)
        val c = DetachedCommands(cfg)
        val id = "stop-4"
        try {
            sh(home, c.upload(id), "hi")
            val st = sh(home, c.stop(id))
            assertTrue("멈춤이 ${st.ms}ms 걸림", st.ms < 5000)
            assertEquals(listOf(Detached.STOP_SENT), st.lines)
            assertTrue(File(home, ".kite/runs/$id.stop").exists())
            assertEquals("stopped", exitOf(home, id))
            assertEquals(listOf("APERITRUN $id"), sh(home, c.start(id)).lines)
            Thread.sleep(500)
            assertFalse("아무것도 뜨지 않아야 한다", File(home, "cap").exists())
            assertFalse(File(home, ".kite/runs/$id.pid").exists())
            assertEquals(emptyList<String>(), alive(home, mark))
            assertEquals(listOf("APERITEXIT stopped"), sh(home, c.follow(id, 0)).lines)
            // 치우면 끝난 값만 남고, 다시 붙으면 gone 이다
            sh(home, c.clean(id))
            assertEquals(listOf("$id.exit"), File(home, ".kite/runs").list()!!.toList())
            assertEquals(listOf("APERITEXIT gone"), sh(home, c.follow(id, 0)).lines)
        } finally {
            endRuns(home, mark, id)
        }
    }

    /** 돌고 있는 실행을 치우면 멈춤 요청이 남아 그 실행이 끝난다. */
    @Test
    fun cleanOfARunningRunEndsIt() {
        assumeFalse(System.getProperty("os.name").orEmpty().startsWith("Windows"))
        val mark = mark(6)
        val home = newHome(longFake + "$mark\n")
        val c = DetachedCommands(cfg)
        val id = "clean-1"
        try {
            sh(home, c.upload(id), "hi")
            assertEquals(listOf("APERITRUN $id"), sh(home, c.start(id)).lines)
            val pid = File(home, ".kite/runs/$id.pid").readText().trim()
            Thread.sleep(600)
            assertTrue("돌고 있어야 한다", alive(home, mark).any { it.contains(mark) })
            sh(home, c.clean(id))
            assertEquals("남은 프로세스", emptyList<String>(), waitGone(home, mark, 4000))
            var i = 0
            while (exitOf(home, id).isEmpty() && i++ < 30) Thread.sleep(100)
            Thread.sleep(300)
            assertFalse(runnerAlive(pid))
            assertTrue("끝난 값: ${exitOf(home, id)}", exitOf(home, id) in listOf("143", "137"))
            assertEquals(listOf("$id.exit", "$id.stop"), File(home, ".kite/runs").list()!!.sorted())
            assertEquals(listOf("APERITEXIT gone"), sh(home, c.follow(id, 0)).lines)
            assertEquals(listOf("APERITFAIL noin"), sh(home, c.start(id)).lines)
        } finally {
            endRuns(home, mark, id)
        }
    }

    /**
     * `.pid` 의 번호가 남의 프로세스다 (서버가 다시 켜져 번호가 다시 쓰였다; 여기서는 이 시험이 띄운 프로세스):
     * 돌고 있다고 보지 않고, 멈춤도 치우기도 그 프로세스를 건드리지 않는다.
     */
    @Test
    fun foreignPidIsNotOurs() {
        assumeFalse(System.getProperty("os.name").orEmpty().startsWith("Windows"))
        val home = newHome(okFake)
        val c = DetachedCommands(cfg)
        var started: Process? = null
        try {
            val other = ProcessBuilder("sh", "-c", "echo \$\$; exec sleep 36.$RUN").start().also { started = it }
            val otherPid = other.inputStream.bufferedReader().readLine().trim() // 이 시험이 띄운 프로세스가 알려 준 자기 번호
            val runs = File(home, ".kite/runs").also { it.mkdirs() }
            File(runs, "f1.in").writeText("hi")
            File(runs, "f1.pid").writeText(otherPid + "\n")
            val t0 = System.currentTimeMillis()
            assertEquals(listOf("APERITEXIT lost"), sh(home, c.follow("f1", 0)).lines)
            assertTrue("lost 까지 ${System.currentTimeMillis() - t0}ms", System.currentTimeMillis() - t0 < 4000)
            val st = sh(home, c.stop("f1"))
            assertTrue("멈춤이 ${st.ms}ms 걸림", st.ms < 5000)
            assertTrue("남의 프로세스가 살아 있어야 한다", other.isAlive)
            assertEquals("stopped", File(runs, "f1.exit").readText().trim())
            sh(home, c.clean("f1"))
            assertTrue("남의 프로세스가 살아 있어야 한다", other.isAlive)
            // 끝난 값이 감싼 bash 가 적은 코드가 아니라 `stopped` 면 (감싼 bash 가 살아 있는지 잘못 봤을 수도 있다) 멈춤 요청은 지우지 않는다
            assertEquals(listOf("f1.exit", "f1.stop"), runs.list()!!.sorted())
            // 끝난 값이 없는 채로 치워도 (멈춤 요청만 남는다) 그 프로세스는 그대로다
            File(runs, "f2.in").writeText("hi")
            File(runs, "f2.pid").writeText(otherPid + "\n")
            sh(home, c.clean("f2"))
            Thread.sleep(1500)
            assertTrue("남의 프로세스가 살아 있어야 한다", other.isAlive)
            assertEquals(listOf("f1.exit", "f1.stop", "f2.stop"), runs.list()!!.sorted())
        } finally {
            started?.destroyForcibly()
            home.deleteRecursively()
        }
    }

    /** 올리기만 하고 시작 전에 앱이 꺼졌다: `.pid` 도 `.exit` 도 없으면 돌던 적 없는 것(none)이다 — lost 가 아니다. */
    @Test
    fun uploadedButNeverStartedIsNone() {
        assumeFalse(System.getProperty("os.name").orEmpty().startsWith("Windows"))
        val home = newHome(okFake)
        val c = DetachedCommands(cfg)
        try {
            sh(home, c.upload("n1"), "hi")
            assertEquals(listOf("APERITEXIT none"), sh(home, c.follow("n1", 0)).lines)
            // 그 뒤 같은 번호로 시작하면 돈다
            assertEquals(listOf("APERITRUN n1"), sh(home, c.start("n1")).lines)
            assertEquals("APERITEXIT 0", sh(home, c.follow("n1", 0)).lines.last())
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun goneIsClassifiedAndStartedOnlyWhenNothingRemains() {
        assertEquals(FollowLine.Exit("gone"), Detached.classify("APERITEXIT gone"))
        assertEquals(FollowLine.Exit("none"), Detached.classify("APERITEXIT none"))
    }

    /** A server that takes the connection and never says a word: the engine stays inside its connect. */
    private class Silent : AutoCloseable {
        val socket = java.net.ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress())
        val taken = java.util.concurrent.CountDownLatch(1)
        private val held = java.util.concurrent.CopyOnWriteArrayList<java.net.Socket>()
        private val accepting = kotlin.concurrent.thread {
            try {
                while (true) {
                    held.add(socket.accept())
                    taken.countDown()
                }
            } catch (_: Exception) {
            }
        }

        fun ssh() = com.fidenit.kite.engine.Ssh(com.fidenit.kite.engine.Server("127.0.0.1", socket.localPort, "u", "", "", "", "")) {}

        override fun close() {
            socket.close()
            accepting.join(2000)
            held.forEach { it.close() }
        }
    }

    /** R4: close() does not wait for a connect in progress, and that connect ends at once. */
    @Test
    fun closeDoesNotWaitForAConnectInProgress() {
        Silent().use { server ->
            val ssh = server.ssh()
            val ended = java.util.concurrent.CountDownLatch(1)
            kotlin.concurrent.thread {
                try {
                    ssh.connect()
                } catch (_: Exception) {
                }
                ended.countDown()
            }
            assertTrue(server.taken.await(5, TimeUnit.SECONDS)) // the engine is inside its connect now
            val t0 = System.nanoTime()
            ssh.close()
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertTrue("close() took $ms ms", ms < 1000)
            assertTrue("the connect ends when the connection is closed", ended.await(3, TimeUnit.SECONDS))
            assertFalse(ssh.connected)
        }
    }

    /** R4: Stop while the engine is reconnecting to a server that does not answer ends in about 10 s, and the run stays on the clean list as not stopped. */
    @Test
    fun stopWhileReconnectingEndsInTimeAndLeavesTheRunOnTheCleanList() {
        Silent().use { server ->
            val cc = com.fidenit.kite.engine.ClaudeCode(server.ssh(), cfg)
            val id = "20261120-013000-a1b2"
            val stop = java.util.concurrent.atomic.AtomicBoolean(false)
            kotlin.concurrent.thread {
                server.taken.await(5, TimeUnit.SECONDS)
                stop.set(true) // Stop is pressed while the engine is inside its connect
            }
            val t0 = System.nanoTime()
            val e = try {
                cc.resume(id, null, 0, { stop.get() }) {}
                null
            } catch (e: Exception) {
                e
            }
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertEquals(com.fidenit.kite.engine.STOPPED, e?.message)
            assertTrue("Stop took $ms ms", ms < 11_000)
            assertEquals(listOf(id), cc.toClean)
            assertFalse(cc.endedOnServer(id))
        }
    }
}
