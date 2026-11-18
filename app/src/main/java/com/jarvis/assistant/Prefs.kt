package com.jarvis.assistant

import android.content.Context

class Prefs(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    // Returns the default when the stored value is missing or blank.
    fun s(k: String, d: String = ""): String = p.getString(k, d)?.takeIf { it.isNotBlank() } ?: d

    fun put(k: String, v: String) {
        p.edit().putString(k, v).apply()
    }

    fun b(k: String, d: Boolean): Boolean = p.getBoolean(k, d)

    fun putB(k: String, v: Boolean) {
        p.edit().putBoolean(k, v).apply()
    }
}
