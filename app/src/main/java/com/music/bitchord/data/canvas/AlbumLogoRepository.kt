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
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Finds an album's own wordmark, preferring a verified transparent asset from
 * Wikimedia Commons and falling back to conservative extraction from the cover.
 *
 * A Commons result is accepted only when its metadata identifies the exact
 * album edition and lead artist, its license is public-domain/CC0, and the
 * downloaded image has a genuinely transparent background. OCR is stricter
 * still: every meaningful word in the album title (including edition words)
 * must be present, and a flat background must be separable from the lettering.
 *
 * A miss is preferable to a guessed logo. User-selected artwork always takes
 * precedence and is kept separately from the automatic cache.
 */
object AlbumLogoRepository {

    private const val PREFS = "album_title_logos"
    private const val DIRECTORY = "album-title-logos"
    private const val CACHE_VERSION = 3
    private const val MAX_BITMAP_EDGE = 1800
    private const val MIN_WORDMARK_WIDTH = 96
    private const val MIN_WORDMARK_HEIGHT = 16
    private const val NEGATIVE_CACHE_TTL_MS = 30L * 24L * 60L * 60L * 1000L
    private const val MIN_LOGO_ASPECT = 0.75f
    private const val MAX_LOGO_ASPECT = 16f

    private val ignoredWords = setOf("the", "a", "an", "of", "and", "to", "in", "on", "for")

    private data class CommonsLogoCandidate(
        val title: String,
        val imageUrl: String,
        val license: String,
        val exactPhrase: Boolean,
        val area: Long,
    )

    fun manualUri(context: Context, albumId: String): Uri? {
        val path = preferences(context).getString(manualKey(albumId), null) ?: return null
        val file = File(path)
        return file.takeIf { it.isFile && it.length() > 0L }?.let { Uri.fromFile(it) }
    }

    fun cachedAutomatic(
        context: Context,
        albumId: String,
        albumTitle: String,
        artistName: String,
        artworkUrl: String?,
    ): Uri? {
        ensureCacheVersion(context)
        val key = automaticKey(albumId, albumTitle, artistName, artworkUrl)
        val prefs = preferences(context)
        val path = prefs.getString("auto_uri_" + key, null) ?: return null
        val file = File(path)
        if (!file.isFile || file.length() == 0L) return null

        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return invalidateAutomatic(context, key, file)
        val valid = bitmap.hasAlpha() &&
            bitmap.width >= MIN_WORDMARK_WIDTH &&
            bitmap.height >= MIN_WORDMARK_HEIGHT &&
            (bitmap.width.toFloat() / bitmap.height.toFloat()) in MIN_LOGO_ASPECT..MAX_LOGO_ASPECT
        bitmap.recycle()
        if (!valid) return invalidateAutomatic(context, key, file)
        return Uri.fromFile(file)
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
                val file = File(directory(context), "manual_" + digest(albumId) + "." + extension)
                input.use { stream ->
                    FileOutputStream(file).use { output -> stream.copyTo(output) }
                }
                if (!file.isFile || file.length() == 0L) return@runCatching null
                val oldPath = preferences(context).getString(manualKey(albumId), null)
                preferences(context).edit().putString(manualKey(albumId), file.absolutePath).apply()
                oldPath?.takeIf { it != file.absolutePath }?.let { runCatching { File(it).delete() } }
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
     * Finds an automatic wordmark. Positive and negative results are cached;
     * transient network/decoder failures are not, so an outage cannot become
     * a permanent "no logo" decision.
     */
    suspend fun automatic(
        context: Context,
        albumId: String,
        albumTitle: String,
        artistName: String,
        artworkUrl: String?,
    ): Uri? = withContext(Dispatchers.IO) {
        if (albumId.isBlank() || albumTitle.isBlank()) {
            return@withContext null
        }

        ensureCacheVersion(context)
        val key = automaticKey(albumId, albumTitle, artistName, artworkUrl)
        cachedAutomatic(context, albumId, albumTitle, artistName, artworkUrl)?.let {
            return@withContext it
        }

        val prefs = preferences(context)
        val doneAt = prefs.getLong("auto_done_at_" + key, 0L)
        if (doneAt > 0L && System.currentTimeMillis() - doneAt < NEGATIVE_CACHE_TTL_MS) {
            return@withContext null
        }

        val commonsResult = findCommonsLogo(context, key, albumTitle, artistName)
        val commonsLogo = commonsResult.getOrNull()
        if (commonsLogo != null) {
            return@withContext storeAutomatic(context, key, commonsLogo, "Wikimedia Commons", "Public domain / CC0")
        }
        val commonsUnavailable = commonsResult.isFailure

        if (artworkUrl.isNullOrBlank()) {
            if (!commonsUnavailable) markAutomaticMiss(prefs, key)
            return@withContext null
        }
        val bytes = readArtwork(context, artworkUrl) ?: return@withContext null
        val bitmap = decode(bytes) ?: return@withContext null
        try {
            val blocks = runCatching { recognize(bitmap) }.getOrNull() ?: return@withContext null
            val bounds = bestTitleBounds(blocks, albumTitle, bitmap.width, bitmap.height)
            if (bounds == null) {
                if (!commonsUnavailable) markAutomaticMiss(prefs, key)
                return@withContext null
            }

            val logo = runCatching { extractTransparentWordmark(bitmap, bounds) }.getOrNull()
            if (logo == null) {
                if (!commonsUnavailable) markAutomaticMiss(prefs, key)
                return@withContext null
            }

            storeAutomatic(context, key, logo, "Cover wordmark extraction", "Derived from supplied album artwork")
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Pure title-matching rule, exposed internally so edition matching can be
     * regression-tested without Android bitmap or OCR dependencies.
     */
    internal fun strictTitleMatchScore(candidateText: String, albumTitle: String): Float {
        val candidate = normalize(candidateText)
        val target = normalize(albumTitle)
        val tokens = titleTokens(albumTitle)
        if (candidate.isBlank() || target.isBlank() || tokens.isEmpty()) return 0f
        if (candidate.length > target.length * 1.25f + 4f) return 0f
        if (candidate == target || candidate.contains(target)) return 1f
        val coverage = tokens.count(candidate::contains).toFloat() / tokens.size
        return if (coverage >= 0.95f) 0.95f else 0f
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
        val best = blocks.mapNotNull { (text, rect) ->
            val score = strictTitleMatchScore(text, title)
            if (score < 0.9f) return@mapNotNull null

            val box = Rect(rect)
            val minimumWidth = maxOf(MIN_WORDMARK_WIDTH, (imageWidth * 0.055f).roundToInt())
            if (box.width() < minimumWidth || box.height() < 12) return@mapNotNull null
            if (box.width() > imageWidth * 0.92f || box.height() > imageHeight * 0.30f) {
                return@mapNotNull null
            }
            val aspect = box.width().toFloat() / box.height().toFloat()
            if (aspect !in MIN_LOGO_ASPECT..MAX_LOGO_ASPECT) return@mapNotNull null
            Triple(score, box, box.width().toLong() * box.height())
        }.minWithOrNull(
            compareByDescending<Triple<Float, Rect, Long>> { it.first }.thenBy { it.third },
        )
        return best?.second
    }

    private fun findCommonsLogo(
        context: Context,
        cacheKey: String,
        albumTitle: String,
        artistName: String,
    ): Result<Bitmap?> = runCatching {
        if (artistName.isBlank()) return@runCatching null

        val query = "\"" + albumTitle + "\" \"" + artistName + "\""
        val apiUri = Uri.parse("https://commons.wikimedia.org/w/api.php").buildUpon()
            .appendQueryParameter("action", "query")
            .appendQueryParameter("format", "json")
            .appendQueryParameter("generator", "search")
            .appendQueryParameter("gsrnamespace", "6")
            .appendQueryParameter("gsrsearch", query)
            .appendQueryParameter("gsrlimit", "50")
            .appendQueryParameter("prop", "imageinfo|categories")
            .appendQueryParameter("iiprop", "url|size|mime|extmetadata")
            .appendQueryParameter("iiurlwidth", "1600")
            .appendQueryParameter("cllimit", "50")
            .build()

        val request = Request.Builder()
            .url(apiUri.toString())
            .header("User-Agent", "BitChord Android (https://github.com/folkxromania/BitChord)")
            .build()
        val jsonText = Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Wikimedia Commons returned " + response.code)
            response.body?.string() ?: throw IOException("Wikimedia Commons returned an empty response")
        }

        val pages = JSONObject(jsonText).optJSONObject("query")?.optJSONObject("pages")
            ?: return@runCatching null
        val albumTokens = titleTokens(albumTitle)
        val artistTokens = titleTokens(artistName)
        val candidates = mutableListOf<CommonsLogoCandidate>()
        val pageKeys = pages.keys()

        while (pageKeys.hasNext()) {
            val page = pages.optJSONObject(pageKeys.next()) ?: continue
            val imageInfo = page.optJSONArray("imageinfo")?.optJSONObject(0) ?: continue
            val metadata = imageInfo.optJSONObject("extmetadata") ?: continue
            val description = metadataValue(metadata, "ImageDescription")
            val objectName = metadataValue(metadata, "ObjectName")
            val creditedArtist = metadataValue(metadata, "Artist")
            val source = metadataValue(metadata, "Source")
            val license = metadataValue(metadata, "LicenseShortName")
            val licenseUrl = metadataValue(metadata, "LicenseUrl")
            val categories = page.optJSONArray("categories")
            val categoryText = buildString {
                if (categories != null) {
                    for (index in 0 until categories.length()) {
                        append(categories.optJSONObject(index)?.optString("title").orEmpty())
                        append(' ')
                    }
                }
            }

            // Keep album evidence separate from artist evidence. Otherwise a
            // title token appearing only in a source URL or artist field could
            // make a different edition look like a match.
            val titleEvidence = normalize(
                listOf(page.optString("title"), description, objectName).joinToString(" "),
            )
            val artistEvidence = normalize(
                listOf(creditedArtist, source, categoryText, description).joinToString(" "),
            )
            if (albumTokens.any { it !in titleEvidence }) continue
            if (artistTokens.isNotEmpty() && artistTokens.any { it !in artistEvidence }) continue
            if (!isAllowedCommonsLicense(license, licenseUrl)) continue

            val mime = imageInfo.optString("mime")
            if (mime !in setOf("image/png", "image/webp", "image/svg+xml")) continue
            val width = imageInfo.optInt("width")
            val height = imageInfo.optInt("height")
            if (width < 220 || height < 36) continue
            val aspect = width.toFloat() / height.toFloat()
            if (aspect !in 1.1f..14f) continue

            val imageUrl = imageInfo.optString("thumburl").ifBlank { imageInfo.optString("url") }
            if (!imageUrl.startsWith("https://upload.wikimedia.org/")) continue
            val phrase = normalize(page.optString("title") + " " + description + " " + objectName)
            candidates += CommonsLogoCandidate(
                title = page.optString("title"),
                imageUrl = imageUrl,
                license = license,
                exactPhrase = phrase.contains(normalize(albumTitle)),
                area = width.toLong() * height.toLong(),
            )
        }

        val ordered = candidates.sortedWith(
            compareByDescending<CommonsLogoCandidate> { it.exactPhrase }.thenByDescending { it.area },
        )
        for (candidate in ordered) {
            val bytes = readArtwork(context, candidate.imageUrl) ?: continue
            val logo = decodeTransparentLogo(bytes) ?: continue
            preferences(context).edit()
                .putString("auto_source_" + cacheKey, candidate.title)
                .putString("auto_license_" + cacheKey, candidate.license)
                .apply()
            return@runCatching logo
        }
        null
    }

    private fun metadataValue(metadata: JSONObject, key: String): String =
        metadata.optJSONObject(key)?.optString("value").orEmpty()
            .replace(Regex("<[^>]*>"), " ")
            .replace("&amp;", "&")
            .replace("&nbsp;", " ")

    private fun isAllowedCommonsLicense(license: String, licenseUrl: String): Boolean {
        val name = license.lowercase(Locale.ROOT)
        val url = licenseUrl.lowercase(Locale.ROOT)
        return name.contains("public domain") ||
            name.contains("cc0") ||
            url.contains("publicdomain") ||
            url.contains("zero/1.0")
    }

    private fun decodeTransparentLogo(bytes: ByteArray): Bitmap? {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        try {
            if (!bitmap.hasAlpha() || bitmap.width < 220 || bitmap.height < 36) return null
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            if (transparentBorderRatio(pixels, bitmap.width, bitmap.height) < 0.55f) return null

            val bounds = alphaBounds(pixels, bitmap.width, bitmap.height, 16) ?: return null
            if (bounds.width() < MIN_WORDMARK_WIDTH || bounds.height() < MIN_WORDMARK_HEIGHT) return null
            val aspect = bounds.width().toFloat() / bounds.height().toFloat()
            if (aspect !in 1.1f..MAX_LOGO_ASPECT) return null

            val inkRatio = pixels.count { (it ushr 24) > 64 }.toFloat() / pixels.size
            if (inkRatio !in 0.004f..0.90f) return null
            return Bitmap.createBitmap(bitmap, bounds.left, bounds.top, bounds.width(), bounds.height())
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Removes a nearly uniform cover background using the colour distribution
     * around the OCR box. Complex photographic backgrounds fail the uniformity
     * gate and therefore never become a fake "transparent" logo.
     */
    private fun extractTransparentWordmark(source: Bitmap, textBounds: Rect): Bitmap? {
        val padX = maxOf(12, (textBounds.width() * 0.22f).roundToInt())
        val padY = maxOf(8, (textBounds.height() * 0.55f).roundToInt())
        val cropRect = Rect(
            (textBounds.left - padX).coerceAtLeast(0),
            (textBounds.top - padY).coerceAtLeast(0),
            (textBounds.right + padX).coerceAtMost(source.width),
            (textBounds.bottom + padY).coerceAtMost(source.height),
        )
        if (cropRect.width() < MIN_WORDMARK_WIDTH || cropRect.height() < MIN_WORDMARK_HEIGHT) return null

        val crop = Bitmap.createBitmap(
            source,
            cropRect.left,
            cropRect.top,
            cropRect.width(),
            cropRect.height(),
        )
        try {
            val width = crop.width
            val height = crop.height
            val pixels = IntArray(width * height)
            crop.getPixels(pixels, 0, width, 0, 0, width, height)

            val border = borderPixels(pixels, width, height)
            val background = medianColor(border)
            val deviations = border.map { rgbDistance(it, background) }.sorted()
            val spread = deviations[(deviations.lastIndex * 9 / 10).coerceAtLeast(0)]
            if (spread > 42f) return null

            val transparentThreshold = maxOf(18f, spread + 8f)
            val opaqueThreshold = transparentThreshold + 38f
            val outputPixels = IntArray(pixels.size)
            val localTextBounds = Rect(textBounds).apply {
                offset(-cropRect.left, -cropRect.top)
            }

            var textInk = 0
            var textArea = 0
            var outputInk = 0
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val index = y * width + x
                    val distance = rgbDistance(pixels[index], background)
                    if (localTextBounds.contains(x, y)) {
                        textArea++
                        if (distance > transparentThreshold + 6f) textInk++
                    }
                    val alpha = (
                        (distance - transparentThreshold) /
                            (opaqueThreshold - transparentThreshold) * 255f
                        ).roundToInt().coerceIn(0, 255)
                    if (alpha > 36) outputInk++
                    outputPixels[index] = (alpha shl 24) or (pixels[index] and 0x00ffffff)
                }
            }

            val textCoverage = if (textArea == 0) 0f else textInk.toFloat() / textArea
            val outputCoverage = outputInk.toFloat() / outputPixels.size
            if (textCoverage !in 0.012f..0.75f || outputCoverage !in 0.003f..0.75f) return null

            val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            output.setPixels(outputPixels, 0, width, 0, 0, width, height)
            val content = alphaBounds(outputPixels, width, height, 24)
            if (content == null || content.width() < MIN_WORDMARK_WIDTH ||
                content.height() < MIN_WORDMARK_HEIGHT
            ) {
                output.recycle()
                return null
            }

            val trimmed = Rect(
                (content.left - 2).coerceAtLeast(0),
                (content.top - 2).coerceAtLeast(0),
                (content.right + 2).coerceAtMost(width),
                (content.bottom + 2).coerceAtMost(height),
            )
            val result = Bitmap.createBitmap(output, trimmed.left, trimmed.top, trimmed.width(), trimmed.height())
            output.recycle()
            return result
        } finally {
            crop.recycle()
        }
    }

    private fun transparentBorderRatio(pixels: IntArray, width: Int, height: Int): Float {
        val border = borderPixels(pixels, width, height)
        if (border.isEmpty()) return 0f
        return border.count { (it ushr 24) < 24 }.toFloat() / border.size
    }

    private fun borderPixels(pixels: IntArray, width: Int, height: Int): IntArray {
        if (width <= 0 || height <= 0) return intArrayOf()
        val result = IntArray(width * 2 + height * 2)
        var index = 0
        for (x in 0 until width) {
            result[index++] = pixels[x]
            result[index++] = pixels[(height - 1) * width + x]
        }
        for (y in 0 until height) {
            result[index++] = pixels[y * width]
            result[index++] = pixels[y * width + width - 1]
        }
        return result
    }

    private fun alphaBounds(pixels: IntArray, width: Int, height: Int, threshold: Int): Rect? {
        var left = width
        var top = height
        var right = -1
        var bottom = -1
        for (y in 0 until height) {
            for (x in 0 until width) {
                if ((pixels[y * width + x] ushr 24) <= threshold) continue
                left = minOf(left, x)
                top = minOf(top, y)
                right = maxOf(right, x)
                bottom = maxOf(bottom, y)
            }
        }
        return if (right < left || bottom < top) null else Rect(left, top, right + 1, bottom + 1)
    }

    private fun medianColor(pixels: IntArray): Int {
        if (pixels.isEmpty()) return 0xff000000.toInt()
        val red = IntArray(pixels.size) { (pixels[it] shr 16) and 0xff }.apply { sort() }
        val green = IntArray(pixels.size) { (pixels[it] shr 8) and 0xff }.apply { sort() }
        val blue = IntArray(pixels.size) { pixels[it] and 0xff }.apply { sort() }
        val middle = pixels.size / 2
        return (0xff shl 24) or
            (red[middle] shl 16) or
            (green[middle] shl 8) or
            blue[middle]
    }

    private fun rgbDistance(first: Int, second: Int): Float {
        val red = ((first shr 16) and 0xff) - ((second shr 16) and 0xff)
        val green = ((first shr 8) and 0xff) - ((second shr 8) and 0xff)
        val blue = (first and 0xff) - (second and 0xff)
        return sqrt((red * red + green * green + blue * blue).toFloat())
    }

    private fun storeAutomatic(
        context: Context,
        key: String,
        bitmap: Bitmap,
        source: String,
        license: String,
    ): Uri? {
        try {
            val file = File(directory(context), "auto_" + key + ".png")
            FileOutputStream(file).use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) return null
            }
            if (!file.isFile || file.length() == 0L) return null
            preferences(context).edit()
                .putString("auto_uri_" + key, file.absolutePath)
                .putString("auto_source_" + key, source)
                .putString("auto_license_" + key, license)
                .remove("auto_done_at_" + key)
                .apply()
            return Uri.fromFile(file)
        } finally {
            bitmap.recycle()
        }
    }

    private fun markAutomaticMiss(prefs: android.content.SharedPreferences, key: String) {
        prefs.edit().putLong("auto_done_at_" + key, System.currentTimeMillis()).apply()
    }

    private fun invalidateAutomatic(context: Context, key: String, file: File): Uri? {
        runCatching { file.delete() }
        preferences(context).edit()
            .remove("auto_uri_" + key)
            .remove("auto_source_" + key)
            .remove("auto_license_" + key)
            .remove("auto_done_at_" + key)
            .apply()
        return null
    }

    private fun readArtwork(context: Context, value: String): ByteArray? = runCatching {
        if (value.startsWith("http://") || value.startsWith("https://")) {
            val request = Request.Builder()
                .url(value)
                .header("User-Agent", "BitChord Android (https://github.com/folkxromania/BitChord)")
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
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        var sample = 1
        while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > MAX_BITMAP_EDGE) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }.getOrNull()

    private fun titleTokens(value: String): List<String> =
        Regex("[\\p{L}\\p{N}]+")
            .findAll(value)
            .map { normalize(it.value) }
            .filter { it.length > 1 && it !in ignoredWords }
            .distinct()
            .toList()

    private fun normalize(value: String): String =
        Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .filter { it.isLetterOrDigit() }

    private fun ensureCacheVersion(context: Context) {
        val prefs = preferences(context)
        if (prefs.getInt("cache_version", 0) == CACHE_VERSION) return
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith("auto_") }.forEach(editor::remove)
        directory(context).listFiles()
            ?.filter { it.name.startsWith("auto_") }
            ?.forEach { runCatching { it.delete() } }
        editor.putInt("cache_version", CACHE_VERSION).apply()
    }

    private fun directory(context: Context): File =
        File(context.filesDir, DIRECTORY).apply { mkdirs() }

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun manualKey(albumId: String) = "manual_" + digest(albumId)

    private fun automaticKey(
        albumId: String,
        albumTitle: String,
        artistName: String,
        artworkUrl: String?,
    ): String = digest(
        CACHE_VERSION.toString() + "\n" +
            albumId + "\n" +
            albumTitle + "\n" +
            artistName + "\n" +
            artworkUrl.orEmpty(),
    )

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
