package com.music.bitchord.data.canvas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.Uri
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.music.bitchord.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Finds the album's own lettering in its cover and keeps a cropped wordmark
 * for the release header. This is deliberately best-effort: there is no
 * reliable public catalogue of individual album-title logos. OCR preserves
 * the typography printed on the actual sleeve; it does not invent a new font.
 *
 * A user-selected image always takes precedence over the automatic crop.
 * Both are copied into app-private storage, so the choice survives restarts
 * without retaining a temporary Photo Picker URI permission.
 */
object AlbumLogoRepository {

    private const val PREFS = "album_title_logos"
    private const val DIRECTORY = "album-title-logos"
    private const val MAX_BITMAP_EDGE = 1600
    private val ignoredWords = setOf("the", "a", "an", "of", "and", "to", "in", "on", "for")

    fun manualUri(context: Context, albumId: String): Uri? {
        val path = preferences(context).getString(manualKey(albumId), null) ?: return null
        val file = File(path)
        return file.takeIf { it.isFile && it.length() > 0L }?.let { Uri.fromFile(it) }
    }

    fun cachedAutomatic(context: Context, albumId: String, artworkUrl: String?): Uri? {
        val key = automaticKey(albumId, artworkUrl)
        val path = preferences(context).getString("auto_uri_$key", null) ?: return null
        val file = File(path)
        return file.takeIf { it.isFile && it.length() > 0L }?.let { Uri.fromFile(it) }
    }

    suspend fun saveManual(context: Context, albumId: String, source: Uri): Uri? =
        withContext(Dispatchers.IO) {
            runCatching {
                val input = context.contentResolver.openInputStream(source) ?: return@runCatching null
                val mimeType = context.contentResolver.getType(source)
                val extension = when (mimeType) {
                    "image/png" -> "png"
                    "image/webp" -> "webp"
                    "image/gif" -> "gif"
                    "image/heic" -> "heic"
                    "image/heif" -> "heif"
                    else -> "jpg"
                }
                val file = File(directory(context), "manual_${digest(albumId)}.$extension")
                input.use { stream ->
                    FileOutputStream(file).use { output -> stream.copyTo(output) }
                }
                if (!file.isFile || file.length() == 0L) return@runCatching null
                preferences(context).edit().putString(manualKey(albumId), file.absolutePath).apply()
                Uri.fromFile(file)
            }.getOrNull()
        }

    fun clearManual(context: Context, albumId: String) {
        val prefs = preferences(context)
        val path = prefs.getString(manualKey(albumId), null)
        path?.let { runCatching { File(it).delete() } }
        prefs.edit().remove(manualKey(albumId)).apply()
    }

    /**
     * Tries to read the title lettering from the album's own artwork.
     * A completed OCR miss is cached; network or decoder failures are not,
     * so a temporary outage does not permanently suppress a later attempt.
     */
    suspend fun automatic(
        context: Context,
        albumId: String,
        albumTitle: String,
        artworkUrl: String?,
    ): Uri? = withContext(Dispatchers.IO) {
        if (albumId.isBlank() || albumTitle.isBlank() || artworkUrl.isNullOrBlank()) {
            return@withContext null
        }

        val key = automaticKey(albumId, artworkUrl)
        val prefs = preferences(context)
        cachedAutomatic(context, albumId, artworkUrl)?.let { return@withContext it }
        if (prefs.getBoolean("auto_done_$key", false)) return@withContext null

        val bytes = readArtwork(context, artworkUrl) ?: return@withContext null
        val bitmap = decode(bytes) ?: return@withContext null
        try {
            val blocks = runCatching { recognize(bitmap) }.getOrNull()
                ?: return@withContext null
            val bounds = bestTitleBounds(blocks, albumTitle, bitmap.width, bitmap.height)
            if (bounds == null) {
                prefs.edit().putBoolean("auto_done_$key", true).apply()
                return@withContext null
            }

            val padX = (bounds.width() * 0.08f).toInt()
            val padY = (bounds.height() * 0.22f).toInt()
            val left = (bounds.left - padX).coerceAtLeast(0)
            val top = (bounds.top - padY).coerceAtLeast(0)
            val right = (bounds.right + padX).coerceAtMost(bitmap.width)
            val bottom = (bounds.bottom + padY).coerceAtMost(bitmap.height)
            if (right <= left || bottom <= top) return@withContext null

            val crop = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
            val file = File(directory(context), "auto_$key.png")
            try {
                FileOutputStream(file).use { output ->
                    if (!crop.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                        return@withContext null
                    }
                }
            } finally {
                crop.recycle()
            }

            if (!file.isFile || file.length() == 0L) return@withContext null
            prefs.edit()
                .putString("auto_uri_$key", file.absolutePath)
                .putBoolean("auto_done_$key", true)
                .apply()
            Uri.fromFile(file)
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun recognize(bitmap: Bitmap): List<Pair<String, Rect>> {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).awaitResult()
            result.textBlocks.mapNotNull { block ->
                block.boundingBox?.let { block.text to Rect(it) }
            }
        } finally {
            recognizer.close()
        }
    }

    private fun bestTitleBounds(
        blocks: List<Pair<String, Rect>>,
        title: String,
        imageWidth: Int,
        imageHeight: Int,
    ): Rect? {
        val titleCore = title.substringBefore(" (").substringBefore(" [")
        val target = normalize(titleCore)
        if (target.isBlank()) return null
        val tokens = Regex("[\\p{L}\\p{N}]+")
            .findAll(titleCore)
            .map { normalize(it.value) }
            .filter { it.length > 1 && it !in ignoredWords }
            .toSet()

        val best = blocks.mapNotNull { (text, rect) ->
            val candidate = normalize(text)
            if (candidate.isBlank()) return@mapNotNull null
            val exact = candidate == target || candidate.contains(target)
            val reverse = target.contains(candidate) && candidate.length >= (target.length * 0.55f)
            val matched = tokens.count(candidate::contains)
            val coverage = if (tokens.isEmpty()) 0f else matched.toFloat() / tokens.size
            val score = when {
                exact -> 1f
                reverse -> 0.9f
                coverage >= 0.75f -> coverage
                else -> 0f
            }
            if (score < 0.72f) return@mapNotNull null
            val box = Rect(rect)
            if (box.width() < 8 || box.height() < 5) return@mapNotNull null
            if (box.width() > imageWidth * 0.92f || box.height() > imageHeight * 0.55f) {
                return@mapNotNull null
            }
            Triple(score, box, box.width().toLong() * box.height())
        }.maxWithOrNull(compareBy<Triple<Float, Rect, Long>> { it.first }.thenBy { it.third })

        return best?.second
    }

    private fun readArtwork(context: Context, value: String): ByteArray? = runCatching {
        if (value.startsWith("http://") || value.startsWith("https://")) {
            val request = Request.Builder()
                .url(value)
                .header("User-Agent", "BitChord/AlbumLogo")
                .build()
            Http.client.newCall(request).execute().use { response ->
                if (response.isSuccessful) response.body?.bytes() else null
            }
        } else {
            val uri = Uri.parse(value)
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: uri.path?.let { path -> File(path).takeIf { it.isFile }?.readBytes() }
        }
    }.getOrNull()

    private fun decode(bytes: ByteArray): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > MAX_BITMAP_EDGE) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }.getOrNull()

    private fun normalize(value: String): String =
        Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .filter { it.isLetterOrDigit() }

    private fun directory(context: Context): File =
        File(context.filesDir, DIRECTORY).apply { mkdirs() }

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun manualKey(albumId: String) = "manual_${digest(albumId)}"

    private fun automaticKey(albumId: String, artworkUrl: String?) =
        digest("$albumId\n${artworkUrl.orEmpty()}")

    private fun digest(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private suspend fun <T> Task<T>.awaitResult(): T =
        suspendCancellableCoroutine { continuation ->
            addOnSuccessListener { value ->
                if (continuation.isActive) continuation.resume(value)
            }
            addOnFailureListener { error ->
                if (continuation.isActive) continuation.resumeWithException(error)
            }
            addOnCanceledListener { continuation.cancel() }
        }
}
