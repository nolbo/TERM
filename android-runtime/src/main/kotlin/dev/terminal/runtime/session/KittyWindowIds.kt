package dev.terminal.runtime.session

import android.content.SharedPreferences

/** Runtime-owned identities. Use a dedicated preference file that the UI never writes. */
class KittyWindowIds(private val preferences: SharedPreferences) {
    fun allocate(): String = synchronized(lock) {
        val previous = preferences.getLong(KEY_LAST_ID, 0)
        check(previous in 0 until Int.MAX_VALUE.toLong()) { "kitty 호환성 ID를 더 이상 할당할 수 없습니다" }
        val next = previous + 1
        check(preferences.edit().putLong(KEY_LAST_ID, next).commit()) { "kitty 호환성 ID를 저장하지 못했습니다" }
        next.toString()
    }

    private companion object {
        val lock = Any()
        const val KEY_LAST_ID = "last-id"
    }
}
