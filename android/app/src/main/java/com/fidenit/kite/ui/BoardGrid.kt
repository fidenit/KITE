// 보드를 여러 칸의 줄로 그린다 (memory/specs/aperit-pro-grid-dock-v1.md §A2-A4). 줄 나누기는 core/BoardLayout.kt.
// 한 줄의 카드는 폭을 똑같이 나누고 높이가 같다. 글자는 줄이지 않는다.
// 줄 안에서는 카드마다 실제 높이를 재고 가장 높은 값으로 다시 그린다 (EqualRow). 줄은 intrinsic 측정을 쓰지 않는다.
package com.fidenit.kite.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fidenit.kite.KiteViewModel
import com.fidenit.kite.core.BoardLayout
import com.fidenit.kite.core.BoardPoint
import com.fidenit.kite.core.Card
import com.fidenit.kite.core.GridSizes
import java.lang.ref.WeakReference
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** 줄 안에서 이 카드가 받는 폭 (dp). 카드는 이것으로 글자 크기를 그리기 전에 정한다 (지표 숫자). 줄 밖(내 차례 칸 등)은 Unspecified. */
val LocalCardWidth = compositionLocalOf { Dp.Unspecified }

/** 가리킨 카드가 반짝이는 동안 그 카드의 semantics 에 true 로 실린다 (화면 시험이 본다). */
val FlashKey = SemanticsPropertyKey<Boolean>("AperitFlash")

/** 답을 기다리는 카드인가 (내 차례 칸에 붙지 않았으면 보드에 작은 카드로 그린다). */
private fun waiting(card: Card) = card.kind.asks && card.field("chosen").isEmpty() && card.field("sent").isEmpty()

private class Cell(val card: Card, val compact: Boolean)

/** 이미 처리한 가리키기 요청. 보드를 떠났다 돌아와도 같은 요청으로 다시 스크롤·반짝이지 않는다 (객체로 구분, 새 화면 모델의 요청은 새 객체). */
private var handledPoint: WeakReference<BoardPoint>? = null

/**
 * 보드 목록. `cards` 는 이미 보드 순서이고 내 차례 칸의 카드는 빠져 있다. `state` 는 BoardScreen 이 기억한다.
 * 줄마다 `board-row`, 목록은 `board-list`.
 */
@Composable
fun BoardGrid(vm: KiteViewModel, cards: List<Card>, rev: Int, state: LazyListState, modifier: Modifier = Modifier) {
    val sizes = remember { GridSizes(Dim.cardGap.value, Dim.cardPad.value, Dim.GRID_V_PHONE, Dim.GRID_V_MID, Dim.GRID_V_WIDE, Dim.GRID_MID_DP, Dim.GRID_WIDE_DP) }
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val bodyStyle = LocalTextStyle.current.merge(TextStyle(fontSize = Dim.body, lineHeight = Dim.bodyLine))
    // 본문 글자 "가" 한 음절의 폭 (dp): 글꼴 크기 설정이 바뀌면 다시 잰다
    val syllable = remember(measurer, bodyStyle, density) { with(density) { measurer.measure("가", bodyStyle).size.width.toDp().value } }
    val screenDp = LocalConfiguration.current.screenWidthDp
    BoxWithConstraints(modifier) {
        val inner = (maxWidth - Dim.listPadH * 2).value
        val cells = cards.map { Cell(it, waiting(it)) }
        val byId = cells.associateBy { it.card.id }
        val rows = BoardLayout.pack(cells.map { BoardLayout.slot(it.card, it.compact) }, inner, syllable, screenDp, sizes).map { r -> r.map { byId.getValue(it.id) } }
        val latestRows by rememberUpdatedState(rows)

        // 가리키기 (§A3): 처음 줄이 다 보이지 않으면 그 줄로 스크롤하고, 화면에 있는 가리킨 카드를 1.2 초 동안 반짝인다
        val flash = remember { Animatable(0f) }
        var flashing by remember { mutableStateOf<Set<String>>(emptySet()) }
        val point = vm.boardPoint
        LaunchedEffect(point) {
            val p = point ?: return@LaunchedEffect
            if (handledPoint?.get() === p) return@LaunchedEffect
            handledPoint = WeakReference(p)
            if (p.ids.isEmpty()) return@LaunchedEffect
            // 주제가 같이 바뀌었으면 새 주제의 줄이 놓일 때까지 기다린다 (옛 주제의 배치로 스크롤·반짝임을 정하지 않게)
            withTimeoutOrNull(Dim.POINT_WAIT_MS) {
                snapshotFlow {
                    val rowsNow = latestRows
                    val keys = rowsNow.map { it.first().card.id }.toSet()
                    val shown = state.layoutInfo.visibleItemsInfo
                    rowsNow.any { r -> r.any { it.card.id in p.ids } } && shown.isNotEmpty() && shown.all { it.key in keys }
                }.first { it }
            } ?: return@LaunchedEffect
            val at = latestRows.indexOfFirst { r -> r.any { it.card.id in p.ids } }
            if (at < 0) return@LaunchedEffect
            val info = state.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull { it.index == at }
            val whole = item != null && item.offset >= info.viewportStartOffset && item.offset + item.size <= info.viewportEndOffset
            if (!whole) state.animateScrollToItem(at)
            val rowByKey = latestRows.associateBy { it.first().card.id }
            val onScreen = state.layoutInfo.visibleItemsInfo.flatMap { rowByKey[it.key].orEmpty() }.map { it.card.id }
            flashing = onScreen.filter { it in p.ids }.toSet()
            try {
                flash.snapTo(1f)
                flash.animateTo(0f, tween(Dim.FLASH_MS, easing = LinearEasing))
            } finally {
                flashing = emptySet()
            }
        }

        LazyColumn(
            Modifier.fillMaxSize().testTag("board-list"),
            state = state,
            contentPadding = PaddingValues(horizontal = Dim.listPadH, vertical = Dim.listPadV),
            verticalArrangement = Arrangement.spacedBy(Dim.cardGap),
        ) {
            items(rows, key = { it.first().card.id }) { row ->
                val draw: @Composable (Cell, Modifier) -> Unit = { cell, m -> if (cell.compact) AskCompact(vm, cell.card, m) else CardView(vm, cell.card, rev, m) }
                if (row.size == 1) {
                    // 혼자면 잴 필요가 없다
                    val cell = row[0]
                    Row(Modifier.fillMaxWidth().testTag("board-row")) {
                        CompositionLocalProvider(LocalCardWidth provides BoardLayout.cardWidth(1, inner, sizes).dp) {
                            draw(cell, Modifier.weight(1f).flashMark(cell.card.id in flashing) { flash.value })
                        }
                    }
                } else {
                    EqualRow(row, Modifier.fillMaxWidth().testTag("board-row")) { cell, measuring ->
                        draw(cell, if (measuring) Modifier else Modifier.flashMark(cell.card.id in flashing) { flash.value })
                    }
                }
            }
        }
    }
}

/**
 * 여러 장이 나란한 줄: 카드마다 제 폭에서 실제 높이를 먼저 재고, 가장 높은 값으로 모든 카드를 그린다 (같은 높이, 사이 cardGap).
 * intrinsic 측정(IntrinsicSize.Min)은 글꼴 2.0 에서 실제보다 한 줄쯤 낮게 나와 글이 잘렸다 (a06). 재는 쪽은 그리지 않고 semantics 도 없다.
 */
@Composable
private fun EqualRow(row: List<Cell>, modifier: Modifier, card: @Composable (Cell, measuring: Boolean) -> Unit) {
    SubcomposeLayout(modifier) { constraints ->
        val gap = Dim.cardGap.roundToPx()
        val n = row.size
        val free = (constraints.maxWidth - gap * (n - 1)).coerceAtLeast(0)
        val widths = IntArray(n) { i -> free / n + if (i < free % n) 1 else 0 }
        val probe = subcompose("measure") {
            row.forEachIndexed { i, cell ->
                Box(Modifier.clearAndSetSemantics {}) {
                    CompositionLocalProvider(LocalCardWidth provides widths[i].toDp()) { card(cell, true) }
                }
            }
        }
        val h = probe.mapIndexed { i, m -> m.measure(Constraints(minWidth = widths[i], maxWidth = widths[i])).height }.maxOrNull() ?: 0
        val real = subcompose("draw") {
            row.forEachIndexed { i, cell ->
                CompositionLocalProvider(LocalCardWidth provides widths[i].toDp()) { card(cell, false) }
            }
        }.mapIndexed { i, m -> m.measure(Constraints.fixed(widths[i], h)) }
        layout(constraints.maxWidth, h) {
            var x = 0
            real.forEachIndexed { i, p ->
                p.place(x, 0)
                x += widths[i] + gap
            }
        }
    }
}

/** 반짝이는 동안: 강조색 테두리(투명도는 그릴 때 읽으니 다시 구성하지 않는다) + FlashKey. 크기는 바꾸지 않는다. */
private fun Modifier.flashMark(on: Boolean, alpha: () -> Float): Modifier = if (!on) this else this
    .semantics { this[FlashKey] = true }
    .drawWithContent {
        drawContent()
        val w = Dim.flashStroke.toPx()
        drawRoundRect(
            C.accent.copy(alpha = alpha().coerceIn(0f, 1f)),
            topLeft = Offset(w / 2, w / 2),
            size = Size(size.width - w, size.height - w),
            cornerRadius = CornerRadius(Dim.cardCorner.toPx() - w / 2),
            style = Stroke(w),
        )
    }

/** 질문의 첫 줄: 결정은 질문 글 (없으면 첫 선택지), 입력은 첫 칸 이름 (없으면 질문 글). */
private fun askLine(vm: KiteViewModel, card: Card): String {
    val text = card.field("text").replace("\\n", "\n").lineSequence().firstOrNull().orEmpty().trim()
    val first = card.items.firstOrNull()?.cells?.getOrNull(0).orEmpty()
    val line = if (card.kind == com.fidenit.kite.core.Kind.Input) first.ifEmpty { text } else text.ifEmpty { first }
    return vm.board.show(line)
}

/** 답을 기다리는 카드의 작은 모양 (§A4): 제목, 질문 첫 줄, 답하기. 선택지·칸은 내 차례 칸에만 있다. */
@Composable
fun AskCompact(vm: KiteViewModel, card: Card, modifier: Modifier = Modifier) {
    val english = vm.store.lang == "en"
    Column(Modifier.testTag("board-ask").then(modifier).fillMaxWidth().clip(RoundedCornerShape(Dim.cardCorner)).background(C.card).padding(Dim.cardPad)) {
        if (card.title.isNotEmpty()) {
            CardTitle(vm.board.show(card.title))
            Spacer(Modifier.height(Dim.titleGap))
        }
        val line = askLine(vm, card)
        if (line.isNotEmpty()) {
            Body(line, C.dim)
            Spacer(Modifier.height(Dim.inGap))
        }
        Pill(if (english) "Answer" else "답하기", modifier = Modifier.testTag("board-ask-answer")) { vm.dockAsk(card.id) }
    }
}
