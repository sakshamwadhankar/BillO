package com.billo.ledger.perception.ocr

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.tasks.await

/**
 * On-device OCR for wholesale slips and handwritten tallies.
 * ML Kit's bundled recognizers run entirely offline (no Play Services
 * call): Latin for amounts/digits, Devanagari for Hindi text lines.
 * Both run and the richer reading wins; ties are concatenated so the
 * fusion engine (Phase 3) can weigh partial evidence.
 */
object SlipOcr {

    private val latinClient: TextRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
    private val devaClient: TextRecognizer by lazy {
        TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
    }

    suspend fun read(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        val raw = SlipPreprocessor.decodeSampled(context.contentResolver, uri) ?: return@withContext null
        readBitmap(raw)
    }

    suspend fun readBitmap(raw: Bitmap): String? {
        val enhanced = SlipPreprocessor.enhance(raw)
        return coroutineScope {
            val latinText = async { runCatching { latinClient.process(InputImage.fromBitmap(enhanced, 0)).await().text }.getOrNull() }
            val devaText = async { runCatching { devaClient.process(InputImage.fromBitmap(enhanced, 0)).await().text }.getOrNull() }
            merge(latinText.await(), devaText.await())
        }
    }

    private fun merge(latin: String?, devanagari: String?): String? {
        val l = latin?.trim().orEmpty()
        val d = devanagari?.trim().orEmpty()
        return when {
            l.isEmpty() && d.isEmpty() -> null
            l.isEmpty() -> d
            d.isEmpty() -> l
            else -> if (d.length > l.length) "$d\n---\n$l" else "$l\n---\n$d"
        }
    }
}
