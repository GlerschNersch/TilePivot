package dev.x360reborn

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import dev.x360reborn.home.GAME_ART
import dev.x360reborn.home.GameInfo
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Xbox 360 game cover art, keyed like icons ([GAME_ART] + game uri). Art comes from an
 * image the user put beside the game, or else the thumbnail in a Games on Demand / XBLA
 * package's own header (see [GameScanner.stfsThumbnail]). Both are read as streams from
 * the picked folder through [open]. Decoding happens off the main thread; until a cover is
 * ready the tile keeps its drawn controller glyph.
 */
class Covers(
    /** Opens a document uri for reading (ContentResolver in the app); null on failure. */
    private val open: (String) -> InputStream?,
    /** A cover finished decoding (called off the main thread). */
    private val onReady: () -> Unit,
) {
    /** Largest edge the hub draws a cover at; big box-art files are subsampled to about this. */
    @Volatile var edgePx = 448

    @Volatile private var games: Map<String, GameInfo> = emptyMap()

    private val cache = object : LruCache<String, Bitmap>(BUDGET_KB) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount / 1024
    }
    private val pending = ConcurrentHashMap.newKeySet<String>()
    /** Keys whose art could not be decoded; not retried until the next scan. */
    private val failed = ConcurrentHashMap.newKeySet<String>()
    private val worker = Executors.newSingleThreadExecutor()

    /** The latest scan: drops art for games that are gone or whose cover source changed. */
    fun update(list: List<GameInfo>) {
        val next = list.filter { it.hasArt }.associateBy { GAME_ART + it.uri }
        val old = games
        for (key in cache.snapshot().keys) if (next[key] != old[key]) cache.remove(key)
        failed.clear()
        games = next
    }

    fun get(key: String): Bitmap? {
        cache.get(key)?.let { return it }
        val game = games[key] ?: return null
        if (key in failed || !pending.add(key)) return null
        try {
            worker.execute {
                try {
                    val bmp = decode(game)
                    if (bmp != null) cache.put(key, bmp) else failed += key
                } catch (e: Throwable) {
                    failed += key // unreadable file, or too big to decode: keep the glyph
                } finally {
                    pending.remove(key)
                }
                onReady()
            }
        } catch (e: RejectedExecutionException) {
            pending.remove(key)
        }
        return null
    }

    private fun decode(game: GameInfo): Bitmap? {
        game.cover?.let { uri ->
            // Two passes (bounds, then pixels), so the document is opened twice.
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            // A bounds-only decode always returns null; success shows in outWidth/outHeight.
            val input = open(uri) ?: return null
            input.use { BitmapFactory.decodeStream(it, null, opts) }
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
            opts.inJustDecodeBounds = false
            opts.inSampleSize = sampleSize(opts.outWidth, opts.outHeight, edgePx)
            return open(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        }
        val png = GameScanner.stfsThumbnail { open(game.uri) } ?: return null
        return BitmapFactory.decodeByteArray(png, 0, png.size)
    }

    fun close() = worker.shutdownNow()

    companion object {
        /** Covers are few (one per game); 24 MB holds dozens even at full tile size. */
        private const val BUDGET_KB = 24 * 1024

        /** Largest power-of-two subsample that keeps the short edge at or above [edge]. */
        fun sampleSize(w: Int, h: Int, edge: Int): Int {
            var n = 1
            while (minOf(w, h) / (n * 2) >= edge) n *= 2
            return n
        }
    }
}
