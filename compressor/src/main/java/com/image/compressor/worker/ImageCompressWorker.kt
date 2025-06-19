package com.image.compressor.worker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.image.compressor.utils.getFileSizeKB
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt
import kotlin.system.measureTimeMillis

class ImageCompressWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val TAG = "ImageCompressWorker"
    private val appContext = context
    // Create a custom dispatcher with limited concurrency to avoid overwhelming the system
    @OptIn(ExperimentalCoroutinesApi::class)
    private val imageProcessingDispatcher = Dispatchers.IO.limitedParallelism(
        parallelism = minOf(Runtime.getRuntime().availableProcessors(), 4)
    )

    override suspend fun doWork(): Result {
        return try {
            val uriString = inputData.getString("uri")
            val uriListString = inputData.getString("uriList")

            if (!uriListString.isNullOrEmpty()) {
                processMultipleImages(uriListString.split(","))
            } else if (!uriString.isNullOrEmpty()) {
                processSingleImage(uriString)
            } else {
                Result.failure(workDataOf("error" to "No URIs provided"))
            }

        } catch (e: Exception) {
            Result.failure(workDataOf("error" to e.message))
        }
    }

    private suspend fun processSingleImage(uriString: String): Result {
        val uri = uriString.toUri()
        val enableLogging = inputData.getBoolean("enableLogging", false)

        if (enableLogging) Log.d(TAG, "Processing single image: $uri")

        // Get original file size for compression ratio calculation
        val originalSizeKB = getFileSizeKB(appContext,uri)

        // Compress the image
        val compressionResult = compressImage(uri, 0, 1)

        return if (compressionResult.success) {
            Result.success(
                workDataOf(
                    "compressedImagePath" to compressionResult.outputPath,
                    "originalSizeKB" to originalSizeKB,
                    "compressedSizeKB" to compressionResult.compressedSizeKB,
                    "compressionRatio" to calculateCompressionRatio(originalSizeKB, compressionResult.compressedSizeKB)
                )
            )
        } else {
            Result.failure(workDataOf("error" to compressionResult.error))
        }
    }


    private suspend fun processMultipleImages(uriList: List<String>): Result =
        coroutineScope {
            val enableLogging = inputData.getBoolean("enableLogging", false)
            val totalImages = uriList.size

            if (enableLogging) Log.d(TAG, "Processing $totalImages images in parallel")

            val results = Collections.synchronizedList(mutableListOf<WorkerCompressionResult>())
            val compressedPaths = Collections.synchronizedList(mutableListOf<String>())
            val processedCount = AtomicInteger(0)
            val successCount = AtomicInteger(0)
            val failedCount = AtomicInteger(0)

            setProgress(
                workDataOf(
                    "progress" to 0,
                    "currentImage" to 0,
                    "totalImages" to totalImages,
                    "status" to "Starting parallel batch compression..."
                )
            )

            try {
                // Process images in parallel using async
                val timeTakenMillis = measureTimeMillis {
                    val deferredResults = uriList.mapIndexed { index, uriString ->
                        async(imageProcessingDispatcher) {
                            processImageAsync(
                                index, uriString, totalImages, enableLogging,
                                processedCount, successCount, failedCount, results, compressedPaths
                            )
                        }
                    }

                    // Wait for all images to complete
                    deferredResults.awaitAll()
                }
                if (enableLogging) Log.i(TAG, "Time taken in parallel processing: (${timeTakenMillis / 1000.0}s)")

            } catch (e: Exception) {
                if (enableLogging) Log.e(TAG, "Error in parallel processing: ${e.message}", e)
                return@coroutineScope Result.failure(workDataOf("error" to e.message))
            }

            setProgress(
                workDataOf(
                    "progress" to 100,
                    "currentImage" to totalImages,
                    "totalImages" to totalImages,
                    "status" to "Parallel compression completed: ${successCount.get()} successful, ${failedCount.get()} failed"
                )
            )

            if (enableLogging) {
                Log.d(
                    TAG,
                    "Parallel compression completed: ${successCount.get()} successful, ${failedCount.get()} failed"
                )
            }

            val sortedResults = results.sortedBy { it.index }
            val resultsJson = mapResultsToJson(sortedResults)

            return@coroutineScope Result.success(
                workDataOf(
                    "compressedImagePaths" to compressedPaths.toTypedArray(),
                    "results" to resultsJson.toString(),
                    "successCount" to successCount.get(),
                    "failedCount" to failedCount.get(),
                    "totalProcessed" to totalImages
                )
            )
        }

    // Convert results to JSON string for output
    private fun mapResultsToJson(results: List<WorkerCompressionResult>): List<Map<String, Any?>> {
        return results.map { result ->
            mapOf(
                "index" to result.index,
                "originalUri" to result.originalUri,
                "outputPath" to result.outputPath,
                "originalSizeKB" to result.originalSizeKB,
                "compressedSizeKB" to result.compressedSizeKB,
                "compressionRatio" to result.compressionRatio,
                "success" to result.success,
                "error" to result.error
            )
        }
    }

    private suspend fun compressImage(uri: Uri, index: Int, totalImages: Int): ImageCompressionResult {
        return try {
            val maxSizeMB = inputData.getDouble("maxSizeMB", 1.0)
            val compressionQuality = inputData.getInt("compressionQuality", -1)
            val maxWidth = inputData.getInt("maxWidth", -1)
            val maxHeight = inputData.getInt("maxHeight", -1)
            val compressFormatName = inputData.getString("compressFormat").orEmpty()
            val outputFileName = inputData.getString("outputFileName").takeIf { it?.isNotBlank() == true }
            val enableLogging = inputData.getBoolean("enableLogging", false)

            // Determine compress format
            val compressFormat = parseCompressFormat(compressFormatName)

            // Load, downscale, rotate, scale bitmap as needed
            val bitmap = loadBitmap(uri, maxWidth, maxHeight, enableLogging)

            // Scale bitmap further if needed to target maxSizeMB
            val targetBitmap = scaleToOptimalSize(bitmap, maxSizeMB, enableLogging)

            // Calculate quality (override if specified)
            val quality = if (compressionQuality in 0..100) {
                compressionQuality
            } else {
                calculateOptimalQuality(targetBitmap, (maxSizeMB * 1024 * 1024).toLong())
            }

            val outputStream = ByteArrayOutputStream()
            targetBitmap.compress(compressFormat, quality, outputStream)

            // Clean up bitmaps
            bitmap.recycle()
            if (targetBitmap != bitmap) {
                targetBitmap.recycle()
            }

            val compressedBytes = outputStream.toByteArray()

            // Generate unique filename for multiple images
            val fileName = if (totalImages > 1) {
                outputFileName?.let { "${it}_${index}_${System.currentTimeMillis()}.${getFileExtension(compressFormat)}" }
                    ?: "compressed_${index}_${System.currentTimeMillis()}.${getFileExtension(compressFormat)}"
            } else {
                outputFileName?.let { "${it}_${System.currentTimeMillis()}.${getFileExtension(compressFormat)}" }
                    ?: "compressed_${System.currentTimeMillis()}.${getFileExtension(compressFormat)}"
            }

            val outputFile = File(appContext.cacheDir, fileName)
            outputFile.writeBytes(compressedBytes)

            if (enableLogging) {
                Log.d(TAG, "Image ${index + 1}/$totalImages compressed successfully")
                Log.d(TAG, "Saved to: ${outputFile.absolutePath}")
                Log.d(TAG, "Final size: ${compressedBytes.size / 1024} KB, quality: $quality")
            }

            ImageCompressionResult(
                success = true,
                outputPath = outputFile.absolutePath,
                compressedSizeKB = compressedBytes.size / 1024,
                error = null
            )

        } catch (e: Exception) {
            Log.e(TAG, "Compression failed for image ${index + 1}/$totalImages: ${e.message}", e)
            ImageCompressionResult(
                success = false,
                outputPath = null,
                compressedSizeKB = 0,
                error = e.message ?: "Unknown compression error"
            )
        }
    }

    private fun calculateCompressionRatio(originalKB: Long, compressedKB: Int): Float {
        return if (originalKB > 0) {
            ((originalKB - compressedKB).toFloat() / originalKB.toFloat()) * 100
        } else 0f
    }

    private fun parseCompressFormat(formatName: String): Bitmap.CompressFormat {
        return when (formatName.uppercase()) {
            "PNG" -> Bitmap.CompressFormat.PNG
            "JPEG", "JPG" -> Bitmap.CompressFormat.JPEG
            "WEBP_LOSSLESS" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSLESS
            } else {
                Bitmap.CompressFormat.WEBP
            }
            "WEBP" -> Bitmap.CompressFormat.WEBP
            else -> Bitmap.CompressFormat.JPEG
        }
    }

    private suspend fun loadBitmap(
        uri: Uri,
        maxWidth: Int,
        maxHeight: Int,
        enableLogging: Boolean
    ): Bitmap = withContext(Dispatchers.IO) {
        // Get original image info and rotation
        val (bounds, rotation) = getImageInfoFromUri(uri)

        // Calculate inSampleSize for down sampling
        val inSampleSize = calculateInSampleSize(bounds, maxWidth, maxHeight)

        val options = BitmapFactory.Options().apply {
            this.inSampleSize = inSampleSize
            inPreferredConfig = Bitmap.Config.RGB_565
        }

        val inputStream = appContext.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Cannot open input stream for URI")

        val bitmap = inputStream.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        } ?: throw IllegalArgumentException("Failed to decode image from URI")

        // Apply rotation if needed
        val rotatedBitmap = if (rotation != 0) {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            bitmap.recycle()
            rotated
        } else {
            bitmap
        }

        // Resize if maxWidth or maxHeight specified
        val resizedBitmap = if (maxWidth > 0 || maxHeight > 0) {
            val targetWidth = if (maxWidth > 0) maxWidth else rotatedBitmap.width
            val targetHeight = if (maxHeight > 0) maxHeight else rotatedBitmap.height
            scaleBitmapToFit(rotatedBitmap, targetWidth, targetHeight)
        } else {
            rotatedBitmap
        }

        if (enableLogging) {
            Log.d(TAG, "Loaded bitmap with size: ${resizedBitmap.width}x${resizedBitmap.height}")
        }

        resizedBitmap
    }

    private fun scaleToOptimalSize(bitmap: Bitmap, maxSizeMB: Double, enableLogging: Boolean): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val currentPixels = width * height

        val bytesPerPixel = 0.8 // Estimated
        val targetPixels = ((maxSizeMB * 1024 * 1024) / bytesPerPixel).toInt()

        if (currentPixels <= targetPixels) {
            return bitmap
        }

        val scaleFactor = sqrt(targetPixels.toDouble() / currentPixels)
        val newWidth = (width * scaleFactor).toInt()
        val newHeight = (height * scaleFactor).toInt()

        if (enableLogging) {
            Log.d(TAG, "Final scaling from ${width}x${height} to ${newWidth}x${newHeight}")
        }

        return bitmap.scale(newWidth, newHeight)
    }

    private fun calculateOptimalQuality(bitmap: Bitmap, maxSizeBytes: Long): Int {
        val pixels = bitmap.width * bitmap.height
        val estimatedBytesAt100 = pixels * 1.2 // Estimated bytes at quality 100
        val compressionRatio = maxSizeBytes.toDouble() / estimatedBytesAt100

        return when {
            compressionRatio >= 0.8 -> 95
            compressionRatio >= 0.6 -> 85
            compressionRatio >= 0.4 -> 75
            compressionRatio >= 0.2 -> 65
            else -> 50
        }.coerceIn(50, 95)
    }

    private fun calculateInSampleSize(bounds: BitmapFactory.Options, maxWidth: Int, maxHeight: Int): Int {
        val (width, height) = bounds.outWidth to bounds.outHeight

        if (maxWidth <= 0 && maxHeight <= 0) return 1

        var inSampleSize = 1

        if (height > maxHeight && maxHeight > 0 || width > maxWidth && maxWidth > 0) {
            val heightRatio = if (maxHeight > 0) height / maxHeight else Int.MAX_VALUE
            val widthRatio = if (maxWidth > 0) width / maxWidth else Int.MAX_VALUE

            inSampleSize = heightRatio.coerceAtMost(widthRatio)
        }

        return inSampleSize.coerceAtLeast(1)
    }

    private fun getImageInfoFromUri(uri: Uri): Pair<BitmapFactory.Options, Int> {
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
            appContext.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, this)
            }
        }

        val rotation = getImageRotation(uri)

        return Pair(bounds, rotation)
    }

    private fun getImageRotation(uri: Uri?): Int {
        if (uri == null) return 0
        return try {
            val inputStream = appContext.contentResolver.openInputStream(uri)
            inputStream?.use {
                val exifInterface = ExifInterface(it)
                when (exifInterface.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } ?: 0
        } catch (e: Exception) {
            Log.e(TAG, "Error getting image orientation: ${e.message}")
            0
        }
    }

    private fun scaleBitmapToFit(bitmap: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        val widthRatio = maxWidth.toFloat() / width.toFloat()
        val heightRatio = maxHeight.toFloat() / height.toFloat()
        val scaleFactor = minOf(widthRatio, heightRatio, 1f) // Don't upscale

        val newWidth = (width * scaleFactor).toInt()
        val newHeight = (height * scaleFactor).toInt()

        return bitmap.scale(newWidth, newHeight)
    }

    private fun Bitmap.scale(newWidth: Int, newHeight: Int): Bitmap {
        return Bitmap.createScaledBitmap(this, newWidth, newHeight, true)
    }

    private fun getFileExtension(compressFormat: Bitmap.CompressFormat): String {
        return when (compressFormat) {
            Bitmap.CompressFormat.PNG -> "png"
            Bitmap.CompressFormat.JPEG -> "jpg"
            Bitmap.CompressFormat.WEBP -> "webp"
            else -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    && compressFormat == Bitmap.CompressFormat.WEBP_LOSSLESS
                ) {
                    "webp"
                } else {
                    "jpg" // fallback
                }
            }
        }
    }

    private suspend fun processImageAsync(
        index: Int,
        uriString: String,
        totalImages: Int,
        enableLogging: Boolean,
        processedCount: AtomicInteger,
        successCount: AtomicInteger,
        failedCount: AtomicInteger,
        results: MutableList<WorkerCompressionResult>,
        compressedPaths: MutableList<String>
    ) {
        try {
            val uri = uriString.toUri()

            if (enableLogging) Log.d(TAG, "Processing image ${index + 1}/$totalImages: $uri")

            // Get original file size
            val originalSizeKB = getFileSizeKB(appContext, uri)

            // Compress the image
            val compressionResult = compressImage(uri, index, totalImages)

            val processed = processedCount.incrementAndGet()
            val currentProgress = ((processed.toFloat() / totalImages) * 100).toInt()

            // Update progress safely
            setProgress(workDataOf(
                "progress" to currentProgress,
                "currentImage" to processed,
                "totalImages" to totalImages,
                "status" to "Processed $processed of $totalImages images..."
            ))

            if (compressionResult.success) {
                results.add(
                    WorkerCompressionResult(
                        index = index,
                        originalUri = uriString,
                        outputPath = compressionResult.outputPath,
                        originalSizeKB = originalSizeKB,
                        compressedSizeKB = compressionResult.compressedSizeKB,
                        compressionRatio = calculateCompressionRatio(originalSizeKB, compressionResult.compressedSizeKB),
                        success = true,
                        error = null
                    )
                )
                compressionResult.outputPath?.let { compressedPaths.add(it) }
                successCount.incrementAndGet()
            } else {
                results.add(
                    WorkerCompressionResult(
                        index = index,
                        originalUri = uriString,
                        outputPath = null,
                        originalSizeKB = originalSizeKB,
                        compressedSizeKB = 0,
                        compressionRatio = 0f,
                        success = false,
                        error = compressionResult.error
                    )
                )
                failedCount.incrementAndGet()
                if (enableLogging) Log.e(TAG, "Failed to compress image ${index + 1}: ${compressionResult.error}")
            }

        } catch (e: Exception) {
            results.add(
                WorkerCompressionResult(
                    index = index,
                    originalUri = uriString,
                    outputPath = null,
                    originalSizeKB = 0,
                    compressedSizeKB = 0,
                    compressionRatio = 0f,
                    success = false,
                    error = e.message ?: "Unknown error"
                )
            )
            failedCount.incrementAndGet()
            if (enableLogging) Log.e(TAG, "Exception processing image ${index + 1}: ${e.message}", e)
        }
    }

    private data class ImageCompressionResult(
        val success: Boolean,
        val outputPath: String?,
        val compressedSizeKB: Int,
        val error: String?
    )

    private data class WorkerCompressionResult(
        val index: Int,
        val originalUri: String,
        val outputPath: String?,
        val originalSizeKB: Long,
        val compressedSizeKB: Int,
        val compressionRatio: Float,
        val success: Boolean,
        val error: String?
    )
}





