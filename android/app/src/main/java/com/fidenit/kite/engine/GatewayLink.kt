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

/** 게이트웨이 요청의 단계: 턴을 시작하기 전(올리기·시작·지시문), 턴을 따라 읽는 중, 멈춤·치우기. 거절 글의 "무엇을 했나" 가 단계마다 다르다. */
enum class LinkPhase { BEFORE, FOLLOW, AFTER }

/** 게이트웨이가 거절했을 때 보여 줄 글 (한국어/영어). `kind` = unknown / pending / revoked / role_not_supported … */
object GatewayText {
    fun refused(kind: String, code: String, en: Boolean, phase: LinkPhase = LinkPhase.BEFORE): String {
        val problem = when (kind) {
            "pending" -> if (en) "This phone is not approved yet (waiting $code)" else "이 폰은 아직 허용되지 않았습니다 (허용 대기 $code)"
            "revoked" -> if (en) "This phone was blocked on the server" else "이 폰은 서버에서 막혔습니다"
            "unknown" -> if (en) "The server does not know this phone" else "서버가 이 폰을 알지 못합니다"
            else -> if (en) "This phone's access level cannot be used yet ($kind)" else "이 폰에 허용된 범위로는 아직 쓸 수 없습니다 ($kind)"
        }
        val done = when (phase) {
            LinkPhase.BEFORE -> if (en) "this message was not sent" else "이 말은 보내지 않았습니다"
            LinkPhase.FOLLOW -> if (en) "this message was sent, but its result could not be received and the app ended it here" else "이 말은 보냈지만 결과를 받지 못해 화면에서는 여기서 끝냈습니다"
            LinkPhase.AFTER -> if (en) "the stop or clean-up could not be delivered to the server" else "서버에 멈춤·치우기를 전하지 못했습니다"
        }
        val todo = when (kind) {
            "pending" -> if (en) "send it again once it is approved" else "허용된 뒤 다시 보내 주세요"
            "revoked" -> if (en) "it has to be approved on the server again" else "서버에서 이 폰을 다시 허용해야 합니다"
            "unknown" -> if (en) "open the app again to register it again" else "앱을 다시 열면 다시 등록합니다"
            else -> if (en) "its level has to be changed on the server" else "서버에서 이 폰의 범위를 바꿔야 합니다"
        }
        return "$problem → $done → $todo."
    }

    /** 앱에 넣은 게이트웨이 주소나 이 폰의 정보로 요청을 만들 수 없다 (잘못 만든 빌드). */
    fun badConfig(en: Boolean): String = if (en) "The gateway address built into this app is not valid → this message was not sent → install a correctly built app."
    else "앱에 넣은 게이트웨이 주소가 올바르지 않습니다 → 이 말은 보내지 않았습니다 → 제대로 만든 앱을 다시 설치해 주세요."

    /** 빌드에 넣은 등록 표가 이미 쓰였거나 기한이 지났다 (`ticket` = used / expired / revoked / invalid): 이 폰은 번호로 허용을 기다린다. */
    fun ticketRejected(kind: String, code: String, en: Boolean): String = if (en) "The built-in enrollment ticket was already used or has expired ($kind) → this phone now waits for approval → approve it on the server with the code $code."
    else "앱에 넣은 등록 표가 이미 쓰였거나 기한이 지났습니다 ($kind) → 이 폰은 허용을 기다립니다 → 서버에서 번호 $code 로 허용해 주세요."

    /** 시작에서 `APERITFAIL busy`: 서버에서 이미 여러 말이 돌거나 메모리가 모자라다. */
    fun busy(en: Boolean): String = if (en) "The server is busy (other messages are running or memory is short) → this message was not started → send it again shortly."
    else "서버가 지금 바쁩니다 (다른 말이 돌고 있거나 메모리가 모자람) → 이번 말은 시작하지 않았습니다 → 잠시 뒤 다시 보내 주세요."
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

/**
 * 등록 상태: approved / pending / revoked, 그 밖에 앱 쪽에서 붙이는 conflict(409) / limited(429) / unknown(401).
 * `ticket` = 등록 표를 보냈을 때 서버의 판단 (ok / used / expired / revoked / invalid), 보내지 않았으면 빈 글.
 */
data class DeviceStatus(val status: String, val code: String = "", val voice: Boolean = false, val jev: Boolean = false, val ticket: String = "")

/** 게이트웨이용 OkHttp. `app` (예: `0.2.0-pro`) 은 모든 요청에 `X-Aperit-App` 으로 붙는다. 따라 읽기는 [GatewayLink] 가 읽기 한도만 늘려 쓴다. */
fun gatewayClient(app: String = ""): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    // 넘겨주기(3xx)를 따르지 않는다: PUT·POST 가 GET 으로 바뀌거나 비밀 글이 다른 곳으로 가지 않게. 3xx 는 거절로 본다.
    .followRedirects(false)
    .followSslRedirects(false)
    .apply { if (app.isNotEmpty()) addInterceptor { ch -> ch.proceed(ch.request().newBuilder().header("X-Aperit-App", app).build()) } }
    .build()

/** 따라 읽기의 읽기 한도: ClaudeCode 의 40초 규칙([Detached.IDLE_MS])이 먼저 판단하도록 그보다 조금 길게. 전체 한도는 없다. */
const val FOLLOW_READ_MS = 45_000L

private fun join(base: String, path: String) = base.trimEnd('/') + "/" + path

private val TEXT = "text/plain; charset=utf-8".toMediaType()
private val JSON = "application/json; charset=utf-8".toMediaType()

/** 서버에 보내는 기기 정보 글: 제어 문자(0x00-0x1F, 0x7F)를 빼고 200자로 자른다 (서버는 제어 문자가 든 글을 400 으로 거절한다). */
fun cleanField(s: String): String = s.filter { it.code >= 0x20 && it.code != 0x7F }.take(200)

/**
 * 등록과 상태 확인 (`POST /v1/enroll`, `GET /v1/me`). 앱 쪽 상태: conflict(409), limited(429), refused(400·413·3xx: 보낸 것이 잘못됨, 다시 해도 같다),
 * error(5xx: 서버 쪽 문제, 천천히 다시), unknown(401, `/v1/me`). 연결이 안 되거나 모르는 답이면 IOException.
 */
class Enroller(private val base: String, private val http: OkHttpClient = gatewayClient()) {
    private fun other(code: Int): DeviceStatus = when {
        code in 500..599 -> DeviceStatus("error")
        code in 300..499 -> DeviceStatus("refused")
        else -> throw IOException("HTTP $code")
    }

    /** 등록한다. */
    fun enroll(id: String, secret: String, model: String, android: String, app: String, publicKey: String? = null, ticket: String? = null): DeviceStatus {
        val j = JSONObject()
            .put("device_id", id)
            .put("token_sha256", DeviceKeys.sha256(secret))
            .put("model", cleanField(model))
            .put("android", cleanField(android))
            .put("app", cleanField(app))
        if (!publicKey.isNullOrEmpty()) j.put("public_key", publicKey)
        if (!ticket.isNullOrEmpty()) j.put("ticket", ticket)
        val req = Request.Builder().url(join(base, "v1/enroll")).post(j.toString().toRequestBody(JSON)).build()
        http.newCall(req).execute().use { r ->
            return when (r.code) {
                200 -> parse(r.body?.string().orEmpty())
                409 -> DeviceStatus("conflict")
                429 -> DeviceStatus("limited")
                else -> other(r.code)
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
                else -> other(r.code)
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
        return DeviceStatus(s, j.optString("code"), j.optBoolean("voice", false), j.optBoolean("jev", false), j.optString("ticket"))
    }
}

/** 앱이 한 번에 받아 두는 SSE 줄의 한도 (게이트웨이는 64 MiB 까지 보낸다): 폰의 메모리가 바닥나지 않게. 더 긴 줄은 `lost` 로 본다. */
const val SSE_MAX_LINE = 16L * 1024 * 1024

/** SSE 한 줄이 [SSE_MAX_LINE] 보다 길었다. */
class SseTooLong : IOException("sse line too long")

/**
 * Server-Sent Events 를 읽는다: 빈 줄로 끝난(완성된) 사건의 `data` 만 `onData` 로 넘긴다 (여러 `data:` 줄은 줄바꿈으로 잇는다).
 * 연결이 사건 한가운데서 끊기면 그 반쪽 사건은 넘기지 않는다. `onEvent` 는 사건의 `id:` 도 받는다 (없으면 null).
 * 한 줄이 `maxLine` 바이트보다 길면 [SseTooLong].
 */
fun readSse(src: BufferedSource, onData: (String) -> Unit) = readSseEvents(src) { _, d -> onData(d) }

fun readSseEvents(src: BufferedSource, maxLine: Long = SSE_MAX_LINE, onEvent: (String?, String) -> Unit) {
    val data = StringBuilder()
    var has = false
    var id: String? = null
    while (true) {
        val nl = src.indexOf('\n'.code.toByte(), 0, maxLine + 1)
        if (nl < 0) {
            if (src.buffer.size > maxLine) throw SseTooLong()
            break // 끝 (줄바꿈 없이 끝난 반쪽 줄은 버린다)
        }
        var line = src.readUtf8(nl)
        src.skip(1)
        if (line.endsWith("\r")) line = line.dropLast(1)
        if (line.isEmpty()) {
            if (has) onEvent(id, data.toString())
            data.setLength(0)
            has = false
            id = null
            continue
        }
        if (line.startsWith(":")) continue
        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        var value = if (colon < 0) "" else line.substring(colon + 1)
        if (value.startsWith(" ")) value = value.substring(1)
        when (field) {
            "data" -> {
                if (has) data.append('\n')
                data.append(value)
                has = true
            }
            "id" -> id = value
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
    private val followHttp = http.newBuilder().readTimeout(FOLLOW_READ_MS, TimeUnit.MILLISECONDS).callTimeout(0, TimeUnit.MILLISECONDS).build()

    /** 요청을 만든다. 주소나 머리글 값이 잘못됐으면(잘못 만든 빌드) 다시 해도 소용없다: [SshFatal]. */
    private fun req(path: String): Request.Builder = try {
        Request.Builder().url(join(base, path)).header("Authorization", "Bearer ${token()}")
    } catch (e: IllegalArgumentException) {
        throw SshFatal(GatewayText.badConfig(english()))
    }

    private fun <T> call(r: Request, client: OkHttpClient = http, body: (Response) -> T): T {
        val c = client.newCall(r)
        calls.add(c)
        try {
            return c.execute().use(body)
        } catch (e: IllegalArgumentException) {
            throw SshFatal(GatewayText.badConfig(english()))
        } finally {
            calls.remove(c)
        }
    }

    /** 401·403: 다시 하지 않는다. 글은 단계에 맞게 (보내기 전 / 따라 읽는 중 / 멈춤·치우기). */
    private fun refusedCheck(r: Response, phase: LinkPhase) {
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
        throw SshFatal(GatewayText.refused(kind, code, english(), phase))
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

    /** (끝난 코드, 이유) 로 돌려주는 호출: 끊김은 음수, 3xx·4xx·모르는 2xx 는 양수. `ok` 가 답이 맞는지 본다. */
    private fun coded(r: Request, ok: (Response) -> Boolean): Pair<Int, String> = try {
        call(r) { resp ->
            refusedCheck(resp, LinkPhase.BEFORE)
            when {
                resp.isSuccessful -> if (ok(resp)) 0 to "" else 1 to "unexpected answer HTTP ${resp.code}"
                retryable(resp.code) -> -1 to ""
                else -> resp.code to short(resp)
            }
        }
    } catch (e: IOException) {
        -1 to ""
    }

    /** 실패하면 예외를 던지는 짧은 호출. */
    private fun text(r: Request, phase: LinkPhase = LinkPhase.BEFORE): String = call(r) { resp ->
        refusedCheck(resp, phase)
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

    /** 게이트웨이는 204 로 답한다. 다른 2xx 는 게이트웨이의 답이 아니다. */
    override fun upload(id: String, text: String?): Pair<Int, String> =
        coded(req("v1/runs/${Detached.check(id)}/input").put((text ?: "").toRequestBody(TEXT)).build()) { it.code == 204 }

    override fun start(id: String, onLine: (String) -> Unit): Pair<Int, String> {
        val j = JSONObject()
        if (effort.isNotEmpty()) j.put("effort", effort)
        return coded(req("v1/runs/${Detached.check(id)}/start").post(j.toString().toRequestBody(JSON)).build()) { resp ->
            val lines = mutableListOf<String>()
            readLines(java.io.StringReader(resp.body?.string().orEmpty()), false) { lines.add(it) }
            if (lines.any { Detached.classify(it) == FollowLine.Fail("busy") }) throw SshFatal(GatewayText.busy(english()))
            val known = lines.any { val c = Detached.classify(it); c is FollowLine.Run || c is FollowLine.Fail }
            if (known) lines.forEach(onLine)
            known
        }
    }

    /** `id:` 가 받은 줄 수 + 1 이 아니다: 빠지거나 겹친 줄이 있다. 끊김으로 보고 받은 줄 다음부터 다시 붙는다. */
    private class Gap : IOException("sse id gap")

    override fun follow(id: String, n: Int, onLine: (String) -> Unit): Pair<Int, String> {
        require(n >= 0)
        val r = req("v1/runs/${Detached.check(id)}/follow?n=$n")
            .header("Accept", "text/event-stream")
            .header("Accept-Encoding", "identity")
            .build()
        return try {
            call(r, followHttp) { resp ->
                refusedCheck(resp, LinkPhase.FOLLOW)
                when {
                    resp.isSuccessful && resp.header("Content-Type").orEmpty().startsWith("text/event-stream") -> {
                        var expect = n.toLong()
                        try {
                            readSseEvents(resp.body!!.source()) { eid, data ->
                                if (data.startsWith(">")) {
                                    if (eid != null && eid.trim().toLongOrNull() != expect + 1) throw Gap()
                                    expect++
                                }
                                onLine(data)
                            }
                        } catch (e: SseTooLong) {
                            onLine("APERITEXIT lost") // 받을 수 없는 길이의 줄: 서버도 이 실행을 멈춘다
                        }
                        0 to ""
                    }
                    // 2xx 인데 이벤트 흐름이 아니다 (게이트웨이의 답이 아님): 따라 읽기 고장으로 본다 (세 번 이어지면 멈춘다)
                    resp.isSuccessful -> 2 to "unexpected answer"
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
        text(req("v1/runs/${Detached.check(id)}/stop").post(ByteArray(0).toRequestBody(null)).build(), LinkPhase.AFTER)
        onLine(Detached.STOP_SENT)
        return 0 to ""
    }

    override fun clean(id: String) {
        text(req("v1/runs/${Detached.check(id)}").delete().build(), LinkPhase.AFTER)
    }

    override fun errTail(id: String): String = text(req("v1/runs/${Detached.check(id)}/err").get().build(), LinkPhase.AFTER)

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

    override fun uploadRefusedText(detail: String): String = if (english()) "The server did not accept this message ($detail) → it was not sent → shorten it if it is very long, otherwise send it again shortly."
    else "서버가 이 말을 받지 않았습니다 ($detail) → 이번 말은 보내지 않았습니다 → 아주 길면 줄여서, 아니면 잠시 뒤 다시 보내 주세요."

    override fun followBrokenText(): String = if (english()) "The server's answer could not be read three times in a row → the work on the server is stopped at the next connection → send the same message again shortly."
    else "서버의 답을 세 번 이어서 읽지 못했습니다 → 서버의 작업은 다음에 연결할 때 멈춥니다 → 잠시 뒤 같은 말을 다시 보내 주세요."
}
