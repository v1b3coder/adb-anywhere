/*
 * QrScanner - CameraX preview and ZXing QR decoding for the pairing key.
 */
package cz.slush.adbanywhere.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@Composable
fun QrScanner(
    modifier: Modifier = Modifier,
    validate: (String) -> Boolean = { true },
    onResult: (String) -> Unit,
    onInvalid: (String) -> Unit = {},
    onError: (String) -> Unit = {},
    onUsePaste: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        if (!it) onError("Camera permission is needed to scan the QR code. Paste the key instead.")
    }
    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(Manifest.permission.CAMERA)
    }

    if (!granted) {
        Column(
            modifier = modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Camera access is needed to scan the key.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) {
                    Text("Allow camera")
                }
                if (onUsePaste != null) {
                    TextButton(onClick = onUsePaste) { Text("Paste key instead") }
                }
            }
        }
        return
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val found = remember { AtomicBoolean(false) }
    val lastInvalid = remember { AtomicReference<String?>(null) }
    val providerRef = remember { mutableStateOf<ProcessCameraProvider?>(null) }
    val cameraRef = remember { AtomicReference<Camera?>(null) }
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            // SurfaceView is a separate window layer: Compose cannot clip it, so
            // inside a dialog it drew over the tabs and the text below.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    // PreviewView handles these gestures only with a CameraController; these use
    // cases are bound by hand, so zoom and focus are wired here.
    val zoomGesture = remember {
        ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val camera = cameraRef.get() ?: return false
                    val zoom = camera.cameraInfo.zoomState.value ?: return false
                    val ratio = (zoom.zoomRatio * detector.scaleFactor)
                        .coerceIn(zoom.minZoomRatio, zoom.maxZoomRatio)
                    camera.cameraControl.setZoomRatio(ratio)
                    return true
                }
            },
        )
    }

    // A dismissed dialog must not leave the camera bound to the activity.
    DisposableEffect(Unit) {
        previewView.setOnTouchListener { _, event ->
            zoomGesture.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                cameraRef.get()?.let { camera ->
                    val point = previewView.meteringPointFactory.createPoint(event.x, event.y)
                    runCatching {
                        camera.cameraControl.startFocusAndMetering(
                            FocusMeteringAction.Builder(point).build(),
                        )
                    }
                }
            }
            false
        }
        onDispose {
            previewView.setOnTouchListener(null)
            cameraRef.set(null)
            runCatching { providerRef.value?.unbindAll() }
            executor.shutdown()
        }
    }

    LaunchedEffect(Unit) {
        // The provider future is blocking; only the bind and the preview touch
        // the main thread.
        val provider = withContext(Dispatchers.IO) {
            ProcessCameraProvider.getInstance(context).get()
        }
        providerRef.value = provider
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            // The key's QR has ~97 modules; 720p left too few
                            // pixels per module for a screen.
                            Size(1920, 1080),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                        ),
                    )
                    .build(),
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(executor) { image ->
            if (found.get()) {
                image.close()
                return@setAnalyzer
            }
            try {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val data = ByteArray(buffer.remaining())
                buffer.get(data)
                val text = decodeQrFrame(
                    width = image.width,
                    height = image.height,
                    rowStride = plane.rowStride,
                    pixelStride = plane.pixelStride,
                    data = data,
                )
                if (text != null) {
                    if (validate(text)) {
                        if (found.compareAndSet(false, true)) {
                            mainHandler.post {
                                providerRef.value?.unbindAll()
                                onResult(text)
                            }
                        }
                    } else if (lastInvalid.getAndSet(text) != text) {
                        mainHandler.post { onInvalid(text) }
                    }
                }
            } finally {
                image.close()
            }
        }
        runCatching {
            provider.unbindAll()
            cameraRef.set(
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                ),
            )
        }.onFailure {
            onError("Camera could not start. Paste the key instead.")
        }
    }

    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(280.dp)
            .clip(shape)
            .background(Color.Black)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
    ) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.62f)
                .height(200.dp)
                .border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(24.dp)),
        )
    }
}

/**
 * Decodes one analysis frame's Y plane. CameraX reports the HAL row stride,
 * which is usually wider than the image; a planar source fed with the width
 * instead reads a sheared image and never finds a code. `qrencode -t ansiutf8`
 * on a dark terminal draws light modules on black, so inverted codes count too.
 */
internal fun decodeQrFrame(
    width: Int,
    height: Int,
    rowStride: Int,
    pixelStride: Int,
    data: ByteArray,
): String? {
    val luma = if (rowStride == width && pixelStride == 1) {
        data
    } else {
        ByteArray(width * height).also { out ->
            for (y in 0 until height) {
                var src = y * rowStride
                var dst = y * width
                repeat(width) {
                    if (src < data.size) out[dst] = data[src]
                    src += pixelStride
                    dst++
                }
            }
        }
    }
    val source = PlanarYUVLuminanceSource(luma, width, height, 0, 0, width, height, false)
    val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true,
                DecodeHintType.ALSO_INVERTED to true,
            ),
        )
    }
    return runCatching {
        reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
    }.getOrNull()
}
