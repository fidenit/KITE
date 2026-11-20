package com.fidenit.kite

import com.fidenit.kite.core.BoardLayout
import com.fidenit.kite.core.Card
import com.fidenit.kite.core.Fit
import com.fidenit.kite.core.GridSizes
import com.fidenit.kite.core.Item
import com.fidenit.kite.core.Kind
import com.fidenit.kite.core.PARTS
import com.fidenit.kite.ui.Dim
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** memory/specs/aperit-pro-grid-dock-v1.md §A1: 줄 나누기 (plain JUnit). */
class BoardLayoutTest {
    private val sizes = GridSizes(Dim.cardGap.value, Dim.cardPad.value, Dim.GRID_V_PHONE, Dim.GRID_V_MID, Dim.GRID_V_WIDE, Dim.GRID_MID_DP, Dim.GRID_WIDE_DP)

    // §A1: "가" 한 음절 폭 at font 1.0 / 1.3 / 2.0
    private val fonts = listOf(12.9f, 16.8f, 25.8f)

    private var n = 0
    private fun card(kind: Kind, items: Boolean = false, ext: String? = null): Card {
        val p = PARTS.first { it.kind == kind }
        val c = Card("c${n++}", kind, "t", "", p.w, p.h, ext)
        if (items) c.items.add(Item("i", listOf("a", "b")))
        return c
    }

    /** cards per row when the whole board is this one kind (screen width, font index) */
    private fun perRow(make: () -> Card, screen: Int, font: Int): Int {
        val slots = List(12) { BoardLayout.slot(make(), compact = false) }
        val rows = BoardLayout.pack(slots, (screen - 20).toFloat(), fonts[font], screen, sizes)
        return rows.first().size
    }

    private fun check(name: String, make: () -> Card, s360: List<Int>, s411: List<Int>, s600: List<Int>, l891: Int) {
        for ((screen, want) in listOf(360 to s360, 411 to s411, 600 to s600)) {
            for (f in 0..2) assertEquals("$name at $screen dp, font ${listOf("1.0", "1.3", "2.0")[f]}", want[f], perRow(make, screen, f))
        }
        assertEquals("$name at 891 dp, font 1.0", l891, perRow(make, 891, 0))
    }

    @Test
    fun specTable() {
        // The spec table was computed by hand; the formula wins (§A1). Cells where the formula gives another number:
        //   status 360 dp font 2.0: table 2, formula 1 (m = 24+16+5×25.8 = 169 dp; floor(348/177) = 1)
        //   status 411 dp font 1.3: table 2, formula 3 (m = 124 dp; floor(399/132) = 3; kCap 6/2 = 3)
        //   metric 411 dp font 1.3: table 2, formula 3 (m = 124.8 dp; floor(399/132.8) = 3)
        check("status", { card(Kind.Status) }, listOf(3, 2, 1), listOf(3, 3, 2), listOf(4, 4, 3), 6)
        check("metric", { card(Kind.Metric) }, listOf(3, 2, 1), listOf(3, 3, 2), listOf(4, 4, 3), 6)
        for (k in listOf(Kind.Note, Kind.Doc, Kind.Checklist, Kind.Decision)) {
            check(k.label, { card(k) }, listOf(2, 1, 1), listOf(2, 2, 1), listOf(3, 3, 2), 4)
        }
        check("progress", { card(Kind.Progress) }, listOf(2, 2, 1), listOf(2, 2, 1), listOf(3, 3, 2), 4)
        check("timeline", { card(Kind.Timeline) }, listOf(1, 1, 1), listOf(2, 1, 1), listOf(3, 2, 2), 4)
        check("record-fields", { card(Kind.Record) }, listOf(1, 1, 1), listOf(2, 1, 1), listOf(3, 2, 2), 4)
        for (k in listOf(Kind.Input, Kind.Chart, Kind.Image, Kind.Table)) {
            check(k.label, { card(k) }, listOf(1, 1, 1), listOf(1, 1, 1), listOf(2, 1, 1), 3)
        }
        for (k in listOf(Kind.Draw, Kind.Route, Kind.Web)) {
            check(k.label, { card(k) }, listOf(1, 1, 1), listOf(1, 1, 1), listOf(1, 1, 1), 2)
        }
        // record with items (w 3, wide): table row "draw, route, web" says 1 at 600 dp and 2 at 891 dp; formula 2 and 3
        // (600: m = 24+16×12.9 = 230.4 dp, floor(588/238.4) = 2, kCap 9/3 = 3; 891: floor(879/238.4) = 3, kCap 12/3 = 4). Same for an extension kind with w 3.
        check("record with items", { card(Kind.Record, items = true) }, listOf(1, 1, 1), listOf(1, 1, 1), listOf(2, 1, 1), 3)
        check("extension kind", { card(Kind.Record, ext = "x-plan") }, listOf(1, 1, 1), listOf(1, 1, 1), listOf(2, 1, 1), 3)
    }

    @Test
    fun compactAskIsSmallAndNeverAlone() {
        val input = card(Kind.Input)
        assertEquals(Fit.Wide, BoardLayout.fit(input, compact = false))
        assertEquals(Fit.Ask, BoardLayout.fit(input, compact = true))
        assertEquals(1, BoardLayout.kMax(BoardLayout.slot(input, false), 391f, fonts[0], 411, sizes))
        assertEquals(2, BoardLayout.kMax(BoardLayout.slot(input, true), 391f, fonts[0], 411, sizes))
    }

    @Test
    fun orderKeptAndRowsShareTheSmallestLimit() {
        val cards = listOf(card(Kind.Status), card(Kind.Status), card(Kind.Note), card(Kind.Note), card(Kind.Status), card(Kind.Table), card(Kind.Metric), card(Kind.Metric), card(Kind.Metric), card(Kind.Metric))
        val rows = BoardLayout.pack(cards.map { BoardLayout.slot(it, false) }, 391f, fonts[0], 411, sizes)
        assertEquals(cards.map { it.id }, rows.flatten().map { it.id })
        // status kMax 3, note 2 → [s s] stops before the note would make 3 > 2
        assertEquals(listOf(2, 2, 1, 1, 3, 1), rows.map { it.size })
    }

    @Test
    fun longerTextChangesNoRow() {
        val cards = listOf(card(Kind.Note), card(Kind.Status), card(Kind.Note), card(Kind.Checklist), card(Kind.Metric), card(Kind.Metric))
        fun rows() = BoardLayout.pack(cards.map { BoardLayout.slot(it, false) }, 391f, fonts[1], 411, sizes).map { r -> r.map { it.id } }
        val before = rows()
        cards[2].fields["text"] = "가".repeat(240)
        cards[0].title = "제목".repeat(20)
        cards[3].items.add(Item("x", listOf("todo", "할 일 ".repeat(10))))
        assertEquals(before, rows())
    }

    @Test
    fun widthClasses600And840() {
        val wide = 2000f // reading width never limits here, only the class cap V / w
        fun k(kind: Kind, screen: Int) = BoardLayout.kMax(BoardLayout.slot(card(kind), false), wide, fonts[0], screen, sizes)
        assertEquals(3, k(Kind.Status, 599))
        assertEquals(4, k(Kind.Status, 600))
        assertEquals(4, k(Kind.Status, 839))
        assertEquals(6, k(Kind.Status, 840))
        assertEquals(1, k(Kind.Table, 599)) // input, table, draw alone below 600 dp
        assertEquals(2, k(Kind.Table, 600))
        assertEquals(3, k(Kind.Table, 840))
        assertEquals(1, k(Kind.Draw, 839))
        assertEquals(2, k(Kind.Draw, 840))
        // PATET width is clamped 1..8
        val big = card(Kind.Note).apply { w = 20 }
        assertEquals(1, BoardLayout.kMax(BoardLayout.slot(big, false), wide, fonts[0], 1280, sizes))
    }

    @Test
    fun cardWidths() {
        assertEquals(391f, BoardLayout.cardWidth(1, 391f, sizes), 0.01f)
        assertEquals(191.5f, BoardLayout.cardWidth(2, 391f, sizes), 0.01f)
        assertEquals(125f, BoardLayout.cardWidth(3, 391f, sizes), 0.01f)
    }

    @Test
    fun sizesAndTextFloor() {
        assertEquals(8f, sizes.gap)
        assertEquals(12f, sizes.pad)
        assertEquals(listOf(6, 9, 12, 600, 840), listOf(sizes.vPhone, sizes.vMid, sizes.vWide, sizes.midDp, sizes.wideDp))
        assertEquals(1200, Dim.FLASH_MS)
        val floor = Dim.TEXT_FLOOR.value
        assertEquals(12f, floor)
        // canvas labels have no semantics, so the token is checked here
        for (t in listOf(Dim.drawS, Dim.drawM, Dim.drawL, Dim.small, Dim.status, Dim.metricUnit, Dim.metricDelta)) assertTrue(t.value >= floor)
        assertEquals(14f, Dim.body.value)
        assertEquals(15f, Dim.title.value)
        assertEquals(listOf(30f, 26f, 22f, 20f), Dim.metricSteps.map { it.value })
    }
}
