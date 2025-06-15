package com.image.compressor.worker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.sqrt

class ImageCompressWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val TAG = "ImageCompressWorker"
    private val appContext = context

    override suspend fun doWork(): Result {
        try {
            val uriString = inputData.getString("uri") ?: return Result.failure()
            val maxSizeMB = inputData.getDouble("maxSizeMB", 1.0)
            val compressionQuality = inputData.getInt("compressionQuality", -1)
            val maxWidth = inputData.getInt("maxWidth", -1)
            val maxHeight = inputData.getInt("maxHeight", -1)
            val compressFormatName = inputData.getString("compressFormat").orEmpty()
            val outputFileName = inputData.getString("outputFileName").takeIf { it?.isNotBlank() == true }
            val enableLogging = inputData.getBoolean("enableLogging", false)

            val uri = Uri.parse(uriString)
            if (enableLogging) Log.d(TAG, "Compression start uri: $uri")

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

            bitmap.recycle()
            if (targetBitmap != bitmap) {
                targetBitmap.recycle()
            }

            val compressedBytes = outputStream.toByteArray()

            // Save compressed bytes to file
            val fileName =
                outputFileName ?: ("compressed_${System.currentTimeMillis()}." + getFileExtension(
                    compressFormat
                ))
            val outputFile = File(appContext.cacheDir, fileName)
            outputFile.writeBytes(compressedBytes)

            if (enableLogging) {
                Log.d(TAG, "Compression done, saved to ${outputFile.absolutePath}")
                Log.d(TAG, "Final size: ${compressedBytes.size / 1024} KB, quality: $quality")
            }

            return Result.success(workDataOf("compressedImagePath" to outputFile.absolutePath))
        } catch (e: Exception) {
            Log.e(TAG, "Compression failed: ${e.message}", e)
            return Result.failure()
        }
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
}





