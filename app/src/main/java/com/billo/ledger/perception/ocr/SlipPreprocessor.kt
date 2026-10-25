package com.billo.ledger.perception.ocr

import android.graphics.Bitmap
import android.graphics.Color

/**
 * Image enhancement for crumpled, oil-stained thermal/wholesale slips:
 * downscale to OCR-friendly size, grayscale, and min-max contrast
 * stretch so faded ink and grease shadows separate again.
 */
object SlipPreprocessor {

    private const val MAX_DIMENSION = 1600

    fun enhance(src: Bitmap): Bitmap {
        val scale = minOf(1f, MAX_DIMENSION.toFloat() / maxOf(src.width, src.height))
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                src,
                (src.width * scale).toInt().coerceAtLeast(1),
                (src.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            src
        }

        val w = scaled.width
        val h = scaled.height
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)

        val gray = IntArray(pixels.size)
        var min = 255
        var max = 0
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = Color.red(p)
            val g = Color.green(p)
            val b = Color.blue(p)
            // luma weights; yellowed grease stains read as bright background
            val luma = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
            gray[i] = luma
            if (luma < min) min = luma
            if (luma > max) max = luma
        }

        val range = (max - min).coerceAtLeast(1)
        val out = IntArray(pixels.size)
        for (i in gray.indices) {
            val stretched = ((gray[i] - min) * 255 / range).coerceIn(0, 255)
            out[i] = Color.rgb(stretched, stretched, stretched)
        }

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
    }

    /** Decode a content URI at a sane sample size to avoid OOM on big camera photos. */
    fun decodeSampled(resolver: android.content.ContentResolver, uri: android.net.Uri): Bitmap? {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
            ?: return null
        var sample = 1
        while (bounds.outWidth / sample > MAX_DIMENSION * 2 || bounds.outHeight / sample > MAX_DIMENSION * 2) {
            sample *= 2
        }
        val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        return resolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, options)
        }
    }
}
