// APERIT Pro: 이 기기의 이름표·비밀 글을 처음 한 번 만들고, 게이트웨이에 등록·상태 확인을 한다. 사용자가 넣을 것은 없다.
package com.fidenit.kite

import com.fidenit.kite.engine.DeviceKeys
import com.fidenit.kite.engine.DeviceStatus
import com.fidenit.kite.engine.Enroller

/** 기기 정보를 둘 곳 (앱에서는 암호화된 [Store]). */
interface DeviceStore {
    var deviceId: String
    var deviceSecret: String

    /** 마지막으로 받은 상태: approved / pending / revoked (빈 글 = 아직 등록 안 함) */
    var proStatus: String

    /** 허용 대기 번호 (pending 일 때) */
    var proCode: String

    /** 빌드에 넣은 등록 표를 다 썼다 ("1": 허용됐거나 서버가 표를 판단해 답했다): 그 뒤로는 보내지 않는다. */
    var ticketUsed: String

    /** 암호화 저장소를 열지 못해 이번 실행 동안만 메모리에 둔다: 등록하지 않는다 (켤 때마다 새 기기가 생기지 않게). */
    val memoryOnly: Boolean get() = false
}

/**
 * 상태 확인의 주인. [check] 는 몇 번 불려도 서버에 묻는 일은 한 번에 하나이고, 성공한 뒤 [minGapMs] 안에는 다시 묻지 않으며,
 * 실패(연결 안 됨·429·5xx) 뒤에는 15·30·60·120·240·300초 간격을 지킨다. 그 사이에는 마지막 상태를 돌려준다.
 * 400·413(보낸 것이 잘못됨)과 두 번 이어진 409 는 이번 실행에서는 다시 묻지 않는다.
 * 상태 값: approved / pending / revoked, 그 밖에 offline(닿지 않음) / limited(429) / error(5xx) / refused(400·413) / conflict(409 두 번) / nostore(저장소 없음).
 */
class ProDevice(
    private val store: DeviceStore,
    private val enroller: Enroller,
    /** (기기 모델, 안드로이드 판, 앱 판) */
    private val about: () -> Triple<String, String, String>,
    /** 한 번만 쓰는 등록 표 (빌드할 때 넣는다, 비어 있으면 없음). 아직 허용되지 않은 동안만 보낸다. */
    private val ticket: String = "",
    private val now: () -> Long = System::currentTimeMillis,
    private val minGapMs: Long = 10_000,
) {
    private val lock = Any()
    private var nextAt = 0L
    private var fails = 0
    private var last: DeviceStatus? = null
    private var renewed = false
    private var ticketNoVerdict = 0

    /** 이름표와 비밀 글이 없으면 만든다. 한 번 만든 것은 바꾸지 않는다 (서버가 409 로 거절했을 때만 새로). */
    fun ensure() = synchronized(lock) {
        if (store.deviceId.isBlank() || store.deviceSecret.isBlank()) renew()
    }

    private fun renew() {
        // 비밀 글을 먼저 비우고 이름표를 쓴 뒤 비밀 글을 쓴다: 중간에 꺼져도 짝이 섞인 채로 남지 않는다 (빈 비밀 글이면 다음에 다시 만든다)
        store.deviceSecret = ""
        store.deviceId = DeviceKeys.newId()
        store.deviceSecret = DeviceKeys.newSecret()
        store.proStatus = ""
        store.proCode = ""
    }

    val secret: String get() = store.deviceSecret

    /** 짧은 기기 번호 (설정 화면에 보인다). */
    val shortId: String get() = store.deviceId.take(8)

    val approved: Boolean get() = store.proStatus == "approved"

    /** 마지막 [check] 에서 보낸 등록 표를 서버가 받지 않았다 (used / expired / revoked / invalid), 아니면 빈 글. */
    @Volatile
    var ticketRejected: String = ""
        private set

    /**
     * 지금 상태. 물을 때가 됐으면 서버에 묻고(아직 등록 안 했으면 등록, 했으면 `/v1/me`), 아니면 마지막 상태를 그대로 준다. 예외를 던지지 않는다.
     * 서버가 이 기기를 잊었으면(401, 허용 대기가 30분 지남) 같은 이름표로 다시 등록하고, 같은 이름표가 다른 비밀 글로 있으면(409) 새 이름표로 한 번 다시 등록한다.
     */
    fun check(): DeviceStatus = synchronized(lock) {
        if (store.memoryOnly) return DeviceStatus("nostore")
        val t = now()
        last?.let { if (t < nextAt) return it }
        ticketRejected = ""
        val st = try {
            ask()
        } catch (_: Exception) {
            DeviceStatus("offline")
        }
        when (st.status) {
            "approved", "pending", "revoked" -> {
                fails = 0
                nextAt = t + minGapMs
            }
            "refused", "conflict" -> nextAt = Long.MAX_VALUE
            else -> nextAt = t + backoff(fails++)
        }
        last = st
        st
    }

    private fun backoff(n: Int): Long = longArrayOf(15, 30, 60, 120, 240, 300)[n.coerceIn(0, 5)] * 1000

    /** 다음 [check] 가 바로 묻게 한다 (게이트웨이가 요청을 거절한 직후). 실패 간격은 그대로 지킨다. */
    fun stale() = synchronized(lock) {
        if (fails == 0) nextAt = 0
    }

    private fun ask(): DeviceStatus {
        if (store.deviceId.isBlank() || store.deviceSecret.isBlank()) renew()
        var st = if (store.proStatus.isEmpty()) enroll() else enroller.me(store.deviceSecret)
        if (st.status == "unknown") st = enroll()
        if (st.status == "conflict") {
            if (renewed) return st // 새 이름표도 409: 더 만들지 않는다
            renewed = true
            renew()
            st = enroll()
        }
        if (st.status in setOf("approved", "pending", "revoked")) {
            store.proStatus = st.status
            store.proCode = if (st.status == "pending") st.code else ""
        }
        return st
    }

    private fun enroll(): DeviceStatus {
        val (model, android, app) = about()
        val t = ticket.takeIf { it.isNotEmpty() && store.ticketUsed != "1" && store.proStatus != "approved" }
        val st = enroller.enroll(store.deviceId, store.deviceSecret, model, android, app, ticket = t)
        if (t != null) {
            when {
                st.ticket.isNotEmpty() -> store.ticketUsed = "1" // 서버가 표를 판단했다 (ok 든 아니든): 다시 보내도 소용없다
                st.status == "approved" -> store.ticketUsed = "1"
                st.status in setOf("pending", "revoked") && ++ticketNoVerdict >= 2 -> store.ticketUsed = "1" // 판단 없는 답이 두 번: 그만 보낸다
            }
            if (st.ticket.isNotEmpty() && st.ticket != "ok") ticketRejected = st.ticket
        }
        if (st.status == "approved") store.ticketUsed = "1"
        return st
    }

    /** 게이트웨이가 요청을 거절했다 ([com.fidenit.kite.engine.GatewayLink.onRefused]): 상태를 고쳐 둔다. */
    fun refused(kind: String, code: String) = synchronized(lock) {
        when (kind) {
            "pending" -> {
                store.proStatus = "pending"
                store.proCode = code
            }
            "revoked" -> {
                store.proStatus = "revoked"
                store.proCode = ""
            }
            "unknown" -> store.proStatus = "" // 다음 확인 때 다시 등록한다
        }
        last = DeviceStatus(store.proStatus.ifEmpty { "offline" }, store.proCode)
        if (fails == 0) nextAt = 0
    }
}
