// 설정을 폰에 남긴다. 암호·키 같은 것은 기기의 키 저장소로 암호화해서 둔다.
package com.fidenit.kite

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import kotlin.reflect.KProperty

class Store(context: Context) {
    private val prefs: SharedPreferences = try {
        EncryptedSharedPreferences.create(
            "kite-settings",
            MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (_: Exception) {
        // 키 저장소를 못 쓰는 기기: 앱 전용 저장소에 그대로 둔다 (다른 앱은 읽지 못한다).
        context.getSharedPreferences("kite-settings-plain", Context.MODE_PRIVATE)
    }

    inner class Text(private val default: String = "") {
        operator fun getValue(self: Any?, p: KProperty<*>): String = prefs.getString(p.name, default) ?: default
        operator fun setValue(self: Any?, p: KProperty<*>, v: String) = prefs.edit().putString(p.name, v).apply()
    }

    // 엔진: api(폰에서 Claude API 를 바로 부른다) 또는 ssh(내 서버의 Claude Code). APERIT Pro 는 늘 api(중계 서버).
    var engineKind by Text("api")

    // Claude API (무료 앱: 사용자의 키)
    var apiKey by Text()
    var model by Text("claude-opus-5-5")
    var apiEffort by Text("low")

    // APERIT Pro: 중계 서버가 이 기기에 내준 토큰 (구독을 확인하면 받는다)
    var relayToken by Text()

    // 서버
    var host by Text()
    var port by Text("22")
    var user by Text()
    var password by Text()
    var privateKey by Text()
    var passphrase by Text()
    var publicKey by Text()
    var fingerprint by Text()

    // Claude Code
    var claude by Text("claude")
    var dir by Text("~")
    var permission by Text("default")
    var effort by Text("low")
    var extra by Text()

    // 음성
    var elevenKey by Text()
    var voiceId by Text()
    var lang by Text("ko")
    var micThreshold by Text("0.02")

    // 판단 모델: TypeSafe 의 Jev (선택). 비어 있으면 그 판단 없이 기본대로 돈다. signature() 에는 넣지 않는다: 엔진이 다시 만들어질 이유가 아니다.
    var jevKey by Text()

    /** 엔진을 다시 만들어야 하는지 알아보는 데 쓰는 글. */
    fun signature(): String =
        listOf(engineKind, apiKey, model, apiEffort, relayToken, host, port, user, password, privateKey, passphrase, claude, dir, permission, effort, extra).joinToString("\u0001")
}
