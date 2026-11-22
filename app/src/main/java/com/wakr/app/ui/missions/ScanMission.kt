package com.wakr.app.ui.missions

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors

@Composable
internal fun ScanMission(onUnavailable: (() -> Unit)? = null, onScan: (String) -> Unit) {
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
