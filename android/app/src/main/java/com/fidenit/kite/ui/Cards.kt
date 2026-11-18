// 부품별 그리기. 데스크톱 src/ui.rs 의 draw_body 에 해당한다. 폰에서는 모든 카드가 화면 폭을 다 쓴다.
package com.fidenit.kite.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
private fun Dot(color: Color) = Box(Modifier.size(10.dp).clip(CircleShape).background(color))

@Composable
private fun Body(s: String, color: Color = C.text) = Text(s, color = color, fontSize = 15.sp, lineHeight = 22.sp)

/** 카드 하나. `rev` 는 보드가 바뀌었을 때 다시 그리게 하려고 받는다. */
@Composable
fun CardView(vm: KiteViewModel, card: Card, @Suppress("UNUSED_PARAMETER") rev: Int) {
    val b = vm.board
    // In a field named `text`, the two characters \n are shown as a line break (protocol 0.3).
    fun f(name: String) = b.show(card.field(name)).let { if (name == "text") it.replace("\\n", "\n") else it }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(C.card).padding(16.dp)) {
        if (card.title.isNotEmpty()) {
            Text(b.show(card.title), color = C.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
        }
        when (card.kind) {
            Kind.Note -> Body(f("text"))
            Kind.Status -> Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(stateColor(card.field("state")))
                Spacer(Modifier.width(10.dp))
                Body(f("text"))
            }
            Kind.Metric -> {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(f("value"), color = C.text, fontSize = 38.sp, fontWeight = FontWeight.Bold)
                    if (f("unit").isNotEmpty()) Text(f("unit"), color = C.dim, fontSize = 16.sp, modifier = Modifier.padding(start = 6.dp, bottom = 7.dp))
                    val delta = f("delta")
                    if (delta.isNotEmpty()) {
                        Text(delta, color = if (delta.startsWith("+")) C.green else if (delta.startsWith("-")) C.red else C.dim, fontSize = 15.sp, modifier = Modifier.padding(start = 12.dp, bottom = 7.dp))
                    }
                }
                if (f("text").isNotEmpty()) Body(f("text"), C.dim)
            }
            Kind.Chart -> Chart(vm, card)
            Kind.Table -> {
                val cols = com.fidenit.kite.core.Lang.splitCells(f("cols")).filter { it.isNotEmpty() }
                if (cols.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) { cols.forEach { Text(it, color = C.dim, fontSize = 13.sp, modifier = Modifier.weight(1f).padding(end = 8.dp)) } }
                }
                for (item in card.items) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(C.line))
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        item.cells.forEachIndexed { i, cell ->
                            Text(b.show(cell), color = C.text, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = if (i == 0) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f).padding(end = 8.dp))
                        }
                    }
                }
            }
            Kind.Progress -> {
                val pct = card.field("pct").toIntOrNull()
                if (pct != null) {
                    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(C.line)) {
                        Box(Modifier.fillMaxWidth(pct / 100f).fillMaxHeight().background(C.accent))
                    }
                    Spacer(Modifier.height(8.dp))
                }
                if (f("text").isNotEmpty()) Body(f("text"), C.dim)
                for (item in card.items) {
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Dot(stateColor(item.cells[0]))
                        Spacer(Modifier.width(10.dp))
                        Body(b.show(item.cells.getOrElse(1) { "" }), if (item.cells[0] == "todo") C.dim else C.text)
                    }
                }
            }
            Kind.Checklist -> for (item in card.items) {
                val done = item.cells[0] == "done"
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { vm.toggle(card, item) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(22.dp).clip(RoundedCornerShape(7.dp)).background(if (done) C.green else Color.Transparent).border(1.5.dp, if (done) C.green else C.faint, RoundedCornerShape(7.dp)))
                    Spacer(Modifier.width(12.dp))
                    Body(b.show(item.cells.getOrElse(1) { "" }), if (done) C.dim else C.text)
                }
            }
            Kind.Decision -> {
                if (f("text").isNotEmpty()) {
                    Body(f("text"))
                    Spacer(Modifier.height(10.dp))
                }
                val chosen = card.field("chosen").split(',')
                val multi = card.field("multi") == "yes"
                val danger = card.field("tone") == "danger"
                val answered = card.field("chosen").isNotEmpty()
                // With several answers a tap only marks the option; the answer goes out with "Done".
                val marks = remember(card.id) { mutableStateMapOf<String, String>() }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (item in card.items) {
                        val on = item.id in chosen || (multi && !answered && marks.containsKey(item.id))
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (on) C.accent.copy(alpha = 0.18f) else Color.Transparent)
                                .border(1.dp, if (on) C.accent else if (danger) C.red.copy(alpha = 0.6f) else C.line, RoundedCornerShape(16.dp))
                                .clickable {
                                    if (!multi) {
                                        vm.choose(card, item)
                                    } else if (!answered) {
                                        if (marks.containsKey(item.id)) marks.remove(item.id) else marks[item.id] = "1"
                                    }
                                }
                                .padding(14.dp),
                        ) {
                            Text(b.show(item.cells[0]), color = C.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            item.cells.getOrNull(1)?.takeIf { it.isNotEmpty() }?.let { Text(b.show(it), color = C.dim, fontSize = 14.sp, lineHeight = 20.sp) }
                        }
                    }
                    if (multi && !answered && marks.isNotEmpty()) Pill("완료") { vm.chooseMany(card, marks.keys.toSet()) }
                }
            }
            Kind.Record -> {
                // One thing with named properties: the summary, label and value per field, then its rows.
                if (f("text").isNotEmpty()) {
                    Body(f("text"), C.dim)
                    Spacer(Modifier.height(8.dp))
                }
                for ((name, value) in card.fields) {
                    if (name == "text" || name == "cols") continue
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(name.replace('-', ' ').replace('_', ' '), color = C.dim, fontSize = 13.sp, modifier = Modifier.width(104.dp))
                        Text(b.show(value), color = C.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                }
                val cols = com.fidenit.kite.core.Lang.splitCells(card.field("cols")).filter { it.isNotEmpty() }
                if (card.items.isNotEmpty() && cols.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp)) { cols.forEach { Text(it, color = C.dim, fontSize = 13.sp, modifier = Modifier.weight(1f).padding(end = 8.dp)) } }
                }
                for (item in card.items) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(C.line))
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        item.cells.forEachIndexed { i, cell ->
                            Text(b.show(cell), color = C.text, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = if (i == 0) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f).padding(end = 8.dp))
                        }
                    }
                }
            }
            Kind.Input -> InputCard(vm, card)
            Kind.Timeline -> for (item in card.items) {
                Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                    Box(Modifier.padding(top = 6.dp)) { Dot(stateColor(item.cells.getOrElse(2) { "" })) }
                    Spacer(Modifier.width(10.dp))
                    Text(b.show(item.cells[0]), color = C.dim, fontSize = 14.sp, modifier = Modifier.width(84.dp))
                    Body(b.show(item.cells.getOrElse(1) { "" }))
                }
            }
            Kind.Route -> {
                if (f("total").isNotEmpty()) Text(f("total"), color = C.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                if (f("text").isNotEmpty()) Body(f("text"), C.dim)
                for (item in card.items) {
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Dot(C.accent)
                        Spacer(Modifier.width(10.dp))
                        Text(item.cells[0], color = C.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                    item.cells.getOrNull(2)?.takeIf { it.isNotEmpty() }?.let { Text(it, color = C.dim, fontSize = 13.sp, modifier = Modifier.padding(start = 20.dp, top = 2.dp)) }
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
                Text(target, color = C.dim, fontSize = 13.sp, maxLines = 2)
                if (target.startsWith("http")) {
                    Spacer(Modifier.height(10.dp))
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

@Composable
private fun InputCard(vm: KiteViewModel, card: Card) {
    val b = vm.board
    if (card.field("text").isNotEmpty()) {
        Body(b.show(card.field("text")))
        Spacer(Modifier.height(10.dp))
    }
    val sent = card.field("sent").isNotEmpty()
    val values = remember(card.id) { mutableStateMapOf<String, String>() }
    for (item in card.items) {
        val name = item.cells[0]
        val hint = item.cells.getOrElse(1) { "" }
        if (sent) {
            Row(Modifier.padding(vertical = 4.dp)) {
                Text(name, color = C.dim, fontSize = 14.sp, modifier = Modifier.width(96.dp))
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
            Text("$name  ${v.toInt()}${if (unit.isNotEmpty()) " $unit" else ""}", color = C.text, fontSize = 15.sp)
            Slider(value = v, onValueChange = { values[item.id] = it.toInt().toString() }, valueRange = lo..hi)
        } else if (hint.startsWith("choice ")) {
            // "choice Train / Car / Flight": one of the listed values, picked with a press
            Text(name, color = C.dim, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp)) {
                for (option in hint.removePrefix("choice ").split('/').map { it.trim() }.filter { it.isNotEmpty() }) {
                    Pill(option, selected = values[item.id] == option) { values[item.id] = option }
                    Spacer(Modifier.width(8.dp))
                }
            }
        } else {
            OutlinedTextField(
                value = values[item.id].orEmpty(),
                onValueChange = { values[item.id] = it },
                label = { Text(name) },
                placeholder = { Text(if (hint == "date") "예: 12/3" else if (hint == "time") "예: 18:30" else if (hint == "number") "숫자" else hint, color = C.faint) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = if (hint == "number") KeyboardType.Number else KeyboardType.Text),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
        }
    }
    if (!sent) {
        Spacer(Modifier.height(4.dp))
        // A box whose name ends with `*` must be filled before the form can be sent.
        val missing = card.items.any { it.cells[0].trimEnd().endsWith("*") && values[it.id].isNullOrBlank() }
        Pill(if (vm.store.lang == "en") "Send" else "보내기", selected = !missing) { if (!missing) vm.submit(card, values.toMap()) }
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
            Canvas(Modifier.fillMaxWidth().height(140.dp)) {
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
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${rows.first().first} ${label(rows.first().second)}", color = C.dim, fontSize = 13.sp)
                if (rows.size > 1) Text("${rows.last().first} ${label(rows.last().second)}", color = C.text, fontSize = 13.sp)
            }
        }
        "donut" -> {
            val total = rows.sumOf { it.second }.coerceAtLeast(1e-9)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(110.dp)) {
                    var start = -90f
                    val w = 18.dp.toPx()
                    rows.forEachIndexed { i, (_, v) ->
                        val sweep = (v / total * 360).toFloat()
                        drawArc(shades[i % shades.size], start, sweep - 1.5f, false, Offset(w / 2, w / 2), Size(size.width - w, size.height - w), style = Stroke(w))
                        start += sweep
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    rows.forEachIndexed { i, (name, v) ->
                        Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Dot(shades[i % shades.size])
                            Spacer(Modifier.width(8.dp))
                            Text("$name  ${label(v)} (${"%.0f".format(v / total * 100)}%)", color = C.text, fontSize = 14.sp)
                        }
                    }
                }
            }
        }
        else -> for ((name, v) in rows) {
            Column(Modifier.padding(vertical = 5.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(name, color = C.text, fontSize = 14.sp)
                    Text(label(v), color = C.dim, fontSize = 14.sp)
                }
                Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(C.line)) {
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
                "s" -> 11.sp
                "l" -> 17.sp
                else -> 13.sp
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
