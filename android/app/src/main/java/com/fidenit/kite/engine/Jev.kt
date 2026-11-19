// TypeSafe AI 의 판단 전용 모델 Jev 를 부른다. 데스크톱의 src/jev.rs 와 같은 요청·응답이다.
// Jev 는 글을 쓰지 않는다. 상황(state)과 미리 정한 질문들을 주면 질문마다 "정해 준 선택지 중 하나"나 "0~1 사이의 확률"만 돌려준다.
// 안드로이드 API 를 쓰지 않는다 (JVM 에서 시험한다). 키는 부르는 쪽이 Store 에서 읽어 넘긴다 — 여기서는 어디에도 적지 않는다.
package com.fidenit.kite.engine

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** Jev 의 응답에서 읽은 판단 하나. `value` 는 noul 질문의 확률, 또는 score 질문의 단계 번호(0, 1, 2 …) 위의 위치. */
class JevAnswer(val choice: String?, val value: Double?, val confidence: Double)

/** 응답이 약속한 모양이 아니다. */
class JevError(message: String) : Exception(message)

object Jev {
    const val URL = "https://api.typesafe.ai/v1/systemone"

    /** 전체 한도. 빨라야 의미가 있는 결정이라서, 늦으면 기다리지 않고 기본값으로 간다. */
    const val LIMIT_MS = 2500L

    /** 선택지 중 하나를 고르게 하는 질문. `options` 는 (이름, 설명). */
    fun choice(instructions: String, options: List<Pair<String, String>>): JSONObject {
        val criteria = JSONObject()
        for ((k, v) in options) criteria.put(k, v)
        return JSONObject().put("type", "choice").put("instructions", instructions).put("criteria", criteria)
    }

    /** 문장이 참일 확률(0~1)을 묻는 질문. */
    fun noul(statement: String): JSONObject = JSONObject().put("type", "noul").put("instructions", statement)

    /** 낮은 쪽부터 높은 쪽으로 늘어놓은 단계 위에서의 위치를 묻는 질문. */
    fun score(instructions: String, steps: List<String>): JSONObject =
        JSONObject().put("type", "score").put("instructions", instructions).put("criteria", JSONArray(steps))

    /** 응답 본문을 해석한다. 모양이 다르면 [JevError]. */
    fun parse(text: String): Map<String, JevAnswer> {
        val v = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw JevError("Jev 응답 해석 실패: ${e.message}")
        }
        val answers = v.optJSONObject("answers")
            ?: throw JevError("Jev 오류: " + (v.optJSONObject("error")?.optString("message", "")?.ifEmpty { null } ?: v.optString("message", "").ifEmpty { null } ?: text.take(200)))
        val out = LinkedHashMap<String, JevAnswer>()
        for (name in answers.keys()) {
            val a = answers.optJSONObject(name)
            val value = (a?.opt("score") as? Number)?.toDouble() ?: (a?.opt("noul") as? Number)?.toDouble()
            // noul 에는 confidence 가 없다: 0.5 에서 멀수록 확신이 큰 것으로 본다.
            val confidence = (a?.opt("confidence") as? Number)?.toDouble() ?: value?.let { abs(it - 0.5) * 2.0 } ?: 0.0
            out[name] = JevAnswer(a?.opt("choice") as? String, value, confidence)
        }
        return out
    }

    /** 요청 본문. */
    fun body(state: String, questions: JSONObject): String =
        JSONObject().put("model", "jev-latest").put("state", state).put("questions", questions).toString()

    /**
     * 질문 묶음을 한 번에 보낸다. 키가 없거나, 200 이 아니거나, 늦거나(`limitMs` 안에 끝나지 않음), 모양이 다르면
     * 값 없음(null)이다 — 호출한 쪽은 기본값으로 진행한다. 예외는 밖으로 내지 않는다. 이유는 `fail` 로 알린다 (기록용, 화면에 띄우지 않는다).
     * `url` 은 시험에서 바꿀 수 있다. https 만 쓴다 (시험용 로컬 주소만 예외).
     */
    fun decide(key: String, state: String, questions: JSONObject, url: String = URL, limitMs: Long = LIMIT_MS, fail: (String) -> Unit = {}): Map<String, JevAnswer>? {
        val k = key.trim()
        if (k.isEmpty()) {
            fail("Jev 키 없음")
            return null
        }
        if (!(url.startsWith("https://") || url.startsWith("http://127.0.0.1") || url.startsWith("http://localhost"))) {
            fail("Jev 주소가 https 가 아님")
            return null
        }
        val conn = AtomicReference<HttpURLConnection?>()
        val done = AtomicReference<Result<Map<String, JevAnswer>>?>()
        val worker = Thread {
            done.set(runCatching { request(k, body(state, questions), url, limitMs, conn) })
        }
        worker.isDaemon = true
        worker.start()
        try {
            worker.join(limitMs)
        } catch (_: InterruptedException) {
        }
        val r = done.get()
        if (r == null) {
            // 늦었다: 기다리지 않고 연결을 끊는다.
            try {
                conn.get()?.disconnect()
            } catch (_: Exception) {
            }
            fail("Jev 응답이 ${limitMs}ms 안에 오지 않음")
            return null
        }
        return r.getOrElse {
            fail(it.message ?: it.toString())
            null
        }
    }

    private fun request(key: String, body: String, url: String, limitMs: Long, holder: AtomicReference<HttpURLConnection?>): Map<String, JevAnswer> {
        val c = URL(url).openConnection() as HttpURLConnection
        holder.set(c)
        try {
            c.requestMethod = "POST"
            c.connectTimeout = limitMs.toInt()
            c.readTimeout = limitMs.toInt()
            c.doOutput = true
            c.setRequestProperty("Authorization", "Bearer $key")
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
            if (code != 200) throw JevError("Jev HTTP $code: ${text.take(200)}")
            return parse(text)
        } finally {
            c.disconnect()
        }
    }
}
