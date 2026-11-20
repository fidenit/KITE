// APERIT Pro: 서버(게이트웨이)의 목소리로 말하기·받아쓰기를 할 때의 판단과 화면 글 (texts-pro-v1 D). 안드로이드 API 를 쓰지 않는다 (JVM 에서 시험한다).
package com.fidenit.kite.voice

object ProVoice {
    /** 대화 버튼·마이크 버튼·설정의 음성 칸을 보이는가. 무료 앱은 늘, Pro 는 서버가 `voice: true` 라고 했을 때만 (D-A1, 아니면 A1). */
    fun controlsShown(pro: Boolean, voice: Boolean): Boolean = !pro || voice

    /** 말로 대화하기를 켤 수 있는가 (켤 때 마이크 권한을 묻는가). 무료 앱은 ElevenLabs 키가 있을 때, Pro 는 서버의 목소리를 쓸 수 있을 때. */
    fun mayTalk(pro: Boolean, voice: Boolean, key: String): Boolean = if (pro) voice else key.isNotBlank()

    /** D1 */
    fun cannotSpeak(en: Boolean): String = if (en) "The server's voice cannot be used right now → this answer is read with the phone's own voice → if it keeps happening, tell whoever gave you this app"
    else "서버의 목소리를 지금 쓸 수 없습니다 → 이 답은 폰의 목소리로 읽습니다 → 계속되면 이 앱을 준 사람에게 알려 주세요"

    /** D2 */
    fun speakLimit(en: Boolean): String = if (en) "The server's voice limit was reached → this answer is read with the phone's own voice → nothing to do"
    else "서버의 목소리 사용 한도에 닿았습니다 → 이 답은 폰의 목소리로 읽습니다 → 할 일은 없습니다"

    /** D3 */
    fun cannotHear(en: Boolean): String = if (en) "The server could not turn your speech into text → what you just said was not sent → type it instead; if it keeps happening, tell whoever gave you this app"
    else "서버가 말을 글로 바꾸지 못했습니다 → 방금 한 말은 보내지 않았습니다 → 글로 적어 보내 주세요. 계속되면 이 앱을 준 사람에게 알려 주세요"

    /** D4 */
    fun hearLimit(en: Boolean): String = if (en) "The server's speech-to-text limit was reached → what you just said was not sent → type it instead"
    else "서버의 받아쓰기 사용 한도에 닿았습니다 → 방금 한 말은 보내지 않았습니다 → 글로 적어 보내 주세요"

    /**
     * 문장 하나를 읽은 결과의 한 줄 (Pro). 키·설정을 말하지 않는다 (D-A2).
     * 연결 문제(서버에 닿지 않음, 서버가 ElevenLabs 에 닿지 않음)와 폰 쪽 문제(스피커, 폰의 기본 음성)는 무료 앱의 줄 (D5: 키도 설정도 말하지 않는 줄들),
     * 서버가 목소리를 내주지 않았으면 D1, 한도면 D2. 그때 폰의 기본 음성도 못 읽었으면 폰의 기본 음성 줄 (D1·D2 의 "폰의 목소리로 읽습니다" 가 참이 아니므로).
     */
    fun speakNotice(o: Speaking.Outcome, en: Boolean): String? {
        val f = o.eleven ?: return Speaking.notice(o, en)
        if (f.why == Eleven.Why.NETWORK || (f.gate == null && f.why == Eleven.Why.SPEAKER)) return Speaking.notice(o, en)
        if (o.phoneFailed) return Speaking.notice(Speaking.Outcome(null, true), en)
        return if (f.why == Eleven.Why.LIMIT) speakLimit(en) else cannotSpeak(en)
    }

    /** 받아쓰기가 실패한 뒤 말로 대화하기를 끄는가 (D-A3: D3·D4 면 끈다, 연결 문제면 무료 앱처럼 계속 듣는다). */
    fun stopsTalking(e: Throwable): Boolean = (e as? Eleven.Failure)?.why != Eleven.Why.NETWORK

    /** 받아쓰기 실패의 한 줄 (Pro). 연결 문제는 무료 앱의 줄 (D5), 한도는 D4, 나머지는 D3. `listening` = 그 뒤에도 계속 듣는가. */
    fun heardNotice(e: Throwable, listening: Boolean, en: Boolean): String {
        val f = e as? Eleven.Failure
        if (f?.why == Eleven.Why.NETWORK) return Speaking.heardNotice(Eleven.Why.NETWORK, listening, en)
        return if (f?.why == Eleven.Why.LIMIT) hearLimit(en) else cannotHear(en)
    }

    /** 게이트웨이가 이 기기를 거절했다 (401·403): (이유, 허용 대기 번호) — 앱은 보통의 상태 확인을 시작한다 (D-A2). 아니면 null. */
    fun deviceProblem(e: Throwable?): Pair<String, String>? {
        val f = e as? Eleven.Failure ?: return null
        return if (f.gate == Eleven.Gate.DEVICE) f.deviceKind to f.deviceCode else null
    }

    /** pro.log 에 남길 한 줄의 까닭 부분: 번호와 낱말만 (글, 소리, 비밀 글, 주소는 넣지 않는다). */
    fun logWhy(e: Throwable?): String {
        if (e == null) return "ok"
        val f = e as? Eleven.Failure ?: return "fail ${e.javaClass.simpleName}"
        val http = f.message.orEmpty().takeIf { f.gate != null }.orEmpty() // "HTTP 502 voice_key" (번호와 낱말만)
        return "fail why=${f.why} gate=${f.gate ?: "-"} played=${f.played}" + (if (http.isNotEmpty()) " $http" else "") + (f.cause?.let { " err=${it.javaClass.simpleName}" } ?: "")
    }
}
