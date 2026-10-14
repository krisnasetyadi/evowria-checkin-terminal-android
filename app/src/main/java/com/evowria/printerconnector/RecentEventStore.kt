package com.evowria.printerconnector

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Events this device has opened before, so the picker can offer them as a
 * searchable list. Kept on the device on purpose: the server only answers an
 * exact event code (/api/checkin/event) and never lists couples.
 */
class RecentEventStore(context: Context) {
    data class RecentEvent(
        val terminalUrl: String,
        val code: String,
        val coupleNames: String,
        val dateText: String,
        val venue: String,
        val lastUsedAt: Long,
    )

    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    /** Most recently used first. */
    fun list(): List<RecentEvent> {
        val raw = preferences.getString(KEY_EVENTS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length())
                .mapNotNull { index -> array.optJSONObject(index)?.toRecentEvent() }
                .sortedByDescending { it.lastUsedAt }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun remember(event: RecentEvent) {
        val events = listOf(event) + list().filterNot { it.terminalUrl == event.terminalUrl }
        val array = JSONArray()
        events.take(MAX_EVENTS).forEach { array.put(it.toJson()) }
        preferences.edit().putString(KEY_EVENTS, array.toString()).apply()
    }

    private fun JSONObject.toRecentEvent(): RecentEvent? {
        val terminalUrl = optString("terminalUrl").takeIf { it.isNotBlank() } ?: return null
        val code = optString("code").takeIf { it.isNotBlank() } ?: return null
        return RecentEvent(
            terminalUrl = terminalUrl,
            code = code,
            coupleNames = optString("coupleNames").ifBlank { code },
            dateText = optString("dateText"),
            venue = optString("venue"),
            lastUsedAt = optLong("lastUsedAt"),
        )
    }

    private fun RecentEvent.toJson() = JSONObject()
        .put("terminalUrl", terminalUrl)
        .put("code", code)
        .put("coupleNames", coupleNames)
        .put("dateText", dateText)
        .put("venue", venue)
        .put("lastUsedAt", lastUsedAt)

    private companion object {
        const val PREFERENCES_NAME = "recent_events"
        const val KEY_EVENTS = "events"
        const val MAX_EVENTS = 20
    }
}
