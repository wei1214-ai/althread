package com.example.myapplicationkoG

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Feed post supporting up to 9 images.
 *
 * [mediaUrls] holds 1-9 photos; [mediaUrl] is the legacy single-image field
 * retained for backward compatibility.
 */
@Serializable
data class Post(
    val id: String = "",

    @SerialName("user_id")
    val userId: String = "",

    val username: String = "AltUser",

    @SerialName("user_profile_pic_url")
    val userProfilePicUrl: String? = null,

    @SerialName("media_url")
    val mediaUrl: String = "",

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

    @SerialName("comment_count")
    val commentCount: Int = 0,

    @SerialName("created_at")
    val createdAt: String = "",

    @SerialName("design_id")
    val designId: String? = null,

    @SerialName("challenge_post_id")
    val challengePostId: String? = null,

    // UI-only state; excluded from the network payload.
    @Transient
    val isLikedByCurrentUser: Boolean = false,

    @Transient
    val isFavoritedByCurrentUser: Boolean = false
) {
    /** Resolved image list, preferring [mediaUrls] with fallback to [mediaUrl]. */
    val allMediaUrls: List<String>
        get() = if (mediaUrls.isNotEmpty()) mediaUrls else listOfNotNull(mediaUrl.ifEmpty { null })
}

/** Like record linking a user to a post. */
@Serializable
data class PostLike(
    @SerialName("post_id") val postId: String,
    @SerialName("user_id") val userId: String,
    val username: String? = null,
    @SerialName("user_profile_pic_url") val userProfilePicUrl: String? = null
)

/** Saved-post record for the profile collection. */
@Serializable
data class PostFavorite(
    @SerialName("post_id") val postId: String,
    @SerialName("user_id") val userId: String
)