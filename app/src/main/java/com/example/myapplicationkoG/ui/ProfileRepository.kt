package com.example.myapplicationkoG.ui

import com.example.myapplicationkoG.supabase
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import android.content.Context
import android.net.Uri
import io.github.jan.supabase.storage.storage
import io.ktor.http.ContentType

@Serializable
data class UserProfile(
    val id: String,
    val email: String? = null,
    val username: String? = null,
    val bio: String? = null,
    val avatar_url: String? = null
)
@Serializable
data class ProfileUpdate(
    val username: String,
    val bio: String
)

@Serializable
data class AvatarUpdate(
    val avatar_url: String
)
class ProfileRepository {

    suspend fun getMyProfile(): UserProfile = withContext(Dispatchers.IO) {        val userId = supabase.auth.currentUserOrNull()?.id
            ?: error("No user is logged in")

        supabase
            .from("profiles")
            .select {
                filter {
                    eq("id", userId)
                }
            }
            .decodeList<UserProfile>()
            .firstOrNull() ?: error("Profile not found (RLS or not created yet)")
    }

    /** Public profile of any user (bio/avatar for OtherUserProfileScreen). Null if missing. */
    suspend fun getProfileById(userId: String): UserProfile? = withContext(Dispatchers.IO) {
        try {
            // Select public columns only to avoid exposing email addresses.
            supabase
                .from("profiles")
                .select("id,username,bio,avatar_url") {
                    filter {
                        eq("id", userId)
                    }
                }
                .decodeList<UserProfile>()
                .firstOrNull()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    suspend fun updateMyProfile(username: String, bio: String) = withContext(Dispatchers.IO) {
        val userId = supabase.auth.currentUserOrNull()?.id
            ?: error("No user is logged in")

        // Server-side mirror of the UI caps (username 20, bio 150).
        val cleanName = username.trim().take(20)
        supabase
            .from("profiles")
            .update(
                ProfileUpdate(
                    username = cleanName,
                    bio = bio.trim().take(150)
                )
            ) {
                filter {
                    eq("id", userId)
                }
            }

        // Posts, likes and comments snapshot the username, so propagate the rename;
        // snapshot failures are ignored to keep the rename itself reliable.
        runCatching {
            supabase.from("posts").update(mapOf("username" to cleanName)) {
                filter { eq("user_id", userId) }
            }
        }
        runCatching {
            supabase.from("post_likes").update(mapOf("username" to cleanName)) {
                filter { eq("user_id", userId) }
            }
        }
        runCatching {
            supabase.from("post_comments").update(mapOf("username" to cleanName)) {
                filter { eq("user_id", userId) }
            }
        }
    }

    suspend fun uploadAvatar(
        context: Context,
        imageUri: Uri
    ): String = withContext(Dispatchers.IO) {
        val userId = supabase.auth.currentUserOrNull()?.id
            ?: error("No user is logged in")

        val mimeType = context.contentResolver.getType(imageUri) ?: "image/jpeg"
        val extension = when {
            mimeType.contains("png") -> "png"
            mimeType.contains("webp") -> "webp"
            mimeType.contains("heic") || mimeType.contains("heif") -> "jpg"
            else -> "jpg"
        }
        val imageBytes = context.contentResolver
            .openInputStream(imageUri)
            ?.use {
                val bytes = it.readBytes()
                if (bytes.size > 10L * 1024 * 1024) error("Avatar too large (max 10MB).")
                bytes
            }
            ?: error("Could not read selected image")

        val avatarPath = "$userId/avatar.$extension"
        val bucket = supabase.storage.from("avatars")

        bucket.upload(avatarPath, imageBytes) {
            upsert = true
            this.contentType = ContentType.parse(if (extension == "png") "image/png" else "image/jpeg")
        }

        // Store pure URL in DB (no cache-buster); add ?v= only at display time
        val avatarUrl = bucket.publicUrl(avatarPath)

        supabase
            .from("profiles")
            .update(AvatarUpdate(avatar_url = avatarUrl)) {
                filter {
                    eq("id", userId)
                }
            }

        "$avatarUrl?v=${System.currentTimeMillis()}"
    }
}