package com.fidenit.kite

import android.app.Application
import android.os.Looper
import com.fidenit.kite.voice.LiveEvents
import com.fidenit.kite.voice.LiveListen
import com.fidenit.kite.voice.LiveWire
import com.fidenit.kite.voice.LiveWire.In
import com.fidenit.kite.voice.ProVoice
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The realtime listen path as wired in the view model (spec aperit-pro-voice-realtime-v1 §3.2), on the JVM (Robolectric).
 * The test build is the free flavor, so the gateway's listen socket is replaced by a fake through `liveListenOverride`
 * (with no override the free flavor never opens one). No server, no network; the main looper's delayed ticks are not run.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KiteViewModelLiveTest {
    private lateinit var app: Application

    private class Sock(val events: LiveEvents) : LiveListen {
        var ended = false
        override fun audio(pcm: ShortArray, n: Int) = true
        override fun commit() {}
        override fun end() {
            ended = true
        }
    }

    private val socks = mutableListOf<Sock>()
    private lateinit var vm: KiteViewModel

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        app.filesDir.listFiles()?.forEach { it.deleteRecursively() }
        for (n in listOf("kite-settings", "kite-settings-plain")) app.getSharedPreferences(n, 0).edit().clear().commit()
        Store(app).elevenKey = "test-key-not-real" // the free flavor needs a key to switch talking on
        vm = KiteViewModel(app)
        vm.liveListenOverride = LiveListen.Opener { _, ev -> Sock(ev).also { socks.add(it) } }
    }

    @After
    fun tearDown() {
        vm.converse(false)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun gw(m: In) {
        socks.last().events.message(m)
        idle()
    }

    @Test
    fun partialsShowInTheStatusAndAFinalFollowsTodaysRules() {
        vm.converse(true)
        idle()
        assertEquals("talking on opens one listen socket", 1, socks.size)
        gw(In.Ready)
        gw(In.Partial("잠깐"))
        assertEquals("잠깐", vm.liveHeard)
        assertTrue(vm.heard)
        gw(In.Final("잠깐만", "kor"))
        assertNull(vm.liveHeard)
        assertFalse(vm.heard)
        assertTrue("a wait word through a final", vm.standby)
        vm.converse(false)
        assertTrue("talking off sends end", socks.last().ended)
        assertNull(vm.liveHeard)
    }

    @Test
    fun voiceOffFallsBackWithoutD9() {
        vm.converse(true)
        idle()
        socks.last().events.closed(LiveWire.Close("voice_off", 4503, false))
        idle()
        assertNull(vm.error)
        assertTrue(vm.converse)
    }

    @Test
    fun aSecondBadRequestFallsBackAndShowsD9Once() {
        vm.converse(true)
        idle()
        socks.last().events.closed(LiveWire.Close("bad_request", 4400, false))
        idle()
        assertEquals("reopened once", 2, socks.size)
        socks.last().events.closed(LiveWire.Close("bad_request", 4400, false))
        idle()
        assertEquals(ProVoice.liveDropped(false), vm.error)
        assertTrue("the conversation goes on the earlier way", vm.converse)
        assertEquals(2, socks.size)
    }

    @Test
    fun withoutVoiceLiveNoSocketIsOpened() {
        vm.liveListenOverride = null
        vm.converse(true)
        idle()
        assertTrue(vm.converse)
        assertEquals(0, socks.size)
    }
}
