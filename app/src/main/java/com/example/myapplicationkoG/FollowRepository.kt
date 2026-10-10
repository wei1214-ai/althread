package com.example.myapplicationkoG

import com.example.myapplicationkoG.ui.UserProfile
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class FollowRow(
    val id: String = "",

    @SerialName("follower_id")
    val followerId: String,

    @SerialName("following_id")
    val followingId: String
)

class FollowRepository {

    private fun getCurrentUserId(): String {
        return supabase.auth.currentUserOrNull()?.id
            ?: error("Please log in first.")
    }

    suspend fun isFollowing(targetUserId: String): Boolean = withContext(Dispatchers.IO) {
        val currentUserId = getCurrentUserId()

        supabase.from("user_follows")
            .select {
                filter {
                    eq("follower_id", currentUserId)
                    eq("following_id", targetUserId)
                }
            }
            .decodeList<FollowRow>()
            .isNotEmpty()
    }

    suspend fun follow(targetUserId: String) = withContext(Dispatchers.IO) {
        val currentUserId = getCurrentUserId()

        if (currentUserId == targetUserId) {
            error("You cannot follow yourself.")
        }

        // Idempotent: avoid 409 on double-tap / retry
        if (isFollowing(targetUserId)) return@withContext

        try {
            supabase.from("user_follows").insert(
                mapOf(
                    "follower_id" to currentUserId,
                    "following_id" to targetUserId
                )
            )
        } catch (e: Exception) {
            val msg = (e.message ?: "").lowercase()
            if (!msg.contains("duplicate") && !msg.contains("unique") && !msg.contains("409")) throw e
        }
    }

    suspend fun unfollow(targetUserId: String) = withContext(Dispatchers.IO) {
        val currentUserId = getCurrentUserId()

        supabase.from("user_follows").delete {
            filter {
                eq("follower_id", currentUserId)
                eq("following_id", targetUserId)
            }
        }
    }

    suspend fun getFollowerCount(userId: String): Int = withContext(Dispatchers.IO) {
        supabase.from("user_follows")
            .select {
                filter {
                    eq("following_id", userId)
                }
            }
            .decodeList<FollowRow>()
            .size
    }

    suspend fun getFollowingCount(userId: String): Int = withContext(Dispatchers.IO) {
        supabase.from("user_follows")
            .select {
                filter {
                    eq("follower_id", userId)
                }
            }
            .decodeList<FollowRow>()
            .size
    }

    suspend fun getFollowers(userId: String): List<UserProfile> = withContext(Dispatchers.IO) {
        val followRows = supabase.from("user_follows")
            .select {
                filter {
                    eq("following_id", userId)
                }
            }
            .decodeList<FollowRow>()

        if (followRows.isEmpty()) return@withContext emptyList()
        // Load profiles concurrently instead of serial per-row requests.
        coroutineScope {
            followRows.map { row ->
                async { getProfile(row.followerId) }
            }.awaitAll().filterNotNull()
        }
    }

    suspend fun getFollowing(userId: String): List<UserProfile> = withContext(Dispatchers.IO) {
        val followRows = supabase.from("user_follows")
            .select {
                filter {
                    eq("follower_id", userId)
                }
            }
            .decodeList<FollowRow>()

        if (followRows.isEmpty()) return@withContext emptyList()
        coroutineScope {
            followRows.map { row ->
                async { getProfile(row.followingId) }
            }.awaitAll().filterNotNull()
        }
    }

    private suspend fun getProfile(userId: String): UserProfile? {
        return try {
            // Select public columns only to avoid exposing email addresses.
            supabase.from("profiles")
                .select(columns = Columns.list("id,username,bio,avatar_url")) {
                    filter {
                        eq("id", userId)
                    }
                }
                .decodeList<UserProfile>()
                .firstOrNull()
        } catch (e: Exception) {
            null
        }
    }
}
