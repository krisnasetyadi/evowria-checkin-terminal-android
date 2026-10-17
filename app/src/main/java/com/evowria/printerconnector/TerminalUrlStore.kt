package com.evowria.printerconnector

import android.content.Context

/** Stores only the terminal URL selected on this device (and, in dev
 *  builds, the local server it points at). */
class TerminalUrlStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun getUrl(): String? = preferences.getString(KEY_TERMINAL_URL, null)

    fun saveUrl(url: String) {
        preferences.edit().putString(KEY_TERMINAL_URL, url).apply()
    }

    /** Dev builds only: the local server typed in the event picker. */
    fun getServerUrl(): String =
        preferences.getString(KEY_SERVER_URL, null) ?: BuildConfig.DEFAULT_SERVER_URL

    fun saveServerUrl(url: String) {
        preferences.edit().putString(KEY_SERVER_URL, url).apply()
    }

    companion object {
        private const val PREFERENCES_NAME = "terminal_settings"
        private const val KEY_TERMINAL_URL = "terminal_url"
        private const val KEY_SERVER_URL = "server_url"
    }
}
