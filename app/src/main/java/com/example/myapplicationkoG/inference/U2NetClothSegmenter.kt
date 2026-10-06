package com.example.myapplicationkoG.inference

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import java.util.Collections

/**
 * levindabhi/cloth-segmentation (U2NET) drop-in segmenter.
 *
 * Drop the converted model as app/src/main/assets/cloth_segmentation.onnx,
 * it is copied to filesDir on first use and memory-mapped.
 * Returns null when the files are absent.
 */
class U2NetClothSegmenter(context: Context) {
    private val appContext = context.applicationContext
    private val ortEnv: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

    fun modelFile(): File? {
        val outFile = File(appContext.filesDir, "onnx_models/cloth_segmentation.onnx")
        val outData = File(outFile.absolutePath + ".data")
        // Main file is only ~700KB, weights live in the external .data file (~176MB)
        if (outFile.exists() && outFile.length() > 100 * 1024 &&
            outData.exists() && outData.length() > 100 * 1024 * 1024
        ) return outFile
        return try {
            outFile.parentFile?.mkdirs()
            copyAsset("cloth_segmentation.onnx", outFile)
            copyAsset("cloth_segmentation.onnx.data", outData)
            if (outFile.length() == 0L || outData.length() == 0L) return null
            Log.d("U2Net", "cloth model ready size=${outFile.length()} data=${outData.length()}")
            outFile
        } catch (_: Throwable) {
            null
        }
    }

    private fun copyAsset(assetName: String, outFile: File) {
        appContext.assets.open(assetName).use { input ->
            val tmp = File(outFile.absolutePath + ".tmp")
            tmp.outputStream().use { out -> input.copyTo(out, bufferSize = 8192) }
            if (outFile.exists()) outFile.delete()
            if (!tmp.renameTo(outFile)) {
                tmp.copyTo(outFile, overwrite = true)
                tmp.delete()
            }
        }
    }

    @Volatile private var session: OrtSession? = null

    @Synchronized
    private fun getSession(file: File): OrtSession {
        session?.let { return it }
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(Runtime.getRuntime().availableProcessors())
        }
        return ortEnv.createSession(file.absolutePath, opts).also { session = it }
    }

    fun segment(source: Bitmap): Bitmap {
        val file = modelFile() ?: error("cloth_segmentation.onnx not found")
        val session = getSession(file)
        val inputSize = 768
        val resized = Bitmap.createScaledBitmap(source, inputSize, inputSize, true)
        var tensor: OnnxTensor? = null
        var outputs: OrtSession.Result? = null
        try {
            tensor = OnnxTensor.createTensor(
                ortEnv,
                imageNetBuffer(resized),
                longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong())
            )
            val inputName = session.inputNames.firstOrNull() ?: "x"
            outputs = session.run(Collections.singletonMap(inputName, tensor))
            // Model has 7 outputs (d0 fused + d1..d6 sides); prefer d0 named "output"
            val d0 = try {
                outputs.get("output").orElse(null)
            } catch (_: Throwable) {
                null
            }
            val value = (d0 ?: outputs.get(0)).getValue()
            val maskLowRes = parseFourChannelMask(value)
            return resampleToBitmap(maskLowRes, source.width, source.height)
        } finally {
            runCatching { tensor?.close() }
            runCatching { outputs?.close() }
            if (resized !== source) runCatching { resized.recycle() }
        }
    }

    private fun imageNetBuffer(bitmap: Bitmap): FloatBuffer {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = FloatBuffer.allocate(3 * w * h)
        // ImageNet mean/std used by the U2NET cloth repo
        val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
        val std = floatArrayOf(0.229f, 0.224f, 0.225f)
        val plane = FloatArray(w * h)
        for (c in 0..2) {
            for (i in pixels.indices) {
                val p = pixels[i]
                val v = when (c) {
                    0 -> ((p shr 16) and 0xFF) / 255f
                    1 -> ((p shr 8) and 0xFF) / 255f
                    else -> (p and 0xFF) / 255f
                }
                plane[i] = (v - mean[c]) / std[c]
            }
            out.put(plane)
        }
        out.rewind()
        return out
    }

    private fun parseFourChannelMask(value: Any?): Array<IntArray> {
        val batch = value as? Array<*> ?: error("Unexpected U2NET output")
        if (batch.isEmpty()) error("Empty U2NET output")
        val first = batch[0] as? Array<*> ?: error("Unexpected U2NET dims")
        if (first.isEmpty()) error("Empty U2NET channels")
        val numClasses = first.size
        val head = first[0]
        if (head is FloatArray) {
            // Flat per-channel data: [C][H*W], assume square
            @Suppress("UNCHECKED_CAST")
            val flat = first as Array<FloatArray>
            val side = kotlin.math.sqrt(head.size.toDouble()).toInt()
            if (side * side != head.size) error("Unexpected U2NET flat size ${head.size}")
            if (numClasses == 1) {
                return Array(side) { y ->
                    IntArray(side) { x ->
                        val v = flat[0][y * side + x]
                        if (1f / (1f + kotlin.math.exp(-v)) > 0.5f) 1 else 0
                    }
                }
            }
            val cls = Array(side) { y ->
                IntArray(side) { x ->
                    val idx = y * side + x
                    var best = 0
                    var bestV = Float.NEGATIVE_INFINITY
                    for (c in 0 until numClasses) {
                        val v = flat[c][idx]
                        if (v > bestV) { bestV = v; best = c }
                    }
                    best
                }
            }
            val bg = borderVote(cls)
            return Array(side) { y ->
                IntArray(side) { x -> if (cls[y][x] == bg) 0 else 1 }
            }
        }
        // Grid data: [C][H][W]
        @Suppress("UNCHECKED_CAST")
        val grid = first as Array<Array<FloatArray>>
        val hh = grid[0].size
        val ww = grid[0][0].size
        if (numClasses == 1) {
            return Array(hh) { y ->
                IntArray(ww) { x ->
                    val v = grid[0][y][x]
                    if (1f / (1f + kotlin.math.exp(-v)) > 0.5f) 1 else 0
                }
            }
        }
        // Argmax class map first, then vote background from image borders
        // instead of assuming a fixed channel order.
        val cls = Array(hh) { y ->
            IntArray(ww) { x ->
                var best = 0
                var bestV = Float.NEGATIVE_INFINITY
                for (c in 0 until numClasses) {
                    val v = grid[c][y][x]
                    if (v > bestV) { bestV = v; best = c }
                }
                best
            }
        }
        val bg = borderVote(cls)
        return Array(hh) { y ->
            IntArray(ww) { x -> if (cls[y][x] == bg) 0 else 1 }
        }
    }

    private fun borderVote(cls: Array<IntArray>): Int {
        val hh = cls.size
        val ww = cls[0].size
        var classes = 0
        for (row in cls) for (v in row) if (v + 1 > classes) classes = v + 1
        val votes = IntArray(classes.coerceAtLeast(1))
        for (x in 0 until ww) {
            votes[cls[0][x]]++
            votes[cls[hh - 1][x]]++
        }
        for (y in 0 until hh) {
            votes[cls[y][0]]++
            votes[cls[y][ww - 1]]++
        }
        var bg = 0
        for (c in votes.indices) if (votes[c] > votes[bg]) bg = c
        return bg
    }

    private fun resampleToBitmap(mask: Array<IntArray>, outW: Int, outH: Int): Bitmap {
        // Bilinear upsample so edges carry gradient alpha instead of hard steps.
        val srcH = mask.size
        val srcW = mask[0].size
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(outW * outH)
        for (y in 0 until outH) {
            val sy = y.toFloat() * srcH / outH
            val y0 = sy.toInt().coerceIn(0, srcH - 1)
            val y1 = (y0 + 1).coerceAtMost(srcH - 1)
            val dy = sy - y0
            for (x in 0 until outW) {
                val sx = x.toFloat() * srcW / outW
                val x0 = sx.toInt().coerceIn(0, srcW - 1)
                val x1 = (x0 + 1).coerceAtMost(srcW - 1)
                val dx = sx - x0
                val v = (1 - dy) * ((1 - dx) * mask[y0][x0] + dx * mask[y0][x1]) +
                    dy * ((1 - dx) * mask[y1][x0] + dx * mask[y1][x1])
                val alpha = (v * 255f).toInt().coerceIn(0, 255)
                pixels[y * outW + x] = (alpha shl 24) or 0x00FFFFFF
            }
        }
        out.setPixels(pixels, 0, outW, 0, 0, outW, outH)
        return out
    }
}
