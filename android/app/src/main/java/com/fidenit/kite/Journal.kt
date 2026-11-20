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
    /**
     * 이 턴의 말이 주고받은 말 가운데 몇 번째가 되는가 (0부터 세는 차례 번호, `recent.json` 의 셋째 값과 같은 번호).
     * 다시 붙을 때 이 번호부터를 빼서 같은 말이 두 번 들어가지 않게 한다. 모르면(옛 기록) -1: 아무것도 빼지 않는다.
     */
    val recentSeq: Int = -1,
    /** 이 턴을 돌린 서버 ("계정@주소:포트"). 다른 서버에서는 이어 받지도 다시 시작하지도 않는다. API 엔진이나 옛 기록이면 빈 글. */
    val server: String = "",
    /** 서버에서 실행이 시작된 것을 확인했는가. 참이면 서버에 그 실행이 없다고 해도 다시 시작하지 않는다 (옛 기록은 참으로 읽는다). */
    val started: Boolean = false,
)

class Queue(val pending: List<String>, val events: List<String>)

class Journal(private val dir: File, private val async: Boolean = false) {
    // async 면 쓰기는 일꾼 스레드 하나가 들어온 순서 그대로 한다. 지우기도 줄의 한 칸이고, 이미 줄에 있는 파일을 다시 써도
    // 앞의 칸을 빼거나 옮기지 않는다 (줄의 맨 끝 칸이 같은 파일일 때만 그 칸의 내용을 바꾼다).
    // 그래서 어느 순간에 꺼져도 디스크에는 앱이 실제로 지나온 어느 한 순간의 상태가 남는다:
    // "보드를 쓴 다음 기록을 지운다"가 그 뒤에 온 쓰기 때문에 "기록을 지운 다음 보드를 쓴다"로 바뀌지 않는다.
    private class Entry(val name: String, var text: String?) // text 가 null 이면 지운다

    private val lock = Object()
    private val queued = ArrayDeque<Entry>()
    private var running = false // 일꾼이 맡은 일이 남아 있다 (줄에 있거나 지금 쓰는 중)
    // 마지막 쓰기(지우기)가 실패한 파일과 그때 쓰려던 내용 (null 이면 지우기). 그 파일을 그 뒤에 다시 쓰거나 지우면 (되든 안 되든)
    // 이 칸은 그 새 내용으로 바뀌거나 없어진다: 그래서 여기 남은 내용은 늘 그 파일에 쓰려던 가장 새 내용이다.
    private val failed = LinkedHashMap<String, String?>()
    private var retry = false // flush 가 불렸다: 줄이 비면 실패한 파일을 한 번 더 써 본다
    private val worker by lazy { java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "journal").apply { isDaemon = true } } }

    /** 시험용: 일꾼이 파일 하나를 쓰기(지우기) 바로 전에 일꾼 스레드에서 불린다. */
    @Volatile
    internal var beforeApply: ((name: String, text: String?) -> Unit)? = null

    /** 시험용: 아직 줄에 서 있는 파일 이름 (순서대로). */
    internal fun queuedNames(): List<String> = synchronized(lock) { queued.map { it.name } }

    private val inflightFile get() = File(dir, "inflight.json")
    private val queueFile get() = File(dir, "queue.json")
    private val toCleanFile get() = File(dir, "toclean.json")
    private val currentFile get() = File(dir, "current.txt")

    /** 파일 하나를 쓴다 (`null` 이면 지운다). */
    private fun put(file: File, text: String?) {
        if (!async) {
            synchronized(lock) { settle(file.name, text, apply(file, text)) }
            return
        }
        synchronized(lock) {
            val last = queued.lastOrNull()
            if (last != null && last.name == file.name) last.text = text else queued.addLast(Entry(file.name, text))
            if (!running) {
                running = true
                worker.execute { drain() }
            }
        }
    }

    private fun apply(file: File, text: String?): Boolean =
        if (text == null) {
            try {
                file.delete()
                !file.exists()
            } catch (_: Exception) {
                false
            }
        } else {
            writeAtomic(file, text)
        }

    /** 쓰기(지우기) 하나의 결과를 적는다 (`lock` 안에서 부른다). */
    private fun settle(name: String, text: String?, ok: Boolean) {
        failed.remove(name)
        if (!ok) failed[name] = text
    }

    private fun drain() {
        while (true) {
            val e = synchronized(lock) {
                // 다시 써 보기는 줄이 비었을 때만 줄에 세운다: 실패한 내용은 그 파일의 가장 새 내용이고, 그 뒤에 들어오는 쓰기·지우기는
                // 모두 이 칸 뒤에 선다. 그래서 다시 쓴 옛 내용이 새 내용을 덮거나, 뒤에 지우기로 한 파일을 되살린 채로 끝나지 않는다.
                if (queued.isEmpty() && retry) {
                    retry = false
                    failed.forEach { (name, text) -> queued.addLast(Entry(name, text)) }
                }
                val head = queued.removeFirstOrNull()
                if (head == null) {
                    running = false
                    lock.notifyAll()
                    return
                }
                head
            }
            val ok = try {
                beforeApply?.invoke(e.name, e.text)
                apply(File(dir, e.name), e.text)
            } catch (_: Throwable) {
                false
            }
            synchronized(lock) { settle(e.name, e.text, ok) }
        }
    }

    /**
     * 밀린 쓰기가 모두 디스크에 닿을 때까지 (최대 `timeoutMs`) 기다린다.
     * 다 닿았고 마지막 쓰기가 실패한 채인 파일이 없을 때만 참: 거짓이면 그 뒤에 하려던 일(기록 지우기, 서버 파일 치우기)을 하면 안 된다.
     * 실패한 채인 파일이 있으면 부를 때마다 그 파일을 한 번 더 써 본다 (일꾼이, 밀린 쓰기 다음에): 디스크가 다시 되면
     * 그 파일을 누가 다시 쓰지 않아도 참으로 돌아오고, 계속 안 되면 계속 거짓이다.
     */
    fun flush(timeoutMs: Long = 5000): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        synchronized(lock) {
            if (!async) {
                failed.toList().forEach { (name, text) -> settle(name, text, apply(File(dir, name), text)) }
                return failed.isEmpty()
            }
            if (running || failed.isNotEmpty()) {
                retry = true
                if (!running) {
                    running = true
                    worker.execute { drain() }
                }
            }
            while (running) {
                val left = end - System.currentTimeMillis()
                if (left <= 0) return false
                lock.wait(minOf(left, 100L))
            }
            return failed.isEmpty()
        }
    }

    /** 보드·대화 같은 다른 파일도 같은 일꾼으로 쓴다 (순서와 마지막 것만 남기기가 같다). */
    fun writeFile(name: String, text: String) = put(File(dir, name), text)

    fun writeInflight(i: Inflight) {
        val j = JSONObject().put("engine", i.engine).put("id", i.id).put("turnText", i.turnText).put("shown", i.shown).put("board", i.board).put("startedAt", i.startedAt).put("recentSeq", i.recentSeq).put("server", i.server).put("started", i.started)
        put(inflightFile, j.toString())
    }

    /** 없거나 깨졌거나 모르는 값이 있으면 null. */
    fun readInflight(): Inflight? = try {
        flush()
        val j = JSONObject(inflightFile.readText())
        val engine = j.getString("engine")
        val id = j.getString("id")
        if ((engine != "ssh" && engine != "api") || (id.isNotEmpty() && !Detached.valid(id))) null
        else Inflight(engine, id, j.getString("turnText"), j.getString("shown"), j.getString("board"), j.getLong("startedAt"), j.optInt("recentSeq", -1), j.optString("server", ""), j.optBoolean("started", true))
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

    // 치울 목록은 메모리에 들고 있다 (만들 때 한 번 읽는다): 더하고 빼는 일이 디스크를 읽거나 일꾼을 기다리지 않는다
    // (멈춤 버튼은 메인 스레드에서 번호를 더한다). 바뀔 때마다 통째로 줄에 세워 쓴다.
    private val cleanIds = ArrayList<String>()

    init {
        try {
            cleanIds.addAll(strings(JSONArray(toCleanFile.readText())).filter { Detached.valid(it) }.distinct())
        } catch (_: Exception) {
        }
    }

    private fun putClean() = put(toCleanFile, JSONArray(cleanIds).toString())

    fun writeToClean(ids: List<String>) = synchronized(lock) {
        cleanIds.clear()
        cleanIds.addAll(ids.filter { Detached.valid(it) }.distinct())
        putClean()
    }

    /** 치울 목록에 더한다 (합친다, 덮어쓰지 않는다). */
    fun addToClean(ids: Collection<String>) = synchronized(lock) {
        val fresh = ids.filter { Detached.valid(it) && it !in cleanIds }.distinct()
        if (fresh.isNotEmpty()) {
            cleanIds.addAll(fresh)
            putClean()
        }
    }

    /** 서버에서 멈추고 치운 것이 확인된 실행만 목록에서 뺀다. */
    fun removeToClean(ids: Collection<String>) = synchronized(lock) {
        if (cleanIds.removeAll(ids.toSet())) putClean()
    }

    fun readToClean(): List<String> = synchronized(lock) { cleanIds.toList() }

    // 지금 보는 주제도 메모리에 들고 있다 (만들 때 한 번 읽는다): 읽는 쪽(메인 스레드)이 디스크도 일꾼도 기다리지 않고, 방금 쓴 값을 그대로 읽는다.
    @Volatile
    private var currentId: String? = try {
        currentFile.readText().trim().ifEmpty { null }
    } catch (_: Exception) {
        null
    }

    fun writeCurrent(id: String?) {
        currentId = id?.trim()?.ifEmpty { null }
        put(currentFile, id.orEmpty())
    }

    /** 지금 보는 주제 (마지막으로 쓴 값). 일꾼을 기다리지 않는다: 메인 스레드에서 부른다. */
    fun readCurrent(): String? = currentId

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
