package com.evowria.printerconnector

import org.json.JSONObject

enum class PrintJobStatus {
    PRINTED,
    FAILED,
    UNKNOWN,
}

data class PrinterStatus(
    val available: Boolean,
    val connected: Boolean,
    val printerName: String? = null,
    val detail: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("available", available)
        put("connected", connected)
        printerName?.let { put("printerName", it) }
        detail?.let { put("detail", it) }
    }
}

data class PrintResult(
    val status: PrintJobStatus,
    val error: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("status", status.name)
        error?.let { put("error", it) }
    }
}

data class GuestLabel(
    val guestCode: String,
    val guestName: String,
    val side: String?,
    val category: String?,
    val pax: Int,
) {
    companion object {
        fun fromJson(json: JSONObject): GuestLabel = GuestLabel(
            guestCode = json.optString("guestCode").ifBlank { "TANPA-KODE" },
            guestName = json.optString("guestName").ifBlank { "Tamu undangan" },
            side = json.optString("side").takeIf { it.isNotBlank() },
            category = json.optString("category").takeIf { it.isNotBlank() },
            pax = json.optInt("pax", 1).coerceAtLeast(1),
        )
    }
}
