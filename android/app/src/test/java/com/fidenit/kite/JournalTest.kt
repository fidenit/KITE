package com.fidenit.kite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class JournalTest {
    private fun dir(): File = File.createTempFile("kite", "journal").let { it.delete(); it.mkdirs(); it }

    @Test
    fun inflightRoundTrip() {
        val d = dir()
        val j = Journal(d)
        assertNull(j.readInflight())
        val text = "<board>\ntopic t 일정\n</board>\n<input>it's \"quoted\" \$HOME\n두 줄 ✓</input>"
        j.writeInflight(Inflight("ssh", "20261120-013000-a1b2", text, "점심 정해 줘", "topic t 일정\ncard c1 note t\n", 1234L))
        val r = Journal(d).readInflight()!! // 새로 열어도 읽힌다
        assertEquals("ssh", r.engine)
        assertEquals("20261120-013000-a1b2", r.id)
        assertEquals(text, r.turnText)
        assertEquals("점심 정해 줘", r.shown)
        assertEquals("topic t 일정\ncard c1 note t\n", r.board)
        assertEquals(1234L, r.startedAt)
        // 번호가 아직 없는 턴 (서버에 올리기 전)
        j.writeInflight(Inflight("api", "", "x", "y", "", 1L))
        assertEquals("", j.readInflight()!!.id)
        j.clearInflight()
        assertNull(j.readInflight())
        j.clearInflight() // 없어도 괜찮다
        d.deleteRecursively()
    }

    @Test
    fun queueToCleanCurrentRoundTrip() {
        val d = dir()
        val j = Journal(d)
        assertTrue(j.readQueue().pending.isEmpty() && j.readQueue().events.isEmpty())
        j.writeQueue(listOf("가", "it's \"b\"\n둘"), listOf("upd c1 i1 done | x", "set c2 chosen o1"))
        val q = Journal(d).readQueue()
        assertEquals(listOf("가", "it's \"b\"\n둘"), q.pending)
        assertEquals(listOf("upd c1 i1 done | x", "set c2 chosen o1"), q.events)
        j.clearQueue()
        assertTrue(j.readQueue().pending.isEmpty())

        assertEquals(emptyList<String>(), j.readToClean())
        j.writeToClean(listOf("a-1", "b-2", "bad id;rm"))
        assertEquals(listOf("a-1", "b-2"), j.readToClean())

        assertNull(j.readCurrent())
        j.writeCurrent("t3")
        assertEquals("t3", j.readCurrent())
        j.writeCurrent(null)
        assertNull(j.readCurrent())
        d.deleteRecursively()
    }

    @Test
    fun corruptFilesAreTreatedAsAbsent() {
        val d = dir()
        val j = Journal(d)
        for (bad in listOf("", "{", "not json", "[]", "null", "{\"engine\":\"ssh\"}", "{\"engine\":\"ftp\",\"id\":\"\",\"turnText\":\"\",\"shown\":\"\",\"board\":\"\",\"startedAt\":1}", "{\"engine\":\"ssh\",\"id\":\"a;b\",\"turnText\":\"\",\"shown\":\"\",\"board\":\"\",\"startedAt\":1}")) {
            File(d, "inflight.json").writeText(bad)
            File(d, "queue.json").writeText(bad)
            File(d, "toclean.json").writeText(bad)
            assertNull(bad, j.readInflight())
            assertTrue(bad, j.readQueue().pending.isEmpty())
            assertTrue(bad, j.readToClean().isEmpty())
        }
        // 바이트가 깨진 파일
        File(d, "inflight.json").writeBytes(byteArrayOf(0x7b, 0xff.toByte(), 0xfe.toByte(), 0x00))
        assertNull(j.readInflight())
        // 깨진 파일 위에 새로 쓰면 읽힌다
        j.writeInflight(Inflight("ssh", "x1", "t", "s", "b", 5L))
        assertEquals("x1", j.readInflight()!!.id)
        // 폴더가 없어도 예외를 내지 않는다
        val gone = Journal(File(d, "nope/deeper"))
        gone.writeInflight(Inflight("ssh", "x1", "t", "s", "b", 5L))
        gone.writeQueue(listOf("a"), listOf())
        assertNull(gone.readInflight())
        d.deleteRecursively()
    }

    @Test
    fun writesReplaceWholeFileAndLeaveNoTemp() {
        val d = dir()
        val f = File(d, "board.txt")
        assertTrue(Journal.writeAtomic(f, "옛 글 ".repeat(1000)))
        assertTrue(Journal.writeAtomic(f, "새 글"))
        assertEquals("새 글", f.readText())
        assertEquals(listOf("board.txt"), d.list()!!.toList())
        // 쓰다 꺼져 임시 파일만 남았어도 본 파일은 그대로고, 다음 쓰기가 바로잡는다
        File(d, "board.txt.tmp").writeText("반쪽")
        assertEquals("새 글", f.readText())
        assertTrue(Journal.writeAtomic(f, "다음"))
        assertEquals("다음", f.readText())
        assertFalse(File(d, "board.txt.tmp").exists())
        // 쓸 수 없는 자리에는 거짓
        assertFalse(Journal.writeAtomic(File(d, "no/such/dir/x.txt"), "a"))
        d.deleteRecursively()
    }

    @Test
    fun recentLengthIsKeptAndOldRecordsReadAsUnknown() {
        val d = dir()
        val j = Journal(d)
        j.writeInflight(Inflight("ssh", "a-1", "t", "s", "b", 1L, 7))
        assertEquals(7, j.readInflight()!!.recentSeq)
        File(d, "inflight.json").writeText("{\"engine\":\"ssh\",\"id\":\"a-1\",\"turnText\":\"t\",\"shown\":\"s\",\"board\":\"b\",\"startedAt\":1}")
        assertEquals(-1, j.readInflight()!!.recentSeq)
        // a record of the version before: its length field is not a sequence number, so nothing is cut; no server; the start counts as made
        File(d, "inflight.json").writeText("{\"engine\":\"ssh\",\"id\":\"a-1\",\"turnText\":\"t\",\"shown\":\"s\",\"board\":\"b\",\"startedAt\":1,\"recentLen\":39}")
        val old = j.readInflight()!!
        assertEquals(-1, old.recentSeq)
        assertEquals("", old.server)
        assertTrue(old.started)
        j.writeInflight(Inflight("ssh", "a-1", "t", "s", "b", 1L, 7, "u@h:22", false))
        assertEquals("u@h:22", j.readInflight()!!.server)
        assertFalse(j.readInflight()!!.started)
        d.deleteRecursively()
    }

    /** 치울 목록은 합친다: 덮어쓰지 않고, 서버에서 치운 것이 확인된 것만 뺀다. */
    @Test
    fun theCleanListIsMergedNeverOverwritten() {
        val d = dir()
        val j = Journal(d)
        j.addToClean(listOf("a-1", "b-2"))
        j.addToClean(listOf("b-2", "c-3", "bad id"))
        assertEquals(listOf("a-1", "b-2", "c-3"), j.readToClean())
        j.removeToClean(listOf("b-2", "zzz"))
        assertEquals(listOf("a-1", "c-3"), j.readToClean())
        assertEquals(listOf("a-1", "c-3"), Journal(d).readToClean())
        d.deleteRecursively()
    }

    /** 쓰기는 일꾼 하나가 하고, 같은 파일은 마지막 것만 남고, 파일 사이의 순서는 지켜진다. flush 뒤에는 디스크에 있다. */
    @Test
    fun asyncWritesAreCoalescedOrderedAndFlushed() {
        val d = dir()
        val j = Journal(d, async = true)
        j.writeInflight(Inflight("ssh", "a-1", "t", "s", "b", 1L))
        for (i in 1..200) j.writeQueue(listOf("말 $i"), emptyList())
        j.writeFile("board.txt", "보드")
        j.clearInflight() // 보드를 쓴 다음에 기록을 지운다
        j.writeCurrent("t2")
        j.flush()
        assertEquals(listOf("말 200"), Journal(d).readQueue().pending)
        assertEquals("보드", File(d, "board.txt").readText())
        assertNull(Journal(d).readInflight())
        assertEquals("t2", Journal(d).readCurrent())
        assertFalse(File(d, "queue.json.tmp").exists())
        // 읽기는 밀린 쓰기를 먼저 기다린다
        j.writeQueue(listOf("새 말"), emptyList())
        assertEquals(listOf("새 말"), j.readQueue().pending)
        d.deleteRecursively()
    }

    /** R1: 줄에 있는 파일을 다시 써도 순서가 바뀌지 않는다: 보드를 쓴 다음에 지우기로 한 기록은 보드보다 먼저 지워지지 않는다. */
    @Test
    fun aFileQueuedAgainKeepsTheOrderAndADeleteStaysBehindTheWriteItFollowed() {
        val d = dir()
        val j = Journal(d, async = true)
        val applied = java.util.concurrent.CopyOnWriteArrayList<String>()
        val busy = java.util.concurrent.CountDownLatch(1)
        val hold = java.util.concurrent.CountDownLatch(1)
        j.beforeApply = { name, text ->
            if (name == "hold.txt") {
                busy.countDown()
                hold.await(5, java.util.concurrent.TimeUnit.SECONDS)
            }
            applied.add(if (text == null) "delete $name" else "write $name $text")
        }
        j.writeFile("hold.txt", "x") // the writer is kept busy with this one while the rest queues up
        assertTrue(busy.await(5, java.util.concurrent.TimeUnit.SECONDS))
        j.writeInflight(Inflight("ssh", "a-1", "t", "s", "b", 1L))
        j.writeFile("board.txt", "턴 결과") // turn end: the board is saved ...
        j.clearInflight() // ... and then the record is deleted
        j.writeFile("board.txt", "턴 결과와 누른 카드") // a card tap right after, before the writer got there
        assertEquals(listOf("inflight.json", "board.txt", "inflight.json", "board.txt"), j.queuedNames())
        hold.countDown()
        assertTrue(j.flush())
        val board = applied.indexOf("write board.txt 턴 결과")
        val delete = applied.indexOf("delete inflight.json")
        assertTrue("the board of the turn is written before its record is deleted: $applied", board in 0 until delete)
        assertEquals("write board.txt 턴 결과와 누른 카드", applied.last())
        assertEquals("턴 결과와 누른 카드", File(d, "board.txt").readText())
        assertNull(Journal(d).readInflight())
        d.deleteRecursively()
    }

    /** R2: flush 는 디스크에 닿지 못한 쓰기가 있으면 거짓을 돌려준다 (그 뒤의 지우기·치우기를 막으려고). 다시 써서 닿으면 참. */
    @Test
    fun flushSaysWhetherEverythingReachedTheDisk() {
        val d = dir()
        val j = Journal(d, async = true)
        File(d, "board.txt/x").mkdirs() // a folder in the file's place: the write cannot replace it
        j.writeFile("board.txt", "보드")
        assertFalse(j.flush())
        File(d, "board.txt").deleteRecursively()
        j.writeFile("board.txt", "보드")
        assertTrue(j.flush())
        // a writer that does not get there in time: false, and the write is not lost
        val hold = java.util.concurrent.CountDownLatch(1)
        j.beforeApply = { _, _ -> hold.await(5, java.util.concurrent.TimeUnit.SECONDS) }
        j.writeCurrent("t1")
        assertFalse(j.flush(200))
        hold.countDown()
        assertTrue(j.flush())
        assertEquals("t1", Journal(d).readCurrent())
        d.deleteRecursively()
    }

    /** R3: 치울 목록에 더하고 빼는 일은 일꾼을 기다리지 않는다 (멈춤 버튼이 메인 스레드에서 부른다), 그래도 디스크에는 합쳐진 목록이 남는다. */
    @Test
    fun theCleanListIsChangedWithoutWaitingForTheWriter() {
        val d = dir()
        Journal(d).writeToClean(listOf("a-1"))
        val j = Journal(d, async = true)
        val hold = java.util.concurrent.CountDownLatch(1)
        j.beforeApply = { _, _ -> hold.await(5, java.util.concurrent.TimeUnit.SECONDS) }
        j.writeFile("board.txt", "보드") // the writer is stuck on this
        val t0 = System.nanoTime()
        j.addToClean(listOf("b-2", "a-1"))
        j.removeToClean(listOf("a-1"))
        assertEquals(listOf("b-2"), j.readToClean())
        assertTrue("no wait for the writer", (System.nanoTime() - t0) / 1_000_000 < 1000)
        hold.countDown()
        assertTrue(j.flush())
        assertEquals(listOf("b-2"), Journal(d).readToClean())
        d.deleteRecursively()
    }
}
