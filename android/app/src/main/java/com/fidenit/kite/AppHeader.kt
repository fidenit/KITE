// Pro 의 앱 머리글 `X-Aperit-App`: 판 이름 + 이 폰의 화면 설정 (memory/specs/aperit-pro-screen-density-v1.md §5).
// 다음에 "너무 크다/작다"고 하면 그 폰의 실제 화면 크기·밀도·글자 배율이 서버 기록(run_start/run_end 의 app)에 있다.
package com.fidenit.kite

import java.util.Locale

/** 게이트웨이가 받는 머리글의 최대 길이 (app.py:34 `^[A-Za-z0-9._-]{1,40}$`, 안 맞으면 서버가 버린다). */
const val APP_HEADER_MAX = 40

private val APP_HEADER = Regex("^[A-Za-z0-9._-]{1,$APP_HEADER_MAX}$")

/**
 * `<판>-pro_w<W>h<H>d<D>f<F>` (W·H 화면 폭·높이 dp, D 밀도, F 글자 크기 배율, 소수 둘째 자리, 언어와 상관없이 점).
 * 서버의 규칙에 맞지 않거나 밀도·배율이 숫자가 아니면 `<판>-pro`.
 */
fun proAppHeader(version: String, widthDp: Int, heightDp: Int, density: Float, fontScale: Float): String {
    val plain = "$version-pro"
    if (!density.isFinite() || !fontScale.isFinite()) return plain
    val full = plain + "_w${widthDp}h${heightDp}d${"%.2f".format(Locale.ROOT, density)}f${"%.2f".format(Locale.ROOT, fontScale)}"
    return if (APP_HEADER.matches(full)) full else plain
}
