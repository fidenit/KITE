package com.fidenit.kite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StoreTest {
    @Test
    fun jevKeyIsKeptAndDoesNotRebuildTheEngine() {
        val app = RuntimeEnvironment.getApplication()
        val s = Store(app)
        assertEquals("", s.jevKey)
        val before = s.signature()
        s.jevKey = "test-key-not-real"
        assertEquals("test-key-not-real", Store(app).jevKey) // read again from the store every time it is used
        assertEquals(before, s.signature()) // a Jev key is no reason to reconnect the server or rebuild the API client
        s.jevKey = ""
        assertEquals("", Store(app).jevKey)
        assertFalse(s.signature().contains("test-key"))
    }
}
