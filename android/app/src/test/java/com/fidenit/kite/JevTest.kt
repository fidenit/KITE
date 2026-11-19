package com.fidenit.kite

import com.fidenit.kite.engine.Jev
import com.fidenit.kite.engine.JevError
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * The first three tests are the desktop's three (src/jev.rs:106, 122, 134) with the same data;
 * the rest run the client against a fake server (never the real api.typesafe.ai).
 */
class JevTest {
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

    private val q = JSONObject().put("urgent", Jev.noul("The message conveys urgency"))

    private val documented = """{"model":"jev-1.13.0","answers":{
            "department":{"type":"choice","choice":"technical","confidence":1.0,"probabilities":{"technical":1.0,"billing":0.0,"sales":0.0}},
            "frustration":{"type":"score","score":0.67,"confidence":0.5,"legend":{"0":"a","1":"b","2":"c"},"probabilities":{"0":0.33,"1":0.67,"2":0.0}},
            "is_urgent":{"type":"noul","noul":0.99}},
            "usage":{"input_tokens":428,"output_tokens":73}}"""

    // desktop jev.rs:106 parses_documented_response
    @Test
    fun parsesDocumentedResponse() {
        val a = Jev.parse(documented)
        assertEquals("technical", a["department"]!!.choice)
        assertEquals(0.67, a["frustration"]!!.value!!, 1e-9)
        assertEquals(0.99, a["is_urgent"]!!.value!!, 1e-9)
        assertTrue(a["is_urgent"]!!.confidence > 0.9) // noul has no confidence: far from 0.5 means sure
        assertEquals(0.5, a["frustration"]!!.confidence, 1e-9)
    }

    // desktop jev.rs:122 builds_documented_request
    @Test
    fun buildsDocumentedRequest() {
        val q = JSONObject()
            .put("team", Jev.choice("Which team should handle this", listOf("billing" to "Payment issues")))
            .put("urgent", Jev.noul("The message conveys urgency"))
            .put("mood", Jev.score("How frustrated", listOf("calm", "angry")))
        assertEquals("choice", q.getJSONObject("team").getString("type"))
        assertEquals("Payment issues", q.getJSONObject("team").getJSONObject("criteria").getString("billing"))
        assertEquals("noul", q.getJSONObject("urgent").getString("type"))
        assertEquals("The message conveys urgency", q.getJSONObject("urgent").getString("instructions"))
        assertEquals(setOf("type", "instructions"), q.getJSONObject("urgent").keys().asSequence().toSet())
        assertEquals("angry", q.getJSONObject("mood").getJSONArray("criteria").getString(1))
    }

    // desktop jev.rs:135 error_body_is_reported
    @Test
    fun errorBodyIsReported() {
        val e = try {
            Jev.parse("""{"error":{"message":"bad key"}}""")
            null
        } catch (e: JevError) {
            e
        }
        assertTrue(e!!.message!!.contains("bad key"))
        assertTrue(try { Jev.parse("not json"); false } catch (e: JevError) { true })
        assertTrue(try { Jev.parse("""{"message":"plain"}""").isEmpty() } catch (e: JevError) { e.message!!.contains("plain") })
    }

    @Test
    fun sendsTheDocumentedHeadersAndBody() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(documented))
        val fails = mutableListOf<String>()
        val a = Jev.decide(" secret-test-key \n", "상황 글", q, url()) { fails.add(it) }
        assertNotNull(a)
        assertEquals(emptyList<String>(), fails)
        assertEquals("technical", a!!["department"]!!.choice)
        val r = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("POST", r.method)
        assertEquals("/v1/systemone", r.path)
        assertEquals("Bearer secret-test-key", r.getHeader("Authorization")) // the key is trimmed
        assertTrue(r.getHeader("Content-Type")!!.startsWith("application/json"))
        val body = JSONObject(r.body.readUtf8())
        assertEquals("jev-latest", body.getString("model"))
        assertEquals("상황 글", body.getString("state"))
        assertEquals("noul", body.getJSONObject("questions").getJSONObject("urgent").getString("type"))
        assertEquals(setOf("model", "state", "questions"), body.keys().asSequence().toSet())
    }

    @Test
    fun noKeyMeansNoCallAndNoValue() {
        assertNull(Jev.decide("   ", "s", q, url()))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun anAddressThatIsNotHttpsIsRefused() {
        assertNull(Jev.decide("k", "s", q, "http://example.com/v1/systemone"))
        assertNull(Jev.decide("k", "s", q, "ftp://example.com/x"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun theLimitIsTwoAndAHalfSeconds() {
        assertEquals(2500L, Jev.LIMIT_MS)
        assertEquals("https://api.typesafe.ai/v1/systemone", Jev.URL)
    }

    @Test
    fun anAnswerLaterThanTheLimitGivesNoValue() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(documented).setBodyDelay(3, TimeUnit.SECONDS))
        val fails = mutableListOf<String>()
        val t0 = System.nanoTime()
        val a = Jev.decide("k", "s", q, url(), limitMs = 400) { fails.add(it) }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertNull(a)
        assertTrue("took $ms ms", ms < 2000) // it did not wait for the 3 s answer
        assertEquals(1, fails.size)
        assertTrue(fails[0], fails[0].contains("400ms"))
    }

    @Test
    fun aServerThatNeverAnswersAtAllGivesNoValue() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        assertNull(Jev.decide("k", "s", q, url(), limitMs = 400))
    }

    @Test
    fun aStatusOtherThan200GivesNoValue() {
        for (code in listOf(401, 429, 500, 204)) server.enqueue(MockResponse().setResponseCode(code).setBody("""{"error":{"message":"nope"}}"""))
        for (code in listOf(401, 429, 500, 204)) {
            val fails = mutableListOf<String>()
            assertNull("HTTP $code", Jev.decide("k", "s", q, url()) { fails.add(it) })
            assertEquals(1, fails.size)
            if (code != 204) assertTrue(fails[0], fails[0].contains("HTTP $code"))
        }
    }

    @Test
    fun aBodyOfTheWrongShapeGivesNoValue() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>oops</html>"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"error":{"message":"bad key"}}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"answers":[1,2]}"""))
        repeat(3) { assertNull(Jev.decide("k", "s", q, url())) }
    }

    @Test
    fun aClosedConnectionGivesNoValueAndDoesNotThrow() {
        server.shutdown()
        assertNull(Jev.decide("k", "s", q, url(), limitMs = 400))
        server = MockWebServer().apply { start() } // so that down() has something to shut down
    }
}
