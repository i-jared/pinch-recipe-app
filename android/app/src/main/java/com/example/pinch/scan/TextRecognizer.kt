package com.example.pinch.scan

import android.graphics.Bitmap
import com.example.pinch.parsing.Box
import com.example.pinch.parsing.LineLayout
import com.example.pinch.parsing.OcrFragment
import com.example.pinch.parsing.TextLine
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * On-device text recognition with ML Kit's bundled Latin model: no network, no Play
 * services download, nothing leaves the phone.
 */
object TextRecognizer {
    private val client by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    suspend fun recognize(bitmap: Bitmap, page: Int): List<TextLine> {
        val result = client.process(InputImage.fromBitmap(bitmap, 0)).await()
        val width = bitmap.width.toDouble()
        val height = bitmap.height.toDouble()
        val fragments = result.textBlocks.flatMap { block ->
            block.lines.mapNotNull { line ->
                val r = line.boundingBox ?: return@mapNotNull null
                OcrFragment(line.text, Box(r.left / width, r.top / height, r.width() / width, r.height() / height))
            }
        }
        return LineLayout.lines(fragments, page)
    }
}

/** Awaits a Play services task without pulling in another dependency. */
suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { continuation.resume(it) }
    addOnFailureListener { continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}
