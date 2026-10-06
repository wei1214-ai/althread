package com.example.myapplicationkoG

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Matrix
import android.graphics.Paint
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private fun thumbMinScale(style: Int): Float = if (style < 3) 0.125f else 0.5f
private fun thumbMaxScale(style: Int): Float = if (style < 3) 0.5f else 3.5f

private fun keyOut(src: Bitmap, lo: Float, hi: Float): Bitmap {
    val out = src.copy(Bitmap.Config.ARGB_8888, true)
    val w = out.width
    val h = out.height
    val pixels = IntArray(w * h)
    out.getPixels(pixels, 0, w, 0, 0, w, h)
    for (i in pixels.indices) {
        val p = pixels[i]
        val m = maxOf((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
        val a = ((m - lo) / (hi - lo)).coerceIn(0f, 1f)
        pixels[i] = ((a * 255).toInt() shl 24) or (p and 0x00FFFFFF)
    }
    out.setPixels(pixels, 0, w, 0, 0, w, h)
    return out
}

private fun circularMask(src: Bitmap): Bitmap {
    val out = src.copy(Bitmap.Config.ARGB_8888, true)
    val w = out.width
    val h = out.height
    val cx = w / 2f
    val cy = h / 2f
    val r = minOf(w, h) / 2f
    val pixels = IntArray(w * h)
    out.getPixels(pixels, 0, w, 0, 0, w, h)
    for (y in 0 until h) {
        for (x in 0 until w) {
            val dx = (x + 0.5f - cx) / r
            val dy = (y + 0.5f - cy) / r
            val d = kotlin.math.sqrt(dx * dx + dy * dy)
            val m = ((0.82f - d) / 0.08f).coerceIn(0f, 1f)
            if (m < 1f) {
                val i = y * w + x
                val p = pixels[i]
                val a = ((p ushr 24) * m).toInt()
                pixels[i] = (a shl 24) or (p and 0x00FFFFFF)
            }
        }
    }
    out.setPixels(pixels, 0, w, 0, 0, w, h)
    return out
}

private fun contentBox(bmp: Bitmap): Pair<Float, Float> {
    val w = bmp.width
    val h = bmp.height
    val pixels = IntArray(w * h)
    bmp.getPixels(pixels, 0, w, 0, 0, w, h)
    var minX = w
    var minY = h
    var maxX = -1
    var maxY = -1
    for (y in 0 until h) {
        for (x in 0 until w) {
            if ((pixels[y * w + x] ushr 24) > 16) {
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
    }
    if (maxX < 0) return w.toFloat() to h.toFloat()
    return (maxX - minX + 1).toFloat() to (maxY - minY + 1).toFloat()
}

private fun styleRes(style: Int): Int = when (style) {
    0 -> R.drawable.patchbutton1
    1 -> R.drawable.patchbutton2
    2 -> R.drawable.patchbutton3
    3 -> R.drawable.patch1
    4 -> R.drawable.patch2
    5 -> R.drawable.patch3
    else -> R.drawable.patch4
}

private fun applyDye(bmp: Bitmap, dye: Color, strength: Float) {
    val w = bmp.width
    val h = bmp.height
    val pixels = IntArray(w * h)
    bmp.getPixels(pixels, 0, w, 0, 0, w, h)
    val dyeR = (dye.red * 255).toInt()
    val dyeG = (dye.green * 255).toInt()
    val dyeB = (dye.blue * 255).toInt()
    val k = (strength * 0.85f).coerceIn(0f, 1f)
    for (i in pixels.indices) {
        val p = pixels[i]
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        if (r > 242 && g > 242 && b > 242) continue
        val lum = (r + g + b) / 3f / 255f
        val shade = 0.35f + 0.65f * lum
        val nr = (r * (1f - k) + dyeR * shade * k).toInt().coerceIn(0, 255)
        val ng = (g * (1f - k) + dyeG * shade * k).toInt().coerceIn(0, 255)
        val nb = (b * (1f - k) + dyeB * shade * k).toInt().coerceIn(0, 255)
        pixels[i] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
    }
    bmp.setPixels(pixels, 0, w, 0, 0, w, h)
}

/**
 * Renders what the design actually looks like (dye + patches) for one side.
 */
private fun renderDesignBitmap(
    context: Context,
    row: DesignRow,
    srcFile: File,
    side: String
): Bitmap {
    var bmp = BitmapFactory.decodeFile(srcFile.absolutePath)
        ?: error("Cannot decode design image")
    bmp = bmp.copy(Bitmap.Config.ARGB_8888, true)
    row.state.dye[side]?.let { dye ->
        applyDye(bmp, Color(dye.color), dye.strength)
    }
    val buttons = row.state.buttons[side].orEmpty()
    if (buttons.isNotEmpty()) {
        val texMap = buttons.map { it.style }.distinct().associateWith { style ->
            val raw = BitmapFactory.decodeResource(context.resources, styleRes(style))
                ?: return@associateWith null
            val keyed = if (style == 2) raw else {
                if (style < 3) keyOut(raw, 25f, 140f) else keyOut(raw, 10f, 50f)
            }
            val final = if (style < 3) circularMask(keyed) else keyed
            final to contentBox(final)
        }
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val base = 0.05f * minOf(bmp.width, bmp.height)
        buttons.forEach { btn ->
            val (tex, box) = texMap[btn.style] ?: return@forEach
            val target = (base * btn.scale).coerceIn(
                base * thumbMinScale(btn.style), base * thumbMaxScale(btn.style)
            )
            val s = target / maxOf(box.first.coerceAtLeast(1f), box.second.coerceAtLeast(1f))
            val m = Matrix()
            m.postTranslate(-tex.width / 2f, -tex.height / 2f)
            m.postScale(s, s)
            m.postRotate(btn.rotation)
            m.postTranslate(btn.x * bmp.width, btn.y * bmp.height)
            canvas.drawBitmap(tex, m, paint)
        }
    }
    return bmp
}

private fun writeDesignPng(context: Context, row: DesignRow, name: String, bmp: Bitmap): File {
    val out = File(File(context.filesDir, "designs_cache/${row.id}").apply { mkdirs() }, name)
    out.outputStream().use { o -> bmp.compress(Bitmap.CompressFormat.PNG, 90, o) }
    return out
}

/**
 * Renders what the design actually looks like (dye + patches) for list thumbnails.
 */
suspend fun renderDesignThumb(
    context: Context,
    row: DesignRow,
    frontFile: File
): File = withContext(Dispatchers.Default) {
    val bmp = renderDesignBitmap(context, row, frontFile, "FRONT")
    val out = writeDesignPng(context, row, "thumb.png", bmp)
    runCatching { if (!bmp.isRecycled) bmp.recycle() }
    out
}

/**
 * Renders the finished (dyed + patched) image of one side for sharing as a post image.
 */
suspend fun renderDesignShareImage(
    context: Context,
    row: DesignRow,
    srcFile: File,
    side: String
): File = withContext(Dispatchers.Default) {
    val bmp = renderDesignBitmap(context, row, srcFile, side)
    val out = writeDesignPng(context, row, "share_${side.lowercase()}.png", bmp)
    runCatching { if (!bmp.isRecycled) bmp.recycle() }
    out
}

/**
 * Renders both finished sides (front first, then back) of a design for sharing.
 */
suspend fun renderDesignShareImages(
    context: Context,
    repo: DesignRepository,
    row: DesignRow
): Pair<File, File> {
    val (front, back) = repo.ensureLocalFiles(context, row)
    return renderDesignShareImage(context, row, front, "FRONT") to
        renderDesignShareImage(context, row, back, "BACK")
}
