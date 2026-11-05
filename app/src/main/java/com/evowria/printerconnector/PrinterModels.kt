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

/**
 * One line (or a blank, a divider, the logo) of a print laid out by the web
 * server (evowria lib/checkin-print-layout.ts). Texts arrive already wrapped
 * to the paper, so the printer only draws them.
 */
sealed class PrintBlock {
    data class Text(
        val text: String,
        val size: PrintTextSize,
        val bold: Boolean,
        val align: PrintAlign,
    ) : PrintBlock()

    /** Two texts on one line, one at each edge. */
    data class Row(
        val left: String,
        val leftSize: PrintTextSize,
        val right: String,
        val rightSize: PrintTextSize,
        val bold: Boolean,
    ) : PrintBlock()

    data class Divider(val isSolid: Boolean) : PrintBlock()

    /** The line the usher cuts along between two parts. */
    data class Cut(val text: String) : PrintBlock()

    data class Feed(val lines: Int) : PrintBlock()

    data object Logo : PrintBlock()

    companion object {
        /** Null for a block this app doesn't know (a newer server): skipped. */
        fun fromJson(json: JSONObject): PrintBlock? = when (json.optString("type")) {
            "text" -> Text(
                text = json.optString("text"),
                size = PrintTextSize.fromKey(json.optString("size")),
                bold = json.optBoolean("bold"),
                align = PrintAlign.fromKey(json.optString("align")),
            )
            "row" -> Row(
                left = json.optString("left"),
                leftSize = PrintTextSize.fromKey(json.optString("leftSize")),
                right = json.optString("right"),
                rightSize = PrintTextSize.fromKey(json.optString("rightSize")),
                bold = json.optBoolean("bold"),
            )
            "divider" -> Divider(isSolid = json.optString("style") == "solid")
            "cut" -> Cut(json.optString("text").ifBlank { DEFAULT_CUT_TEXT })
            "feed" -> Feed(json.optInt("lines", 1).coerceIn(0, MAX_FEED_LINES))
            "logo" -> Logo
            else -> null
        }

        private const val DEFAULT_CUT_TEXT = "8< - - - GUNTING DI SINI - - - -"
        private const val MAX_FEED_LINES = 20
    }
}

/** Character size, in normal characters (width × height). */
enum class PrintTextSize(val key: String, val width: Int, val height: Int) {
    NORMAL("normal", 1, 1),
    TALL("tall", 1, 2),
    LARGE("large", 2, 2),
    XL("xl", 3, 3),
    XXL("xxl", 4, 4);

    companion object {
        fun fromKey(key: String) = entries.firstOrNull { it.key == key } ?: NORMAL
    }
}

enum class PrintAlign(val key: String) {
    LEFT("left"),
    CENTER("center"),
    RIGHT("right");

    companion object {
        fun fromKey(key: String) = entries.firstOrNull { it.key == key } ?: CENTER
    }
}

data class GuestLabel(
    val guestCode: String,
    val guestName: String,
    val side: String?,
    val category: String?,
    val pax: Int,
    val table: String? = null,
    /** The print laid out by the server; empty from an older server, which
     *  gets the old gift label drawn from the fields above. */
    val blocks: List<PrintBlock> = emptyList(),
) {
    companion object {
        fun fromJson(json: JSONObject): GuestLabel = GuestLabel(
            guestCode = json.optString("guestCode").ifBlank { "TANPA-KODE" },
            guestName = json.optString("guestName").ifBlank { "Tamu undangan" },
            side = json.textOrNull("side"),
            category = json.textOrNull("category"),
            pax = json.optInt("pax", 1).coerceAtLeast(1),
            table = json.textOrNull("table"),
            blocks = json.optJSONArray("blocks")?.let { array ->
                (0 until array.length()).mapNotNull { index ->
                    array.optJSONObject(index)?.let(PrintBlock::fromJson)
                }
            } ?: emptyList(),
        )

        // optString turns a JSON null into the text "null", which would be
        // printed on the label; a missing or empty value is null instead.
        private fun JSONObject.textOrNull(name: String): String? =
            if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() }
    }
}
