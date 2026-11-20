package com.fidenit.kite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pro 의 `X-Aperit-App` 화면 보고 (memory/specs/aperit-pro-screen-density-v1.md §5). JVM 만. */
class AppHeaderTest {
    @Test
    fun thePixel7ExampleOfTheSpec() {
        assertEquals("0.3.2-pro_w411h914d2.63f1.00", proAppHeader("0.3.2", 411, 914, 2.625f, 1.0f))
    }

    @Test
    fun twoDecimalsWithAPointInEveryLocale() {
        val before = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY) // 소수점이 쉼표인 언어에서도 점
            assertEquals("0.3.2-pro_w360h800d3.00f1.30", proAppHeader("0.3.2", 360, 800, 3f, 1.3f))
        } finally {
            java.util.Locale.setDefault(before)
        }
    }

    @Test
    fun everyBuiltValueMatchesTheServerPatternAndLimit() {
        val pattern = Regex("^[A-Za-z0-9._-]{1,40}$") // gateway app.py:34
        for (h in listOf(
            proAppHeader("0.3.2", 411, 914, 2.625f, 1.0f),
            proAppHeader("0.3.2", 99999, 99999, 10.5f, 2.0f),
            proAppHeader("0.3.2", 0, 0, 0f, 0f),
            proAppHeader("0.3.2-debug", 1280, 2856, 3.5f, 1.15f),
        )) {
            assertTrue(h, pattern.matches(h))
            assertTrue(h, h.length <= 40)
        }
        assertEquals(40, APP_HEADER_MAX)
    }

    @Test
    fun tooLongOrOddFallsBackToTheVersionAlone() {
        // 판 이름이 길어 40자를 넘으면 화면 값 없이
        val long = "0.3.2-rc1-build20261120"
        assertTrue(("$long-pro_w411h914d2.63f1.00").length > 40)
        assertEquals("$long-pro", proAppHeader(long, 411, 914, 2.625f, 1.0f))
        // 밀도·글자 배율이 숫자가 아니면 (무한대, NaN) 화면 값 없이
        assertEquals("0.3.2-pro", proAppHeader("0.3.2", 411, 914, Float.POSITIVE_INFINITY, 1.0f))
        assertEquals("0.3.2-pro", proAppHeader("0.3.2", 411, 914, Float.NaN, 1.0f))
    }
}
