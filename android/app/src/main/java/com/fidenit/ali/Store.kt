// 설정을 폰에 남긴다. 암호·키 같은 것은 기기의 키 저장소로 암호화해서 둔다.
package com.fidenit.ali

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import kotlin.reflect.KProperty

class Store(context: Context) {
    private val prefs: SharedPreferences = try {
        EncryptedSharedPreferences.create(
            "ali-settings",
            MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (_: Exception) {
        // 키 저장소를 못 쓰는 기기: 앱 전용 저장소에 그대로 둔다 (다른 앱은 읽지 못한다).
        context.getSharedPreferences("ali-settings-plain", Context.MODE_PRIVATE)
    }

    inner class Text(private val default: String = "") {
        operator fun getValue(self: Any?, p: KProperty<*>): String = prefs.getString(p.name, default) ?: default
        operator fun setValue(self: Any?, p: KProperty<*>, v: String) = prefs.edit().putString(p.name, v).apply()
    }

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

    /** 엔진을 다시 만들어야 하는지 알아보는 데 쓰는 글. */
    fun signature(): String = listOf(host, port, user, password, privateKey, passphrase, claude, dir, permission, effort, extra).joinToString("\u0001")
}
