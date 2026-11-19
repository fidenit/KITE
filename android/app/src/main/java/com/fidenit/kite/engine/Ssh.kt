// SSH 연결 하나. 서버에서 명령을 돌리고 그 출력을 줄 단위로 받는다.
package com.fidenit.kite.engine

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import kotlin.concurrent.thread

class Server(
    val host: String,
    val port: Int,
    val user: String,
    val password: String,
    val privateKey: String,
    val passphrase: String,
    /** 처음 접속했을 때 기억해 둔 서버 지문. 비어 있으면 이번에 받은 것을 기억한다. */
    val fingerprint: String,
)

/** 다시 시도해도 소용없는 연결 오류: 서버 지문이 바뀜, 로그인 거절, 설정이 비어 있음. */
class SshFatal(message: String) : Exception(message)

/** 서버에서 명령이 오류로 끝났다 (연결이 끊긴 것이 아니다). */
class RemoteFail(message: String) : Exception(message)

/** 작은따옴표로 감싸 셸에 그대로 넘긴다. */
fun shq(s: String): String = "'" + s.replace("'", "'\\''") + "'"

class Ssh(private val server: Server, private val remember: (String) -> Unit) {
    private var session: Session? = null

    val connected: Boolean get() = session?.isConnected == true

    /**
     * 서버 지문 검사: 처음 보는 서버는 지문을 기억하고 받아들인다. 기억한 것과 다르면 거절한다.
     * 이 검사는 암호나 키를 보내기 전에 일어난다.
     */
    private inner class Known : HostKeyRepository {
        var seen: String = server.fingerprint
        override fun check(host: String?, key: ByteArray?): Int {
            val now = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(key ?: ByteArray(0)))
            if (seen.isEmpty()) {
                seen = now
                remember(now)
                return HostKeyRepository.OK
            }
            return if (seen == now) HostKeyRepository.OK else HostKeyRepository.CHANGED
        }
        override fun add(hostkey: HostKey?, ui: UserInfo?) {}
        override fun remove(host: String?, type: String?) {}
        override fun remove(host: String?, type: String?, key: ByteArray?) {}
        override fun getKnownHostsRepositoryID(): String = "kite"
        override fun getHostKey(): Array<HostKey> = emptyArray()
        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
    }

    @Synchronized
    fun connect(timeoutMs: Int = 15_000) {
        if (connected) return
        if (server.host.isBlank() || server.user.isBlank()) throw SshFatal("서버 주소와 계정을 설정에 적어 주세요")
        val jsch = JSch()
        jsch.hostKeyRepository = Known()
        if (server.privateKey.isNotBlank()) {
            jsch.addIdentity("kite", (server.privateKey.trim() + "\n").toByteArray(), null, server.passphrase.takeIf { it.isNotEmpty() }?.toByteArray())
        }
        val s = jsch.getSession(server.user.trim(), server.host.trim(), server.port)
        if (server.password.isNotEmpty()) s.setPassword(server.password)
        s.setConfig("StrictHostKeyChecking", "yes")
        s.setConfig("PreferredAuthentications", "publickey,password,keyboard-interactive")
        s.serverAliveInterval = 15_000
        s.serverAliveCountMax = 4
        try {
            s.connect(timeoutMs)
        } catch (e: Exception) {
            val m = e.message ?: e.toString()
            val changed = m.contains("HostKey has been changed") || m.contains("reject HostKey")
            val refused = m.contains("Auth fail") || m.contains("Auth cancel")
            val text = when {
                changed -> "서버 지문이 기억한 것과 다릅니다. 서버를 다시 설치했다면 설정에서 '서버 지문 잊기'를 누르세요."
                refused -> "로그인이 거절되었습니다. 계정과 암호 또는 키를 확인해 주세요."
                else -> "서버에 연결하지 못했습니다: $m"
            }
            throw if (changed || refused) SshFatal(text) else Exception(text)
        }
        session = s
    }

    @Synchronized
    fun close() {
        try {
            session?.disconnect()
        } catch (_: Exception) {
        }
        session = null
    }

    /**
     * 명령을 돌린다. 표준 입력으로 `stdin` 을 주고 닫는다. 표준 출력은 한 줄씩 `onLine` 으로 넘긴다.
     * (끝난 코드, 표준 오류)를 돌려준다.
     */
    fun exec(command: String, stdin: String?, onLine: (String) -> Unit): Pair<Int, String> {
        connect()
        val ch = session!!.openChannel("exec") as ChannelExec
        ch.setCommand(command)
        ch.setInputStream(null)
        val input = ch.outputStream
        val out = ch.inputStream
        val err = ch.errStream
        ch.connect(15_000)
        val errors = ByteArrayOutputStream()
        val errReader = thread {
            try {
                err.copyTo(errors)
            } catch (_: Exception) {
            }
        }
        thread {
            try {
                if (stdin != null) input.write(stdin.toByteArray())
                input.flush()
                input.close() // 입력이 끝났음을 알린다
            } catch (_: Exception) {
            }
        }
        try {
            out.bufferedReader(Charsets.UTF_8).forEachLine(onLine)
            errReader.join(2000)
            var waited = 0
            while (!ch.isClosed && waited < 3000) {
                Thread.sleep(50)
                waited += 50
            }
            return ch.exitStatus to errors.toString("UTF-8")
        } finally {
            ch.disconnect()
        }
    }

    /** 짧은 명령 하나를 돌리고 출력을 통째로 받는다. */
    fun run(command: String, stdin: String? = null): String {
        val sb = StringBuilder()
        val (code, err) = exec(command, stdin) { sb.append(it).append('\n') }
        if (code != 0 && sb.isEmpty()) throw RemoteFail(err.trim().takeLast(300).ifEmpty { "끝난 코드 $code" })
        return sb.toString()
    }

    companion object {
        /** 이 폰만의 키 한 쌍을 만든다: (개인 키, 서버에 등록할 공개 키 한 줄). */
        fun newKey(comment: String): Pair<String, String> {
            val kp = KeyPair.genKeyPair(JSch(), KeyPair.RSA, 3072)
            val prv = ByteArrayOutputStream()
            val pub = ByteArrayOutputStream()
            kp.writePrivateKey(prv)
            kp.writePublicKey(pub, comment)
            kp.dispose()
            return prv.toString("UTF-8") to pub.toString("UTF-8").trim()
        }
    }
}
