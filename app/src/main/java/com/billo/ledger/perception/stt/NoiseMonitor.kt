package com.billo.ledger.perception.stt

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Ambient noise gate for the 85dB-traffic case. Measures input level
 * (dBFS) from the mic; the fusion engine (Phase 3) uses it to weight
 * voice evidence and to decide when NOT to trust a transcription.
 */
class NoiseMonitor {

    data class Level(val dbfs: Int, val tooNoisy: Boolean)

    companion object {
        private const val SAMPLE_RATE = 16_000
        /** Above this level, ambient noise is assumed to drown speech. */
        const val GATE_DBFS = -22
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var record: AudioRecord? = null

    private val _level = MutableStateFlow<Level?>(null)
    val level: StateFlow<Level?> = _level.asStateFlow()

    val isRunning: Boolean get() = job?.isActive == true

    @SuppressLint("MissingPermission") // caller checks RECORD_AUDIO
    fun start() {
        if (isRunning) return
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, SAMPLE_RATE),
        )
        record = recorder
        recorder.startRecording()
        job = scope.launch {
            val buf = ShortArray(SAMPLE_RATE / 10) // 100 ms windows
            while (isActive) {
                val read = recorder.read(buf, 0, buf.size)
                if (read > 0) {
                    var sum = 0.0
                    for (i in 0 until read) {
                        val s = buf[i].toDouble()
                        sum += s * s
                    }
                    val rms = sqrt(sum / read)
                    val dbfs = if (rms < 1.0) -90 else (20 * log10(rms / 32768.0)).toInt().coerceIn(-90, 0)
                    _level.value = Level(dbfs, dbfs > GATE_DBFS)
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        try {
            record?.stop()
            record?.release()
        } catch (_: Exception) {
        }
        record = null
        _level.value = null
    }
}
