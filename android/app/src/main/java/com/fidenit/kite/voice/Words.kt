// 받아쓴 말 가운데 따로 다루는 짧은 말들. 데스크톱 src/main.rs 의 같은 이름 함수들과 같다.
package com.fidenit.kite.voice

object Words {
    private fun squash(text: String): String = text.filter { it.isLetterOrDigit() }.lowercase()

    /** 말을 멈추라는 짧은 말인가 ("그만", "멈춰", "스톱", "조용히 해" 등). */
    fun isStop(text: String): Boolean {
        val t = squash(text)
        if (t.isEmpty() || t.length > 10) return false
        return listOf("그만", "멈춰", "멈춰요", "멈춤", "스톱", "stop", "조용", "쉿", "됐어", "됐다", "닥쳐", "그만해", "그만하라고", "shutup", "quiet", "enough", "thatsenough").any { t.contains(it) }
    }

    /** 잠시 기다려 달라는 말인가 ("잠깐만", "기다려 봐", "확인해 볼게", "읽어 볼게"). */
    fun isWait(text: String): Boolean {
        val t = squash(text)
        if (t.isEmpty() || t.length > 20) return false
        // "잠깐, 이거 찾아봐"처럼 시키는 말은 기다리라는 말이 아니다.
        if (listOf("줘", "찾아봐", "해봐", "알려", "보여", "만들어", "가져와", "써봐", "봐봐", "find", "show", "tellme", "make", "search", "get").any { t.contains(it) }) return false
        return listOf("잠깐", "잠시만", "기다려", "기다리", "볼게", "볼께", "보고올게", "체크해볼", "생각좀", "생각해볼", "holdon", "wait", "onesec", "onesecond", "letmecheck", "letmeread", "letmethink", "justasec").any { t.contains(it) }
    }

    /** 기다리는 중에 분명히 나를 부르는 말인가 ("카이트", "다 봤어", "이제 얘기해"). */
    fun isWake(text: String): Boolean {
        // 영어 이름은 낱말 하나로 들렸을 때만 (낱말 속의 kite(kites 같은)는 아니다).
        if (text.split(Regex("[^\\p{L}\\p{N}]+")).any { it.equals("aperit", ignoreCase = true) || it.equals("kite", ignoreCase = true) }) return true
        val t = squash(text)
        return listOf("아페리트", "카이트", "다봤어", "다했어", "다읽었어", "확인했어", "이제얘기해", "이제말해", "얘기해봐", "말해봐", "됐어이제", "자이제", "돌아왔어", "imback", "imdone", "okaydone", "allright", "goahead", "youthere").any { t.contains(it) }
    }

    /** 두 글이 거의 같은가 (두 글자 묶음이 얼마나 겹치는가). AI 자기 목소리를 받아쓴 것을 가려낼 때 쓴다. */
    fun similar(a: String, b: String): Boolean {
        fun grams(s: String): Set<String> = s.filter { it.isLetterOrDigit() }.windowed(2).toSet()
        val ga = grams(a)
        val gb = grams(b)
        if (gb.size < 3) return false
        return gb.count { it in ga }.toFloat() / gb.size > 0.6f
    }

    /** 받아쓴 한 말을 어떻게 하나 (뷰모델 heardText: 무료·Pro·실시간이 같은 규칙). */
    enum class Heard { IGNORE, SEND, WAIT, STOP }

    /**
     * 빈 말은 버린다. 기다리는 중이면 분명히 부르는 말만 보낸다. "잠깐만" → 기다림, "그만" → 멈춤.
     * 스피커에서 나온 AI 자기 목소리(이번 답에서 읽은 마지막 세 문장과 거의 같음)는 버린다. 나머지는 보낸다.
     */
    fun route(text: String, standby: Boolean, spoken: List<String>): Heard = when {
        text.isBlank() -> Heard.IGNORE
        standby -> if (isWake(text)) Heard.SEND else Heard.IGNORE
        isWait(text) -> Heard.WAIT
        isStop(text) -> Heard.STOP
        spoken.takeLast(3).any { similar(it, text) } -> Heard.IGNORE
        else -> Heard.SEND
    }
}
