package com.fidenit.kite

import com.fidenit.kite.core.Author
import com.fidenit.kite.core.Board
import com.fidenit.kite.core.Lang
import com.fidenit.kite.core.Todo
import com.fidenit.kite.core.TodoPicker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** memory/specs/aperit-pro-grid-dock-v1.md §C1 / §T: which card the "내 차례" dock shows (plain JUnit). */
class TodoTest {
    private val b = Board()
    private val p = TodoPicker()
    private var busy = false
    private var editing: Set<String> = emptySet()

    private fun ai(vararg lines: String) = lines.forEach { l -> b.apply(Lang.parse(l)!!, Author.Ai) }
    private fun user(vararg lines: String) = lines.forEach { l -> b.apply(Lang.parse(l)!!, Author.User) }

    private fun pick(): String? = p.update(Todo.asks(b), b.cards, busy, b.focus, { it in editing })

    private fun decision(id: String, topic: String) = ai("card $id decision $topic 질문 $id", "add $id o1 예", "add $id o2 아니오")
    private fun form(id: String, topic: String) = ai("card $id input $topic 적기 $id", "add $id a 이름 | 예: 김철수")

    init {
        ai("topic a 가", "topic b 나", "topic c 다")
    }

    @Test
    fun waitingAsksAreUnansweredDecisionsAndInputsOnlyInBoardOrder() {
        decision("d1", "a")
        ai("card k1 checklist a 할 일", "add k1 x todo | 우유")
        form("f1", "b")
        ai("card n1 note a 메모")
        assertEquals(listOf("d1", "f1"), Todo.asks(b).map { it.id })
        user("set d1 chosen o1")
        assertEquals(listOf("f1"), Todo.asks(b).map { it.id })
        user("set f1 sent yes")
        assertTrue(Todo.asks(b).isEmpty())
    }

    @Test
    fun startUpDocksTheOldest() {
        decision("d1", "b")
        form("f1", "a")
        decision("d2", "a")
        assertEquals("d1", pick())
        assertEquals("next-oldest", p.reason)
        assertFalse(p.applying)
    }

    @Test
    fun nothingWaitingMeansNoDock() {
        ai("card n1 note a 메모")
        assertNull(pick())
        busy = true
        assertNull(pick())
    }

    @Test
    fun aNewAskNeverReplacesAWaitingDockedOne() {
        decision("d1", "a")
        assertEquals("d1", pick())
        busy = true
        decision("d2", "a")
        assertEquals("d1", pick())
        busy = false
        decision("d3", "b")
        assertEquals("d1", pick())
        assertEquals("keep", p.reason)
        assertEquals(3, Todo.asks(b).size) // the queue grows, the count shows it
    }

    @Test
    fun aiFocusReplacesTheDockedAskUnlessItIsBeingEdited() {
        form("f1", "a")
        decision("d1", "b")
        assertEquals("f1", pick())
        ai("focus d1")
        assertEquals("d1", pick())
        assertEquals("focus", p.reason)
        assertEquals("b", p.lastTopic)
        // focus moves back while the user types in d1? d1 is a decision: no text; type in f1 after docking it by hand
        p.dockManually("f1", Todo.asks(b))
        editing = setOf("f1")
        decision("d2", "c")
        ai("focus d2")
        assertEquals("the user is typing in f1: the AI focus does not take the dock", "f1", pick())
        // the same focus is not used again later, when typing stops
        editing = emptySet()
        assertEquals("f1", pick())
    }

    @Test
    fun aFocusOnACardThatIsNotAWaitingAskChangesNothing() {
        decision("d1", "a")
        ai("card n1 note a 메모")
        assertEquals("d1", pick())
        ai("focus n1")
        assertEquals("d1", pick())
    }

    @Test
    fun answeredDocksTheNextAtOnceWhileTheAiIsStillBusy() {
        decision("d1", "a")
        decision("d2", "b")
        assertEquals("d1", pick())
        user("set d1 chosen o1")
        busy = true // the answer started a turn
        assertEquals("d2", pick())
        assertFalse("another ask waits: no applying state", p.applying)
    }

    @Test
    fun applyingOnlyWhenNothingElseWaits() {
        decision("d1", "a")
        assertEquals("d1", pick())
        user("set d1 chosen o1")
        busy = true
        assertEquals("d1", pick())
        assertTrue(p.applying)
        assertEquals("applying", p.reason)
        // a new ask arrives during the turn: it docks at once, the applying state ends
        decision("d2", "b")
        assertEquals("d2", pick())
        assertFalse(p.applying)
    }

    @Test
    fun applyingEndsWhenTheAiDeletesTheCardOrTheTurnEnds() {
        decision("d1", "a")
        decision("d2", "a")
        assertEquals("d1", pick())
        user("set d1 chosen o1")
        busy = true
        assertEquals("d1 answered: d2 docks at once", "d2", pick())
        user("set d2 chosen o1")
        // d2 answered too: nothing waits, d2 stays as applying while busy
        assertEquals("d2", pick())
        assertTrue(p.applying)
        ai("del d2")
        assertNull("the AI deleted it", pick())
        decision("d3", "a")
        pick()
        user("set d3 chosen o2")
        assertEquals("d3", pick())
        busy = false
        assertNull("the turn ended and the AI kept the answered card: the dock closes", pick())
        assertFalse(p.applying)
    }

    @Test
    fun sameTopicFirstThenFirstNewThenOldest() {
        decision("x1", "b")
        decision("a1", "a")
        decision("a2", "a")
        p.dockManually("a1", Todo.asks(b))
        pick()
        user("set a1 chosen o1")
        assertEquals("same topic as the one just answered", "a2", pick())
        assertEquals("next-topic", p.reason)
        // a2 answered while a new ask of topic c arrives in the same change: the new one beats the oldest (x1)
        user("set a2 chosen o1")
        decision("c1", "c")
        assertEquals("c1", pick())
        assertEquals("next-new", p.reason)
        user("set c1 chosen o1")
        assertEquals("no same topic, nothing new: the oldest", "x1", pick())
        assertEquals("next-oldest", p.reason)
    }

    @Test
    fun nextCyclesInBoardOrderAndWraps() {
        decision("d1", "a")
        form("f1", "b")
        decision("d2", "c")
        assertEquals("d1", pick())
        assertEquals("f1", p.next(Todo.asks(b)))
        assertEquals("b", p.lastTopic)
        assertEquals("d2", p.next(Todo.asks(b)))
        assertEquals("wraps", "d1", p.next(Todo.asks(b)))
        assertEquals("d1", pick()) // keeps what next chose
    }

    @Test
    fun nextNeedsTwoWaitingAsks() {
        decision("d1", "a")
        assertEquals("d1", pick())
        assertEquals("d1", p.next(Todo.asks(b)))
        assertEquals("d1", p.docked)
    }

    @Test
    fun theEditingGuardKeepsTheTypedFormDocked() {
        form("f1", "a")
        decision("d1", "b")
        assertEquals("f1", pick())
        editing = setOf("f1")
        decision("d2", "c")
        ai("focus d2")
        assertEquals("f1", pick())
        // editing does not stop the user's own 다음 ›
        assertEquals("d1", p.next(Todo.asks(b)))
    }

    @Test
    fun anArchivedTopicIsIgnored() {
        decision("d1", "a")
        decision("d2", "b")
        assertEquals("d1", pick())
        user("archive a")
        assertEquals(listOf("d2"), Todo.asks(b).map { it.id })
        assertEquals("the docked card went to the shelf: the next one docks", "d2", pick())
        user("archive b")
        assertNull(pick())
    }

    @Test
    fun dockManuallyTakesOnlyWaitingAsks() {
        decision("d1", "a")
        decision("d2", "b")
        ai("card n1 note a 메모")
        assertEquals("d1", pick())
        assertEquals("d2", p.dockManually("d2", Todo.asks(b)))
        assertEquals("tap", p.reason)
        assertEquals("d2", p.dockManually("n1", Todo.asks(b)))
        assertEquals("d2", pick())
    }

    @Test
    fun anAskAppearingWhileNothingIsDockedDocksAtOnceEvenDuringATurn() {
        ai("card n1 note a 메모")
        assertNull(pick())
        busy = true
        form("f2", "a")
        assertEquals("f2", pick())
    }
}
