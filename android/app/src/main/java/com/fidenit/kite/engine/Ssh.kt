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

/**
 * 줄 단위로 읽는다. `completeLinesOnly` 가 참이면 줄바꿈으로 끝난 줄만 넘기고 끝에 남은 조각은 버린다
 * (연결이 줄 한가운데서 끊겼을 때 반쪽 줄을 한 줄로 세지 않으려고). 거짓이면 마지막 줄에 줄바꿈이 없어도 넘긴다.
 */
fun readLines(r: java.io.Reader, completeLinesOnly: Boolean, onLine: (String) -> Unit) {
    val buf = CharArray(8192)
    val line = StringBuilder()
    while (true) {
        val n = r.read(buf)
        if (n < 0) break
        for (i in 0 until n) {
            val c = buf[i]
            if (c == '\n') {
                if (line.isNotEmpty() && line[line.length - 1] == '\r') line.setLength(line.length - 1)
                onLine(line.toString())
                line.setLength(0)
            } else {
                line.append(c)
            }
        }
    }
    if (!completeLinesOnly && line.isNotEmpty()) onLine(line.toString())
}

/** 작은따옴표로 감싸 셸에 그대로 넘긴다. */
fun shq(s: String): String = "'" + s.replace("'", "'\\''") + "'"

class Ssh(private val server: Server, private val remember: (String) -> Unit) {
    @Volatile
    private var session: Session? = null

    /** 지금 연결하는 중인 세션과 소켓: [close] 가 연결이 끝나기를 기다리지 않고 끊는 데 쓴다 */
    @Volatile
    private var pending: Session? = null

    @Volatile
    private var pendingSocket: java.net.Socket? = null

    /** [close] 가 불린 횟수: 연결하는 동안 불렸으면 그 연결은 쓰지 않고 버린다 */
    private val closes = java.util.concurrent.atomic.AtomicInteger(0)
    private val connecting = java.util.concurrent.locks.ReentrantLock()
    private val state = Any()

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

    /**
     * 연결한다 (이미 연결돼 있으면 아무것도 하지 않는다). 한 번에 하나만 연결하고, 다른 스레드가 연결하는 중이면 `timeoutMs` 까지만 기다린다.
     * 연결하는 동안 [close] 가 불리면 바로 그만두고 예외를 던진다.
     */
    fun connect(timeoutMs: Int = 15_000) {
        if (connected) return
        if (server.host.isBlank() || server.user.isBlank()) throw SshFatal("서버 주소와 계정을 설정에 적어 주세요")
        if (!connecting.tryLock(timeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)) throw java.io.IOException("서버에 연결하지 못했습니다: 앞선 연결이 끝나지 않았습니다")
        try {
            if (connected) return
            val mark = closes.get()
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
            // 소켓을 직접 만든다: 연결하는 중에도 close() 가 이 소켓을 닫아 바로 풀어 줄 수 있다
            s.setSocketFactory(object : com.jcraft.jsch.SocketFactory {
                override fun createSocket(host: String, port: Int): java.net.Socket {
                    val sock = java.net.Socket()
                    pendingSocket = sock
                    if (closes.get() != mark) throw java.io.IOException("closed")
                    sock.connect(java.net.InetSocketAddress(host, port), timeoutMs)
                    return sock
                }

                override fun getInputStream(socket: java.net.Socket): java.io.InputStream = socket.getInputStream()

                override fun getOutputStream(socket: java.net.Socket): java.io.OutputStream = socket.getOutputStream()
            })
            pending = s
            try {
                s.connect(timeoutMs)
            } catch (e: Exception) {
                if (closes.get() != mark) throw java.io.IOException("서버에 연결하지 못했습니다: 연결을 그만두었습니다")
                val m = e.message ?: e.toString()
                val changed = m.contains("HostKey has been changed") || m.contains("reject HostKey")
                val refused = m.contains("Auth fail") || m.contains("Auth cancel")
                val text = when {
                    changed -> "서버 지문이 기억한 것과 다릅니다. 서버를 다시 설치했다면 설정에서 '서버 지문 잊기'를 누르세요."
                    refused -> "로그인이 거절되었습니다. 계정과 암호 또는 키를 확인해 주세요."
                    else -> "서버에 연결하지 못했습니다: $m"
                }
                throw if (changed || refused) SshFatal(text) else Exception(text)
            } finally {
                pending = null
                pendingSocket = null
            }
            val kept = synchronized(state) {
                if (closes.get() == mark) {
                    session = s
                    true
                } else {
                    false
                }
            }
            if (!kept) {
                try {
                    s.disconnect()
                } catch (_: Exception) {
                }
                throw java.io.IOException("서버에 연결하지 못했습니다: 연결을 그만두었습니다")
            }
        } finally {
            connecting.unlock()
        }
    }

    /** 연결을 끊는다. 연결하는 중이면 그것도 그만두게 한다. 연결이 끝나기를 기다리지 않는다 (멈춤이 제때 끝나야 한다). */
    fun close() {
        val s = synchronized(state) {
            closes.incrementAndGet()
            session.also { session = null }
        }
        try {
            pendingSocket?.close()
        } catch (_: Exception) {
        }
        try {
            pending?.disconnect()
        } catch (_: Exception) {
        }
        try {
            s?.disconnect()
        } catch (_: Exception) {
        }
    }

    /**
     * 명령을 돌린다. 표준 입력으로 `stdin` 을 주고 닫는다. 표준 출력은 한 줄씩 `onLine` 으로 넘긴다.
     * `completeLinesOnly` 가 참이면 줄바꿈으로 끝난 줄만 넘기고, 연결이 끊겨 잘린 마지막 조각은 버린다 (이어 읽기용).
     * (끝난 코드, 표준 오류)를 돌려준다.
     */
    fun exec(command: String, stdin: String?, completeLinesOnly: Boolean = false, onLine: (String) -> Unit): Pair<Int, String> {
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
            readLines(out.bufferedReader(Charsets.UTF_8), completeLinesOnly, onLine)
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
        // 끝난 코드가 없다 = 명령이 끝나기 전에 연결이 끊겼다 (명령이 실패한 것이 아니다)
        if (code < 0) throw java.io.IOException("서버와의 연결이 끊겼습니다")
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
