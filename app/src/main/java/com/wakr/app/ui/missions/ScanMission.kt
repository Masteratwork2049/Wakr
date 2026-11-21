package com.wakr.app.ui.missions

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Nfc
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.wakr.app.core.MissionType
import java.util.Locale
import java.util.concurrent.Executors

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

@Composable
internal fun ScanMission(type: MissionType, onUnavailable: (() -> Unit)? = null, onScan: (String) -> Unit) {
    if (type == MissionType.NFC) ScanNfc(onUnavailable, onScan) else ScanBarcode(onUnavailable, onScan)
}

@Composable
private fun ScanNfc(onUnavailable: (() -> Unit)?, onScan: (String) -> Unit) {
    val context = LocalContext.current
    val activity = context.activity()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val adapter = remember(context) { NfcAdapter.getDefaultAdapter(context) }
    val result by rememberUpdatedState(onScan)
    var enabled by remember { mutableStateOf(adapter?.isEnabled == true) }
    if (adapter == null || activity == null) {
        MissionScannerPrompt(Icons.Outlined.Nfc, "NFC is unavailable on this device.")
        ScannerFallback(onUnavailable)
        return
    }
    DisposableEffect(adapter, activity, lifecycle) {
        fun start() {
            enabled = adapter.isEnabled
            if (!enabled) return
            adapter.enableReaderMode(activity, { tag ->
                val id = tag.id.joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
                activity.runOnUiThread { if (id.isNotEmpty()) result(id) }
            }, NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or
                NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_NFC_BARCODE or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, Bundle())
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> start()
                Lifecycle.Event.ON_PAUSE -> adapter.disableReaderMode(activity)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            runCatching { adapter.disableReaderMode(activity) }
        }
    }
    MissionScannerPrompt(Icons.Outlined.Nfc,
        if (enabled) "Hold the phone near the NFC tag." else "Turn on NFC in Android Settings.")
    if (!enabled) ScannerFallback(onUnavailable)
}

@Composable
private fun ScanBarcode(onUnavailable: (() -> Unit)?, onScan: (String) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val main = remember(context) { ContextCompat.getMainExecutor(context) }
    val result by rememberUpdatedState(onScan)
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    if (!granted) {
        MissionScannerPrompt(Icons.Outlined.QrCodeScanner, "Camera access is required to scan a code.")
        FilledTonalButton(onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text("Allow camera access") }
        ScannerFallback(onUnavailable)
        return
    }
    val view = remember(context) { PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }
    var error by remember { mutableStateOf(false) }
    DisposableEffect(view, owner) {
        val future = ProcessCameraProvider.getInstance(context)
        val worker = Executors.newSingleThreadExecutor()
        val preview = Preview.Builder().build().apply { surfaceProvider = view.surfaceProvider }
        val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
        val decoder = MultiFormatReader()
        var disposed = false
        val last = java.util.concurrent.atomic.AtomicReference<String>()
        analysis.setAnalyzer(worker) { image ->
            try {
                val plane = image.planes.first()
                val buffer = plane.buffer
                val data = ByteArray(image.width * image.height)
                for (row in 0 until image.height) {
                    buffer.position(row * plane.rowStride)
                    buffer.get(data, row * image.width, image.width)
                }
                fun decode(pixels: ByteArray, width: Int, height: Int): String? {
                    val source = PlanarYUVLuminanceSource(pixels, width, height, 0, 0, width, height, false)
                    return try { decoder.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text }
                    catch (_: com.google.zxing.ReaderException) { null }
                    finally { decoder.reset() }
                }
                val match = decode(data, image.width, image.height) ?: run {
                    val rotated = ByteArray(data.size)
                    for (row in 0 until image.height) for (col in 0 until image.width) {
                        rotated[col * image.height + image.height - row - 1] = data[row * image.width + col]
                    }
                    decode(rotated, image.height, image.width)
                }
                if (!match.isNullOrEmpty() && last.getAndSet(match) != match) main.execute { if (!disposed) result(match) }
            } catch (_: Exception) {
                // A truncated frame is dropped. The analyzer continues with the next frame.
            } finally {
                image.close()
            }
        }
        future.addListener({
            if (!disposed) error = runCatching {
                val provider = future.get()
                val selector = if (provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) CameraSelector.DEFAULT_BACK_CAMERA
                    else CameraSelector.DEFAULT_FRONT_CAMERA
                provider.bindToLifecycle(owner, selector, preview, analysis)
            }.isFailure
        }, main)
        onDispose {
            disposed = true
            analysis.clearAnalyzer()
            if (future.isDone) runCatching { future.get().unbind(preview, analysis) }
            worker.shutdown()
        }
    }
    if (error) {
        MissionScannerPrompt(Icons.Outlined.QrCodeScanner,
            "The camera could not start. Check whether another app is using it.")
        ScannerFallback(onUnavailable)
    } else {
        AndroidView(factory = { view }, modifier = Modifier.fillMaxWidth().missionSpace(220.dp))
        Text("Center the code in the camera preview.")
    }
}

@Composable
internal fun MissionScannerPrompt(icon: ImageVector, instruction: String) {
    Surface(
        modifier = Modifier.fillMaxWidth().missionSpace(120.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
            Text(instruction, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun ScannerFallback(onUnavailable: (() -> Unit)?) {
    if (onUnavailable != null) TextButton(onClick = onUnavailable) { Text("Solve a math challenge") }
}
