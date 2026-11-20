package com.fidenit.kite

import android.app.Application
import android.os.Looper
import com.fidenit.kite.engine.Ev
import com.fidenit.kite.engine.GONE
import com.fidenit.kite.engine.Runner
import com.fidenit.kite.engine.Stats
import com.fidenit.kite.core.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * Smoke tests for KiteViewModel on the JVM (Robolectric): the start-up and restore code that otherwise only
 * runs on a phone. Only files in the app's files dir are involved; no server, no key, no real network.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KiteViewModelTest {
    private lateinit var app: Application
    private lateinit var files: File

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        files = app.filesDir
        files.listFiles()?.forEach { it.deleteRecursively() }
        for (n in listOf("kite-settings", "kite-settings-plain")) app.getSharedPreferences(n, 0).edit().clear().commit() // no settings left over from another test
    }

    private fun vm() = KiteViewModel(app)

    private val jevServer = okhttp3.mockwebserver.MockWebServer()

    @After
    fun stopJevServer() {
        com.fidenit.kite.engine.Jev.baseUrl = com.fidenit.kite.engine.Jev.URL
        try {
            jevServer.shutdown()
        } catch (_: Exception) {
        }
    }

    /**
     * A ViewModel whose first turn stays running for a while: the decision call (a fake Jev server that never answers) takes its
     * 2.5 s before the engine is asked, so `busy` is true when the test looks at it. Without this the engine (no key) fails at once.
     */
    private fun vmWithTurnHeld(): KiteViewModel {
        jevServer.start()
        jevServer.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = okhttp3.mockwebserver.MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE)
        }
        Store(app).jevKey = "test-key-not-real"
        return KiteViewModel(app).also { it.jevUrl = jevServer.url("/v1/systemone").toString() }
    }

    /** Runs the main looper one task at a time until `cond` holds. */
    private fun until(what: String, cond: () -> Boolean) {
        val looper = shadowOf(Looper.getMainLooper())
        val end = System.currentTimeMillis() + 10_000
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "timed out: $what" }
            if (looper.isIdle) Thread.sleep(10) else looper.runOneTask()
        }
    }

    /** An engine for the tests: says what it is told to, can wait on a latch, can fail. */
    private class Fake(
        val lines: String = "",
        val failWith: String? = null,
        val gate: java.util.concurrent.CountDownLatch? = null,
    ) : Runner {
        override val label = "fake"
        val started = java.util.concurrent.CountDownLatch(1)
        private var calls = 0

        override fun run(turnText: String, stopped: () -> Boolean, send: (Ev) -> Unit): Stats {
            started.countDown()
            gate?.await(10, java.util.concurrent.TimeUnit.SECONDS)
            failWith?.let { throw Exception(it) }
            if (lines.isNotEmpty() && calls++ == 0) send(Ev.Delta(lines)) // only the first turn says it (a later turn for the tap notices must not redo it)
            return Stats()
        }
    }

    private fun serverSettings() = Store(app).apply { engineKind = "ssh"; host = "h"; user = "u" }

    private fun write(name: String, text: String) = File(files, name).writeText(text)

    private fun exists(name: String) = File(files, name).exists()

    /** Two topics, a checklist with two open items, a note. */
    private val board = "topic t1 여행\ncard k1 checklist t1 3x2 준비물\nadd k1 i1 todo | 여권\nadd k1 i2 todo | 충전기\ntopic t2 일\ncard k2 note t2 메모\n"
    private val recent = "[[\"안녕\",\"반가워요\"],[\"둘째 말\",\"마지막 한마디\"]]"

    private fun same(a: String, b: String) = assertEquals(Lang.serialize(Lang.load(a)), b)

    private fun inflight(engine: String, id: String, boardText: String = board) = Journal(files).writeInflight(Inflight(engine, id, "<input>x</input>", "여행 짐 정리해 줘", boardText, 1L))

    // (1)
    @Test
    fun freshInstallConstructsAndNeedsSetup() {
        val vm = vm()
        assertTrue(vm.needsSetup())
        assertTrue(vm.board.cards.isEmpty())
        assertNull(vm.current)
        assertFalse(vm.busy)
        assertNull(vm.error)
        assertTrue(vm.recent.isEmpty())
    }

    // (2)
    @Test
    fun savedBoardTopicAndLastSayAreRestored() {
        write("board.txt", board)
        write("recent.json", recent)
        write("current.txt", "t1")
        val vm = vm()
        same(board, Lang.serialize(vm.board))
        assertEquals(2, vm.recent.size)
        assertEquals("마지막 한마디", vm.board.say)
        assertEquals("t1", vm.current) // the topic the owner was looking at, not just the last one
        assertNull(vm.error)
        assertFalse(vm.busy)
        // a stored topic that is gone falls back to the last topic
        write("current.txt", "t9")
        assertEquals("t2", vm().current)
    }

    // (3)
    @Test
    fun serverTurnWithoutServerSettingsIsDroppedWithAnErrorLine() {
        Store(app).engineKind = "ssh" // no host: nothing to re-attach to
        write("board.txt", board)
        write("recent.json", recent)
        inflight("ssh", "20261120-013000-a1b2")
        val vm = vm()
        vm.flushWrites()
        assertFalse(vm.busy)
        assertNull(Journal(files).readInflight())
        assertFalse(exists("inflight.json"))
        val e = vm.error!!
        assertTrue(e, e.contains("여행 짐 정리해 줘"))
        same(board, Lang.serialize(vm.board)) // board unchanged
        assertEquals(listOf("20261120-013000-a1b2"), Journal(files).readToClean()) // the server run is cleaned up on the next connect
    }

    @Test
    fun interruptedTurnBringsBackTheBoardFromBeforeIt() {
        write("board.txt", board + "card k3 note t2 반쯤 적용된 카드\n") // the turn had already applied a line
        inflight("api", "")
        val vm = vm()
        vm.flushWrites()
        same(board, Lang.serialize(vm.board))
        assertNull(vm.board.card("k3"))
        same(board, File(files, "board.txt").readText().let { Lang.serialize(Lang.load(it)) }) // and the file says so too
    }

    // (4)
    @Test
    fun apiTurnRecordGivesTheCutOffLineWithTheShownText() {
        write("board.txt", board)
        inflight("api", "")
        val vm = vm()
        vm.flushWrites()
        val e = vm.error!!
        assertTrue(e, e.contains("여행 짐 정리해 줘"))
        assertTrue(e, e.contains("끊겼"))
        assertFalse(exists("inflight.json"))
        assertFalse(vm.busy)
        same(board, Lang.serialize(vm.board))
    }

    @Test
    fun apiTurnRecordErrorLineIsEnglishWhenTheAppIsEnglish() {
        Store(app).lang = "en"
        inflight("api", "")
        val v = vm()
        val e = v.error!!
        v.flushWrites()
        assertTrue(e, e.contains("여행 짐 정리해 줘") && e.contains("closed") && e.contains("send it again"))
        assertFalse(exists("inflight.json"))
    }

    // (5)
    @Test
    fun brokenFilesDoNotCrashTheStart() {
        for (n in listOf("board.txt", "recent.json", "inflight.json", "queue.json", "toclean.json", "current.txt")) write(n, "{\"not json ][ \u0000 ???")
        val vm = vm()
        assertTrue(vm.needsSetup())
        assertFalse(vm.busy)
        assertNull(vm.error) // a record that cannot be read is no record
        assertTrue(vm.recent.isEmpty())
        assertTrue(vm.board.cards.isEmpty())
        vm.send("") // and the app still works afterwards
        assertFalse(vm.busy)
    }

    @Test
    fun emptyAndTruncatedFilesDoNotCrashTheStart() {
        for (n in listOf("board.txt", "recent.json", "inflight.json", "queue.json", "toclean.json", "current.txt")) write(n, "")
        assertFalse(vm().busy)
        write("inflight.json", "{\"engine\":\"ssh\",\"id\":\"a;b\",\"turnText\":\"x\"") // cut in the middle, and an id that is not allowed
        write("queue.json", "{\"pending\":[\"가\"")
        assertNull(vm().error)
    }

    // (6)
    @Test
    fun cardTapIsOnDiskAtOnceAndComesBackAfterARestart() {
        write("board.txt", board)
        val vm = vm()
        val card = vm.board.card("k1")!!
        vm.toggle(card, card.items.first())
        // the tap is written by the writer thread: on disk before the app can be stopped normally (background() and onCleared flush)
        vm.flushWrites()
        assertTrue(File(files, "board.txt").readText().contains("add k1 i1 done | 여권"))
        assertEquals(listOf("upd k1 i1 done | 여권"), Journal(files).readQueue().events)
        val again = vm() // a new ViewModel on the same files dir (as after a kill that came after the flush)
        assertEquals("done", again.board.card("k1")!!.items.first().cells.first())
        assertEquals(listOf("upd k1 i1 done | 여권"), Journal(files).readQueue().events) // still to be told to the model
    }

    @Test
    fun queuedInputsAndEventsAreRestoredAndStartedAfterARestart() {
        write("board.txt", board)
        val vm = vmWithTurnHeld()
        vm.send("첫째 말") // the turn starts (the engine answers on the IO thread; its result is not delivered in this test)
        assertTrue(vm.busy)
        vm.flushWrites()
        assertNotNull(Journal(files).readInflight())
        val card = vm.board.card("k1")!!
        vm.toggle(card, card.items.first())
        vm.send("둘째 말")
        vm.send("셋째 말")
        vm.flushWrites()
        val q = Journal(files).readQueue()
        assertEquals(listOf("둘째 말", "셋째 말"), q.pending)
        assertEquals(listOf("upd k1 i1 done | 여권"), q.events)
        // the app is killed here; the turn record went with it (a finished turn deletes it before the queue is started)
        Journal(files).clearInflight()
        // the restarted ViewModel asks the same fake Jev server (never answering), which keeps its turn running while we look
        com.fidenit.kite.engine.Jev.baseUrl = jevServer.url("/v1/systemone").toString()
        val again = vm()
        again.flushWrites()
        assertTrue(again.busy) // the queued words were started as a turn
        val sent = Journal(files).readInflight()!!.turnText
        assertTrue(sent, sent.contains("<input>둘째 말\n셋째 말</input>"))
        assertTrue(sent, sent.contains("<events>\nupd k1 i1 done | 여권\n</events>"))
        assertEquals("done", again.board.card("k1")!!.items.first().cells.first())
    }

    @Test
    fun cardTapsMadeDuringAnInterruptedTurnAreAppliedAgainToTheRestoredBoard() {
        inflight("api", "")
        Journal(files).writeQueue(emptyList(), listOf("upd k1 i1 done | 여권"))
        write("board.txt", board)
        val vm = vm()
        assertEquals("done", vm.board.card("k1")!!.items.first().cells.first())
    }

    // (7)
    @Test
    fun clearAllRemovesTheJournalRecords() {
        write("board.txt", board)
        write("recent.json", recent)
        val vm = vmWithTurnHeld()
        vm.send("첫째 말")
        vm.send("둘째 말")
        vm.flushWrites()
        assertTrue(exists("inflight.json") && exists("queue.json"))
        vm.clearAll()
        vm.flushWrites()
        assertFalse(exists("inflight.json"))
        assertFalse(exists("queue.json"))
        assertTrue(vm.board.cards.isEmpty())
        assertTrue(vm.recent.isEmpty())
        // a new start finds nothing to resume
        Store(app).jevKey = "" // the restarted ViewModel must not call out
        val again = vm()
        assertFalse(again.busy)
        assertNull(again.error)
        assertTrue(again.board.cards.isEmpty())
    }

    // RV-3: a tap made during an interrupted server turn is applied after the replay, so a tap on a card that turn created survives
    @Test
    fun aTapOnACardTheInterruptedTurnCreatedSurvivesTheReplay() {
        serverSettings()
        write("board.txt", board)
        Journal(files).writeInflight(Inflight("ssh", "a-1", "<input>x</input>", "여행 짐 정리해 줘", board, 1L, 0))
        Journal(files).writeQueue(emptyList(), listOf("upd k3 i1 done | 가"))
        val vm = vm()
        vm.runnerOverride = Fake(lines = "card k3 checklist t2 3x2 새 목록\nadd k3 i1 todo | 가\n")
        assertTrue(vm.busy)
        assertNull(vm.board.card("k3")) // the pre-turn board is back; the tap is not applied yet
        until("replay ends") { !vm.busy }
        assertEquals("done", vm.board.card("k3")!!.items.first().cells.first())
    }

    // RV-1c: killed after the save but before the record was deleted: the replay must not add the same exchange twice
    @Test
    fun aTurnThatWasAlreadySavedIsNotAddedToTheConversationAgain() {
        serverSettings()
        write("board.txt", board)
        write("recent.json", "[[\"안녕\",\"반가워요\"],[\"둘째 말\",\"마지막 한마디\"],[\"여행 짐 정리해 줘\",\"끝\"]]")
        Journal(files).writeInflight(Inflight("ssh", "a-1", "<input>x</input>", "여행 짐 정리해 줘", board, 1L, 2))
        val vm = vm()
        vm.runnerOverride = Fake(lines = "say 끝\n")
        until("replay ends") { !vm.busy }
        assertEquals(3, vm.recent.size)
        assertEquals("여행 짐 정리해 줘" to "끝", vm.recent.last())
        vm.flushWrites()
        assertFalse(exists("inflight.json"))
    }

    // RV-1b: a run that was finished and cleaned on the server is not run again: the board saved on disk is shown
    @Test
    fun aRunThatIsGoneShowsTheSavedBoardAndRunsNothing() {
        serverSettings()
        write("board.txt", board + "card k3 note t2 끝난 턴이 만든 카드\n") // saved after the turn
        Journal(files).writeInflight(Inflight("ssh", "a-1", "<input>x</input>", "여행 짐 정리해 줘", board, 1L, 0))
        val vm = vm()
        vm.runnerOverride = Fake(failWith = GONE)
        until("replay ends") { !vm.busy }
        assertNotNull(vm.board.card("k3")) // not the pre-turn board
        assertNull(vm.error)
        vm.flushWrites()
        assertFalse(exists("inflight.json"))
    }

    // RV-8: clearing the screen while a turn runs: its lines do not land on the cleared board
    @Test
    fun clearAllDuringATurnKeepsItsLinesOffTheNewBoard() {
        write("board.txt", board)
        val gate = java.util.concurrent.CountDownLatch(1)
        val fake = Fake(lines = "card k9 note t2 늦게 온 카드\n", gate = gate)
        val vm = vm()
        vm.runnerOverride = fake
        vm.send("뭔가 해 줘")
        until("the engine has the turn") { fake.started.count == 0L }
        vm.clearAll()
        gate.countDown()
        until("turn ends") { !vm.busy }
        assertTrue(vm.board.cards.isEmpty())
        vm.flushWrites()
        assertFalse(File(files, "board.txt").readText().contains("k9"))
        assertFalse(exists("inflight.json"))
    }

    // RV-7: the engine is now the phone's own: say that, and send the queued words by themselves
    @Test
    fun aServerRecordWhenTheEngineIsNowThePhoneSaysSoAndQueuedWordsGo() {
        jevServer.start()
        jevServer.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = okhttp3.mockwebserver.MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE)
        }
        com.fidenit.kite.engine.Jev.baseUrl = jevServer.url("/v1/systemone").toString()
        Store(app).jevKey = "test-key-not-real"
        write("board.txt", board)
        Journal(files).writeInflight(Inflight("ssh", "a-1", "<input>x</input>", "여행 짐 정리해 줘", board, 1L, 0))
        Journal(files).writeQueue(listOf("둘째 말"), emptyList())
        val vm = vm()
        val e = vm.error!!
        assertTrue(e, e.contains("폰에서 바로") && e.contains("여행 짐 정리해 줘"))
        until("the queued words' turn ends") { !vm.busy }
        assertTrue("the queued words were sent by themselves", vm.recent.any { it.first == "둘째 말" })
        vm.flushWrites()
        assertEquals(listOf("a-1"), Journal(files).readToClean()) // the server run is cleaned up on the next connect
    }

    /**
     * The engine step is over before the main thread goes on: the block runs on another thread and `dispatch` comes back only
     * when it is done. A phone or a loaded machine does this now and then (the main thread loses the CPU right after the
     * hand-over); here it happens every time, without waiting on a clock.
     */
    private val engineDoneFirst = object : kotlinx.coroutines.CoroutineDispatcher() {
        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
            Thread(block).apply { start() }.join()
        }
    }

    // K1: a turn never ends inside the call that started it, however fast its engine step is over
    @Test
    fun aTurnWhoseEngineIsDoneAtOnceIsStillRunningWhenSendReturns() {
        write("board.txt", board)
        val vm = vm()
        vm.turnIo = engineDoneFirst
        vm.runnerOverride = Fake(failWith = "엔진 오류")
        vm.send("첫째 말")
        assertTrue("the turn is running when send() returns", vm.busy)
        assertNull(vm.error)
        vm.flushWrites()
        assertEquals("첫째 말", Journal(files).readInflight()!!.shown) // a kill here finds the record
        until("turn ends") { !vm.busy }
        assertEquals("엔진 오류", vm.error)
        vm.flushWrites()
        assertFalse(exists("inflight.json"))
    }

    // K1: the same at start-up: the turn for the queued words has not ended when the ViewModel is handed out
    @Test
    fun queuedWordsStartedAtStartUpAreStillRunningWhenTheViewModelIsReady() {
        write("board.txt", board)
        Journal(files).writeQueue(listOf("둘째 말"), emptyList())
        val vm = vm() // no key: the engine step of this turn fails at once
        assertTrue(vm.busy)
        assertNull(vm.error)
        vm.flushWrites()
        assertTrue(Journal(files).readInflight()!!.turnText.contains("<input>둘째 말</input>"))
        until("turn ends") { !vm.busy }
        assertTrue(vm.error!!.contains("API"))
    }

    // (8)
    @Test
    fun backgroundSavesAndLeavesTalkOff() {
        write("board.txt", board)
        Store(app).elevenKey = "test-key-not-real"
        val vm = vm()
        vm.converse(true)
        assertTrue(vm.converse)
        vm.current = "t2" // what the owner looks at now
        vm.background()
        assertFalse(vm.converse)
        assertEquals("t2", Journal(files).readCurrent())
        assertTrue(File(files, "board.txt").readText().contains("topic t2 일"))
    }

    // (9)
    @Test
    fun filesFromVersion010LoadFine() {
        write("board.txt", board) // 0.1.0 wrote only these two files: no journal, no current topic
        write("recent.json", recent)
        for (n in listOf("inflight.json", "queue.json", "toclean.json", "current.txt")) assertFalse(exists(n))
        val vm = vm()
        same(board, Lang.serialize(vm.board))
        assertEquals("마지막 한마디", vm.board.say)
        assertEquals("t2", vm.current) // no stored topic: the last one
        assertNull(vm.error)
        assertFalse(vm.busy)
        assertFalse(exists("inflight.json"))
    }

    // R2(a): the record that carries the run id is on disk before the engine makes its first connection for that run
    @Test
    fun theRecordWithTheRunIdIsOnDiskBeforeTheEngineTouchesTheNetwork() {
        val server = java.net.ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress())
        val arrived = java.util.concurrent.CountDownLatch(1)
        val seen = java.util.concurrent.atomic.AtomicReference("")
        val accepting = kotlin.concurrent.thread {
            try {
                while (true) {
                    server.accept().use {
                        if (arrived.count > 0) {
                            seen.set(try { File(files, "inflight.json").readText() } catch (_: Exception) { "" })
                            arrived.countDown()
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        val vm = vm()
        // The writer is held on the record with the run id until the engine's connection has arrived (at most 500 ms):
        // an engine that does not wait for the disk gets to the server first.
        vm.journal.beforeApply = { name, text ->
            if (name == "inflight.json" && text != null && org.json.JSONObject(text).getString("id").isNotEmpty()) arrived.await(500, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
        vm.runnerOverride = com.fidenit.kite.engine.ClaudeCode(
            com.fidenit.kite.engine.Ssh(com.fidenit.kite.engine.Server("127.0.0.1", server.localPort, "u", "", "", "", "")) {},
            com.fidenit.kite.engine.EngineConfig("", "", "default", "", ""),
        )
        vm.send("뭔가 해 줘")
        until("the engine connects") { arrived.count == 0L }
        val onDisk = seen.get()
        assertTrue("the record on disk carries the run id when the first connection arrives: '$onDisk'", onDisk.isNotEmpty() && org.json.JSONObject(onDisk).getString("id").isNotEmpty())
        vm.stop()
        until("turn ends") { !vm.busy }
        server.close()
        accepting.join(2000)
    }

    // R2(b): at turn end the board and the conversation reach the disk before the record is deleted, and the delete before the server files are cleaned
    @Test
    fun atTurnEndTheBoardIsOnDiskBeforeTheRecordGoesAndTheRecordBeforeTheServerIsCleaned() {
        serverSettings()
        write("board.txt", board)
        Journal(files).writeInflight(Inflight("ssh", "a-1", "<input>x</input>", "여행 짐 정리해 줘", board, 1L, 0))
        val vm = vm()
        val log = java.util.concurrent.CopyOnWriteArrayList<String>()
        vm.journal.beforeApply = { name, text ->
            if (name == "board.txt") {
                Thread.sleep(200) // the writer is held on the board: what is queued behind it by then shows below
                if ("inflight.json" in vm.journal.queuedNames()) log.add("the delete of the record is queued while the board is not on disk")
            }
            log.add(if (text == null) "delete $name" else "write $name")
        }
        vm.cleanOverride = { log.add("clean $it") }
        vm.runnerOverride = Fake(lines = "card k3 note t2 새 카드\nsay 끝\n")
        until("replay ends") { !vm.busy }
        assertTrue(vm.flushWrites())
        val text = log.toString()
        assertFalse(text, log.contains("the delete of the record is queued while the board is not on disk"))
        val deleted = log.indexOf("delete inflight.json")
        assertTrue(text, log.indexOf("write board.txt") in 0 until deleted)
        assertTrue(text, log.indexOf("write recent.json") in 0 until deleted)
        assertTrue(text, deleted < log.indexOf("clean a-1"))
        assertFalse(exists("inflight.json"))
    }

    // R2(b): the board cannot be written: the record stays and the server files are not cleaned, so the next start replays the turn
    @Test
    fun aBoardThatCannotBeSavedKeepsTheRecordAndTheServerFiles() {
        serverSettings()
        write("board.txt", board)
        Journal(files).writeInflight(Inflight("ssh", "a-1", "<input>x</input>", "여행 짐 정리해 줘", board, 1L, 0))
        val vm = vm()
        val cleaned = java.util.concurrent.CopyOnWriteArrayList<String>()
        vm.cleanOverride = { cleaned.add(it) }
        File(files, "board.txt").delete()
        File(files, "board.txt/x").mkdirs() // a folder in the file's place: the board cannot be written
        vm.runnerOverride = Fake(lines = "card k3 note t2 새 카드\n")
        until("replay ends") { !vm.busy }
        assertFalse(vm.flushWrites())
        assertTrue(exists("inflight.json"))
        assertTrue(cleaned.toString(), cleaned.isEmpty())
    }
}
