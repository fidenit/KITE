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

    /** 첫 소리가 오기까지·조각 사이에 기다리는 한도. 중간 서버가 소리를 모았다가 보내도 끊지 않게 60초. */
    const val READ_MS = 60_000

    /**
     * 부를 곳: 주소 뿌리(그 아래 `/text-to-speech/…`, `/speech-to-text`)와 머리글.
     * free = ElevenLabs 에 사용자의 키(`xi-api-key`), pro = 게이트웨이의 `/v1/voice` 에 이 기기의 비밀 글(Bearer). 비밀 글은 글로 찍지 않는다.
     */
    class Target internal constructor(internal val root: String, internal val headers: List<Pair<String, String>>, val pro: Boolean) {
        override fun toString(): String = if (pro) "Target(pro)" else "Target(free)"

        companion object {
            /** 무료 앱: 오늘과 같은 호출 (`$api/v1/…`, `xi-api-key`). */
            fun free(key: String): Target = Target("$api/v1", listOf("xi-api-key" to key), false)

            /** Pro: `<게이트웨이>/v1/voice/…`, `Authorization: Bearer <비밀 글>`, `X-Aperit-App` (키는 서버에만 있다). */
            fun pro(gateway: String, secret: String, app: String): Target =
                Target(gateway.trimEnd('/') + "/v1/voice", listOf("Authorization" to "Bearer $secret", "X-Aperit-App" to app), true)
        }
    }

    private fun open(url: String, t: Target, type: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15_000
        c.readTimeout = READ_MS
        c.doOutput = true
        // Pro: 넘겨주기(3xx)를 따르지 않는다 (비밀 글이 다른 곳으로 가지 않게). 3xx 는 실패로 본다.
        if (t.pro) c.instanceFollowRedirects = false
        for ((k, v) in t.headers) c.setRequestProperty(k, v)
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
    class Failure(
        val why: Why,
        val played: Boolean,
        message: String,
        cause: Throwable? = null,
        /** Pro: 게이트웨이가 밝힌 까닭 (null = 게이트웨이의 답이 아니다: 연결 문제, 폰 쪽 문제, 또는 무료 앱). */
        val gate: Gate? = null,
        /** Pro, 401·403: 기기 문제의 낱말 (401 → unknown, 403 → 본문의 error 또는 forbidden) 과 허용 대기 번호. */
        val deviceKind: String = "",
        val deviceCode: String = "",
    ) : Exception(message, cause)

    /** Pro: 게이트웨이의 오류 본문 `{"error":…}` 낱말 (spec 10.1) 과 기기 문제(401·403). */
    enum class Gate { OFF, KEY, LIMIT, RATE, REFUSED, UNREACHABLE, TOO_LARGE, BAD_REQUEST, DEVICE, OTHER }

    /**
     * Pro: 200 이 아닌 게이트웨이 답 → 까닭. 기록용 글은 번호와 낱말만 (본문을 옮기지 않는다).
     * `why`: 한도(voice_limit / rate_limited) → LIMIT, 서버가 ElevenLabs 에 닿지 못함 → NETWORK, 나머지 → OTHER.
     */
    internal fun gate(code: Int, body: String): Failure {
        val j = try {
            JSONObject(body)
        } catch (_: Exception) {
            null
        }
        val word = j?.optString("error").orEmpty().filter { it.isLetterOrDigit() || it == '_' }.take(40)
        val g = when {
            code == 401 || code == 403 -> Gate.DEVICE
            word == "voice_off" -> Gate.OFF
            word == "voice_key" -> Gate.KEY
            word == "voice_limit" -> Gate.LIMIT
            word == "rate_limited" -> Gate.RATE
            word == "voice_refused" -> Gate.REFUSED
            word == "voice_unreachable" -> Gate.UNREACHABLE
            word == "too_large" -> Gate.TOO_LARGE
            word == "bad_request" -> Gate.BAD_REQUEST
            code == 429 -> Gate.RATE
            code == 413 -> Gate.TOO_LARGE
            else -> Gate.OTHER
        }
        val why = when (g) {
            Gate.LIMIT, Gate.RATE -> Why.LIMIT
            Gate.UNREACHABLE -> Why.NETWORK
            else -> Why.OTHER
        }
        val kind = when (code) {
            401 -> "unknown"
            403 -> word.ifEmpty { "forbidden" }
            else -> ""
        }
        val dc = if (code == 403) j?.optString("code").orEmpty().filter { it.isDigit() }.take(12) else ""
        return Failure(why, false, "HTTP $code" + if (word.isNotEmpty()) " $word" else "", gate = g, deviceKind = kind, deviceCode = dc)
    }

    /** 200 이 아닌 답 → [Failure] (무료 앱은 [why], Pro 는 [gate]). */
    private fun refused(t: Target, code: Int, body: String): Failure =
        if (t.pro) gate(code, body) else Failure(why(code, body), false, "HTTP $code: ${body.take(200)}")

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
    fun speak(key: String, voice: String, text: String, player: Sink, stopped: () -> Boolean) = speak(Target.free(key), voice, text, player, stopped)

    /** [speak] 를 고른 곳([Target])으로. `format` 은 받을 소리 (앱은 [TTS_RATE] 에 맞춘 pcm_24000). */
    fun speak(t: Target, voice: String, text: String, player: Sink, stopped: () -> Boolean, format: String = "pcm_$TTS_RATE") {
        var played = false
        var c: HttpURLConnection? = null
        try {
            c = open("${t.root}/text-to-speech/${voice.ifBlank { DEFAULT_VOICE }}/stream?output_format=$format", t, "application/json")
            c.outputStream.use { it.write(JSONObject().put("text", text).put("model_id", TTS_MODEL).toString().toByteArray()) }
            val code = status(c)
            if (code != 200) {
                throw refused(t, code, errorBody(c))
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
    fun transcribe(key: String, pcm: ShortArray, english: Boolean): String = transcribe(Target.free(key), pcm, english)

    /** Pro 에서 다른 모델로 다시 불러 볼 만한 까닭 (서버의 설정이나 ElevenLabs 가 그 모델만 거절했을 수 있다). */
    private val RETRY = setOf(Gate.REFUSED, Gate.BAD_REQUEST, Gate.OTHER)

    /** [transcribe] 를 고른 곳([Target])으로. */
    fun transcribe(t: Target, pcm: ShortArray, english: Boolean): String {
        val audio = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN).also { it.asShortBuffer().put(pcm) }.array()
        var first = ""
        var firstWhy = Why.OTHER
        var firstFail: Failure? = null
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
            val c = open("${t.root}/speech-to-text", t, "multipart/form-data; boundary=$boundary")
            try {
                c.setFixedLengthStreamingMode(body.size())
                c.outputStream.use { body.writeTo(it) }
                val code = status(c)
                if (code == 200) {
                    return JSONObject(c.inputStream.bufferedReader().readText()).optString("text").trim()
                }
                val answer = errorBody(c)
                if (t.pro) {
                    // Pro: 서버의 목소리가 꺼졌거나·한도·기기 문제면 다른 모델로 다시 불러도 소용없다
                    val f = gate(code, answer)
                    if (firstFail == null) firstFail = f
                    if (f.gate !in RETRY) throw f
                    continue
                }
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
        throw firstFail ?: Failure(firstWhy, false, first)
    }
}
