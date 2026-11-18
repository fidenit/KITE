// 앱의 상태와 흐름: 한 턴 돌리기, AI 가 낸 줄을 화면과 소리로 내보내기, 말로 대화하기.
// 흐름은 데스크톱 src/main.rs 의 send / ai_line / advance_speech / voice_input 을 따른다.
package com.fidenit.kite

import android.app.Application
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fidenit.kite.core.Author
import com.fidenit.kite.core.Board
import com.fidenit.kite.core.Card
import com.fidenit.kite.core.Chunk
import com.fidenit.kite.core.Item
import com.fidenit.kite.core.Lang
import com.fidenit.kite.core.LineStream
import com.fidenit.kite.core.Op
import com.fidenit.kite.core.Rejected
import com.fidenit.kite.core.partsTable
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.fidenit.kite.engine.Api
import com.fidenit.kite.engine.ApiConfig
import com.fidenit.kite.engine.ClaudeCode
import com.fidenit.kite.engine.EngineConfig
import com.fidenit.kite.engine.Ev
import com.fidenit.kite.engine.NOTE
import com.fidenit.kite.engine.PHONE_NOTE
import com.fidenit.kite.engine.PhoneTools
import com.fidenit.kite.engine.Runner
import com.fidenit.kite.engine.STOPPED
import com.fidenit.kite.engine.Server
import com.fidenit.kite.engine.Ssh
import com.fidenit.kite.voice.Eleven
import com.fidenit.kite.voice.Mic
import com.fidenit.kite.voice.Player
import com.fidenit.kite.voice.Words
import com.fidenit.kite.voice.talkMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 최근 대화 가운데 모델에게 함께 보내는 턴 수 */
private const val RECENT = 6

class KiteViewModel(app: Application) : AndroidViewModel(app) {
    val store = Store(app)
    private val files: File = app.filesDir

    // ---------------------------------------------------------------- 화면이 보는 상태
    var board = Board()
        private set

    /** 보드가 바뀔 때마다 1씩 는다. 화면은 이 값을 읽어 다시 그린다. */
    var rev by mutableIntStateOf(0)
        private set

    /** 지금 보고 있는 주제 */
    var current by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
        private set

    /** 지금 하는 일 ("명령 실행: ..." 등) */
    var activity by mutableStateOf("")
        private set
    var error by mutableStateOf<String?>(null)

    /** 아직 줄이 끝나지 않은 채 흘러 들어오는 한마디 */
    var liveSay by mutableStateOf<String?>(null)
        private set

    /** 마지막 턴의 통계 한 줄 */
    var statsLine by mutableStateOf("")
        private set

    /** 주고받은 말 (사용자, 비서의 한마디) */
    val recent = mutableStateListOf<Pair<String, String>>()

    /** 말로 대화하기 스위치 */
    var converse by mutableStateOf(false)
        private set
    var speaking by mutableStateOf(false)
        private set
    var transcribing by mutableStateOf(false)
        private set

    /** 기다리는 중: "잠깐만"이라고 했다. 부를 때까지 말하지 않고 진행하지 않는다. */
    var standby by mutableStateOf(false)
        private set
    var micLevel by mutableFloatStateOf(0f)
        private set
    var heard by mutableStateOf(false)
        private set

    // ---------------------------------------------------------------- 안에서만 쓰는 상태
    private var ssh: Ssh? = null
    private var engine: Runner? = null
    private var engineSig = ""

    /** 폰 안의 기억 보관함 (API 엔진이 쓴다. SSH 엔진은 서버의 ~/.kite/vault 를 쓴다) */
    private val tools = PhoneTools(File(files, "vault"))

    /** 내 서버의 Claude Code 를 쓰는가 (무료 앱에서만 고를 수 있다) */
    private val useSsh: Boolean get() = !BuildConfig.PRO && store.engineKind == "ssh"

    /** 데스크톱과 같은 지시문 (src/prompt.md, 빌드할 때 복사해 온다) */
    private val basePrompt: String by lazy {
        getApplication<Application>().assets.open("prompt.md").bufferedReader().readText().replace("{{PARTS}}", partsTable().trimEnd())
    }
    private var prepared = false
    private var serverOs = ""
    private var serverHome = "~"
    private var core = ""

    @Volatile
    private var stopFlag = false
    private val pending = mutableListOf<String>()
    private val events = mutableListOf<String>()
    private val rejects = mutableListOf<String>()
    private var lines = LineStream()
    private var eventTimer: Job? = null

    // 소리
    private val speech = ArrayDeque<Pair<String, MutableList<String>>>()
    private val turnSpoken = mutableListOf<String>()
    private var quietSay: String? = null
    private var hush = false

    @Volatile
    private var speakEpoch = 0
    private var player: Player? = null
    private var deviceTts: TextToSpeech? = null
    private var deviceTtsReady = CompletableDeferred<Boolean>()

    // 마이크
    private var mic: Mic? = null
    private var micSince = 0L
    private var quietSince: Long? = null
    private var echo = 0f
    private var bargeSince: Long? = null
    private var voiceLoop: Job? = null

    private val english: Boolean get() = store.lang == "en"
    private val voice: Boolean get() = converse

    init {
        try {
            board = Lang.load(File(files, "board.txt").readText())
        } catch (_: Exception) {
        }
        try {
            val a = JSONArray(File(files, "recent.json").readText())
            for (i in 0 until a.length()) a.getJSONArray(i).let { recent.add(it.getString(0) to it.getString(1)) }
        } catch (_: Exception) {
        }
        current = board.topics.lastOrNull()?.id
    }

    private fun save() {
        try {
            File(files, "board.txt").writeText(Lang.save(board))
            val a = JSONArray()
            recent.takeLast(40).forEach { a.put(JSONArray().put(it.first).put(it.second)) }
            File(files, "recent.json").writeText(a.toString())
        } catch (_: Exception) {
        }
    }

    // ---------------------------------------------------------------- 엔진

    /** 설정이 바뀌었으면 엔진을 새로 만든다. (입출력 스레드에서 부른다) */
    private fun engine(): Runner {
        val sig = store.signature()
        var e = engine
        if (e == null || sig != engineSig) {
            ssh?.close()
            ssh = null
            e = if (useSsh) {
                val s = Ssh(Server(store.host, store.port.toIntOrNull() ?: 22, store.user, store.password, store.privateKey, store.passphrase, store.fingerprint)) { store.fingerprint = it }
                ssh = s
                ClaudeCode(s, EngineConfig(store.claude, store.dir, store.permission, store.effort, store.extra))
            } else {
                // 무료 앱: 사용자의 키로 Anthropic 에 바로. Pro: 같은 요청을 중계 서버로 (키는 서버가 갖고 있다).
                val key = (if (BuildConfig.PRO) store.relayToken else store.apiKey).trim()
                if (key.isEmpty()) {
                    throw Exception(
                        if (BuildConfig.PRO) (if (english) "Your subscription is not connected yet." else "구독이 아직 연결되지 않았습니다.")
                        else (if (english) "Add your Claude API key in Settings." else "설정에서 Claude API 키를 넣어 주세요."),
                    )
                }
                val client = AnthropicOkHttpClient.builder().apiKey(key).apply { if (BuildConfig.PRO) baseUrl(BuildConfig.RELAY_URL) }.build()
                Api(client, basePrompt + PHONE_NOTE, ApiConfig(store.model, store.apiEffort, viaRelay = BuildConfig.PRO), tools)
            }
            engine = e
            engineSig = sig
            prepared = false
        }
        if (!prepared) {
            if (e is ClaudeCode) {
                ssh!!.connect()
                val (os, home) = e.where()
                serverOs = os
                serverHome = home
                e.prepare(basePrompt + NOTE)
                core = e.core()
            } else {
                tools.vault.mkdirs()
                core = tools.core()
            }
            prepared = true
        }
        return e
    }

    /** 처음 켰을 때 설정부터 보여 줘야 하는가 (무료 앱에서 키나 서버가 비어 있을 때). Pro 는 넣을 것이 없다. */
    fun needsSetup(): Boolean = when {
        BuildConfig.PRO -> false
        useSsh -> store.host.isBlank()
        else -> store.apiKey.isBlank()
    }

    /** 설정 화면에서 바꾼 뒤 부른다: 다음 턴에 다시 연결한다. */
    fun resetEngine() {
        engineSig = ""
    }

    /** 설정 화면의 "연결 시험": 서버의 Claude Code 버전, 또는 API 키가 맞는지(요금 없는 모델 목록) 알아본다. */
    suspend fun testConnection(): String = withContext(Dispatchers.IO) {
        try {
            engineSig = ""
            when (val e = engine()) {
                is ClaudeCode -> "연결됨 · $serverOs · Claude Code ${e.version()}"
                is Api -> e.check()
                else -> "연결됨"
            }
        } catch (e: Exception) {
            "실패: ${e.message}"
        }
    }

    /** KITE Pro: 이번 달 사용량을 중계 서버에 묻는다. */
    suspend fun usage(): String = withContext(Dispatchers.IO) {
        try {
            val token = store.relayToken.trim()
            if (token.isEmpty()) return@withContext if (english) "Subscription not connected." else "구독이 아직 연결되지 않았습니다."
            val c = java.net.URL(BuildConfig.RELAY_URL.trimEnd('/') + "/v1/usage").openConnection() as java.net.HttpURLConnection
            c.setRequestProperty("x-api-key", token)
            c.connectTimeout = 8000
            c.readTimeout = 8000
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
            if (code != 200) return@withContext "실패: HTTP $code ${body.take(160)}"
            val j = org.json.JSONObject(body)
            val used = j.optDouble("used_usd", 0.0)
            val budget = j.optDouble("budget_usd", 0.0)
            val pct = if (budget > 0) (used / budget * 100).toInt().coerceIn(0, 100) else 0
            if (english) "This month: $pct% used (${j.optString("plan")})" else "이번 달 사용량 $pct% (${j.optString("plan")})"
        } catch (e: Exception) {
            "실패: ${e.message}"
        }
    }

    suspend fun newKey(): String = withContext(Dispatchers.IO) {
        val (prv, pub) = Ssh.newKey("kite-phone")
        store.privateKey = prv
        store.publicKey = pub
        store.passphrase = ""
        pub
    }

    // ---------------------------------------------------------------- 한 턴

    private fun turnText(input: String): String {
        val t = StringBuilder("<board>\n${Lang.serialize(board)}</board>\n")
        if (core.isNotEmpty()) t.append("<core>\n$core\n</core>\n")
        if (board.shelf.isNotEmpty()) {
            t.append("<archived>\n")
            for (s in board.shelf.takeLast(40)) t.append("${s.topic.id} ${s.topic.name} (카드 ${s.cards.size}장: ${s.cards.take(8).joinToString(", ") { it.title }})\n")
            t.append("</archived>\n")
        }
        if (recent.isNotEmpty()) {
            t.append("<recent>\n")
            for ((u, a) in recent.takeLast(RECENT)) t.append("사용자: $u\nsay: $a\n")
            t.append("</recent>\n")
        }
        if (events.isNotEmpty()) t.append("<events>\n${events.joinToString("\n")}\n</events>\n")
        val rejected = rejects + board.notes
        rejects.clear()
        board.notes.clear()
        if (rejected.isNotEmpty()) t.append("<rejected>\n${rejected.joinToString("\n")}\n</rejected>\n")
        if (voice) t.append("<voice>on</voice>\n")
        if (english) t.append("<lang>en</lang>\n")
        events.clear()
        val now = SimpleDateFormat("yyyy-MM-dd (E) HH:mm", if (english) Locale.ENGLISH else Locale.KOREAN).format(Date())
        if (useSsh) {
            t.append("<env>\n지금: $now\n운영체제: $serverOs (Claude Code 가 도는 서버. 사용자의 화면은 안드로이드 폰)\n사용자 홈 폴더: $serverHome\n기억 보관함 폴더: $serverHome/.kite/vault\n</env>\n")
        } else {
            val memory = tools.list()
            if (memory.isNotEmpty()) t.append("<memory>\n$memory\n</memory>\n")
            t.append("<env>\n지금: $now\n기기: 안드로이드 폰의 KITE 앱 (컴퓨터와 파일 시스템은 없다)\n</env>\n")
        }
        t.append("<input>$input</input>")
        return t.toString()
    }

    /** 사용자의 말을 보낸다. 답하는 중이면 줄을 세워 두었다가 끝나자마자 이어서 처리한다. */
    fun send(text: String) {
        standby = false
        val input = text.trim()
        if (busy) {
            if (input.isNotEmpty()) pending.add(input)
            return
        }
        if (input.isEmpty() && events.isEmpty()) return
        startTurn(input)
    }

    private fun startTurn(input: String) {
        busy = true
        error = null
        activity = ""
        stopFlag = false
        hush = false
        turnSpoken.clear()
        quietSay = null
        lines = LineStream()
        val text = turnText(input)
        val shown = input.ifEmpty { if (english) "(screen action)" else "(화면 조작)" }
        val evs = Channel<Ev>(Channel.UNLIMITED)
        viewModelScope.launch {
            val reader = launch {
                for (ev in evs) handle(ev)
            }
            val result = withContext(Dispatchers.IO) {
                runCatching { engine().run(text, { stopFlag }) { evs.trySend(it) } }
            }
            evs.close()
            reader.join()
            lines.finish()?.let { aiLine(it) }
            liveSay = null
            board.cards.forEach { it.live = null }
            // 다른 말이 없었으면 남겨 둔 한마디를 읽는다.
            quietSay?.let {
                if (voice && !hush && !standby) speech.addLast(it to mutableListOf())
                quietSay = null
            }
            advanceSpeech()
            result.onSuccess { s ->
                // 무료 앱은 사용자가 Anthropic 에 직접 내므로 어림 비용을 보여 준다 (Pro 는 구독에 들어 있다).
                val cost = if (!BuildConfig.PRO && !useSsh && s.costUsd > 0) " · 약 $${"%.3f".format(s.costUsd)}" else ""
                statsLine = "${s.model} · ${"%.1f".format(s.totalMs / 1000.0)}초 · 첫 반응 ${"%.1f".format(s.firstMs / 1000.0)}초$cost" + if (s.limit.isNotEmpty()) " · ${s.limit}" else ""
            }.onFailure { e ->
                if (e.message != STOPPED) error = e.message ?: e.toString()
            }
            recent.add(shown to board.say)
            activity = ""
            busy = false
            rev++
            save()
            // 기억이 바뀌었을 수 있으니 다음 턴을 위해 다시 읽어 둔다.
            launch(Dispatchers.IO) {
                try {
                    engine?.takeIf { prepared && result.isSuccess }?.let { core = if (it is ClaudeCode) it.core() else tools.core() }
                } catch (_: Exception) {
                }
            }
            if (pending.isNotEmpty() || events.isNotEmpty()) {
                val next = pending.joinToString("\n")
                pending.clear()
                startTurn(next)
            }
        }
    }

    /** `screen` 도구로 온 명령들을 화면에 낸다. 바로 거부된 명령은 이유를 돌려주고, 다음 턴에 또 알리지 않는다. */
    private fun screenOps(ops: String): String {
        val before = rejects.size
        ops.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("```") }.forEach { aiLine(it) }
        val fresh = rejects.drop(before)
        repeat(fresh.size) { rejects.removeAt(rejects.size - 1) }
        return if (fresh.isEmpty()) "반영됨" else "거부된 명령:\n" + fresh.joinToString("\n")
    }

    private fun handle(ev: Ev) {
        when (ev) {
            is Ev.Screen -> ev.reply.complete(screenOps(ev.ops))
            is Ev.Activity -> activity = ev.text
            is Ev.Delta -> for (chunk in lines.feed(ev.text)) {
                when (chunk) {
                    is Chunk.Line -> {
                        liveSay = null
                        aiLine(chunk.text)
                    }
                    is Chunk.Partial -> Lang.partialText(chunk.text)?.let { (id, field, value) ->
                        if (id.isEmpty()) {
                            if (!voice || hush) liveSay = value
                        } else if (speech.isEmpty()) {
                            board.card(id)?.let {
                                it.live = field to value
                                rev++
                            }
                        }
                    }
                }
            }
        }
    }

    /** 멈춤: 하던 일도, 하던 말도 멈춘다. */
    fun stop() {
        pending.clear()
        silence()
        if (busy) stopFlag = true
    }

    /** 줄 하나를 보드에 반영한다. AI 의 줄이 거부되면 다음 턴에 알려 준다. */
    private fun applyLine(line: String, by: Author) {
        try {
            val op = Lang.parse(line) ?: return
            board.apply(op, by)
            // 지금 바뀌는 카드가 있는 주제를 보여 준다.
            val topic = when (op) {
                is Op.Card -> op.topic
                is Op.Set -> board.card(op.id)?.topic
                is Op.Add -> board.card(op.id)?.topic
                is Op.Upd -> board.card(op.id)?.topic
                is Op.Focus -> board.card(op.id)?.topic
                is Op.Mv -> op.topic
                is Op.Restore -> op.topic
                else -> null
            }
            if (by == Author.Ai && topic != null) current = topic
        } catch (e: Rejected) {
            if (by == Author.Ai) rejects.add("${line.trim().take(80)} → ${e.line}")
        }
        if (current == null || board.topics.none { it.id == current }) current = board.topics.lastOrNull()?.id
        rev++
    }

    /**
     * AI 가 낸 한 줄을 받는다. 음성이 꺼져 있으면 바로 화면에 반영하고,
     * 켜져 있으면 "문장 → 그 문장의 카드" 순서로 줄을 세워 말하는 속도에 맞춰 내보낸다.
     */
    private fun aiLine(line: String) {
        val t = line.trim()
        if (!voice || hush || standby) {
            if (!t.startsWith("tell ")) applyLine(line, Author.Ai)
            return
        }
        // 한 턴에 소리 내는 말은 짧게: say 는 아직 아무 말도 안 했을 때만 읽는다 (일 시작 전 한마디).
        val saidAny = turnSpoken.isNotEmpty() || speech.isNotEmpty()
        val sayText = if (t.startsWith("say ")) t.removePrefix("say ").trim().takeIf { it.isNotEmpty() } else null
        if (sayText != null && saidAny) {
            quietSay = sayText
            val last = speech.lastOrNull()
            if (last != null) last.second.add(line) else applyLine(line, Author.Ai)
            return
        }
        val tellText = if (t.startsWith("tell ")) t.removePrefix("tell ").trim().takeIf { it.isNotEmpty() } else null
        if (t.startsWith("tell ")) quietSay = null // 정리한 대답이 나왔으니 남은 한마디는 읽지 않는다
        val spoken = tellText ?: sayText
        if (spoken != null) {
            // say 는 화면 아래 한마디도 함께 바꾼다
            speech.addLast(spoken to if (sayText != null) mutableListOf(line) else mutableListOf())
        } else if (t.startsWith("tell ")) {
            return
        } else if (speech.isNotEmpty()) {
            speech.last().second.add(line) // 아직 읽지 않은 문장에 딸린 명령: 그 문장 차례까지 기다린다
        } else {
            applyLine(line, Author.Ai) // 지금 읽는 문장에 딸린 명령: 바로 나간다
        }
        advanceSpeech()
    }

    /** 앞 문장을 다 읽었으면 다음 문장을 읽기 시작하고 그 문장의 카드를 띄운다. */
    private fun advanceSpeech() {
        if (speaking) return
        val (sentence, cmds) = speech.removeFirstOrNull() ?: return
        turnSpoken.add(sentence)
        cmds.forEach { applyLine(it, Author.Ai) }
        speak(sentence)
    }

    // ---------------------------------------------------------------- 소리 내어 읽기

    private fun speak(text: String) {
        speaking = true
        val epoch = ++speakEpoch
        val key = store.elevenKey.trim()
        viewModelScope.launch {
            val failed = withContext(Dispatchers.IO) {
                runCatching {
                    if (key.isNotEmpty()) {
                        val p = player ?: Player().also { player = it }
                        Eleven.speak(key, store.voiceId.trim(), board.show(text), p) { epoch != speakEpoch }
                    } else {
                        speakWithDevice(board.show(text), epoch)
                    }
                }.exceptionOrNull()
            }
            if (epoch != speakEpoch) return@launch
            if (failed != null) error = (if (english) "Could not speak: " else "소리 내어 읽지 못함: ") + failed.message
            speaking = false
            advanceSpeech()
        }
    }

    /** ElevenLabs 키가 없을 때: 폰에 들어 있는 음성으로 읽는다. */
    private suspend fun speakWithDevice(text: String, epoch: Int) {
        val tts = deviceTts ?: TextToSpeech(getApplication()) { status -> deviceTtsReady.complete(status == TextToSpeech.SUCCESS) }.also { deviceTts = it }
        if (!deviceTtsReady.await()) throw Exception("폰의 음성 엔진을 쓸 수 없음")
        tts.language = if (english) Locale.ENGLISH else Locale.KOREAN
        val done = CompletableDeferred<Unit>()
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) {
                done.complete(Unit)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) {
                done.complete(Unit)
            }

            override fun onStop(id: String?, interrupted: Boolean) {
                done.complete(Unit)
            }
        })
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "kite-$epoch")
        done.await()
    }

    /** 하던 말을 멈춘다. 아직 읽지 않은 문장은 버리되, 딸린 카드는 화면에 낸다. */
    private fun silence() {
        speakEpoch++
        speaking = false
        quietSay = null
        player?.stop()
        try {
            deviceTts?.stop()
        } catch (_: Exception) {
        }
        val rest = speech.flatMap { it.second }
        speech.clear()
        rest.forEach { applyLine(it, Author.Ai) }
    }

    // ---------------------------------------------------------------- 말로 대화하기

    /** 말로 대화하기를 켜거나 끈다. 켤 때는 마이크 권한이 이미 있어야 한다. */
    fun converse(on: Boolean) {
        if (on && store.elevenKey.isBlank()) {
            error = if (english) "Talking needs an ElevenLabs key. Add it in Settings." else "말로 대화하려면 ElevenLabs 키가 필요합니다. 설정에서 넣어 주세요."
            return
        }
        if (converse == on) return
        converse = on
        talkMode(getApplication(), on)
        if (on) {
            player?.release()
            player = null // 통화 경로로 다시 만든다
            voiceLoop = viewModelScope.launch {
                while (isActive) {
                    tick()
                    delay(40)
                }
            }
        } else {
            voiceLoop?.cancel()
            voiceLoop = null
            silence()
            mic?.finish()
            mic = null
            standby = false
            heard = false
            micLevel = 0f
        }
    }

    /**
     * 마이크 스위치가 켜져 있는 동안: 듣는다 → 말이 끝나면 글로 바꿔 보낸다 → AI 가 답한다 → 다시 듣는다.
     * 마이크는 AI 가 일하는 동안에도, 말하는 동안에도 열어 둔다. 말하는 중에 사용자가 끼어들면 AI 는 말을 멈추고 듣는다.
     */
    private fun tick() {
        val now = System.currentTimeMillis()
        val threshold = store.micThreshold.toFloatOrNull() ?: 0.02f
        val m = mic
        if (m == null) {
            if (!transcribing) {
                mic = Mic()
                micSince = now
                heard = false
                quietSince = null
                echo = 0f
                bargeSince = null
            }
            return
        }
        m.error?.let {
            error = (if (english) "Microphone unavailable: " else "마이크를 쓸 수 없음: ") + it
            converse(false)
            return
        }
        val level = m.level
        micLevel = level
        if (speaking && !heard) {
            // AI 가 말하는 중: 스피커 소리가 마이크로 되돌아 들어온다. 그 평균보다 뚜렷하게 큰 소리가
            // 0.2초 넘게 이어지면 사용자가 끼어든 것으로 보고 말을 멈춘다.
            echo = echo * 0.95f + level * 0.05f
            val loud = level > maxOf(threshold * 1.5f, echo * 2f)
            if (!loud) bargeSince = null else if (bargeSince == null) bargeSince = now
            if (bargeSince?.let { now - it > 200 } == true) {
                m.keepLast(0.5f) // 끼어든 말의 시작은 남기고, 그 앞의 AI 목소리는 버린다
                silence()
                heard = true
                quietSince = null
                micSince = now
                bargeSince = null
            } else {
                // 끼어들지 않았으면 AI 목소리를 쌓아 두지 않는다.
                m.keepLast(0.6f)
                micSince = now
            }
            return
        }
        // 말소리가 들리다가 1.1초 조용하면 말이 끝난 것으로 본다.
        val loud = level > threshold
        if (loud && !heard && busy) hush = true // 일하는 중에 사용자가 말하기 시작했다: 남은 말은 소리 내지 않는다
        if (loud) {
            heard = true
            quietSince = null
        } else if (heard && quietSince == null) {
            quietSince = now
        }
        if (!heard) {
            // 말이 시작되기 전의 조용한 소리는 오래 쌓아 두지 않는다.
            m.keepLast(0.6f)
            micSince = now
        }
        val ended = quietSince?.let { now - it > 1100 } == true
        val tooLong = now - micSince > 45_000
        if (ended || tooLong) {
            val wasHeard = heard
            mic = null
            heard = false
            val pcm = m.finish()
            if (wasHeard && pcm.size > 16000 / 3) transcribe(pcm)
        }
    }

    private fun transcribe(pcm: ShortArray) {
        transcribing = true
        val key = store.elevenKey.trim()
        val en = english
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { Eleven.transcribe(key, pcm, en) } }
            transcribing = false
            result.onSuccess { heardText(it) }.onFailure { error = (if (en) "Transcription failed: " else "받아쓰기 실패: ") + it.message }
        }
    }

    private fun heardText(text: String) {
        when {
            text.isBlank() -> {}
            // 기다리는 중: 분명히 부르는 말에만 깨어난다.
            standby -> if (Words.isWake(text)) send(text)
            // "잠깐만", "확인해 볼게": 말을 멈추고 부를 때까지 기다린다.
            Words.isWait(text) -> {
                silence()
                standby = true
            }
            // "그만", "멈춰": 말도 일도 바로 멈춘다. 새 요청으로 보내지 않는다.
            Words.isStop(text) -> stop()
            // 스피커에서 나온 AI 자기 목소리를 받아쓴 것: 버린다.
            turnSpoken.takeLast(3).any { Words.similar(it, text) } -> {}
            else -> send(text)
        }
    }

    // ---------------------------------------------------------------- 카드를 직접 만질 때

    private fun userLine(line: String) {
        applyLine(line, Author.User)
        events.add(line)
    }

    private fun safe(s: String) = s.replace('|', '/').replace('\n', ' ').trim()

    /** 체크리스트 항목을 누른다. 연달아 누를 수 있으니 잠깐 기다렸다 한 번에 알린다. */
    fun toggle(card: Card, item: Item) {
        val state = if (item.cells.firstOrNull() == "done") "todo" else "done"
        userLine("upd ${card.id} ${item.id} ${(listOf(state) + item.cells.drop(1)).joinToString(" | ")}")
        eventTimer?.cancel()
        eventTimer = viewModelScope.launch {
            delay(1500)
            send("")
        }
    }

    /** 결정 카드에서 선택지를 고른다. */
    fun choose(card: Card, item: Item) {
        userLine("set ${card.id} chosen ${item.id}")
        send("")
    }

    /** 입력 카드에 적은 값을 보낸다 (항목 ID → 값). */
    fun submit(card: Card, values: Map<String, String>) {
        for (item in card.items) {
            val v = safe(values[item.id].orEmpty())
            userLine("upd ${card.id} ${item.id} ${item.cells.getOrElse(0) { "" }} | ${item.cells.getOrElse(1) { "" }} | $v")
        }
        userLine("set ${card.id} sent yes")
        send("")
    }

    /** 주제를 보관함으로 내린다. */
    fun archive(topic: String) {
        userLine("archive $topic")
        save()
    }

    /** 화면을 비운다 (대화 기록도 지운다). */
    fun clearAll() {
        stop()
        board = Board()
        recent.clear()
        events.clear()
        current = null
        rev++
        save()
    }

    override fun onCleared() {
        converse(false)
        player?.release()
        deviceTts?.shutdown()
        val s = ssh
        Thread { s?.close() }.start()
    }
}
