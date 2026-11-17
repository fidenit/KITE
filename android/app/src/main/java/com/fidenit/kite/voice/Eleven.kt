// ElevenLabs: 자연스러운 목소리(말하기)와 음성 인식(받아쓰기). 데스크톱의 src/eleven.rs 와 같은 호출이다.
package com.fidenit.kite.voice

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

object Eleven {
    private const val API = "https://api.elevenlabs.io"

    /** 기본 목소리 (George). */
    const val DEFAULT_VOICE = "JBFqnCBsd6RMkjVDRZzb"
    const val TTS_MODEL = "eleven_v4_turbo"

    private fun open(url: String, key: String, type: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15_000
        c.readTimeout = 60_000
        c.doOutput = true
        c.setRequestProperty("xi-api-key", key)
        c.setRequestProperty("Content-Type", type)
        return c
    }

    private fun failure(c: HttpURLConnection): String {
        val body = try {
            c.errorStream?.bufferedReader()?.readText().orEmpty()
        } catch (_: Exception) {
            ""
        }
        return "HTTP ${c.responseCode}: ${body.take(200)}"
    }

    /**
     * 문장 하나를 소리 내어 읽는다. 소리가 도착하는 대로 틀기 시작하고, 다 틀 때까지 기다린다.
     * `stopped` 가 참을 돌려주면 즉시 멈춘다.
     */
    fun speak(key: String, voice: String, text: String, player: Player, stopped: () -> Boolean) {
        val c = open("$API/v1/text-to-speech/${voice.ifBlank { DEFAULT_VOICE }}/stream?output_format=pcm_$TTS_RATE", key, "application/json")
        try {
            c.outputStream.use { it.write(JSONObject().put("text", text).put("model_id", TTS_MODEL).toString().toByteArray()) }
            if (c.responseCode != 200) throw Exception(failure(c))
            val input = c.inputStream
            val buf = ByteArray(4800)
            var carry = -1 // 샘플은 2바이트인데 조각이 홀수 바이트로 끊길 수 있다
            val pcm = ShortArray(buf.size / 2 + 1)
            while (true) {
                if (stopped()) {
                    player.stop()
                    return
                }
                val n = input.read(buf)
                if (n < 0) break
                var i = 0
                var count = 0
                if (carry >= 0 && n > 0) {
                    pcm[count++] = ((buf[0].toInt() shl 8) or carry).toShort()
                    carry = -1
                    i = 1
                }
                while (i + 1 < n) {
                    pcm[count++] = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort()
                    i += 2
                }
                if (i < n) carry = buf[i].toInt() and 0xFF
                if (count > 0 && !stopped()) player.push(pcm, count)
            }
            // 다 틀 때까지 기다린다.
            while (player.pendingSecs() > 0.02f) {
                if (stopped()) {
                    player.stop()
                    return
                }
                Thread.sleep(20)
            }
        } finally {
            c.disconnect()
        }
    }

    /** 녹음한 소리(16kHz, 16비트, 한 채널)를 글로 바꾼다. */
    fun transcribe(key: String, pcm: ShortArray, english: Boolean): String {
        val audio = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN).also { it.asShortBuffer().put(pcm) }.array()
        var last = ""
        for (model in listOf("scribe_v3", "scribe_v2")) {
            val boundary = "----ali7d81b3c9f2a44e6f"
            val body = ByteArrayOutputStream()
            fun field(name: String, value: String) = body.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
            field("model_id", model)
            field("file_format", "pcm_s16le_16")
            field("tag_audio_events", "false")
            // 고른 대화 언어로 받아쓴다 (짧은 말에서 언어를 잘못 짐작하는 일을 막는다).
            field("language_code", if (english) "eng" else "kor")
            body.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"speech.pcm\"\r\nContent-Type: application/octet-stream\r\n\r\n".toByteArray())
            body.write(audio)
            body.write("\r\n--$boundary--\r\n".toByteArray())
            val c = open("$API/v1/speech-to-text", key, "multipart/form-data; boundary=$boundary")
            try {
                c.setFixedLengthStreamingMode(body.size())
                c.outputStream.use { body.writeTo(it) }
                if (c.responseCode == 200) {
                    return JSONObject(c.inputStream.bufferedReader().readText()).optString("text").trim()
                }
                last = failure(c)
            } finally {
                c.disconnect()
            }
        }
        throw Exception(last)
    }
}
