package com.fidenit.kite

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** 설정 저장소를 여는 규칙 (JVM 만, 실제 키 저장소 없이 여는 일을 흉내 낸다). */
class SecureStoreTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    private class Rig(var failOpens: Int, val readFails: Boolean = false, val error: () -> Exception = { java.security.GeneralSecurityException("keyset") }) {
        var opens = 0
        var wipes = 0
        var plains = 0
        var memories = 0
        val enc = object : SharedPreferences by MemoryPrefs() {
            override fun getAll(): MutableMap<String, *> = if (readFails) throw SecurityException("cannot decrypt") else HashMap<String, Any>()
        }
        val plain = MemoryPrefs()
        val mem = MemoryPrefs()

        fun open(pro: Boolean) = openSettings(
            pro,
            open = {
                opens++
                if (failOpens-- > 0) throw error() else enc
            },
            wipe = { wipes++ },
            plain = { plains++; plain },
            memory = { memories++; mem },
            pause = {},
        )
    }

    @Test
    fun proOpensTheEncryptedStoreWhenItCan() {
        val r = Rig(0)
        assertSame(r.enc, r.open(true))
        assertEquals(0, r.wipes)
    }

    @Test
    fun proWipesAnUnreadableStoreAndStartsEmptyNeverPlain() {
        // a failure that goes away is retried, never wiped
        val r = Rig(2)
        assertSame(r.enc, r.open(true))
        assertEquals(3, r.opens)
        assertEquals(0, r.wipes)
        // still unreadable after 3 tries (restored onto another phone): wiped once, then opened empty
        val w = Rig(3)
        assertSame(w.enc, w.open(true))
        assertEquals(1, w.wipes)
        assertEquals(0, w.plains)
        // opens but values cannot be decrypted: wiped, and if still unreadable, memory only
        val u = Rig(0, readFails = true)
        assertSame(u.mem, u.open(true))
        assertEquals(1, u.wipes)
        assertEquals(0, u.plains)
        val v = Rig(9)
        assertSame(v.mem, v.open(true))
        assertEquals(0, v.plains)
    }

    @Test
    fun aTransientKeystoreErrorIsNeverWiped() {
        val r = Rig(9, error = { java.security.KeyStoreException("keystore busy") })
        assertSame(r.mem, r.open(true))
        assertEquals(0, r.wipes)
        assertEquals(3, r.opens)
        val p = Rig(9, error = { java.security.ProviderException("transient") })
        assertSame(p.mem, p.open(true))
        assertEquals(0, p.wipes)
    }

    @Test
    fun whichFailuresCountAsUnreadable() {
        assertTrue(unreadable(javax.crypto.AEADBadTagException("tag")))
        assertTrue(unreadable(java.security.InvalidKeyException("key")))
        assertTrue(unreadable(SecurityException("Could not decrypt value")))
        assertTrue(unreadable(RuntimeException("wrap", java.security.UnrecoverableKeyException("x"))))
        assertFalse(unreadable(java.security.KeyStoreException("busy")))
        assertFalse(unreadable(java.security.ProviderException("p")))
        assertFalse(unreadable(IllegalStateException("s")))
        assertFalse(unreadable(java.io.IOException("io")))
    }

    @Test
    fun freeKeepsItsPlainFallback() {
        val r = Rig(1)
        assertSame(r.plain, r.open(false))
        assertEquals(0, r.wipes)
        assertEquals(0, r.memories)
    }

    @Test
    fun anEmptyStoreGivesANewIdentity() {
        // after a wipe the store is empty: ProDevice makes a new id and secret before it enrolls (which then is pending)
        val m = object : DeviceStore {
            override var deviceId = ""
            override var deviceSecret = ""
            override var proStatus = ""
            override var proCode = ""
            override var ticketUsed = ""
        }
        ProDevice(m, com.fidenit.kite.engine.Enroller("http://127.0.0.1:9/"), { Triple("", "", "") }).ensure()
        assertTrue(m.deviceId.isNotEmpty() && m.deviceSecret.startsWith("apd_"))
        val p = MemoryPrefs()
        p.edit().putString("a", "1").apply()
        assertEquals("1", p.getString("a", ""))
        p.edit().clear().apply()
        assertEquals("", p.getString("a", ""))
    }
}
