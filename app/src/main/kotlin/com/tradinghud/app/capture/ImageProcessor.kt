package com.tradinghud.app.capture

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

object ImageProcessor {

    private const val MAX_DIMENSION = 1024
    private const val JPEG_QUALITY = 70

    /**
     * Scales [bitmap] so its longest side is at most [MAX_DIMENSION], encodes
     * as JPEG at [JPEG_QUALITY], recycles the source bitmap, and returns the
     * compressed bytes. The source bitmap must not be recycled by the caller.
     */
    fun process(bitmap: Bitmap): ByteArray {
        val scaled = scale(bitmap)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()
        return out.toByteArray()
    }

    private fun scale(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val max = maxOf(w, h)
        if (max <= MAX_DIMENSION) return bitmap

        val scale = MAX_DIMENSION.toFloat() / max
        val newW = (w * scale).toInt()
        val newH = (h * scale).toInt()
        return Bitmap.createScaledBitmap(bitmap, newW, newH, true)
    }
}
