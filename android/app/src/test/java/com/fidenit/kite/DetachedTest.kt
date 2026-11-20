package com.fidenit.kite

import com.fidenit.kite.engine.Detached
import com.fidenit.kite.engine.DetachedCommands
import com.fidenit.kite.engine.EngineConfig
import com.fidenit.kite.engine.FollowLine
import com.fidenit.kite.engine.Step
import com.fidenit.kite.engine.Waker
import com.fidenit.kite.engine.readLines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class DetachedTest {
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
        proc.outputStream.use { if (stdin != null) it.write(stdin.toByteArray()) }
        val lines = proc.inputStream.bufferedReader().readLines()
        assertTrue("시간 초과: $cmd", proc.waitFor(timeoutSec, TimeUnit.SECONDS))
        return Out(lines, System.currentTimeMillis() - t0)
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

    private fun alive(home: File): List<String> = sh(File("/"), "ps -eo args").lines.filter { (it.contains(home.absolutePath) || it.contains("sleep 37.31")) && !it.contains("ps -eo") }

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
            sh(home, c.stop(id)) // 치운 실행에 멈춤은 아무 일도 없다
            assertEquals("0", File(home, ".kite/runs/$id.exit").readText().trim())
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

    @Test
    fun stopEndsWrapperClaudeAndChildren() {
        assumeFalse(System.getProperty("os.name").orEmpty().startsWith("Windows"))
        val home = newHome("#!/bin/sh\ncat > /dev/null\necho '{\"type\":\"system\"}'\nsleep 37.31\necho '{\"type\":\"result\"}'\n")
        val c = DetachedCommands(cfg)
        val id = "stop-1"
        try {
            sh(home, c.upload(id), "hi")
            assertEquals(listOf("APERITRUN $id"), sh(home, c.start(id)).lines)
            Thread.sleep(600)
            assertTrue("돌고 있어야 한다: ${alive(home)}", alive(home).any { it.contains("sleep 37.31") })
            val t0 = System.currentTimeMillis()
            sh(home, c.stop(id))
            val took = System.currentTimeMillis() - t0
            val exit = File(home, ".kite/runs/$id.exit").readText().trim()
            assertTrue("끝난 값: $exit", exit in listOf("143", "137", "stopped"))
            assertTrue("멈춤이 ${took}ms 걸림", took < 5000)
            Thread.sleep(300)
            assertEquals("남은 프로세스", emptyList<String>(), alive(home))
            assertEquals(listOf("APERITEXIT $exit"), sh(home, c.follow(id, 2)).lines.filter { it.startsWith("APERITEXIT") })
            // 끝난 뒤 다시 멈춰도 아무 일 없다
            sh(home, c.stop(id))
            assertEquals(exit, File(home, ".kite/runs/$id.exit").readText().trim())
        } finally {
            sh(File("/"), "pkill -f ${home.absolutePath}; true")
            home.deleteRecursively()
        }
    }

    /** 서버가 다시 켜져 `.pid` 의 번호가 남의 프로세스가 됐다: 돌고 있다고 보지 않고, 멈춤도 그 프로세스를 건드리지 않는다. */
    @Test
    fun foreignPidIsNotOurs() {
        assumeFalse(System.getProperty("os.name").orEmpty().startsWith("Windows"))
        val home = newHome(okFake)
        val c = DetachedCommands(cfg)
        val other = ProcessBuilder("sh", "-c", "echo \$\$; exec sleep 51.7").start()
        val otherPid = other.inputStream.bufferedReader().readLine().trim()
        try {
            val runs = File(home, ".kite/runs").also { it.mkdirs() }
            File(runs, "f1.in").writeText("hi")
            File(runs, "f1.pid").writeText(otherPid + "\n")
            val t0 = System.currentTimeMillis()
            assertEquals(listOf("APERITEXIT lost"), sh(home, c.follow("f1", 0)).lines)
            assertTrue("lost 까지 ${System.currentTimeMillis() - t0}ms", System.currentTimeMillis() - t0 < 4000)
            sh(home, c.stop("f1"))
            assertTrue("남의 프로세스가 살아 있어야 한다", other.isAlive)
            assertEquals("stopped", File(runs, "f1.exit").readText().trim())
        } finally {
            other.destroyForcibly()
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
            // 멈춤은 아무것도 끝내지 않고, 시작한 적이 없으니 `.exit` 도 적지 않는다
            sh(home, c.stop("n1"))
            assertFalse(File(home, ".kite/runs/n1.exit").exists())
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
