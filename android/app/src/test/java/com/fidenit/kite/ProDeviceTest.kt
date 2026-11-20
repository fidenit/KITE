package com.fidenit.kite

import com.fidenit.kite.engine.DeviceKeys
import com.fidenit.kite.engine.Enroller
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** Pro 의 기기 등록 흐름: 처음 켜면 이름표·비밀 글을 만들고 등록, 그 뒤에는 `/v1/me`. JVM 만. */
class ProDeviceTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    private class Mem : DeviceStore {
        override var deviceId = ""
        override var deviceSecret = ""
        override var proStatus = ""
        override var proCode = ""
        override var ticketUsed = ""
    }

    private val server = MockWebServer()

    @Before
    fun up() = server.start()

    @After
    fun down() = server.shutdown()

    /** 시험의 시계: 기다림 규칙을 건너뛰려면 앞으로 돌린다. */
    private var clock = 1_000_000L

    private fun dev(m: Mem, ticket: String = "", gap: Long = 0, model: String = "Pixel 8") =
        ProDevice(m, Enroller(server.url("/").toString()), { Triple(model, "14", "0.2.0") }, ticket, { clock }, gap)

    @Test
    fun theBuildTicketIsSentOnlyUntilApproved() {
        val m = Mem()
        val d = dev(m, "tkt-9")
        // ticket used up on the server: pending, so the ticket is sent again on the next enroll (still not approved)
        server.enqueue(MockResponse().setBody("""{"status":"pending","code":"482913"}"""))
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setBody("""{"status":"approved"}"""))
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setBody("""{"status":"pending","code":"111111"}"""))
        assertEquals("pending", d.check().status)
        assertEquals("482913", m.proCode)
        assertEquals("approved", d.check().status) // pending expired on the server (401) -> enroll again by itself
        assertEquals("1", m.ticketUsed)
        assertEquals("pending", d.check().status) // forgotten after approval: enroll again WITHOUT the ticket
        assertEquals("111111", m.proCode)
        assertEquals("tkt-9", JSONObject(server.takeRequest().body.readUtf8()).getString("ticket"))
        assertEquals("/v1/me", server.takeRequest().path)
        assertEquals("tkt-9", JSONObject(server.takeRequest().body.readUtf8()).getString("ticket"))
        assertEquals("/v1/me", server.takeRequest().path)
        assertFalse(JSONObject(server.takeRequest().body.readUtf8()).has("ticket"))
    }

    @Test
    fun aRejectedTicketLeavesThePhonePendingWithTheCodeAndIsNotSentAgain() {
        val m = Mem()
        val d = dev(m, "tkt-9")
        server.enqueue(MockResponse().setBody("""{"status":"pending","code":"482913","ticket":"used"}"""))
        server.enqueue(MockResponse().setResponseCode(401)) // pending expired after 30 minutes
        server.enqueue(MockResponse().setBody("""{"status":"pending","code":"777001"}"""))
        assertEquals("pending", d.check().status)
        assertEquals("used", d.ticketRejected)
        assertEquals("482913", m.proCode)
        assertEquals("1", m.ticketUsed)
        assertEquals("pending", d.check().status)
        assertEquals("", d.ticketRejected) // no ticket sent this time
        assertEquals("777001", m.proCode)
        assertEquals("tkt-9", JSONObject(server.takeRequest().body.readUtf8()).getString("ticket"))
        assertEquals("/v1/me", server.takeRequest().path)
        assertFalse(JSONObject(server.takeRequest().body.readUtf8()).has("ticket"))
        // a valid ticket: approved at once, nothing to show
        val m2 = Mem()
        server.enqueue(MockResponse().setBody("""{"status":"approved","ticket":"ok"}"""))
        val d2 = dev(m2, "tkt-10")
        assertEquals("approved", d2.check().status)
        assertEquals("", d2.ticketRejected)
        assertEquals("1", m2.ticketUsed)
        assertTrue(com.fidenit.kite.engine.GatewayText.ticketRejected("expired", "482913", false).contains("482913"))
    }

    @Test
    fun firstLaunchMakesTheIdentityOnceAndEnrolls() {
        val m = Mem()
        val d = dev(m)
        server.enqueue(MockResponse().setBody("""{"status":"pending","code":"4821"}"""))
        server.enqueue(MockResponse().setBody("""{"status":"approved","code":"","role":"owner"}"""))
        assertEquals("pending", d.check().status)
        assertEquals("pending", m.proStatus)
        assertEquals("4821", m.proCode)
        assertFalse(d.approved)
        val id = m.deviceId
        val secret = m.deviceSecret
        assertTrue(Regex("apd_[A-Za-z0-9_-]{43}").matches(secret))
        assertEquals("approved", d.check().status)
        assertTrue(d.approved)
        assertEquals("", m.proCode)
        assertEquals(id, m.deviceId) // never remade
        assertEquals(secret, m.deviceSecret)
        assertEquals(id.take(8), d.shortId)
        val r1 = server.takeRequest()
        assertEquals("/v1/enroll", r1.path)
        val j = JSONObject(r1.body.readUtf8())
        assertEquals(id, j.getString("device_id"))
        assertEquals(DeviceKeys.sha256(secret), j.getString("token_sha256"))
        assertEquals("Pixel 8", j.getString("model"))
        val r2 = server.takeRequest()
        assertEquals("/v1/me", r2.path)
        assertEquals("Bearer $secret", r2.getHeader("Authorization"))
    }

    @Test
    fun forgottenDeviceEnrollsAgainAndConflictMakesANewIdentity() {
        val m = Mem().apply { deviceId = "11111111-1111-4111-8111-111111111111"; deviceSecret = "apd_old"; proStatus = "approved" }
        val d = dev(m)
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"unknown"}"""))
        server.enqueue(MockResponse().setResponseCode(409))
        server.enqueue(MockResponse().setBody("""{"status":"pending","code":"0042"}"""))
        assertEquals("pending", d.check().status)
        assertEquals("/v1/me", server.takeRequest().path)
        assertEquals("11111111-1111-4111-8111-111111111111", JSONObject(server.takeRequest().body.readUtf8()).getString("device_id"))
        val third = JSONObject(server.takeRequest().body.readUtf8())
        assertTrue(third.getString("device_id") != "11111111-1111-4111-8111-111111111111")
        assertEquals(m.deviceId, third.getString("device_id"))
        assertTrue(m.deviceSecret != "apd_old")
        assertEquals("0042", m.proCode)
    }

    @Test
    fun rateLimitKeepsTheLastStatusAndWaitsBeforeAskingAgain() {
        val m = Mem().apply { deviceId = "x"; deviceSecret = "apd_s"; proStatus = "pending"; proCode = "4821" }
        val d = dev(m)
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":"rate_limited"}"""))
        assertEquals("limited", d.check().status)
        assertEquals("pending", m.proStatus)
        assertEquals("4821", m.proCode)
        // within the back-off (15 s) nothing is asked, whatever the callers do
        repeat(5) { assertEquals("limited", d.check().status) }
        clock += 14_000
        assertEquals("limited", d.check().status)
        assertEquals(1, server.requestCount)
        clock += 2_000
        server.enqueue(MockResponse().setResponseCode(429))
        assertEquals("limited", d.check().status)
        assertEquals(2, server.requestCount)
        clock += 20_000 // second back-off is 30 s
        assertEquals("limited", d.check().status)
        assertEquals(2, server.requestCount)
        // offline: no exception, status offline, the stored state is kept
        server.shutdown()
        clock += 60_000
        assertEquals("offline", d.check().status)
        assertEquals("pending", m.proStatus)
    }

    @Test
    fun rapidCallsMakeOneExchange() {
        val m = Mem()
        val d = dev(m, gap = 10_000)
        server.enqueue(MockResponse().setBody("""{"status":"approved","code":""}"""))
        val threads = (1..8).map { Thread { d.check() } }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        repeat(10) { assertEquals("approved", d.check().status) }
        assertEquals(1, server.requestCount)
        clock += 10_001
        server.enqueue(MockResponse().setBody("""{"status":"approved","code":""}"""))
        assertEquals("approved", d.check().status)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun enrollRefusalsAreNotRetriedFastAndFieldsAreCleaned() {
        for (code in listOf(400, 413)) {
            val m = Mem()
            val d = dev(m)
            server.enqueue(MockResponse().setResponseCode(code).setBody("""{"error":"bad_request"}"""))
            assertEquals("refused", d.check().status)
            clock += 3_600_000
            assertEquals("refused", d.check().status) // never asked again in this run
        }
        val m5 = Mem()
        val d5 = dev(m5)
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"internal"}"""))
        assertEquals("error", d5.check().status)
        assertEquals("error", d5.check().status) // back-off
        assertEquals(3, server.requestCount)
        clock += 16_000
        server.enqueue(MockResponse().setBody("""{"status":"pending","code":"123456"}"""))
        assertEquals("pending", d5.check().status)
        repeat(4) { server.takeRequest() }
        // a model name with a control character still enrolls (cleaned before sending)
        val mc = Mem()
        server.enqueue(MockResponse().setBody("""{"status":"pending","code":"654321"}"""))
        assertEquals("pending", dev(mc, model = "Pixel\u0000 8\u001f\u007f" + "x".repeat(300)).check().status)
        val sent = JSONObject(server.takeRequest().body.readUtf8()).getString("model")
        assertEquals(("Pixel 8" + "x".repeat(300)).take(200), sent)
        assertEquals("Pixel 8", com.fidenit.kite.engine.cleanField("Pixel\n 8"))
    }

    @Test
    fun twoThreadsAtFirstLaunchMakeOneIdentity() {
        repeat(20) {
            val m = Mem()
            val d = dev(m)
            val a = Thread { d.ensure() }
            val b = Thread { d.ensure() }
            a.start(); b.start(); a.join(); b.join()
            val id = m.deviceId
            val secret = m.deviceSecret
            d.ensure()
            assertEquals(id, m.deviceId)
            assertEquals(secret, m.deviceSecret)
            assertTrue(secret.startsWith("apd_"))
        }
    }

    @Test
    fun aSecond409DoesNotMakeIdentitiesForever() {
        val m = Mem()
        val d = dev(m)
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":"conflict"}"""))
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":"conflict"}"""))
        assertEquals("conflict", d.check().status)
        val id = m.deviceId
        clock += 3_600_000
        assertEquals("conflict", d.check().status)
        assertEquals(id, m.deviceId)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun aTicketWithoutVerdictIsSentTwiceAtMost() {
        val m = Mem()
        val d = dev(m, "tkt-9")
        server.enqueue(MockResponse().setBody("""{"status":"pending","code":"111110"}"""))
        repeat(2) {
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setBody("""{"status":"pending","code":"11111$it"}"""))
        }
        d.check() // enroll #1 with ticket (no verdict)
        d.check() // me 401 -> enroll #2 with ticket (no verdict)
        d.check() // me 401 -> enroll #3 without ticket
        val bodies = (1..5).map { server.takeRequest() }.filter { it.path == "/v1/enroll" }.map { JSONObject(it.body.readUtf8()) }
        assertEquals(listOf(true, true, false), bodies.map { it.has("ticket") })
    }

    @Test
    fun memoryOnlyStoreNeverEnrolls() {
        val m = object : DeviceStore {
            override var deviceId = ""
            override var deviceSecret = ""
            override var proStatus = ""
            override var proCode = ""
            override var ticketUsed = ""
            override val memoryOnly = true
        }
        val d = ProDevice(m, Enroller(server.url("/").toString()), { Triple("", "", "") })
        assertEquals("nostore", d.check().status)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun refusalsFromTurnsUpdateTheStatus() {
        val m = Mem().apply { deviceId = "x"; deviceSecret = "apd_s"; proStatus = "approved" }
        val d = dev(m)
        d.refused("pending", "7777")
        assertEquals("pending" to "7777", m.proStatus to m.proCode)
        d.refused("revoked", "")
        assertEquals("revoked", m.proStatus)
        d.refused("unknown", "")
        assertEquals("", m.proStatus)
    }

    @Test
    fun aSendMayForceOneFreshCheckAfterAFailure() {
        val m = Mem().apply { deviceId = "x"; deviceSecret = "apd_s"; proStatus = "approved" }
        val d = dev(m)
        server.enqueue(MockResponse().setResponseCode(503))
        assertEquals("error", d.check().status) // back-off 15 s starts
        clock += 5_000
        assertEquals("error", d.check(force = true).status) // last attempt only 5 s ago: still cached
        assertEquals(1, server.requestCount)
        clock += 11_000
        server.enqueue(MockResponse().setBody("""{"status":"approved","code":""}"""))
        assertEquals("approved", d.check(force = true).status) // 16 s after the last attempt: one fresh check
        assertEquals(2, server.requestCount)
        // forced sends in a burst never exceed one exchange per 15 s
        repeat(3) { server.enqueue(MockResponse().setResponseCode(503)) }
        clock += 1
        d.stale()
        repeat(20) {
            d.check(force = true)
            clock += 1_000
        }
        assertTrue("${server.requestCount}", server.requestCount <= 2 + 2)
    }
}
