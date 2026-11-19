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
        server.enqueue(answer("작업", 0.9))
        val vm = vm("test-key-not-real")
        assertEquals("t2", vm.current) // the last topic
        vm.current = "t1"
        vm.send("항공권 알아봐 줘")
        until("the decision is shown") { vm.liveSay != null }
        assertEquals("네, 바로 해볼게요.", vm.liveSay)
        assertEquals("작업 · 생각하는 중", vm.activity)
        assertEquals("t2", vm.current)
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
        server.enqueue(answer("대화", 0.9))
        val vm = vm("k")
        vm.send("안녕")
        until("the decision is shown") { vm.activity.isNotEmpty() }
        assertEquals("대화 · 생각하는 중", vm.activity)
        assertNull(vm.liveSay)
        until("turn ends") { !vm.busy }
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
}
