package com.image.liteimagecompressor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.work.WorkManager
import coil.compose.rememberAsyncImagePainter
import com.image.compressor.ImageCompressor
import com.image.liteimagecompressor.ui.theme.LiteImageCompressorTheme
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LiteImageCompressorTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ImageCompressorUI()
                }
            }
        }
    }
}

@Composable
fun ImageCompressorUI() {
    val context = LocalContext.current
    val selectedUri = remember { mutableStateOf<Uri?>(null) }
    val originalSizeMB = remember { mutableStateOf<Double?>(null) }
    val compressedBytes = remember { mutableStateOf<ByteArray?>(null) }
    val compressedSizeMB = remember { mutableStateOf<Double?>(null) }
    val coroutineScope = rememberCoroutineScope()

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri ->
            selectedUri.value = uri
            compressedBytes.value = null
            compressedSizeMB.value = null
            uri?.let {
                val inputStream: InputStream? = context.contentResolver.openInputStream(it)
                val size = inputStream?.available()?.toDouble() ?: 0.0
                originalSizeMB.value = size / (1024 * 1024)
                inputStream?.close()
            }
        }
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Button(onClick = { imagePickerLauncher.launch("image/*") }) {
            Text("Pick Image")
        }

        selectedUri.value?.let { uri ->
            Image(
                painter = rememberAsyncImagePainter(uri),
                contentDescription = "Original Image",
                modifier = Modifier
                    .height(200.dp)
                    .fillMaxWidth()
            )

            Text(
                text = "Original Size: ${"%.2f".format(originalSizeMB.value ?: 0.0)} MB",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )

            Button(onClick = {
                val workId = ImageCompressor.Builder(context, uri)
                    .setMaxSizeMB(1.0)                             // Max target size in MB
                    .setMaxWidth(1280)                             // Optional: Max width
                    .setMaxHeight(720)                             // Optional: Max height
                    .setCompressFormat(Bitmap.CompressFormat.WEBP) // Optional: Format
                    .setCompressionQuality(80)                     // Optional: JPEG/WebP quality
                    .setOutputFileName("compressed_avatar")        // Optional: Desired file name
                    .setEnableLogging(true)                        // Optional: Enable logs
                    .setTag("image-compression")                   // Optional: WorkManager tag
                    .build()
                    .compress()

                // Observe result from WorkManager
                coroutineScope.launch {
                    val workManager = WorkManager.getInstance(context)
                    workManager.getWorkInfoByIdLiveData(workId).observeForever { info ->
                        if (info != null && info.state.isFinished && info.outputData.keyValueMap.isNotEmpty()) {
                            val compressedImagePath = info.outputData.getString("compressedImagePath")
                            compressedImagePath?.let { path ->
                                val file = File(path)
                                if (file.exists()) {
                                    // Read bytes if you want or just use the file path to display image
                                    val bytes = file.readBytes()
                                    compressedBytes.value = bytes
                                    compressedSizeMB.value = bytes.size.toDouble() / (1024 * 1024)
                                }
                            }
                        }
                    }

                }
            }) {
                Text("Compress Image")
            }
        }

        compressedBytes.value?.let { bytes ->
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Compressed Image",
                modifier = Modifier
                    .height(200.dp)
                    .fillMaxWidth()
            )

            Text(
                text = "Compressed Size: ${"%.2f".format(compressedSizeMB.value ?: 0.0)} MB",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
        }
    }
}
