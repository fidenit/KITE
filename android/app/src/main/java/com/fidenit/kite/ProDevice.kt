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
}

class ProDevice(
    private val store: DeviceStore,
    private val enroller: Enroller,
    /** (기기 모델, 안드로이드 판, 앱 판) */
    private val about: () -> Triple<String, String, String>,
) {
    /** 이름표와 비밀 글이 없으면 만든다. 한 번 만든 것은 바꾸지 않는다 (서버가 409 로 거절했을 때만 새로). */
    fun ensure() {
        if (store.deviceId.isBlank() || store.deviceSecret.isBlank()) renew()
    }

    private fun renew() {
        store.deviceId = DeviceKeys.newId()
        store.deviceSecret = DeviceKeys.newSecret()
        store.proStatus = ""
        store.proCode = ""
    }

    val secret: String get() = store.deviceSecret

    /** 짧은 기기 번호 (설정 화면에 보인다). */
    val shortId: String get() = store.deviceId.take(8)

    val approved: Boolean get() = store.proStatus == "approved"

    /**
     * 서버에 물어 상태를 고친다: 아직 등록 안 했으면 등록, 했으면 `/v1/me`. 서버가 이 기기를 잊었으면(401) 다시 등록하고,
     * 같은 이름표가 다른 비밀 글로 이미 있으면(409) 새 이름표로 한 번 다시 등록한다. 연결이 안 되면 IOException (상태는 그대로).
     */
    fun check(): DeviceStatus {
        ensure()
        var st = if (store.proStatus.isEmpty()) enroll() else enroller.me(store.deviceSecret)
        if (st.status == "unknown") st = enroll()
        if (st.status == "conflict") {
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
        return enroller.enroll(store.deviceId, store.deviceSecret, model, android, app)
    }

    /** 게이트웨이가 요청을 거절했다 ([com.fidenit.kite.engine.GatewayLink.onRefused]): 상태를 고쳐 둔다. */
    fun refused(kind: String, code: String) {
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
    }
}
