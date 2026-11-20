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
import org.junit.Test

/** Pro 의 기기 등록 흐름: 처음 켜면 이름표·비밀 글을 만들고 등록, 그 뒤에는 `/v1/me`. JVM 만. */
class ProDeviceTest {
    private class Mem : DeviceStore {
        override var deviceId = ""
        override var deviceSecret = ""
        override var proStatus = ""
        override var proCode = ""
    }

    private val server = MockWebServer()

    @Before
    fun up() = server.start()

    @After
    fun down() = server.shutdown()

    private fun dev(m: Mem) = ProDevice(m, Enroller(server.url("/").toString())) { Triple("Pixel 8", "14", "0.2.0") }

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
    fun rateLimitKeepsTheLastStatusAndNetworkErrorsThrow() {
        val m = Mem().apply { deviceId = "x"; deviceSecret = "apd_s"; proStatus = "pending"; proCode = "4821" }
        val d = dev(m)
        server.enqueue(MockResponse().setResponseCode(429))
        assertEquals("limited", d.check().status)
        assertEquals("pending", m.proStatus)
        assertEquals("4821", m.proCode)
        server.shutdown()
        assertTrue(runCatching { d.check() }.exceptionOrNull() is java.io.IOException)
        assertEquals("pending", m.proStatus)
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
}
