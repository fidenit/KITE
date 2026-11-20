// 설정 저장소를 여는 규칙. APERIT Pro 는 기기 비밀 글을 키 저장소로 암호화한 곳에만 둔다.
package com.fidenit.kite

import android.content.Context
import android.content.SharedPreferences

/**
 * 무료 앱: 지금처럼 암호화 저장소를 열고, 못 열면 앱 전용 일반 저장소 (`plain`).
 * Pro: 암호화 저장소를 열고 모든 값을 한 번 읽어 본다. 못 열거나 못 읽으면(다른 폰에 복원됨·손상) 그 저장소를 지우고(`wipe`) 다시 연다
 * (빈 저장소 → 새 이름표로 다시 등록, 허용 대기가 된다). 그래도 안 되면 이번 실행 동안만 메모리에 둔다. 일반 저장소로는 내려가지 않는다.
 */
fun openSettings(
    pro: Boolean,
    open: () -> SharedPreferences,
    wipe: () -> Unit,
    plain: () -> SharedPreferences,
    memory: () -> SharedPreferences,
): SharedPreferences {
    if (!pro) {
        return try {
            open()
        } catch (_: Exception) {
            plain()
        }
    }
    fun tryOpen(): SharedPreferences? = try {
        open().also { it.all }
    } catch (_: Exception) {
        null
    }
    tryOpen()?.let { return it }
    try {
        wipe()
    } catch (_: Exception) {
    }
    return tryOpen() ?: memory()
}

/** 암호화 설정 파일과 그 열쇠를 지운다 (열쇠는 androidx.security 의 기본 이름). */
fun wipeSecureSettings(context: Context, name: String) {
    try {
        context.deleteSharedPreferences(name)
    } catch (_: Exception) {
    }
    try {
        val ks = java.security.KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        ks.deleteEntry("_androidx_security_master_key_")
    } catch (_: Exception) {
    }
}

/** 이번 실행 동안만 쓰는 저장소 (파일에 쓰지 않는다). */
class MemoryPrefs : SharedPreferences {
    private val map = java.util.concurrent.ConcurrentHashMap<String, Any>()

    override fun getAll(): MutableMap<String, *> = HashMap(map)
    override fun getString(key: String, defValue: String?): String? = map[key] as? String ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = (map[key] as? Set<String>)?.toMutableSet() ?: defValues
    override fun getInt(key: String, defValue: Int): Int = map[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long): Long = map[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = map[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
    override fun contains(key: String): Boolean = map.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val put = HashMap<String, Any?>()
        private var clear = false
        override fun putString(key: String, value: String?): SharedPreferences.Editor = also { put[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor = also { put[key] = values?.toSet() }
        override fun putInt(key: String, value: Int): SharedPreferences.Editor = also { put[key] = value }
        override fun putLong(key: String, value: Long): SharedPreferences.Editor = also { put[key] = value }
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = also { put[key] = value }
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = also { put[key] = value }
        override fun remove(key: String): SharedPreferences.Editor = also { put[key] = null }
        override fun clear(): SharedPreferences.Editor = also { clear = true }
        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            if (clear) map.clear()
            for ((k, v) in put) if (v == null) map.remove(k) else map[k] = v
        }
    }
}
