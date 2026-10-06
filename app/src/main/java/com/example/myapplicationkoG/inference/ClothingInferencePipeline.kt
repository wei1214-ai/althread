package com.example.myapplicationkoG.inference

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import java.io.File

/**
 * Result of the on-device cutout pipeline for a single garment photo.
 * Only the final cutout bitmap matters for now.
 */
data class InferenceResult(
    val cutout: Bitmap,
)

/**
 * Pipeline:
 *   photo file → decode → cloth U2NET mask → cut out + center on 1080x1080
 *
 * U2NET only. YOLO and SAM have been removed from the project.
 *
 * Runs on Dispatchers.IO. No deskew, no contour math — just the AI
 * cutout blended onto a white canvas.
 */
class ClothingInferencePipeline(context: Context) {

    private val appContext = context.applicationContext
    private val clothNet = U2NetClothSegmenter(appContext)

    suspend fun run(file: File): InferenceResult {
        val sourceBitmap = decodeScaled(file, maxEdge = 1280)
        try {
            val clothFile = runCatching { clothNet.modelFile() }.getOrNull()
                ?: error("cloth_segmentation.onnx missing. Please add the model to assets.")
            Log.d("ClothingPipeline", "U2NET model=${clothFile.absolutePath}")
            var mask = clothNet.segment(sourceBitmap)
            try {
                // Fill interior holes (e.g. white T-shirt pattern predicted as background)
                val filled = fillMaskHoles(mask)
                if (filled !== mask) {
                    runCatching { if (!mask.isRecycled) mask.recycle() }
                    mask = filled
                }
                val coverage = maskCoverage(mask)
                if (coverage < 0.20f) {
                    error("Invalid photo: garment not fully visible. Please lay the garment flat and fill the frame.")
                }
                Log.d("ClothingPipeline", "U2NET coverage=$coverage")
                val cutout = cutoutOntoCanvas(sourceBitmap, mask, canvasSize = 1080)
                return InferenceResult(cutout = cutout)
            } finally {
                // mask is ARGB with alpha channel, recycle after use
                runCatching { if (!mask.isRecycled) mask.recycle() }
            }
        } finally {
            runCatching { if (!sourceBitmap.isRecycled) sourceBitmap.recycle() }
        }
    }

    private fun decodeScaled(file: File, maxEdge: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val (w, h) = bounds.outWidth to bounds.outHeight
        if (w <= 0 || h <= 0) error("Could not decode image: ${file.absolutePath}")

        var sample = 1
        while (w / sample > maxEdge || h / sample > maxEdge) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = BitmapFactory.decodeFile(file.absolutePath, opts)
            ?: error("Could not decode image: ${file.absolutePath}")
        val longEdge = maxOf(bmp.width, bmp.height)
        return if (longEdge > maxEdge) {
            val scale = maxEdge.toFloat() / longEdge
            val scaled = Bitmap.createScaledBitmap(
                bmp,
                (bmp.width * scale).toInt(),
                (bmp.height * scale).toInt(),
                true,
            )
            if (scaled !== bmp) bmp.recycle()
            scaled
        } else bmp
    }

    /**
     * Use the SAM mask to cut the garment out of the original photo and
     * center-fit it on a 1080x1080 white canvas.
     */
    private fun cutoutOntoCanvas(
        source: Bitmap,
        maskArgb: Bitmap,
        canvasSize: Int,
    ): Bitmap {
        // 1) Make a transparent-background cutout of the source bitmap
        //    using the SAM mask as the alpha channel.
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
            // Keep feathered alpha from SAM (0-255) for anti-aliased edge
            srcPx[i] = (alpha shl 24) or (srcPx[i] and 0x00FFFFFF)
        }
        cut.setPixels(srcPx, 0, srcW, 0, 0, srcW, srcH)
        if (maskResized !== maskArgb) maskResized.recycle()

        // 2) Center-fit the cutout on a 1080x1080 white canvas.
        // Prefer pure Android Canvas (no native dependency) to avoid alpha loss that OpenCV 3-channel Mat caused.
        // Keep OpenCV path as fallback but fixed to 4-channel handling.
        return try {
            if (isOpenCVReady()) {
                cutoutViaOpenCV(cut, canvasSize)
            } else {
                cutoutViaCanvas(cut, canvasSize)
            }
        } catch (t: Throwable) {
            Log.w("ClothingPipeline", "OpenCV path failed, fallback to Canvas: ${t.message}", t)
            cutoutViaCanvas(cut, canvasSize)
        } finally {
            runCatching { if (!cut.isRecycled) cut.recycle() }
        }
    }

    private fun cutoutViaCanvas(cut: Bitmap, canvasSize: Int): Bitmap {
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
        val left = (canvasSize - newW) / 2f
        val top = (canvasSize - newH) / 2f
        canvas.drawBitmap(scaled, left, top, null)
        if (scaled !== cut) scaled.recycle()
        return out
    }

    private fun cutoutViaOpenCV(cut: Bitmap, canvasSize: Int): Bitmap {
        // Fixed 4-channel path: preserve alpha, then composite onto white 3-channel canvas using mask
        val cutMat4 = bitmapToBgraMat(cut) // 4 channels
        val canvasMat = Mat(canvasSize, canvasSize, CvType.CV_8UC3, Scalar.all(255.0))
        try {
            // Split 4 channels to get alpha mask
            val channels = mutableListOf<Mat>()
            org.opencv.core.Core.split(cutMat4, channels) // B,G,R,A
            val alpha = channels[3]
            // Create 3-channel BGR from first 3 channels
            val bgr = Mat()
            org.opencv.core.Core.merge(channels.subList(0, 3), bgr)

            val ratio = minOf(
                canvasSize.toDouble() / bgr.cols(),
                canvasSize.toDouble() / bgr.rows(),
            ).coerceAtLeast(1e-6)
            val newW = (bgr.cols() * ratio).toInt().coerceAtLeast(1)
            val newH = (bgr.rows() * ratio).toInt().coerceAtLeast(1)
            val fittedBgr = Mat()
            val fittedAlpha = Mat()
            Imgproc.resize(bgr, fittedBgr, org.opencv.core.Size(newW.toDouble(), newH.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            Imgproc.resize(alpha, fittedAlpha, org.opencv.core.Size(newW.toDouble(), newH.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
            try {
                val x = (canvasSize - newW) / 2
                val y = (canvasSize - newH) / 2
                val roi = canvasMat.submat(y, y + newH, x, x + newW)
                try {
                    // Copy only where alpha >127
                    // threshold alpha to binary
                    val maskBinary = Mat()
                    Imgproc.threshold(fittedAlpha, maskBinary, 127.0, 255.0, Imgproc.THRESH_BINARY)
                    try {
                        fittedBgr.copyTo(roi, maskBinary)
                    } finally {
                        maskBinary.release()
                    }
                } finally {
                    roi.release()
                }
            } finally {
                fittedBgr.release()
                fittedAlpha.release()
                bgr.release()
                alpha.release()
                for (c in channels) c.release()
            }
            return bgrMatToBitmap(canvasMat)
        } finally {
            cutMat4.release()
            canvasMat.release()
        }
    }

    private fun bitmapToBgrMat(bitmap: Bitmap): Mat {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val mat = Mat(h, w, CvType.CV_8UC3)
        val data = ByteArray(w * h * 3)
        var i = 0
        for (p in pixels) {
            data[i++] = (p and 0xFF).toByte() // B
            data[i++] = ((p shr 8) and 0xFF).toByte()  // G
            data[i++] = ((p shr 16) and 0xFF).toByte()  // R
        }
        mat.put(0, 0, data)
        return mat
    }

    private fun bitmapToBgraMat(bitmap: Bitmap): Mat {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val mat = Mat(h, w, CvType.CV_8UC4)
        val data = ByteArray(w * h * 4)
        var i = 0
        for (p in pixels) {
            data[i++] = (p and 0xFF).toByte() // B
            data[i++] = ((p shr 8) and 0xFF).toByte()  // G
            data[i++] = ((p shr 16) and 0xFF).toByte()  // R
            data[i++] = ((p ushr 24) and 0xFF).toByte() // A
        }
        mat.put(0, 0, data)
        return mat
    }

    private fun bgrMatToBitmap(mat: Mat): Bitmap {
        val w = mat.cols()
        val h = mat.rows()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val data = ByteArray(w * h * 3)
        mat.get(0, 0, data)
        val pixels = IntArray(w * h)
        var i = 0
        for (p in pixels.indices) {
            val b = data[i++].toInt() and 0xFF
            val g = data[i++].toInt() and 0xFF
            val r = data[i++].toInt() and 0xFF
            pixels[p] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        return bmp
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
     * Fill interior holes in SAM mask so inner patterns are kept.
     * White T-shirt pattern is often predicted as background hole;
     * flood fill from borders marks true background, the rest is garment.
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

    companion object {
        @Volatile private var opencvInited: Boolean? = null

        private fun isOpenCVReady(): Boolean {
            opencvInited?.let { return it }
            val ready = try {
                System.loadLibrary("opencv_java4")
                true
            } catch (_: Throwable) {
                try {
                    System.loadLibrary("opencv_java4100")
                    true
                } catch (_: Throwable) {
                    try {
                        org.opencv.android.OpenCVLoader.initDebug()
                    } catch (_: Throwable) {
                        false
                    }
                }
            }
            opencvInited = ready
            if (!ready) Log.w("ClothingPipeline", "OpenCV not available, using Canvas fallback")
            return ready
        }
    }
}
