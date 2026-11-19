package com.fidenit.kite

import com.fidenit.kite.engine.Decide
import com.fidenit.kite.engine.Decide.CardRef
import com.fidenit.kite.engine.Jev
import com.fidenit.kite.engine.JevAnswer
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Decisions around a turn: the desktop's thresholds (src/decide.rs) at their edges with canned answers, no call without a key, the block texts. */
class DecideTest {
    private lateinit var server: MockWebServer

    @Before
    fun up() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun down() {
        server.shutdown()
    }

    private fun url() = server.url("/v1/systemone").toString()

    private fun choice(c: String, conf: Double) = JevAnswer(c, null, conf)

    private fun p(v: Double) = JevAnswer(null, v, Math.abs(v - 0.5) * 2)

    // --- before: thresholds -------------------------------------------------------------

    @Test
    fun effortAndKindNeedConfidenceOfAtLeastHalf() {
        val at = Decide.pre(mapOf("effort" to choice("high", 0.5), "kind" to choice("작업", 0.5)), false)
        assertEquals("high", at.effort)
        assertEquals("작업", at.kind)
        val below = Decide.pre(mapOf("effort" to choice("high", 0.4999), "kind" to choice("작업", 0.4999)), false)
        assertNull(below.effort)
        assertNull(below.kind)
        assertNull(below.ack) // no sure kind, no acknowledgement
    }

    @Test
    fun topicNeedsAtLeastZeroPointSixAndIsNotNew() {
        assertEquals("t2", Decide.pre(mapOf("topic" to choice("t2", 0.6)), false).topic)
        assertNull(Decide.pre(mapOf("topic" to choice("t2", 0.5999)), false).topic)
        assertNull(Decide.pre(mapOf("topic" to choice("new", 1.0)), false).topic) // a new subject: nothing to point at
        assertNull(Decide.pre(emptyMap(), false).topic)
    }

    @Test
    fun anOptionNobodyOfferedIsNotTaken() {
        val pre = Decide.pre(mapOf("effort" to choice("extreme", 1.0), "kind" to choice("딴것", 1.0)), false)
        assertNull(pre.effort)
        assertNull(pre.kind)
    }

    @Test
    fun acknowledgementsFollowTheKindAndTheLanguage() {
        fun ack(kind: String, en: Boolean) = Decide.pre(mapOf("kind" to choice(kind, 0.9)), en).ack
        assertEquals("네, 바로 해볼게요.", ack("작업", false))
        assertEquals("네, 고칠게요.", ack("화면 수정", false))
        assertEquals("잠깐만요, 찾아볼게요.", ack("기억 찾기", false))
        assertNull(ack("대화", false))
        assertEquals("Sure, on it.", ack("작업", true))
        assertEquals("Okay, updating.", ack("화면 수정", true))
        assertEquals("One moment, let me look.", ack("기억 찾기", true))
        assertNull(ack("대화", true))
    }

    // --- before: the call ---------------------------------------------------------------

    private fun answer(vararg parts: String) = MockResponse().setResponseCode(200).setBody("""{"answers":{${parts.joinToString(",")}}}""")

    @Test
    fun beforeAsksTheDesktopQuestionsAndReadsTheAnswers() {
        server.enqueue(
            answer(
                """"effort":{"type":"choice","choice":"medium","confidence":0.8}""",
                """"kind":{"type":"choice","choice":"작업","confidence":0.9}""",
                """"topic":{"type":"choice","choice":"t2","confidence":0.7}""",
            ),
        )
        val logs = mutableListOf<String>()
        val pre = Decide.before(" k ", "항공권 찾아 줘", listOf("t1" to "여행", "t2" to "일"), "사용자: 안녕\n응답: 네\n", false, url(), log = { logs.add(it) })!!
        assertEquals("medium", pre.effort)
        assertEquals("작업", pre.kind)
        assertEquals("t2", pre.topic)
        assertEquals("네, 바로 해볼게요.", pre.ack)
        val r = server.takeRequest(1, TimeUnit.SECONDS)!!
        val body = JSONObject(r.body.readUtf8())
        assertEquals("사용자가 방금 한 말:\n항공권 찾아 줘\n\n직전 대화:\n사용자: 안녕\n응답: 네\n\n\n지금 화면에 있는 주제:\n- t1: 여행\n- t2: 일\n", body.getString("state"))
        val qs = body.getJSONObject("questions")
        assertEquals(setOf("effort", "kind", "topic"), qs.keys().asSequence().toSet())
        assertEquals("이 요청을 제대로 처리하는 데 필요한 생각의 깊이", qs.getJSONObject("effort").getString("instructions"))
        assertEquals(setOf("low", "medium", "high"), qs.getJSONObject("effort").getJSONObject("criteria").keys().asSequence().toSet())
        assertEquals(setOf("대화", "화면 수정", "작업", "기억 찾기"), qs.getJSONObject("kind").getJSONObject("criteria").keys().asSequence().toSet())
        assertEquals("이 요청의 종류", qs.getJSONObject("kind").getString("instructions"))
        val topic = qs.getJSONObject("topic")
        assertEquals("이 말이 속하는 주제", topic.getString("instructions"))
        assertEquals("여행", topic.getJSONObject("criteria").getString("t1"))
        assertEquals("위 주제 어디에도 속하지 않는 새로운 이야기", topic.getJSONObject("criteria").getString("new"))
        assertTrue(logs.joinToString("\n"), logs.single().startsWith("앞 ") && logs.single().contains("항공권 찾아 줘") && !logs.single().contains("k "))
    }

    @Test
    fun beforeWithoutTopicsDoesNotAskForATopic() {
        server.enqueue(answer(""""kind":{"type":"choice","choice":"대화","confidence":0.9}"""))
        val pre = Decide.before("k", "안녕", emptyList(), "", false, url())!!
        assertEquals("대화", pre.kind)
        val body = JSONObject(server.takeRequest(1, TimeUnit.SECONDS)!!.body.readUtf8())
        assertEquals("사용자가 방금 한 말:\n안녕\n", body.getString("state"))
        assertFalse(body.getJSONObject("questions").has("topic"))
    }

    @Test
    fun noKeyOrNoInputMeansNoCall() {
        assertNull(Decide.before("", "안녕", emptyList(), "", false, url()))
        assertNull(Decide.before("   ", "안녕", emptyList(), "", false, url()))
        assertNull(Decide.before("k", "", emptyList(), "", false, url()))
        assertNull(Decide.before("k", "  \n", emptyList(), "", false, url()))
        assertNull(Decide.after("", "topic t x", listOf(CardRef("c1", "t", "제목")), url()))
        assertNull(Decide.after("k", "topic t x", emptyList(), url())) // nothing touched, nothing to ask
        assertEquals(0, server.requestCount)
    }

    @Test
    fun aFailedCallGivesNoDecisionAndIsLogged() {
        server.enqueue(MockResponse().setResponseCode(500))
        val logs = mutableListOf<String>()
        assertNull(Decide.before("k", "안녕", emptyList(), "", false, url(), log = { logs.add(it) }))
        assertEquals(1, logs.size)
        assertTrue(logs[0], logs[0].startsWith("실패: Jev HTTP 500"))
    }

    @Test
    fun recentTextKeepsTheLastThreeTurns() {
        val r = (1..5).map { "말$it" to "답$it" }
        assertEquals("사용자: 말3\n응답: 답3\n사용자: 말4\n응답: 답4\n사용자: 말5\n응답: 답5\n", Decide.recentText(r))
        assertEquals("", Decide.recentText(emptyList()))
    }

    // --- after: thresholds --------------------------------------------------------------

    private val cards = listOf(CardRef("c1", "여행", "항공편"), CardRef("c2", "여행", "메모"))

    @Test
    fun aCardIsUnclearOnlyBelowZeroPointFour() {
        fun unclear(v: Double) = Decide.post(mapOf("clear_c1" to p(v)), cards).unclear
        assertEquals(listOf("c1" to 0.39), unclear(0.39))
        assertEquals(emptyList<Pair<String, Double>>(), unclear(0.4))
        assertEquals(emptyList<Pair<String, Double>>(), unclear(0.41))
        assertEquals(listOf("c1" to 0.0), unclear(0.0))
        // a card Jev said nothing about is not unclear
        assertEquals(emptyList<Pair<String, Double>>(), Decide.post(emptyMap(), cards).unclear)
    }

    @Test
    fun importanceIsTheScoreOverTwoAndStaysInZeroToOne() {
        val post = Decide.post(mapOf("imp_c1" to p(0.0), "imp_c2" to p(1.0), "imp_c3" to p(2.0), "imp_c4" to p(3.5)), listOf(CardRef("c1", "t", "a"), CardRef("c2", "t", "b"), CardRef("c3", "t", "c"), CardRef("c4", "t", "d")))
        assertEquals(0.0, post.importance["c1"]!!, 1e-9)
        assertEquals(0.5, post.importance["c2"]!!, 1e-9)
        assertEquals(1.0, post.importance["c3"]!!, 1e-9)
        assertEquals(1.0, post.importance["c4"]!!, 1e-9)
        assertNull(Decide.post(emptyMap(), cards).importance["c1"])
    }

    @Test
    fun importanceFallsIntoThreeOrderLevels() {
        assertEquals(0, Decide.level(0.0))
        assertEquals(0, Decide.level(0.33)) // 0.33 * 2.99 = 0.98
        assertEquals(1, Decide.level(0.34)) // 1.01
        assertEquals(1, Decide.level(0.5))
        assertEquals(1, Decide.level(null)) // no score yet: the middle
        assertEquals(1, Decide.level(0.66)) // 1.97
        assertEquals(2, Decide.level(0.67)) // 2.003
        assertEquals(2, Decide.level(1.0))
    }

    @Test
    fun onlyTheFirstTwentyCardsAreAsked() {
        val many = (1..25).map { CardRef("c$it", "t", "카드$it") }
        server.enqueue(answer(""""clear_c1":{"type":"noul","noul":0.1}"""))
        Decide.after("k", "topic t x\n", many, url())!!
        val qs = JSONObject(server.takeRequest(1, TimeUnit.SECONDS)!!.body.readUtf8()).getJSONObject("questions")
        assertEquals(40, qs.length()) // clear_ and imp_ for 20 cards
        assertTrue(qs.has("clear_c20") && qs.has("imp_c20"))
        assertFalse(qs.has("clear_c21"))
    }

    @Test
    fun afterAsksTheDesktopQuestionsAndReadsTheAnswers() {
        server.enqueue(
            answer(
                """"clear_c1":{"type":"noul","noul":0.2}""",
                """"imp_c1":{"type":"score","score":2.0,"confidence":0.9}""",
                """"clear_c2":{"type":"noul","noul":0.9}""",
                """"imp_c2":{"type":"score","score":0.0,"confidence":0.9}""",
            ),
        )
        val logs = mutableListOf<String>()
        val post = Decide.after("k", "topic t1 여행\ncard c1 note t1 3x2 항공편\n", cards, url(), log = { logs.add(it) })!!
        assertEquals(listOf("c1" to 0.2), post.unclear)
        assertEquals(1.0, post.importance["c1"]!!, 1e-9)
        assertEquals(0.0, post.importance["c2"]!!, 1e-9)
        val body = JSONObject(server.takeRequest(1, TimeUnit.SECONDS)!!.body.readUtf8())
        assertEquals("한 화면에 여러 주제의 카드가 놓여 있다. 사용자는 대화 과정을 보지 않고 이 화면만 본다. 아래는 화면 전체를 명령 형식으로 적은 것이다.\n\ntopic t1 여행\ncard c1 note t1 3x2 항공편\n", body.getString("state"))
        val qs = body.getJSONObject("questions")
        assertEquals("noul", qs.getJSONObject("clear_c1").getString("type"))
        assertEquals("'여행' 주제의 카드 '항공편'(c1)은 주제 이름과 카드 내용만 보고도 무엇에 관한 것인지 분명하다", qs.getJSONObject("clear_c1").getString("instructions"))
        assertEquals("'여행' 주제의 카드 '메모'(c2)이 지금 사용자에게 중요한 정도", qs.getJSONObject("imp_c2").getString("instructions"))
        assertEquals("지금 꼭 봐야 하거나 사용자의 결정이 필요하다", qs.getJSONObject("imp_c2").getJSONArray("criteria").getString(2))
        assertEquals(3, qs.getJSONObject("imp_c1").getJSONArray("criteria").length())
        assertTrue(logs.single(), logs.single().startsWith("뒤 ") && logs.single().contains("카드 2장"))
    }

    // --- the text blocks ----------------------------------------------------------------

    @Test
    fun reviewBlockText() {
        assertNull(Decide.reviewBlock(emptyList()))
        assertEquals(
            "<review>\ncard c1: hard to tell what it is about from the screen alone (probability it is clear: 0.20)\ncard c2: hard to tell what it is about from the screen alone (probability it is clear: 0.05)\n</review>\n",
            Decide.reviewBlock(listOf("c1" to 0.2, "c2" to 0.05)),
        )
    }

    @Test
    fun alreadySaidBlockText() {
        assertEquals("\n<already_said>네, 바로 해볼게요.</already_said>", Decide.alreadySaid("네, 바로 해볼게요."))
    }
}
