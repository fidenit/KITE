// 부품별 그리기. 데스크톱 src/ui.rs 의 draw_body 에 해당한다. 폰에서는 카드가 줄의 폭을 나눠 쓴다 (ui/BoardGrid.kt):
// 좁은 카드에서도 글자는 줄이지 않고 줄을 바꾼다 (제목·본문은 말줄임 없음).
package com.fidenit.kite.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import com.fidenit.kite.KiteViewModel
import com.fidenit.kite.core.Card
import com.fidenit.kite.core.Kind
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

private fun stateColor(s: String): Color = when (s) {
    "ok", "done" -> C.green
    "run" -> C.accent
    "wait" -> C.amber
    "fail" -> C.red
    else -> C.faint
}

private fun named(c: String): Color = when (c.trim()) {
    "accent" -> C.accent
    "green" -> C.green
    "red" -> C.red
    "amber" -> C.amber
    "dim" -> C.dim
    "faint" -> C.faint
    "white" -> Color.White
    else -> C.text
}

@Composable
private fun Dot(color: Color) = Box(Modifier.size(Dim.dot).clip(CircleShape).background(color))

/** 본문: 좁은 카드에서도 같은 크기. 줄바꿈은 기본값 (Heading/Paragraph 는 font 2.0 에서 줄 높이를 재는 값과 그린 높이가 한 줄 달라 글이 잘렸다, a06). */
@Composable
internal fun Body(s: String, color: Color = C.text, modifier: Modifier = Modifier) = Text(s, color = color, fontSize = Dim.body, lineHeight = Dim.bodyLine, modifier = modifier)

/** 카드 제목: 말줄임 없이 줄을 바꾼다 (기본 줄바꿈, 위 Body 와 같은 까닭). */
@Composable
internal fun CardTitle(s: String) = Text(s, color = C.text, fontSize = Dim.title, fontWeight = FontWeight.Bold)

/** 카드 하나. `rev` 는 보드가 바뀌었을 때 다시 그리게 하려고 받는다. `modifier` 는 카드 바탕(`card`)에 붙는다 (줄 안의 폭·높이, 반짝임). */
@Composable
fun CardView(vm: KiteViewModel, card: Card, @Suppress("UNUSED_PARAMETER") rev: Int, modifier: Modifier = Modifier) {
    val b = vm.board
    // In a field named `text`, the two characters \n are shown as a line break (protocol 0.3).
    fun f(name: String) = b.show(card.field(name)).let { if (name == "text") it.replace("\\n", "\n") else it }
    Column(Modifier.testTag("card").then(modifier).fillMaxWidth().clip(RoundedCornerShape(Dim.cardCorner)).background(C.card).padding(Dim.cardPad)) {
        if (card.title.isNotEmpty()) {
            CardTitle(b.show(card.title))
            Spacer(Modifier.height(Dim.titleGap))
        }
        when (card.kind) {
            Kind.Note -> Body(f("text"))
            Kind.Status -> Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(stateColor(card.field("state")))
                Spacer(Modifier.width(Dim.dotGap))
                Body(f("text"), modifier = Modifier.weight(1f))
            }
            Kind.Metric -> {
                MetricLine(f("value"), f("unit"), f("delta"))
                if (f("text").isNotEmpty()) Body(f("text"), C.dim)
            }
            Kind.Chart -> Chart(vm, card)
            Kind.Table -> {
                val cols = com.fidenit.kite.core.Lang.splitCells(f("cols")).filter { it.isNotEmpty() }
                if (cols.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().padding(bottom = Dim.headPadB)) { cols.forEach { Text(it, color = C.dim, fontSize = Dim.small, modifier = Modifier.weight(1f).padding(end = Dim.cellGap)) } }
                }
                for (item in card.items) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(C.line))
                    Row(Modifier.fillMaxWidth().padding(vertical = Dim.rowV)) {
                        item.cells.forEachIndexed { i, cell ->
                            Text(b.show(cell), color = C.text, fontSize = Dim.body, lineHeight = Dim.bodyLine, fontWeight = if (i == 0) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f).padding(end = Dim.cellGap))
                        }
                    }
                }
            }
            Kind.Progress -> {
                val pct = card.field("pct").toIntOrNull()
                if (pct != null) {
                    Box(Modifier.fillMaxWidth().height(Dim.progressH).clip(RoundedCornerShape(Dim.progressCorner)).background(C.line)) {
                        Box(Modifier.fillMaxWidth(pct / 100f).fillMaxHeight().background(C.accent))
                    }
                    Spacer(Modifier.height(Dim.inGap))
                }
                if (f("text").isNotEmpty()) Body(f("text"), C.dim)
                for (item in card.items) {
                    Row(Modifier.padding(top = Dim.inGap), verticalAlignment = Alignment.CenterVertically) {
                        Dot(stateColor(item.cells[0]))
                        Spacer(Modifier.width(Dim.dotGap))
                        Body(b.show(item.cells.getOrElse(1) { "" }), if (item.cells[0] == "todo") C.dim else C.text, Modifier.weight(1f))
                    }
                }
            }
            Kind.Checklist -> for (item in card.items) {
                val done = item.cells[0] == "done"
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(Dim.checkRowCorner)).clickable { vm.toggle(card, item) }.padding(vertical = Dim.checkRowV), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(Dim.checkBox).clip(RoundedCornerShape(Dim.checkCorner)).background(if (done) C.green else Color.Transparent).border(1.5.dp, if (done) C.green else C.faint, RoundedCornerShape(Dim.checkCorner)))
                    Spacer(Modifier.width(Dim.checkGap))
                    Body(b.show(item.cells.getOrElse(1) { "" }), if (done) C.dim else C.text, Modifier.weight(1f))
                }
            }
            Kind.Decision -> {
                if (f("text").isNotEmpty()) {
                    Body(f("text"))
                    Spacer(Modifier.height(Dim.inGap))
                }
                val chosen = card.field("chosen").split(',')
                val multi = card.field("multi") == "yes"
                val danger = card.field("tone") == "danger"
                val answered = card.field("chosen").isNotEmpty()
                // With several answers a tap only marks the option; the answer goes out with "Done". The marks live in the view model (dock ↔ board).
                val marks = vm.marksOf(card.id)
                Column(verticalArrangement = Arrangement.spacedBy(Dim.optGap)) {
                    for (item in card.items) {
                        val on = item.id in chosen || (multi && !answered && marks.containsKey(item.id))
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(Dim.optCorner)).background(if (on) C.accent.copy(alpha = 0.18f) else Color.Transparent)
                                .border(1.dp, if (on) C.accent else if (danger) C.red.copy(alpha = 0.6f) else C.line, RoundedCornerShape(Dim.optCorner))
                                .clickable {
                                    if (!multi) {
                                        vm.choose(card, item)
                                    } else if (!answered) {
                                        if (marks.containsKey(item.id)) marks.remove(item.id) else marks[item.id] = "1"
                                    }
                                }
                                .padding(Dim.optPad),
                        ) {
                            Text(b.show(item.cells[0]), color = C.text, fontSize = Dim.optTitle, fontWeight = FontWeight.Bold)
                            item.cells.getOrNull(1)?.takeIf { it.isNotEmpty() }?.let { Text(b.show(it), color = C.dim, fontSize = Dim.optDesc, lineHeight = Dim.optDescLine) }
                        }
                    }
                    if (multi && !answered && marks.isNotEmpty()) Pill("완료") { vm.chooseMany(card, marks.keys.toSet()) }
                }
            }
            Kind.Record -> {
                // One thing with named properties: the summary, label and value per field, then its rows.
                if (f("text").isNotEmpty()) {
                    Body(f("text"), C.dim)
                    Spacer(Modifier.height(Dim.inGap))
                }
                for ((name, value) in card.fields) {
                    if (name == "text" || name == "cols") continue
                    Row(Modifier.fillMaxWidth().padding(vertical = Dim.recordFieldV)) {
                        Text(name.replace('-', ' ').replace('_', ' '), color = C.dim, fontSize = Dim.small, modifier = Modifier.width(Dim.keyCol))
                        Text(b.show(value), color = C.text, fontSize = Dim.body, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    }
                }
                val cols = com.fidenit.kite.core.Lang.splitCells(card.field("cols")).filter { it.isNotEmpty() }
                if (card.items.isNotEmpty() && cols.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().padding(top = Dim.inGap, bottom = Dim.headPadB)) { cols.forEach { Text(it, color = C.dim, fontSize = Dim.small, modifier = Modifier.weight(1f).padding(end = Dim.cellGap)) } }
                }
                for (item in card.items) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(C.line))
                    Row(Modifier.fillMaxWidth().padding(vertical = Dim.rowV)) {
                        item.cells.forEachIndexed { i, cell ->
                            Text(b.show(cell), color = C.text, fontSize = Dim.body, lineHeight = Dim.bodyLine, fontWeight = if (i == 0) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f).padding(end = Dim.cellGap))
                        }
                    }
                }
            }
            Kind.Input -> InputCard(vm, card)
            Kind.Timeline -> for (item in card.items) {
                Row(Modifier.padding(vertical = Dim.timeRowV), verticalAlignment = Alignment.Top) {
                    Box(Modifier.padding(top = Dim.timeDotTop)) { Dot(stateColor(item.cells.getOrElse(2) { "" })) }
                    Spacer(Modifier.width(Dim.dotGap))
                    Text(b.show(item.cells[0]), color = C.dim, fontSize = Dim.smallPlus, modifier = Modifier.width(Dim.timeCol))
                    Body(b.show(item.cells.getOrElse(1) { "" }), modifier = Modifier.weight(1f))
                }
            }
            Kind.Route -> {
                if (f("total").isNotEmpty()) Text(f("total"), color = C.text, fontSize = Dim.routeTotal, fontWeight = FontWeight.Bold)
                if (f("text").isNotEmpty()) Body(f("text"), C.dim)
                for (item in card.items) {
                    Row(Modifier.padding(top = Dim.inGap), verticalAlignment = Alignment.CenterVertically) {
                        Dot(C.accent)
                        Spacer(Modifier.width(Dim.dotGap))
                        Text(item.cells[0], color = C.text, fontSize = Dim.body, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    }
                    item.cells.getOrNull(2)?.takeIf { it.isNotEmpty() }?.let { Text(it, color = C.dim, fontSize = Dim.small, modifier = Modifier.padding(start = Dim.dot + Dim.dotGap, top = Dim.noteTop)) }
                }
            }
            Kind.Draw -> {
                Drawing(card)
                if (f("text").isNotEmpty()) Body(f("text"), C.dim)
            }
            Kind.Image, Kind.Web, Kind.Doc -> {
                val uri = LocalUriHandler.current
                val target = card.field(if (card.kind == Kind.Web) "url" else "src")
                if (f("text").isNotEmpty()) Body(f("text"))
                Text(target, color = C.dim, fontSize = Dim.small, maxLines = 2)
                if (target.startsWith("http")) {
                    Spacer(Modifier.height(Dim.inGap))
                    Pill("열기") {
                        try {
                            uri.openUri(target)
                        } catch (_: Exception) {
                        }
                    }
                }
            }
        }
    }
}

/** 숫자 칸의 첫 줄 크기: Dim.metricSteps 가운데 카드 안쪽 폭에 한 줄로 들어가는 첫 크기 (없으면 마지막 크기로 줄을 바꾼다). */
private fun firstFit(widths: List<Float>, room: Float): Int = widths.indexOfFirst { it <= room }.let { if (it < 0) widths.lastIndex else it }

/**
 * 지표 숫자·단위·증감. 크기는 그리기 전에 카드 폭([LocalCardWidth], 모르면 화면 폭의 혼자인 카드)으로 정한다:
 * 같은 줄 높이를 재는 측정과 실제 그리기가 같은 크기를 보고, 그린 뒤에 크기가 바뀌지 않는다.
 * 셋이 한 줄에 들어가면 한 줄, 아니면 숫자 한 줄(넘치면 줄바꿈) 아래에 단위·증감.
 */
@Composable
private fun MetricLine(value: String, unit: String, delta: String) {
    val known = LocalCardWidth.current
    val cardW = if (known.isSpecified) known else LocalConfiguration.current.screenWidthDp.dp - Dim.listPadH * 2
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val base = LocalTextStyle.current
    val room = with(density) { (cardW - Dim.cardPad * 2).toPx() }
    fun width(s: String, size: TextUnit, bold: Boolean): Float =
        if (s.isEmpty()) 0f else measurer.measure(s, base.merge(TextStyle(fontSize = size, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)), maxLines = 1, softWrap = false).size.width.toFloat()
    val (size, oneLine) = remember(value, unit, delta, room, measurer, base, density) {
        val step = firstFit(Dim.metricSteps.map { width(value, it, true) }, room)
        val sz = Dim.metricSteps[step]
        val side = with(density) {
            (if (unit.isEmpty()) 0f else Dim.metricUnitStart.toPx() + width(unit, Dim.metricUnit, false)) +
                (if (delta.isEmpty()) 0f else Dim.metricDeltaStart.toPx() + width(delta, Dim.metricDelta, false))
        }
        sz to (width(value, sz, true) + side <= room)
    }
    val deltaColor = if (delta.startsWith("+")) C.green else if (delta.startsWith("-")) C.red else C.dim
    if (oneLine) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, color = C.text, fontSize = size, fontWeight = FontWeight.Bold, maxLines = 1)
            if (unit.isNotEmpty()) Text(unit, color = C.dim, fontSize = Dim.metricUnit, modifier = Modifier.padding(start = Dim.metricUnitStart, bottom = Dim.metricBaseline))
            if (delta.isNotEmpty()) Text(delta, color = deltaColor, fontSize = Dim.metricDelta, modifier = Modifier.padding(start = Dim.metricDeltaStart, bottom = Dim.metricBaseline))
        }
    } else {
        Text(value, color = C.text, fontSize = size, fontWeight = FontWeight.Bold)
        if (unit.isNotEmpty() || delta.isNotEmpty()) {
            val rest = buildAnnotatedString {
                if (unit.isNotEmpty()) withStyle(SpanStyle(color = C.dim)) { append(unit) }
                if (unit.isNotEmpty() && delta.isNotEmpty()) append("  ")
                if (delta.isNotEmpty()) withStyle(SpanStyle(color = deltaColor)) { append(delta) }
            }
            Text(rest, fontSize = Dim.metricDelta)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InputCard(vm: KiteViewModel, card: Card) {
    val b = vm.board
    if (card.field("text").isNotEmpty()) {
        Body(b.show(card.field("text")))
        Spacer(Modifier.height(Dim.inGap))
    }
    val sent = card.field("sent").isNotEmpty()
    // 적는 중인 값은 화면 모델에 둔다: 내 차례 칸과 보드를 오가도 남는다 (§C1)
    val values = vm.draftOf(card.id)
    val hide = rememberHideKeyboard()
    // 이 카드의 글 칸: 마지막 칸만 Done, 나머지는 Next 로 다음 글 칸에 간다 (K3)
    val boxes = card.items.filter { val h = it.cells.getOrElse(1) { "" }; !h.startsWith("slider") && !h.startsWith("choice ") }.map { it.id }
    val focus = remember(card.id, boxes) { boxes.map { FocusRequester() } }
    // 포커스를 받은 글 칸은 보이게 한다: 받을 때, 그리고 내 차례 칸의 높이가 바뀐 뒤 (자판이 올라와 칸이 줄었을 때) (§C2)
    val bring = remember(card.id, boxes) { boxes.map { BringIntoViewRequester() } }
    var focusedAt by remember(card.id) { mutableIntStateOf(-1) }
    val dockHeight = LocalDockHeight.current
    LaunchedEffect(focusedAt, dockHeight) { bring.getOrNull(focusedAt)?.bringIntoView() }
    DisposableEffect(card.id) { onDispose { vm.fieldFocus(card.id, false) } }
    for (item in card.items) {
        val name = item.cells[0]
        val hint = item.cells.getOrElse(1) { "" }
        if (sent) {
            Row(Modifier.padding(vertical = Dim.sentRowV)) {
                Text(name, color = C.dim, fontSize = Dim.smallPlus, modifier = Modifier.width(Dim.keyCol))
                Body(item.cells.getOrElse(2) { "" })
            }
            continue
        }
        if (hint.startsWith("slider")) {
            // "slider 0~30 만원": 범위 안에서 끌어서 정한다
            val parts = hint.removePrefix("slider").trim().split(Regex("\\s+"), limit = 2)
            val range = parts.getOrElse(0) { "" }.split('~', '-').mapNotNull { it.trim().toFloatOrNull() }
            val lo = range.getOrElse(0) { 0f }
            val hi = range.getOrElse(1) { 100f }.coerceAtLeast(lo + 1f)
            val unit = parts.getOrElse(1) { "" }
            val v = values[item.id]?.toFloatOrNull() ?: ((lo + hi) / 2f).also { values[item.id] = it.toInt().toString() }
            Text("$name  ${v.toInt()}${if (unit.isNotEmpty()) " $unit" else ""}", color = C.text, fontSize = Dim.body)
            Slider(value = v, onValueChange = { values[item.id] = it.toInt().toString() }, valueRange = lo..hi)
        } else if (hint.startsWith("choice ")) {
            // "choice Train / Car / Flight": one of the listed values, picked with a press
            Text(name, color = C.dim, fontSize = Dim.small, modifier = Modifier.padding(top = Dim.choiceLabelTop, bottom = Dim.choiceLabelBottom))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = Dim.inGap)) {
                for (option in hint.removePrefix("choice ").split('/').map { it.trim() }.filter { it.isNotEmpty() }) {
                    Pill(option, selected = values[item.id] == option) { values[item.id] = option }
                    Spacer(Modifier.width(Dim.choiceGap))
                }
            }
        } else {
            val at = boxes.indexOf(item.id)
            FieldBox(
                value = values[item.id].orEmpty(),
                onValueChange = { values[item.id] = it },
                label = name,
                placeholder = if (hint == "date") "예: 12/3" else if (hint == "time") "예: 18:30" else if (hint == "number") "숫자" else hint,
                keyboardOptions = KeyboardOptions(keyboardType = if (hint == "number") KeyboardType.Number else KeyboardType.Text, imeAction = if (at == boxes.lastIndex) ImeAction.Done else ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { focus.getOrNull(at + 1)?.requestFocus() }, onDone = { hide() }),
                modifier = Modifier.focusRequester(focus[at]).bringIntoViewRequester(bring[at]).onFocusChanged {
                    if (it.isFocused) {
                        focusedAt = at
                        vm.fieldFocus(card.id, true)
                    } else if (focusedAt == at) {
                        focusedAt = -1
                        vm.fieldFocus(card.id, false)
                    }
                }.testTag("card-field"),
            )
        }
    }
    if (!sent) {
        Spacer(Modifier.height(Dim.submitTop))
        // A box whose name ends with `*` must be filled before the form can be sent.
        val missing = card.items.any { it.cells[0].trimEnd().endsWith("*") && values[it.id].isNullOrBlank() }
        Pill(if (vm.store.lang == "en") "Send" else "보내기", selected = !missing, modifier = Modifier.testTag("card-submit")) {
            hide() // K3
            if (!missing) vm.submit(card, values.toMap())
        }
    }
}

@Composable
private fun Chart(vm: KiteViewModel, card: Card) {
    val rows = card.items.mapNotNull { i -> i.cells.getOrNull(1)?.replace(",", "")?.toDoubleOrNull()?.let { vm.board.show(i.cells[0]) to it } }
    if (rows.isEmpty()) return
    val unit = card.field("unit")
    fun label(v: Double) = (if (v == Math.floor(v)) "%,d".format(v.toLong()) else "%,.1f".format(v)) + unit
    val max = rows.maxOf { it.second }.coerceAtLeast(1e-9)
    val shades = listOf(C.accent, C.text, C.dim, C.faint, C.green, C.amber, C.red)
    when (card.field("form")) {
        "line" -> {
            val min = rows.minOf { it.second }
            Canvas(Modifier.fillMaxWidth().height(Dim.lineChart)) {
                val span = (max - min).coerceAtLeast(1e-9)
                val path = Path()
                rows.forEachIndexed { i, (_, v) ->
                    val x = if (rows.size == 1) size.width / 2 else size.width * i / (rows.size - 1)
                    val y = size.height * (1f - ((v - min) / span).toFloat() * 0.9f - 0.05f)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    drawCircle(C.accent, 4.dp.toPx(), Offset(x, y))
                }
                drawPath(path, C.accent, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
            }
            Row(Modifier.fillMaxWidth().padding(top = Dim.inGap), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${rows.first().first} ${label(rows.first().second)}", color = C.dim, fontSize = Dim.small, modifier = Modifier.weight(1f, fill = false))
                if (rows.size > 1) Text("${rows.last().first} ${label(rows.last().second)}", color = C.text, fontSize = Dim.small, modifier = Modifier.weight(1f, fill = false).padding(start = Dim.cellGap))
            }
        }
        "donut" -> {
            val total = rows.sumOf { it.second }.coerceAtLeast(1e-9)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(Dim.donut)) {
                    var start = -90f
                    val w = Dim.donutStroke.toPx()
                    rows.forEachIndexed { i, (_, v) ->
                        val sweep = (v / total * 360).toFloat()
                        drawArc(shades[i % shades.size], start, sweep - 1.5f, false, Offset(w / 2, w / 2), Size(size.width - w, size.height - w), style = Stroke(w))
                        start += sweep
                    }
                }
                Spacer(Modifier.width(Dim.donutGap))
                Column(Modifier.weight(1f)) {
                    rows.forEachIndexed { i, (name, v) ->
                        Row(Modifier.padding(vertical = Dim.legendRowV), verticalAlignment = Alignment.CenterVertically) {
                            Dot(shades[i % shades.size])
                            Spacer(Modifier.width(Dim.dotGap))
                            Text("$name  ${label(v)} (${"%.0f".format(v / total * 100)}%)", color = C.text, fontSize = Dim.smallPlus, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        else -> for ((name, v) in rows) {
            Column(Modifier.padding(vertical = Dim.chartRowV)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(name, color = C.text, fontSize = Dim.smallPlus, modifier = Modifier.weight(1f, fill = false).padding(end = Dim.cellGap))
                    Text(label(v), color = C.dim, fontSize = Dim.smallPlus)
                }
                Box(Modifier.padding(top = Dim.chartBarTop).fillMaxWidth().height(Dim.chartBar).clip(RoundedCornerShape(Dim.chartBarCorner)).background(C.line)) {
                    Box(Modifier.fillMaxWidth((v / max).toFloat().coerceIn(0f, 1f)).fillMaxHeight().background(if (v == max) C.accent else C.dim))
                }
            }
        }
    }
}

/** 그림 카드: 카드 안쪽을 가로 0~100, 세로 0~100 의 좌표로 보고 도형을 그린다. */
@Composable
private fun Drawing(card: Card) {
    val measurer = rememberTextMeasurer()
    Canvas(Modifier.fillMaxWidth().aspectRatio((card.w.toFloat() / card.h).coerceIn(1f, 2f))) {
        fun points(s: String): List<Offset> = s.trim().split(Regex("\\s+")).mapNotNull { p ->
            val xy = p.split(',')
            val x = xy.getOrNull(0)?.toFloatOrNull()
            val y = xy.getOrNull(1)?.toFloatOrNull()
            if (x == null || y == null) null else Offset(x / 100f * size.width, y / 100f * size.height)
        }
        for (item in card.items) {
            val shape = item.cells[0]
            val coords = item.cells.getOrElse(1) { "" }
            val text = item.cells.getOrElse(2) { "" }
            val color = named(item.cells.getOrElse(3) { "" })
            val sp = when (item.cells.getOrElse(4) { "" }.trim()) {
                "s" -> Dim.drawS
                "l" -> Dim.drawL
                else -> Dim.drawM
            }
            val stroke = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round)
            when (shape) {
                "rect" -> {
                    val nums = coords.trim().split(Regex("[\\s,]+")).mapNotNull { it.toFloatOrNull() }
                    if (nums.size >= 4) {
                        val tl = Offset(nums[0] / 100f * size.width, nums[1] / 100f * size.height)
                        val sz = Size(nums[2] / 100f * size.width, nums[3] / 100f * size.height)
                        drawRoundRect(color, tl, sz, androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()), style = stroke)
                        label(measurer, text, Offset(tl.x + sz.width / 2, tl.y + sz.height / 2), color, sp)
                    }
                }
                "circle" -> {
                    val nums = coords.trim().split(Regex("[\\s,]+")).mapNotNull { it.toFloatOrNull() }
                    if (nums.size >= 3) {
                        val c = Offset(nums[0] / 100f * size.width, nums[1] / 100f * size.height)
                        drawCircle(color, nums[2] / 100f * size.width, c, style = stroke)
                        label(measurer, text, c, color, sp)
                    }
                }
                "line", "arrow" -> {
                    val pts = points(coords)
                    for (i in 0 until pts.size - 1) drawLine(color, pts[i], pts[i + 1], stroke.width, StrokeCap.Round)
                    if (shape == "arrow" && pts.size >= 2) {
                        val a = pts[pts.size - 2]
                        val e = pts.last()
                        val ang = atan2(e.y - a.y, e.x - a.x)
                        val len = 9.dp.toPx()
                        for (d in listOf(2.6f, -2.6f)) drawLine(color, e, Offset(e.x + len * cos(ang + d), e.y + len * sin(ang + d)), stroke.width, StrokeCap.Round)
                    }
                    if (text.isNotEmpty() && pts.size >= 2) {
                        val mid = Offset((pts.first().x + pts.last().x) / 2, (pts.first().y + pts.last().y) / 2 - 10.dp.toPx())
                        label(measurer, text, mid, color, sp)
                    }
                }
                "text" -> points(coords).firstOrNull()?.let { label(measurer, text, it, color, sp) }
            }
        }
    }
}

/** 글을 주어진 점을 가운데로 해서 쓴다. */
private fun DrawScope.label(measurer: TextMeasurer, text: String, center: Offset, color: Color, size: androidx.compose.ui.unit.TextUnit) {
    if (text.isEmpty()) return
    val layout = measurer.measure(text, TextStyle(color = color, fontSize = size))
    val x = (center.x - layout.size.width / 2f).coerceIn(0f, (this.size.width - layout.size.width).coerceAtLeast(0f))
    val y = (center.y - layout.size.height / 2f).coerceIn(0f, (this.size.height - layout.size.height).coerceAtLeast(0f))
    drawText(layout, topLeft = Offset(x, y))
}
