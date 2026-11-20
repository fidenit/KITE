// 폰 화면. 주제 하나가 한 화면이고, 카드는 한 줄로 쌓인다. 아래에 한마디와 마이크가 있다.
// 디자인은 데스크톱과 같다: 무광 검정, 회색조, 큰 둥근 모서리, 굵은 제목.
package com.fidenit.kite.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.fidenit.kite.KiteViewModel
import com.fidenit.kite.engine.Decide
import com.fidenit.kite.voice.ProVoice

object C {
    val bg = Color(0xFF0B0B0C)
    val card = Color(0xFF161618)
    val line = Color(0xFF2A2A2E)
    val text = Color(0xFFEDEDED)
    val dim = Color(0xFF9A9AA0)
    val faint = Color(0xFF5A5A60)
    val accent = Color(0xFFFF8A3D)
    val green = Color(0xFF4CC38A)
    val red = Color(0xFFE5484D)
    val amber = Color(0xFFF5B83D)
}

/**
 * 한 줄에 나란한 칸들의 높이: 보통 글꼴에서는 [min] 그대로, 글꼴을 크게 키워 [sizes] 의 글자 한 줄이 그보다 높으면 그 높이.
 * 같은 줄의 칸이 모두 이 값을 쓰니 높이가 같이 커진다 (글자는 잘리지 않는다).
 */
@Composable
fun rowHeight(min: Dp, sizes: List<TextUnit>): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val base = LocalTextStyle.current
    return remember(min, sizes, measurer, density, base) {
        val tallest = sizes.maxOf { measurer.measure("가Ag", base.merge(TextStyle(fontSize = it, fontWeight = FontWeight.Bold)), maxLines = 1).size.height }
        maxOf(min, with(density) { tallest.toDp() })
    }
}

/** 아래 입력 줄의 칸 높이 (글 칸 한 줄·알약·마이크). */
@Composable
private fun barHeight(): Dp = rowHeight(Dim.BAR_H, listOf(Dim.input, Dim.pill, Dim.talk))

/** 테두리만 있는 알약형 버튼. 글자는 가운데, 높이는 적어도 [height] (아래 줄 밖은 [Dim.PILL_H], 줄 높이를 맞출 때는 [rowHeight]); 글꼴이 아주 크면 글자 한 줄만큼 커진다. */
@Composable
fun Pill(label: String, selected: Boolean = false, color: Color = C.text, modifier: Modifier = Modifier, height: Dp = Dim.PILL_H, onClick: () -> Unit) {
    Text(
        label,
        color = if (selected) C.bg else color,
        fontSize = Dim.pill,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        maxLines = 1,
        modifier = modifier
            .heightIn(min = height)
            .clip(RoundedCornerShape(50))
            .background(if (selected) C.text else Color.Transparent)
            .border(1.dp, if (selected) C.text else C.line, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .wrapContentHeight(Alignment.CenterVertically)
            .padding(horizontal = Dim.pillPadH),
    )
}

/**
 * 작은 테두리 글 칸 (입력 카드와 설정이 같이 쓴다). 한 줄 칸은 [Dim.FIELD_H] 높이, 이름은 지금처럼 테두리 위에 뜨고, 예시 글도 지금처럼.
 * `modifier` (시험 이름·포커스) 는 글을 적는 칸 자체에 붙고, 그 칸의 크기가 테두리 상자다. 위의 [Dim.fieldLabel] 은 떠오른 이름이 들어갈 자리.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FieldBox(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    lines: Int = 1,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val single = lines == 1
    val source = remember { MutableInteractionSource() }
    val colors = OutlinedTextFieldDefaults.colors()
    val padV = if (single) 0.dp else Dim.fieldPadV
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.padding(top = Dim.fieldLabel, bottom = Dim.fieldGap).then(modifier).fillMaxWidth()
            .let { if (single) it.height(Dim.FIELD_H) else it.heightIn(min = Dim.FIELD_H) },
        textStyle = TextStyle(color = C.text, fontSize = Dim.input),
        cursorBrush = SolidColor(C.accent),
        singleLine = single,
        minLines = lines,
        maxLines = if (single) 1 else 6,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        interactionSource = source,
        decorationBox = { inner ->
            OutlinedTextFieldDefaults.DecorationBox(
                value = value,
                innerTextField = inner,
                enabled = true,
                singleLine = single,
                visualTransformation = visualTransformation,
                interactionSource = source,
                label = { Text(label) },
                placeholder = if (placeholder.isEmpty()) null else ({ Text(placeholder, color = C.faint, fontSize = Dim.input) }),
                colors = colors,
                contentPadding = OutlinedTextFieldDefaults.contentPadding(start = Dim.fieldPadH, top = padV, end = Dim.fieldPadH, bottom = padV),
                container = { OutlinedTextFieldDefaults.ContainerBox(true, false, source, colors, RoundedCornerShape(Dim.fieldCorner)) },
            )
        },
    )
}

/** 글 칸의 포커스를 풀고 자판을 내린다 (K1 바깥 누름, K2 보낸 뒤, K3·K4 Done). */
@Composable
fun rememberHideKeyboard(): () -> Unit {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    return remember(focus, keyboard) {
        {
            focus.clearFocus()
            keyboard?.hide()
        }
    }
}

@Composable
fun KiteApp(vm: KiteViewModel) {
    var page by remember { mutableStateOf(if (vm.needsSetup()) "settings" else "board") }
    val view = LocalView.current
    DisposableEffect(vm.converse) {
        view.keepScreenOn = vm.converse // 대화하는 동안 화면을 켜 둔다
        onDispose { }
    }
    MaterialTheme(colorScheme = darkColorScheme(primary = C.accent, background = C.bg, surface = C.card, onBackground = C.text, onSurface = C.text)) {
        val hide = rememberHideKeyboard()
        // 글 칸도 버튼도 아닌 곳을 누르면 자판을 내린다 (K1). 버튼·글 칸이 먼저 받아 쓰므로 그 누름은 여기까지 오지 않는다.
        Box(Modifier.fillMaxSize().background(C.bg).systemBarsPadding().imePadding().testTag("root").pointerInput(hide) { detectTapGestures(onTap = { hide() }) }) {
            when (page) {
                "settings" -> {
                    BackHandler { page = "board" }
                    SettingsScreen(vm) { page = "board" }
                }
                "talk" -> {
                    BackHandler { page = "board" }
                    TalkLog(vm) { page = "board" }
                }
                else -> BoardScreen(vm, onSettings = { page = "settings" }, onLog = { page = "talk" })
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BoardScreen(vm: KiteViewModel, onSettings: () -> Unit, onLog: () -> Unit) {
    val rev = vm.rev // 보드가 바뀌면 다시 그린다
    val board = vm.board
    val english = vm.store.lang == "en"
    val listState = rememberLazyListState() // 보드 목록의 스크롤 (가리키기가 움직인다)
    // 내 차례 칸의 높이 한도 (§C2): 보드 목록 + 칸의 높이는 칸이 커지고 줄어도 같다 (보드가 나머지를 채운다)
    var boardH by remember { mutableIntStateOf(0) }
    var dockH by remember { mutableIntStateOf(0) }
    val dockTopic = vm.dockedId?.let { board.card(it)?.topic }
    // 위 줄의 주제·알약은 같은 높이 (보통 PILL_H, 글꼴이 아주 크면 같이 커진다)
    val topH = rowHeight(Dim.PILL_H, listOf(Dim.topic, Dim.pill))
    val barH = barHeight()
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // 첫 그림에서는 보드 높이를 아직 재지 않았다: 창 높이에서 위 줄과 입력 줄을 뺀 값으로 칸 한도를 먼저 정한다 (칸이 다 찼다가 줄어드는 일이 없게)
        val windowH = if (constraints.hasBoundedHeight) constraints.maxHeight else 0
        Column(Modifier.fillMaxSize()) {
            // 주제
            Row(Modifier.fillMaxWidth().testTag("top-bar").padding(start = Dim.topPadH, end = Dim.topPadH, top = Dim.topPadTop, bottom = Dim.topPadBottom), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Dim.topicGap)) {
                    for (t in board.topics) {
                        val on = t.id == vm.current
                        Box {
                            Text(
                                t.name,
                                color = if (on) C.text else C.dim,
                                fontSize = Dim.topic,
                                fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                // 같은 줄의 알약과 같은 높이, 글자는 가운데
                                modifier = Modifier
                                    .height(topH)
                                    .clip(RoundedCornerShape(Dim.topicCorner))
                                    .combinedClickable(onClick = { vm.current = t.id }, onLongClick = { vm.archive(t.id) })
                                    .wrapContentHeight(Alignment.CenterVertically)
                                    .padding(horizontal = Dim.topicPadH),
                            )
                            // 내 차례 칸에 붙은 카드의 주제
                            if (t.id == dockTopic) Box(Modifier.align(Alignment.TopEnd).padding(top = Dim.topicDotTop, end = Dim.topicDotEnd).size(Dim.topicDot).clip(CircleShape).background(C.accent).testTag("topic-dot"))
                        }
                    }
                    if (board.topics.isEmpty()) Text(if (com.fidenit.kite.BuildConfig.PRO) "APERIT Pro" else "APERIT", color = C.text, fontSize = Dim.topic, fontWeight = FontWeight.Bold, modifier = Modifier.height(topH).wrapContentHeight(Alignment.CenterVertically).padding(horizontal = Dim.topicPadH))
                }
                Spacer(Modifier.width(Dim.topTopicsGap))
                Pill(if (english) "Talk log" else "대화 기록", modifier = Modifier.testTag("top-log"), height = topH, onClick = onLog)
                Spacer(Modifier.width(Dim.topPillGap))
                Pill(if (english) "Settings" else "설정", modifier = Modifier.testTag("top-settings"), height = topH, onClick = onSettings)
            }

            vm.error?.let { e ->
                Text(
                    e,
                    color = C.text,
                    fontSize = Dim.error,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Dim.errPadH, vertical = Dim.errPadV).clip(RoundedCornerShape(Dim.errCorner)).background(C.red.copy(alpha = 0.22f)).clickable { vm.error = null }.padding(Dim.errInner),
                )
            }

            // 카드: 중요한 것부터 세 단계로 (같은 단계 안에서는 원래 순서 그대로). 내 차례 칸에 붙은 카드는 보드에서 뺀다.
            val importance = vm.importance
            val docked = vm.dockedId
            val ordered = board.cards.filter { it.topic == vm.current && it.id != docked }.sortedBy { -Decide.level(importance[it.id]) }
            if (ordered.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth().onSizeChanged { boardH = it.height }.testTag("board-empty"), contentAlignment = Alignment.Center) {
                    // Pro 에는 말로 대화하기가 없고 화면에 안내 문장을 두지 않는다 (A2)
                    if (!com.fidenit.kite.BuildConfig.PRO) {
                        Text(
                            if (english) "Tap the microphone and talk,\nor type below." else "마이크를 누르고 말하거나\n아래에 적어 보세요.",
                            color = C.faint,
                            fontSize = Dim.emptyHint,
                            lineHeight = Dim.emptyHintLine,
                        )
                    }
                }
            } else {
                BoardGrid(vm, ordered, rev, listState, Modifier.weight(1f).fillMaxWidth().onSizeChanged { boardH = it.height })
            }

            // 보드에 다른 카드가 있으면 칸은 DOCK_MAX 까지, 없으면 입력 줄 위 전부
            val fixed = with(density) { (Dim.topPadTop + topH + Dim.topPadBottom + Dim.barPadV * 2 + Dim.barRowTop + barH).roundToPx() }
            val area = if (boardH > 0) boardH + dockH else (windowH - fixed).coerceAtLeast(0)
            val dockMax = if (area <= 0) 0 else if (ordered.isNotEmpty()) (area * Dim.DOCK_MAX).toInt() else area
            BottomBar(vm, english) { TodoDock(vm, dockMax, dockH) { dockH = it } }
        }
    }
}

@Composable
private fun BottomBar(vm: KiteViewModel, english: Boolean, dock: @Composable () -> Unit = {}) {
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    // Pro: 보내기 전에 막힌 말은 입력칸으로 돌아온다 (A4)
    if (com.fidenit.kite.BuildConfig.PRO) {
        LaunchedEffect(vm.refill) {
            vm.refill?.let {
                input = if (input.isBlank()) it else it + "\n" + input // 그사이 새로 적은 글은 뒤에 남긴다
                vm.refill = null
            }
        }
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) vm.converse(true) else vm.error = if (english) "Microphone permission is needed to talk." else "말로 대화하려면 마이크 권한이 필요합니다."
    }
    val hide = rememberHideKeyboard()
    val fieldFocus = remember { FocusRequester() }
    var multiLine by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().testTag("bottom-bar").background(C.bg).padding(horizontal = Dim.barPadH, vertical = Dim.barPadV)) {
        // 한마디
        val say = vm.liveSay ?: vm.board.show(vm.board.say)
        if (say.isNotEmpty()) Text(say, color = C.text, fontSize = Dim.say, lineHeight = Dim.sayLine, fontWeight = FontWeight.Medium)
        // 지금 무엇을 하는지
        val status = when {
            vm.activity.isNotEmpty() -> vm.activity
            !vm.busy && vm.proLine.isNotEmpty() -> vm.proLine
            vm.transcribing -> if (english) "Writing down what you said…" else "받아쓰는 중…"
            vm.standby -> if (english) "Waiting. Say \"Aperit\" to continue." else "기다리는 중. \"아페리트\"라고 부르면 이어 갑니다."
            vm.speaking -> if (english) "Speaking. Talk over me to interrupt." else "말하는 중. 말을 걸면 멈추고 듣습니다."
            vm.busy -> if (english) "Working…" else "일하는 중…"
            vm.converse && vm.heard -> if (english) "Listening…" else "듣는 중…"
            vm.converse -> if (english) "Go ahead." else "말씀하세요."
            else -> vm.statsLine
        }
        if (status.isNotEmpty()) Text(status, color = C.dim, fontSize = Dim.status, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = Dim.statusTop))
        // 내 차례 칸: 입력 줄 바로 위 (붙은 카드가 없으면 아무것도 그리지 않는다)
        dock()
        Spacer(Modifier.height(Dim.barRowTop))
        // 아래 줄의 글 칸(한 줄일 때)·알약·마이크는 같은 높이: 보통 BAR_H, 글꼴이 아주 크면 같이 커진다
        val barH = barHeight()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.weight(1f).heightIn(min = barH).testTag("bar-input").clip(RoundedCornerShape(Dim.inputCorner)).background(C.card).border(1.dp, C.line, RoundedCornerShape(Dim.inputCorner))
                    .pointerInput(Unit) { detectTapGestures(onTap = { fieldFocus.requestFocus() }) } // 칸 안의 여백도 글 칸이다 (K1 로 내리지 않는다)
                    .padding(horizontal = Dim.inputPadH, vertical = if (multiLine) Dim.inputPadV else 0.dp),
                // 한 줄일 때 글자가 BAR_H 안 가운데 (글자를 키워도 한 줄이면 BAR_H), 두 줄부터 위아래 여백을 두고 네 줄까지 늘어난다
                contentAlignment = Alignment.CenterStart,
            ) {
                if (input.isEmpty()) Text(if (english) "Type here" else "여기에 적기", color = C.faint, fontSize = Dim.input)
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    textStyle = TextStyle(color = C.text, fontSize = Dim.input),
                    onTextLayout = { multiLine = it.lineCount > 1 },
                    cursorBrush = SolidColor(C.accent),
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        vm.send(input)
                        input = ""
                        hide() // K2
                    }),
                    modifier = Modifier.fillMaxWidth().focusRequester(fieldFocus).testTag("bar-field"),
                )
            }
            Spacer(Modifier.width(Dim.barGap))
            if (input.isNotBlank()) {
                Pill(if (english) "Send" else "보내기", selected = true, modifier = Modifier.testTag("bar-send"), height = barH) {
                    vm.send(input)
                    input = ""
                    hide() // K2
                }
            } else {
                if (vm.busy || vm.speaking) {
                    Pill(if (english) "Stop" else "멈춤", color = C.red, modifier = Modifier.testTag("bar-stop"), height = barH) { vm.stop() }
                    Spacer(Modifier.width(Dim.barGap))
                }
                // 마이크: 켜져 있으면 들리는 소리 크기만큼 테두리가 밝아진다. Pro 는 서버의 목소리를 쓸 수 있을 때만 (D-A1, 아니면 A1)
                if (ProVoice.controlsShown(com.fidenit.kite.BuildConfig.PRO, vm.proVoiceOn)) {
                val on = vm.converse
                val glow = if (on) (vm.micLevel * 12f).coerceIn(0f, 1f) else 0f
                Box(
                    Modifier
                        .size(barH)
                        .testTag("bar-talk")
                        .clip(CircleShape)
                        .background(if (on) C.accent.copy(alpha = 0.35f + 0.65f * glow) else Color.Transparent)
                        .border(1.5.dp, if (on) C.accent else C.line, CircleShape)
                        .clickable {
                            if (on) {
                                vm.converse(false)
                            } else if (!ProVoice.mayTalk(com.fidenit.kite.BuildConfig.PRO, vm.proVoiceOn, vm.store.elevenKey)) {
                                vm.converse(true) // 키가 없으면 마이크 권한은 묻지 않고, 키가 필요하다는 안내만 낸다 (Pro 는 키 대신 서버의 목소리)
                            } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                vm.converse(true)
                            } else {
                                ask.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (on) (if (english) "On" else "켜짐") else (if (english) "Talk" else "대화"), color = if (on) C.bg else C.text, fontSize = Dim.talk, fontWeight = FontWeight.Bold)
                }
                }
            }
        }
    }
}

/** 주고받은 말 */
@Composable
private fun TalkLog(vm: KiteViewModel, onBack: () -> Unit) {
    val english = vm.store.lang == "en"
    Column(Modifier.fillMaxSize().padding(horizontal = Dim.logPadH)) {
        Row(Modifier.fillMaxWidth().padding(vertical = Dim.logTitleV), verticalAlignment = Alignment.CenterVertically) {
            Text(if (english) "Talk log" else "대화 기록", color = C.text, fontSize = Dim.pageTitle, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Pill(if (english) "Back" else "돌아가기", modifier = Modifier.testTag("page-back"), onClick = onBack)
        }
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Dim.logGap), reverseLayout = true) {
            items(vm.recent.reversed()) { (user, ali) ->
                Column {
                    Text(user, color = C.dim, fontSize = Dim.logUser, lineHeight = Dim.logUserLine)
                    if (ali.isNotEmpty()) Text(ali, color = C.text, fontSize = Dim.logReply, lineHeight = Dim.logReplyLine, modifier = Modifier.padding(top = Dim.logReplyTop))
                }
            }
        }
    }
}
