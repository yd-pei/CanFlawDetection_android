package com.example.canflaw_detection

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.canflaw_detection.ui.theme.Canflaw_detectionTheme
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import androidx.camera.core.Preview as CameraPreview
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull

// Import necessary packages
// 1. Complete Detection Data Class
// Update Detection data class to include confidence
data class Detection(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val label: String,
    val confidence: Float
)

class MainActivity : ComponentActivity() {
    companion object {
        // Detection interval in milliseconds (e.g., 1000ms = 1 second)
        // Processing 2.5 frames per second
        const val DETECTION_INTERVAL: Long = 1000
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Add this line to keep screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        enableEdgeToEdge()
        setContent {
            Canflaw_detectionTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    CameraScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
fun CameraScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    var cameraStarted by remember { mutableStateOf(false) }


    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            hasCameraPermission = granted
            if (granted) {
                cameraStarted = true
            }
        }
    )

    Box(modifier = modifier.fillMaxSize()) {
        if (hasCameraPermission && cameraStarted) {
            CameraPreview()
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Button(
                    onClick = {
                        if (!hasCameraPermission) {
                            launcher.launch(Manifest.permission.CAMERA)
                        } else {
                            cameraStarted = true
                        }
                    }
                ) {
                    Text(text = if (hasCameraPermission) "Start Camera" else "Request Camera Permission")
                }
            }
        }
    }
}

@Composable
fun CameraPreview() {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current

    val previewView = remember { PreviewView(context) }
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    // OkHttp Client
    val client = remember { OkHttpClient() }

    // Variables to hold image dimensions
    var imageWidth by remember { mutableIntStateOf(1) }
    var imageHeight by remember { mutableIntStateOf(1) }
    var detections by remember { mutableStateOf<List<Detection>>(emptyList()) }

    DisposableEffect(Unit) {
        val executor = ContextCompat.getMainExecutor(context)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                val preview = CameraPreview.Builder().build()
                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                // Configure ImageAnalysis
                val imageAnalyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                var lastAnalyzedTime = 0L

                // Inside the analyzer
                imageAnalyzer.setAnalyzer(executor) { imageProxy ->
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastAnalyzedTime >= MainActivity.DETECTION_INTERVAL) {
                        lastAnalyzedTime = currentTime
                        processImageProxy(imageProxy, { newDetections, imgWidth, imgHeight ->
                            detections = newDetections
                            imageWidth = imgWidth
                            imageHeight = imgHeight
                        }, client)
                    } else {
                        imageProxy.close()
                    }
                }

                preview.surfaceProvider = previewView.surfaceProvider

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageAnalyzer // Add imageAnalyzer to the use cases
                    )
                } catch (e: Exception) {
                    Log.e("CameraPreview", "Use case binding failed", e)
                }
            } catch (e: Exception) {
                Log.e("CameraPreview", "Camera provider initialization failed", e)
            }
        }, executor)

        onDispose {
            try {
                val cameraProvider = cameraProviderFuture.get()
                cameraProvider.unbindAll()
            } catch (e: Exception) {
                Log.e("CameraPreview", "Failed to unbind camera", e)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )

        // Overlay Canvas for Drawing Boxes
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (detections.isNotEmpty()) {
                detections.forEach { detection ->
                    // Choose the appropriate color based on the label
                    val boxColor = when (detection.label) {
                        "Critical Defect" -> Color.Red
                        "Major Defect" -> Color.Red
                        "Minor Defect" -> Color.Yellow
                        else -> Color.Green
                    }
                    val boxStrokeWidth = 4f

                    // Use the image dimensions for scaling
                    val scaleX = size.width / imageWidth.toFloat()
                    val scaleY = size.height / imageHeight.toFloat()

                    //val rectLeft = (detection.x - detection.width / 2f) * scaleX
                    val rectLeft = (detection.x - detection.width / 1.3f) * scaleX
                    val rectTop = (detection.y - detection.height / 2f) * scaleY
                    //val rectWidth = detection.width * scaleX
                    val rectWidth = detection.width * scaleX * 1.5f
                    val rectHeight = detection.height * scaleY

                    // Draw detection rectangle
                    drawRect(
                        color = boxColor,
                        topLeft = Offset(rectLeft, rectTop),
                        size = Size(rectWidth, rectHeight),
                        style = Stroke(width = boxStrokeWidth)
                    )

                    // Draw text label
                    drawContext.canvas.nativeCanvas.apply {
                        drawText(
                            "${detection.label} (${String.format("%.2f", detection.confidence)})",
                            rectLeft,
                            rectTop - 10f, // Position above box
                            Paint().apply {
                                color = android.graphics.Color.WHITE
                                textSize = 40f
                                strokeWidth = 10f
                                style = android.graphics.Paint.Style.STROKE
                                strokeJoin = android.graphics.Paint.Join.ROUND
                            }
                        )
                        drawText(
                            "${detection.label} (${String.format("%.2f", detection.confidence)})",
                            rectLeft,
                            rectTop - 10f,
                            Paint().apply {
                                color = if (detection.label == "Critical Defect") 
                                    android.graphics.Color.RED 
                                else if (detection.label == "Major Defect")
                                    android.graphics.Color.RED
                                else if (detection.label == "Minor Defect")
                                    android.graphics.Color.YELLOW
                                else
                                    android.graphics.Color.GREEN
                                textSize = 40f
                            }
                        )
                    }
                }
            } else {
                // Draw a green border if no detections are present
                drawRect(
                    color = Color.Green,
                    topLeft = Offset(0f, 0f),
                    size = Size(size.width, size.height),
                    style = Stroke(width = 4f)
                )
            }
        }
    }
}

// Helper function to convert ImageProxy to Bitmap
fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap {
    val rotationDegrees = imageProxy.imageInfo.rotationDegrees

    val yBuffer = imageProxy.planes[0].buffer
    val uBuffer = imageProxy.planes[1].buffer
    val vBuffer = imageProxy.planes[2].buffer

    val ySize = yBuffer.remaining()
    val uSize = uBuffer.remaining()
    val vSize = vBuffer.remaining()

    val nv21 = ByteArray(ySize + uSize + vSize)
    yBuffer.get(nv21, 0, ySize)
    vBuffer.get(nv21, ySize, vSize)
    uBuffer.get(nv21, ySize + vSize, uSize)

    val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
    val out = ByteArrayOutputStream()
    yuvImage.compressToJpeg(Rect(0, 0, yuvImage.width, yuvImage.height), 80, out)
    val imageBytes = out.toByteArray()
    var bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)

    // Rotate the bitmap according to the rotationDegrees
    bitmap = rotateBitmap(bitmap, rotationDegrees.toFloat())

    return bitmap
}

// Helper function to rotate a bitmap
fun rotateBitmap(bitmap: Bitmap, rotationDegrees: Float): Bitmap {
    val matrix = Matrix()
    matrix.postRotate(rotationDegrees)
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

// Process ImageProxy: Convert to Bitmap, encode to Base64, and send to API
fun processImageProxy(
    imageProxy: ImageProxy,
    onDetectionsUpdated: (List<Detection>, Int, Int) -> Unit,
    client: OkHttpClient
) {
    val bitmap = imageProxyToBitmap(imageProxy)
    imageProxy.close() // Close the image to prevent memory leaks

    val imageWidth = bitmap.width
    val imageHeight = bitmap.height

    // Convert bitmap to Base64 without line breaks
    val outputStream = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
    val byteArray = outputStream.toByteArray()
    val base64Image = Base64.encodeToString(byteArray, Base64.NO_WRAP)

    // Send the image to the API
    sendImageToApi(base64Image, { detections ->
        onDetectionsUpdated(detections, imageWidth, imageHeight)
    }, client)
}

// Updated sendImageToApi function
fun sendImageToApi(
    base64Image: String,
    onDetectionsUpdated: (List<Detection>) -> Unit,
    client: OkHttpClient
) {
    val baseUrl = "https://detect.roboflow.com/canned-food-surface-defect/1"
    val httpUrl = baseUrl.toHttpUrlOrNull()?.newBuilder()
        ?.addQueryParameter("api_key", "HXblEa6WLoZkYmTZA7wI")
        ?.build()

    // Create request body with correct Content-Type
    val mediaType = "application/x-www-form-urlencoded".toMediaTypeOrNull()
    val requestBody = base64Image.toRequestBody(mediaType)

    val request = Request.Builder()
        .url(httpUrl!!)
        .post(requestBody)
        .header("Content-Type", "application/x-www-form-urlencoded")
        .build()

    client.newCall(request).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            Log.e("API", "Failed to send image", e)
        }

        override fun onResponse(call: Call, response: Response) {
            response.body?.string()?.let { responseBody ->
                Log.d("API_Response", "Response: $responseBody")
                
                CoroutineScope(Dispatchers.Main).launch {
                    processApiResponse(responseBody, onDetectionsUpdated)
                }
            } ?: run {
                Log.e("API_Response", "Response body is null.")
                CoroutineScope(Dispatchers.Main).launch {
                    onDetectionsUpdated(emptyList())
                }
            }
        }
    })
}

// Process API response and update detections
fun processApiResponse(
    responseBody: String,
    onDetectionsUpdated: (List<Detection>) -> Unit
) {
    try {
        val jsonObject = JSONObject(responseBody)
        val predictions = jsonObject.optJSONArray("predictions") // Safely access 'predictions'

        if (predictions == null) {
            Log.e("API", "'predictions' field is missing in the response.")
            onDetectionsUpdated(emptyList()) // Update with an empty list
            return
        }

        val detectionList = mutableListOf<Detection>()

        for (i in 0 until predictions.length()) {
            val prediction = predictions.getJSONObject(i)
            val x = prediction.getDouble("x").toFloat()
            val y = prediction.getDouble("y").toFloat()
            val width = prediction.getDouble("width").toFloat()
            val height = prediction.getDouble("height").toFloat()
            val label = prediction.getString("class")
            val confidence = prediction.getDouble("confidence").toFloat()

            detectionList.add(Detection(x, y, width, height, label, confidence))
        }

        onDetectionsUpdated(detectionList)
    } catch (e: JSONException) {
        Log.e("API", "JSON parsing error: ", e)
        onDetectionsUpdated(emptyList()) // Update with an empty list on error
    }
}
