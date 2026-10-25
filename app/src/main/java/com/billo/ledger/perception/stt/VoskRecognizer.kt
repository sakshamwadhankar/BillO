package com.billo.ledger.perception.stt

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File

sealed interface SttState {
    data object Idle : SttState
    data object LoadingModel : SttState
    data class Listening(val partial: String) : SttState
    data class Final(val text: String) : SttState
    data object ModelMissing : SttState
    data class Error(val message: String) : SttState
}

/**
 * Fully on-device Hindi speech recognition via Vosk (Kaldi).
 * No network calls — the acoustic model is loaded from app-private
 * storage. One-time setup (outside the app): download
 * `vosk-model-small-hi-0.22` from alphacephei.com/vosk/models and
 * extract it into the directory reported by [modelDir].
 */
class VoskRecognizer(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow<SttState>(SttState.Idle)
    val state: StateFlow<SttState> = _state.asStateFlow()

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var speechService: SpeechService? = null

    private val listener = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) {
            val partial = JSONObject(hypothesis ?: return).optString("partial", "")
            if (partial.isNotEmpty()) _state.value = SttState.Listening(partial)
        }

        override fun onResult(hypothesis: String?) {
            emitFinal(hypothesis)
        }

        override fun onFinalResult(hypothesis: String?) {
            emitFinal(hypothesis)
        }

        override fun onError(exception: Exception?) {
            _state.value = SttState.Error(exception?.message ?: "STT error")
        }

        override fun onTimeout() {
            _state.value = SttState.Idle
        }

        private fun emitFinal(hypothesis: String?) {
            val text = JSONObject(hypothesis ?: return).optString("text", "")
            if (text.isNotEmpty()) _state.value = SttState.Final(text)
        }
    }

    fun modelDir(): File = File(context.getExternalFilesDir(null), "model-hi")

    fun modelPresent(): Boolean {
        val dir = modelDir()
        return dir.isDirectory && File(dir, "am").exists() && File(dir, "conf").exists()
    }

    fun start() {
        if (!modelPresent()) {
            _state.value = SttState.ModelMissing
            return
        }
        scope.launch {
            try {
                _state.value = SttState.LoadingModel
                withContext(Dispatchers.IO) {
                    val m = model ?: Model(modelDir().absolutePath).also { model = it }
                    val rec = recognizer ?: Recognizer(m, 16_000.0f).also { recognizer = it }
                    withContext(Dispatchers.Main) {
                        val service = speechService ?: SpeechService(rec, 16_000.0f).also {
                            speechService = it
                        }
                        if (!service.startListening(listener)) {
                            // a previous session may still own the thread; reset and retry once
                            service.reset()
                            service.startListening(listener)
                        }
                        _state.value = SttState.Listening("")
                    }
                }
            } catch (e: Exception) {
                Log.e("VoskRecognizer", "start failed", e)
                _state.value = SttState.Error(e.message ?: "failed to start STT")
            }
        }
    }

    fun stop() {
        try {
            speechService?.stop()
        } catch (e: Exception) {
            Log.w("VoskRecognizer", "stop failed", e)
        }
        if (_state.value is SttState.Listening) _state.value = SttState.Idle
    }
}
