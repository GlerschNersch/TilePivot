package dev.x360reborn

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.File

/**
 * The player profile shown in the clock strip: a name (kept in the "hub" prefs, so it
 * rides along in settings backups) and a picture the user chose from their own photos.
 * The picture is cropped to a square, scaled to [PX] and kept as a private PNG; it is
 * not part of the JSON backup. No permission is involved: the system photo picker hands
 * over one image, which is copied straight away.
 */
class Profile(private val context: Context) {
    private val prefs = context.getSharedPreferences("hub", Context.MODE_PRIVATE)
    private val file get() = File(context.filesDir, FILE)

    var name: String
        get() = prefs.getString(NAME, "")!!
        set(v) = prefs.edit().putString(NAME, clean(v)).apply()

    /** The saved picture, or null for the drawn placeholder. Reads the disk: call off the main thread. */
    fun loadPicture(): Bitmap? = file.takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.path) }

    /** Imports [uri] as the picture; returns it, or null when the image can't be read. */
    fun setPicture(uri: Uri): Bitmap? {
        val src = try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { d, info, _ ->
                // Decode no bigger than needed: the short side lands near PX.
                val short = minOf(info.size.width, info.size.height)
                val sample = maxOf(1, short / PX)
                d.setTargetSize(info.size.width / sample, info.size.height / sample)
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } catch (e: Exception) {
            return null
        }
        val (x, y, side) = squareCrop(src.width, src.height)
        val square = Bitmap.createScaledBitmap(Bitmap.createBitmap(src, x, y, side, side), PX, PX, true)
        val tmp = File(context.filesDir, "$FILE.tmp")
        return try {
            tmp.outputStream().use { square.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (!tmp.renameTo(file)) return null
            square
        } catch (e: Exception) {
            tmp.delete()
            null
        }
    }

    fun clearPicture() { file.delete() }

    companion object {
        /** Stored picture edge, in px: comfortably over the chip's on-screen size. */
        const val PX = 256
        const val MAX_NAME = 24
        private const val FILE = "profile.png"
        private const val NAME = "profileName"

        /** A name as stored: trimmed, single-line, at most [MAX_NAME] characters. */
        fun clean(raw: String): String =
            raw.replace(Regex("\\s+"), " ").trim().take(MAX_NAME).trim()

        /** The centred square crop of a [w]x[h] image: (left, top, side). */
        fun squareCrop(w: Int, h: Int): Triple<Int, Int, Int> {
            val side = minOf(w, h)
            return Triple((w - side) / 2, (h - side) / 2, side)
        }
    }
}
