// 문장 하나를 소리 내어 읽을 때의 판단: ElevenLabs 가 실패하면 폰의 기본 음성으로 읽을지, 화면에 무엇을 알릴지.
// 안드로이드 API 를 쓰지 않는다 (JVM 에서 시험한다).
package com.fidenit.kite.voice

import kotlinx.coroutines.CancellationException

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
                e as? Eleven.Failure ?: Eleven.Failure(Eleven.Why.OTHER, false, e.message ?: e.toString(), e)
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
            Eleven.Why.OTHER -> if (english) "try again in a moment; if it goes on, check the ElevenLabs key and the voice ID in Settings" else "잠시 뒤 다시 해 보고, 계속되면 '설정'에서 'ElevenLabs 키'와 '목소리 ID'를 확인해 주세요"
        }
        return "$what → $did → $todo"
    }
}
