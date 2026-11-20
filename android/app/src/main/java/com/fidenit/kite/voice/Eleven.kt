// ElevenLabs: 자연스러운 목소리(말하기)와 음성 인식(받아쓰기). 데스크톱의 src/eleven.rs 와 같은 호출이다.
package com.fidenit.kite.voice

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

object Eleven {
    /** 서버 주소. 시험에서 가짜 서버로 바꾼다 (앱은 바꾸지 않는다). */
    @Volatile
    internal var api = "https://api.elevenlabs.io"

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

    /** 상태 코드. 본문을 정해진 길이로 올리는 중에 401 이 오면 HttpURLConnection 이 다시 시도할 수 없다며 예외를 내므로 거기서 코드를 꺼낸다. */
    private fun status(c: HttpURLConnection): Int = try {
        c.responseCode
    } catch (e: java.net.HttpRetryException) {
        e.responseCode()
    }

    private fun errorBody(c: HttpURLConnection): String = try {
        c.errorStream?.bufferedReader()?.readText().orEmpty()
    } catch (_: Exception) {
        ""
    }

    /** 소리 조각을 받아 트는 쪽 (앱에서는 [Player], 시험에서는 가짜). */
    interface Sink {
        fun push(pcm: ShortArray, n: Int)

        fun pendingSecs(): Float

        fun stop()
    }

    /** 실패한 까닭: 키가 거절됨, 사용 한도, 연결이 끊기거나 응답이 없음, 폰이 받은 소리를 틀지 못함, 그 밖. */
    enum class Why { KEY, LIMIT, NETWORK, SPEAKER, OTHER }

    /**
     * 말하기가 실패했다. `played` 는 이 문장의 소리를 조금이라도 스피커 쪽에 넘긴 뒤에 실패했는가
     * (참이면 그 문장을 다른 목소리로 처음부터 다시 읽으면 안 된다). `message` 는 기록용이고 화면에 그대로 내지 않는다.
     */
    class Failure(val why: Why, val played: Boolean, message: String, cause: Throwable? = null) : Exception(message, cause)

    /** 200 이 아닌 답의 까닭. 한도를 다 쓴 계정에는 401 과 함께 본문의 detail.status 가 quota_exceeded 로 온다. */
    internal fun why(code: Int, body: String): Why {
        val status = try {
            JSONObject(body).optJSONObject("detail")?.optString("status").orEmpty()
        } catch (_: Exception) {
            ""
        }
        return when {
            code == 429 || status.contains("quota") -> Why.LIMIT
            code == 401 || code == 403 -> Why.KEY
            else -> Why.OTHER
        }
    }

    /**
     * 문장 하나를 소리 내어 읽는다. 소리가 도착하는 대로 틀기 시작하고, 다 틀 때까지 기다린다.
     * `stopped` 가 참을 돌려주면 즉시 멈춘다. 실패하면 [Failure] 를 낸다 (다른 예외는 내지 않는다).
     */
    fun speak(key: String, voice: String, text: String, player: Sink, stopped: () -> Boolean) {
        var played = false
        var c: HttpURLConnection? = null
        try {
            c = open("$api/v1/text-to-speech/${voice.ifBlank { DEFAULT_VOICE }}/stream?output_format=pcm_$TTS_RATE", key, "application/json")
            c.outputStream.use { it.write(JSONObject().put("text", text).put("model_id", TTS_MODEL).toString().toByteArray()) }
            val code = status(c)
            if (code != 200) {
                val body = errorBody(c)
                throw Failure(why(code, body), false, "HTTP $code: ${body.take(200)}")
            }
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
                if (count > 0 && !stopped()) {
                    val before = played
                    played = true
                    try {
                        player.push(pcm, count)
                    } catch (e: Exception) {
                        throw Failure(Why.SPEAKER, before, e.message ?: e.toString(), e)
                    }
                }
            }
            // 다 틀 때까지 기다린다.
            while (player.pendingSecs() > 0.02f) {
                if (stopped()) {
                    player.stop()
                    return
                }
                Thread.sleep(20)
            }
        } catch (e: Failure) {
            throw e
        } catch (e: IOException) {
            throw Failure(Why.NETWORK, played, e.message ?: e.toString(), e)
        } catch (e: Exception) {
            throw Failure(Why.OTHER, played, e.message ?: e.toString(), e)
        } finally {
            c?.disconnect()
        }
    }

    /** 녹음한 소리(16kHz, 16비트, 한 채널)를 글로 바꾼다. 실패하면 [Failure] 를 낸다 (까닭은 값으로, `message` 는 기록용). */
    fun transcribe(key: String, pcm: ShortArray, english: Boolean): String {
        val audio = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN).also { it.asShortBuffer().put(pcm) }.array()
        var first = ""
        var firstWhy = Why.OTHER
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
            val c = open("$api/v1/speech-to-text", key, "multipart/form-data; boundary=$boundary")
            try {
                c.setFixedLengthStreamingMode(body.size())
                c.outputStream.use { body.writeTo(it) }
                val code = status(c)
                if (code == 200) {
                    return JSONObject(c.inputStream.bufferedReader().readText()).optString("text").trim()
                }
                val answer = errorBody(c)
                if (first.isEmpty()) {
                    first = "HTTP $code: ${answer.take(200)}"
                    firstWhy = why(code, answer)
                }
                // 키가 거절됐거나 한도에 닿았으면 다른 모델로 다시 불러도 소용없다: 거기서 끝내고, 끝내게 한 그 답의 까닭을 알린다
                if (code == 401 || code == 403 || code == 429) throw Failure(why(code, answer), false, "HTTP $code: ${answer.take(200)}")
            } catch (e: Failure) {
                throw e
            } catch (e: IOException) {
                throw Failure(Why.NETWORK, false, e.message ?: e.toString(), e)
            } catch (e: Exception) {
                throw Failure(Why.OTHER, false, e.message ?: e.toString(), e)
            } finally {
                c.disconnect()
            }
        }
        throw Failure(firstWhy, false, first)
    }
}
