// APERIT Pro: 서버의 게이트웨이(aperit-gateway)에 HTTPS 로 닿는다. ClaudeCode 의 떼어 돌리는 턴을 SSH 대신 이 길로 돌린다.
// 이 기기의 비밀 글(`apd_…`)을 Bearer 로 보낸다. 서버는 그 글의 해시만 갖고 있다.
package com.fidenit.kite.engine

import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSource
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** 게이트웨이가 거절했을 때 보여 줄 글 (한국어/영어). `kind` = unknown / pending / revoked / role_not_supported … */
object GatewayText {
    fun refused(kind: String, code: String, en: Boolean): String = when (kind) {
        "pending" -> if (en) "This phone is not approved yet (waiting $code) → this message was not sent → send it again once it is approved."
        else "이 폰은 아직 허용되지 않았습니다 (허용 대기 $code) → 이 말은 보내지 않았습니다 → 허용된 뒤 다시 보내 주세요."
        "revoked" -> if (en) "This phone was blocked on the server → this message was not sent → it has to be approved on the server again."
        else "이 폰은 서버에서 막혔습니다 → 이 말은 보내지 않았습니다 → 서버에서 이 폰을 다시 허용해야 합니다."
        "unknown" -> if (en) "The server does not know this phone → this message was not sent → open the app again to register it again."
        else "서버가 이 폰을 알지 못합니다 → 이 말은 보내지 않았습니다 → 앱을 다시 열면 다시 등록합니다."
        else -> if (en) "This phone's access level cannot be used yet ($kind) → this message was not sent → its level has to be changed on the server."
        else "이 폰에 허용된 범위로는 아직 쓸 수 없습니다 ($kind) → 이 말은 보내지 않았습니다 → 서버에서 이 폰의 범위를 바꿔야 합니다."
    }
}

/** 이 기기의 이름표와 비밀 글. 앱이 처음 켜질 때 한 번 만든다. */
object DeviceKeys {
    fun newId(): String = UUID.randomUUID().toString()

    fun newSecret(random: SecureRandom = SecureRandom()): String {
        val b = ByteArray(32)
        random.nextBytes(b)
        return "apd_" + Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }

    fun sha256(token: String): String = MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }
}

/** 등록 상태: approved / pending / revoked, 그 밖에 앱 쪽에서 붙이는 conflict(409) / limited(429) / unknown(401). */
data class DeviceStatus(val status: String, val code: String = "", val voice: Boolean = false, val jev: Boolean = false)

fun gatewayClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    // 따라 읽기는 10초마다 APERITBEAT 가 온다. 40초 조용하면 ClaudeCode 가 먼저 끊는다: 이것은 그 뒤의 안전망.
    .readTimeout(60, TimeUnit.SECONDS)
    .writeTimeout(60, TimeUnit.SECONDS)
    .build()

private fun join(base: String, path: String) = base.trimEnd('/') + "/" + path

private val TEXT = "text/plain; charset=utf-8".toMediaType()
private val JSON = "application/json; charset=utf-8".toMediaType()

/** 등록과 상태 확인 (`POST /v1/enroll`, `GET /v1/me`). */
class Enroller(private val base: String, private val http: OkHttpClient = gatewayClient()) {
    /** 등록한다. 연결이 안 되거나 서버가 모르는 답을 하면 IOException. */
    fun enroll(id: String, secret: String, model: String, android: String, app: String, publicKey: String? = null): DeviceStatus {
        val j = JSONObject()
            .put("device_id", id)
            .put("token_sha256", DeviceKeys.sha256(secret))
            .put("model", model.take(200))
            .put("android", android.take(200))
            .put("app", app.take(200))
        if (!publicKey.isNullOrEmpty()) j.put("public_key", publicKey)
        val req = Request.Builder().url(join(base, "v1/enroll")).post(j.toString().toRequestBody(JSON)).build()
        http.newCall(req).execute().use { r ->
            return when (r.code) {
                200 -> parse(r.body?.string().orEmpty())
                409 -> DeviceStatus("conflict")
                429 -> DeviceStatus("limited")
                else -> throw IOException("enroll HTTP ${r.code}")
            }
        }
    }

    /** 지금 상태. 서버가 이 비밀 글을 모르면 `unknown`. */
    fun me(secret: String): DeviceStatus {
        val req = Request.Builder().url(join(base, "v1/me")).header("Authorization", "Bearer $secret").get().build()
        http.newCall(req).execute().use { r ->
            return when (r.code) {
                200 -> parse(r.body?.string().orEmpty())
                401 -> DeviceStatus("unknown")
                429 -> DeviceStatus("limited")
                else -> throw IOException("me HTTP ${r.code}")
            }
        }
    }

    private fun parse(body: String): DeviceStatus {
        val j = try {
            JSONObject(body)
        } catch (e: Exception) {
            throw IOException("bad answer")
        }
        val s = j.optString("status")
        if (s.isEmpty()) throw IOException("bad answer")
        return DeviceStatus(s, j.optString("code"), j.optBoolean("voice", false), j.optBoolean("jev", false))
    }
}

/**
 * Server-Sent Events 를 읽는다: 빈 줄로 끝난(완성된) 사건의 `data` 만 `onData` 로 넘긴다 (여러 `data:` 줄은 줄바꿈으로 잇는다).
 * 연결이 사건 한가운데서 끊기면 그 반쪽 사건은 넘기지 않는다.
 */
fun readSse(src: BufferedSource, onData: (String) -> Unit) {
    val data = StringBuilder()
    var has = false
    while (true) {
        val line = src.readUtf8Line() ?: break
        if (line.isEmpty()) {
            if (has) onData(data.toString())
            data.setLength(0)
            has = false
            continue
        }
        if (line.startsWith(":")) continue
        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        var value = if (colon < 0) "" else line.substring(colon + 1)
        if (value.startsWith(" ")) value = value.substring(1)
        if (field == "data") {
            if (has) data.append('\n')
            data.append(value)
            has = true
        }
    }
}

/**
 * 게이트웨이로 닿는 [Link]. 끝난 코드의 약속은 SSH 와 같다: 0 = 됐다, 음수 = 연결이 끊겼다 (다시 한다), 양수 = 서버가 거절했다.
 * 401·403 은 다시 해도 소용없으므로 [SshFatal] 을 던진다 ([onRefused] 로 이유도 알린다).
 */
class GatewayLink(
    private val base: String,
    private val token: () -> String,
    private val effort: String = "",
    private val english: () -> Boolean = { false },
    private val http: OkHttpClient = gatewayClient(),
) : Link {
    /** 게이트웨이가 이 기기를 거절했다: (이유, 허용 대기 번호). */
    @Volatile
    var onRefused: ((String, String) -> Unit)? = null

    private val calls = ConcurrentHashMap.newKeySet<Call>()

    private fun req(path: String) = Request.Builder().url(join(base, path)).header("Authorization", "Bearer ${token()}")

    private fun <T> call(r: Request, body: (Response) -> T): T {
        val c = http.newCall(r)
        calls.add(c)
        try {
            return c.execute().use(body)
        } finally {
            calls.remove(c)
        }
    }

    /** 401·403: 다시 하지 않는다. */
    private fun refusedCheck(r: Response) {
        if (r.code != 401 && r.code != 403) return
        val j = try {
            JSONObject(r.body?.string().orEmpty())
        } catch (_: Exception) {
            null
        }
        val kind = if (r.code == 401) "unknown" else j?.optString("error").orEmpty().ifEmpty { "forbidden" }
        val code = j?.optString("code").orEmpty()
        try {
            onRefused?.invoke(kind, code)
        } catch (_: Exception) {
        }
        throw SshFatal(GatewayText.refused(kind, code, english()))
    }

    /** 연결 쪽 문제(게이트웨이가 아직 안 떴거나 터널이 잠깐 끊김)라 다시 해 볼 만한 HTTP 코드. */
    private fun retryable(code: Int) = code == 408 || code == 429 || code in 502..504 || code in 520..530

    private fun short(r: Response): String {
        val b = try {
            r.body?.string().orEmpty()
        } catch (_: Exception) {
            ""
        }
        val e = try {
            JSONObject(b).optString("error")
        } catch (_: Exception) {
            ""
        }
        return "HTTP ${r.code}" + if (e.isNotEmpty()) " $e" else ""
    }

    /** (끝난 코드, 이유) 로 돌려주는 호출: 끊김은 음수. */
    private fun coded(r: Request, ok: (Response) -> Unit): Pair<Int, String> = try {
        call(r) { resp ->
            refusedCheck(resp)
            when {
                resp.isSuccessful -> {
                    ok(resp)
                    0 to ""
                }
                retryable(resp.code) -> -1 to ""
                else -> resp.code to short(resp)
            }
        }
    } catch (e: IOException) {
        -1 to ""
    }

    /** 실패하면 예외를 던지는 짧은 호출. */
    private fun text(r: Request): String = call(r) { resp ->
        refusedCheck(resp)
        when {
            resp.isSuccessful -> resp.body?.string().orEmpty()
            retryable(resp.code) -> throw IOException("서버와의 연결이 끊겼습니다")
            else -> throw RemoteFail(short(resp))
        }
    }

    override fun connect(timeoutMs: Int) {}

    override fun close() {
        for (c in calls.toList()) c.cancel()
    }

    override fun upload(id: String, text: String?): Pair<Int, String> =
        coded(req("v1/runs/${Detached.check(id)}/input").put((text ?: "").toRequestBody(TEXT)).build()) {}

    override fun start(id: String, onLine: (String) -> Unit): Pair<Int, String> {
        val j = JSONObject()
        if (effort.isNotEmpty()) j.put("effort", effort)
        return coded(req("v1/runs/${Detached.check(id)}/start").post(j.toString().toRequestBody(JSON)).build()) { resp ->
            readLines(java.io.StringReader(resp.body?.string().orEmpty()), false, onLine)
        }
    }

    override fun follow(id: String, n: Int, onLine: (String) -> Unit): Pair<Int, String> {
        require(n >= 0)
        val r = req("v1/runs/${Detached.check(id)}/follow?n=$n")
            .header("Accept", "text/event-stream")
            .header("Accept-Encoding", "identity")
            .build()
        return try {
            call(r) { resp ->
                refusedCheck(resp)
                when {
                    resp.isSuccessful -> {
                        readSse(resp.body!!.source(), onLine)
                        0 to ""
                    }
                    retryable(resp.code) -> -1 to ""
                    // 따라 읽기가 고장 났다 (ClaudeCode 는 세 번 이어지면 멈춘다): SSH 의 '명령 문법 오류' 와 같게 본다
                    else -> 2 to short(resp)
                }
            }
        } catch (e: IOException) {
            -1 to ""
        }
    }

    override val connected: Boolean get() = true

    /** 게이트웨이는 멈춤 요청을 적고(같은 번호의 시작을 기다린 뒤) 204 로 답한다: 그것이 "요청이 적혔다" 이다. */
    override fun stop(id: String, onLine: (String) -> Unit): Pair<Int, String> {
        text(req("v1/runs/${Detached.check(id)}/stop").post(ByteArray(0).toRequestBody(null)).build())
        onLine(Detached.STOP_SENT)
        return 0 to ""
    }

    override fun clean(id: String) {
        text(req("v1/runs/${Detached.check(id)}").delete().build())
    }

    override fun errTail(id: String): String = text(req("v1/runs/${Detached.check(id)}/err").get().build())

    private fun info(): JSONObject = try {
        JSONObject(text(req("v1/info").get().build()))
    } catch (e: org.json.JSONException) {
        throw IOException("bad answer")
    }

    override fun version(): String = info().optString("claude").trim().lineSequence().lastOrNull().orEmpty()

    /** 게이트웨이는 운영체제를 알려 주지 않는다 (있으면 쓴다). 홈 폴더 자리에는 작업 폴더(`dir`)를 쓴다. */
    override fun where(): Pair<String, String> {
        val j = info()
        return j.optString("os") to j.optString("home").ifEmpty { j.optString("dir") }.ifEmpty { "~" }
    }

    override fun prepare(system: String) {
        text(req("v1/system").put(system.toRequestBody(TEXT)).build())
    }

    override fun core(): String = text(req("v1/core").get().build())
}
