package com.image.compressor.utils

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log

 fun getFileSizeKB(context: Context, uri: Uri): Long {
    return try {
        val inputStream = context.contentResolver.openInputStream(uri)
        val size = inputStream?.available()?.toLong() ?: 0L
        inputStream?.close()
        size / 1024 // Convert to KB
    } catch (e: Exception) {
        Log.e("Utils", "Error getting file size: ${e.message}")
        0L
    }
}

fun getFileSizeMB(context: Context, uri: Uri): Double {
    return context.contentResolver.openInputStream(uri)?.use {
        it.available().toDouble() / (1024 * 1024)
    } ?: 0.0
}

fun getImageInfo(context: Context, uri: Uri): Triple<Int, Int, String> {
    val options = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }
    context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, options)
    }
    val mimeType = options.outMimeType ?: "Unknown"
    return Triple(options.outWidth, options.outHeight, mimeType)
}

fun getImageInfoFromFile(path: String): Triple<Int, Int, String> {
    val options = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }
    BitmapFactory.decodeFile(path, options)
    val mimeType = options.outMimeType ?: "unknown"
    return Triple(options.outWidth, options.outHeight, mimeType)
}