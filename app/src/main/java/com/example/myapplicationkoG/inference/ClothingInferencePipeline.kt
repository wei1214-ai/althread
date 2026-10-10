package com.example.myapplicationkoG.inference

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import com.example.myapplicationkoG.CutoutRepository
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Result of the cloud cutout pipeline for a single garment photo.
 * Only the final cutout bitmap matters for now.
 */
data class InferenceResult(
    val cutout: Bitmap,
)

/**
 * Cloud cutout pipeline: photo file → downscale → JPEG80 → `cutout` Edge Function
 * → merge garment masks → fill holes → cut out and center on a 1080x1080 canvas.
 *
 * Requires network; callers surface failures instead of silently skipping.
 *
 * Runs on Dispatchers.IO (CutoutRepository switches internally).
 */
class ClothingInferencePipeline(context: Context) {

    private val appContext = context.applicationContext
    private val cutoutRepo = CutoutRepository()

    suspend fun run(file: File): InferenceResult {
        val sourceBitmap = decodeScaled(file, maxEdge = 1280)
        try {
            val jpeg = bitmapToJpegBytes(sourceBitmap, quality = 80)
            val (polys, respSize, topGarment) = runCatching { cutoutRepo.fetchPolygons(jpeg) }.getOrElse { e ->
                throw mapNetworkError(e)
            }
            Log.d("ClothingPipeline", "cloud polys=${polys.size} top=$topGarment")
            var mask = rasterizePolygons(
                polys,
                srcW = respSize.first.takeIf { it > 0 } ?: sourceBitmap.width,
                srcH = respSize.second.takeIf { it > 0 } ?: sourceBitmap.height,
                outW = sourceBitmap.width,
                outH = sourceBitmap.height
            )
            try {
                // Fill interior holes (e.g. white T-shirt pattern predicted as background)
                val filled = fillMaskHoles(mask)
                if (filled !== mask) {
                    runCatching { if (!mask.isRecycled) mask.recycle() }
                    mask = filled
                }
                val coverage = maskCoverage(mask)
                // Reject only near-empty masks; small garments and partial fills are valid.
                if (coverage < 0.08f) {
                    error("Invalid photo: garment not fully visible. Please lay the garment flat and fill the frame.")
                }
                Log.d("ClothingPipeline", "cloud coverage=$coverage")
                val cutout = cutoutOntoCanvas(sourceBitmap, mask, canvasSize = 1080)
                return InferenceResult(cutout = cutout)
            } finally {
                runCatching { if (!mask.isRecycled) mask.recycle() }
            }
        } finally {
            runCatching { if (!sourceBitmap.isRecycled) sourceBitmap.recycle() }
        }
    }

    /** Raw network failures become a human "need internet" message. */
    private fun mapNetworkError(e: Throwable): Throwable {
        val msg = e.message.orEmpty()
        val offline = e is java.io.IOException ||
            e is java.net.UnknownHostException ||
            e is java.net.SocketTimeoutException ||
            msg.contains("Unable to resolve host", ignoreCase = true) ||
            msg.contains("timeout", ignoreCase = true) ||
            msg.contains("Failed to connect", ignoreCase = true)
        return if (offline) {
            IOExceptionNoNetwork("No internet connection. Cutout needs network — check connection and retry.")
        } else e
    }

    class IOExceptionNoNetwork(message: String) : java.io.IOException(message)

    private fun bitmapToJpegBytes(bmp: Bitmap, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.use { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        return out.toByteArray()
    }

    /**
     * Rasterize Roboflow polygons into one white-on-black alpha bitmap.
     * Roboflow points are (usually) in source pixel space; srcW/srcH rescale
     * defensively in case the service returns a resized coordinate space.
     */
    private fun rasterizePolygons(
        polys: List<com.example.myapplicationkoG.CutoutPolygon>,
        srcW: Int,
        srcH: Int,
        outW: Int,
        outH: Int
    ): Bitmap {
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)
        val paint = android.graphics.Paint().apply {
            color = Color.WHITE
            style = android.graphics.Paint.Style.FILL
            isAntiAlias = true
        }
        val sx = if (srcW > 0) outW.toFloat() / srcW else 1f
        val sy = if (srcH > 0) outH.toFloat() / srcH else 1f
        for (poly in polys) {
            if (poly.points.size < 3) continue
            val path = android.graphics.Path()
            poly.points.forEachIndexed { i, pt ->
                val x = (pt.x * sx).coerceIn(0f, outW.toFloat())
                val y = (pt.y * sy).coerceIn(0f, outH.toFloat())
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            canvas.drawPath(path, paint)
        }
        // Convert to alpha mask: white fill -> full alpha.
        val px = IntArray(outW * outH)
        out.getPixels(px, 0, outW, 0, 0, outW, outH)
        for (i in px.indices) {
            val white = px[i] and 0x00FFFFFF
            px[i] = if (white != 0) (0xFF shl 24) or 0x00FFFFFF else (0x00 shl 24)
        }
        out.setPixels(px, 0, outW, 0, 0, outW, outH)
        return out
    }

    private fun decodeScaled(file: File, maxEdge: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val (w, h) = bounds.outWidth to bounds.outHeight
        if (w <= 0 || h <= 0) error("Could not decode image: ${file.absolutePath}")
        return decodeScaledBounds(file.absolutePath, w, h, maxEdge)
    }

    private fun decodeScaledBounds(path: String, w: Int, h: Int, maxEdge: Int): Bitmap {
        val longEdge = maxOf(w, h)
        if (longEdge <= maxEdge) {
            return BitmapFactory.decodeFile(path)
                ?: error("Could not decode image: $path")
        }
        val scale = maxEdge.toFloat() / longEdge
        val bmp = BitmapFactory.decodeFile(path)
            ?: error("Could not decode image: $path")
        val scaled = Bitmap.createScaledBitmap(
            bmp,
            (bmp.width * scale).toInt(),
            (bmp.height * scale).toInt(),
            true,
        )
        if (scaled !== bmp) bmp.recycle()
        return scaled
    }

    /**
     * Use the merged mask to cut the garment out of the original photo and
     * center-fit it on a 1080x1080 white canvas.
     */
    private fun cutoutOntoCanvas(
        source: Bitmap,
        maskArgb: Bitmap,
        canvasSize: Int,
    ): Bitmap {
        val srcW = source.width
        val srcH = source.height
        val maskResized = if (maskArgb.width != srcW || maskArgb.height != srcH) {
            Bitmap.createScaledBitmap(maskArgb, srcW, srcH, true)
        } else maskArgb

        val cut = Bitmap.createBitmap(srcW, srcH, Bitmap.Config.ARGB_8888)
        val srcPx = IntArray(srcW * srcH)
        val maskPx = IntArray(srcW * srcH)
        source.getPixels(srcPx, 0, srcW, 0, 0, srcW, srcH)
        maskResized.getPixels(maskPx, 0, srcW, 0, 0, srcW, srcH)
        for (i in srcPx.indices) {
            val alpha = (maskPx[i] ushr 24) and 0xFF
            srcPx[i] = (alpha shl 24) or (srcPx[i] and 0x00FFFFFF)
        }
        cut.setPixels(srcPx, 0, srcW, 0, 0, srcW, srcH)
        if (maskResized !== maskArgb) maskResized.recycle()

        val ratio = minOf(
            canvasSize.toFloat() / cut.width,
            canvasSize.toFloat() / cut.height
        ).coerceAtLeast(1e-6f)
        val newW = (cut.width * ratio).toInt().coerceAtLeast(1)
        val newH = (cut.height * ratio).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(cut, newW, newH, true)
        val out = Bitmap.createBitmap(canvasSize, canvasSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(scaled, (canvasSize - newW) / 2f, (canvasSize - newH) / 2f, null)
        if (scaled !== cut) scaled.recycle()
        runCatching { if (!cut.isRecycled) cut.recycle() }
        return out
    }

    private fun maskCoverage(mask: Bitmap): Float {
        val w = mask.width
        val h = mask.height
        val pixels = IntArray(w * h)
        mask.getPixels(pixels, 0, w, 0, 0, w, h)
        var fg = 0
        for (p in pixels) if (((p ushr 24) and 0xFF) > 127) fg++
        return fg.toFloat() / (w * h).toFloat()
    }

    /**
     * Fill interior holes in the mask so inner patterns are kept.
     * Flood fill from borders marks true background, the rest is garment.
     */
    private fun fillMaskHoles(mask: Bitmap): Bitmap {
        val w = mask.width
        val h = mask.height
        val pixels = IntArray(w * h)
        mask.getPixels(pixels, 0, w, 0, 0, w, h)
        val fg = BooleanArray(w * h) { ((pixels[it] ushr 24) and 0xFF) > 127 }
        val visited = BooleanArray(w * h)
        val queue = ArrayDeque<Int>()
        for (x in 0 until w) {
            if (!fg[x]) { visited[x] = true; queue.add(x) }
            val b = (h - 1) * w + x
            if (!fg[b]) { visited[b] = true; queue.add(b) }
        }
        for (y in 0 until h) {
            val l = y * w
            if (!fg[l] && !visited[l]) { visited[l] = true; queue.add(l) }
            val r = y * w + w - 1
            if (!fg[r] && !visited[r]) { visited[r] = true; queue.add(r) }
        }
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            val cx = cur % w
            val cy = cur / w
            if (cx > 0) {
                val n = cur - 1
                if (!fg[n] && !visited[n]) { visited[n] = true; queue.add(n) }
            }
            if (cx < w - 1) {
                val n = cur + 1
                if (!fg[n] && !visited[n]) { visited[n] = true; queue.add(n) }
            }
            if (cy > 0) {
                val n = cur - w
                if (!fg[n] && !visited[n]) { visited[n] = true; queue.add(n) }
            }
            if (cy < h - 1) {
                val n = cur + w
                if (!fg[n] && !visited[n]) { visited[n] = true; queue.add(n) }
            }
        }
        var changed = false
        for (i in pixels.indices) {
            if (!fg[i] && !visited[i]) {
                pixels[i] = (0xFF shl 24) or 0x00FFFFFF
                changed = true
            }
        }
        if (!changed) return mask
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }
}
