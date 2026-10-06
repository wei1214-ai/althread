package com.example.myapplicationkoG

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

// 1. Main Post Model with Multi-Image Support (Up to 9 images)
@Serializable
data class Post(
    val id: String = "",

    @SerialName("user_id")
    val userId: String = "",

    val username: String = "AltUser",

    @SerialName("user_profile_pic_url")
    val userProfilePicUrl: String? = null,

    // Primary single image URL (retained for backward compatibility)
    @SerialName("media_url")
    val mediaUrl: String = "",

    // List of multiple image URLs (supports 1 to 9 photos per post)
    @SerialName("media_urls")
    val mediaUrls: List<String> = emptyList(),

    @SerialName("is_video")
    val isVideo: Boolean = false,

    @SerialName("clothing_title")
    val clothingTitle: String = "",

    @SerialName("clothing_category")
    val clothingCategory: String = "For You",

    @SerialName("post_type")
    val postType: String = "Post",

    val caption: String = "",

    @SerialName("like_count")
    val likeCount: Int = 0,

    @SerialName("created_at")
    val createdAt: String = "",

    @SerialName("design_id")
    val designId: String? = null,

    @SerialName("challenge_post_id")
    val challengePostId: String? = null,

    // UI state helper fields (ignored during direct Supabase JSON parsing)
    @Transient
    val isLikedByCurrentUser: Boolean = false,

    @Transient
    val isFavoritedByCurrentUser: Boolean = false
) {
    // Helper property: Returns mediaUrls if non-empty, otherwise falls back to single mediaUrl
    val allMediaUrls: List<String>
        get() = if (mediaUrls.isNotEmpty()) mediaUrls else listOfNotNull(mediaUrl.ifEmpty { null })
}

// 2. Track who liked which post
@Serializable
data class PostLike(
    @SerialName("post_id") val postId: String,
    @SerialName("user_id") val userId: String,
    val username: String? = null,
    @SerialName("user_profile_pic_url") val userProfilePicUrl: String? = null
)

// 3. Track saved/bookmarked posts for Profile
@Serializable
data class PostFavorite(
    @SerialName("post_id") val postId: String,
    @SerialName("user_id") val userId: String
)