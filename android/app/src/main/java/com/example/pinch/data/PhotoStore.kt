package com.example.pinch.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.InputStream
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/** Recipe photos and scanned pages, stored as JPEGs with unique file names. */
class PhotoStore(private val directory: File) {
    init {
        directory.mkdirs()
    }

    fun file(name: String) = File(directory, name)

    fun exists(name: String) = file(name).exists()

    /** Decodes, orients upright, downscales and saves an image, returning its file name. */
    fun save(context: Context, uri: Uri): String {
        val bitmap = ImageTools.decodeUpright(context, uri, MAX_DIMENSION) ?: error("Couldn't open that image")
        return try { save(bitmap) } finally { bitmap.recycle() }
    }

    fun save(bitmap: Bitmap): String {
        val name = UUID.randomUUID().toString() + ".jpg"
        val target = file(name)
        try {
            target.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 82, it)) }
        } catch (e: Exception) {
            target.delete()
            throw e
        }
        return name
    }

    fun save(bytes: ByteArray, name: String) {
        file(name).writeBytes(bytes)
    }

    fun bytes(name: String): ByteArray? = file(name).takeIf { it.exists() }?.readBytes()

    fun loadBitmap(name: String, maxDimension: Int = MAX_DIMENSION): Bitmap? =
        ImageTools.decodeFile(file(name), maxDimension)

    fun delete(name: String) {
        file(name).delete()
    }

    companion object {
        const val MAX_DIMENSION = 2400
    }
}

object ImageTools {
    /** Reads a content URI at no more than [maxDimension] pixels, rotated per its EXIF orientation. */
    fun decodeUpright(context: Context, uri: Uri, maxDimension: Int): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxDimension) }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
        val orientation = resolver.openInputStream(uri)?.use { exifOrientation(it) } ?: ExifInterface.ORIENTATION_NORMAL
        return fit(rotate(decoded, orientation), maxDimension)
    }

    fun decodeFile(file: File, maxDimension: Int): Bitmap? {
        if (!file.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return null
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxDimension) }
        return BitmapFactory.decodeFile(file.path, options)
    }

    private fun sampleSize(width: Int, height: Int, maxDimension: Int): Int {
        var sample = 1
        while (max(width, height) / (sample * 2) >= maxDimension) sample *= 2
        return sample
    }

    private fun exifOrientation(stream: InputStream): Int =
        runCatching { ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
            .getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    private fun rotate(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            else -> return bitmap
        }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated != bitmap) bitmap.recycle()
        return rotated
    }

    /** Scales down to exactly [maxDimension] on the long edge (decoding only halves). */
    fun fitWithin(bitmap: Bitmap, maxDimension: Int): Bitmap = fit(bitmap, maxDimension)

    private fun fit(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= maxDimension) return bitmap
        val ratio = maxDimension.toFloat() / longest
        val scaled = Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).roundToInt(), (bitmap.height * ratio).roundToInt(), true)
        if (scaled != bitmap) bitmap.recycle()
        return scaled
    }
}
