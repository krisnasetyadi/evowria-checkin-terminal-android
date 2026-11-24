package com.evowria.printerconnector

import android.graphics.Bitmap
import android.graphics.Color

/**
 * ESC/POS raster images ("GS v 0"): rows of width / 8 bytes, the leftmost dot
 * in the high bit, 1 = black. Used for the table card's logo, the server's
 * stickers and the sticker test print.
 */
object EscPosRaster {
    // A 58 mm printer draws 384 dots a line.
    const val PAPER_DOTS = 384

    /** The GS v 0 command for dots already packed as rows. */
    fun command(bytesPerRow: Int, height: Int, dots: ByteArray): ByteArray {
        val header = byteArrayOf(
            0x1D, 0x76, 0x30, 0x00,
            (bytesPerRow and 0xFF).toByte(), (bytesPerRow shr 8).toByte(),
            (height and 0xFF).toByte(), (height shr 8).toByte(),
        )
        return header + dots
    }

    /**
     * A bitmap as one GS v 0 command, centred on the paper with blank dots on
     * its left (many cheap printers ignore the alignment for images). A dot is
     * black where the pixel is opaque and darker than mid-grey.
     */
    fun fromBitmap(bitmap: Bitmap): ByteArray {
        val width = bitmap.width.coerceAtMost(PAPER_DOTS)
        val height = bitmap.height
        val leftDots = (PAPER_DOTS - width) / 2
        val bytesPerRow = (leftDots + width + 7) / 8
        val dots = ByteArray(bytesPerRow * height)
        val row = IntArray(width)
        for (y in 0 until height) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1)
            for (x in 0 until width) {
                val pixel = row[x]
                val isInk = Color.alpha(pixel) > 127 &&
                    (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / 3 < 128
                if (isInk) {
                    val dot = leftDots + x
                    val index = y * bytesPerRow + dot / 8
                    dots[index] = (dots[index].toInt() or (0x80 ushr (dot % 8))).toByte()
                }
            }
        }
        return command(bytesPerRow, height, dots)
    }
}
