package com.billo.ledger.ui.screens

import android.Manifest
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Spellcheck
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import com.billo.ledger.BillOApplication
import com.billo.ledger.R
import com.billo.ledger.data.db.EvidenceChannel
import com.billo.ledger.perception.ocr.SlipOcr
import com.billo.ledger.perception.sms.SmsInboxReader
import com.billo.ledger.perception.stt.NoiseMonitor
import com.billo.ledger.perception.stt.SttState
import com.billo.ledger.perception.stt.VoskRecognizer
import com.billo.ledger.ui.BillOCard
import com.billo.ledger.ui.BillOIconBadge
import com.billo.ledger.ui.CardLime
import com.billo.ledger.ui.CardMint
import com.billo.ledger.ui.CardSky
import com.billo.ledger.ui.ChipOn
import com.billo.ledger.ui.Forest
import com.billo.ledger.ui.Ink
import com.billo.ledger.ui.LimeAccent
import com.billo.ledger.ui.MutedText
import com.billo.ledger.ui.TealGreen
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
fun CaptureScreen(preset: String = "") {
    val context = LocalContext.current
    val app = context.applicationContext as BillOApplication
    val scope = rememberCoroutineScope()
    val defaultStatus = stringResource(R.string.capture_status)
    val savedOfflineNote = stringResource(R.string.saved_offline)
    var status by remember { mutableStateOf<String?>(null) }
    var editableText by remember { mutableStateOf("") }

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
    val listening = sttState is SttState.Listening
    var lastSavedVoice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(sttState) {
        val s = sttState
        if (s is SttState.Final && s.text != lastSavedVoice) {
            lastSavedVoice = s.text
            // Final speech results land in the editable text for review before saving.
            editableText = if (editableText.isBlank()) s.text else editableText.trimEnd() + " " + s.text
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

    // --- intent preset prefill (once per preset) ---------------------------
    val egSold = stringResource(R.string.intent_sold_eg)
    val egBought = stringResource(R.string.intent_bought_eg)
    val egOwe = stringResource(R.string.intent_owe_eg)
    LaunchedEffect(preset) {
        val eg = when (preset) {
            "sale" -> egSold
            "stock" -> egBought
            "people" -> egOwe
            else -> null
        }
        if (eg != null && editableText.isBlank()) editableText = eg
    }

    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val currentLang = AppCompatDelegate.getApplicationLocales()
        .toLanguageTags().ifBlank { "en" }.substringBefore('-')

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // ---------------- header ----------------
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { backDispatcher?.onBackPressed() }) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                    tint = Ink,
                )
            }
            Text(
                stringResource(R.string.what_happened),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Ink,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.Filled.Translate,
                contentDescription = null,
                tint = MutedText,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        status?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = TealGreen, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        } ?: Text(
            defaultStatus,
            style = MaterialTheme.typography.bodySmall,
            color = MutedText,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            stringResource(R.string.choose_speak),
            style = MaterialTheme.typography.bodyMedium,
            color = MutedText,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )

        // ---------------- language chips ----------------
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LanguageChip("हिंदी", currentLang == "hi") {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("hi"))
            }
            Spacer(Modifier.width(8.dp))
            LanguageChip("मराठी", currentLang == "mr") {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("mr"))
            }
            Spacer(Modifier.width(8.dp))
            LanguageChip("English", currentLang == "en") {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
            }
        }

        // ---------------- intent cards ----------------
        ActionTile(
            title = stringResource(R.string.intent_sold),
            subtitle = egSold,
            background = CardMint,
        ) { editableText = egSold }
        ActionTile(
            title = stringResource(R.string.intent_bought),
            subtitle = egBought,
            background = CardLime,
        ) { editableText = egBought }
        ActionTile(
            title = stringResource(R.string.intent_owe),
            subtitle = egOwe,
            background = CardSky,
        ) { editableText = egOwe }

        // ---------------- tap and speak ----------------
        BillOCard(background = CardLime, modifier = Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(R.drawable.ic_cat),
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                )
                Box(
                    Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.White)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(
                        stringResource(R.string.tap_and_speak),
                        color = Forest,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                Box(
                    Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(if (listening) LimeAccent else Forest)
                        .clickable {
                            if (listening) {
                                stt.stop()
                            } else if (micGranted) {
                                stt.start()
                            } else {
                                permLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Mic,
                        contentDescription = null,
                        tint = if (listening) Forest else Color.White,
                        modifier = Modifier.size(30.dp),
                    )
                }
                Waveform(listening)
                when (val s = sttState) {
                    is SttState.Listening -> Text(
                        s.partial.ifEmpty { stringResource(R.string.listening) },
                        style = MaterialTheme.typography.bodyMedium,
                        color = Forest,
                        textAlign = TextAlign.Center,
                    )
                    is SttState.Final -> Text(
                        stringResource(R.string.heard, s.text),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Forest,
                        textAlign = TextAlign.Center,
                    )
                    is SttState.Error -> Text(
                        stringResource(R.string.error_prefix, s.message),
                        color = Color(0xFFE57373),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                    else -> {}
                }
                Text(
                    stringResource(R.string.own_words),
                    style = MaterialTheme.typography.labelSmall,
                    color = MutedText,
                    textAlign = TextAlign.Center,
                )
            }
        }

        // ---------------- detected text ----------------
        BillOCard(background = Color.White, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.detected_text),
                    style = MaterialTheme.typography.labelMedium,
                    color = MutedText,
                )
                OutlinedTextField(
                    value = editableText,
                    onValueChange = { editableText = it },
                    placeholder = { Text(stringResource(R.string.detected_hint), color = MutedText) },
                    minLines = 2,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // ---------------- save entry ----------------
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(LimeAccent)
                .clickable {
                    val text = editableText.trim()
                    if (text.isNotEmpty()) {
                        scope.launch {
                            app.evidence.insert(EvidenceChannel.MANUAL, text, confidence = 0.95)
                            editableText = ""
                            status = savedOfflineNote
                        }
                    }
                }
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.save_entry),
                color = Forest,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = Forest,
            )
        }

        // ---------------- capability: ambient noise gate ----------------
        BillOCard(background = CardSky, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BillOIconBadge(Icons.Filled.Sensors, Forest, Color.White)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(R.string.noise_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Ink,
                    )
                }
                Text(
                    stringResource(R.string.noise_desc, NoiseMonitor.GATE_DBFS),
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedText,
                )
                if (noise.isRunning) {
                    OutlinedButton(onClick = { noise.stop() }) { Text(stringResource(R.string.stop_meter)) }
                } else {
                    Button(
                        onClick = {
                            if (micGranted) noise.start() else permLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Forest, contentColor = Color.White),
                    ) { Text(stringResource(R.string.start_meter)) }
                }
                level?.let { l ->
                    LinearProgressIndicator(
                        progress = { ((l.dbfs + 90) / 90f).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                        color = if (l.tooNoisy) Color(0xFFE57373) else Forest,
                    )
                    Text(
                        "${l.dbfs} dBFS — " + if (l.tooNoisy) stringResource(R.string.too_noisy) else stringResource(R.string.clear_enough),
                        style = MaterialTheme.typography.bodySmall,
                        color = MutedText,
                    )
                }
            }
        }

        // ---------------- capability: offline STT status ----------------
        BillOCard(background = Color.White, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BillOIconBadge(Icons.Filled.Spellcheck, Forest, CardMint)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(R.string.stt_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Ink,
                    )
                }
                when (val s = sttState) {
                    is SttState.ModelMissing -> {
                        Text(stringResource(R.string.model_missing), style = MaterialTheme.typography.bodySmall)
                        Text(stt.modelDir().absolutePath, style = MaterialTheme.typography.bodySmall, color = Forest)
                        Text(stringResource(R.string.model_missing_after), style = MaterialTheme.typography.bodySmall)
                    }
                    SttState.LoadingModel -> Text(
                        "loading model…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MutedText,
                    )
                    else -> {}
                }
            }
        }

        // ---------------- capability: OCR ----------------
        BillOCard(background = CardMint, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BillOIconBadge(Icons.Filled.PhotoLibrary, TealGreen, Color.White)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(R.string.ocr_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Ink,
                    )
                }
                Text(stringResource(R.string.ocr_desc), style = MaterialTheme.typography.bodySmall, color = MutedText)
                if (ocrBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                ocrText?.let { text ->
                    Text(text.take(400), style = MaterialTheme.typography.bodySmall, color = Ink)
                }
                Button(
                    onClick = {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Forest, contentColor = Color.White),
                ) { Text(stringResource(R.string.pick_photo)) }
            }
        }

        // ---------------- capability: camera capture ----------------
        BillOCard(background = Color.White, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BillOIconBadge(Icons.Filled.PhotoCamera, Forest, CardLime)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(R.string.camera_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Ink,
                    )
                }
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
                    Button(
                        onClick = { permLauncher.launch(arrayOf(Manifest.permission.CAMERA)) },
                        colors = ButtonDefaults.buttonColors(containerColor = Forest, contentColor = Color.White),
                    ) {
                        Text(stringResource(R.string.grant_camera))
                    }
                }
            }
        }

        // ---------------- capability: delayed SMS ----------------
        BillOCard(background = CardSky, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BillOIconBadge(Icons.Filled.Sms, Forest, Color.White)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(R.string.sms_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Ink,
                    )
                }
                Text(stringResource(R.string.sms_desc), style = MaterialTheme.typography.bodySmall, color = MutedText)
                if (smsGranted) {
                    var importing by remember { mutableStateOf(false) }
                    Button(
                        enabled = !importing,
                        colors = ButtonDefaults.buttonColors(containerColor = Forest, contentColor = Color.White),
                        onClick = {
                            scope.launch {
                                importing = true
                                val imported = SmsInboxReader.importRecent(context)
                                importing = false
                                status = if (imported >= 0) "Imported $imported SMS from inbox" else "SMS import failed ($imported)"
                            }
                        },
                    ) { Text(if (importing) stringResource(R.string.importing) else stringResource(R.string.import_sms)) }
                } else {
                    Button(
                        onClick = {
                            permLauncher.launch(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS))
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Forest, contentColor = Color.White),
                    ) { Text(stringResource(R.string.grant_sms)) }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun LanguageChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, color = if (selected) Forest else MutedText, fontWeight = FontWeight.SemiBold) },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Color.White,
            labelColor = MutedText,
            selectedContainerColor = ChipOn,
            selectedLabelColor = Forest,
        ),
        border = BorderStroke(1.dp, if (selected) Forest else Color(0xFFE0E8E2)),
    )
}

@Composable
private fun ActionTile(
    title: String,
    subtitle: String,
    background: Color,
    onClick: () -> Unit,
) {
    BillOCard(background = background, modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Ink)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MutedText)
            }
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = Forest,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun Waveform(listening: Boolean, modifier: Modifier = Modifier) {
    val heights = listOf(10.dp, 22.dp, 14.dp, 26.dp, 12.dp)
    val transition = rememberInfiniteTransition(label = "waveform")
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        heights.forEachIndexed { i, h ->
            val phase by transition.animateFloat(
                initialValue = 0.4f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 420 + i * 90, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "bar$i",
            )
            Box(
                Modifier
                    .width(5.dp)
                    .height(if (listening) h * phase else h)
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (listening) Forest else Forest.copy(alpha = 0.35f)),
            )
        }
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
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .clip(RoundedCornerShape(16.dp)),
    )
    Spacer(Modifier.height(6.dp))
    Button(
        onClick = {
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
        },
        colors = ButtonDefaults.buttonColors(containerColor = Forest, contentColor = Color.White),
    ) { Text(stringResource(R.string.capture_ocr)) }
}
