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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.work.WorkManager
import coil.compose.rememberAsyncImagePainter
import com.image.compressor.ImageCompressor
import com.image.compressor.utils.getFileSizeMB
import com.image.compressor.utils.getImageInfo
import com.image.compressor.utils.getImageInfoFromFile
import com.image.liteimagecompressor.ui.theme.LiteImageCompressorTheme
import kotlinx.coroutines.launch
import java.io.File

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
    val coroutineScope = rememberCoroutineScope()

    val singleUri = remember { mutableStateOf<Uri?>(null) }
    val multipleUris = remember { mutableStateOf<List<Uri>>(emptyList()) }
    val compressedResults = remember { mutableStateMapOf<Uri, Pair<ByteArray, Triple<Int, Int, String>>>() }
    val originalInfoMap = remember { mutableStateMapOf<Uri, Triple<Int, Int, String>>() }
    val originalSizeMap = remember { mutableStateMapOf<Uri, Double>() }

    val singlePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            singleUri.value = it
            multipleUris.value = listOf(it)
            compressedResults.clear()
            originalInfoMap[it] = getImageInfo(context, it)
            originalSizeMap[it] = getFileSizeMB(context, it)
        }
    }

    val multiPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        multipleUris.value = uris
        compressedResults.clear()
        uris.forEach { uri ->
            originalInfoMap[uri] = getImageInfo(context, uri)
            originalSizeMap[uri] = getFileSizeMB(context, uri)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { singlePicker.launch("image/*") }) {
                Text("Pick Single Image")
            }
            Button(onClick = { multiPicker.launch("image/*") }) {
                Text("Pick Multiple Images")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        multipleUris.value.forEach { uri ->
            ImageCompareCard(
                uri = uri,
                originalInfo = originalInfoMap[uri],
                originalSizeMB = originalSizeMap[uri],
                compressedResult = compressedResults[uri],
                onCompress = {
                    val workId = ImageCompressor.Builder(context)
                        .setMaxSizeMB(1.0)
                        .setMaxWidth(1280)
                        .setMaxHeight(720)
                        .setCompressFormat(Bitmap.CompressFormat.WEBP)
                        .setCompressionQuality(80)
                        .setOutputFileName("compressed_avatar")
                        .setEnableLogging(true)
                        .build()
                        .compress(uri)

                    coroutineScope.launch {
                        val workManager = WorkManager.getInstance(context)
                        workManager.getWorkInfoByIdLiveData(workId).observeForever { info ->
                            if (info != null && info.state.isFinished) {
                                val outputPath = info.outputData.getString("compressedImagePath")
                                outputPath?.let { path ->
                                    val file = File(path)
                                    if (file.exists()) {
                                        val bytes = file.readBytes()
                                        val info = getImageInfoFromFile(path)
                                        compressedResults[uri] = bytes to info
                                    }
                                }
                            }
                        }
                    }
                }
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}


@Composable
fun ImageCompareCard(
    uri: Uri,
    originalInfo: Triple<Int, Int, String>?,
    originalSizeMB: Double?,
    compressedResult: Pair<ByteArray, Triple<Int, Int, String>>?,
    onCompress: () -> Unit
) {
    Column {
        Text("Comparing: ${uri.lastPathSegment}", fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Image(
                painter = rememberAsyncImagePainter(uri),
                contentDescription = "Original",
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 4.dp)
            )
            compressedResult?.first?.let { bytes ->
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Compressed",
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 4.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            ImageDetailColumn("Original", originalSizeMB, originalInfo)
            ImageDetailColumn(
                "Compressed",
                compressedResult?.first?.size?.toDouble()?.div(1024 * 1024),
                compressedResult?.second,
                ratio = if (compressedResult != null && originalSizeMB != null) {
                    (originalSizeMB / (compressedResult.first.size.toDouble() / (1024 * 1024)))
                } else null
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(onClick = onCompress) {
            Text("Compress This Image")
        }
    }
}


@Composable
fun ImageDetailColumn(
    label: String,
    sizeMB: Double?,
    info: Triple<Int, Int, String>?,
    ratio: Double? = null
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontWeight = FontWeight.Bold)
        Text("Size: %.2f MB".format(sizeMB ?: 0.0))
        info?.let { (w, h, mime) ->
            Text("Res: ${w}x${h}")
            Text("Format: ${mime.substringAfterLast('/')}")
        }
        ratio?.let {
            Text("Ratio: %.2f".format(it))
        }
    }
}


