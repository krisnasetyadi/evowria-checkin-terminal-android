package com.evowria.printerconnector

import android.content.res.Resources
import android.graphics.BitmapFactory
import android.graphics.Color

/**
 * The Evowria logo at the foot of the table card, as one ESC/POS raster
 * command (GS v 0). The image is a 1-bit PNG bundled with the app
 * (res/drawable-nodpi/evowria_logo_print.png, made by tools/make_print_logo.py),
 * so the printer gets it exactly as drawn: black wordmark, dithered leaves.
 */
object PrintLogo {
    // A 58 mm printer draws 384 dots a line. Many cheap printers ignore the
    // alignment for images, so the logo is centred with blank dots on its left.
    private const val PAPER_DOTS = 384

    /** Null when the image can't be read: the card prints without its logo. */
    fun rasterCommand(resources: Resources): ByteArray? {
        val options = BitmapFactory.Options().apply { inScaled = false }
        val bitmap = BitmapFactory.decodeResource(
            resources,
            R.drawable.evowria_logo_print,
            options,
        ) ?: return null
        val width = bitmap.width.coerceAtMost(PAPER_DOTS)
        val height = bitmap.height
        val leftDots = (PAPER_DOTS - width) / 2
        val bytesPerRow = (leftDots + width + 7) / 8
        val header = byteArrayOf(
            0x1D, 0x76, 0x30, 0x00,
            (bytesPerRow and 0xFF).toByte(), (bytesPerRow shr 8).toByte(),
            (height and 0xFF).toByte(), (height shr 8).toByte(),
        )
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
        bitmap.recycle()
        return header + dots
    }
}
