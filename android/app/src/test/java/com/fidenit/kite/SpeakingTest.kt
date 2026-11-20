package com.fidenit.kite

import com.fidenit.kite.voice.Eleven
import com.fidenit.kite.voice.Speaking
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What happens to one sentence when the ElevenLabs voice fails: which voice reads it, and in what order (no Android, no network). */
class SpeakingTest {
    private class Run(val calls: List<String>, val out: Speaking.Outcome)

    private fun read(eleven: (() -> Unit)?, phoneWorks: Boolean = true, current: Boolean = true): Run {
        val calls = ArrayList<String>()
        val viaEleven: (() -> Unit)? = if (eleven == null) null else ({
            calls.add("eleven")
            eleven()
        })
        val out = runBlocking {
            Speaking.read(viaEleven, stop = { calls.add("stop") }, current = { current }, phone = {
                calls.add("phone")
                if (!phoneWorks) throw Exception("no engine")
            })
        }
        return Run(calls, out)
    }

    private fun failure(played: Boolean, why: Eleven.Why = Eleven.Why.NETWORK) = Eleven.Failure(why, played, "HTTP 401: {\"detail\":{\"status\":\"invalid_api_key\"}}")

    /** V1: sound of this sentence was already playing when the stream dropped: the speaker is stopped and the sentence is not read again. */
    @Test
    fun aSentenceCutOffMidWayIsStoppedAndNotReadAgain() {
        val r = read({ throw failure(played = true) })
        assertEquals(listOf("eleven", "stop"), r.calls)
        assertTrue(r.out.eleven!!.played)
        assertFalse(r.out.phoneFailed)
    }

    /** Nothing of the sentence was played: the speaker is stopped first, then the phone's own voice reads it once. */
    @Test
    fun aSentenceThatNeverStartedIsReadOnceWithThePhoneVoiceAfterTheSpeakerIsStopped() {
        val r = read({ throw failure(played = false) })
        assertEquals(listOf("eleven", "stop", "phone"), r.calls)
        assertEquals(Eleven.Why.NETWORK, r.out.eleven!!.why)
        assertFalse(r.out.phoneFailed)
        // any other error (the speaker could not be opened) counts as nothing played
        val other = read({ throw IllegalStateException("no speaker") })
        assertEquals(listOf("eleven", "stop", "phone"), other.calls)
        assertEquals(Eleven.Why.OTHER, other.out.eleven!!.why)
        assertFalse(other.out.eleven!!.played)
    }

    @Test
    fun aWorkingVoiceNeedsNothingElseAndAPhoneVoiceThatFailsIsReported() {
        val fine = read({})
        assertEquals(listOf("eleven"), fine.calls)
        assertNull(fine.out.eleven)
        assertFalse(fine.out.phoneFailed)
        val noKey = read(null)
        assertEquals(listOf("phone"), noKey.calls)
        assertNull(noKey.out.eleven)
        assertFalse(noKey.out.phoneFailed)
        assertTrue(read(null, phoneWorks = false).out.phoneFailed)
        val both = read({ throw failure(played = false, why = Eleven.Why.KEY) }, phoneWorks = false)
        assertEquals(listOf("eleven", "stop", "phone"), both.calls)
        assertEquals(Eleven.Why.KEY, both.out.eleven!!.why)
        assertTrue(both.out.phoneFailed)
    }

    /** Stop was pressed meanwhile: neither the speaker (it may already play the next thing) nor the phone's voice is touched. */
    @Test
    fun afterAStopNothingMoreIsDone() {
        assertEquals(listOf("eleven"), read({ throw failure(played = false) }, current = false).calls)
    }
}
