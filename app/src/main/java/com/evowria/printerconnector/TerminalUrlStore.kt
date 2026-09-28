package com.evowria.printerconnector

import android.content.Context

/** Stores only the terminal URL selected on this device. */
class TerminalUrlStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun getUrl(): String? = preferences.getString(KEY_TERMINAL_URL, null)

    fun saveUrl(url: String) {
        preferences.edit().putString(KEY_TERMINAL_URL, url).apply()
    }

    companion object {
        private const val PREFERENCES_NAME = "terminal_settings"
        private const val KEY_TERMINAL_URL = "terminal_url"
    }
}
