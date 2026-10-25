package com.billo.ledger.ui.screens

import android.Manifest
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.billo.ledger.BillOApplication
import com.billo.ledger.data.db.EvidenceChannel
import com.billo.ledger.perception.ocr.SlipOcr
import com.billo.ledger.perception.sms.SmsInboxReader
import com.billo.ledger.perception.stt.NoiseMonitor
import com.billo.ledger.perception.stt.SttState
import com.billo.ledger.perception.stt.VoskRecognizer
import kotlinx.coroutines.launch
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

private suspend fun Context.cameraProvider(): ProcessCameraProvider =
    suspendCoroutine { cont ->
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener(
            {
                try {
                    cont.resume(future.get())
                } catch (e: Exception) {
                    cont.resumeWithException(e)
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

@Composable
fun CaptureScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as BillOApplication
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("All processing is on-device — no internet permission exists in this app.") }

    var micGranted by remember { mutableStateOf(false) }
    var camGranted by remember { mutableStateOf(false) }
    var smsGranted by remember { mutableStateOf(false) }

    fun refreshPermissions() {
        micGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        camGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        smsGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    LaunchedEffect(Unit) { refreshPermissions() }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { refreshPermissions() }

    // --- channel 1: ambient noise gate ------------------------------------
    val noise = remember { NoiseMonitor() }
    val level by noise.level.collectAsState()

    // --- channel 2: offline Hindi STT --------------------------------------
    val stt = remember { VoskRecognizer(context) }
    val sttState by stt.state.collectAsState()
    var lastSavedVoice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(sttState) {
        val s = sttState
        if (s is SttState.Final && s.text != lastSavedVoice) {
            lastSavedVoice = s.text
            app.evidence.insert(EvidenceChannel.VOICE, s.text)
            status = "Voice evidence saved: \"${s.text.take(60)}\""
        }
    }

    // --- channel 3: OCR of slips and tallies --------------------------------
    var ocrText by remember { mutableStateOf<String?>(null) }
    var ocrBusy by remember { mutableStateOf(false) }
    var lastSavedOcr by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(ocrText) {
        val text = ocrText
        if (text != null && text != lastSavedOcr) {
            lastSavedOcr = text
            app.evidence.insert(EvidenceChannel.OCR, text)
            status = "OCR evidence saved (${text.length} chars)"
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                ocrBusy = true
                ocrText = SlipOcr.read(context, uri)
                ocrBusy = false
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            noise.stop()
            stt.stop()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Capture evidence", style = MaterialTheme.typography.headlineSmall)
        Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)

        // ---------------- noise gate ----------------
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Ambient noise gate", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Weights voice evidence: at ${NoiseMonitor.GATE_DBFS} dBFS and above, " +
                        "speech is considered drowned by traffic (the 85dB case).",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (noise.isRunning) {
                        OutlinedButton(onClick = { noise.stop() }) { Text("Stop meter") }
                    } else {
                        Button(onClick = {
                            if (micGranted) noise.start() else permLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                        }) { Text("Start meter") }
                    }
                }
                level?.let { l ->
                    LinearProgressIndicator(
                        progress = { ((l.dbfs + 90) / 90f).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                        color = if (l.tooNoisy) Color(0xFFE57373) else MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "${l.dbfs} dBFS — " + if (l.tooNoisy) "TOO NOISY: voice evidence will be down-weighted" else "clear enough for speech",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        // ---------------- offline STT ----------------
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Offline Hindi speech (Vosk, on-device)", style = MaterialTheme.typography.titleSmall)
                when (val s = sttState) {
                    is SttState.Listening -> {
                        Text("listening…", style = MaterialTheme.typography.labelMedium)
                        Text(s.partial.ifEmpty { "…" }, style = MaterialTheme.typography.bodyMedium)
                    }
                    is SttState.Final -> Text("heard: \"${s.text}\"", style = MaterialTheme.typography.bodyMedium)
                    is SttState.Error -> Text("error: ${s.message}", color = Color(0xFFE57373), style = MaterialTheme.typography.bodySmall)
                    is SttState.ModelMissing -> {
                        Text(
                            "Vosk model not installed. One-time online setup: download " +
                                "vosk-model-small-hi-0.22 from alphacephei.com/vosk/models on a " +
                                "connected machine and extract it to:",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(stt.modelDir().absolutePath, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        Text(
                            "After that, recognition is permanently offline.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    SttState.LoadingModel -> Text("loading model…", style = MaterialTheme.typography.bodySmall)
                    SttState.Idle -> {}
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (sttState is SttState.Listening) {
                        OutlinedButton(onClick = { stt.stop() }) { Text("Stop listening") }
                    } else {
                        Button(onClick = { stt.start() }) { Text("Start listening") }
                    }
                }
            }
        }

        // ---------------- OCR ----------------
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Slip / tally OCR (ML Kit, on-device)", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Grayscale + contrast-stretch preprocessing for crumpled, oil-stained paper; " +
                        "Latin + Devanagari recognizers run together.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (ocrBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                ocrText?.let { text ->
                    Text(text.take(400), style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) { Text("Pick slip photo") }
                }
            }
        }

        // ---------------- camera capture ----------------
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Camera capture (handwritten tallies)", style = MaterialTheme.typography.titleSmall)
                if (camGranted) {
                    CameraSection(
                        onCaptured = { uri ->
                            scope.launch {
                                ocrBusy = true
                                ocrText = SlipOcr.read(context, uri)
                                ocrBusy = false
                            }
                        },
                        onError = { status = "Capture failed: $it" },
                    )
                } else {
                    Button(onClick = { permLauncher.launch(arrayOf(Manifest.permission.CAMERA)) }) {
                        Text("Grant camera access")
                    }
                }
            }
        }

        // ---------------- SMS ----------------
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Delayed SMS chimes", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Every incoming SMS is stored passively by the receiver; " +
                        "inbox import backfills anything missed while the app was closed.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (smsGranted) {
                    var importing by remember { mutableStateOf(false) }
                    Button(
                        enabled = !importing,
                        onClick = {
                            scope.launch {
                                importing = true
                                val imported = SmsInboxReader.importRecent(context)
                                importing = false
                                status = if (imported >= 0) "Imported $imported SMS from inbox" else "SMS import failed ($imported)"
                            }
                        },
                    ) { Text(if (importing) "Importing…" else "Import recent inbox SMS") }
                } else {
                    Button(onClick = {
                        permLauncher.launch(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS))
                    }) { Text("Grant SMS access") }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun CameraSection(onCaptured: (Uri) -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }
    val previewView = remember { PreviewView(context) }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    LaunchedEffect(Unit) {
        try {
            val p = context.cameraProvider()
            provider = p
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            p.unbindAll()
            p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
        } catch (e: Exception) {
            onError("camera init: ${e.message}")
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = Modifier.fillMaxWidth().height(200.dp),
    )
    Spacer(Modifier.height(6.dp))
    Button(onClick = {
        val file = File(context.cacheDir, "slip_${System.currentTimeMillis()}.jpg")
        imageCapture.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                    val uri = results.savedUri ?: Uri.fromFile(file)
                    onCaptured(uri)
                }

                override fun onError(exception: ImageCaptureException) {
                    onError(exception.message ?: "unknown")
                }
            },
        )
    }) { Text("Capture & OCR") }
}
