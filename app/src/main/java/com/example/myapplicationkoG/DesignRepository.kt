package com.example.myapplicationkoG

import android.content.Context
import com.example.myapplicationkoG.domain.model.GarmentSideId
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.storage.storage
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
    private val bucketPath = "object/public/$bucketName/"

    private fun urlToPath(url: String): String {
        val idx = url.indexOf(bucketPath)
        return if (idx >= 0) url.substring(idx + bucketPath.length) else url
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
        bucket.upload(frontPath, frontFile.readBytes()) { upsert = true }
        bucket.upload(backPath, backFile.readBytes()) { upsert = true }
        val frontUrl = bucket.publicUrl(frontPath)
        val backUrl = bucket.publicUrl(backPath)
        val finalName = name.ifBlank { "Untitled design" }
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
        bucket.upload(frontPath, frontFile.readBytes()) { upsert = true }
        bucket.upload(backPath, backFile.readBytes()) { upsert = true }
        val frontUrl = bucket.publicUrl(frontPath)
        val backUrl = bucket.publicUrl(backPath)
        val finalName = name.ifBlank { "Untitled design" }
        val state = DesignState(
            dye = dye.mapKeys { it.key.name },
            buttons = buttons.mapKeys { it.key.name },
            challengePostId = challengePostId?.takeIf { it.isNotBlank() }
        )
        supabase.from("designs").update(
            DesignUpdate(finalName, frontUrl, backUrl, state, java.time.Instant.now().toString())
        ) {
            filter { eq("id", rowId) }
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

    private suspend fun localFile(context: Context, row: DesignRow, side: String): File {
        val dir = File(context.filesDir, "designs_cache/${row.id}").apply { mkdirs() }
        val out = File(dir, "$side.png")
        if (!out.exists() || out.length() == 0L) {
            val remote = if (side == "front") {
                row.frontUrl ?: error("Design has no front image")
            } else {
                row.backUrl ?: error("Design has no back image")
            }
            out.writeBytes(supabase.storage.from(bucketName).downloadAuthenticated(urlToPath(remote)))
        }
        return out
    }

    suspend fun ensureFrontFile(context: Context, row: DesignRow): File =
        localFile(context, row, "front")

    suspend fun ensureLocalFiles(context: Context, row: DesignRow): Pair<File, File> =
        localFile(context, row, "front") to localFile(context, row, "back")

    suspend fun deleteDesign(context: Context, row: DesignRow) = withContext(Dispatchers.IO) {
        val bucket = supabase.storage.from(bucketName)
        val paths = listOfNotNull(row.frontUrl, row.backUrl).map { urlToPath(it) }
        runCatching { bucket.delete(paths) }
        supabase.from("designs").delete {
            filter { eq("id", row.id) }
        }
        runCatching { File(context.filesDir, "designs_cache/${row.id}").deleteRecursively() }
    }
}
