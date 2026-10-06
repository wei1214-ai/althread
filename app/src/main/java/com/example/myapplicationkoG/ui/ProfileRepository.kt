package com.example.myapplicationkoG.ui

import com.example.myapplicationkoG.supabase
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import kotlinx.serialization.Serializable
import android.content.Context
import android.net.Uri
import io.github.jan.supabase.storage.storage

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

    suspend fun getMyProfile(): UserProfile {
        val userId = supabase.auth.currentUserOrNull()?.id
            ?: error("No user is logged in")

        return supabase
            .from("profiles")
            .select {
                filter {
                    eq("id", userId)
                }
            }
            .decodeSingle()
    }
    suspend fun updateMyProfile(username: String, bio: String) {
        val userId = supabase.auth.currentUserOrNull()?.id
            ?: error("No user is logged in")

        supabase
            .from("profiles")
            .update(
                ProfileUpdate(
                    username = username,
                    bio = bio
                )
            ) {
                filter {
                    eq("id", userId)
                }
            }
    }

    suspend fun uploadAvatar(
        context: Context,
        imageUri: Uri
    ): String {
        val userId = supabase.auth.currentUserOrNull()?.id
            ?: error("No user is logged in")

        val imageBytes = context.contentResolver
            .openInputStream(imageUri)
            ?.use { it.readBytes() }
            ?: error("Could not read selected image")

        val avatarPath = "$userId/avatar.jpg"
        val bucket = supabase.storage.from("avatars")

        bucket.upload(avatarPath, imageBytes) {
            upsert = true
        }

        val avatarUrl =
            "${bucket.publicUrl(avatarPath)}?v=${System.currentTimeMillis()}"

        supabase
            .from("profiles")
            .update(AvatarUpdate(avatar_url = avatarUrl)) {
                filter {
                    eq("id", userId)
                }
            }

        return avatarUrl
    }
}