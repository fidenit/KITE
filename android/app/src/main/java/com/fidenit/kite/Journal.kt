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
data class Inflight(
    val engine: String,
    val id: String,
    val turnText: String,
    val shown: String,
    val board: String,
    val startedAt: Long,
    /** 턴 시작 전 `recent`(주고받은 말)의 길이 (최대 39). 다시 붙을 때 이 길이로 되돌려 같은 말이 두 번 들어가지 않게 한다. 모르면 -1. */
    val recentLen: Int = -1,
)

class Queue(val pending: List<String>, val events: List<String>)

class Journal(private val dir: File, private val async: Boolean = false) {
    // async 면 쓰기는 일꾼 스레드 하나에서 한다: 같은 파일에 대한 쓰기는 마지막 것만 남기고, 파일 사이의 순서는 지킨다.
    // flush() 는 밀린 쓰기가 다 끝날 때까지 기다린다 (뒤로 갈 때, 서버 파일을 치우기 전, 뷰모델이 없어질 때).
    private val lock = Object()
    private val queued = LinkedHashMap<String, String?>() // 파일 이름 -> 내용 (null 이면 지운다)
    private var scheduled = false
    private val worker by lazy { java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "journal").apply { isDaemon = true } } }

    private val inflightFile get() = File(dir, "inflight.json")
    private val queueFile get() = File(dir, "queue.json")
    private val toCleanFile get() = File(dir, "toclean.json")
    private val currentFile get() = File(dir, "current.txt")

    /** 파일 하나를 쓴다 (`null` 이면 지운다). */
    private fun put(file: File, text: String?) {
        if (!async) {
            apply(file, text)
            return
        }
        synchronized(lock) {
            queued.remove(file.name)
            queued[file.name] = text
            if (!scheduled) {
                scheduled = true
                worker.execute { drain() }
            }
        }
    }

    private fun apply(file: File, text: String?) {
        if (text == null) {
            try {
                file.delete()
            } catch (_: Exception) {
            }
        } else {
            writeAtomic(file, text)
        }
    }

    private fun drain() {
        while (true) {
            val batch = synchronized(lock) {
                if (queued.isEmpty()) {
                    scheduled = false
                    lock.notifyAll()
                    return
                }
                LinkedHashMap(queued).also { queued.clear() }
            }
            for ((name, text) in batch) apply(File(dir, name), text)
        }
    }

    /** 밀린 쓰기가 다 끝날 때까지 (최대 5초) 기다린다. */
    fun flush() {
        if (!async) return
        val end = System.currentTimeMillis() + 5000
        synchronized(lock) {
            while (scheduled && System.currentTimeMillis() < end) lock.wait(100)
        }
    }

    /** 보드·대화 같은 다른 파일도 같은 일꾼으로 쓴다 (순서와 마지막 것만 남기기가 같다). */
    fun writeFile(name: String, text: String) = put(File(dir, name), text)

    fun writeInflight(i: Inflight) {
        val j = JSONObject().put("engine", i.engine).put("id", i.id).put("turnText", i.turnText).put("shown", i.shown).put("board", i.board).put("startedAt", i.startedAt).put("recentLen", i.recentLen)
        put(inflightFile, j.toString())
    }

    /** 없거나 깨졌거나 모르는 값이 있으면 null. */
    fun readInflight(): Inflight? = try {
        flush()
        val j = JSONObject(inflightFile.readText())
        val engine = j.getString("engine")
        val id = j.getString("id")
        if ((engine != "ssh" && engine != "api") || (id.isNotEmpty() && !Detached.valid(id))) null
        else Inflight(engine, id, j.getString("turnText"), j.getString("shown"), j.getString("board"), j.getLong("startedAt"), j.optInt("recentLen", -1))
    } catch (_: Exception) {
        null
    }

    fun clearInflight() {
        put(inflightFile, null)
    }

    fun writeQueue(pending: List<String>, events: List<String>) {
        put(queueFile, JSONObject().put("pending", JSONArray(pending)).put("events", JSONArray(events)).toString())
    }

    fun readQueue(): Queue = try {
        flush()
        val j = JSONObject(queueFile.readText())
        Queue(strings(j.getJSONArray("pending")), strings(j.getJSONArray("events")))
    } catch (_: Exception) {
        Queue(emptyList(), emptyList())
    }

    fun clearQueue() {
        put(queueFile, null)
    }

    fun writeToClean(ids: List<String>) {
        put(toCleanFile, JSONArray(ids.filter { Detached.valid(it) }.distinct()).toString())
    }

    /** 치울 목록에 더한다 (합친다, 덮어쓰지 않는다). */
    @Synchronized
    fun addToClean(ids: Collection<String>) {
        val have = readToClean()
        writeToClean(have + ids.filter { it !in have })
    }

    /** 서버에서 멈추고 치운 것이 확인된 실행만 목록에서 뺀다. */
    @Synchronized
    fun removeToClean(ids: Collection<String>) {
        writeToClean(readToClean().filter { it !in ids })
    }

    fun readToClean(): List<String> = try {
        flush()
        strings(JSONArray(toCleanFile.readText())).filter { Detached.valid(it) }
    } catch (_: Exception) {
        emptyList()
    }

    fun writeCurrent(id: String?) {
        put(currentFile, id.orEmpty())
    }

    fun readCurrent(): String? = try {
        flush()
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
