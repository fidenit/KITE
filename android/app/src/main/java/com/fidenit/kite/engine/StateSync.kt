// APERIT Pro: 보드와 대화 기록을 게이트웨이(`/v1/state`)에도 둔다. 폰이 먼저 저장하고, 조금 뒤에 서버로 올린다 (화면을 막지 않는다).
package com.fidenit.kite.engine

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** 서버에 저장된 상태: 판 번호, 저장 시각, 앱의 문서. */
data class RemoteState(val rev: Int, val savedAt: String, val doc: JSONObject)

sealed class PutResult {
    data class Ok(val rev: Int) : PutResult()

    /** 서버의 판이 앱이 바탕으로 삼은 판보다 새롭다 (412). */
    data class Conflict(val rev: Int) : PutResult()
}

/** `GET/PUT /v1/state`. 연결이 안 되거나 서버가 잠깐 못 받으면 IOException, 다시 해도 소용없는 거절은 [RemoteFail]·[SshFatal]. */
class StateClient(private val base: String, private val token: () -> String, private val http: OkHttpClient = gatewayClient()) {
    private val url get() = base.trimEnd('/') + "/v1/state"

    private fun fail(code: Int, body: String): Nothing {
        val e = try {
            JSONObject(body).optString("error")
        } catch (_: Exception) {
            ""
        }
        if (code == 401 || code == 403) throw SshFatal("state HTTP $code $e")
        if (code == 408 || code == 429 || code in 500..599) throw IOException("state HTTP $code")
        throw RemoteFail("state HTTP $code $e")
    }

    /** 저장된 것이 없으면 null. */
    fun get(): RemoteState? {
        val r = Request.Builder().url(url).header("Authorization", "Bearer ${token()}").get().build()
        http.newCall(r).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (resp.code == 404) return null
            if (!resp.isSuccessful) fail(resp.code, body)
            val j = try {
                JSONObject(body)
            } catch (_: Exception) {
                throw IOException("bad answer")
            }
            val doc = j.optJSONObject("doc") ?: throw IOException("bad answer")
            return RemoteState(j.optInt("rev", 0), j.optString("saved_at"), doc)
        }
    }

    /** `baseRev` 위에 올린다 (`If-Match: "baseRev"`, 없으면 0). */
    fun put(doc: JSONObject, baseRev: Int): PutResult {
        val r = Request.Builder().url(url)
            .header("Authorization", "Bearer ${token()}")
            .header("If-Match", "\"$baseRev\"")
            .put(doc.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        http.newCall(r).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            val rev = try {
                JSONObject(body).optInt("rev", -1)
            } catch (_: Exception) {
                -1
            }
            return when {
                resp.code == 200 && rev >= 0 -> PutResult.Ok(rev)
                resp.code == 412 -> PutResult.Conflict(rev)
                else -> fail(resp.code, body)
            }
        }
    }
}

/** 앱이 마지막으로 서버와 맞춘 판 번호와, 그 뒤로 올리지 않은 변경이 있는지 (암호화 설정에 둔다). */
interface StateMarks {
    var stateRev: String
    var stateDirty: String
}

/**
 * 규칙 (spec §4, 기기 하나):
 * - 앱이 저장할 때마다 [changed] → [debounceMs] 뒤에 올린다. 연결이 안 되면 1·2·4·8·15·30초 간격으로 다시 한다. 화면을 막지 않는다.
 * - 허용된 폰이 켜질 때 [start]: 서버 것을 받아, 폰에 저장된 보드가 없거나 폰의 판이 서버보다 오래됐으면 서버 것을 쓴다 ([apply] 가 폰의 것을 따로 남긴다).
 * - 412: 서버 것을 받아 그것을 쓰고 [onConflict] 로 알린다.
 * [apply] 는 이 클래스의 스레드에서 불린다. 지금 바꿀 수 없으면(턴이 도는 중) false: 다음에 다시 받는다.
 */
class StateSync(
    private val client: StateClient,
    private val marks: StateMarks,
    private val apply: (JSONObject) -> Boolean,
    private val onConflict: () -> Unit,
    private val debounceMs: Long = 2000,
    private val log: (String) -> Unit = {},
) {
    enum class Outcome { NONE, PUSHED, PULLED, CONFLICT, DEFERRED, FAILED, REFUSED }

    private val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "aperit-state").apply { isDaemon = true } }
    private val changes = AtomicInteger(0)

    @Volatile
    private var latest: JSONObject? = null
    private var pending: ScheduledFuture<*>? = null
    private var attempt = 0

    private var rev: Int
        get() = marks.stateRev.toIntOrNull() ?: 0
        set(v) {
            marks.stateRev = v.toString()
        }

    private var dirty: Boolean
        get() = marks.stateDirty == "1"
        set(v) {
            marks.stateDirty = if (v) "1" else ""
        }

    /** 폰에 저장한 직후에 부른다 (메인 스레드에서 떠 둔 문서). */
    @Synchronized
    fun changed(doc: JSONObject) {
        latest = doc
        changes.incrementAndGet()
        dirty = true
        schedulePush(debounceMs)
    }

    /** 허용된 폰이 켜졌다. `doc` 은 지금 폰의 문서 (저장된 보드가 없으면 null). */
    fun start(doc: JSONObject?) {
        if (doc != null && latest == null) latest = doc
        exec.execute { runPull(doc == null) }
    }

    @Synchronized
    private fun schedulePush(delay: Long) {
        pending?.cancel(false)
        pending = exec.schedule(Runnable { runPush() }, delay, TimeUnit.MILLISECONDS)
    }

    private fun retry(o: Outcome, again: () -> Unit) {
        if (o != Outcome.FAILED) {
            attempt = 0
            return
        }
        val wait = Detached.delayMs(attempt++)
        exec.schedule(Runnable { again() }, wait, TimeUnit.MILLISECONDS)
    }

    private fun runPush(): Unit = retry(pushNow()) { runPush() }

    private fun runPull(emptyPhone: Boolean): Unit = retry(pullNow(emptyPhone)) { runPull(emptyPhone) }

    /** 올리지 않은 변경을 올린다 (이 클래스의 스레드, 시험에서는 바로). */
    fun pushNow(): Outcome {
        if (!dirty) return Outcome.NONE
        val doc = latest ?: return Outcome.NONE
        val seen = changes.get()
        return try {
            when (val r = client.put(doc, rev)) {
                is PutResult.Ok -> {
                    rev = r.rev
                    if (changes.get() == seen) dirty = false
                    log("state_put rev=${r.rev} bytes=${doc.toString().length}")
                    Outcome.PUSHED
                }
                is PutResult.Conflict -> {
                    log("state_conflict server_rev=${r.rev} base=$rev")
                    if (takeServer() == Outcome.PULLED) {
                        dirty = false
                        onConflict()
                        Outcome.CONFLICT
                    } else {
                        Outcome.DEFERRED
                    }
                }
            }
        } catch (e: IOException) {
            Outcome.FAILED
        } catch (e: Exception) {
            log("state_put refused ${e.message}")
            Outcome.REFUSED
        }
    }

    /** 켜질 때: 서버 것을 받아 규칙대로 쓰거나, 서버에 없으면 폰의 것을 올린다. */
    fun pullNow(emptyPhone: Boolean): Outcome = try {
        val remote = client.get()
        when {
            remote == null -> {
                if (latest != null) dirty = true
                pushNow().let { if (it == Outcome.NONE) Outcome.NONE else it }
            }
            emptyPhone || rev < remote.rev -> use(remote)
            else -> pushNow()
        }
    } catch (e: IOException) {
        Outcome.FAILED
    } catch (e: Exception) {
        log("state_get refused ${e.message}")
        Outcome.REFUSED
    }

    private fun takeServer(): Outcome {
        val remote = client.get() ?: return Outcome.NONE
        return use(remote)
    }

    private fun use(remote: RemoteState): Outcome {
        if (!apply(remote.doc)) return Outcome.DEFERRED
        rev = remote.rev
        dirty = false
        latest = remote.doc
        log("state_get rev=${remote.rev} bytes=${remote.doc.toString().length}")
        return Outcome.PULLED
    }

    /** 시험용: 이 클래스의 스레드에 맡긴 일이 끝나기를 기다린다. */
    fun drain(ms: Long = 5000): Boolean {
        val f = exec.submit(Runnable {})
        return try {
            f.get(ms, TimeUnit.MILLISECONDS)
            true
        } catch (_: Exception) {
            false
        }
    }
}
