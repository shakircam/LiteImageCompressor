package com.image.compressor

import android.net.Uri

data class CompressionResult(
    val originalUri: Uri,
    val compressedPath: String?,
    val error: String? = null,
    val sizeBeforeKB: Long? = null,
    val sizeAfterKB: Long? = null
)
