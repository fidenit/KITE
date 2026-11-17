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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.fidenit.kite.KiteViewModel

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

/** 테두리만 있는 알약형 버튼 */
@Composable
fun Pill(label: String, selected: Boolean = false, color: Color = C.text, onClick: () -> Unit) {
    Text(
        label,
        color = if (selected) C.bg else color,
        fontSize = 14.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) C.text else Color.Transparent)
            .border(1.dp, if (selected) C.text else C.line, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
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
        Box(Modifier.fillMaxSize().background(C.bg).systemBarsPadding().imePadding()) {
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
    Column(Modifier.fillMaxSize()) {
        // 주제
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (t in board.topics) {
                    val on = t.id == vm.current
                    Text(
                        t.name,
                        color = if (on) C.text else C.dim,
                        fontSize = 17.sp,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .combinedClickable(onClick = { vm.current = t.id }, onLongClick = { vm.archive(t.id) })
                            .padding(horizontal = 6.dp, vertical = 6.dp),
                    )
                }
                if (board.topics.isEmpty()) Text(if (com.fidenit.kite.BuildConfig.PRO) "KITE Pro" else "KITE", color = C.text, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(6.dp))
            }
            Spacer(Modifier.width(8.dp))
            Pill(if (english) "Talk log" else "대화", onClick = onLog)
            Spacer(Modifier.width(6.dp))
            Pill(if (english) "Settings" else "설정", onClick = onSettings)
        }

        vm.error?.let { e ->
            Text(
                e,
                color = C.text,
                fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(14.dp)).background(C.red.copy(alpha = 0.22f)).clickable { vm.error = null }.padding(12.dp),
            )
        }

        // 카드: 답을 기다리는 것이 맨 위
        val cards = board.cards.filter { it.topic == vm.current }
        val waiting = cards.filter { (it.kind.asks) && it.field("chosen").isEmpty() && it.field("sent").isEmpty() }
        val ordered = waiting + cards.filter { it !in waiting }
        if (ordered.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    if (english) "Tap the microphone and talk,\nor type below." else "마이크를 누르고 말하거나\n아래에 적어 보세요.",
                    color = C.faint,
                    fontSize = 16.sp,
                    lineHeight = 24.sp,
                )
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(ordered, key = { it.id }) { card -> CardView(vm, card, rev) }
            }
        }

        BottomBar(vm, english)
    }
}

@Composable
private fun BottomBar(vm: KiteViewModel, english: Boolean) {
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) vm.converse(true) else vm.error = if (english) "Microphone permission is needed to talk." else "말로 대화하려면 마이크 권한이 필요합니다."
    }
    Column(Modifier.fillMaxWidth().background(C.bg).padding(horizontal = 14.dp, vertical = 10.dp)) {
        // 한마디
        val say = vm.liveSay ?: vm.board.show(vm.board.say)
        if (say.isNotEmpty()) Text(say, color = C.text, fontSize = 18.sp, lineHeight = 25.sp, fontWeight = FontWeight.Medium)
        // 지금 무엇을 하는지
        val status = when {
            vm.activity.isNotEmpty() -> vm.activity
            vm.transcribing -> if (english) "Writing down what you said…" else "받아쓰는 중…"
            vm.standby -> if (english) "Waiting. Say \"Kite\" to continue." else "기다리는 중. \"카이트\"라고 부르면 이어 갑니다."
            vm.speaking -> if (english) "Speaking. Talk over me to interrupt." else "말하는 중. 말을 걸면 멈추고 듣습니다."
            vm.busy -> if (english) "Working…" else "일하는 중…"
            vm.converse && vm.heard -> if (english) "Listening…" else "듣는 중…"
            vm.converse -> if (english) "Go ahead." else "말씀하세요."
            else -> vm.statsLine
        }
        if (status.isNotEmpty()) Text(status, color = C.dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).clip(RoundedCornerShape(22.dp)).background(C.card).border(1.dp, C.line, RoundedCornerShape(22.dp)).padding(horizontal = 16.dp, vertical = 13.dp)) {
                if (input.isEmpty()) Text(if (english) "Type here" else "여기에 적기", color = C.faint, fontSize = 16.sp)
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    textStyle = TextStyle(color = C.text, fontSize = 16.sp),
                    cursorBrush = SolidColor(C.accent),
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        vm.send(input)
                        input = ""
                    }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.width(8.dp))
            if (input.isNotBlank()) {
                Pill(if (english) "Send" else "보내기", selected = true) {
                    vm.send(input)
                    input = ""
                }
            } else {
                if (vm.busy || vm.speaking) {
                    Pill(if (english) "Stop" else "멈춤", color = C.red) { vm.stop() }
                    Spacer(Modifier.width(8.dp))
                }
                // 마이크: 켜져 있으면 들리는 소리 크기만큼 테두리가 밝아진다
                val on = vm.converse
                val glow = if (on) (vm.micLevel * 12f).coerceIn(0f, 1f) else 0f
                Box(
                    Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(if (on) C.accent.copy(alpha = 0.35f + 0.65f * glow) else Color.Transparent)
                        .border(1.5.dp, if (on) C.accent else C.line, CircleShape)
                        .clickable {
                            if (on) {
                                vm.converse(false)
                            } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                vm.converse(true)
                            } else {
                                ask.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (on) (if (english) "On" else "켜짐") else (if (english) "Talk" else "대화"), color = if (on) C.bg else C.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/** 주고받은 말 */
@Composable
private fun TalkLog(vm: KiteViewModel, onBack: () -> Unit) {
    val english = vm.store.lang == "en"
    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (english) "Talk log" else "대화", color = C.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Pill(if (english) "Back" else "돌아가기", onClick = onBack)
        }
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp), reverseLayout = true) {
            items(vm.recent.reversed()) { (user, ali) ->
                Column {
                    Text(user, color = C.dim, fontSize = 15.sp, lineHeight = 21.sp)
                    if (ali.isNotEmpty()) Text(ali, color = C.text, fontSize = 16.sp, lineHeight = 23.sp, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}
