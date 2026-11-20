// 보드를 여러 칸의 줄로 나눈다 (memory/specs/aperit-pro-grid-dock-v1.md §A1). 안드로이드·Compose 없이 숫자만 다룬다.
// 카드는 보드 순서대로 왼쪽부터 줄을 채우고, 한 줄의 카드는 폭을 똑같이 나눈다.
// 글자를 줄여서 칸을 늘리지 않는다: 줄의 모든 카드가 읽을 만한 한 줄(c 음절)을 담을 때만 옆에 놓인다.
// 폭은 종류·PATET 폭 w·항목 유무·보드 폭·글자 폭·화면 폭으로만 정해진다. 글 길이는 보지 않으므로 글이 바뀌면 그 줄 높이만 바뀐다.
package com.fidenit.kite.core

import kotlin.math.floor

/** 이 카드들로 스크롤하고 한 번 반짝인다. 새 seq = 새 요청 (§A3). */
data class BoardPoint(val ids: List<String>, val seq: Long)

/** 카드 종류별 최소 폭: 2 × 안쪽 여백 + fixed dp + c × 한 음절 폭 (§A1 표). */
enum class Fit(val fixedDp: Float, val c: Int) {
    Status(16f, 5),
    Metric(0f, 6),
    Text(0f, 9), // note, doc
    Checklist(28f, 7),
    Progress(16f, 7),
    Keyed(88f, 5), // timeline, 항목 없는 record
    Ask(20f, 8), // decision, 답을 기다리는 작은 카드 (§A4)
    Wide(0f, 16), // table, draw, input, chart, image, route, web, 항목 있는 record, 확장 종류
}

/** 줄 나누기에 쓰는 카드 하나. `alone` = 600 dp 아래에서 늘 혼자 한 줄 (input, table, draw). */
class Slot(val id: String, val fit: Fit, val w: Int, val alone: Boolean)

/** 크기 값. 화면에서는 ui/Dim.kt 의 값으로 만든다 (모든 크기는 Dim 한 곳). */
class GridSizes(
    val gap: Float,
    val pad: Float,
    val vPhone: Int,
    val vMid: Int,
    val vWide: Int,
    val midDp: Int,
    val wideDp: Int,
)

object BoardLayout {
    fun fit(card: Card, compact: Boolean): Fit {
        if (compact) return Fit.Ask
        if (card.ext != null) return Fit.Wide
        return when (card.kind) {
            Kind.Status -> Fit.Status
            Kind.Metric -> Fit.Metric
            Kind.Note, Kind.Doc -> Fit.Text
            Kind.Checklist -> Fit.Checklist
            Kind.Progress -> Fit.Progress
            Kind.Timeline -> Fit.Keyed
            Kind.Record -> if (card.items.isEmpty()) Fit.Keyed else Fit.Wide
            Kind.Decision -> Fit.Ask
            Kind.Table, Kind.Draw, Kind.Input, Kind.Chart, Kind.Image, Kind.Route, Kind.Web -> Fit.Wide
        }
    }

    /** `compact` = 보드에 작은 카드(AskCompact)로 그리는, 답을 기다리는 카드. */
    fun slot(card: Card, compact: Boolean): Slot =
        Slot(card.id, fit(card, compact), card.w, !compact && (card.kind == Kind.Input || card.kind == Kind.Table || card.kind == Kind.Draw))

    /** 최소 카드 폭 m (dp). `s` = 본문 글자 "가" 한 음절의 폭 (dp). */
    fun minWidth(fit: Fit, s: Float, sizes: GridSizes): Float = 2 * sizes.pad + fit.fixedDp + fit.c * s

    /** 한 줄에 놓을 수 있는 이 카드 수의 최대값 (kMax). `width` = 보드 안쪽 폭 W (dp). */
    fun kMax(slot: Slot, width: Float, s: Float, screenWidthDp: Int, sizes: GridSizes): Int {
        if (slot.alone && screenWidthDp < sizes.midDp) return 1
        val m = minWidth(slot.fit, s, sizes)
        val kRead = maxOf(1, floor((width + sizes.gap) / (m + sizes.gap)).toInt())
        val v = if (screenWidthDp < sizes.midDp) sizes.vPhone else if (screenWidthDp < sizes.wideDp) sizes.vMid else sizes.vWide
        val kCap = maxOf(1, v / slot.w.coerceIn(1, MAX_W))
        return minOf(kRead, kCap)
    }

    /** 보드 순서대로 줄을 채운다. 줄의 모든 카드의 kMax 안에서만 옆에 붙인다. */
    fun pack(slots: List<Slot>, width: Float, s: Float, screenWidthDp: Int, sizes: GridSizes): List<List<Slot>> {
        val rows = mutableListOf<List<Slot>>()
        var row = mutableListOf<Slot>()
        var cap = Int.MAX_VALUE
        for (slot in slots) {
            val k = kMax(slot, width, s, screenWidthDp, sizes)
            if (row.isNotEmpty() && row.size + 1 <= minOf(cap, k)) {
                row.add(slot)
                cap = minOf(cap, k)
            } else {
                if (row.isNotEmpty()) rows.add(row)
                row = mutableListOf(slot)
                cap = k
            }
        }
        if (row.isNotEmpty()) rows.add(row)
        return rows
    }

    /** k 개가 나란한 줄에서 카드 하나의 폭 (dp). */
    fun cardWidth(k: Int, width: Float, sizes: GridSizes): Float = (width - (k - 1) * sizes.gap) / k
}
