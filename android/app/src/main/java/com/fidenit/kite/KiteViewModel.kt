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
import com.fidenit.kite.engine.ABANDONED
import com.fidenit.kite.engine.ClaudeCode
import com.fidenit.kite.engine.EngineConfig
import com.fidenit.kite.engine.GONE
import com.fidenit.kite.engine.DeviceStatus
import com.fidenit.kite.engine.Enroller
import com.fidenit.kite.engine.GatewayLink
import com.fidenit.kite.engine.GatewayText
import com.fidenit.kite.engine.StateClient
import com.fidenit.kite.engine.StateSync
import com.fidenit.kite.engine.gatewayClient
import com.fidenit.kite.engine.Decide
import com.fidenit.kite.engine.Detached
import com.fidenit.kite.engine.Ev
import com.fidenit.kite.engine.Jev
import com.fidenit.kite.engine.NOTE
import com.fidenit.kite.engine.PHONE_NOTE
import com.fidenit.kite.engine.PhoneTools
import com.fidenit.kite.engine.Runner
import com.fidenit.kite.engine.STOPPED
import com.fidenit.kite.engine.RemoteFail
import com.fidenit.kite.engine.Server
import com.fidenit.kite.engine.Ssh
import com.fidenit.kite.engine.SshFatal
import com.fidenit.kite.engine.Waker
import com.fidenit.kite.voice.Eleven
import com.fidenit.kite.voice.Mic
import com.fidenit.kite.voice.MicProblem
import com.fidenit.kite.voice.Player
import com.fidenit.kite.voice.Speaking
import com.fidenit.kite.voice.Words
import com.fidenit.kite.voice.talkMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.json.JSONArray
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 최근 대화 가운데 모델에게 함께 보내는 턴 수 */
private const val RECENT = 6

/** 다시 붙으려는 사이 서버 설정이 바뀌었다: 그 턴은 다른 서버에 있다 */
private const val OTHER_SERVER = "__other_server"

/** `recordOwner` 의 값: 켤 때 이어 받지 못해 되돌린 기록 (어느 턴의 것도 아니다) */
private const val RESTORED = -1

class KiteViewModel(app: Application) : AndroidViewModel(app) {
    val store = Store(app)
    private val files: File = app.filesDir

    /** 앱이 꺼져도 남는 기록: 돌고 있는 턴, 줄 세워 둔 말, 카드 조작 알림, 서버에서 못 치운 실행, 보던 주제 */
    internal val journal = Journal(files, async = true)

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

    /** 턴을 서버에서 떼어 돌리는가: 내 서버의 Claude Code(SSH), 또는 APERIT Pro(게이트웨이). 돌던 턴의 기록·다시 붙기·치우기는 둘이 같다. */
    private val serverEngine: Boolean get() = useSsh || BuildConfig.PRO

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

    /** 앱이 다시 켜져 서버에 닿기를 기다리는 동안 앞으로 돌아오면 바로 깨운다 */
    private val resumeWaker = Waker()
    private val pending = mutableListOf<String>()
    private val events = mutableListOf<String>()
    private val rejects = mutableListOf<String>()

    /** 이번 턴에 AI 가 건드린 카드의 ID (턴 뒤 판단이 이것만 본다) */
    private val touched = LinkedHashSet<String>()

    /** 턴 뒤 판단이 "화면만 봐서는 무엇인지 알기 어렵다"고 한 카드 (ID, 분명할 확률): 다음 턴 글에 `<review>` 로 한 번 실린다 */
    private var unclear: List<Pair<String, Double>> = emptyList()

    /** 턴 뒤 판단이 매긴 카드별 중요도(0~1). 화면은 기다리는 카드 다음에 이 순서(세 단계)로 놓는다. */
    var importance by mutableStateOf<Map<String, Double>>(emptyMap())
        private set
    private var lines = LineStream()
    private var eventTimer: Job? = null

    /** 화면을 비울 때마다 1씩 는다. 비우기 전에 시작한 턴의 줄과 끝맺음은 새 화면에 닿지 않는다. */
    @Volatile
    private var generation = 0

    @Volatile
    private var cleared = false

    /** 턴이 시작될 때마다 1씩 는다 (멈춤 뒤 10초 감시가 다른 턴을 건드리지 않게). */
    private var turnSeq = 0

    /** 지금 도는 턴의 서버 실행 번호 */
    @Volatile
    private var currentRunId: String? = null

    /** 엔진에 맡겨 둔, 서버에서 아직 멈추고 치우지 못한 실행 번호 (끝난 뒤 목록에서 뺄 것을 가리려고) */
    private val handed: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())

    /** 치울 목록을 고치는 일은 한 번에 하나만 한다 (멈춤이 번호를 적는 것과 턴 끝의 정리가 엇갈려 번호가 빠지지 않게). */
    private val cleanLock = Any()

    /**
     * 돌던 턴의 기록(inflight.json)이 지금 어느 턴의 것인가 (`turnSeq`. 없으면 0, 켤 때 되돌린 기록이면 [RESTORED]).
     * 기록을 쓰고 지우는 일은 이 자물쇠 안에서만 한다: 턴 끝의 지우기(입출력 스레드)가 그사이 시작한 다음 턴의 기록을 지우지 않고,
     * 실행 번호 적기(입출력 스레드)가 화면을 비운 뒤에 옛 기록을 되살리지 않는다.
     */
    private val recordLock = Any()
    private var recordOwner = 0

    /** 턴 끝의 디스크 정리(기록 지우기, 서버 파일 치우기)가 아직 도는 수. 시험이 끝나기를 기다릴 때 본다. */
    private val settling = java.util.concurrent.atomic.AtomicInteger(0)

    /** 멈춤을 전하지 못한 실행을 서버에 닿는 대로 멈추고 치우는 일 (하나만 돈다), 그 기다림을 깨우는 것 */
    private var settleJob: Job? = null
    private val settleWaker = Waker()

    /** 시험에서 "남은 실행을 서버에서 멈추고 치우기" 대신 쓴다: 받은 번호 가운데 멈추고 치운 것을 돌려준다 (앱은 바꾸지 않는다). */
    internal var settleOverride: ((List<String>) -> List<String>)? = null

    /** 시험에서 서버의 실행 파일 치우기 대신 쓴다 (앱은 바꾸지 않는다). */
    internal var cleanOverride: ((String) -> Unit)? = null

    /** 다시 붙는 동안 만진 카드: 따라잡기가 끝난 뒤에 순서대로 반영한다 (그 턴이 만든 카드를 만졌을 수 있다) */
    private var replayTaps: List<String> = emptyList()

    /** `recent` 의 첫 말의 차례 번호 (저장은 마지막 40개만 하므로 0이 아닐 수 있다). `recent[k]` 의 번호는 `recentBase + k`. */
    private var recentBase = 0

    /** 서버에서 돌던 턴을 다시 받는 중인가. 그동안 만진 카드는 바로 반영하지 않고 [lateTaps] 에 모았다가, 따라잡은 뒤에 순서대로 반영한다. */
    private var replaying = false
    private val lateTaps = mutableListOf<String>()
    private var reportUserRejects = false

    /** 시험에서 엔진 대신 쓴다 */
    internal var runnerOverride: Runner? = null

    /** 턴의 엔진 단계가 도는 입출력 스레드. 시험에서 바꾼다 (앱은 바꾸지 않는다). */
    internal var turnIo: CoroutineDispatcher = Dispatchers.IO

    /**
     * TypeSafe Jev 의 주소. 시험에서 가짜 서버로 바꾼다 (앱은 바꾸지 않는다).
     * 켤 때 시작하는 턴도 init 이 끝난 뒤에야 돌기 시작하므로(`runTurn`), 만든 직후에 바꾼 값이 그 턴에도 쓰인다.
     */
    internal var jevUrl: String = Jev.baseUrl

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
            if (a.length() > 0) recentBase = a.getJSONArray(0).optInt(2, 0)
        } catch (_: Exception) {
        }
        val q = journal.readQueue()
        pending.addAll(q.pending)
        events.addAll(q.events)
        restoreView()
        resumeInterrupted()
        settleWhenReachable() // 멈춤을 전하지 못한 채 꺼졌던 실행이 있으면 서버에 닿는 대로 멈추고 치운다
        if (BuildConfig.PRO) proRefresh() // 처음 켰으면 이 폰을 등록한다 (넣을 것은 없다)
    }

    /** 보던 주제와 화면 아래 한마디를 되살린다 (`board.say` 는 보드 파일에 들어 있지 않다). */
    private fun restoreView() {
        board.say = recent.lastOrNull()?.second.orEmpty()
        current = journal.readCurrent()?.takeIf { id -> board.topics.any { it.id == id } } ?: board.topics.lastOrNull()?.id
    }

    /**
     * 앱이 켜질 때: 도는 도중에 꺼진 턴이 있으면 그 턴 시작 전 보드로 되돌리고(그 뒤에 만진 카드는 다시 반영),
     * 서버에서 떼어 돌던 턴이면 0번 줄부터 다시 붙어 결과를 반영한다. 그럴 수 없으면 기록을 지우고 한 줄로 알린다.
     */
    private fun resumeInterrupted(left: Inflight? = journal.readInflight()) {
        val i = left
        if (i == null) {
            // 턴은 끝났는데 줄 세워 둔 말이 남았다 (그 말을 넘기기 전에 꺼졌다)
            startPendingWords()
            return
        }
        val serverTurn = i.engine == "ssh" && i.id.isNotEmpty()
        if (serverTurn && serverEngine && !needsSetup() && i.server != serverSig()) {
            // 다른 서버에서 돌던 턴이다 (어느 서버인지 적히지 않은 옛 기록도): 지금 서버에서는 받을 수 없고, 다시 시작하면 같은 말이 두 번 돈다.
            // 저장된 화면을 그대로 두고, 기록만 지우고, 한 줄로 알린다. (그 실행 번호는 이 서버의 치울 목록에 넣지 않는다.)
            synchronized(recordLock) { recordOwner = RESTORED }
            settleTurn(RESTORED, null, null)
            startPendingWords()
            error = otherServerLine(i)
            return
        }
        // 멈춤을 누른 뒤 앱이 꺼졌던 턴 (번호가 치울 목록에 있다): 이어 받지 않는다. 서버의 실행은 닿는 대로 멈추고 치운다.
        val stoppedBefore = serverTurn && i.id in journal.readToClean()
        try {
            board = Lang.load(i.board)
        } catch (_: Exception) {
        }
        // 저장은 했는데 기록을 지우기 전에 꺼졌다면 주고받은 말에 이 턴이 이미 들어 있다: 이 턴의 차례 번호부터는 뺀다.
        // 차례 번호로 가린다: 40개까지만 저장하는 것과 상관없이 이 턴의 말만 빠지고, 그 전의 말은 하나도 빠지지 않는다. 번호가 없는 옛 기록이면 빼지 않는다.
        if (i.recentSeq >= 0) while (recent.isNotEmpty() && recentBase + recent.size > i.recentSeq) recent.removeAt(recent.size - 1)
        restoreView()
        if (serverTurn && serverEngine && !needsSetup() && !stoppedBefore) {
            // 그 사이 만진 카드는 따라잡은 뒤에 반영한다 (그 턴이 만든 카드를 만졌을 수 있다)
            replayTaps = events.toList()
            lateTaps.clear()
            replaying = true
            busy = true
            error = null
            activity = ""
            stopFlag = false
            hush = true // 따라잡는 동안에는 소리를 내지 않고 화면에만 반영한다
            touched.clear()
            turnSpoken.clear()
            quietSay = null
            lines = LineStream()
            synchronized(recordLock) { recordOwner = turnSeq + 1 }
            runTurn(i, resume = true)
            return
        }
        // 이어 받을 수 없다: 서버 설정이 바뀌었거나, 엔진이 바뀌었거나, API 엔진이거나, 서버에 올리기 전에 끊겼다
        events.forEach { applyLine(it, Author.User) }
        if (i.id.isNotEmpty()) journal.addToClean(listOf(i.id))
        // 되돌린 보드를 먼저 저장하고, 그것이 디스크에 닿은 뒤에야 기록을 지운다 (그 전에 꺼지면 다음에 켤 때 똑같이 되돌린다)
        synchronized(recordLock) { recordOwner = RESTORED }
        save()
        settleTurn(RESTORED, null, null)
        val why = when {
            stoppedBefore -> if (english) "The last message was stopped before the app closed" else "앱이 꺼지기 전에 멈춘 말입니다"
            i.engine == "ssh" && i.id.isNotEmpty() && !serverEngine ->
                if (english) "The engine is now \"directly from the phone\", so the message that was running on the server cannot be picked up again" else "엔진이 '폰에서 바로'로 바뀌어 서버에서 돌던 말을 이어 받을 수 없습니다"
            i.engine == "ssh" && i.id.isNotEmpty() ->
                if (english) "The server settings changed, so the last message cannot be picked up again" else "서버 설정이 바뀌어 마지막 말을 이어 받을 수 없습니다"
            else -> if (english) "The app was closed in the middle of the last message" else "앱이 꺼지면서 마지막 말이 끊겼습니다"
        }
        val line = if (english) "$why → the board is back to before it → send it again: \"${i.shown}\"" else "$why → 화면은 그 말 이전으로 되돌렸습니다 → 다시 보내 주세요: \"${i.shown}\""
        startPendingWords() // 줄 세워 둔 말은 저절로 보낸다 (턴이 시작하면 오류 줄이 지워지므로 그다음에 적는다)
        error = line
    }

    /** 서버를 가리는 이름: 계정@주소:포트. 돌던 턴의 기록에 적어 두고, 다시 붙을 때 같은 서버인지 본다. */
    private fun serverSig(): String = if (BuildConfig.PRO) "APERIT Pro" else "${store.user.trim()}@${store.host.trim()}:${store.port.toIntOrNull() ?: 22}"

    private fun otherServerLine(i: Inflight): String {
        val where = if (i.server.isEmpty()) "" else " (${i.server})"
        return if (english) "The last message ran on another server$where → its result cannot be collected from the server set now, and it was not started again → send it again if needed: \"${i.shown}\""
        else "마지막 말은 다른 서버$where 에서 돌았습니다 → 지금 설정된 서버에서는 결과를 받을 수 없고, 다시 시작하지도 않았습니다 → 필요하면 다시 보내 주세요: \"${i.shown}\""
    }

    /** 줄 세워 둔 말이 남아 있으면 지금 보낸다 (돌고 있는 턴이 없을 때). */
    private fun startPendingWords() {
        if (pending.isEmpty() || busy) return
        val next = pending.joinToString("\n")
        pending.clear()
        startTurn(next)
    }

    /** 앱이 저장해 둔 보드와 대화를 다시 읽는다 (이미 끝나 저장된 턴에 다시 붙었을 때). */
    private fun reloadFromDisk() {
        try {
            board = Lang.load(File(files, "board.txt").readText())
        } catch (_: Exception) {
            board = Board()
        }
        recent.clear()
        recentBase = 0
        try {
            val a = JSONArray(File(files, "recent.json").readText())
            for (k in 0 until a.length()) a.getJSONArray(k).let { recent.add(it.getString(0) to it.getString(1)) }
            if (a.length() > 0) recentBase = a.getJSONArray(0).optInt(2, 0)
        } catch (_: Exception) {
        }
        restoreView()
        rev++
    }

    /** 서버에서 멈추고 치워야 할 실행으로 적어 둔다 (파일에 합쳐 쓰고, 지금 엔진에도 알린다). */
    private fun markToClean(id: String) = synchronized(cleanLock) {
        journal.addToClean(listOf(id)) // 메모리의 목록에 더하고 쓰기를 줄에 세울 뿐이다: 디스크를 읽거나 기다리지 않는다
        handed.add(id)
        (engine as? ClaudeCode)?.addToClean(id)
    }

    /** 엔진이 서버에서 멈추고 치웠다고 확인한 것만 파일 목록에서 빼고, 엔진에 남은 것은 파일에 합친다. */
    private fun syncToClean(cc: ClaudeCode) = synchronized(cleanLock) {
        // 엔진의 목록을 읽는 것부터 빼는 것까지 한 자물쇠 안이다: 그사이 멈춤이 적은 번호가 "확인된 것"으로 잘못 빠지지 않는다
        val now = cc.toClean.toSet()
        journal.addToClean(now)
        val settled = handed.filter { it !in now }
        if (settled.isNotEmpty()) journal.removeToClean(settled)
        handed.removeAll(settled.toSet())
        handed.addAll(now)
    }

    /**
     * 턴 끝의 디스크 순서 (입출력 스레드에서 기다린다, 메인 스레드는 기다리지 않는다):
     * 저장한 보드·대화·치울 목록이 디스크에 닿은 뒤에야 돌던 턴의 기록을 지우고, 그 지우기가 디스크에 닿은 뒤에야 서버의 실행 파일을 치운다.
     * 어느 단계든 디스크에 닿지 못했으면(실패·5초 넘김) 거기서 그만둔다: 기록과 서버 파일이 남아 있으므로 다음에 켤 때 그 턴을 다시 받아 반영한다.
     * 기록이 이미 다음 턴의 것이면 지우지 않는다 (`seq` 는 이 턴의 번호).
     */
    private fun settleTurn(seq: Int, cc: ClaudeCode?, id: String?) {
        if (cc != null) syncToClean(cc) // 엔진이 멈추지 못해 남긴 실행 번호를, 기록을 지우기 전에 목록에 적는다
        settling.incrementAndGet()
        viewModelScope.launch(Dispatchers.IO) {
            if (!journal.flush()) return@launch
            synchronized(recordLock) {
                if (recordOwner == seq) {
                    recordOwner = 0
                    journal.clearInflight()
                }
            }
            if (!journal.flush()) return@launch
            if (id != null) {
                val over = cleanOverride
                if (over != null) over(id) else cc?.cleanRun(id)
            }
            if (cc != null) syncToClean(cc)
        }.invokeOnCompletion { settling.decrementAndGet() }
    }

    private fun stopNotDelivered(): String =
        if (english) "Stop could not reach the server → the work may still be running there → the app stops it as soon as it can connect"
        else "서버에 닿지 않아 멈춤을 전하지 못했습니다 → 서버에서는 아직 일하고 있을 수 있습니다 → 연결되는 대로 앱이 멈추고 치웁니다"

    /** 새 연결 하나 (턴의 엔진과 따로 쓴다). */
    private fun newSsh() = Ssh(Server(store.host, store.port.toIntOrNull() ?: 22, store.user, store.password, store.privateKey, store.passphrase, store.fingerprint)) { store.fingerprint = it }

    /** 치울 목록의 실행을 서버에서 멈추고 치운다 (턴의 엔진과 다른, 자기 연결로). 멈추고 치운 번호를 돌려준다. 입출력 스레드에서 부른다. */
    private fun settleOnce(ids: List<String>): List<String> {
        settleOverride?.let { return it(ids) }
        if (BuildConfig.PRO) {
            val pc = proEngine()
            ids.forEach { pc.addToClean(it) }
            try {
                pc.settle()
            } catch (_: Exception) {
            }
            val rest = pc.toClean.toSet()
            return ids.filter { it !in rest }
        }
        if (!useSsh || needsSetup()) return emptyList()
        val s = newSsh()
        val cc = ClaudeCode(s, EngineConfig(store.claude, store.dir, store.permission, store.effort, store.extra))
        ids.forEach { cc.addToClean(it) }
        try {
            s.connect(8000)
            cc.settle()
        } catch (_: Exception) {
        } finally {
            s.close()
        }
        val left = cc.toClean.toSet()
        return ids.filter { it !in left }
    }

    /**
     * 멈춤을 전하지 못한 실행이 치울 목록에 남아 있으면, 서버에 닿을 때까지 간격을 두고 다시 해 본다 (앱이 앞으로 돌아오면 바로).
     * 닿으면 멈추고 치운다. 턴이 도는 동안에는 쉬고 (그 턴의 연결이 붙을 때 엔진이 한다), 목록이 비면 끝난다.
     */
    private fun settleWhenReachable() {
        if (settleJob?.isActive == true) return
        if (journal.readToClean().isEmpty()) return
        if (settleOverride == null && (!serverEngine || needsSetup())) return
        settleJob = viewModelScope.launch {
            var attempt = 0
            while (true) {
                val ids = journal.readToClean()
                if (ids.isEmpty()) break
                if (!busy) {
                    val done = withContext(Dispatchers.IO) { runCatching { settleOnce(ids) }.getOrDefault(emptyList()) }
                    if (done.isNotEmpty()) {
                        synchronized(cleanLock) {
                            journal.removeToClean(done)
                            handed.removeAll(done.toSet())
                            (engine as? ClaudeCode)?.forget(done)
                        }
                        attempt = 0
                    }
                    if (journal.readToClean().isEmpty()) {
                        if (error == stopNotDelivered()) error = null // 이제 멈췄다: 알리던 줄을 거둔다
                        break
                    }
                }
                val wait = Detached.delayMs(attempt++)
                withContext(Dispatchers.IO) { settleWaker.sleep(wait) { cleared } }
            }
        }
    }

    private fun save() {
        try {
            journal.writeFile("board.txt", Lang.save(board))
            val a = JSONArray()
            // 마지막 40개만 저장한다. 셋째 값은 그 말의 차례 번호 (다시 붙을 때 어디까지가 그 턴 이전인지 가린다)
            for (k in maxOf(0, recent.size - 40) until recent.size) a.put(JSONArray().put(recent[k].first).put(recent[k].second).put(recentBase + k))
            journal.writeFile("recent.json", a.toString())
            journal.writeCurrent(current)
            if (BuildConfig.PRO && !applyingRemote) stateSync.changed(stateDoc(a)) // Pro: 조금 뒤에 서버에도 올린다
        } catch (_: Exception) {
        }
    }

    /** 줄 세워 둔 말과 카드 조작 알림이 바뀔 때마다 파일에도 적는다. */
    private fun saveQueue() = journal.writeQueue(pending, events)

    // ---------------------------------------------------------------- 엔진

    /** 설정이 바뀌었으면 엔진을 새로 만든다. (입출력 스레드에서 부른다) */
    private fun engine(resume: Boolean = false): Runner {
        runnerOverride?.let { return it }
        val sig = store.signature()
        var e = engine
        if (e == null || sig != engineSig) {
            (engine as? ClaudeCode)?.abandon() // 바꾸는 엔진은 따라 읽기를 그만둔다 (새 엔진이 따라 읽는 실행을 치우지 못하게)
            ssh?.close()
            if (BuildConfig.PRO) (engine as? ClaudeCode)?.closeLink() // Pro: 게이트웨이 길은 ssh 가 아니다
            ssh = null
            e = if (serverEngine) {
                val made = if (BuildConfig.PRO) {
                    proEngine()
                } else {
                    val s = Ssh(Server(store.host, store.port.toIntOrNull() ?: 22, store.user, store.password, store.privateKey, store.passphrase, store.fingerprint)) { store.fingerprint = it }
                    ssh = s
                    ClaudeCode(s, EngineConfig(store.claude, store.dir, store.permission, store.effort, store.extra))
                }
                made.also { cc ->
                    cc.cleanAfterRun = false // 서버 파일은 앱이 보드를 저장하고 기록을 지운 뒤에 치운다 (finishServerRun)
                    synchronized(cleanLock) {
                        val ids = journal.readToClean()
                        handed.addAll(ids)
                        ids.forEach { cc.addToClean(it) } // 멈추고 치우지 못한 실행: 다음에 붙을 때 처리한다
                    }
                }
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
        if (BuildConfig.PRO) proReady(resume) // 보내기 전에 이 폰이 허용됐는지 서버에 묻는다
        if (!prepared) {
            if (e is ClaudeCode) {
                e.connect()
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

    // ---------------------------------------------------------------- APERIT Pro: 등록과 상태

    /** APERIT Pro: 화면 아래 상태 줄에 보일 것 (`허용 대기 482913`). 허용됐거나 무료 앱이면 빈 글. */
    var proLine by mutableStateOf("")
        private set

    private var lastProLogged = ""

    /** APERIT Pro: 설정의 연결 상태 (`연결됨` / `허용 대기 482913` / `연결 안 됨`). */
    var proState by mutableStateOf("")
        private set

    /** 게이트웨이 요청에 붙는 앱 판 (`X-Aperit-App`). */
    private val proHttp by lazy { gatewayClient(BuildConfig.VERSION_NAME + "-pro") }

    private val device: ProDevice by lazy {
        ProDevice(store, Enroller(BuildConfig.GATEWAY_URL, proHttp), { Triple(android.os.Build.MODEL.orEmpty(), android.os.Build.VERSION.RELEASE.orEmpty(), BuildConfig.VERSION_NAME) }, BuildConfig.ENROLL_TICKET)
    }

    /** 설정에 보이는 짧은 기기 번호 (기기 번호의 앞 8글자. 서버가 대표에게 보내는 알림에도 같은 8글자가 나온다). */
    val proShortId: String get() = if (BuildConfig.PRO) store.deviceId.take(8) else ""

    /** Pro 의 엔진: 게이트웨이로 닿는 ClaudeCode. 서버의 설정(claude, 폴더, 권한, 모델)은 서버가 정한다. 생각의 깊이는 보내지 않는다. */
    private fun proEngine(): ClaudeCode {
        val link = GatewayLink(BuildConfig.GATEWAY_URL, { store.deviceSecret }, "", { english }, proHttp)
        link.id8 = { store.deviceId.take(8) }
        link.log = { line -> proLog("link $line") }
        link.onRefused = { kind, code ->
            device.refused(kind, code)
            viewModelScope.launch { proShow(DeviceStatus(store.proStatus, store.proCode), "") }
        }
        return ClaudeCode(link, EngineConfig("claude", "", "default", "", ""))
    }

    /** 켤 때·앞으로 돌아올 때 저절로 뜨는 상태 줄은 앱이 켜진 동안 한 번, 상태가 바뀔 때만 다시 (A3). */
    private val proNotice = OnceNotice()

    /** 보내기가 보내기 전에 막혔을 때 입력칸에 되돌려 놓을 글 (화면이 가져가고 비운다). */
    var refill by mutableStateOf<String?>(null)

    /**
     * 받은 상태를 화면에 낸다 (메인 스레드). `st` 가 null 이면 서버에 닿지 않았다.
     * `auto` = 켤 때·앞으로 돌아올 때 (오류 줄은 [proNotice] 가 정한 때만), 아니면 보내기·거절 (오류 줄은 던진 예외가 낸다). `seed` = 저장된 상태로 처음 채우기.
     */
    private fun proShow(st: DeviceStatus?, ticketRejected: String, auto: Boolean = false, seed: Boolean = false) {
        val en = english
        val now = st?.status ?: "offline"
        if (!seed && now != lastProLogged) {
            lastProLogged = now
            Thread { proLog("device status=$now id=${store.deviceId.take(8)}" + if (ticketRejected.isNotEmpty()) " ticket=$ticketRejected" else "") }.start()
        }
        when (st?.status) {
            "approved" -> {
                proState = if (en) "Connected" else "연결됨"
                proLine = ""
                if (!seed && !stateStarted) {
                    stateStarted = true
                    stateSync.start(if (File(files, "board.txt").exists()) stateDoc() else null)
                }
            }
            "pending" -> {
                proState = if (en) "Waiting ${st.code}" else "허용 대기 ${st.code}"
                proLine = proState
            }
            else -> {
                proState = if (en) "Not connected" else "연결 안 됨"
                proLine = ""
            }
        }
        if (seed) return
        val id8 = store.deviceId.take(8)
        val word = now + if (ticketRejected.isNotEmpty() && now == "pending") ":$ticketRejected" else ""
        if (!proNotice.changed(word) || !auto) return
        when {
            now == "revoked" -> error = GatewayText.blockedAtStart(id8, en)
            now == "refused" || now == "conflict" -> error = GatewayText.notAccepted(id8, en, send = false)
            now == "nostore" -> error = proStoreLine(en)
            now == "pending" && ticketRejected.isNotEmpty() -> error = GatewayText.ticketRejected(ticketRejected, st!!.code, en)
        }
    }

    /** 이 폰의 상태를 서버에 묻는다 (처음 켤 때는 등록한다). 앱이 켜질 때와 앞으로 돌아올 때 부른다. 실패해도 아무 일도 막지 않는다. */
    private fun proRefresh() {
        viewModelScope.launch {
            yield() // init 이 끝난 뒤에 돈다 (아래에 선언된 속성을 읽는다)
            if (proState.isEmpty()) proShow(DeviceStatus(store.proStatus.ifEmpty { "offline" }, store.proCode), "", seed = true) // 첫 답 전에도 비어 있지 않게 (A7)
            val r = withContext(Dispatchers.IO) { runCatching { device.check() to device.ticketRejected } }
            proShow(r.getOrNull()?.first, r.getOrNull()?.second.orEmpty(), auto = true)
        }
    }

    /** 보내기가 보내기 전에 막혔나 (그 글을 입력칸에 되돌린다). */
    private fun notSent(msg: String?): Boolean = msg != null && listOf("이 말은 보내지 않았습니다", "이번 말은 보내지 않았습니다", "이번 말은 시작하지 않았습니다", "보내지도 않았습니다", "this message was not sent", "it was not sent", "this message was not started", "nothing was sent").any { msg.contains(it) }

    // ---------------------------------------------------------------- APERIT Pro: 상태를 서버에도 (spec §4)

    private var stateStarted = false

    /** 서버에서 받은 상태를 반영하는 동안은 그 저장을 다시 올리지 않는다. */
    private var applyingRemote = false

    private val stateSync: StateSync by lazy {
        StateSync(StateClient(BuildConfig.GATEWAY_URL, { store.deviceSecret }, proHttp), store, ::applyRemoteState, {
            viewModelScope.launch { error = GatewayText.newerOnServer(english) }
        }, log = ::proLog)
    }

    /** 서버에 둘 문서 v1: 보드, 주고받은 말(저장하는 40개), 보던 주제. 메인 스레드에서 뜬다. */
    private fun stateDoc(recentJson: JSONArray? = null): org.json.JSONObject {
        val a = recentJson ?: JSONArray().also { r ->
            for (k in maxOf(0, recent.size - 40) until recent.size) r.put(JSONArray().put(recent[k].first).put(recent[k].second).put(recentBase + k))
        }
        return org.json.JSONObject().put("v", 1).put("board", Lang.save(board)).put("recent", a).put("current", current ?: "")
    }

    /** 서버의 문서로 바꾼다 (상태 스레드에서 불린다. 실제 일은 메인 스레드에서). 턴이 도는 중이면 하지 않는다. 바꾸기 전의 폰 것은 state-backup.json 에 남긴다. */
    private fun applyRemoteState(doc: org.json.JSONObject): Boolean {
        val done = java.util.concurrent.CompletableFuture<Boolean>()
        viewModelScope.launch {
            done.complete(
                try {
                    if (busy || cleared || doc.optInt("v", 0) != 1) {
                        false
                    } else {
                        journal.writeFile("state-backup.json", stateDoc().toString())
                        val b = Lang.load(doc.optString("board"))
                        val r = doc.optJSONArray("recent") ?: JSONArray()
                        board = b
                        recent.clear()
                        recentBase = 0
                        for (k in 0 until r.length()) r.getJSONArray(k).let { recent.add(it.getString(0) to it.getString(1)) }
                        if (r.length() > 0) recentBase = r.getJSONArray(0).optInt(2, 0)
                        board.say = recent.lastOrNull()?.second.orEmpty()
                        current = doc.optString("current").takeIf { id -> board.topics.any { it.id == id } } ?: board.topics.lastOrNull()?.id
                        rev++
                        applyingRemote = true
                        try {
                            save()
                        } finally {
                            applyingRemote = false
                        }
                        true
                    }
                } catch (_: Exception) {
                    false
                },
            )
        }
        return try {
            done.get(10, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: Exception) {
            false
        }
    }

    private val proLogLock = Any()

    /** Pro 의 기록 (등록 상태, 상태 맞추기의 판 번호·크기, 앱 판). 비밀 글·등록 표·내용은 적지 않는다. 기록이 실패해도 아무 일도 없다. */
    private fun proLog(line: String) {
        try {
            synchronized(proLogLock) {
                val f = File(files, "pro.log")
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())
                if (f.length() > 256 * 1024) f.writeText(f.readText().takeLast(128 * 1024))
                f.appendText("$stamp v${BuildConfig.VERSION_NAME}  $line\n")
            }
        } catch (_: Exception) {
        }
    }

    /** 보내기 전 (입출력 스레드): 허용된 폰이 아니면 이 말을 보내지 않는다. 서버에 묻는 간격은 [ProDevice] 가 지킨다. */
    private fun proReady(resume: Boolean = false) {
        // 사용자가 보낸 말이면 간격을 건너뛰고 한 번 더 묻는다 (ProDevice 가 간격을 지킨다: 허용 대기 중 3초, 그 밖 15초). 다시 붙는 턴은 이미 보낸 말이다.
        val st = device.check(force = !resume)
        val rejected = device.ticketRejected
        viewModelScope.launch { proShow(st, rejected) }
        val en = english
        val id8 = store.deviceId.take(8)
        val phase = if (resume) com.fidenit.kite.engine.LinkPhase.FOLLOW else com.fidenit.kite.engine.LinkPhase.BEFORE
        when (st.status) {
            "approved" -> return
            "offline" -> throw Exception(
                if (en) "The server cannot be reached → this message was not sent → check the connection and send it again"
                else "서버에 닿지 않습니다 → 이 말은 보내지 않았습니다 → 연결을 확인한 뒤 다시 보내 주세요",
            )
            "limited", "error" -> throw Exception(GatewayText.limited(en))
            "forgotten" -> throw Exception(if (resume) GatewayText.refused("unknown", "", en, phase, id8) else GatewayText.forgotten(en))
            "refused", "conflict" -> throw SshFatal(GatewayText.notAccepted(id8, en, send = true))
            "nostore" -> throw SshFatal(proStoreLine(en))
            else -> throw SshFatal(GatewayText.refused(st.status, st.code, en, phase, id8))
        }
    }

    /** 암호화 저장소를 열 수 없다 (B18). */
    private fun proStoreLine(en: Boolean): String = if (en) "This phone's encrypted storage cannot be opened → the phone was not registered and nothing was sent → restart the phone and open the app again"
    else "이 폰의 암호화 저장소를 열 수 없습니다 → 등록하지 않았고 보내지도 않았습니다 → 폰을 다시 켠 뒤 앱을 다시 열어 주세요"

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

    /**
     * 설정 화면의 "연결 시험": 서버의 Claude Code 버전, 또는 API 키가 맞는지(요금 없는 모델 목록) 알아본다.
     * 서버는 이 시험만의 연결로 알아본다: 돌고 있는(또는 다시 붙는) 턴의 엔진과 연결은 건드리지 않는다.
     */
    suspend fun testConnection(): String = withContext(Dispatchers.IO) {
        try {
            if (useSsh) {
                val s = newSsh()
                try {
                    val cc = ClaudeCode(s, EngineConfig(store.claude, store.dir, store.permission, store.effort, store.extra))
                    s.connect()
                    "연결됨 · ${cc.where().first} · Claude Code ${cc.version()}"
                } finally {
                    s.close()
                }
            } else if (busy) {
                if (english) "Answering now, so the connection was not tested → try again when the answer is done" else "지금 답하는 중이라 연결 시험은 하지 않았습니다 → 답이 끝난 뒤 다시 눌러 주세요"
            } else {
                engineSig = ""
                when (val e = engine()) {
                    is Api -> e.check()
                    else -> "연결됨"
                }
            }
        } catch (e: Exception) {
            "실패: ${e.message}"
        }
    }

    /** APERIT Pro: 이번 달 사용량을 중계 서버에 묻는다. */
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
        Decide.reviewBlock(unclear)?.let { t.append(it) }
        unclear = emptyList()
        val rejected = rejects + board.notes
        rejects.clear()
        board.notes.clear()
        if (rejected.isNotEmpty()) t.append("<rejected>\n${rejected.joinToString("\n")}\n</rejected>\n")
        if (voice) t.append("<voice>on</voice>\n")
        if (english) t.append("<lang>en</lang>\n")
        events.clear()
        val now = SimpleDateFormat("yyyy-MM-dd (E) HH:mm", if (english) Locale.ENGLISH else Locale.KOREAN).format(Date())
        if (serverEngine) {
            t.append("<env>\n지금: $now\n운영체제: $serverOs (Claude Code 가 도는 서버. 사용자의 화면은 안드로이드 폰)\n사용자 홈 폴더: $serverHome\n기억 보관함 폴더: $serverHome/.kite/vault\n</env>\n")
        } else {
            val memory = tools.list()
            if (memory.isNotEmpty()) t.append("<memory>\n$memory\n</memory>\n")
            t.append("<env>\n지금: $now\n기기: 안드로이드 폰의 APERIT 앱 (컴퓨터와 파일 시스템은 없다)\n</env>\n")
        }
        t.append("<input>$input</input>")
        return t.toString()
    }

    /** 사용자의 말을 보낸다. 답하는 중이면 줄을 세워 두었다가 끝나자마자 이어서 처리한다. */
    fun send(text: String) {
        standby = false
        val input = text.trim()
        if (busy) {
            if (input.isNotEmpty()) {
                pending.add(input)
                saveQueue()
            }
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
        touched.clear()
        turnSpoken.clear()
        quietSay = null
        lines = LineStream()
        val before = Lang.save(board)
        val text = turnText(input)
        val shown = input.ifEmpty { if (english) "(screen action)" else "(화면 조작)" }
        // 네트워크를 쓰기 전에 남긴다: 앱이 이 턴 도중 꺼져도 다음에 켤 때 이어 받거나, 끊겼다고 알릴 수 있다.
        val record = Inflight(if (serverEngine) "ssh" else "api", "", text, shown, before, System.currentTimeMillis(), recentBase + recent.size, if (serverEngine) serverSig() else "")
        synchronized(recordLock) {
            recordOwner = turnSeq + 1 // runTurn 이 매길 이 턴의 번호
            journal.writeInflight(record)
        }
        saveQueue()
        runTurn(record, resume = false, input = input)
    }

    /** 한 턴을 돌려 결과를 반영한다. `resume` 이면 서버에서 돌던 그 턴에 다시 붙는다 (앱이 다시 켜졌을 때). */
    private fun runTurn(record: Inflight, resume: Boolean, input: String = "") {
        val shown = record.shown
        val gen = generation
        val seq = ++turnSeq
        currentRunId = if (resume) record.id else null
        val evs = Channel<Ev>(Channel.UNLIMITED)
        viewModelScope.launch {
            // 턴은 부른 쪽(send, 켜질 때의 init)이 돌아간 뒤, 메인 루프의 다음 차례에야 돌기 시작한다.
            // 이것이 없으면 엔진 단계가 메인 스레드보다 먼저 끝났을 때 턴이 send() 나 init 안에서 통째로 끝난다
            // (withContext 는 맡긴 일이 이미 끝나 있으면 멈추지 않고 바로 돌아온다): 부른 쪽이 busy == false 와 지워진 기록을 보고,
            // init 에서는 아직 만들어지지 않은 속성(init 아래에 선언된 것)을 턴이 읽는다.
            yield()
            val reader = launch {
                for (ev in evs) {
                    if (gen == generation) handle(ev) else if (ev is Ev.Screen) ev.reply.complete("반영되지 않음")
                }
            }
            // 판단 모델(Jev)이 먼저 요청의 종류·주제를 보고 받았다는 한마디를 고른다. 입출력 스레드에서, 엔진을 부르기 전에.
            // 키가 없거나 입력이 비었거나 서버에서 돌던 턴에 다시 붙는 것이면 부르지 않는다.
            val turn = if (resume) record else beforeTurn(record, input)
            var used: Runner? = null
            val result = withContext(turnIo) {
                runCatching {
                    // 이 턴의 기록이 디스크에 닿기를 기다린 뒤에 네트워크를 쓴다 (입출력 스레드에서 기다린다)
                    if (!resume) journal.flush()
                    val e = if (resume) engineWhenReachable(record, evs) else engine()
                    used = e
                    if (e is ClaudeCode) {
                        // 서버에서 떼어 돌린다: 실행 번호가 정해지면 (서버에 닿기 전에) 기록에 적는다.
                        e.onRunId = { id ->
                            val mine = synchronized(recordLock) {
                                if (gen == generation && recordOwner == seq) {
                                    currentRunId = id
                                    journal.writeInflight(turn.copy(engine = "ssh", id = id))
                                    true
                                } else {
                                    false
                                }
                            }
                            // 화면을 비운 뒤라면 서버에 아무것도 올리지 않는다
                            if (!mine) throw Exception(STOPPED)
                            // 번호가 적힌 기록이 디스크에 닿은 뒤에야 서버에 올리고 시작한다: 그 전에 앱이 꺼지면 서버의 실행을 다시 찾을 길이 없다
                            if (!journal.flush()) {
                                // 이 말은 서버에 보내지 않고 다시 보내 달라고 알린다: 이 턴의 기록도 버린다 (실패한 쓰기 뒤에 지우기를 세운다).
                                // 남겨 두면 나중에 디스크에 닿아, 다음에 켤 때 앱이 이 말을 혼자 시작한다.
                                synchronized(recordLock) {
                                    if (recordOwner == seq) {
                                        recordOwner = 0
                                        journal.clearInflight()
                                    }
                                }
                                throw Exception(
                                    if (english) "This message could not be saved on the phone → it was not sent to the server → check the phone's free storage and send it again"
                                    else "폰에 이 말을 저장하지 못했습니다 → 서버에는 보내지 않았습니다 → 폰의 저장 공간을 확인한 뒤 다시 보내 주세요",
                                )
                            }
                        }
                        e.onStarted = { id ->
                            // 서버에서 시작된 것을 기록에 적는다: 그 뒤로는 서버에 실행이 없다고 해도 같은 말을 다시 시작하지 않는다
                            synchronized(recordLock) {
                                if (gen == generation && recordOwner == seq) journal.writeInflight(turn.copy(engine = "ssh", id = id, started = true))
                            }
                        }
                        // 서버에 실행이 없을 때 새로 시작하는 것은, 시작된 적이 없다고 기록에 적혀 있을 때뿐이다 (턴 글을 넘기지 않으면 엔진은 시작하지 않는다)
                        if (resume) e.resume(record.id, if (record.started) null else turn.turnText, 0, { stopFlag }) { evs.trySend(it) }
                        else e.run(turn.turnText, { stopFlag }) { evs.trySend(it) }
                    } else {
                        e.run(turn.turnText, { stopFlag }) { evs.trySend(it) }
                    }
                }
            }
            evs.close()
            reader.join()
            val cc = engine as? ClaudeCode
            val runId = currentRunId
            val msg = result.exceptionOrNull()?.message
            val usedCc = used as? ClaudeCode // 이 턴을 실제로 돌린 엔진
            // 멈춤을 눌렀는데 서버에서 끝난 것이 확인되지 않았다 = 멈춤이 서버에 닿지 않았다: 치울 목록에 두고, 닿는 대로 멈추고 치운다
            val unreached = msg == STOPPED && runId != null && (usedCc == null || !usedCc.endedOnServer(runId))
            if (unreached) runId?.let { markToClean(it) }
            // 서버에서 아직 돌 수 있는 실행의 파일은 여기서 치우지 않는다 (멈춘 뒤에 치운다)
            val cleanId = if (unreached) null else runId
            // 저장된 보드를 다시 읽기 전에 밀린 쓰기가 디스크에 닿게 한다 (입출력 스레드에서 기다린다)
            if (msg == GONE) withContext(Dispatchers.IO) { journal.flush() }
            if (resume) replaying = false // 여기서부터 이 턴의 끝맺음은 쉬지 않고 한 번에 돈다: 그동안 모은 조작을 아래에서 반영한다
            if (gen != generation) {
                // 화면을 비운 뒤에 끝났다: 새 화면에는 아무것도 반영하지 않고, 서버 실행만 치운다
                activity = ""
                busy = false
                settleTurn(seq, cc, cleanId)
                if (unreached) settleWhenReachable()
                startPendingWords()
                return@launch
            }
            if (msg == OTHER_SERVER) {
                // 다시 붙기를 기다리는 사이 서버 설정이 바뀌었다: 새 서버에서는 이 턴을 받지도 다시 시작하지도 않는다. 기록을 지우고 알린다.
                (replayTaps + lateTaps).forEach { applyLine(it, Author.User) }
                replayTaps = emptyList()
                lateTaps.clear()
                activity = ""
                busy = false
                rev++
                save()
                settleTurn(seq, null, null)
                startPendingWords()
                error = otherServerLine(record)
                return@launch
            }
            if (msg == ABANDONED) {
                // 엔진이 바뀌어 따라 읽기를 그만뒀다: 서버의 실행은 그대로고 기록도 남는다 (앱을 다시 열면 이어 받는다)
                activity = ""
                busy = false
                if (cleared) return@launch // 앱이 닫히는 중이다: 기록이 남아 있으니 다음에 켤 때 이어 받는다
                // 서버의 실행은 그대로 돌고 기록도 남아 있다. 그대로 두면 다음 말이 기록을 덮어써 그 실행을 잃으므로, 지금 설정으로 바로 다시 붙는다
                // (턴 시작 전 보드로 되돌리고 처음부터 다시 받는다. 이어 받을 수 없는 설정이면 기록을 지우고 한 줄로 알린다).
                lateTaps.clear()
                resumeInterrupted(if (runId != null) turn.copy(engine = "ssh", id = runId, started = true) else turn)
                return@launch
            }
            if (msg == GONE) {
                // 이 턴은 이미 끝나 저장되고 서버에서도 치워졌다: 저장된 보드를 그대로 보여 주고, 다시 돌리지 않는다
                reloadFromDisk()
                // 다시 붙는 동안 만진 카드는 저장된 보드에 아직 없다: 반영하고 저장한 뒤에 기록을 지운다
                if (lateTaps.isNotEmpty()) {
                    lateTaps.forEach { applyLine(it, Author.User) }
                    lateTaps.clear()
                    save()
                }
                settleTurn(seq, cc, null)
                replayTaps = emptyList()
                activity = ""
                busy = false
                startPendingWords()
                return@launch
            }
            lines.finish()?.let { aiLine(it) }
            liveSay = null
            board.cards.forEach { it.live = null }
            val taps = replayTaps + lateTaps // 꺼지기 전에 만진 것, 그다음 다시 붙는 동안 만진 것
            replayTaps = emptyList()
            lateTaps.clear()
            if (taps.isNotEmpty()) {
                // 다시 붙는 동안 만진 카드: 이제 그 카드가 있다. 아직도 거부되는 것은 다음 턴에 알린다.
                reportUserRejects = true
                taps.forEach { applyLine(it, Author.User) }
                reportUserRejects = false
            }
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
                if (e.message != STOPPED && !stopFlag) error = e.message ?: e.toString()
                if (BuildConfig.PRO && !resume && input.isNotEmpty() && notSent(e.message)) refill = input // A4: 보내지 않은 말은 입력칸으로 되돌린다
            }
            if (unreached) error = stopNotDelivered()
            val wasStopped = stopFlag
            recent.add(shown to board.say)
            activity = ""
            busy = false
            rev++
            save() // 반영한 것을 먼저 저장한다. 그것이 디스크에 닿은 뒤에 돌던 턴의 기록을 지우고, 그 지우기가 닿은 뒤에야 서버의 실행 파일을 치운다
            settleTurn(seq, cc, cleanId)
            if (unreached) settleWhenReachable()
            if (result.isSuccess) reviewTurn() else touched.clear()
            // 기억이 바뀌었을 수 있으니 다음 턴을 위해 다시 읽어 둔다.
            launch(Dispatchers.IO) {
                try {
                    engine?.takeIf { prepared && result.isSuccess }?.let { core = if (it is ClaudeCode) it.core() else tools.core() }
                } catch (_: Exception) {
                }
            }
            // 멈춘 뒤에는 아무것도 저절로 시작하지 않는다: 카드 조작 알림은 다음에 사용자가 말을 보낼 때 함께 간다.
            // (멈춘 뒤에 사용자가 새로 보낸 말이 줄 서 있으면 그 말은 보낸다.)
            if (pending.isNotEmpty() || (events.isNotEmpty() && !wasStopped)) {
                val next = pending.joinToString("\n")
                pending.clear()
                startTurn(next)
            }
        }
    }

    /**
     * 턴 앞의 판단: 종류는 상태 줄에, 주제는 바로 보여 주고, 일을 시킨 말이면 받았다는 한마디를 먼저 건넨다 (말로 대화 중이면 읽고,
     * 아니면 화면 아래 한마디로). 한마디를 건넸으면 턴 글에 `<already_said>` 를 붙여 모델이 같은 인사를 되풀이하지 않게 한다.
     * 판단이 없으면(키 없음·늦음·오류) 턴은 그대로 돈다. 메인 스레드에서 부르고, Jev 호출은 입출력 스레드에서 한다.
     */
    private suspend fun beforeTurn(record: Inflight, input: String): Inflight {
        val key = store.jevKey.trim()
        if (key.isEmpty() || input.isBlank()) return record
        val topics = board.topics.map { it.id to it.name }
        val recentText = Decide.recentText(recent.toList())
        val en = english
        val pre = withContext(Dispatchers.IO) { runCatching { Decide.before(key, input, topics, recentText, en, jevUrl, log = ::jevLog) }.getOrNull() } ?: return record
        pre.kind?.let { activity = if (en) "Thinking…" else "$it · 생각하는 중" }
        pre.topic?.takeIf { id -> board.topics.any { it.id == id } }?.let {
            current = it
            rev++
        }
        val ack = pre.ack ?: return record
        liveSay = ack
        if (voice && !hush && !standby) {
            speech.addLast(ack to mutableListOf())
            advanceSpeech()
        }
        return record.copy(turnText = record.turnText + Decide.alreadySaid(ack))
    }

    /**
     * 턴 뒤의 판단: 이번 턴에 AI 가 건드린 카드(최대 20장)가 화면만 보고 분명한지, 얼마나 중요한지를 Jev 에게 묻는다.
     * 턴이 끝난 뒤 뒤에서 돌고(입출력 스레드), 키가 없거나 실패하면 아무 일도 없다.
     * 분명하지 않은 카드는 다음 턴 글에 `<review>` 로 실리고, 중요도는 카드 순서에 쓰인다.
     */
    internal fun reviewTurn() {
        val key = store.jevKey.trim()
        val cards = touched.toList().asReversed().mapNotNull { id ->
            board.card(id)?.let { c -> Decide.CardRef(c.id, board.topics.firstOrNull { it.id == c.topic }?.name.orEmpty(), c.title) }
        }.take(Decide.MAX_CARDS)
        touched.clear()
        if (key.isEmpty() || cards.isEmpty()) return
        val snapshot = Lang.serialize(board) // 메인 스레드에서 떠 둔다: 보드는 다른 스레드에서 읽지 않는다
        viewModelScope.launch {
            val post = withContext(Dispatchers.IO) { runCatching { Decide.after(key, snapshot, cards, jevUrl, log = ::jevLog) }.getOrNull() } ?: return@launch
            unclear = post.unclear
            importance = importance + post.importance // 이번에 검토한 카드의 값만 고친다
        }
    }

    private val jevLogLock = Any()

    /** Jev 를 부른 기록 (입력 앞부분, 고른 값, 걸린 시간, 앱 버전). 키는 적지 않는다. 기록이 실패해도 아무 일도 없다. */
    private fun jevLog(line: String) {
        try {
            synchronized(jevLogLock) {
                val f = File(files, "jev.log")
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())
                // 너무 커지면 뒷부분만 남긴다
                if (f.length() > 256 * 1024) f.writeText(f.readText().takeLast(128 * 1024))
                f.appendText("$stamp v${BuildConfig.VERSION_NAME}  $line\n")
            }
        } catch (_: Exception) {
        }
    }

    /** 다시 붙는 턴의 엔진: 서버에 닿지 않으면 닿을 때까지 간격을 두고 다시 해 본다 (멈춤과 앞으로 돌아옴에 바로 깬다). */
    private fun engineWhenReachable(record: Inflight, evs: Channel<Ev>): Runner {
        val runId = record.id
        var attempt = 0
        while (true) {
            if (cleared) throw Exception(ABANDONED)
            // 설정이 그사이 다른 서버로 바뀌었으면 그 서버에는 붙지 않는다 (거기서 이 턴이 새로 시작되면 같은 말이 두 번 돈다)
            if (!serverEngine || needsSetup() || record.server != serverSig()) throw Exception(OTHER_SERVER)
            try {
                return engine(resume = true)
            } catch (e: SshFatal) {
                throw e
            } catch (e: RemoteFail) {
                throw e
            } catch (e: Exception) {
                if (cleared) throw Exception(ABANDONED)
                if (stopFlag) {
                    // 서버에 멈춤을 보내지 못했다: 다음에 붙을 때 멈추고 치운다
                    markToClean(runId)
                    throw Exception(STOPPED)
                }
                val wait = Detached.delayMs(attempt++)
                evs.trySend(Ev.Activity(if (english) "The server cannot be reached → the last message continues once it can → nothing to do" else "서버에 닿지 않습니다 → 닿으면 하던 말을 이어 받습니다 → 할 일은 없습니다"))
                resumeWaker.sleep(wait) { stopFlag || cleared }
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
        saveQueue()
        silence()
        eventTimer?.cancel() // 멈춘 뒤에는 카드 조작 알림도 저절로 보내지 않는다
        if (busy) {
            stopFlag = true
            currentRunId?.let { markToClean(it) } // 서버에 닿지 않아 멈춤을 못 보내도 연결되는 대로 멈추고 치운다
            // 아직 서버에 붙는 중(연결·준비)이면 그 연결을 바로 끊어 풀어 준다: 그 단계에서는 이 연결로 서버에서 도는 것이 없다
            if (!prepared) {
                val now = ssh
                if (now != null) viewModelScope.launch(Dispatchers.IO) { now.close() }
                val pc = if (BuildConfig.PRO) engine as? ClaudeCode else null
                if (pc != null) viewModelScope.launch(Dispatchers.IO) { pc.closeLink() }
            }
            // 서버에 닿는 단계(연결·준비)에서 멈추지 못하고 매달려 있으면 10초 뒤 연결을 끊어 풀어 준다
            val seq = turnSeq
            viewModelScope.launch {
                delay(10_000)
                if (busy && seq == turnSeq) {
                    val s = ssh
                    val pc = if (BuildConfig.PRO) engine as? ClaudeCode else null
                    withContext(Dispatchers.IO) {
                        s?.close()
                        pc?.closeLink()
                    }
                }
            }
        }
    }

    /** 줄 하나를 보드에 반영한다. AI 의 줄이 거부되면 다음 턴에 알려 준다. */
    private fun applyLine(line: String, by: Author) {
        // 어떤 카드를 건드렸는지: 줄의 둘째 낱말 (데스크톱과 같다. 카드가 아닌 것은 턴 뒤에 걸러진다)
        if (by == Author.Ai) line.trim().split(Regex("\\s+")).getOrNull(1)?.let { touched.add(it) }
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
            if (by == Author.Ai || reportUserRejects) rejects.add("${line.trim().take(80)} → ${e.line}")
        }
        if (current == null || board.topics.none { it.id == current }) current = board.topics.lastOrNull()?.id
        rev++
    }

    /**
     * AI 가 낸 한 줄을 받는다. 음성이 꺼져 있으면 바로 화면에 반영하고,
     * 켜져 있으면 "문장 → 그 문장의 카드" 순서로 줄을 세워 말하는 속도에 맞춰 내보낸다.
     */
    internal fun aiLine(line: String) {
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

    /** 소리 내어 읽기가 실패했다고 이미 알렸는가 (ElevenLabs 든 폰의 기본 음성이든, 문장마다 같은 줄을 되풀이해 띄우지 않는다). 다시 잘 읽으면 풀린다. */
    private var speakFailShown = false

    private fun speak(text: String) {
        speaking = true
        val epoch = ++speakEpoch
        val key = store.elevenKey.trim()
        val viaEleven: () -> Unit = { Eleven.speak(key, store.voiceId.trim(), board.show(text), player ?: Player().also { player = it }) { epoch != speakEpoch } }
        viewModelScope.launch {
            // ElevenLabs 가 실패하면 틀던 소리를 멈추고, 이 문장의 소리가 하나도 나가지 않았을 때만 폰의 기본 음성으로 읽는다 (Speaking.read)
            val out = withContext(Dispatchers.IO) {
                Speaking.read(if (key.isEmpty()) null else viaEleven, stop = { player?.stop() }, current = { epoch == speakEpoch }, phone = { speakWithDevice(board.show(text), epoch) })
            }
            if (epoch != speakEpoch) return@launch
            // 한 줄로 알린다 (무엇이 문제 → 이 문장을 어떻게 했나 → 할 일). 다시 잘 읽을 때까지는 한 번만.
            val line = Speaking.notice(out, english)
            if (line == null) {
                speakFailShown = false
            } else {
                if (!speakFailShown) error = line
                speakFailShown = true
            }
            speaking = false
            advanceSpeech()
        }
    }

    /** ElevenLabs 키가 없거나 ElevenLabs 가 소리를 내기 전에 실패했을 때: 폰에 들어 있는 음성으로 읽는다. */
    private suspend fun speakWithDevice(text: String, epoch: Int) {
        val tts = deviceTts ?: TextToSpeech(getApplication()) { status -> deviceTtsReady.complete(status == TextToSpeech.SUCCESS) }.also { deviceTts = it }
        // 엔진이 안 켜졌거나 읽기를 받아들이지 않았거나 읽다가 오류가 나면 예외: 읽지 못한 것으로 알리고 다음 문장으로 넘어간다 (Speaking.phone)
        Speaking.phone(ready = { deviceTtsReady.await() }, current = { epoch == speakEpoch }, readMs = Speaking.phoneReadMs(text.length)) { done ->
            tts.language = if (english) Locale.ENGLISH else Locale.KOREAN
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) {
                    done(true)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) {
                    done(false)
                }

                override fun onStop(id: String?, interrupted: Boolean) {
                    done(true)
                }
            })
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "kite-$epoch") == TextToSpeech.SUCCESS
        }
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
        if (BuildConfig.PRO) return // Pro 1단계에는 말로 대화하기가 없다 (A1)
        if (on && store.elevenKey.isBlank()) {
            error = if (english) "Talking needs an ElevenLabs key. Add it in Settings." else "말로 대화하려면 ElevenLabs 키가 필요합니다. 설정에서 넣어 주세요."
            return
        }
        if (converse == on) return
        converse = on
        if (on) {
            speakFailShown = false
            hearFailShown = false
        }
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
            // 마이크는 "열지 못했다 / 멈췄다"까지만 안다. 권한이 없어서인지는 여기서 본다. 말로 대화하기를 끄고 한 줄로 알린다.
            val granted = getApplication<Application>().checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
            error = Speaking.micNotice(if (granted) it else MicProblem.PERMISSION, english)
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

    /** 받아쓰기가 실패했다고 이미 알렸는가 (말할 때마다 같은 줄을 되풀이해 띄우지 않는다). 다시 받아쓰면 풀린다. */
    private var hearFailShown = false

    private fun transcribe(pcm: ShortArray) {
        transcribing = true
        val key = store.elevenKey.trim()
        val en = english
        val waiting = standby // 부르는 말을 기다리던 중에 들은 소리인가
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { Eleven.transcribe(key, pcm, en) } }
            transcribing = false
            result.onSuccess {
                hearFailShown = false
                // 받아쓰는 사이 말로 대화하기를 꺼도 (앱이 뒤로 가도 꺼진다) 켜져 있을 때 한 말은 보낸다.
                // 다만 부르는 말을 기다리던 중에 들은 소리는, 꺼지면서 기다림이 풀렸어도 부르는 말이 아니면 보내지 않는다.
                if (converse || !waiting || Words.isWake(it)) heardText(it)
            }.onFailure {
                // 방금 들은 말은 버린다. 켜져 있으면 tick 이 마이크를 다시 열어 계속 듣고, 그사이 꺼졌으면 듣지 않는다: 그대로 알린다.
                // 한 줄로, 다시 받아쓸 때까지 한 번만.
                if (!hearFailShown) error = Speaking.heardNotice((it as? Eleven.Failure)?.why ?: Eleven.Why.OTHER, converse, en)
                hearFailShown = true
            }
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

    private fun userLine(line: String, persist: Boolean = true) {
        if (replaying) {
            // 서버에서 돌던 턴을 다시 받는 중이다: 지금 반영하면 뒤따라오는 그 턴의 줄이 덮어쓴다. 모아 두었다가 따라잡은 뒤에 반영한다.
            lateTaps.add(line)
            events.add(line)
            if (persist) saveQueue() // 그사이 꺼져도 다음에 켤 때 다시 반영된다
            return
        }
        applyLine(line, Author.User)
        events.add(line)
        if (persist) {
            saveQueue()
            save() // 카드를 만진 것은 바로 저장한다 (일꾼 스레드가 쓰고, 앱이 정상적으로 멈추기 전에 디스크에 닿는다)
        }
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

    /** Several answers to a decision card that takes them (`multi yes`), in item order. */
    fun chooseMany(card: Card, picked: Set<String>) {
        val ids = card.items.map { it.id }.filter { it in picked }
        if (ids.isEmpty()) return
        userLine("set ${card.id} chosen ${ids.joinToString(",")}")
        send("")
    }

    /** 입력 카드에 적은 값을 보낸다 (항목 ID → 값). */
    fun submit(card: Card, values: Map<String, String>) {
        for (item in card.items) {
            val v = safe(values[item.id].orEmpty())
            userLine("upd ${card.id} ${item.id} ${item.cells.getOrElse(0) { "" }} | ${item.cells.getOrElse(1) { "" }} | $v", persist = false)
        }
        userLine("set ${card.id} sent yes") // 저장은 한 번
        send("")
    }

    /** 주제를 보관함으로 내린다. */
    fun archive(topic: String) {
        userLine("archive $topic")
    }

    /** 화면을 비운다 (대화 기록도 지운다). */
    fun clearAll() {
        currentRunId?.takeIf { busy }?.let { markToClean(it) } // 돌던 서버 실행은 멈추고 치운다
        stop()
        synchronized(recordLock) {
            generation++ // 돌던 턴의 줄과 끝맺음이 새 화면에 닿지 않게
            recordOwner = 0
            journal.clearInflight() // 지우지 않으면 다시 켰을 때 옛 보드가 되살아난다. 실행 번호를 적는 쪽과 같은 자물쇠 안이라, 지운 뒤에 옛 기록이 다시 적히지 않는다
        }
        board = Board()
        recent.clear()
        recentBase = 0
        events.clear()
        // 옛 화면에 딸린 것은 모두 버린다: 다시 붙는 동안 모아 둔 카드 조작, 다음 턴에 알리려던 거부
        replayTaps = emptyList()
        lateTaps.clear()
        replaying = false
        rejects.clear()
        reportUserRejects = false
        touched.clear()
        unclear = emptyList()
        importance = emptyMap()
        current = null
        rev++
        journal.clearQueue()
        save()
    }

    /** 앱이 앞으로 돌아왔다: 다시 붙기를 기다리는 중이면 바로 시도한다. */
    fun foreground() {
        if (BuildConfig.PRO) proRefresh()
        resumeWaker.wake()
        (engine as? ClaudeCode)?.wake()
        settleWaker.wake()
        settleWhenReachable()
    }

    /** 앱이 뒤로 갔다: 보드를 저장하고, 말로 대화하기는 끈다 (마이크가 뒤에서 계속 돌지 않게). */
    fun background() {
        save()
        journal.flush(1000) // 앱이 멈추기 전에 디스크에 닿게 한다. 메인 스레드가 일꾼을 기다리는 곳은 여기와 onCleared 뿐이고, 1초까지만 기다린다
        if (converse) converse(false)
    }

    /** 시험용: 밀린 쓰기가 끝나기를 기다린다. */
    internal fun flushWrites(): Boolean {
        val end = System.currentTimeMillis() + 10_000
        while (settling.get() > 0 && System.currentTimeMillis() < end) Thread.sleep(5) // 턴 끝의 디스크 정리가 끝나기를 기다린다
        return journal.flush()
    }

    override fun onCleared() {
        cleared = true
        resumeWaker.wake()
        settleWaker.wake()
        (engine as? ClaudeCode)?.abandon() // 서버의 실행은 그대로 두고 따라 읽기만 그만둔다 (다음에 켜면 inflight.json 으로 다시 붙는다)
        converse(false)
        player?.release()
        deviceTts?.shutdown()
        val s = ssh
        val pc = if (BuildConfig.PRO) engine as? ClaudeCode else null
        Thread {
            s?.close()
            pc?.closeLink()
        }.start()
        journal.flush(1000)
    }
}
