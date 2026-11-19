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
        assertEquals(7, j.readInflight()!!.recentLen)
        File(d, "inflight.json").writeText("{\"engine\":\"ssh\",\"id\":\"a-1\",\"turnText\":\"t\",\"shown\":\"s\",\"board\":\"b\",\"startedAt\":1}")
        assertEquals(-1, j.readInflight()!!.recentLen)
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
}
