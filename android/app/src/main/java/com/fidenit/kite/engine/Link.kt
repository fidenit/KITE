// ClaudeCode 엔진이 서버 쪽에 바라는 것: 떼어 돌리는 턴의 다섯 동작(올리기·시작·따라 읽기·멈춤·치우기)과 오류 읽기, 지시문 올리기, 기억 읽기.
// 두 가지로 닿는다: SSH(SshLink, 명령 글을 그대로 보낸다)와 우리 게이트웨이(GatewayLink, HTTPS).
package com.fidenit.kite.engine

/**
 * 끝난 코드의 약속 (두 구현이 같게 돌려준다): 0 = 됐다, 음수 = 연결이 끊겨 결과를 모른다 (다시 붙어 다시 한다),
 * 양수 = 서버가 분명히 거절했다 (두 번째 값이 짧은 이유). 다시 해도 소용없는 연결 오류는 [SshFatal] 을 던진다.
 * [clean]·[errTail]·[prepare]·[version]·[where] 는 실패하면 예외를 던진다.
 */
interface Link {
    /** 지금 붙어 있는가 (게이트웨이는 붙어 있는 연결이 없어 늘 참). */
    val connected: Boolean

    fun connect(timeoutMs: Int = 15_000)

    /** 연결을 끊는다. 다른 스레드에서 막혀 있는 호출도 풀린다 (예외나 음수 코드로 돌아온다). */
    fun close()

    /** 턴 글을 `<id>.in` 으로 올린다. */
    fun upload(id: String, text: String?): Pair<Int, String>

    /** 시작. 나온 줄(`APERITRUN <id>` / `APERITFAIL <이유>`)은 `onLine` 으로. */
    fun start(id: String, onLine: (String) -> Unit): Pair<Int, String>

    /** 따라 읽기: `n` 줄 받은 뒤부터. 줄바꿈으로 끝난(완성된) 줄만 `onLine` 으로 넘긴다 (`>…`, `APERITBEAT`, `APERITEXIT x`). */
    fun follow(id: String, n: Int, onLine: (String) -> Unit): Pair<Int, String>

    /** 멈춤 요청. 서버가 요청을 적었으면 `onLine` 으로 [Detached.STOP_SENT] 가 온다 (그 뒤 실행이 끝나기를 조금 기다린다). */
    fun stop(id: String, onLine: (String) -> Unit): Pair<Int, String>

    fun clean(id: String)

    /** 표준 오류의 끝부분. */
    fun errTail(id: String): String

    /** 서버의 Claude Code 버전 한 줄. */
    fun version(): String

    /** 서버의 환경: (운영체제, 홈 폴더). */
    fun where(): Pair<String, String>

    /** 지시문을 서버에 올리고 기억 보관함 폴더를 만든다. */
    fun prepare(system: String)

    /** 늘 기억하고 있는 것 (`기억/핵심.md`). 없으면 빈 글. */
    fun core(): String

    /** 서버가 턴 글을 받지 않았을 때의 한 줄 (`detail` = 짧은 이유). */
    fun uploadRefusedText(detail: String): String =
        "서버에 이 말을 저장하지 못했습니다 → 이번 말은 보내지 않았습니다 → 서버의 저장 공간과 폴더 권한을 확인한 뒤 다시 보내 주세요. ($detail)"

    /** 따라 읽기가 세 번 이어서 고장 났을 때의 한 줄. */
    fun followBrokenText(): String =
        "서버에서 결과를 읽어 오지 못합니다 → 서버의 작업은 다음에 연결할 때 멈춥니다 → 서버의 bash 설정을 확인한 뒤 같은 말을 다시 보내 주세요."
}

/** SSH 로 닿는다: 지금까지와 같은 명령 글을 그대로 보낸다. */
class SshLink(private val ssh: Ssh, cfg: EngineConfig) : Link {
    private val cmds = DetachedCommands(cfg)
    private val claude: String = cfg.claude.trim().ifEmpty { "claude" }

    override val connected: Boolean get() = ssh.connected

    override fun connect(timeoutMs: Int) = ssh.connect(timeoutMs)

    override fun close() = ssh.close()

    override fun upload(id: String, text: String?): Pair<Int, String> = ssh.exec(cmds.upload(id), text) {}

    override fun start(id: String, onLine: (String) -> Unit): Pair<Int, String> = ssh.exec(cmds.start(id), null, onLine = onLine)

    override fun follow(id: String, n: Int, onLine: (String) -> Unit): Pair<Int, String> =
        ssh.exec(cmds.follow(id, n), null, completeLinesOnly = true, onLine = onLine)

    override fun stop(id: String, onLine: (String) -> Unit): Pair<Int, String> = ssh.exec(cmds.stop(id), null, onLine = onLine)

    override fun clean(id: String) {
        ssh.run(cmds.clean(id))
    }

    override fun errTail(id: String): String = ssh.run(cmds.errTail(id))

    override fun version(): String = ssh.run(serverShell("$claude --version")).trim().lineSequence().lastOrNull().orEmpty()

    override fun where(): Pair<String, String> {
        val lines = ssh.run("uname -sm; echo \"\$HOME\"").trim().lines()
        return (lines.getOrNull(0) ?: "") to (lines.getOrNull(1) ?: "~")
    }

    override fun prepare(system: String) {
        ssh.run("mkdir -p ~/.kite/vault/기억 && cat > ~/.kite/system.txt", system)
    }

    override fun core(): String = ssh.run("cat ~/.kite/vault/기억/핵심.md 2>/dev/null || true")
}
