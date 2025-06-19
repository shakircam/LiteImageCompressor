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
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.work.WorkManager
import coil.compose.AsyncImage
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
    val isCompressing = remember { mutableStateOf(false) }
    val compressionProgress = remember { mutableStateMapOf<Uri, Boolean>() }

    val singlePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            singleUri.value = it
            multipleUris.value = listOf(it)
            compressedResults.clear()
            compressionProgress.clear()
            originalInfoMap[it] = getImageInfo(context, it)
            originalSizeMap[it] = getFileSizeMB(context, it)
        }
    }

    val multiPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        multipleUris.value = uris
        singleUri.value = null
        compressedResults.clear()
        compressionProgress.clear()
        uris.forEach { uri ->
            originalInfoMap[uri] = getImageInfo(context, uri)
            originalSizeMap[uri] = getFileSizeMB(context, uri)
        }
    }

    // Function to remove image from list
    val removeImage = { uri: Uri ->
        multipleUris.value = multipleUris.value.filter { it != uri }
        compressedResults.remove(uri)
        originalInfoMap.remove(uri)
        originalSizeMap.remove(uri)
        compressionProgress.remove(uri)

        // If it was single image, clear single uri
        if (singleUri.value == uri) {
            singleUri.value = null
        }
    }

    // Function to compress single image
    val compressSingleImage = { uri: Uri ->
        compressionProgress[uri] = true
        val workId = ImageCompressor.Builder(context)
            .setMaxSizeMB(1.0)
            .setMaxWidth(1280)
            .setMaxHeight(720)
            .setCompressFormat(Bitmap.CompressFormat.WEBP)
            .setCompressionQuality(80)
            .setOutputFileName("compressed_${System.currentTimeMillis()}")
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
                            val imageInfo = getImageInfoFromFile(path)
                            compressedResults[uri] = bytes to imageInfo
                        }
                    }
                    compressionProgress[uri] = false
                }
            }
        }
    }

    // Function to compress all images
    val compressAllImages : () ->Unit = {
        isCompressing.value = true
        val urisToCompress = multipleUris.value.filter { it !in compressedResults.keys }

        urisToCompress.forEach { uri ->
            compressionProgress[uri] = true
        }

        val workId = ImageCompressor.Builder(context)
            .setMaxSizeMB(1.0)
            .setMaxWidth(1280)
            .setMaxHeight(720)
            .setCompressFormat(Bitmap.CompressFormat.WEBP)
            .setCompressionQuality(80)
            .setOutputFileName("compressed_batch")
            .setEnableLogging(true)
            .build()
            .compress(urisToCompress)

        coroutineScope.launch {
            val workManager = WorkManager.getInstance(context)
            workManager.getWorkInfoByIdLiveData(workId).observeForever { info ->
                if (info != null && info.state.isFinished) {
                    // Handle batch compression results
                    // You'll need to implement logic to get individual results from batch
                    // For now, compress individually
                    urisToCompress.forEach { uri ->
                        compressSingleImage(uri)
                    }
                    isCompressing.value = false
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Selection buttons
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { singlePicker.launch("image/*") }) {
                Text("Pick Single Image")
            }
            Button(onClick = { multiPicker.launch("image/*") }) {
                Text("Pick Multiple Images")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Show selected images count and compress all button for multiple images
        if (multipleUris.value.size > 1) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Selected Images: ${multipleUris.value.size}")

                Button(
                    onClick = compressAllImages,
                    enabled = !isCompressing.value && multipleUris.value.isNotEmpty()
                ) {
                    if (isCompressing.value) {
                        Text("Compressing...")
                    } else {
                        Text("Compress All")
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        // Display images
        if (multipleUris.value.size > 1) {
            // Grid layout for multiple images
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.height(400.dp)
            ) {
                items(multipleUris.value) { uri ->
                    ImageGridItem(
                        uri = uri,
                        onRemove = { removeImage(uri) },
                        isCompressing = compressionProgress[uri] == true,
                        isCompressed = uri in compressedResults.keys
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Show detailed comparison for compressed images
            multipleUris.value.forEach { uri ->
                if (uri in compressedResults.keys) {
                    ImageCompareCard(
                        uri = uri,
                        originalInfo = originalInfoMap[uri],
                        originalSizeMB = originalSizeMap[uri],
                        compressedResult = compressedResults[uri],
                        onCompress = { compressSingleImage(uri) },
                        onRemove = { removeImage(uri) },
                        showCompressButton = false // Hide individual compress button for batch
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        } else {
            // Single image layout
            multipleUris.value.forEach { uri ->
                ImageCompareCard(
                    uri = uri,
                    originalInfo = originalInfoMap[uri],
                    originalSizeMB = originalSizeMap[uri],
                    compressedResult = compressedResults[uri],
                    onCompress = { compressSingleImage(uri) },
                    onRemove = { removeImage(uri) },
                    showCompressButton = true
                )
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
fun ImageGridItem(
    uri: Uri,
    onRemove: () -> Unit,
    isCompressing: Boolean,
    isCompressed: Boolean
) {
    Box(
        modifier = Modifier
            .size(120.dp)
            .clip(RoundedCornerShape(8.dp))
    ) {
        AsyncImage(
            model = uri,
            contentDescription = "Selected image",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        // Remove button
        IconButton(
            onClick = onRemove,
            modifier = Modifier.align(Alignment.TopEnd)
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Remove",
                tint = Color.White,
                modifier = Modifier
                    .background(
                        Color.Black.copy(alpha = 0.6f),
                        CircleShape
                    )
                    .padding(4.dp)
            )
        }

        // Status indicator
        if (isCompressing) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(
                        Color.Black.copy(alpha = 0.7f),
                        RoundedCornerShape(8.dp)
                    )
                    .padding(8.dp)
            ) {
                CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        } else if (isCompressed) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = "Compressed",
                tint = Color.Green,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .background(
                        Color.White,
                        CircleShape
                    )
                    .padding(4.dp)
            )
        }
    }
}

@Composable
fun ImageCompareCard(
    uri: Uri,
    originalInfo: Triple<Int, Int, String>?,
    originalSizeMB: Double?,
    compressedResult: Pair<ByteArray, Triple<Int, Int, String>>?,
    onCompress: () -> Unit,
    onRemove: () -> Unit,
    showCompressButton: Boolean = true
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Comparing: ${uri.lastPathSegment}",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onRemove) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Remove",
                        tint = Color.Red
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                // Original image
                AsyncImage(
                    model = uri,
                    contentDescription = "Original",
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 4.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop
                )

                // Compressed image
                compressedResult?.first?.let { bytes ->
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Compressed",
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 4.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop
                    )
                } ?: run {
                    // Placeholder for compressed image
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 4.dp)
                            .background(
                                Color.Gray.copy(alpha = 0.3f),
                                RoundedCornerShape(8.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Not compressed yet", color = Color.Gray)
                    }
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

            if (showCompressButton) {
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onCompress,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Compress This Image")
                }
            }
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
            Text("Ratio: %.2f".format(it), color = if (it > 1) Color.Green else Color.Red)
        }
    }
}


