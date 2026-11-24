package com.evowria.printerconnector

import android.util.Base64
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
        // This app prints the server's sticker images (GuestLabel.image);
        // the desk only sends stickers to apps that say so.
        put("canPrintSticker", true)
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

/** The event's paper (evowria constants/checkin-print.ts CHECKIN_PRINTER_PAPER). */
enum class PrinterPaper(val key: String) {
    RECEIPT_58("RECEIPT_58"),
    STICKER_50X30("STICKER_50X30");

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: RECEIPT_58
    }
}

/**
 * One sticker drawn by the server (evowria lib/checkin-sticker.tsx) as
 * printer dots: rows of width / 8 bytes, leftmost dot in the high bit,
 * 1 = black.
 */
class StickerImage(
    val width: Int,
    val height: Int,
    val dots: ByteArray,
    /** Dots to feed after the sticker; null = the printer's own "next label"
     *  (GS FF), which stops at the gap between stickers. */
    val feedDots: Int?,
) {
    val bytesPerRow get() = width / 8

    companion object {
        /** Null when the image is missing or doesn't add up: nothing printed. */
        fun fromJson(json: JSONObject?): StickerImage? {
            json ?: return null
            val width = json.optInt("width")
            val height = json.optInt("height")
            if (width <= 0 || width % 8 != 0 || width > EscPosRaster.PAPER_DOTS) return null
            if (height <= 0 || height > MAX_HEIGHT_DOTS) return null
            val dots = runCatching {
                Base64.decode(json.optString("raster"), Base64.DEFAULT)
            }.getOrNull() ?: return null
            if (dots.size != width / 8 * height) return null
            val feedDots = if (json.has("feedDots")) {
                json.optInt("feedDots").coerceIn(0, MAX_HEIGHT_DOTS)
            } else {
                null
            }
            return StickerImage(width, height, dots, feedDots)
        }

        // 10 cm: far more than a sticker; refuses a runaway image.
        private const val MAX_HEIGHT_DOTS = 800
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
    val paper: PrinterPaper = PrinterPaper.RECEIPT_58,
    /** Sticker paper: the whole print as one image (blocks are ignored). */
    val image: StickerImage? = null,
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
            paper = PrinterPaper.fromKey(json.textOrNull("paper")),
            image = StickerImage.fromJson(json.optJSONObject("image")),
        )

        // optString turns a JSON null into the text "null", which would be
        // printed on the label; a missing or empty value is null instead.
        private fun JSONObject.textOrNull(name: String): String? =
            if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() }
    }
}
