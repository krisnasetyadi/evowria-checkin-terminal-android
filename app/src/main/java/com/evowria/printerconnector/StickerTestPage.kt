package com.evowria.printerconnector

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface

/**
 * "Tes cetak" on sticker paper: one sticker with a frame just inside its
 * edges, so the usher sees at once whether the print sits on the sticker
 * (frame whole, gap between stickers clear) before the first guest.
 */
object StickerTestPage {
    // One 50 × 30 mm sticker: 48 mm printed (384 dots) by 30 mm (240 dots).
    private const val WIDTH = EscPosRaster.PAPER_DOTS
    private const val HEIGHT = 240
    private const val INSET = 10f
    private const val STROKE = 4f

    fun rasterCommand(): ByteArray {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val frame = Paint().apply {
                color = Color.BLACK
                style = Paint.Style.STROKE
                strokeWidth = STROKE
            }
            canvas.drawRect(INSET, INSET, WIDTH - INSET, HEIGHT - INSET, frame)
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textAlign = Paint.Align.CENTER
                typeface = Typeface.DEFAULT_BOLD
            }
            val centre = WIDTH / 2f
            text.textSize = 36f
            canvas.drawText("TES STIKER", centre, 92f, text)
            text.textSize = 24f
            canvas.drawText("50 x 30 mm", centre, 128f, text)
            text.textSize = 18f
            canvas.drawText("Bingkai harus utuh di dalam stiker", centre, 168f, text)
            text.textSize = 16f
            text.letterSpacing = 0.15f
            canvas.drawText("EVOWRIA", centre, 208f, text)
            return EscPosRaster.fromBitmap(bitmap)
        } finally {
            bitmap.recycle()
        }
    }
}
