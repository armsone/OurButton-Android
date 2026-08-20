package com.armsone.button.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** CameraX + ZXing scanner kept entirely out of release fixture state. */
@Composable
fun QrScannerPreview(onCode: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var permissionResolved by remember { mutableStateOf(granted) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        permissionResolved = true
    }
    LaunchedEffect(Unit) {
        if (!granted) permission.launch(Manifest.permission.CAMERA)
    }

    if (!granted) {
        Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
            if (permissionResolved) {
                Text("설정 앱에서 카메라 접근을 허용해 주세요.", color = Color.White)
            }
        }
        return
    }

    val executor = remember { Executors.newSingleThreadExecutor() }
    val delivered = remember { AtomicBoolean(false) }
    val reader = remember { MultiFormatReader() }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            cameraProvider?.unbindAll()
            executor.shutdownNow()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            PreviewView(viewContext).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                val previewView = this
                val future = ProcessCameraProvider.getInstance(viewContext)
                future.addListener({
                    val provider = runCatching { future.get() }.getOrNull() ?: return@addListener
                    cameraProvider = provider
                    val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(executor) { image ->
                        try {
                            if (delivered.get()) return@setAnalyzer
                            val plane = image.planes.firstOrNull() ?: return@setAnalyzer
                            val buffer = plane.buffer
                            val bytes = ByteArray(buffer.remaining()).also(buffer::get)
                            val source = PlanarYUVLuminanceSource(
                                bytes,
                                plane.rowStride,
                                image.height,
                                0,
                                0,
                                image.width,
                                image.height,
                                false,
                            )
                            val result = runCatching {
                                reader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))
                            }.getOrNull()
                            reader.reset()
                            val text = result?.text
                            if (!text.isNullOrBlank() && delivered.compareAndSet(false, true)) {
                                previewView.post { onCode(text) }
                            }
                        } finally {
                            image.close()
                        }
                    }
                    runCatching {
                        provider.unbindAll()
                        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                    }
                }, ContextCompat.getMainExecutor(viewContext))
            }
        },
    )
}
