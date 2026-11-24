package com.evowria.printerconnector

import android.content.res.Resources
import android.graphics.BitmapFactory

/**
 * The Evowria logo at the foot of the table card, as one ESC/POS raster
 * command (GS v 0). The image is a 1-bit PNG bundled with the app
 * (res/drawable-nodpi/evowria_logo_print.png, made by tools/make_print_logo.py),
 * so the printer gets it exactly as drawn: black wordmark, dithered leaves.
 */
object PrintLogo {
    /** Null when the image can't be read: the card prints without its logo. */
    fun rasterCommand(resources: Resources): ByteArray? {
        val options = BitmapFactory.Options().apply { inScaled = false }
        val bitmap = BitmapFactory.decodeResource(
            resources,
            R.drawable.evowria_logo_print,
            options,
        ) ?: return null
        return try {
            EscPosRaster.fromBitmap(bitmap)
        } finally {
            bitmap.recycle()
        }
    }
}
