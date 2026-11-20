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
}
