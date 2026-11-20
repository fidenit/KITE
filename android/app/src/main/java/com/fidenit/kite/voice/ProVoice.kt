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

    /** D5a */
    fun unreachableSpeak(en: Boolean): String = if (en) "The server cannot be reached, so its voice cannot be used → this answer is read with the phone's own voice → check the connection"
    else "서버에 닿지 않아 서버의 목소리를 쓸 수 없습니다 → 이 답은 폰의 목소리로 읽습니다 → 연결을 확인해 주세요"

    /** D5b */
    fun unreachableHear(en: Boolean): String = if (en) "The server cannot be reached, so your speech could not be turned into text → what you just said was not sent → check the connection, then say it again or type it"
    else "서버에 닿지 않아 말을 글로 바꾸지 못했습니다 → 방금 한 말은 보내지 않았습니다 → 연결을 확인한 뒤 다시 말하거나 글로 적어 보내 주세요"

    /** D6 */
    fun noVoiceAtAll(en: Boolean): String = if (en) "Neither the server's voice nor the phone's own voice can be used → this answer is shown as text only → check 'Text-to-speech' in the phone's settings"
    else "서버의 목소리도 폰의 목소리도 쓸 수 없습니다 → 이 답은 글로만 보여 드립니다 → 폰 설정의 '텍스트 음성 변환'을 확인해 주세요"

    /** D7 */
    fun brokeOff(en: Boolean): String = if (en) "The server's voice broke off while reading → this sentence was not read to the end → read the answer on the screen"
    else "서버의 목소리가 읽는 중에 끊겼습니다 → 이 문장은 끝까지 읽지 못했습니다 → 답은 화면의 글로 확인해 주세요"

    /** D8 */
    fun talkingOff(en: Boolean): String = if (en) "Talking was switched off on the server → the conversation was ended → type instead"
    else "서버에서 말로 대화하기가 꺼졌습니다 → 대화를 끝냈습니다 → 글로 적어 보내 주세요"

    /**
     * 문장 하나를 읽은 결과의 한 줄 (Pro). 키도, 앱의 설정도, ElevenLabs 도 말하지 않는다 (D-A2). 잘 읽었으면 null.
     * 소리가 조금 나간 뒤 끊겼으면 D7 (폰의 목소리로 다시 읽지 않는다), 폰의 목소리도 못 읽었으면 D6,
     * 그 밖에는 폰의 목소리가 정말 읽었을 때만: 서버에 닿지 않음 D5a, 한도 D2, 나머지 D1.
     * 서버를 부르지 않고 폰의 목소리로 읽은 문장(D-A4)이 실패해도 D6.
     */
    fun speakNotice(o: Speaking.Outcome, en: Boolean): String? {
        val f = o.eleven ?: return if (o.phoneFailed) noVoiceAtAll(en) else null
        if (f.played) return brokeOff(en)
        if (o.phoneFailed) return noVoiceAtAll(en)
        return when (f.why) {
            Eleven.Why.NETWORK -> unreachableSpeak(en)
            Eleven.Why.LIMIT -> speakLimit(en)
            else -> cannotSpeak(en)
        }
    }

    /** 받아쓰기가 실패한 뒤 말로 대화하기를 끄는가 (D-A3: D3·D4 면 끈다, 서버에 닿지 않으면(D5b) 계속 듣는다). */
    fun stopsTalking(e: Throwable): Boolean = (e as? Eleven.Failure)?.why != Eleven.Why.NETWORK

    /** 받아쓰기 실패의 한 줄 (Pro): 서버에 닿지 않음 D5b, 한도 D4, 나머지 D3. */
    fun heardNotice(e: Throwable, en: Boolean): String {
        val f = e as? Eleven.Failure
        return when (f?.why) {
            Eleven.Why.NETWORK -> unreachableHear(en)
            Eleven.Why.LIMIT -> hearLimit(en)
            else -> cannotHear(en)
        }
    }

    /** 이 문장을 서버의 목소리로 읽어 볼까 (D-A4: 같은 답에서 서버의 목소리가 한 번 실패했으면 남은 문장은 폰의 목소리로만). */
    fun tryServer(voice: Boolean, failedThisAnswer: Boolean): Boolean = voice && !failedThisAnswer

    /** 대화하는 중에 서버가 말로 대화하기를 껐다: 대화를 끝내고 D8 을 띄운다 (말없이 끝내지 않는다). */
    fun endsConversation(voice: Boolean, conversing: Boolean): Boolean = !voice && conversing

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
