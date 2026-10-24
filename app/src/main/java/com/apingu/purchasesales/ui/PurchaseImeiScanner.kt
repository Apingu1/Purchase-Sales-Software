package com.apingu.purchasesales.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

/** Scan many codes before applying them to one purchase item. Cancel is non-destructive. */
@Composable
fun PurchaseImeiScannerScreen(
    itemName: String,
    initialCodes: List<String>,
    codesOnOtherItems: Set<String>,
    onApply: (List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    BackHandler(onBack = onDismiss)
    val context = LocalContext.current
    val codes = remember { mutableStateListOf<String>().apply { addAll(initialCodes.distinct()) } }
    var allowSerial by remember { mutableStateOf(false) }
    var frontCamera by remember { mutableStateOf(false) }
    var torch by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(0f) }
    var manual by remember { mutableStateOf("") }
    var feedback by remember { mutableStateOf("Aim the target line at the IMEI barcode.") }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var lastRejected by remember { mutableStateOf("") }

    var permissionGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissionGranted = it
    }
    LaunchedEffect(Unit) {
        if (!permissionGranted) askPermission.launch(Manifest.permission.CAMERA)
    }

    fun addCodes(raw: String) {
        val parsed = ImeiBarcodeRules.fromBarcode(raw, allowSerial)
        if (parsed.isEmpty()) {
            if (raw != lastRejected) {
                feedback = if (allowSerial) "Barcode is not a valid IMEI or serial." else "Not a valid 15-digit IMEI. Retail barcodes are ignored."
                lastRejected = raw
            }
            return
        }
        lastRejected = ""
        var added = 0
        var duplicate = false
        for (value in parsed) {
            if (value in codes || value in codesOnOtherItems) {
                duplicate = true
                continue
            }
            codes.add(value)
            added++
        }
        if (added > 0) {
            successVibration(context)
            feedback = "$added identifier" + (if (added == 1) "" else "s") + " captured. Move to the next phone."
        } else if (duplicate && !feedback.startsWith("Already")) {
            feedback = "Already scanned or assigned to another item in this order."
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDismiss) { Icon(Icons.Default.ArrowBack, "Close scanner") }
            Column(Modifier.weight(1f)) {
                Text("Scan IMEI / serial", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(itemName.ifBlank { "Current purchase item" }, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
            AssistChip(onClick = {}, label = { Text(codes.size.toString() + " scanned") })
        }

        Box(Modifier.fillMaxWidth().height(310.dp).background(Color(0xFF141820))) {
            if (permissionGranted) {
                CameraBarcodeFeed(
                    lensFacing = if (frontCamera) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK,
                    torch = torch && !frontCamera,
                    zoom = zoom,
                    onBarcodes = { values -> values.forEach(::addCodes) },
                    onFailure = { cameraError = it },
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    Modifier.align(Alignment.Center).fillMaxWidth(0.86f).height(130.dp)
                        .border(2.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(16.dp))
                )
                Box(
                    Modifier.align(Alignment.Center).fillMaxWidth(0.86f).height(2.dp)
                        .background(Color(0xFFFFC44D))
                )
                Row(Modifier.align(Alignment.BottomCenter).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalIconButton(onClick = { torch = !torch }, enabled = !frontCamera) {
                        Icon(if (torch && !frontCamera) Icons.Default.FlashOn else Icons.Default.FlashOff, "Toggle torch")
                    }
                    FilledTonalIconButton(onClick = {
                        frontCamera = !frontCamera
                        torch = false
                        cameraError = null
                    }) {
                        Icon(Icons.Default.Cameraswitch, "Switch front or rear camera")
                    }
                }
                if (cameraError != null) {
                    Text(
                        cameraError.orEmpty(),
                        Modifier.align(Alignment.TopCenter).padding(16.dp)
                            .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(8.dp)).padding(8.dp),
                        color = Color.White, style = MaterialTheme.typography.bodySmall
                    )
                }
            } else {
                Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.NoPhotography, null, tint = Color.White)
                    Spacer(Modifier.height(8.dp))
                    Text("Camera access is needed to scan barcodes.", color = Color.White)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { askPermission.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
                }
            }
        }

        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(feedback, style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Zoom", style = MaterialTheme.typography.labelSmall)
                Slider(value = zoom, onValueChange = { zoom = it }, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
                Text(((1f + zoom * 3f).toInt()).toString() + "×", style = MaterialTheme.typography.labelSmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = allowSerial, onCheckedChange = { allowSerial = it })
                Text("Also accept serial-number barcodes", style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = manual, onValueChange = { manual = it },
                    label = { Text(if (allowSerial) "Enter IMEI / serial" else "Enter IMEI manually") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    modifier = Modifier.weight(1f)
                )
                Button(onClick = { addCodes(manual); manual = "" }, enabled = manual.isNotBlank()) { Text("Add") }
            }
            Text(
                "Use IMEI 1 (primary IMEI) for each handset. A second SIM IMEI on the same phone is not a second stock unit.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
        }

        HorizontalDivider()
        Text(
            "Scanned identifiers · " + codes.size,
            Modifier.fillMaxWidth().padding(start = 16.dp, top = 10.dp, bottom = 4.dp),
            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold
        )
        if (codes.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("No IMEIs scanned yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)) {
                items(codes, key = { it }) { value ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircleOutline, null, tint = MaterialTheme.colorScheme.primary)
                        Text(value, Modifier.weight(1f).padding(start = 10.dp))
                        IconButton(onClick = {
                            codes.remove(value)
                            feedback = "$value removed from the scan list."
                        }) { Icon(Icons.Default.Close, "Remove $value") }
                    }
                    HorizontalDivider()
                }
            }
        }
        Button(
            onClick = { onApply(codes.toList()) },
            enabled = codes.toList() != initialCodes.distinct(),
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) {
            Icon(Icons.Default.Check, null)
            Spacer(Modifier.width(8.dp))
            Text("Apply " + codes.size + " identifier" + (if (codes.size == 1) "" else "s") + " to item")
        }
    }
}

@Suppress("DEPRECATION")
private fun successVibration(context: Context) {
    val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    } else {
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }
    if (vibrator.hasVibrator()) vibrator.vibrate(VibrationEffect.createOneShot(60L, VibrationEffect.DEFAULT_AMPLITUDE))
}

/** Bundled ML Kit decoder, CameraX preview, autofocus and device rotation support. */
@OptIn(ExperimentalGetImage::class)
@Composable
private fun CameraBarcodeFeed(
    lensFacing: Int,
    torch: Boolean,
    zoom: Float,
    onBarcodes: (List<String>) -> Unit,
    onFailure: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    val previewView = remember(context) {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                    Barcode.FORMAT_CODE_128, Barcode.FORMAT_CODE_39, Barcode.FORMAT_CODE_93,
                    Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_ITF,
                    Barcode.FORMAT_UPC_A, Barcode.FORMAT_QR_CODE, Barcode.FORMAT_DATA_MATRIX,
                    Barcode.FORMAT_PDF417, Barcode.FORMAT_AZTEC
                ).build()
        )
    }
    val barcodesCallback by rememberUpdatedState(onBarcodes)
    val errorCallback by rememberUpdatedState(onFailure)
    var camera by remember { mutableStateOf<Camera?>(null) }

    DisposableEffect(owner, lensFacing, previewView) {
        var disposed = false
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (!disposed) {
                try {
                    val provider = future.get()
                    val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
                    val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                    val analysis = ImageAnalysis.Builder()
                        .setTargetResolution(Size(1280, 720))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(cameraExecutor) { proxy ->
                        val image = proxy.image
                        if (image == null) {
                            proxy.close()
                        } else {
                            try {
                                scanner.process(InputImage.fromMediaImage(image, proxy.imageInfo.rotationDegrees))
                                    .addOnSuccessListener(mainExecutor) { results ->
                                        if (!disposed) {
                                            val values = results.mapNotNull { it.rawValue?.trim() }.filter { it.isNotEmpty() }
                                            if (values.isNotEmpty()) barcodesCallback(values)
                                        }
                                    }
                                    .addOnCompleteListener { proxy.close() }
                            } catch (_: Exception) {
                                proxy.close()
                            }
                        }
                    }
                    provider.unbindAll()
                    camera = provider.bindToLifecycle(owner, selector, preview, analysis)
                } catch (e: Exception) {
                    errorCallback("Camera unavailable: " + (e.message ?: "please try again"))
                }
            }
        }, mainExecutor)

        onDispose {
            disposed = true
            camera = null
            if (future.isDone) runCatching { future.get().unbindAll() }
        }
    }
    LaunchedEffect(camera, torch, zoom) {
        camera?.cameraControl?.enableTorch(torch)
        camera?.cameraControl?.setLinearZoom(zoom.coerceIn(0f, 1f))
    }
    DisposableEffect(Unit) {
        onDispose {
            scanner.close()
            cameraExecutor.shutdown()
        }
    }
    AndroidView(factory = { previewView }, modifier = modifier)
}
