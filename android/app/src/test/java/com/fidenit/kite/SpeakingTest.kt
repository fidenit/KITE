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

    private val hangul = Regex("[가-힣]")

    private fun line(why: Eleven.Why, english: Boolean, played: Boolean = false, phoneFailed: Boolean = false): String =
        Speaking.notice(Speaking.Outcome(failure(played, why), phoneFailed), english)!!

    /** V2: one line, what is wrong → what was done with this sentence → what to do, in everyday words and in the language of the app. */
    @Test
    fun theLineOnScreenIsOneLineOfEverydayWordsForEachCause() {
        val seen = HashSet<String>()
        for (why in Eleven.Why.values()) for (english in listOf(false, true)) for ((played, phoneFailed) in listOf(false to false, true to false, false to true)) {
            val l = line(why, english, played, phoneFailed)
            assertEquals(l, 3, l.split(" → ").size)
            // nothing of the server's answer: no status number, no JSON, no code name
            assertFalse(l, l.contains("HTTP") || l.contains("401") || l.contains("{") || l.contains("invalid_api_key") || l.contains("\n"))
            assertEquals(l, !english, hangul.containsMatchIn(l))
            assertTrue("a line of its own: $l", seen.add(l))
        }
        assertEquals("ElevenLabs 키가 거절되었습니다 → 이 문장은 폰의 기본 음성으로 읽었습니다 → '설정'에서 'ElevenLabs 키'를 확인해 주세요", line(Eleven.Why.KEY, false))
        assertEquals("ElevenLabs refused the key → this sentence was read with the phone's own voice → check the ElevenLabs key in Settings", line(Eleven.Why.KEY, true))
        // a dropped connection does not send the owner to the key
        for (english in listOf(false, true)) {
            val net = line(Eleven.Why.NETWORK, english)
            assertFalse(net, net.contains("키") || net.contains("key"))
            assertTrue(net, net.contains("인터넷") || net.contains("internet"))
            val limit = line(Eleven.Why.LIMIT, english)
            assertTrue(limit, limit.contains("한도") || limit.contains("limit"))
            assertFalse(limit, limit.contains("키를") || limit.contains("the ElevenLabs key"))
        }
        // what was done is said as it was: cut off midway, or no voice at all
        assertTrue(line(Eleven.Why.NETWORK, false, played = true).contains("다시 읽지 않았습니다"))
        assertFalse(line(Eleven.Why.NETWORK, false, played = true).contains("폰의 기본 음성으로 읽었습니다"))
        assertFalse(line(Eleven.Why.KEY, false, phoneFailed = true).contains("폰의 기본 음성으로 읽었습니다"))
        assertFalse(line(Eleven.Why.KEY, true, phoneFailed = true).contains("was read with"))
    }

    @Test
    fun noLineWhenTheSentenceWasReadAndALineOfItsOwnWhenOnlyThePhoneVoiceFails() {
        assertNull(Speaking.notice(Speaking.Outcome(null, false), false))
        assertNull(Speaking.notice(Speaking.Outcome(null, false), true))
        val ko = Speaking.notice(Speaking.Outcome(null, true), false)!!
        val en = Speaking.notice(Speaking.Outcome(null, true), true)!!
        assertEquals(3, ko.split(" → ").size)
        assertEquals(3, en.split(" → ").size)
        assertTrue(hangul.containsMatchIn(ko))
        assertFalse(hangul.containsMatchIn(en))
        assertFalse(ko.contains("ElevenLabs") || en.contains("ElevenLabs"))
    }

    /** V3: the line for a failed transcription: one line of everyday words per cause, in the language of the app, true to what was done. */
    @Test
    fun theLineForAFailedTranscriptionIsEverydayWordsForEachCause() {
        val seen = HashSet<String>()
        for (why in listOf(Eleven.Why.KEY, Eleven.Why.LIMIT, Eleven.Why.NETWORK, Eleven.Why.OTHER)) for (english in listOf(false, true)) {
            val l = Speaking.heardNotice(why, english)
            assertEquals(l, 3, l.split(" → ").size)
            assertFalse(l, l.contains("HTTP") || l.contains("401") || l.contains("{") || l.contains("\n"))
            assertEquals(l, !english, hangul.containsMatchIn(l))
            assertTrue(l, l.contains("보내지 않았고, 계속 듣고 있습니다") || l.contains("was not sent, and the app keeps listening"))
            assertTrue("a line of its own: $l", seen.add(l))
        }
        assertEquals("ElevenLabs 키가 거절되었습니다 → 방금 하신 말은 보내지 않았고, 계속 듣고 있습니다 → '설정'에서 'ElevenLabs 키'를 확인해 주세요", Speaking.heardNotice(Eleven.Why.KEY, false))
        assertEquals("ElevenLabs refused the key → what you just said was not sent, and the app keeps listening → check the ElevenLabs key in Settings", Speaking.heardNotice(Eleven.Why.KEY, true))
        assertFalse(Speaking.heardNotice(Eleven.Why.NETWORK, false).contains("키"))
        assertFalse(Speaking.heardNotice(Eleven.Why.NETWORK, true).contains("key"))
    }

    /** Reads one sentence with a fake phone voice: what the engine was asked to do, and the error if the sentence was not read. */
    private fun phone(ready: Boolean = true, accepted: Boolean = true, ok: Boolean = true): Pair<List<String>, Throwable?> {
        val calls = ArrayList<String>()
        val error = runBlocking {
            runCatching {
                Speaking.phone(ready = {
                    calls.add("ready")
                    ready
                }) { done ->
                    calls.add("start")
                    if (accepted) done(ok)
                    accepted
                }
            }.exceptionOrNull()
        }
        return calls to error
    }

    /** T2: an error of the phone's voice counts as "not read", and the caller always gets an answer, so the sentence queue moves on. */
    @Test
    fun aPhoneVoiceThatFailsIsNotCountedAsReadAndNeverWaitsForever() {
        assertNull(phone().second)
        assertEquals(listOf("ready", "start"), phone().first)
        assertTrue("an error while reading", phone(ok = false).second != null)
        assertTrue("the engine did not take the sentence: no end will ever be reported", phone(accepted = false).second != null)
        val notReady = phone(ready = false)
        assertEquals(listOf("ready"), notReady.first)
        assertTrue(notReady.second != null)
        // through read(): the line on screen is the "not read aloud" one, not "read with the phone's own voice"
        val out = runBlocking { Speaking.read(null, stop = {}, current = { true }, phone = { Speaking.phone(ready = { true }) { done -> done(false); true } }) }
        assertTrue(out.phoneFailed)
        assertTrue(Speaking.notice(out, false)!!.contains("소리 내어 읽지 못했습니다"))
    }
}
