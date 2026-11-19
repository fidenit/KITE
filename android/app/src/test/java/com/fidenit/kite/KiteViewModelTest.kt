package com.fidenit.kite

import android.app.Application
import com.fidenit.kite.core.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
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
    }

    private fun vm() = KiteViewModel(app)

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
        val e = vm().error!!
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
        // no waiting: the tap is already in the files
        assertTrue(File(files, "board.txt").readText().contains("add k1 i1 done | 여권"))
        assertEquals(listOf("upd k1 i1 done | 여권"), Journal(files).readQueue().events)
        val again = vm() // a new ViewModel on the same files dir
        assertEquals("done", again.board.card("k1")!!.items.first().cells.first())
        assertEquals(listOf("upd k1 i1 done | 여권"), Journal(files).readQueue().events) // still to be told to the model
    }

    @Test
    fun queuedInputsAndEventsAreRestoredAndStartedAfterARestart() {
        write("board.txt", board)
        val vm = vm()
        vm.send("첫째 말") // the turn starts (the engine answers on the IO thread; its result is not delivered in this test)
        assertTrue(vm.busy)
        assertNotNull(Journal(files).readInflight())
        val card = vm.board.card("k1")!!
        vm.toggle(card, card.items.first())
        vm.send("둘째 말")
        vm.send("셋째 말")
        val q = Journal(files).readQueue()
        assertEquals(listOf("둘째 말", "셋째 말"), q.pending)
        assertEquals(listOf("upd k1 i1 done | 여권"), q.events)
        // the app is killed here; the turn record went with it (a finished turn deletes it before the queue is started)
        Journal(files).clearInflight()
        val again = vm()
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
        val vm = vm()
        vm.send("첫째 말")
        vm.send("둘째 말")
        assertTrue(exists("inflight.json") && exists("queue.json"))
        vm.clearAll()
        assertFalse(exists("inflight.json"))
        assertFalse(exists("queue.json"))
        assertTrue(vm.board.cards.isEmpty())
        assertTrue(vm.recent.isEmpty())
        // a new start finds nothing to resume
        val again = vm()
        assertFalse(again.busy)
        assertNull(again.error)
        assertTrue(again.board.cards.isEmpty())
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
}
