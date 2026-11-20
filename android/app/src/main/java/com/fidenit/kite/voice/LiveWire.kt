// APERIT Pro 실시간 음성: 폰 ↔ 게이트웨이 소켓의 글 조각(JSON)과 소리 조각 (spec aperit-pro-voice-realtime-v1 §1).
// 소켓 글의 모양을 아는 곳은 이 파일 하나뿐이다. 안드로이드 API 를 쓰지 않는다 (JVM 에서 시험한다).
package com.fidenit.kite.voice

import org.json.JSONObject

object LiveWire {
    const val LISTEN = "v1/voice/live/listen"
    const val SPEAK = "v1/voice/live/speak"

    /** 글 조각 한도 16 KiB, 소리 조각 한도 64 KiB (§1) */
    const val MAX_TEXT = 16 * 1024
    const val MAX_BINARY = 64 * 1024

    /** 게이트웨이도 15초마다 ping 을 보낸다 */
    const val PING_MS = 15_000L

    /** 정상 닫기 */
    const val NORMAL = 1000

    /** `https://` → `wss://`, `http://` → `ws://` (에뮬레이터용 `http://10.0.2.2:8787` 은 그대로 평문). 다른 모양이면 null. */
    fun url(gateway: String, path: String, query: String = ""): String? {
        val g = gateway.trim().trimEnd('/')
        val base = when {
            g.startsWith("https://") -> "wss://" + g.removePrefix("https://")
            g.startsWith("http://") -> "ws://" + g.removePrefix("http://")
            else -> return null
        }
        return "$base/$path" + if (query.isNotEmpty()) "?$query" else ""
    }

    /** 듣기 소켓의 주소: `lang` 은 오늘의 받아쓰기 칸과 같은 값 (kor / eng). */
    fun listenUrl(gateway: String, english: Boolean): String? = url(gateway, LISTEN, "lang=" + if (english) "eng" else "kor")

    fun speakUrl(gateway: String): String? = url(gateway, SPEAK)

    // ------------------------------------------------------------ 폰 → 게이트웨이
    private fun frame(type: String, fill: JSONObject.() -> Unit = {}): String = JSONObject().put("type", type).apply(fill).toString()

    fun commit(): String = frame("commit")
    fun end(): String = frame("end")
    fun prepare(): String = frame("prepare")
    fun stop(): String = frame("stop")
    fun say(id: Int, text: String): String = frame("say") {
        put("id", id)
        put("text", text)
    }

    /** 16비트 소리를 작은 쪽 먼저(little-endian) 바이트로 */
    fun pcmBytes(pcm: ShortArray, n: Int): ByteArray {
        val out = ByteArray(n * 2)
        for (i in 0 until n) {
            val v = pcm[i].toInt()
            out[2 * i] = v.toByte()
            out[2 * i + 1] = (v shr 8).toByte()
        }
        return out
    }

    /** 받은 소리 바이트를 16비트로 (홀수 바이트 하나가 남으면 버린다) */
    fun pcmShorts(b: ByteArray): ShortArray {
        val n = b.size / 2
        return ShortArray(n) { i -> ((b[2 * i].toInt() and 0xFF) or (b[2 * i + 1].toInt() shl 8)).toShort() }
    }

    // ------------------------------------------------------------ 게이트웨이 → 폰
    sealed class In {
        /** [vadS] = 게이트웨이가 쓰는 말 끝 기다림(초), [minQuietS] = 일찍 끝내기 전에 적어도 조용해야 할 초 (v1.2). 없으면 null. */
        data class Ready(val vadS: Double? = null, val minQuietS: Double? = null) : In()

        /** [tp] = 이 partial 에서 말이 끝났을 확률 (0..1), 없으면 null (v1.1) */
        data class Partial(val text: String, val tp: Double? = null) : In()
        data class Final(val text: String, val lang: String) : In()
        data class Start(val id: Int) : In()
        data class Done(val id: Int, val chars: Int) : In()
        data class Skipped(val id: Int, val error: String) : In()
        data class Stopped(val upto: Int) : In()
        data class Error(val word: String) : In()
    }

    /** 글 조각 하나. JSON 이 아니거나, 모르는 `type` 이거나, 꼭 있어야 할 칸이 없으면 null (버린다, §1 앞으로의 호환). 모르는 칸은 보지 않는다. */
    fun parse(text: String): In? {
        val j = try {
            JSONObject(text)
        } catch (_: Exception) {
            return null
        }
        val id = j.opt("id") as? Number
        return when (j.optString("type")) {
            "ready" -> In.Ready((j.opt("vad_s") as? Number)?.toDouble()?.takeIf { it > 0 && it < 60 }, (j.opt("min_quiet_s") as? Number)?.toDouble()?.takeIf { it >= 0 && it < 10 })
            "partial" -> In.Partial(j.optString("text"), (j.opt("tp") as? Number)?.toDouble()?.takeIf { it in 0.0..1.0 })
            "final" -> In.Final(j.optString("text"), j.optString("lang"))
            "start" -> id?.let { In.Start(it.toInt()) }
            "done" -> id?.let { In.Done(it.toInt(), j.optInt("chars", 0)) }
            "skipped" -> id?.let { In.Skipped(it.toInt(), j.optString("error")) }
            "stopped" -> (j.opt("upto") as? Number)?.let { In.Stopped(it.toInt()) }
            "error" -> In.Error(j.optString("error"))
            else -> null
        }
    }

    /** 오류 낱말 (§1 표) 의 묶음. 낱말이 없으면 닫기 번호로 고른다. */
    enum class Fault { NONE, DEVICE, OFF, KEY, UPSTREAM, LIMIT, BUSY, BAD, IDLE, REPLACED, NETWORK }

    fun fault(word: String?, code: Int): Fault = when (word) {
        "unauthorized", "pending", "revoked" -> Fault.DEVICE
        "voice_off" -> Fault.OFF
        "voice_key" -> Fault.KEY
        "upstream" -> Fault.UPSTREAM
        "limit" -> Fault.LIMIT
        "busy" -> Fault.BUSY
        "bad_request" -> Fault.BAD
        "idle", "session_max" -> Fault.IDLE
        "replaced" -> Fault.REPLACED
        else -> when (code) {
            NORMAL -> Fault.NONE
            4401, 4403 -> Fault.DEVICE
            4503 -> Fault.OFF
            4502 -> Fault.UPSTREAM
            4429 -> Fault.BUSY
            4400 -> Fault.BAD
            4408 -> Fault.IDLE
            4409 -> Fault.REPLACED
            else -> Fault.NETWORK
        }
    }

    /** 소켓 하나가 끝났다: 받은 오류 낱말(없으면 null), 닫기 번호(연결 실패면 -1), 폰이 닫았는가. */
    data class Close(val word: String?, val code: Int, val byUs: Boolean) {
        val fault: Fault get() = fault(word, code)

        /** pro.log 에 남길 낱말 */
        val logWord: String get() = word ?: if (code == -1) "network" else "code$code"
    }
}
