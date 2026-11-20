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
import com.fidenit.kite.engine.GatewayLink
import com.fidenit.kite.engine.NOTE
import com.fidenit.kite.engine.PutResult
import com.fidenit.kite.engine.StateClient
import com.fidenit.kite.engine.Link
import com.fidenit.kite.engine.STOPPED
import com.fidenit.kite.engine.gatewayClient
import com.fidenit.kite.voice.Eleven
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 실제 게이트웨이(aperit-gateway)에 붙어 Pro 앱과 같은 코드(GatewayLink + ClaudeCode)로 턴을 돌려 본다. free 변형에서 돈다 (BuildConfig.PRO 가 필요 없다).
 * 환경 변수가 둘 다 있을 때만 돈다 (없으면 건너뛴다):
 *   APERIT_LIVE_GATEWAY_URL    게이트웨이 주소 (예: http://127.0.0.1:8787)
 *   APERIT_LIVE_GATEWAY_TOKEN  이미 허용된 기기의 비밀 글. 이 시험은 등록하지 않고, 이 글을 찍지 않는다.
 *   APERIT_LIVE_EFFORT         (있으면) 보낼 생각의 깊이
 */
class GatewayLiveTest {
    private fun env(k: String) = System.getenv(k).orEmpty()

    /** 이 서버를 쓰는 사람의 말로 기록되지 않게 시험 입력 앞에 붙인다. */
    private val tag = "[f048 시험 — 대표 말 아님] "

    /** 앱이 하는 것처럼 줄을 보드에 반영한다 (LiveTest 와 같다). */
    private class Applier {
        val board = Board()
        private val stream = LineStream()
        val rejected = mutableListOf<String>()
        val activity = mutableListOf<String>()

        private fun line(l: String) {
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
                is Ev.Screen -> ev.reply.complete("반영됨")
            }
        }

        fun finish() {
            stream.finish()?.let { line(it) }
        }
    }

    private class Rig(val link: GatewayLink, val cc: ClaudeCode, val home: String)

    private fun rig(): Rig {
        assumeTrue("APERIT_LIVE_GATEWAY_URL / APERIT_LIVE_GATEWAY_TOKEN not set", env("APERIT_LIVE_GATEWAY_URL").isNotEmpty() && env("APERIT_LIVE_GATEWAY_TOKEN").isNotEmpty())
        val effort = env("APERIT_LIVE_EFFORT")
        val link = GatewayLink(env("APERIT_LIVE_GATEWAY_URL"), { env("APERIT_LIVE_GATEWAY_TOKEN") }, effort, { false }, gatewayClient("0.2.0-pro"))
        val cc = ClaudeCode(link, EngineConfig("claude", "", "default", effort, ""))
        val (_, home) = cc.where()
        println("게이트웨이의 Claude Code: ${cc.version()}, 폴더 $home")
        cc.prepare(File("../../src/prompt.md").readText().replace("{{PARTS}}", partsTable().trimEnd()) + NOTE)
        return Rig(link, cc, home)
    }

    private fun turn(r: Rig, input: String) =
        "<board>\n</board>\n<voice>on</voice>\n<env>\n지금: 2026-11-20 (금) 12:00\n운영체제: Linux (Claude Code 가 도는 서버. 사용자의 화면은 안드로이드 폰)\n사용자 홈 폴더: ${r.home}\n기억 보관함 폴더: ${r.home}/.kite/vault\n</env>\n<input>$tag$input</input>"

    /** 지시문을 올리고, 실제 턴 하나가 보드에 카드를 만든다. */
    @Test
    fun aRealTurnMakesACard() {
        val r = rig()
        val a = Applier()
        val stats = r.cc.run(turn(r, "다음 주 제주 2박 3일 여행 준비물을 체크리스트 카드 한 장으로 만들어 줘. 다섯 개만."), { false }, a::send)
        a.finish()
        println("${stats.model}  첫 반응 ${stats.firstMs}ms  전체 ${stats.totalMs}ms  입력 ${stats.inputTokens} 출력 ${stats.outputTokens}")
        println("거부된 줄: ${a.rejected}")
        println(Lang.serialize(a.board))
        assertTrue("카드가 만들어져야 한다", a.board.cards.isNotEmpty())
    }

    /**
     * 따라 읽는 도중에 한 번 끊는다 → 엔진이 받은 줄 수(n)부터 다시 붙어 끝까지 받는다. 그 뒤 같은 실행을 0번 줄부터 다시 받아
     * 서버의 줄과 엔진이 처리한 줄이 줄마다 같은지(빠지거나 겹치지 않았는지) 본다.
     */
    @Test
    fun aCutMidFollowIsReattachedFromNWithNoLineLostOrDoubled() {
        val r = rig()
        r.cc.cleanAfterRun = false
        val a = Applier()
        val fed = mutableListOf<String>()
        var cut = false
        r.cc.onDataLine = { line ->
            fed.add(line)
            if (!cut && fed.size >= 3) {
                cut = true
                Thread { r.link.close() }.start() // 읽는 스레드가 아닌 곳에서 끊는다
            }
        }
        val input = "먼저 say 한 줄을 내고, Bash 로 \"for i in 1 2 3 4 5; do date; sleep 2; done\" 를 한 번 실행한 다음, 카드 한 장에 '끝'이라고 써 줘."
        r.cc.run(turn(r, input), { false }, a::send)
        a.finish()
        val id = r.cc.runId!!
        println("실행 $id: 받은 줄 ${r.cc.linesReceived}, 상태 줄 ${a.activity.filter { it.contains("연결이 끊겼습니다") }}")
        assertTrue("한 번 끊었어야 한다", cut)
        assertTrue("스스로 다시 붙었다고 알려야 한다: ${a.activity}", a.activity.any { it.contains("연결이 끊겼습니다 →") })
        assertTrue("카드가 있어야 한다", a.board.cards.isNotEmpty())
        assertEquals("엔진이 센 줄 수 = 처리한 줄 수", fed.size, r.cc.linesReceived)

        val again = mutableListOf<String>()
        r.cc.onDataLine = { again.add(it) }
        r.cc.resume(id, null, 0, { false }) {}
        assertEquals("0번 줄부터 다시 받은 줄 = 끊겼다 붙으며 받은 줄 (빠짐·겹침 없음)", again, fed)
        assertTrue("result 줄이 있어야 한다", fed.any { it.contains("\"type\":\"result\"") })
        r.cc.clean(id)
    }

    /**
     * 상태 왕복 (part B): 받기 → 그 판 위에 올리기 → 같은 것을 다시 받기 → 옛 판으로 올리면 412 와 서버의 판. 서버에 있던 문서는 끝에 그 위에 다시 올려 되돌린다
     * (같은 사용자의 보드이므로). 서버에 아무것도 없었으면 시험 문서가 남는다.
     */
    @Test
    fun stateRoundTripsWithRevHandling() {
        assumeTrue("APERIT_LIVE_GATEWAY_URL / APERIT_LIVE_GATEWAY_TOKEN not set", env("APERIT_LIVE_GATEWAY_URL").isNotEmpty() && env("APERIT_LIVE_GATEWAY_TOKEN").isNotEmpty())
        val c = StateClient(env("APERIT_LIVE_GATEWAY_URL"), { env("APERIT_LIVE_GATEWAY_TOKEN") }, gatewayClient("0.2.0-pro"))
        val before = c.get()
        val base = before?.rev ?: 0
        val doc = org.json.JSONObject().put("v", 1).put("board", "").put("note", tag + System.currentTimeMillis())
        val put = c.put(doc, base)
        assertTrue("put on the current rev: $put", put is PutResult.Ok && put.rev == base + 1)
        val got = c.get()!!
        assertEquals(base + 1, got.rev)
        assertTrue("same document back", doc.similar(got.doc))
        val stale = c.put(doc, base)
        assertEquals("a stale rev is refused with the server's rev", PutResult.Conflict(base + 1), stale)
        if (before != null) {
            val back = c.put(before.doc, base + 1)
            assertTrue("restored: $back", back is PutResult.Ok)
        }
        println("상태: 처음 판 $base → 시험 ${base + 1}" + if (before != null) " → 되돌림 ${base + 2}" else " (서버에 없던 상태: 시험 문서가 남음)")
    }

    /** 올리기·시작을 센다 (다시 올리거나 다시 시작하지 않았는지 보려고). */
    private class Counting(val inner: Link) : Link by inner {
        @Volatile var uploads = 0

        @Volatile var starts = 0

        override fun upload(id: String, text: String?): Pair<Int, String> = inner.upload(id, text).also { uploads++ }

        override fun start(id: String, onLine: (String) -> Unit): Pair<Int, String> = inner.start(id, onLine).also { starts++ }
    }

    /**
     * part C: 도구가 도는 동안 멈춤 (뷰모델처럼 `stopped` 를 참으로) → 10초 안에 STOPPED, 다시 올리기·시작 없음,
     * 그 뒤 n 부터 따라 읽으면 끝난 값(143 / stopped) 이나 gone.
     */
    @Test
    fun aStopEndsTheTurnAsStopped() {
        assumeTrue("APERIT_LIVE_GATEWAY_URL / APERIT_LIVE_GATEWAY_TOKEN not set", env("APERIT_LIVE_GATEWAY_URL").isNotEmpty() && env("APERIT_LIVE_GATEWAY_TOKEN").isNotEmpty())
        val effort = env("APERIT_LIVE_EFFORT")
        val link = GatewayLink(env("APERIT_LIVE_GATEWAY_URL"), { env("APERIT_LIVE_GATEWAY_TOKEN") }, effort, { false }, gatewayClient("0.2.0-pro"))
        val counting = Counting(link)
        val cc = ClaudeCode(counting, EngineConfig("claude", "", "default", effort, ""))
        val (_, home) = cc.where()
        cc.prepare(File("../../src/prompt.md").readText().replace("{{PARTS}}", partsTable().trimEnd()) + NOTE)
        cc.cleanAfterRun = false
        val r = Rig(link, cc, home)
        val lines = java.util.concurrent.atomic.AtomicInteger(0)
        val sawBash = java.util.concurrent.atomic.AtomicBoolean(false)
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val stopAt = java.util.concurrent.atomic.AtomicLong(0)
        cc.onDataLine = { l ->
            lines.incrementAndGet()
            if (l.contains("\"name\":\"Bash\"")) sawBash.set(true)
        }
        val trigger = Thread {
            val deadline = System.currentTimeMillis() + 45_000
            while (!(lines.get() >= 1 && sawBash.get()) && System.currentTimeMillis() < deadline) Thread.sleep(100)
            stopAt.set(System.currentTimeMillis())
            stop.set(true)
        }.apply {
            isDaemon = true
            start()
        }
        val err = runCatching { cc.run(turn(r, "Use the Bash tool to run: sleep 60  and then reply done."), { stop.get() }) {} }.exceptionOrNull()
        val took = System.currentTimeMillis() - stopAt.get()
        trigger.join(1000)
        println("멈춤까지 ${took}ms, 받은 줄 ${cc.linesReceived}, Bash ${sawBash.get()}")
        assertTrue("a Bash tool call must have been seen before the stop", sawBash.get())
        assertEquals(STOPPED, err?.message)
        assertTrue("stopped in $took ms", took < 10_000)
        assertEquals("no second upload", 1, counting.uploads)
        assertEquals("no second start", 1, counting.starts)
        val id = cc.runId!!
        val got = mutableListOf<String>()
        link.follow(id, cc.linesReceived) { got.add(it) }
        val end = got.lastOrNull { it.startsWith("APERITEXIT") }
        println("그 뒤 따라 읽기의 끝: $end")
        assertTrue("end value: $end", end in listOf("APERITEXIT 143", "APERITEXIT stopped", "APERITEXIT gone"))
        cc.clean(id)
    }

    /**
     * Part D (voice, spec 10.3): only when the two variables above are set AND `APERIT_LIVE_VOICE=1` (each run costs ElevenLabs credit on the server's key).
     * A Korean sentence through the server's voice as `pcm_16000` -> PCM bytes arrive (prints bytes, first-byte ms, total ms); that PCM -> speech-to-text (`kor`) contains "안녕".
     * Never prints the token.
     */
    @Test
    fun aKoreanSentenceIsSpokenAndHeardBackThroughTheServerVoice() {
        assumeTrue(
            "APERIT_LIVE_GATEWAY_URL / APERIT_LIVE_GATEWAY_TOKEN not set or APERIT_LIVE_VOICE != 1",
            env("APERIT_LIVE_GATEWAY_URL").isNotEmpty() && env("APERIT_LIVE_GATEWAY_TOKEN").isNotEmpty() && env("APERIT_LIVE_VOICE") == "1",
        )
        val t = Eleven.Target.pro(env("APERIT_LIVE_GATEWAY_URL"), env("APERIT_LIVE_GATEWAY_TOKEN"), "0.3.0-pro")
        val got = java.io.ByteArrayOutputStream()
        val t0 = System.nanoTime()
        var first = -1L
        Eleven.speak(t, "", "안녕하세요, 아페리트입니다.", object : Eleven.Sink {
            override fun push(pcm: ShortArray, n: Int) {
                if (first < 0) first = (System.nanoTime() - t0) / 1_000_000
                for (i in 0 until n) {
                    got.write(pcm[i].toInt() and 0xFF)
                    got.write((pcm[i].toInt() shr 8) and 0xFF)
                }
            }

            override fun pendingSecs(): Float = 0f

            override fun stop() {}
        }, { false }, format = "pcm_16000")
        val total = (System.nanoTime() - t0) / 1_000_000
        val bytes = got.toByteArray()
        println("voice tts bytes=${bytes.size} first_ms=$first total_ms=$total")
        assertTrue("PCM bytes arrived", bytes.size > 16000) // more than half a second at 16 kHz, 16 bit
        val pcm = ShortArray(bytes.size / 2) { ((bytes[2 * it + 1].toInt() shl 8) or (bytes[2 * it].toInt() and 0xFF)).toShort() }
        val s0 = System.nanoTime()
        val heard = Eleven.transcribe(t, pcm, false)
        println("voice stt chars=${heard.length} ms=${(System.nanoTime() - s0) / 1_000_000} text=$heard")
        assertTrue("heard: $heard", heard.contains("안녕"))
    }
}
