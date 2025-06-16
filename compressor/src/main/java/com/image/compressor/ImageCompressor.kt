package com.image.compressor

import android.content.Context
import android.net.Uri
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.image.compressor.worker.ImageCompressWorker
import java.util.UUID
import android.graphics.Bitmap
import com.image.compressor.utils.getFileSizeKB
import java.io.File

class ImageCompressor private constructor(
    private val context: Context,
    private val maxSizeMB: Double,
    private val compressionQuality: Int?,
    private val maxWidth: Int?,
    private val maxHeight: Int?,
    private val compressFormat: Bitmap.CompressFormat?,
    private val tag: String?,
    private val constraints: Constraints,
    private val outputFileName: String?,
    private val outputDir: File?,
    private val enableLogging: Boolean
) {

    class Builder(private val context: Context) {

        // ✅ Optional: Target size in MB (default = 1.0 MB)
        private var maxSizeMB: Double = 1.0

        // ✅ Optional: Max width in pixels (default = no constraint)
        private var maxWidth: Int? = null

        // ✅ Optional: Max height in pixels (default = no constraint)
        private var maxHeight: Int? = null

        // ✅ Optional: Compression format (default = based on URI MIME type, fallback = JPEG)
        private var compressFormat: Bitmap.CompressFormat? = null

        // ✅ Optional: Compression quality (0–100), used for JPEG/WEBP (default = auto-calculated)
        private var compressionQuality: Int? = null

        // ✅ Optional: Output file name without extension (default = auto-generated)
        private var outputFileName: String? = null

        // ✅ Optional: Output directory for compressed file (default = context.cacheDir)
        private var outputDir: File? = null

        // ✅ Optional: Enable debug logging (default = false)
        private var enableLogging: Boolean = false

        // ✅ Optional: WorkManager tag (default = null)
        private var tag: String? = null

        // ✅ Optional: WorkManager constraints (default = storage not low, no network required)
        private var constraints: Constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .setRequiresStorageNotLow(true)
            .build()

        // --------- Setter functions ---------
        fun setMaxSizeMB(size: Double) = apply { this.maxSizeMB = size }
        fun setCompressionQuality(quality: Int) = apply { this.compressionQuality = quality }
        fun setMaxWidth(width: Int) = apply { this.maxWidth = width }
        fun setMaxHeight(height: Int) = apply { this.maxHeight = height }
        fun setCompressFormat(format: Bitmap.CompressFormat) =
            apply { this.compressFormat = format }

        fun setTag(tag: String) = apply { this.tag = tag }
        fun setConstraints(constraints: Constraints) = apply { this.constraints = constraints }
        fun setOutputFileName(name: String) = apply { this.outputFileName = name }
        fun setOutputDir(dir: File) = apply { this.outputDir = dir }
        fun setEnableLogging(enabled: Boolean) = apply { this.enableLogging = enabled }

        // --------- Build the compressor ---------
        fun build(): ImageCompressor {
            return ImageCompressor(
                context,
                maxSizeMB,
                compressionQuality,
                maxWidth,
                maxHeight,
                compressFormat,
                tag,
                constraints,
                outputFileName,
                outputDir,
                enableLogging
            )
        }
    }

    fun compress(uri: Uri, onStart: ((UUID) -> Unit)? = null): UUID {
        val workManager = WorkManager.getInstance(context)
        val outputPath = outputDir?.absolutePath ?: context.cacheDir.absolutePath
        // Get original file size
        val originalSizeKB = getFileSizeKB(context,uri)

        val inputData = workDataOf(
            "uri" to uri.toString(),
            "maxSizeMB" to maxSizeMB,
            "compressionQuality" to (compressionQuality ?: -1),
            "maxWidth" to (maxWidth ?: -1),
            "maxHeight" to (maxHeight ?: -1),
            "compressFormat" to (compressFormat?.name ?: ""),
            "outputFileName" to outputFileName,
            "outputDir" to outputPath,
            "enableLogging" to enableLogging,
            "originalSizeKB" to originalSizeKB
        )

        val request = OneTimeWorkRequestBuilder<ImageCompressWorker>()
            .setInputData(inputData)
            .setConstraints(constraints)
            .apply { tag?.let { addTag(it) } }
            .build()

        workManager.enqueue(request)
        onStart?.invoke(request.id)
        return request.id
    }

    fun compress(uris: List<Uri>, onStart: ((index: Int, uuid: UUID) -> Unit)? = null): UUID {
        val workManager = WorkManager.getInstance(context)
        val outputPath = outputDir?.absolutePath ?: context.cacheDir.absolutePath

        // Convert URIs to comma-separated string
        val uriListString = uris.joinToString(",") { it.toString() }

        val inputData = workDataOf(
            "uriList" to uriListString,
            "maxSizeMB" to maxSizeMB,
            "compressionQuality" to (compressionQuality ?: -1),
            "maxWidth" to (maxWidth ?: -1),
            "maxHeight" to (maxHeight ?: -1),
            "compressFormat" to (compressFormat?.name ?: ""),
            "outputFileName" to outputFileName,
            "outputDir" to outputPath,
            "enableLogging" to enableLogging
        )

        val request = OneTimeWorkRequestBuilder<ImageCompressWorker>()
            .setInputData(inputData)
            .setConstraints(constraints)
            .apply { tag?.let { addTag(it) } }
            .build()

        workManager.enqueue(request)
        onStart?.invoke(0, request.id) // For batch, we return single work ID
        return request.id
    }

    companion object {
        // Helper to cancel compression by tag
        fun cancelByTag(context: Context, tag: String) {
            WorkManager.getInstance(context).cancelAllWorkByTag(tag)
        }

        // Helper to cancel specific work IDs
        fun cancelByIds(context: Context, id: UUID) {
            WorkManager.getInstance(context).cancelWorkById(id)
        }
    }

}
