package com.example.myapplicationkoG

import com.example.myapplicationkoG.ui.UserProfile
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
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

    suspend fun isFollowing(targetUserId: String): Boolean {
        val currentUserId = getCurrentUserId()

        return supabase.from("user_follows")
            .select {
                filter {
                    eq("follower_id", currentUserId)
                    eq("following_id", targetUserId)
                }
            }
            .decodeList<FollowRow>()
            .isNotEmpty()
    }

    suspend fun follow(targetUserId: String) {
        val currentUserId = getCurrentUserId()

        if (currentUserId == targetUserId) {
            error("You cannot follow yourself.")
        }

        supabase.from("user_follows").insert(
            mapOf(
                "follower_id" to currentUserId,
                "following_id" to targetUserId
            )
        )
    }

    suspend fun unfollow(targetUserId: String) {
        val currentUserId = getCurrentUserId()

        supabase.from("user_follows").delete {
            filter {
                eq("follower_id", currentUserId)
                eq("following_id", targetUserId)
            }
        }
    }

    suspend fun getFollowerCount(userId: String): Int {
        return supabase.from("user_follows")
            .select {
                filter {
                    eq("following_id", userId)
                }
            }
            .decodeList<FollowRow>()
            .size
    }

    suspend fun getFollowingCount(userId: String): Int {
        return supabase.from("user_follows")
            .select {
                filter {
                    eq("follower_id", userId)
                }
            }
            .decodeList<FollowRow>()
            .size
    }

    suspend fun getFollowers(userId: String): List<UserProfile> {
        val followRows = supabase.from("user_follows")
            .select {
                filter {
                    eq("following_id", userId)
                }
            }
            .decodeList<FollowRow>()

        return followRows.mapNotNull { row ->
            getProfile(row.followerId)
        }
    }

    suspend fun getFollowing(userId: String): List<UserProfile> {
        val followRows = supabase.from("user_follows")
            .select {
                filter {
                    eq("follower_id", userId)
                }
            }
            .decodeList<FollowRow>()

        return followRows.mapNotNull { row ->
            getProfile(row.followingId)
        }
    }

    private suspend fun getProfile(userId: String): UserProfile? {
        return try {
            supabase.from("profiles")
                .select {
                    filter {
                        eq("id", userId)
                    }
                }
                .decodeSingle<UserProfile>()
        } catch (e: Exception) {
            null
        }
    }
}