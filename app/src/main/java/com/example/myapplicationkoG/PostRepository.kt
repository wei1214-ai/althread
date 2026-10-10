package com.example.myapplicationkoG

import android.content.Context
import android.net.Uri
import com.example.myapplicationkoG.ui.ProfileRepository
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.storage.storage
import io.ktor.http.ContentType
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

    /**
     * Helper to safely retrieve the currently authenticated user's ID.
     */
    private fun getCurrentUserId(): String? {
        return supabase.auth.currentUserOrNull()?.id
    }

    /**
     * Fetches post records filtered by category and sorted by preference.
     * Maps user specific states like likes and favorites.
     * Returns emptyList() on error — never fake sample data (caller shows error/empty state).
     *
     * Tab rule (post_type is the source of truth, NOT clothing_category):
     * - "Challenge" tab -> only post_type == "Challenge".
     * - Style tabs (Trend/Vintage/Streetwear) -> matching category AND
     *   post_type != "Challenge" (challenge posts live ONLY in Challenge).
     * - "For You"/"All" -> everything (challenges stay visible in the main feed).
     */
    suspend fun getPosts(
        category: String = "All",
        sortBy: String = "latest",
        limit: Int = 30
    ): List<Post> = withContext(Dispatchers.IO) {
        try {
            val result = supabase.from("posts")
                .select {
                    when (category) {
                        "For You", "All" -> { /* no filter */ }
                        "Challenge" -> filter { eq("post_type", "Challenge") }
                        else -> filter {
                            eq("clothing_category", category)
                            neq("post_type", "Challenge")
                        }
                    }
                    if (sortBy == "highest_likes") {
                        order(column = "like_count", order = Order.DESCENDING)
                    } else {
                        order(column = "created_at", order = Order.DESCENDING)
                    }
                    limit(count = limit.toLong())
                }
                .decodeList<Post>()
                .take(limit)

            if (result.isEmpty()) return@withContext emptyList()
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
     * Searches posts using caption keywords and returns sorted results.
     * Escapes LIKE wildcards so "%" can't full-scan; caps length + page size.
     */
    suspend fun searchPosts(query: String, sortBy: String = "latest", limit: Int = 30): List<Post> = withContext(Dispatchers.IO) {
        val clean = query.trim().take(30)
        if (clean.isBlank()) return@withContext getPosts(category = "All", sortBy = sortBy, limit = limit)
        // Escape \, % _ so user input is literal inside ilike
        val escaped = clean
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
        try {
            val result = supabase.from("posts")
                .select {
                    filter {
                        ilike("caption", "%$escaped%")
                    }
                    if (sortBy == "highest_likes") {
                        order(column = "like_count", order = Order.DESCENDING)
                    } else {
                        order(column = "created_at", order = Order.DESCENDING)
                    }
                    limit(count = limit.toLong())
                }
                .decodeList<Post>()
                .take(limit)

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
     * Retrieves posts favorited by the current user.
     * Fetches only favorited ids first, then loads those posts directly
     * (no full-table pull + memory filter).
     */
    suspend fun getFavouritePosts(limit: Int = 30): List<Post> = withContext(Dispatchers.IO) {
        try {
            val favoritedPostIds = getFavoritedPostIdsForCurrentUser()
            if (favoritedPostIds.isEmpty()) return@withContext emptyList()

            val ids = favoritedPostIds.take(limit)
            val posts = ids.map { id ->
                async { getPostById(id) }
            }.awaitAll().filterNotNull()
            val likedIds = getLikedPostIdsForCurrentUser()
            posts.map { it.copy(isLikedByCurrentUser = likedIds.contains(it.id), isFavoritedByCurrentUser = true) }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * Counts total number of posts created by a specific user.
     */
    suspend fun getPostCount(userId: String): Int = withContext(Dispatchers.IO) {
        try {
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
     * Scoped to owner: refuses to delete others' posts client-side (RLS must also enforce).
     * Also removes orphan Storage files.
     */
    suspend fun deletePost(postId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val userId = getCurrentUserId() ?: error("Please log in first.")
            val target = getPostById(postId) ?: error("Post not found or no access.")
            if (target.userId.isNotBlank() && target.userId != userId) {
                error("You can only delete your own posts.")
            }
            // Delete child rows first to preserve referential integrity.
            supabase.from("post_likes").delete {
                filter { eq("post_id", postId) }
            }
            supabase.from("post_comments").delete {
                filter { eq("post_id", postId) }
            }
            supabase.from("post_favorites").delete {
                filter { eq("post_id", postId) }
            }

            // Delete the post row itself, owner-scoped (also enforced by RLS).
            supabase.from("posts").delete {
                filter {
                    eq("id", postId)
                    eq("user_id", userId)
                }
            }

            // Remove uploaded files so deleted posts leave no orphan objects; failures are ignored.
            runCatching {
                val paths = target.allMediaUrls.mapNotNull { urlToClothPath(it) }
                if (paths.isNotEmpty()) supabase.storage.from("cloth").delete(paths)
            }

            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun urlToClothPath(url: String): String? {
        // publicUrl = .../storage/v1/object/public/cloth/<path>
        val marker = "/cloth/"
        val idx = url.indexOf(marker)
        if (idx < 0) return null
        return url.substring(idx + marker.length).substringBefore("?")
            .takeIf { it.isNotBlank() }
    }

    /**
     * Helper function that dynamically checks media type (video/image) and uploads to Supabase storage.
     * Returns (publicUrl, isVideo, storagePath) so caller can roll back on failure.
     */
    private suspend fun uploadSingleMedia(
        context: Context,
        uri: Uri,
        userId: String
    ): Triple<String, Boolean, String> = withContext(Dispatchers.IO) {
        val mimeType = context.contentResolver.getType(uri) ?: ""
        val isVideo = mimeType.startsWith("video", ignoreCase = true) || uri.toString().lowercase().endsWith(".mp4")

        val fileExtension = when {
            isVideo -> "mp4"
            mimeType.contains("png") -> "png"
            mimeType.contains("webp") -> "webp"
            else -> "jpg"
        }
        val contentType = when {
            mimeType.isNotBlank() -> mimeType
            isVideo -> "video/mp4"
            fileExtension == "png" -> "image/png"
            else -> "image/jpeg"
        }
        val sizeLimit = if (isVideo) 100L * 1024 * 1024 else 15L * 1024 * 1024
        val mediaBytes = context.contentResolver.openInputStream(uri)
            ?.use { stream ->
                // Guard size without loading unbounded: read with cap
                val bytes = stream.readBytes()
                if (bytes.size > sizeLimit) error(
                    if (isVideo) "Video too large (max 100MB)." else "Image too large (max 15MB)."
                )
                bytes
            }
            ?: error("Could not read media URI: $uri")

        val mediaPath = "$userId/${UUID.randomUUID()}.$fileExtension"
        val bucket = supabase.storage.from("cloth")
        // UploadOptionBuilder.contentType is ktor ContentType (not String) — parse it.
        bucket.upload(mediaPath, mediaBytes) { this.contentType = ContentType.parse(contentType) }

        val publicUrl = bucket.publicUrl(mediaPath)
        return@withContext Triple(publicUrl, isVideo, mediaPath)
    }

    /**
     * Uploads an already rendered local file (used for design front/back images).
     */
    private suspend fun uploadSingleFile(
        file: File,
        userId: String
    ): Pair<String, String> = withContext(Dispatchers.IO) {
        if (!file.exists() || file.length() == 0L) error("Design file missing: ${file.name}")
        if (file.length() > 15L * 1024 * 1024) error("Design image too large (max 15MB).")
        val fileExtension = file.extension.takeIf { it.isNotBlank() } ?: "png"
        val contentType = when (fileExtension.lowercase()) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            else -> "image/jpeg"
        }
        val mediaPath = "$userId/${UUID.randomUUID()}.$fileExtension"
        val bucket = supabase.storage.from("cloth")
        bucket.upload(mediaPath, file.readBytes()) { this.contentType = ContentType.parse(contentType) }
        Pair(bucket.publicUrl(mediaPath), mediaPath)
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
            // Any upload or insert failure deletes every file uploaded so far,
            // otherwise retries pile orphan files in the cloth bucket.
            val completedPaths = java.util.Collections.synchronizedList(mutableListOf<String>())
            try {
                val fileUploads = mediaFiles.map { file ->
                    async {
                        uploadSingleFile(file, user.id).also { completedPaths.add(it.second) }
                    }
                }.awaitAll()

                // Concurrently upload selected media items.
                val uploadResults = imageUris.map { uri ->
                    async {
                        uploadSingleMedia(context, uri, user.id).also { completedPaths.add(it.third) }
                    }
                }.awaitAll()

                val uploadedPublicUrls = fileUploads.map { it.first } + uploadResults.map { it.first }
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
                    isVideo = hasVideo,
                    clothingTitle = title.trim().take(60),
                    clothingCategory = category,
                    postType = effectivePostType,
                    caption = bio.trim().take(280),
                    likeCount = 0,
                    designId = designId,
                    challengePostId = effectiveChallengeId
                )

                supabase.from("posts").insert(newPost)
            } catch (e: Exception) {
                // Roll back uploaded Storage files on ANY failure (upload or DB insert)
                runCatching {
                    val stale = completedPaths.toList()
                    if (stale.isNotEmpty()) supabase.storage.from("cloth").delete(stale)
                }
                throw e
            }

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

                try {
                    supabase.from("post_likes").insert(
                        mapOf(
                            "post_id" to postId,
                            "user_id" to userId,
                            "username" to username
                        )
                    )
                } catch (e: Exception) {
                    // Concurrent double-tap can hit unique(post_id,user_id): treat as liked
                    val msg = (e.message ?: "").lowercase()
                    if (!msg.contains("duplicate") && !msg.contains("unique") && !msg.contains("409")) throw e
                }
            }

            // like_count is maintained by a DB trigger; re-read it fresh after the toggle.
            supabase.from("posts").select {
                filter { eq("id", postId) }
            }.decodeList<Post>().firstOrNull()?.likeCount ?: -1
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
                    "comment_text" to content.trim().take(150)
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
     * Returns false when the write failed so the caller can roll back.
     */
    suspend fun toggleFavourite(postId: String, isSaved: Boolean): Boolean = withContext(Dispatchers.IO) {
        try {
            val userId = getCurrentUserId() ?: return@withContext false
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
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
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
    private suspend fun getLikedPostIdsForCurrentUser(): Set<String> = withContext(Dispatchers.IO) {
        try {
            val userId = getCurrentUserId() ?: return@withContext emptySet()
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
    private suspend fun getFavoritedPostIdsForCurrentUser(): Set<String> = withContext(Dispatchers.IO) {
        try {
            val userId = getCurrentUserId() ?: return@withContext emptySet()
            supabase.from("post_favorites").select {
                filter { eq("user_id", userId) }
            }.decodeList<SimplePostIdRow>().map { it.postId }.toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }
}
