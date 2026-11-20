// APERIT Pro 실시간 듣기 소켓 (`/v1/voice/live/listen`, spec §1). 뷰모델은 [LiveListen] 만 보고, 시험은 가짜를 끼운다.
package com.fidenit.kite.voice

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** 소켓에서 온 것. 소켓의 입출력 스레드에서 불린다 (받는 쪽이 제 스레드로 옮긴다). [closed] 는 소켓마다 꼭 한 번. */
interface LiveEvents {
    fun opened(ms: Long) {}
    fun message(m: LiveWire.In)
    fun audio(pcm: ShortArray) {}
    fun closed(c: LiveWire.Close)
}

/** 열린 듣기 소켓 하나. */
interface LiveListen {
    /** 마이크에서 읽은 소리 조각 (16 kHz 16비트 한 채널). 보내지 못하면 거짓. 아무 스레드에서나. */
    fun audio(pcm: ShortArray, n: Int): Boolean

    /** 지금 말을 끝낸 것으로 (45초 안전 한도) */
    fun commit()

    /** 다 들었다: `end` 를 보내고 닫는다 */
    fun end()

    fun interface Opener {
        fun open(english: Boolean, events: LiveEvents): LiveListen
    }
}

/** 실시간 소켓의 OkHttp 손님: 15초 ping, 넘겨주기(3xx) 따르지 않음, 읽기 한도 없음 (게이트웨이의 idle 이 끊는다). */
fun liveClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(0, TimeUnit.SECONDS)
    .pingInterval(LiveWire.PING_MS, TimeUnit.MILLISECONDS)
    .followRedirects(false)
    .followSslRedirects(false)
    .build()

/** 듣기·읽기 소켓이 같이 쓰는 OkHttp 소켓 하나. 머리글은 `proVoiceTarget()` 과 같다 (Bearer + X-Aperit-App). */
internal class LiveSocket(client: OkHttpClient, url: String, secret: String, app: String, private val events: LiveEvents, private val now: () -> Long = System::currentTimeMillis) {
    private val t0 = now()
    private val done = AtomicBoolean(false)

    @Volatile
    private var errorWord: String? = null

    @Volatile
    private var byUs = false

    val ws: WebSocket = client.newWebSocket(
        Request.Builder().url(url).header("Authorization", "Bearer $secret").apply { if (app.isNotEmpty()) header("X-Aperit-App", app) }.build(),
        object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = events.opened(now() - t0)

            override fun onMessage(webSocket: WebSocket, text: String) {
                val m = LiveWire.parse(text) ?: return
                if (m is LiveWire.In.Error) errorWord = m.word
                events.message(m)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = events.audio(LiveWire.pcmShorts(bytes.toByteArray()))

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(LiveWire.NORMAL, null)
                finish(code)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = finish(code)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = finish(-1)
        },
    )

    private fun finish(code: Int) {
        if (done.compareAndSet(false, true)) events.closed(LiveWire.Close(errorWord, code, byUs))
    }

    fun text(s: String): Boolean = !done.get() && ws.send(s)

    fun bytes(b: ByteArray): Boolean = !done.get() && ws.send(b.toByteString())

    /** `end` 를 보내고 닫는다 (OkHttp 는 보낸 순서대로 닫는다). */
    fun end() {
        byUs = true
        ws.send(LiveWire.end())
        ws.close(LiveWire.NORMAL, null)
    }
}

/** 게이트웨이의 듣기 소켓. */
class OkLiveListen(private val client: OkHttpClient, private val gateway: String, private val secret: () -> String, private val app: String) : LiveListen.Opener {
    override fun open(english: Boolean, events: LiveEvents): LiveListen {
        val url = LiveWire.listenUrl(gateway, english) ?: throw IllegalArgumentException("gateway url")
        val s = LiveSocket(client, url, secret(), app, events)
        return object : LiveListen {
            override fun audio(pcm: ShortArray, n: Int): Boolean = s.bytes(LiveWire.pcmBytes(pcm, n))
            override fun commit() {
                s.text(LiveWire.commit())
            }

            override fun end() = s.end()
        }
    }
}
