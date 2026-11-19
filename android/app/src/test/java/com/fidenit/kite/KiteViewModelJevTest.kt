package com.fidenit.kite

import android.app.Application
import android.os.Looper
import com.fidenit.kite.core.Lang
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The Jev decisions around a turn, through the ViewModel: a fake Jev server, and no Claude key so the engine
 * fails at once after the decision (nothing else is called). Never the real api.typesafe.ai.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KiteViewModelJevTest {
    private lateinit var app: Application
    private lateinit var files: File
    private lateinit var server: MockWebServer

    private val board = "topic t1 여행\ncard k1 note t1 3x2 항공편\ntopic t2 일\ncard k2 note t2 메모\n"

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        files = app.filesDir
        files.listFiles()?.forEach { it.deleteRecursively() }
        for (n in listOf("kite-settings", "kite-settings-plain")) app.getSharedPreferences(n, 0).edit().clear().commit() // no settings left over from another test
        server = MockWebServer().apply { start() }
        File(files, "board.txt").writeText(board)
    }

    @After
    fun down() {
        server.shutdown()
    }

    private fun vm(key: String): KiteViewModel {
        Store(app).jevKey = key
        return KiteViewModel(app).also { it.jevUrl = server.url("/v1/systemone").toString() }
    }

    /**
     * Holds the engine step: the server engine connects to a socket that accepts and never answers, so the turn stays in the
     * engine (after the decision) for as long as the test looks at it. Nothing depends on how fast anything runs.
     */
    private inner class Hold : AutoCloseable {
        private val sock = java.net.ServerSocket(0)
        private val accepted = java.util.Collections.synchronizedList(mutableListOf<java.net.Socket>())

        init {
            Thread { try { while (true) accepted.add(sock.accept()) } catch (_: Exception) {} }.apply { isDaemon = true }.start()
            Store(app).apply { engineKind = "ssh"; host = "127.0.0.1"; port = sock.localPort.toString(); user = "u" }
        }

        override fun close() {
            sock.close()
            accepted.forEach { try { it.close() } catch (_: Exception) {} }
        }
    }

    /** The turn text of the running turn, as the file has it (the writer thread is waited for). */
    private fun inflightText(vm: KiteViewModel): String {
        vm.flushWrites()
        return Journal(files).readInflight()!!.turnText
    }

    /** Runs the main looper one task at a time until `cond` holds (so a state in between can be looked at). */
    private fun until(what: String, cond: () -> Boolean) {
        val looper = shadowOf(Looper.getMainLooper())
        val end = System.currentTimeMillis() + 10_000
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "timed out: $what" }
            if (looper.isIdle) Thread.sleep(10) else looper.runOneTask()
        }
    }

    private fun answer(kind: String, conf: Double, topic: String = "t2", topicConf: Double = 0.9) = MockResponse().setResponseCode(200).setBody(
        """{"answers":{"effort":{"type":"choice","choice":"low","confidence":0.9},"kind":{"type":"choice","choice":"$kind","confidence":$conf},"topic":{"type":"choice","choice":"$topic","confidence":$topicConf}}}""",
    )

    @Test
    fun withoutAKeyJevIsNotCalledAndTheTurnGoesOn() {
        val vm = vm("")
        vm.send("항공권 알아봐 줘")
        until("turn ends") { !vm.busy }
        assertEquals(0, server.requestCount)
        assertNull(vm.liveSay)
        assertTrue(vm.error!!.contains("API")) // no Claude key: the ordinary error, nothing about Jev
    }

    @Test
    fun anEmptyInputIsNotSentToJev() {
        val vm = vm("k")
        vm.send("   ")
        assertEquals(0, server.requestCount)
        assertFalse(vm.busy)
    }

    @Test
    fun kindTopicAndAcknowledgementAreShownBeforeTheEngineAnswers() {
        val hold = Hold()
        server.enqueue(answer("작업", 0.9))
        val vm = vm("test-key-not-real")
        assertEquals("t2", vm.current) // the last topic
        vm.current = "t1"
        vm.send("항공권 알아봐 줘")
        until("the decision is shown") { vm.liveSay != null }
        assertEquals("네, 바로 해볼게요.", vm.liveSay)
        assertEquals("작업 · 생각하는 중", vm.activity)
        assertEquals("t2", vm.current)
        assertTrue("the turn is still held in the engine step", vm.busy)
        hold.close()
        until("turn ends") { !vm.busy }
        assertEquals("t2", vm.current) // the topic stays
        assertEquals(1, server.requestCount)
        val r = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("Bearer test-key-not-real", r.getHeader("Authorization"))
        val state = JSONObject(r.body.readUtf8()).getString("state")
        assertTrue(state, state.startsWith("사용자가 방금 한 말:\n항공권 알아봐 줘\n"))
        assertTrue(state, state.contains("- t1: 여행\n- t2: 일\n"))
        // the call is on disk for later analysis, without the key
        val log = File(files, "jev.log").readText()
        assertTrue(log, log.contains("항공권 알아봐 줘") && log.contains("종류=작업") && log.contains("v0.1.1"))
        assertFalse(log.contains("test-key-not-real"))
    }

    @Test
    fun aSureConversationKindShowsNoAcknowledgement() {
        Hold().use {
            server.enqueue(answer("대화", 0.9))
            val vm = vm("k")
            vm.send("안녕")
            until("the decision is shown") { vm.activity.isNotEmpty() }
            assertEquals("대화 · 생각하는 중", vm.activity)
            assertNull(vm.liveSay)
            assertTrue("the turn is still held in the engine step", vm.busy)
        }
    }

    @Test
    fun aDoubtfulAnswerChangesNothing() {
        server.enqueue(answer("작업", 0.49, topic = "t2", topicConf = 0.59))
        val vm = vm("k")
        vm.current = "t1"
        vm.send("음")
        until("turn ends") { !vm.busy }
        assertEquals(1, server.requestCount)
        assertEquals("t1", vm.current)
        assertNull(vm.liveSay)
    }

    @Test
    fun aTopicThatIsNotOnTheScreenIsNotShown() {
        server.enqueue(answer("대화", 0.9, topic = "t9"))
        val vm = vm("k")
        vm.current = "t1"
        vm.send("안녕")
        until("turn ends") { !vm.busy }
        assertEquals("t1", vm.current)
    }

    @Test
    fun aFailingJevServerLeavesTheTurnAlone() {
        server.enqueue(MockResponse().setResponseCode(500))
        val vm = vm("k")
        vm.send("안녕")
        until("turn ends") { !vm.busy }
        assertEquals(1, server.requestCount)
        assertNull(vm.liveSay)
        assertTrue(vm.error!!.contains("API")) // the engine's own error, not a Jev one
        assertTrue(File(files, "jev.log").readText().contains("실패: Jev HTTP 500"))
    }

    @Test
    fun aTurnPickedUpAgainAfterARestartDoesNotAskJev() {
        Store(app).engineKind = "ssh"
        Store(app).host = "127.0.0.1"
        Store(app).port = "1" // nothing listens here: the real sshd of this machine is never touched
        Store(app).user = "nobody"
        Journal(files).writeInflight(Inflight("ssh", "20261120-013000-a1b2", "<input>x</input>", "여행 짐", board, 1L))
        val vm = vm("k") // resumes; it never reaches a server, the point is that it does not call Jev
        assertTrue(vm.busy)
        repeat(20) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertEquals(0, server.requestCount)
        vm.stop()
    }

    // --- J-5: after the turn ---------------------------------------------------------------

    private fun after(vararg parts: String) = MockResponse().setResponseCode(200).setBody("""{"answers":{${parts.joinToString(",")}}}""")

    @Test
    fun afterATurnTheCardsTheAiTouchedAreJudgedAndTheReviewGoesIntoTheNextTurnOnce() {
        val vm = vm("test-key-not-real")
        vm.aiLine("card k3 note t1 2x1 정리")
        vm.aiLine("say 정리했어요")
        server.enqueue(after(""""clear_k3":{"type":"noul","noul":0.2}""", """"imp_k3":{"type":"score","score":2.0,"confidence":0.9}"""))
        vm.reviewTurn()
        until("the review is in") { vm.importance.isNotEmpty() }
        assertEquals(1, server.requestCount)
        val body = JSONObject(server.takeRequest(1, TimeUnit.SECONDS)!!.body.readUtf8())
        assertEquals(setOf("clear_k3", "imp_k3"), body.getJSONObject("questions").keys().asSequence().toSet()) // only what the AI touched, and only cards
        assertTrue(body.getString("state").contains("card k3 note t1 2x1 정리"))
        assertEquals(1.0, vm.importance["k3"]!!, 1e-9)
        // the next turn carries the review once (the key is taken away so that this send asks nothing)
        Store(app).jevKey = ""
        vm.send("다음 말")
        val text = inflightText(vm)
        assertTrue(text, text.contains("<review>\ncard k3: hard to tell what it is about from the screen alone (probability it is clear: 0.20)\n</review>\n"))
        until("turn ends") { !vm.busy }
        // a second turn: a fake Jev answer that never comes keeps it running while its text is read
        Store(app).jevKey = "k"
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
        vm.send("또 다음 말")
        assertFalse(inflightText(vm).contains("<review>"))
    }

    @Test
    fun aClearCardAddsNoReviewAndNoKeyMeansNoCall() {
        val vm = vm("k")
        vm.aiLine("card k3 note t1 2x1 정리")
        server.enqueue(after(""""clear_k3":{"type":"noul","noul":0.4}""", """"imp_k3":{"type":"score","score":0.0,"confidence":0.9}"""))
        vm.reviewTurn()
        until("the review is in") { vm.importance.isNotEmpty() }
        assertEquals(0.0, vm.importance["k3"]!!, 1e-9)
        Store(app).jevKey = ""
        // the engine is a fake that keeps the turn text it gets (what the model would be sent)
        val sent = mutableListOf<String>()
        vm.runnerOverride = object : com.fidenit.kite.engine.Runner {
            override val label = "fake"
            override fun run(turnText: String, stopped: () -> Boolean, send: (com.fidenit.kite.engine.Ev) -> Unit): com.fidenit.kite.engine.Stats {
                sent.add(turnText)
                return com.fidenit.kite.engine.Stats()
            }
        }
        vm.send("다음 말")
        until("turn ends") { !vm.busy }
        assertEquals(1, sent.size)
        assertFalse(sent[0], sent[0].contains("<review>"))
        // without a key: nothing is asked
        vm.aiLine("card k4 note t1 2x1 또")
        vm.reviewTurn()
        assertEquals(1, server.requestCount)
    }

    @Test
    fun importanceOfEarlierCardsStaysWhenLaterOnesAreJudged() {
        val vm = vm("k")
        vm.aiLine("card k3 note t1 2x1 정리")
        server.enqueue(after(""""imp_k3":{"type":"score","score":2.0,"confidence":0.9}"""))
        vm.reviewTurn()
        until("first") { vm.importance.containsKey("k3") }
        vm.aiLine("card k4 note t1 2x1 또")
        server.enqueue(after(""""imp_k4":{"type":"score","score":0.0,"confidence":0.9}"""))
        vm.reviewTurn()
        until("second") { vm.importance.containsKey("k4") }
        assertEquals(1.0, vm.importance["k3"]!!, 1e-9)
        assertEquals(0.0, vm.importance["k4"]!!, 1e-9)
        vm.clearAll()
        assertTrue(vm.importance.isEmpty())
    }

    @Test
    fun aFailingJevServerAfterTheTurnChangesNothing() {
        val vm = vm("k")
        vm.aiLine("card k3 note t1 2x1 정리")
        server.enqueue(MockResponse().setResponseCode(500))
        vm.reviewTurn()
        until("the call was made") { server.requestCount == 1 }
        repeat(30) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue(vm.importance.isEmpty())
        Store(app).jevKey = ""
        vm.send("다음 말")
        assertFalse(inflightText(vm).contains("<review>"))
    }
}
