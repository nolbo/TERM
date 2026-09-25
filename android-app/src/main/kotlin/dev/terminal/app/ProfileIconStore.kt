package dev.terminal.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import dev.terminal.runtime.session.SessionProfileIcon
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.roundToInt

/** Library removal hides immutable images while retaining bytes for saved profiles/live snapshots. */
internal class ProfileIconStore(context: Context) {
    private val app = context.applicationContext
    private val directory = File(app.filesDir, "profile-icons")

    fun import(uri: Uri): String = app.contentResolver.openInputStream(uri).use {
        requireNotNull(it) { "Cannot open image" }
        import(it)
    }

    fun import(input: InputStream): String {
        val bytes = ByteArrayOutputStream().use { output ->
            val buffer = ByteArray(8192)
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                require(output.size() + size <= MAX_BYTES) { "Image exceeds 8 MiB" }
                output.write(buffer, 0, size)
            }
            output.toByteArray()
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outMimeType in setOf("image/png", "image/jpeg", "image/webp")) { "Use PNG, WebP or JPEG" }
        require(bounds.outWidth in 1..8192 && bounds.outHeight in 1..8192 &&
            bounds.outWidth.toLong() * bounds.outHeight <= 16_777_216) { "Image exceeds 16 megapixels" }
        val source = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })) { "Invalid image" }
        val normalized = try { normalize(source) } finally { source.recycle() }
        try {
            val png = ByteArrayOutputStream().use { out ->
                check(normalized.bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
                out.toByteArray()
            }
            val digest = MessageDigest.getInstance("SHA-256").digest(png).joinToString("") { "%02x".format(it) }
            val id = "image:$digest:${if (normalized.tintable) "mask" else "color"}"
            check(directory.mkdirs() || directory.isDirectory)
            val target = requireNotNull(file(id))
            if (!target.exists()) {
                val temporary = File.createTempFile("icon-", ".tmp", directory)
                try {
                    temporary.writeBytes(png)
                    check(temporary.renameTo(target)) { "Could not store icon" }
                } finally { temporary.delete() }
            }
            val removed = removalMarker(target)
            check(!removed.exists() || removed.delete()) { "Could not restore icon to library" }
            return id
        } finally { normalized.bitmap.recycle() }
    }

    fun file(id: String): File? = if (SessionProfileIcon.isImage(id))
        File(directory, id.removePrefix("image:").replace(':', '-') + ".png") else null

    /** Do not invalidate profile IDs or the icon captured by a running session. */
    fun removeFromLibrary(id: String) {
        val target = requireNotNull(file(id)) { "Only imported icons can be removed" }
        require(target.isFile) { "Imported icon does not exist" }
        val marker = removalMarker(target)
        check(marker.createNewFile() || marker.isFile) { "Could not remove icon from library" }
    }

    private fun removalMarker(target: File) = File(directory, target.name + ".removed")

    fun all(): List<String> = directory.listFiles().orEmpty().mapNotNull {
        val id = "image:" + it.name.removeSuffix(".png").replace('-', ':')
        id.takeIf { _ -> it.isFile && it.extension == "png" && SessionProfileIcon.isImage(id) && !removalMarker(it).exists() }
    }.sorted()

    companion object {
        private const val MAX_BYTES = 8 * 1024 * 1024
        private const val SIZE = 128
        private const val CONTENT_SIZE = 112
        internal data class Normalized(val bitmap: Bitmap, val tintable: Boolean)

        /** Crop transparent padding and fit visible content inside a common optical inset. */
        internal fun normalize(source: Bitmap): Normalized {
            var left = source.width; var top = source.height; var right = -1; var bottom = -1
            var transparent = false; var monochrome = true; var rgb: Int? = null
            val row = IntArray(source.width)
            for (y in 0 until source.height) {
                source.getPixels(row, 0, source.width, 0, y, source.width, 1)
                row.forEachIndexed { x, pixel ->
                    val alpha = pixel ushr 24
                    if (alpha == 0) transparent = true else {
                        left = minOf(left, x); top = minOf(top, y)
                        right = maxOf(right, x); bottom = maxOf(bottom, y)
                        val color = pixel and 0xffffff
                        if (rgb == null) rgb = color else if (rgb != color) monochrome = false
                    }
                }
            }
            require(right >= left && bottom >= top) { "Image is fully transparent" }
            val width = right - left + 1; val height = bottom - top + 1
            val scale = CONTENT_SIZE.toFloat() / max(width, height)
            val w = max(1, (width * scale).roundToInt()); val h = max(1, (height * scale).roundToInt())
            val result = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
            Canvas(result).drawBitmap(source, Rect(left, top, right + 1, bottom + 1),
                Rect((SIZE - w) / 2, (SIZE - h) / 2, (SIZE - w) / 2 + w, (SIZE - h) / 2 + h),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            return Normalized(result, transparent && monochrome)
        }
    }
}
