// APERIT Pro 실시간 읽기 소켓 (`/v1/voice/live/speak`, spec §1). 뷰모델은 [LiveSpeak] 만 보고, 시험은 가짜를 끼운다.
package com.fidenit.kite.voice

import okhttp3.OkHttpClient

/** 열린 읽기 소켓 하나. 소리(24 kHz)는 [LiveEvents.audio] 로, start/done/skipped/stopped 는 [LiveEvents.message] 로 온다. */
interface LiveSpeak {
    /** 답이 곧 온다 (게이트웨이가 미리 이어 둔다) */
    fun prepare(): Boolean

    /** 한 문장. id 는 이 소켓에서 1부터 늘기만 한다. */
    fun say(id: Int, text: String): Boolean

    /** 아직 틀지 않은 것을 모두 버린다 (끼어들기, 멈춤) */
    fun stop(): Boolean

    /** 다 읽었다: `end` 를 보내고 닫는다 */
    fun end()

    fun interface Opener {
        fun open(events: LiveEvents): LiveSpeak
    }
}

/** 게이트웨이의 읽기 소켓. */
class OkLiveSpeak(private val client: OkHttpClient, private val gateway: String, private val secret: () -> String, private val app: String) : LiveSpeak.Opener {
    override fun open(events: LiveEvents): LiveSpeak {
        val url = LiveWire.speakUrl(gateway) ?: throw IllegalArgumentException("gateway url")
        val s = LiveSocket(client, url, secret(), app, events)
        return object : LiveSpeak {
            override fun prepare(): Boolean = s.text(LiveWire.prepare())
            override fun say(id: Int, text: String): Boolean = s.text(LiveWire.say(id, text))
            override fun stop(): Boolean = s.text(LiveWire.stop())
            override fun end() = s.end()
        }
    }
}
