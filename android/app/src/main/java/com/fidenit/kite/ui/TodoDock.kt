// "내 차례" 칸 (memory/specs/aperit-pro-grid-dock-v1.md §C2): 상태 줄과 입력 줄 사이, 입력 줄 바로 위.
// 위에서 아래로: 카드(보드와 같은 CardView, 안에서 스크롤) · 줄(● 내 차례 · 주제 · 남은 N건 …… 다음 ›) · 입력 줄.
// 고르기는 core/Todo.kt (화면 모델이 부른다). 붙은 카드가 없으면 그리지 않는다 (자리를 비워 두지 않는다).
package com.fidenit.kite.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.fidenit.kite.KiteViewModel

/** 내 차례 칸의 지금 높이 (px). 칸 안의 글 칸이 높이가 바뀐 뒤에도 보이게 하려고 읽는다 (InputCard). 칸 밖에서는 0. */
val LocalDockHeight = compositionLocalOf { 0 }

/**
 * `maxPx` = 칸(카드 + 줄)이 넘지 않을 높이 (0 이면 아직 모른다), `heightPx` = 지난번에 잰 칸 높이,
 * `onHeight` = 잰 높이를 알린다 (칸이 사라지면 0).
 */
@Composable
fun TodoDock(vm: KiteViewModel, maxPx: Int, heightPx: Int, onHeight: (Int) -> Unit) {
    val rev = vm.rev // 보드가 바뀌면 다시 그린다
    val card = vm.dockedId?.let { vm.board.card(it) } ?: return
    val english = vm.store.lang == "en"
    val applying = vm.dockApplying
    val waiting = vm.waitingAsks().size // 붙은 것까지 센다 (반영하는 중이면 붙은 것은 이미 답했다)
    val topic = vm.board.topics.firstOrNull { it.id == card.topic }?.name.orEmpty()
    val density = LocalDensity.current
    val report by rememberUpdatedState(onHeight)
    DisposableEffect(Unit) { onDispose { report(0) } }
    val cap = if (maxPx > 0) Modifier.heightIn(max = with(density) { maxPx.toDp() }) else Modifier
    CompositionLocalProvider(LocalDockHeight provides heightPx) {
        Column(Modifier.fillMaxWidth().testTag("dock").onSizeChanged { report(it.height) }.then(cap).padding(top = Dim.dockTop)) {
            // 카드: 줄과 입력 줄은 그대로 두고, 카드 위끝만 움직인다. 다음 카드는 겹쳐 바뀐다.
            Box(Modifier.weight(1f, fill = false)) {
                AnimatedContent(
                    targetState = card,
                    contentKey = { it.id },
                    transitionSpec = { fadeIn(tween(Dim.DOCK_FADE_MS)) togetherWith fadeOut(tween(Dim.DOCK_FADE_MS)) using SizeTransform(clip = true) },
                    label = "dock-card",
                ) { c ->
                    // 반영하는 중에는 답한 모양으로 바뀌어도 높이를 줄이지 않는다 (손가락 아래가 튀지 않게)
                    var lastH by remember { mutableIntStateOf(0) }
                    val keep = if (applying && lastH > 0) Modifier.heightIn(min = with(density) { lastH.toDp() }) else Modifier
                    Box(
                        Modifier.fillMaxWidth().testTag("dock-card").then(keep).onSizeChanged { if (!applying) lastH = it.height }
                            .verticalScroll(rememberScrollState()),
                    ) {
                        CardView(vm, c, rev)
                    }
                }
            }
            Spacer(Modifier.height(Dim.dockBarTop))
            DockBar(vm, english, applying, topic, waiting)
        }
    }
}

/** 줄: ● 상태 · 주제 · 남은 N건 …… 다음 ›. 모든 것이 PILL_H 높이, 다음 › 의 누르는 자리도 줄 높이 전부. */
@Composable
private fun DockBar(vm: KiteViewModel, english: Boolean, applying: Boolean, topic: String, waiting: Int) {
    Row(Modifier.fillMaxWidth().testTag("dock-bar").height(Dim.PILL_H), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(Dim.dockDot).clip(CircleShape).background(C.accent))
            Spacer(Modifier.width(Dim.dockGap))
            Text(
                if (applying) (if (english) "Applying" else "반영하는 중") else (if (english) "My turn" else "내 차례"),
                color = C.text,
                fontSize = Dim.dockText,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier.testTag("dock-state").height(Dim.PILL_H).wrapContentHeight(Alignment.CenterVertically).semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (topic.isNotEmpty()) {
                Spacer(Modifier.width(Dim.dockGap))
                Text(
                    topic,
                    color = C.dim,
                    fontSize = Dim.dockText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).height(Dim.PILL_H).wrapContentHeight(Alignment.CenterVertically),
                )
            }
            if (waiting > 0) {
                Spacer(Modifier.width(Dim.dockGap))
                Text(
                    if (english) "$waiting left" else "남은 ${waiting}건",
                    color = C.dim,
                    fontSize = Dim.dockText,
                    maxLines = 1,
                    modifier = Modifier.testTag("dock-count").height(Dim.PILL_H).wrapContentHeight(Alignment.CenterVertically),
                )
            }
        }
        if (waiting >= 2 && !applying) {
            Spacer(Modifier.width(Dim.dockGap))
            Pill(if (english) "Next ›" else "다음 ›", modifier = Modifier.testTag("dock-next")) { vm.dockNext() }
        }
    }
}
