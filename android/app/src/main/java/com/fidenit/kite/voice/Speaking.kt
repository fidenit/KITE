// 문장 하나를 소리 내어 읽을 때의 판단: ElevenLabs 가 실패하면 폰의 기본 음성으로 읽을지, 화면에 무엇을 알릴지.
// 안드로이드 API 를 쓰지 않는다 (JVM 에서 시험한다).
package com.fidenit.kite.voice

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** 마이크를 쓸 수 없게 된 까닭 (코드가 알 수 있는 만큼): 이 앱에 마이크 권한이 없음, 열지 못함, 듣는 중에 멈춤. */
enum class MicProblem { PERMISSION, OPEN, STOPPED }

object Speaking {
    /** 문장 하나를 읽은 결과. `eleven`: ElevenLabs 가 실패했으면 그 까닭. `phoneFailed`: 폰의 기본 음성으로 읽으려 했는데 못 읽었다. */
    class Outcome(val eleven: Eleven.Failure?, val phoneFailed: Boolean)

    /**
     * 문장 하나를 읽는다. `eleven` 은 ElevenLabs 로 읽기 (키가 없으면 null), `phone` 은 폰의 기본 음성으로 읽기.
     * ElevenLabs 가 실패하면 틀던 소리를 먼저 멈추고(`stop`: 남은 소리가 다른 목소리와 겹치지 않게), 이 문장의 소리가
     * 하나도 나가지 않았을 때만 폰의 기본 음성으로 읽는다. 조금이라도 나갔으면 다시 읽지 않는다 (같은 문장을 두 번 듣지 않게).
     * `current` 가 거짓이면 그사이 멈춘 것이다: 아무것도 더 하지 않는다.
     */
    suspend fun read(eleven: (() -> Unit)?, stop: () -> Unit, current: () -> Boolean, phone: suspend () -> Unit): Outcome {
        val failure: Eleven.Failure? = if (eleven == null) {
            null
        } else {
            try {
                eleven()
                return Outcome(null, false)
            } catch (e: Exception) {
                // ElevenLabs 를 부르는 쪽은 Failure 만 낸다: 다른 예외는 폰의 스피커를 열지 못한 것이다
                e as? Eleven.Failure ?: Eleven.Failure(Eleven.Why.SPEAKER, false, e.message ?: e.toString(), e)
            }
        }
        if (failure != null) {
            if (!current()) return Outcome(failure, false)
            stop()
            if (failure.played) return Outcome(failure, false)
        }
        return try {
            phone()
            Outcome(failure, false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Outcome(failure, true)
        }
    }

    /**
     * 화면에 띄울 한 줄: 무엇이 문제인가 → 이번 문장을 어떻게 했나 → 할 일. 잘 읽었으면 null.
     * 화면에 보이는 이름('설정', 'ElevenLabs 키', '목소리 ID')과 일상어만 쓴다: 상태 번호나 서버가 보낸 글은 넣지 않는다.
     */
    fun notice(o: Outcome, english: Boolean): String? {
        val f = o.eleven
        if (f == null) {
            if (!o.phoneFailed) return null
            return if (english) "The phone's own voice cannot be used → this sentence was not read aloud → check text-to-speech in the phone's settings"
            else "폰의 기본 음성을 쓸 수 없습니다 → 이 문장은 소리 내어 읽지 못했습니다 → 폰 설정에서 글자를 소리로 읽어 주는 기능(텍스트 음성 변환)을 확인해 주세요"
        }
        val what = when (f.why) {
            Eleven.Why.KEY -> if (english) "ElevenLabs refused the key" else "ElevenLabs 키가 거절되었습니다"
            Eleven.Why.LIMIT -> if (english) "An ElevenLabs usage limit was reached" else "ElevenLabs 사용 한도에 걸렸습니다"
            Eleven.Why.NETWORK -> if (english) "The connection to ElevenLabs dropped or did not answer" else "ElevenLabs 연결이 끊겼거나 응답이 없습니다"
            Eleven.Why.SPEAKER -> if (english) "The phone could not play the ElevenLabs voice" else "ElevenLabs 목소리를 폰에서 틀지 못했습니다"
            Eleven.Why.OTHER -> if (english) "The ElevenLabs voice did not work" else "ElevenLabs 목소리를 쓰지 못했습니다"
        }
        val did = when {
            f.played -> if (english) "this sentence stopped partway and was not read again" else "이 문장은 읽다가 멈췄고 다시 읽지 않았습니다"
            o.phoneFailed -> if (english) "the phone's own voice cannot be used either, so this sentence was not read aloud" else "폰의 기본 음성도 쓸 수 없어 이 문장은 소리 내어 읽지 못했습니다"
            else -> if (english) "this sentence was read with the phone's own voice" else "이 문장은 폰의 기본 음성으로 읽었습니다"
        }
        val todo = when (f.why) {
            Eleven.Why.KEY -> if (english) "check the ElevenLabs key in Settings" else "'설정'에서 'ElevenLabs 키'를 확인해 주세요"
            Eleven.Why.LIMIT -> if (english) "check the usage and the limits of your ElevenLabs account" else "ElevenLabs 계정에서 남은 사용량과 한도를 확인해 주세요"
            Eleven.Why.NETWORK -> if (english) "check the internet connection" else "인터넷 연결을 확인해 주세요"
            Eleven.Why.SPEAKER -> if (english) "close the app completely and open it again" else "앱을 완전히 닫았다가 다시 열어 주세요"
            Eleven.Why.OTHER -> if (english) "try again in a moment; if it goes on, check the ElevenLabs key and the voice ID in Settings" else "잠시 뒤 다시 해 보고, 계속되면 '설정'에서 'ElevenLabs 키'와 '목소리 ID'를 확인해 주세요"
        }
        return "$what → $did → $todo"
    }

    /**
     * 받아쓰기가 실패했을 때 화면에 띄울 한 줄 (같은 순서: 무엇이 문제 → 방금 한 말을 어떻게 했나 → 할 일).
     * 앱은 그 말을 버린다(보내지 않는다). `listening`: 말로 대화하기가 아직 켜져 있는가 — 켜져 있으면 마이크를 다시 열어 계속 듣고,
     * 그사이 꺼졌으면 듣지 않는다 (그때는 계속 듣는다고 쓰지 않는다).
     */
    fun heardNotice(why: Eleven.Why, listening: Boolean, english: Boolean): String {
        val what = when (why) {
            Eleven.Why.KEY -> if (english) "ElevenLabs refused the key" else "ElevenLabs 키가 거절되었습니다"
            Eleven.Why.LIMIT -> if (english) "An ElevenLabs usage limit was reached" else "ElevenLabs 사용 한도에 걸렸습니다"
            Eleven.Why.NETWORK -> if (english) "The connection to ElevenLabs dropped or did not answer" else "ElevenLabs 연결이 끊겼거나 응답이 없습니다"
            else -> if (english) "ElevenLabs could not turn your words into text" else "ElevenLabs 가 말을 글로 바꾸지 못했습니다"
        }
        val did = if (listening) (if (english) "what you just said was not sent, and the app keeps listening" else "방금 하신 말은 보내지 않았고, 계속 듣고 있습니다")
        else (if (english) "what you just said was not sent, and talking is switched off" else "방금 하신 말은 보내지 않았고, 말로 대화하기는 꺼져 있습니다")
        val todo = when (why) {
            Eleven.Why.KEY -> if (english) "check the ElevenLabs key in Settings" else "'설정'에서 'ElevenLabs 키'를 확인해 주세요"
            Eleven.Why.LIMIT -> if (english) "check the usage and the limits of your ElevenLabs account" else "ElevenLabs 계정에서 남은 사용량과 한도를 확인해 주세요"
            Eleven.Why.NETWORK -> if (english) "check the internet connection and say it again" else "인터넷 연결을 확인하고 다시 말해 주세요"
            else -> if (english) "say it again; if it goes on, check the ElevenLabs key in Settings" else "다시 말해 주세요. 계속되면 '설정'에서 'ElevenLabs 키'를 확인해 주세요"
        }
        return "$what → $did → $todo"
    }

    /**
     * 폰의 기본 음성으로 문장 하나를 읽고, 다 읽을 때까지 기다린다. `ready`: 폰의 음성 엔진이 켜졌는가 (처음에는 켜질 때까지 기다린다).
     * `start`: 읽기를 시작시킨다. 엔진이 받아들였으면 참을 돌려주고, 읽기가 끝나면 넘겨받은 `done` 을 부른다 (오류로 끝났으면 거짓으로).
     * 엔진이 안 켜졌거나, 읽기를 받아들이지 않았거나, 읽다가 오류가 났으면 예외를 낸다: 그 문장은 읽지 못한 것이다.
     * 어느 경우에도 끝없이 기다리지 않는다: 받아들이지 않은 읽기는 기다리지 않고, 엔진이 켜지기는 `readyMs` 까지,
     * 읽기가 끝나기는 `readMs` 까지만 기다린다 (넘으면 읽지 못한 것으로 친다: 다음 문장이 막히지 않는다).
     * 엔진이 켜지기를 기다리는 사이에 멈췄으면 (`current` 가 거짓) 읽지 않는다: 그사이 다음 말이 다른 목소리로 나가고 있을 수 있다.
     */
    suspend fun phone(ready: suspend () -> Boolean, current: () -> Boolean, readMs: Long, readyMs: Long = PHONE_READY_MS, start: (done: (Boolean) -> Unit) -> Boolean) {
        if (withTimeoutOrNull(readyMs) { ready() } != true) throw Exception("the phone's speech engine did not start")
        if (!current()) return
        val done = CompletableDeferred<Boolean>()
        if (!start { done.complete(it) }) throw Exception("the phone's speech engine did not take the sentence")
        if (withTimeoutOrNull(readMs) { done.await() } != true) throw Exception("the phone's speech engine failed while reading or did not finish in time")
    }

    /** 폰의 음성 엔진이 켜지기를 기다리는 한도: 10초. */
    const val PHONE_READY_MS = 10_000L

    /** 폰의 기본 음성이 문장 하나를 다 읽기를 기다리는 한도: 5초 + 글자마다 1초 (느리게 읽도록 맞춘 폰에서도 넉넉하게). */
    fun phoneReadMs(chars: Int): Long = 5_000L + 1_000L * chars

    /**
     * 마이크를 쓸 수 없을 때 화면에 띄울 한 줄 (무엇이 문제 → 앱이 한 일 → 할 일). 앱은 그때 말로 대화하기를 끈다.
     * 오류 글이나 번호는 넣지 않는다.
     */
    fun micNotice(p: MicProblem, english: Boolean): String {
        val what = when (p) {
            MicProblem.PERMISSION -> if (english) "This app has no microphone permission" else "이 앱에 마이크 권한이 없습니다"
            MicProblem.OPEN -> if (english) "The microphone could not be opened" else "마이크를 열지 못했습니다"
            MicProblem.STOPPED -> if (english) "The microphone stopped while listening" else "마이크가 듣는 중에 멈췄습니다"
        }
        val did = if (english) "talking was switched off" else "말로 대화하기를 껐습니다"
        val todo = when (p) {
            MicProblem.PERMISSION -> if (english) "allow Microphone under the phone's Settings > Apps > this app > Permissions, then switch talking on again" else "폰의 '설정 > 애플리케이션(앱) > 이 앱 > 권한'에서 마이크를 허용한 뒤 다시 켜 주세요"
            MicProblem.OPEN -> if (english) "end a call or another app that is recording, then switch talking on again" else "통화나 녹음 중인 다른 앱이 있으면 끝낸 뒤 다시 켜 주세요"
            MicProblem.STOPPED -> if (english) "switch talking on again; if it goes on, close the app completely and open it again" else "다시 켜 주세요. 계속되면 앱을 완전히 닫았다가 다시 열어 주세요"
        }
        return "$what → $did → $todo"
    }
}
