package com.litetv.app.cache

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import android.widget.ImageView
import com.litetv.app.R
import com.litetv.app.network.HttpClient
import kotlinx.coroutines.*

/**
 * Bounded in-memory image cache for channel logos.
 * Uses LruCache with RGB_565 format to minimize memory usage.
 *
 * Memory budget:
 * - Max 20 entries
 * - Logos downscaled to 96x96 px
 * - RGB_565 format (2 bytes/pixel vs 4 for ARGB_8888)
 * - ~18 KB per logo → ~360 KB total max
 */
class ImageCache private constructor() {

    companion object {
        private const val TAG = "ImageCache"
        private const val MAX_ENTRIES = 20
        private const val TARGET_SIZE = 96  // pixels
        private const val CACHE_SIZE_BYTES = MAX_ENTRIES * TARGET_SIZE * TARGET_SIZE * 2 // RGB_565

        @Volatile
        private var instance: ImageCache? = null

        fun getInstance(): ImageCache {
            return instance ?: synchronized(this) {
                instance ?: ImageCache().also { instance = it }
            }
        }
    }

    private val cache = object : LruCache<String, Bitmap>(CACHE_SIZE_BYTES) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int {
            return bitmap.byteCount
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val loadingUrls = HashSet<String>()

    /**
     * Load an image into an ImageView asynchronously.
     * Uses cache if available, otherwise fetches and decodes.
     */
    fun loadImage(url: String, imageView: ImageView) {
        if (url.isEmpty()) {
            imageView.setImageResource(R.drawable.ic_placeholder)
            return
        }

        // Check cache first
        val cached = cache.get(url)
        if (cached != null) {
            imageView.setImageBitmap(cached)
            return
        }

        // Set placeholder while loading
        imageView.setImageResource(R.drawable.ic_placeholder)

        // Tag the view with URL to avoid stale loads in RecyclerView
        imageView.tag = url

        // Don't duplicate in-flight requests for the same URL
        synchronized(loadingUrls) {
            if (url in loadingUrls) return
            loadingUrls.add(url)
        }

        scope.launch {
            try {
                val bitmap = fetchAndDecode(url)
                synchronized(loadingUrls) { loadingUrls.remove(url) }

                if (bitmap != null) {
                    cache.put(url, bitmap)
                    withContext(Dispatchers.Main) {
                        // Only set if the ImageView still shows the same URL
                        if (imageView.tag == url) {
                            imageView.setImageBitmap(bitmap)
                        }
                    }
                }
            } catch (e: Exception) {
                synchronized(loadingUrls) { loadingUrls.remove(url) }
                Log.e(TAG, "Failed to load image: ${url.take(80)}", e)
            }
        }
    }

    /**
     * Fetch and decode a bitmap with downsampling.
     */
    private fun fetchAndDecode(url: String): Bitmap? {
        val bytes = HttpClient.fetchBytes(url, maxSize = 512 * 1024) ?: return null  // 512 KB max per image

        // First pass: get dimensions without decoding
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)

        // Calculate downsampling
        options.apply {
            inSampleSize = calculateInSampleSize(outWidth, outHeight, TARGET_SIZE, TARGET_SIZE)
            inJustDecodeBounds = false
            inPreferredConfig = Bitmap.Config.RGB_565  // 50% less memory than ARGB_8888
        }

        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    /**
     * Calculate the optimal inSampleSize for downsampling.
     */
    private fun calculateInSampleSize(
        srcWidth: Int, srcHeight: Int,
        targetWidth: Int, targetHeight: Int
    ): Int {
        var inSampleSize = 1
        if (srcHeight > targetHeight || srcWidth > targetWidth) {
            val halfHeight = srcHeight / 2
            val halfWidth = srcWidth / 2
            while (halfHeight / inSampleSize >= targetHeight && halfWidth / inSampleSize >= targetWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    /**
     * Clear the cache and cancel pending loads.
     */
    fun clear() {
        cache.evictAll()
        synchronized(loadingUrls) { loadingUrls.clear() }
    }
}
