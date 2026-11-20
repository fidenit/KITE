// APERIT Pro 실시간 읽기의 재생 줄 (spec aperit-pro-voice-realtime-v1 v1.2 (d)): 소켓 스레드는 스피커를 만지지 않는다.
// 소켓 스레드는 소리 조각과 문장 표시(start/done/skipped/끊김)를 한 줄에 차례로 넣기만 하고, 재생 스레드 하나가 그 줄을 따라
// 스피커에 40ms 이하씩 막히지 않게 쓴다. 멈춤은 세대 번호를 먼저 올리고 줄을 비운 뒤 스피커를 비운다: 옛 세대의 소리는 다시 나가지 않는다.
// 안드로이드 API 를 쓰지 않는다 (JVM 에서 시험한다).
package com.fidenit.kite.voice

import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class LivePlay(
    private val out: Out,
    private val events: Events,
    /** 줄에 쌓아 둘 수 있는 소리 (24 kHz 샘플 수, 약 120초). 넘치면 [Events.overflow]. */
    private val maxSamples: Long = TTS_RATE * 120L,
    /** 재생 스레드를 띄운다 (시험은 끄고 [pump] 로 한 걸음씩) */
    thread: Boolean = true,
) {
    /** 스피커 (앱에서는 [Player]) */
    interface Out {
        /** 막히지 않게 쓴다: 쓴 샘플 수 (자리가 없으면 0) */
        fun writeSome(pcm: ShortArray, off: Int, n: Int): Int

        /** 지금까지 튼 샘플 수 (비우면 0 부터) */
        fun played(): Long

        /** 틀지 않은 소리를 바로 버린다 */
        fun flush()
    }

    /** 재생 스레드가 표시에 닿았다 (재생 스레드에서 불린다: 받는 쪽이 제 스레드로 옮긴다). [gen] 이 지금 세대가 아니면 버린다. */
    interface Events {
        fun start(gen: Int, id: Int)
        fun done(gen: Int, id: Int)
        fun skipped(gen: Int, id: Int, error: String)
        fun broke(gen: Int, why: String)
        fun overflow(gen: Int)
    }

    private sealed class Item(val gen: Int) {
        class Start(gen: Int, val id: Int) : Item(gen)
        class Audio(gen: Int, val pcm: ShortArray) : Item(gen)
        class Done(gen: Int, val id: Int) : Item(gen)
        class Skipped(gen: Int, val id: Int, val error: String) : Item(gen)
        class Broke(gen: Int, val why: String) : Item(gen)
    }

    private val lock = Any()
    private val queue = LinkedBlockingDeque<Item>()

    @Volatile
    var gen = 1
        private set

    private val queued = AtomicLong(0)
    private val written = AtomicLong(0)

    @Volatile
    private var base = 0L

    /** 받은 소리·튼 소리가 늘 때마다 는다 (멈춘 채인지 보는 감시용) */
    private val moved = AtomicLong(0)

    @Volatile
    private var running = true
    private val worker: Thread? = if (thread) Thread({ loop() }, "aperit-live-play").apply {
        isDaemon = true
        start()
    } else null

    /** 이 번호까지의 문장은 버린다 (멈춤). 세대와 같은 자물쇠 안에서만 바꾸고 본다. */
    private var dropUpTo = 0

    private fun add(id: Int, make: (Int) -> Item, samples: Int = 0): Boolean = synchronized(lock) {
        if (id <= dropUpTo) return true // 멈춘 문장: 받아도 버린다
        if (samples > 0 && queued.get() + samples > maxSamples) {
            val g = gen
            events.overflow(g)
            return false
        }
        queue.addLast(make(gen))
        if (samples > 0) {
            queued.addAndGet(samples.toLong())
            moved.incrementAndGet()
        }
        true
    }

    // ---- 소켓 스레드
    fun start(id: Int) = add(id, { Item.Start(it, id) })

    /** 문장 [id] 의 소리. 줄이 넘치면 거짓 ([Events.overflow] 도 불린다). */
    fun audio(id: Int, pcm: ShortArray): Boolean = add(id, { Item.Audio(it, pcm) }, pcm.size)
    fun done(id: Int) = add(id, { Item.Done(it, id) })
    fun skipped(id: Int, error: String) = add(id, { Item.Skipped(it, id, error) })

    /** 소켓이 답 중간에 끊겼다: 이미 받은 소리를 다 튼 뒤에 알린다 */
    fun broke(why: String) = add(Int.MAX_VALUE, { Item.Broke(it, why) })

    // ---- 뷰모델 스레드
    /**
     * 멈춤: 세대를 올리고 [upTo] 번까지 버리기를 먼저 정한 뒤 줄을 비우고, [flush] 면 스피커에 남은 소리도 버린다
     * (끼어들기·멈춤. 남은 문장을 예전 방식으로 읽을 때는 이미 스피커에 들어간 소리를 마저 튼다). 새 세대 번호를 돌려준다.
     */
    fun cut(upTo: Int, flush: Boolean = true): Int {
        val g = synchronized(lock) {
            gen++
            if (upTo > dropUpTo) dropUpTo = upTo
            queue.clear()
            queued.set(0)
            gen
        }
        if (flush) {
            try {
                out.flush()
            } catch (_: Exception) {
            }
            written.set(0)
            base = played()
        }
        return g
    }

    /** 새 소켓: 문장 번호가 다시 1부터다. 줄에 남은 옛 소켓의 것은 버린다 (스피커는 그대로). */
    fun renew() {
        synchronized(lock) {
            gen++
            dropUpTo = 0
            queue.clear()
            queued.set(0)
        }
    }

    /** 줄에 남은 것도 스피커에 남은 것도 없다 */
    val idle: Boolean get() = queue.isEmpty() && pendingSecs() <= 0.02f

    /** 스피커에 써 두었지만 아직 틀지 않은 소리 (초, 이번 세대) */
    fun pendingSecs(): Float = ((written.get() - (played() - base)).coerceAtLeast(0)) / TTS_RATE.toFloat()

    /** 줄에 쌓인 소리 (초) */
    fun queuedSecs(): Float = queued.get() / TTS_RATE.toFloat()

    /** 받은 소리·튼 소리가 움직인 정도 (감시: 이 값이 10초 동안 그대로면 멈춘 것) */
    fun progress(): Long = moved.get() + played()

    private fun played(): Long = try {
        out.played()
    } catch (_: Exception) {
        0
    }

    fun release() {
        running = false
        cut(Int.MAX_VALUE, flush = false)
        worker?.interrupt()
    }

    private fun loop() {
        while (running) {
            val item = try {
                queue.pollFirst(200, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                null
            } ?: continue
            step(item, wait = true)
        }
    }

    /** 시험용: 줄에 있는 것을 한 번에 처리한다 (스피커가 꽉 차면 거기서 멈춘다). */
    fun pump() {
        while (true) {
            val item = queue.pollFirst() ?: return
            if (!step(item, wait = false)) return
        }
    }

    /** 표시 하나 또는 소리 조각 하나. 다 쓰지 못하고 멈췄으면 거짓 (pump 만). */
    private fun step(item: Item, wait: Boolean): Boolean {
        val g = item.gen
        if (g != gen) return true
        when (item) {
            is Item.Start -> events.start(g, item.id)
            is Item.Done -> events.done(g, item.id)
            is Item.Skipped -> events.skipped(g, item.id, item.error)
            is Item.Broke -> events.broke(g, item.why)
            is Item.Audio -> {
                val pcm = item.pcm
                var off = 0
                while (off < pcm.size) {
                    if (g != gen || !running) return true // 멈췄다: 이 조각의 남은 소리는 버린다
                    val n = minOf(SLICE, pcm.size - off)
                    val w = try {
                        out.writeSome(pcm, off, n)
                    } catch (_: Exception) {
                        n // 스피커가 고장: 버리고 지나간다 (감시가 끝낸다)
                    }
                    if (g != gen) {
                        // 쓰는 사이에 멈췄다: 방금 쓴 옛 소리를 지운다 (새 세대의 소리는 이 스레드만 쓰므로 아직 없다)
                        try {
                            out.flush()
                        } catch (_: Exception) {
                        }
                        base = played()
                        return true
                    }
                    if (w > 0) {
                        off += w
                        written.addAndGet(w.toLong())
                        queued.addAndGet(-w.toLong())
                        moved.incrementAndGet()
                    } else if (wait) {
                        try {
                            Thread.sleep(10)
                        } catch (_: InterruptedException) {
                        }
                    } else {
                        // 시험: 스피커가 꽉 찼다. 남은 소리를 줄 맨 앞에 되돌려 둔다.
                        queue.addFirst(Item.Audio(g, pcm.copyOfRange(off, pcm.size)))
                        return false
                    }
                }
            }
        }
        return true
    }

    companion object {
        /** 한 번에 쓰는 소리: 40ms (24 kHz) */
        const val SLICE = TTS_RATE / 25
    }
}
