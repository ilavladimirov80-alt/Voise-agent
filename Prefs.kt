package com.example.voiceagent

import android.content.Context

/** Настройки приложения. Хранятся в приватном хранилище приложения. */
class Prefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("voiceagent", Context.MODE_PRIVATE)

    var apiKey: String
        get() = sp.getString("api_key", "") ?: ""
        set(v) = sp.edit().putString("api_key", v.trim()).apply()

    var model: String
        get() = sp.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(v) = sp.edit().putString("model", v.trim().ifEmpty { DEFAULT_MODEL }).apply()

    /** Серверный инструмент веб-поиска API. По умолчанию выключен. */
    var webSearch: Boolean
        get() = sp.getBoolean("web_search", false)
        set(v) = sp.edit().putBoolean("web_search", v).apply()

    companion object {
        const val DEFAULT_MODEL = "claude-sonnet-5-5"
    }
}
