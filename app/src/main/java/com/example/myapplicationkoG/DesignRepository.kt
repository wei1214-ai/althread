package com.example.myapplicationkoG

import android.content.Context
import com.example.myapplicationkoG.domain.model.GarmentSideId
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.storage.storage
import io.ktor.http.ContentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID

@Serializable
data class SavedDye(val color: Int, val strength: Float)

@Serializable
data class SavedButton(
    val x: Float,
    val y: Float,
    val scale: Float,
    val color: Int,
    val style: Int = 0,
    val rotation: Float = 0f
)

@Serializable
data class DesignState(
    val dye: Map<String, SavedDye> = emptyMap(),
    val buttons: Map<String, List<SavedButton>> = emptyMap(),
    val challengePostId: String? = null
)

@Serializable
private data class DesignInsert(
    val id: String,
    @SerialName("user_id") val userId: String,
    val name: String,
    @SerialName("front_url") val frontUrl: String,
    @SerialName("back_url") val backUrl: String,
    val state: DesignState
)

@Serializable
private data class DesignUpdate(
    val name: String,
    @SerialName("front_url") val frontUrl: String,
    @SerialName("back_url") val backUrl: String,
    val state: DesignState,
    @SerialName("updated_at") val updatedAt: String
)

@Serializable
data class DesignRow(
    val id: String = "",
    @SerialName("user_id") val userId: String = "",
    val name: String = "",
    @SerialName("front_url") val frontUrl: String? = null,
    @SerialName("back_url") val backUrl: String? = null,
    val state: DesignState = DesignState(),
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = ""
)

class DesignRepository {

    private val bucketName = "designs"

    private fun urlToPath(url: String): String {
        // Robust: parse after "/designs/" instead of hard-coded "object/public/designs/"
        // (works for public + authenticated + custom-domain URLs).
        val marker = "/$bucketName/"
        val idx = url.indexOf(marker)
        if (idx >= 0) return url.substring(idx + marker.length).substringBefore("?")
        // Fallback: last two segments (userId/file) if URL shape unknown
        return url.substringBefore("?").split("/").takeLast(2).joinToString("/").ifBlank { url }
    }

    suspend fun saveDesign(
        name: String,
        frontFile: File,
        backFile: File,
        dye: Map<GarmentSideId, SavedDye>,
        buttons: Map<GarmentSideId, List<SavedButton>>,
        challengePostId: String? = com.example.myapplicationkoG.ui.editor.ChallengeSession.postId
    ): DesignRow = withContext(Dispatchers.IO) {
        val userId = supabase.auth.currentUserOrNull()?.id ?: error("Please log in first")
        val id = UUID.randomUUID().toString()
        val bucket = supabase.storage.from(bucketName)
        val frontPath = "$userId/$id/front.png"
        val backPath = "$userId/$id/back.png"
        bucket.upload(frontPath, frontFile.readBytes()) { upsert = true; contentType = ContentType.parse("image/png") }
        bucket.upload(backPath, backFile.readBytes()) { upsert = true; contentType = ContentType.parse("image/png") }
        val frontUrl = bucket.publicUrl(frontPath)
        val backUrl = bucket.publicUrl(backPath)
        val finalName = name.ifBlank { "Untitled design" }.trim().take(30)
        val state = DesignState(
            dye = dye.mapKeys { it.key.name },
            buttons = buttons.mapKeys { it.key.name },
            challengePostId = challengePostId?.takeIf { it.isNotBlank() }
        )
        supabase.from("designs").insert(
            DesignInsert(id, userId, finalName, frontUrl, backUrl, state)
        )
        DesignRow(id, userId, finalName, frontUrl, backUrl, state)
    }

    suspend fun updateDesign(
        rowId: String,
        name: String,
        frontFile: File,
        backFile: File,
        dye: Map<GarmentSideId, SavedDye>,
        buttons: Map<GarmentSideId, List<SavedButton>>,
        challengePostId: String? = com.example.myapplicationkoG.ui.editor.ChallengeSession.postId
    ): DesignRow = withContext(Dispatchers.IO) {
        val userId = supabase.auth.currentUserOrNull()?.id ?: error("Please log in first")
        val bucket = supabase.storage.from(bucketName)
        val frontPath = "$userId/$rowId/front.png"
        val backPath = "$userId/$rowId/back.png"
        bucket.upload(frontPath, frontFile.readBytes()) { upsert = true; contentType = ContentType.parse("image/png") }
        bucket.upload(backPath, backFile.readBytes()) { upsert = true; contentType = ContentType.parse("image/png") }
        val frontUrl = bucket.publicUrl(frontPath)
        val backUrl = bucket.publicUrl(backPath)
        val finalName = name.ifBlank { "Untitled design" }.trim().take(30)
        val state = DesignState(
            dye = dye.mapKeys { it.key.name },
            buttons = buttons.mapKeys { it.key.name },
            challengePostId = challengePostId?.takeIf { it.isNotBlank() }
        )
        supabase.from("designs").update(
            DesignUpdate(finalName, frontUrl, backUrl, state, java.time.Instant.now().toString())
        ) {
            filter {
                eq("id", rowId)
                eq("user_id", userId)
            }
        }
        DesignRow(rowId, userId, finalName, frontUrl, backUrl, state)
    }

    suspend fun listMyDesigns(): List<DesignRow> = withContext(Dispatchers.IO) {
        val userId = supabase.auth.currentUserOrNull()?.id ?: error("Please log in first")
        supabase.from("designs").select {
            filter { eq("user_id", userId) }
            order(column = "updated_at", order = Order.DESCENDING)
        }.decodeList<DesignRow>()
    }

    private suspend fun localFile(context: Context, row: DesignRow, side: String): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.filesDir, "designs_cache/${row.id}").apply { mkdirs() }
            val out = File(dir, "$side.png")
            if (isUsableImage(out)) return@withContext out
            val remote = if (side == "front") {
                row.frontUrl ?: error("Design has no front image")
            } else {
                row.backUrl ?: error("Design has no back image")
            }
            // Download to tmp + atomic rename so an interrupted download leaves no truncated file.
            val tmp = File(dir, "$side.png.tmp")
            try {
                tmp.writeBytes(supabase.storage.from(bucketName).downloadAuthenticated(urlToPath(remote)))
                if (!isUsableImage(tmp)) error("Downloaded $side image is corrupt, please retry.")
                if (out.exists()) out.delete()
                if (!tmp.renameTo(out)) {
                    tmp.copyTo(out, overwrite = true)
                    tmp.delete()
                }
            } finally {
                runCatching { if (tmp.exists()) tmp.delete() }
            }
            out
        }

    /** Non-empty + decodable bounds (cheap, no full decode). */
    private fun isUsableImage(f: File): Boolean {
        if (!f.exists() || f.length() <= 0L) return false
        return try {
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(f.absolutePath, o)
            o.outWidth > 0 && o.outHeight > 0
        } catch (_: Exception) {
            false
        }
    }

    suspend fun ensureFrontFile(context: Context, row: DesignRow): File =
        localFile(context, row, "front")

    suspend fun ensureLocalFiles(context: Context, row: DesignRow): Pair<File, File> =
        localFile(context, row, "front") to localFile(context, row, "back")

    suspend fun deleteDesign(context: Context, row: DesignRow) = withContext(Dispatchers.IO) {
        val userId = supabase.auth.currentUserOrNull()?.id ?: error("Please log in first")
        if (row.userId.isNotBlank() && row.userId != userId) error("You can only delete your own designs.")
        val bucket = supabase.storage.from(bucketName)
        val paths = listOfNotNull(row.frontUrl, row.backUrl).map { urlToPath(it) }
        runCatching { bucket.delete(paths) }
        supabase.from("designs").delete {
            filter {
                eq("id", row.id)
                eq("user_id", userId)
            }
        }
        runCatching { File(context.filesDir, "designs_cache/${row.id}").deleteRecursively() }
    }
}
