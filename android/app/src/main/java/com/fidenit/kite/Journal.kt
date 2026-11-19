// 앱이 꺼져도 남겨 두는 기록: 돌고 있는 턴(inflight.json), 줄 세워 둔 말과 카드 조작 알림(queue.json),
// 서버에서 치우지 못한 실행 번호(toclean.json), 지금 보는 주제(current.txt).
// 안드로이드 API 를 쓰지 않는다 (JVM 에서 시험한다). 쓰기는 모두 임시 파일에 쓴 뒤 바꿔 넣고,
// 읽다가 깨진 파일은 없는 것으로 본다 — 어떤 경우에도 예외를 밖으로 내지 않는다.
package com.fidenit.kite

import com.fidenit.kite.engine.Detached
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** 돌고 있는 턴. `engine` 은 "ssh" 또는 "api", `id` 는 서버에서 떼어 돌리는 실행 번호(없으면 빈 글), `board` 는 턴 시작 전 보드(`Lang.save`). */
class Inflight(
    val engine: String,
    val id: String,
    val turnText: String,
    val shown: String,
    val board: String,
    val startedAt: Long,
)

class Queue(val pending: List<String>, val events: List<String>)

class Journal(private val dir: File) {
    private val inflightFile get() = File(dir, "inflight.json")
    private val queueFile get() = File(dir, "queue.json")
    private val toCleanFile get() = File(dir, "toclean.json")
    private val currentFile get() = File(dir, "current.txt")

    fun writeInflight(i: Inflight) {
        val j = JSONObject().put("engine", i.engine).put("id", i.id).put("turnText", i.turnText).put("shown", i.shown).put("board", i.board).put("startedAt", i.startedAt)
        writeAtomic(inflightFile, j.toString())
    }

    /** 없거나 깨졌거나 모르는 값이 있으면 null. */
    fun readInflight(): Inflight? = try {
        val j = JSONObject(inflightFile.readText())
        val engine = j.getString("engine")
        val id = j.getString("id")
        if ((engine != "ssh" && engine != "api") || (id.isNotEmpty() && !Detached.valid(id))) null
        else Inflight(engine, id, j.getString("turnText"), j.getString("shown"), j.getString("board"), j.getLong("startedAt"))
    } catch (_: Exception) {
        null
    }

    fun clearInflight() {
        try {
            inflightFile.delete()
        } catch (_: Exception) {
        }
    }

    fun writeQueue(pending: List<String>, events: List<String>) {
        writeAtomic(queueFile, JSONObject().put("pending", JSONArray(pending)).put("events", JSONArray(events)).toString())
    }

    fun readQueue(): Queue = try {
        val j = JSONObject(queueFile.readText())
        Queue(strings(j.getJSONArray("pending")), strings(j.getJSONArray("events")))
    } catch (_: Exception) {
        Queue(emptyList(), emptyList())
    }

    fun clearQueue() {
        try {
            queueFile.delete()
        } catch (_: Exception) {
        }
    }

    fun writeToClean(ids: List<String>) {
        writeAtomic(toCleanFile, JSONArray(ids.filter { Detached.valid(it) }).toString())
    }

    fun readToClean(): List<String> = try {
        strings(JSONArray(toCleanFile.readText())).filter { Detached.valid(it) }
    } catch (_: Exception) {
        emptyList()
    }

    fun writeCurrent(id: String?) {
        writeAtomic(currentFile, id.orEmpty())
    }

    fun readCurrent(): String? = try {
        currentFile.readText().trim().ifEmpty { null }
    } catch (_: Exception) {
        null
    }

    private fun strings(a: JSONArray): List<String> = (0 until a.length()).map { a.getString(it) }

    companion object {
        /** 임시 파일에 다 쓴 뒤 바꿔 넣는다: 도중에 꺼져도 옛 파일이나 새 파일이 통째로 남는다. 실패해도 예외를 내지 않고 거짓을 돌려준다. */
        fun writeAtomic(file: File, text: String): Boolean {
            val tmp = File(file.parentFile, file.name + ".tmp")
            return try {
                FileOutputStream(tmp).use {
                    it.write(text.toByteArray(Charsets.UTF_8))
                    it.fd.sync()
                }
                try {
                    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                true
            } catch (_: Exception) {
                try {
                    tmp.delete()
                } catch (_: Exception) {
                }
                false
            }
        }
    }
}
