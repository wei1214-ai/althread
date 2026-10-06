package com.example.myapplicationkoG

import android.content.Context
import android.net.Uri
import com.example.myapplicationkoG.ui.ProfileRepository
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID

/**
 * Data model for post comments mapped to Supabase columns.
 */
@Serializable
data class PostComment(
    val id: String = "",
    @SerialName("post_id") val postId: String,
    @SerialName("user_id") val userId: String = "",
    @SerialName("username") val username: String = "",
    @SerialName("avatar_url") val avatar_url: String? = null,
    @SerialName("comment_text") val content: String,
    @SerialName("created_at") val createdAt: String? = null
)

/**
 * Helper lightweight data model to query post ID references.
 */
@Serializable
private data class SimplePostIdRow(
    @SerialName("post_id") val postId: String
)

/**
 * Data payload model used for inserting new posts into Supabase.
 */
@Serializable
private data class NewPost(
    @SerialName("user_id")
    val userId: String,

    val username: String,

    @SerialName("user_profile_pic_url")
    val userProfilePicUrl: String?,

    // Primary single image/video URL maintained for backward compatibility
    @SerialName("media_url")
    val mediaUrl: String,

    // List of media URLs supporting multi-photo/media posts
    @SerialName("media_urls")
    val mediaUrls: List<String> = emptyList(),

    @SerialName("is_video")
    val isVideo: Boolean = false,

    @SerialName("clothing_title")
    val clothingTitle: String,

    @SerialName("clothing_category")
    val clothingCategory: String,

    @SerialName("post_type")
    val postType: String,

    val caption: String,

    @SerialName("like_count")
    val likeCount: Int = 0,

    @SerialName("design_id")
    val designId: String? = null,

    @SerialName("challenge_post_id")
    val challengePostId: String? = null
)

/**
 * Repository responsible for managing post data operations with Supabase.
 */
class PostRepository {

    // Fallback sample post for offline/testing display
    private val samplePost = Post(
        id = "1",
        username = "AltUser",
        userProfilePicUrl = "https://via.placeholder.com/150",
        mediaUrl = "https://fqyixgfokvnvpudiruej.supabase.co/storage/v1/object/public/cloth/althread.jpg",
        mediaUrls = listOf("https://fqyixgfokvnvpudiruej.supabase.co/storage/v1/object/public/cloth/althread.jpg"),
        isVideo = false,
        clothingTitle = "Streetwear Outfit Set",
        clothingCategory = "Trend",
        caption = "Sharing my latest clothing set from AltThread!",
        likeCount = 12
    )

    /**
     * Helper to safely retrieve the currently authenticated user's ID.
     */
    private fun getCurrentUserId(): String? {
        return supabase.auth.currentUserOrNull()?.id
    }

    /**
     * Fetches post records filtered by category and sorted by preference.
     * Maps user specific states like likes and favorites.
     */
    suspend fun getPosts(
        category: String = "All",
        sortBy: String = "latest"
    ): List<Post> = withContext(Dispatchers.IO) {
        try {
            val result = supabase.from("posts")
                .select {
                    if (category != "For You" && category != "All") {
                        filter { eq("clothing_category", category) }
                    }
                    if (sortBy == "highest_likes") {
                        order(column = "like_count", order = Order.DESCENDING)
                    } else {
                        order(column = "created_at", order = Order.DESCENDING)
                    }
                }
                .decodeList<Post>()

            val postsList = result.ifEmpty { listOf(samplePost.copy(clothingCategory = category)) }
            val userLikedIds = getLikedPostIdsForCurrentUser()
            val userFavoritedIds = getFavoritedPostIdsForCurrentUser()

            postsList.map { post ->
                post.copy(
                    isLikedByCurrentUser = userLikedIds.contains(post.id),
                    isFavoritedByCurrentUser = userFavoritedIds.contains(post.id)
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            listOf(samplePost.copy(clothingCategory = category))
        }
    }

    /**
     * Searches posts using caption keywords and returns sorted results.
     */
    suspend fun searchPosts(query: String, sortBy: String = "latest"): List<Post> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext getPosts(category = "All", sortBy = sortBy)
        try {
            val result = supabase.from("posts")
                .select {
                    filter {
                        ilike("caption", "%$query%")
                    }
                    if (sortBy == "highest_likes") {
                        order(column = "like_count", order = Order.DESCENDING)
                    } else {
                        order(column = "created_at", order = Order.DESCENDING)
                    }
                }
                .decodeList<Post>()

            val userLikedIds = getLikedPostIdsForCurrentUser()
            val userFavoritedIds = getFavoritedPostIdsForCurrentUser()

            result.map { post ->
                post.copy(
                    isLikedByCurrentUser = userLikedIds.contains(post.id),
                    isFavoritedByCurrentUser = userFavoritedIds.contains(post.id)
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * Retrieves all posts favorited by the current user.
     */
    suspend fun getFavouritePosts(): List<Post> = withContext(Dispatchers.IO) {
        try {
            val favoritedPostIds = getFavoritedPostIdsForCurrentUser()
            if (favoritedPostIds.isEmpty()) return@withContext emptyList()

            val allPosts = getPosts(category = "All")
            allPosts.filter { post -> favoritedPostIds.contains(post.id) }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * Counts total number of posts created by a specific user.
     */
    suspend fun getPostCount(userId: String): Int {
        return try {
            supabase.from("posts")
                .select {
                    filter {
                        eq("user_id", userId)
                    }
                }
                .decodeList<Post>()
                .size
        } catch (e: Exception) {
            0
        }
    }

    /**
     * Deletes a post record and its associated interactions (likes, comments, favorites) from Supabase.
     */
    suspend fun deletePost(postId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            // 1. Delete associated child records first to ensure referential integrity
            supabase.from("post_likes").delete {
                filter { eq("post_id", postId) }
            }
            supabase.from("post_comments").delete {
                filter { eq("post_id", postId) }
            }
            supabase.from("post_favorites").delete {
                filter { eq("post_id", postId) }
            }

            // 2. Delete main post row
            supabase.from("posts").delete {
                filter { eq("id", postId) }
            }

            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Helper function that dynamically checks media type (video/image) and uploads to Supabase storage.
     */
    private suspend fun uploadSingleMedia(
        context: Context,
        uri: Uri,
        userId: String
    ): Pair<String, Boolean> = withContext(Dispatchers.IO) {
        val mimeType = context.contentResolver.getType(uri) ?: ""
        val isVideo = mimeType.startsWith("video", ignoreCase = true) || uri.toString().lowercase().endsWith(".mp4")

        val fileExtension = if (isVideo) "mp4" else "jpg"
        val mediaBytes = context.contentResolver.openInputStream(uri)
            ?.use { it.readBytes() }
            ?: error("Could not read media URI: $uri")

        val mediaPath = "$userId/${UUID.randomUUID()}.$fileExtension"
        val bucket = supabase.storage.from("cloth")
        bucket.upload(mediaPath, mediaBytes)

        val publicUrl = bucket.publicUrl(mediaPath)
        return@withContext Pair(publicUrl, isVideo)
    }

    /**
     * Uploads an already rendered local file (used for design front/back images).
     */
    private suspend fun uploadSingleFile(
        file: File,
        userId: String
    ): String = withContext(Dispatchers.IO) {
        val fileExtension = file.extension.takeIf { it.isNotBlank() } ?: "png"
        val mediaPath = "$userId/${UUID.randomUUID()}.$fileExtension"
        val bucket = supabase.storage.from("cloth")
        bucket.upload(mediaPath, file.readBytes())
        bucket.publicUrl(mediaPath)
    }

    /**
     * Uploads multiple selected media items concurrently and creates a new post entry in Supabase.
     * Files passed in [mediaFiles] are uploaded first so they become the leading images of the post.
     */
    suspend fun createPost(
        context: Context,
        imageUris: List<Uri>,
        title: String,
        category: String,
        bio: String,
        postType: String = "Post",
        isChallenge: Boolean = false,
        designId: String? = null,
        challengePostId: String? = null,
        mediaFiles: List<File> = emptyList()
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            if (imageUris.isEmpty() && mediaFiles.isEmpty()) {
                error("Please select at least one image or video.")
            }

            val user = supabase.auth.currentUserOrNull()
                ?: error("Please log in before posting.")
            val profile = ProfileRepository().getMyProfile()

            // Rendered design images come first so they stay the main photos of the post
            val fileUrls = mediaFiles.map { file ->
                async { uploadSingleFile(file, user.id) }
            }.awaitAll()

            // Concurrently upload selected media items and detect if video exists
            val uploadResults = imageUris.map { uri ->
                async { uploadSingleMedia(context, uri, user.id) }
            }.awaitAll()

            val uploadedPublicUrls = fileUrls + uploadResults.map { it.first }
            val hasVideo = uploadResults.any { it.second }

            val primaryMediaUrl = uploadedPublicUrls.firstOrNull() ?: ""

            val effectivePostType = when {
                designId != null -> "Design"
                isChallenge -> "Challenge"
                else -> postType
            }
            val effectiveChallengeId = challengePostId
            val newPost = NewPost(
                userId = user.id,
                username = profile.username?.ifBlank { "User" } ?: "User",
                userProfilePicUrl = profile.avatar_url,
                mediaUrl = primaryMediaUrl,
                mediaUrls = uploadedPublicUrls,
                isVideo = hasVideo, // 💡 Dynamically pass video detection status
                clothingTitle = title.trim(),
                clothingCategory = category,
                postType = effectivePostType,
                caption = bio.trim(),
                likeCount = 0,
                designId = designId,
                challengePostId = effectiveChallengeId
            )

            supabase.from("posts").insert(newPost)

            true
        } catch (e: Exception) {
            e.printStackTrace()
            throw e
        }
    }

    /**
     * Overloaded function for single image/video creation to maintain backward compatibility.
     */
    suspend fun createPost(
        context: Context,
        imageUri: Uri,
        title: String,
        category: String,
        bio: String,
        isChallenge: Boolean
    ): Boolean {
        return createPost(
            context = context,
            imageUris = listOf(imageUri),
            title = title,
            category = category,
            bio = bio,
            postType = if (isChallenge) "Challenge" else "Post",
            isChallenge = isChallenge
        )
    }

    /**
     * Toggles the current user's row in post_likes. A Supabase trigger keeps the persisted
     * posts.like_count value synchronized with that table.
     */
    suspend fun toggleLike(postId: String): Int = withContext(Dispatchers.IO) {
        try {
            val userId = getCurrentUserId() ?: return@withContext -1

            val userLiked = supabase.from("post_likes").select {
                filter {
                    eq("post_id", postId)
                    eq("user_id", userId)
                }
            }.decodeList<SimplePostIdRow>().isNotEmpty()

            if (userLiked) {
                supabase.from("post_likes").delete {
                    filter {
                        eq("post_id", postId)
                        eq("user_id", userId)
                    }
                }
            } else {
                val profile = ProfileRepository().getMyProfile()
                val username = profile.username?.ifBlank { "User" } ?: "User"

                supabase.from("post_likes").insert(
                    mapOf(
                        "post_id" to postId,
                        "user_id" to userId,
                        "username" to username
                    )
                )
            }

            // The database trigger has already updated this value by the time this query runs.
            supabase.from("posts").select {
                filter { eq("id", postId) }
            }.decodeSingle<Post>().likeCount
        } catch (e: Exception) {
            e.printStackTrace()
            -1
        }
    }

    /**
     * Fetches all comments associated with a specific post.
     */
    suspend fun getComments(postId: String): List<PostComment> = withContext(Dispatchers.IO) {
        try {
            supabase.from("post_comments").select {
                filter { eq("post_id", postId) }
            }.decodeList<PostComment>()
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * Adds a new comment to a specified post.
     */
    suspend fun addComment(postId: String, content: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val user = supabase.auth.currentUserOrNull()
                ?: error("Please log in before commenting.")

            val profile = ProfileRepository().getMyProfile()

            val username = profile.username
                ?.ifBlank { "User" }
                ?: "User"

            val avatar_url = profile.avatar_url

            supabase.from("post_comments").insert(
                mapOf(
                    "post_id" to postId,
                    "user_id" to user.id,
                    "username" to username,
                    "avatar_url" to avatar_url,
                    "comment_text" to content.trim()
                )
            )

            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Dynamically adds or removes a post from user favorites/bookmarks.
     */
    suspend fun toggleFavourite(postId: String, isSaved: Boolean) = withContext(Dispatchers.IO) {
        try {
            val userId = getCurrentUserId() ?: return@withContext
            if (isSaved) {
                supabase.from("post_favorites").insert(
                    mapOf("post_id" to postId, "user_id" to userId)
                )
            } else {
                supabase.from("post_favorites").delete {
                    filter {
                        eq("user_id", userId)
                        eq("post_id", postId)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Fetches a single post by id (used to show the challenge a design responds to).
     */
    suspend fun getPostById(postId: String): Post? = withContext(Dispatchers.IO) {
        if (postId.isBlank()) return@withContext null
        try {
            supabase.from("posts").select {
                filter { eq("id", postId) }
            }.decodeList<Post>().firstOrNull()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Helper to retrieve set of post IDs liked by current user.
     */
    private suspend fun getLikedPostIdsForCurrentUser(): Set<String> {
        return try {
            val userId = getCurrentUserId() ?: return emptySet()
            supabase.from("post_likes").select {
                filter { eq("user_id", userId) }
            }.decodeList<SimplePostIdRow>().map { it.postId }.toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    /**
     * Helper to retrieve set of post IDs favorited by current user.
     */
    private suspend fun getFavoritedPostIdsForCurrentUser(): Set<String> {
        return try {
            val userId = getCurrentUserId() ?: return emptySet()
            supabase.from("post_favorites").select {
                filter { eq("user_id", userId) }
            }.decodeList<SimplePostIdRow>().map { it.postId }.toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }
}
