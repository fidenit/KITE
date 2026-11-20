// 화면의 모든 크기 (글자 sp, 간격·모양 dp). "조금 더 작게/크게"는 여기 한 줄만 고친다.
// 값은 memory/specs/aperit-pro-screen-density-v1.md §2 의 '후' 값. §2 에 없는 것은 0.3.1 값 그대로 (주석 '그대로').
package com.fidenit.kite.ui

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Dim {
    // 글자
    val topic = 15.sp
    val title = 15.sp
    val body = 14.sp
    val bodyLine = 20.sp
    val small = 12.sp
    val smallPlus = 13.sp
    val optTitle = 14.sp
    val optDesc = 13.sp
    val optDescLine = 18.sp
    val metric = 30.sp
    val metricUnit = 13.sp
    val metricDelta = 13.sp
    val routeTotal = 17.sp
    val say = 15.sp
    val sayLine = 21.sp
    val status = 12.sp
    val input = 15.sp
    val pill = 13.sp
    val talk = 12.sp
    val error = 13.sp
    val pageTitle = 17.sp
    val logUser = 13.sp
    val logUserLine = 18.sp
    val logReply = 14.sp
    val logReplyLine = 20.sp
    val emptyHint = 14.sp
    val emptyHintLine = 20.sp
    val drawS = 12.sp // TEXT_FLOOR 아래로 내리지 않는다 (grid-dock §A2)
    val drawM = 12.sp
    val drawL = 15.sp

    // 카드
    val cardPad = 12.dp
    val cardCorner = 16.dp
    val cardGap = 8.dp

    // 여러 칸 보드 (memory/specs/aperit-pro-grid-dock-v1.md §A1·A5). 종류별 최소 폭 표는 core/BoardLayout.kt 의 Fit.
    val GRID_V_PHONE = 6 // 600 dp 아래: 한 줄의 PATET 폭 합 한도
    val GRID_V_MID = 9 // 600~839 dp
    val GRID_V_WIDE = 12 // 840 dp 부터
    val GRID_MID_DP = 600
    val GRID_WIDE_DP = 840
    val TEXT_FLOOR = 12.sp // 보드의 어떤 글자도 이보다 작지 않다
    val FLASH_MS = 1200 // 가리킨 카드 테두리가 사라지는 시간
    val POINT_WAIT_MS = 1000L // 가리키기: 새 주제의 줄이 놓일 때까지 기다리는 한도 (넘으면 하지 않는다)
    val metricSteps = listOf(metric, 26.sp, 22.sp, 20.sp) // 숫자가 한 줄에 안 들어가면 차례로 줄이고, 20 에서도 넘치면 줄을 바꾼다
    val flashStroke = 2.dp
    val listPadH = 10.dp
    val listPadV = 4.dp
    val titleGap = 6.dp
    val inGap = 6.dp
    val dot = 8.dp
    val dotGap = 8.dp
    val checkBox = 18.dp
    val checkCorner = 5.dp
    val checkRowV = 6.dp
    val checkGap = 10.dp
    val checkRowCorner = 10.dp // 그대로
    val rowV = 6.dp
    val headPadB = 4.dp
    val cellGap = 8.dp // 그대로
    val recordFieldV = 4.dp // 그대로
    val optPad = 10.dp
    val optCorner = 12.dp
    val optGap = 6.dp
    val timeRowV = 4.dp
    val timeCol = 72.dp
    val timeDotTop = 6.dp // 그대로
    val keyCol = 88.dp
    val metricUnitStart = 6.dp // 그대로
    val metricDeltaStart = 12.dp // 그대로
    val metricBaseline = 7.dp // 그대로
    val noteTop = 2.dp // 그대로
    val progressH = 5.dp
    val progressCorner = 3.dp // 그대로
    val chartBar = 6.dp
    val chartBarCorner = 4.dp // 그대로
    val chartBarTop = 4.dp // 그대로
    val chartRowV = 5.dp // 그대로
    val lineChart = 110.dp
    val donut = 88.dp
    val donutStroke = 14.dp
    val donutGap = 16.dp // 그대로
    val legendRowV = 3.dp // 그대로
    val sentRowV = 4.dp // 그대로
    val choiceLabelTop = 4.dp // 그대로
    val choiceLabelBottom = 6.dp // 그대로
    val choiceGap = 8.dp // 그대로
    val submitTop = 4.dp // 그대로

    // 알약 버튼 (아래 줄 밖의 모든 알약)
    val PILL_H = 32.dp
    val pillPadH = 12.dp

    // 글 칸 (입력 카드·설정이 같이 쓴다)
    val FIELD_H = 44.dp
    val fieldCorner = 10.dp
    val fieldPadH = 12.dp
    val fieldPadV = 10.dp // 여러 줄 칸(설정의 개인 키)의 위아래
    val fieldLabel = 8.dp // 테두리 위에 뜬 이름이 칸 위로 나오는 만큼 (Material 과 같은 값)
    val fieldGap = 4.dp // 칸 아래 (칸 위끝에서 다음 칸 위끝까지 8 + 44 + 4 = 56)

    // 위 줄
    val topPadH = 12.dp // 그대로
    val topPadTop = 6.dp
    val topPadBottom = 4.dp
    val topicPadH = 6.dp
    val topicCorner = 10.dp // 그대로
    val topicGap = 8.dp // 그대로
    val topPillGap = 6.dp // 그대로
    val topTopicsGap = 8.dp // 그대로

    // 빨간 상자
    val errPadH = 10.dp
    val errPadV = 4.dp
    val errInner = 10.dp
    val errCorner = 12.dp

    // 아래 줄
    val BAR_H = 44.dp
    val barPadH = 12.dp
    val barPadV = 8.dp
    val barRowTop = 6.dp
    val statusTop = 2.dp
    val barGap = 8.dp // 그대로
    val inputCorner = 22.dp
    val inputPadH = 14.dp
    val inputPadV = 10.dp // 두 줄부터 (한 줄일 때는 BAR_H 안에 가운데)

    // 대화 기록
    val logPadH = 14.dp // 그대로
    val logTitleV = 10.dp // 그대로
    val logGap = 12.dp // 그대로
    val logReplyTop = 2.dp // 그대로

    // 설정 (§2 에 없음: 그대로)
    val setPadH = 16.dp
    val setPadV = 10.dp
    val setSectionTop = 22.dp
    val setSectionBottom = 8.dp
    val setSection = 18.sp
    val setLabel = 15.sp
    val setSmall = 14.sp
    val setResultLine = 20.sp
    val hint = 13.sp
    val hintLine = 19.sp
    val hintTop = 2.dp
    val hintBottom = 6.dp
    val keyText = 11.sp
    val keyTextLine = 15.sp
    val keyTextV = 6.dp
    val setGap = 8.dp
    val setLabelTop = 8.dp
    val setSliderTop = 10.dp
    val setStateTop = 4.dp
    val setChoiceV = 4.dp
    val setEnd = 40.dp

    // 내 차례 칸 (memory/specs/aperit-pro-grid-dock-v1.md §C2): 상태 줄과 아래 입력 줄 사이. 줄의 모든 것은 PILL_H 높이
    val DOCK_MAX = 0.6f // 칸(카드 + 줄) ≤ 이 비율 × (보드 목록 + 칸), 보드에 다른 카드가 있을 때. 없으면 입력 줄 위 전부
    val DOCK_FADE_MS = 200 // 다음 카드가 들어오는 시간
    val DOCK_ARM_MS = 300L // 방금 붙은 카드가 누름을 받기 시작할 때까지
    val dockTop = 6.dp // 상태 줄과 카드 사이
    val dockBarTop = 4.dp // 카드와 줄 사이
    val dockText = 13.sp
    val dockDot = 6.dp
    val dockGap = 6.dp
    val topicDot = 5.dp // 위 줄: 칸에 붙은 카드의 주제
    val topicDotTop = 5.dp
    val topicDotEnd = 1.dp
}
