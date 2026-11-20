package com.fidenit.kite

import com.fidenit.kite.engine.StateClient
import com.fidenit.kite.engine.StateMarks
import com.fidenit.kite.engine.StateSync
import com.fidenit.kite.engine.StateSync.Outcome
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.util.concurrent.TimeUnit

/** Pro 의 상태 맞추기 규칙 (spec §4, 게이트웨이가 실제로 답하는 모양: 412 `rev_conflict`, 428, 404). JVM 만 (free 변형에서 돈다). */
class StateSyncTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    private val server = MockWebServer()
    private val tok = "apd_" + "S".repeat(43)

    private class Marks : StateMarks {
        override var stateRev = ""
        override var stateDirty = ""
    }

    private val applied = mutableListOf<JSONObject>()
    private var conflicts = 0
    private var canApply = true

    @Before
    fun up() = server.start()

    @After
    fun down() = server.shutdown()

    private fun sync(m: Marks, debounce: Long = 0) = StateSync(StateClient(server.url("/").toString(), { tok }), m, { d -> if (canApply) applied.add(d) else false; canApply }, { conflicts++ }, debounce)

    private fun doc(board: String) = JSONObject().put("v", 1).put("board", board)

    private fun remote(rev: Int, board: String) = MockResponse().setBody(JSONObject().put("rev", rev).put("saved_at", "2026-11-20T05:00:00.000Z").put("doc", doc(board)).toString())

    @Test
    fun savesAreDebouncedIntoOnePutOnTheLastRev() {
        val m = Marks()
        val s = sync(m, debounce = 300)
        server.enqueue(MockResponse().setBody("""{"rev":1}"""))
        s.changed(doc("a"))
        s.changed(doc("b"))
        val put = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertTrue(s.drain())
        assertEquals("PUT", put.method)
        assertEquals("/v1/state", put.path)
        assertEquals("\"0\"", put.getHeader("If-Match"))
        assertEquals("Bearer $tok", put.getHeader("Authorization"))
        assertEquals("b", JSONObject(put.body.readUtf8()).getString("board"))
        assertEquals(1, server.requestCount)
        assertEquals("1", m.stateRev)
        assertEquals("", m.stateDirty)
    }

    @Test
    fun startTakesTheServerWhenThePhoneHasNothingOrAnOlderRev() {
        val m = Marks()
        server.enqueue(remote(5, "server"))
        assertEquals(Outcome.PULLED, sync(m).pullNow(emptyPhone = true))
        assertEquals("server", applied.single().getString("board"))
        assertEquals("5", m.stateRev)

        m.stateRev = "3"
        server.enqueue(remote(6, "newer"))
        assertEquals(Outcome.PULLED, sync(m).pullNow(emptyPhone = false))
        assertEquals("6", m.stateRev)
        assertEquals("newer", applied.last().getString("board"))
    }

    @Test
    fun startWithTheSameRevUploadsOnlyUnsentChanges() {
        val m = Marks()
        m.stateRev = "4"
        server.enqueue(remote(4, "same"))
        val s = sync(m)
        assertEquals(Outcome.NONE, s.pullNow(false))
        assertTrue(applied.isEmpty())

        m.stateDirty = "1"
        val t = sync(m, debounce = 60_000)
        t.changed(doc("local"))
        server.enqueue(remote(4, "same"))
        server.enqueue(MockResponse().setBody("""{"rev":5}"""))
        assertEquals(Outcome.PUSHED, t.pullNow(false))
        server.takeRequest()
        server.takeRequest()
        val put = server.takeRequest()
        assertEquals("\"4\"", put.getHeader("If-Match"))
        assertEquals("5", m.stateRev)
    }

    @Test
    fun nothingOnTheServerUploadsThePhone() {
        val m = Marks()
        val s = sync(m, debounce = 60_000)
        s.changed(doc("mine"))
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"not_found"}"""))
        server.enqueue(MockResponse().setBody("""{"rev":1}"""))
        assertEquals(Outcome.PUSHED, s.pullNow(false))
        assertEquals("1", m.stateRev)
    }

    @Test
    fun aConflictTakesTheServersCopyAndSaysSo() {
        val m = Marks()
        m.stateRev = "2"
        val s = sync(m, debounce = 60_000)
        s.changed(doc("phone"))
        server.enqueue(MockResponse().setResponseCode(412).setBody("""{"error":"rev_conflict","rev":7}"""))
        server.enqueue(remote(7, "server7"))
        assertEquals(Outcome.CONFLICT, s.pushNow())
        assertEquals(1, conflicts)
        assertEquals("server7", applied.single().getString("board"))
        assertEquals("7", m.stateRev)
        assertEquals("", m.stateDirty)
        assertEquals(Outcome.NONE, s.pushNow()) // nothing left to send
    }

    @Test
    fun aBusyPhoneDefersAndARefusalDoesNotLoop() {
        val m = Marks()
        canApply = false
        server.enqueue(remote(9, "later"))
        assertEquals(Outcome.DEFERRED, sync(m).pullNow(true))
        assertEquals("", m.stateRev)

        val s = sync(m, debounce = 60_000)
        s.changed(doc("x"))
        server.enqueue(MockResponse().setResponseCode(428).setBody("""{"error":"if_match_required"}"""))
        assertEquals(Outcome.REFUSED, s.pushNow())
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"pending","code":"123456"}"""))
        assertEquals(Outcome.REFUSED, s.pushNow())
        assertEquals("1", m.stateDirty) // still to be sent later
    }

    @Test
    fun aGatewayThatIsDownIsRetriedLater() {
        val m = Marks()
        val s = sync(m, debounce = 60_000)
        s.changed(doc("x"))
        server.enqueue(MockResponse().setResponseCode(503))
        assertEquals(Outcome.FAILED, s.pushNow())
        server.enqueue(MockResponse().setBody("""{"rev":1}"""))
        assertEquals(Outcome.PUSHED, s.pushNow())
        assertEquals("1", m.stateRev)
    }
}
