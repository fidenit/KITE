package com.fidenit.kite

import com.fidenit.kite.engine.GatewayLink
import com.fidenit.kite.engine.GatewayText
import com.fidenit.kite.engine.LinkPhase
import com.fidenit.kite.voice.Eleven
import com.fidenit.kite.voice.ProVoice
import com.fidenit.kite.voice.Speaking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** APERIT Pro 의 화면 글: memory/tasks/f048/out/texts-pro-v1.md 의 B 줄과 글자 하나까지 같아야 한다 (KO·EN). JVM 만. */
class ProTextTest {
    private val c = "482913"
    private val d = "1a2b3c4d"

    private fun both(f: (Boolean) -> String) = f(false) to f(true)

    @Test
    fun everyTextIsTheDecidedWording() {
        val table = listOf(
            "B1" to both { GatewayText.blockedAtStart(d, it) } to ("이 폰(기기 1a2b3c4d)은 서버에서 막혔습니다 → 보낸 말은 없습니다 → 서버에서 이 기기를 다시 허용해 주세요" to
                "This phone (device 1a2b3c4d) is blocked on the server → nothing was sent → have this device approved again on the server"),
            "B2" to both { GatewayText.notAccepted(d, it, send = false) } to ("서버가 이 폰을 받아 주지 않았습니다 → 보낸 말은 없습니다 → 이 앱을 준 사람에게 알려 주세요 (기기 1a2b3c4d)" to
                "The server did not accept this phone → nothing was sent → tell whoever gave you this app (device 1a2b3c4d)"),
            "B2 send" to both { GatewayText.notAccepted(d, it, send = true) } to ("서버가 이 폰을 받아 주지 않았습니다 → 이 말은 보내지 않았습니다 → 이 앱을 준 사람에게 알려 주세요 (기기 1a2b3c4d)" to
                "The server did not accept this phone → this message was not sent → tell whoever gave you this app (device 1a2b3c4d)"),
            "B5" to both { GatewayText.newerOnServer(it) } to ("서버에 더 새 화면이 있어 그것으로 바꿨습니다 → 이 폰에서 마지막에 한 변경은 화면에서 빠졌습니다 → 빠진 것이 있으면 다시 말해 주세요" to
                "The server had a newer screen and it replaced this one → your last change on this phone is not shown → say it again if something is missing"),
            "B6" to both { GatewayText.refused("pending", c, it, LinkPhase.BEFORE, d) } to ("허용 대기 482913 → 이 말은 보내지 않았습니다 → 서버에서 이 번호를 허용한 뒤 다시 보내 주세요" to
                "Waiting 482913 → this message was not sent → send it again once this number is approved on the server"),
            "B7 used" to both { GatewayText.ticketRejected("used", c, it) } to ("앱에 들어 있는 1회용 허용권을 서버가 받지 않았습니다 (이미 씀) → 이 폰은 허용 대기 482913 입니다 → 서버에서 이 번호를 허용해 주세요" to
                "The server did not accept this app's one-time pass (already used) → this phone is waiting, number 482913 → have this number approved on the server"),
            "B7 expired" to both { GatewayText.ticketRejected("expired", c, it) } to ("앱에 들어 있는 1회용 허용권을 서버가 받지 않았습니다 (기한 지남) → 이 폰은 허용 대기 482913 입니다 → 서버에서 이 번호를 허용해 주세요" to
                "The server did not accept this app's one-time pass (expired) → this phone is waiting, number 482913 → have this number approved on the server"),
            "B7 revoked" to both { GatewayText.ticketRejected("revoked", c, it) } to ("앱에 들어 있는 1회용 허용권을 서버가 받지 않았습니다 (취소됨) → 이 폰은 허용 대기 482913 입니다 → 서버에서 이 번호를 허용해 주세요" to
                "The server did not accept this app's one-time pass (cancelled) → this phone is waiting, number 482913 → have this number approved on the server"),
            "B7 invalid" to both { GatewayText.ticketRejected("invalid", c, it) } to ("앱에 들어 있는 1회용 허용권을 서버가 받지 않았습니다 (맞지 않음) → 이 폰은 허용 대기 482913 입니다 → 서버에서 이 번호를 허용해 주세요" to
                "The server did not accept this app's one-time pass (not valid) → this phone is waiting, number 482913 → have this number approved on the server"),
            "B8" to both { GatewayText.refused("revoked", "", it, LinkPhase.BEFORE, d) } to ("이 폰(기기 1a2b3c4d)은 서버에서 막혔습니다 → 이 말은 보내지 않았습니다 → 서버에서 이 기기를 다시 허용해 주세요" to
                "This phone (device 1a2b3c4d) is blocked on the server → this message was not sent → have this device approved again on the server"),
            "B9" to both { GatewayText.refused("unknown", "", it, LinkPhase.BEFORE, d) } to ("서버가 이 폰을 잊었습니다 → 이 말은 보내지 않았습니다 → 다시 보내면 새 허용 대기 번호가 나옵니다" to
                "The server no longer knows this phone → this message was not sent → send again to get a new waiting number"),
            "B10" to both { GatewayText.refused("role_not_supported", "", it, LinkPhase.BEFORE, d) } to ("서버가 이 폰의 사용을 허용하지 않았습니다 → 이 말은 보내지 않았습니다 → 서버에서 이 기기(1a2b3c4d)를 확인해 주세요" to
                "The server did not allow this phone to do this → this message was not sent → check device 1a2b3c4d on the server"),
            "B11a" to both { GatewayText.refused("revoked", "", it, LinkPhase.FOLLOW, d) } to ("서버가 이 폰을 허용하지 않습니다 → 이 말은 서버로 갔지만 결과를 받지 못했습니다 → 서버에서 이 기기를 허용한 뒤, 결과가 필요하면 다시 물어 주세요" to
                "The server does not allow this phone → the message reached the server but its result was not received → have this device approved, then ask again if you need the result"),
            "B11b" to both { GatewayText.refused("pending", c, it, LinkPhase.AFTER, d) } to ("서버가 이 폰을 허용하지 않습니다 → 멈추라는 요청을 전하지 못했습니다 (서버에서는 계속 돌 수 있습니다) → 서버에서 이 기기를 허용해 주세요" to
                "The server does not allow this phone → the stop request did not arrive (it may keep running on the server) → have this device approved on the server"),
            "B12" to both { GatewayText.badConfig(it) } to ("이 앱에 들어 있는 서버 주소가 잘못되었습니다 → 이 말은 보내지 않았습니다 → 이 앱을 준 사람에게 다시 받아 설치해 주세요" to
                "The server address inside this app is wrong → this message was not sent → get the app again from whoever gave it to you"),
            "B12 stop" to both { GatewayText.badConfig(it, LinkPhase.AFTER) } to ("이 앱에 들어 있는 서버 주소가 잘못되었습니다 → 멈추라는 요청을 전하지 못했습니다 → 이 앱을 준 사람에게 다시 받아 설치해 주세요" to
                "The server address inside this app is wrong → the stop request did not arrive → get the app again from whoever gave it to you"),
            "B14 413" to both { GatewayText.messageRefused(true, it) } to ("보내는 내용이 너무 큽니다 → 이번 말은 보내지 않았습니다 → 말을 줄여서 다시 보내 주세요" to
                "What you are sending is too large → this message was not sent → shorten it and send again"),
            "B14" to both { GatewayText.messageRefused(false, it) } to ("서버가 이 말을 받지 않았습니다 → 이번 말은 보내지 않았습니다 → 잠시 뒤 다시 보내 주세요" to
                "The server did not accept this message → it was not sent → send it again shortly"),
            "B16a" to both { GatewayText.notAnswering(it) } to ("서버가 지금 답하지 않습니다 → 이 말은 보내지 않았습니다 → 잠시 뒤 다시 보내 주세요" to
                "The server is not answering right now → this message was not sent → send it again shortly"),
            "B16b" to both { GatewayText.couldNotHandle(it) } to ("서버가 이 말을 처리하지 못했습니다 → 이 말은 보내지 않았습니다 → 잠시 뒤 다시 보내 주세요" to
                "The server could not handle this message → it was not sent → send it again shortly"),
            "B16c" to both { GatewayText.unreadableAnswer(it) } to ("서버의 답을 읽을 수 없습니다 → 이 말은 보내지 않았습니다 → 잠시 뒤 다시 보내 주세요" to
                "The server's answer could not be read → this message was not sent → send it again shortly"),
            "B17" to both { GatewayText.limited(it) } to ("서버가 지금 요청을 받지 못합니다 → 이 말은 보내지 않았습니다 → 잠시 뒤 다시 보내 주세요" to
                "The server cannot take requests right now → this message was not sent → send it again shortly"),
            "B19" to both { GatewayText.lineTooLong(it) } to ("답의 한 줄이 너무 길어 받지 못했습니다 → 받은 데까지만 화면에 반영했습니다 → 같은 말을 다시 보내 주세요" to
                "One line of the answer was too long to receive → the screen shows what arrived before it → send the same message again"),
        )
        for ((head, want) in table) {
            val (name, got) = head
            assertEquals("$name KO", want.first, got.first)
            assertEquals("$name EN", want.second, got.second)
        }
    }

    @Test
    fun noInternalWordOnAnyScreenText() {
        val all = listOf(false, true).flatMap { en ->
            listOf("pending", "revoked", "unknown", "role_not_supported").flatMap { k -> LinkPhase.values().map { GatewayText.refused(k, c, en, it, d) } } +
                listOf("used", "expired", "revoked", "invalid").map { GatewayText.ticketRejected(it, c, en) } +
                listOf(
                    GatewayText.blockedAtStart(d, en), GatewayText.notAccepted(d, en, true), GatewayText.notAccepted(d, en, false), GatewayText.newerOnServer(en),
                    GatewayText.badConfig(en), GatewayText.badConfig(en, LinkPhase.AFTER), GatewayText.messageRefused(true, en), GatewayText.messageRefused(false, en),
                    GatewayText.notAnswering(en), GatewayText.couldNotHandle(en), GatewayText.unreadableAnswer(en), GatewayText.limited(en), GatewayText.lineTooLong(en),
                    GatewayLink("http://127.0.0.1:9/", { "" }, english = { en }).uploadRefusedText("HTTP 413 too_large"),
                    GatewayLink("http://127.0.0.1:9/", { "" }, english = { en }).uploadRefusedText("HTTP 400 bad_path"),
                    GatewayLink("http://127.0.0.1:9/", { "" }, english = { en }).startRefusedText("remote", "unexpected answer HTTP 200"),
                )
        }
        val banned = listOf("게이트웨이", "등록 표", "role_not_supported", "refused", "HTTP", "bad answer", "멈춤·치우기", "gateway", "ticket", "enroll", "413", "too_large", "bad_path", "unexpected")
        for (t in all) for (b in banned) assertFalse("'$b' in: $t", t.contains(b))
        assertTrue(all.all { it.count { ch -> ch == '→' } == 2 })
    }

    @Test
    fun launchNoticesAreShownOncePerWord() {
        val n = OnceNotice()
        assertTrue(n.changed("revoked"))
        assertFalse(n.changed("revoked")) // back to the foreground: not again
        assertFalse(n.changed("revoked"))
        assertTrue(n.changed("approved"))
        assertTrue(n.changed("revoked")) // the status word changed again
        assertTrue(n.changed("pending:used"))
        assertFalse(n.changed("pending:used"))
    }

    /** texts-pro-v1 D1-D5 (voice in Pro), word for word, KO and EN. */
    @Test
    fun voiceTextsAreTheDecidedWording() {
        val rows = listOf(
            "D1" to both { ProVoice.cannotSpeak(it) } to ("서버의 목소리를 지금 쓸 수 없습니다 → 이 답은 폰의 목소리로 읽습니다 → 계속되면 이 앱을 준 사람에게 알려 주세요" to
                "The server's voice cannot be used right now → this answer is read with the phone's own voice → if it keeps happening, tell whoever gave you this app"),
            "D2" to both { ProVoice.speakLimit(it) } to ("서버의 목소리 사용 한도에 닿았습니다 → 이 답은 폰의 목소리로 읽습니다 → 할 일은 없습니다" to
                "The server's voice limit was reached → this answer is read with the phone's own voice → nothing to do"),
            "D3" to both { ProVoice.cannotHear(it) } to ("서버가 말을 글로 바꾸지 못했습니다 → 방금 한 말은 보내지 않았습니다 → 글로 적어 보내 주세요. 계속되면 이 앱을 준 사람에게 알려 주세요" to
                "The server could not turn your speech into text → what you just said was not sent → type it instead; if it keeps happening, tell whoever gave you this app"),
            "D4" to both { ProVoice.hearLimit(it) } to ("서버의 받아쓰기 사용 한도에 닿았습니다 → 방금 한 말은 보내지 않았습니다 → 글로 적어 보내 주세요" to
                "The server's speech-to-text limit was reached → what you just said was not sent → type it instead"),
        )
        for ((pair, want) in rows) {
            val (name, got) = pair
            assertEquals("$name KO", want.first, got.first)
            assertEquals("$name EN", want.second, got.second)
        }
        // which failure shows which line
        fun f(g: Eleven.Gate, why: Eleven.Why = Eleven.Why.OTHER) = Eleven.Failure(why, false, "HTTP", gate = g)
        for (en in listOf(false, true)) {
            for (g in listOf(Eleven.Gate.OFF, Eleven.Gate.KEY, Eleven.Gate.REFUSED, Eleven.Gate.DEVICE, Eleven.Gate.TOO_LARGE, Eleven.Gate.BAD_REQUEST, Eleven.Gate.OTHER)) {
                assertEquals("D1 $g", ProVoice.cannotSpeak(en), ProVoice.speakNotice(Speaking.Outcome(f(g), false), en))
                assertEquals("D3 $g", ProVoice.cannotHear(en), ProVoice.heardNotice(f(g), en))
            }
            for (g in listOf(Eleven.Gate.LIMIT, Eleven.Gate.RATE)) {
                assertEquals("D2 $g", ProVoice.speakLimit(en), ProVoice.speakNotice(Speaking.Outcome(f(g, Eleven.Why.LIMIT), false), en))
                assertEquals("D4 $g", ProVoice.hearLimit(en), ProVoice.heardNotice(f(g, Eleven.Why.LIMIT), en))
            }
            // D5a / D5b: not reachable (the server's voice_unreachable, a proxy 5xx, or the phone's own network)
            for (net in listOf(f(Eleven.Gate.UNREACHABLE, Eleven.Why.NETWORK), Eleven.Failure(Eleven.Why.NETWORK, false, "x"))) {
                assertEquals(ProVoice.unreachableSpeak(en), ProVoice.speakNotice(Speaking.Outcome(net, false), en))
                assertEquals(ProVoice.unreachableHear(en), ProVoice.heardNotice(net, en))
            }
            // D6 / D7
            assertEquals(ProVoice.noVoiceAtAll(en), ProVoice.speakNotice(Speaking.Outcome(f(Eleven.Gate.OFF), true), en))
            assertEquals(ProVoice.brokeOff(en), ProVoice.speakNotice(Speaking.Outcome(Eleven.Failure(Eleven.Why.NETWORK, true, "x"), false), en))
        }
    }

    /** texts-pro-v1 D5a, D5b, D6, D7, D8 (decided 17:30-17:35) and D9 (realtime, 2026-11-21), word for word, KO and EN. */
    @Test
    fun decidedVoiceTextsD5ToD8AreTheWording() {
        val rows = listOf(
            "D5a" to both { ProVoice.unreachableSpeak(it) } to ("서버에 닿지 않아 서버의 목소리를 쓸 수 없습니다 → 이 답은 폰의 목소리로 읽습니다 → 연결을 확인해 주세요" to
                "The server cannot be reached, so its voice cannot be used → this answer is read with the phone's own voice → check the connection"),
            "D5b" to both { ProVoice.unreachableHear(it) } to ("서버에 닿지 않아 말을 글로 바꾸지 못했습니다 → 방금 한 말은 보내지 않았습니다 → 연결을 확인한 뒤 다시 말하거나 글로 적어 보내 주세요" to
                "The server cannot be reached, so your speech could not be turned into text → what you just said was not sent → check the connection, then say it again or type it"),
            "D6" to both { ProVoice.noVoiceAtAll(it) } to ("서버의 목소리도 폰의 목소리도 쓸 수 없습니다 → 이 답은 글로만 보여 드립니다 → 폰 설정의 '텍스트 음성 변환'을 확인해 주세요" to
                "Neither the server's voice nor the phone's own voice can be used → this answer is shown as text only → check 'Text-to-speech' in the phone's settings"),
            "D7" to both { ProVoice.brokeOff(it) } to ("서버의 목소리가 읽는 중에 끊겼습니다 → 이 문장은 끝까지 읽지 못했습니다 → 답은 화면의 글로 확인해 주세요" to
                "The server's voice broke off while reading → this sentence was not read to the end → read the answer on the screen"),
            "D8" to both { ProVoice.talkingOff(it) } to ("서버에서 말로 대화하기가 꺼졌습니다 → 대화를 끝냈습니다 → 글로 적어 보내 주세요" to
                "Talking was switched off on the server → the conversation was ended → type instead"),
            "D9" to both { ProVoice.liveDropped(it) } to ("실시간 음성 연결이 끊겼습니다 → 이번 대화는 예전 방식으로 듣고 읽습니다 → 할 일은 없습니다" to
                "The live voice connection dropped → this conversation listens and reads the earlier way → nothing to do"),
        )
        for ((pair, want) in rows) {
            val (name, got) = pair
            assertEquals("$name KO", want.first, got.first)
            assertEquals("$name EN", want.second, got.second)
        }
    }
}
