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

@Serializable
private data class SimplePostIdRow(
    @SerialName("post_id") val postId: String
)

@Serializable
private data class NewPost(
    @SerialName("user_id")
    val userId: String,

    val username: String,

    @SerialName("user_profile_pic_url")
    val userProfilePicUrl: String?,

    // Retained for compatibility with clients that support one media URL.
    @SerialName("media_url")
    val mediaUrl: String,

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

class PostRepository {

    private fun getCurrentUserId(): String? {
        return supabase.auth.currentUserOrNull()?.id
    }

    /** Returns posts for the selected feed category and sort order. */
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

    /** Searches captions using a bounded, literal query. */
    suspend fun searchPosts(query: String, sortBy: String = "latest", limit: Int = 30): List<Post> = withContext(Dispatchers.IO) {
        val clean = query.trim().take(30)
        if (clean.isBlank()) return@withContext getPosts(category = "All", sortBy = sortBy, limit = limit)
        // Escape SQL LIKE metacharacters so the query remains literal.
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

    /** Returns the current user's saved posts. */
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

    /** Deletes an owned post, its related records, and associated media. */
    suspend fun deletePost(postId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val userId = getCurrentUserId() ?: error("Please log in first.")
            val target = getPostById(postId) ?: error("Post not found or no access.")
            if (target.userId.isNotBlank() && target.userId != userId) {
                error("You can only delete your own posts.")
            }
            // Remove dependent rows before deleting the post.
            supabase.from("post_likes").delete {
                filter { eq("post_id", postId) }
            }
            supabase.from("post_comments").delete {
                filter { eq("post_id", postId) }
            }
            supabase.from("post_favorites").delete {
                filter { eq("post_id", postId) }
            }

            supabase.from("posts").delete {
                filter {
                    eq("id", postId)
                    eq("user_id", userId)
                }
            }

            // Storage cleanup is best effort; database deletion has already succeeded.
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
        val marker = "/cloth/"
        val idx = url.indexOf(marker)
        if (idx < 0) return null
        return url.substring(idx + marker.length).substringBefore("?")
            .takeIf { it.isNotBlank() }
    }

    /** Uploads media and returns its public URL, media type, and storage path. */
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
                val bytes = stream.readBytes()
                if (bytes.size > sizeLimit) error(
                    if (isVideo) "Video too large (max 100MB)." else "Image too large (max 15MB)."
                )
                bytes
            }
            ?: error("Could not read media URI: $uri")

        val mediaPath = "$userId/${UUID.randomUUID()}.$fileExtension"
        val bucket = supabase.storage.from("cloth")
        bucket.upload(mediaPath, mediaBytes) { this.contentType = ContentType.parse(contentType) }

        val publicUrl = bucket.publicUrl(mediaPath)
        return@withContext Triple(publicUrl, isVideo, mediaPath)
    }

    /** Uploads a rendered design image. */
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

    /** Uploads post media and creates the corresponding database record. */
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

            // Design renders precede selected media and therefore become primary images.
            val completedPaths = java.util.Collections.synchronizedList(mutableListOf<String>())
            try {
                val fileUploads = mediaFiles.map { file ->
                    async {
                        uploadSingleFile(file, user.id).also { completedPaths.add(it.second) }
                    }
                }.awaitAll()

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
                // Prevent orphaned objects when any upload or insert fails.
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

    /** Toggles the current user's like and returns the persisted count. */
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
                    // A duplicate insert indicates a concurrent like request already succeeded.
                    val msg = (e.message ?: "").lowercase()
                    if (!msg.contains("duplicate") && !msg.contains("unique") && !msg.contains("409")) throw e
                }
            }

            // The database trigger maintains the count; read back its authoritative value.
            supabase.from("posts").select {
                filter { eq("id", postId) }
            }.decodeList<Post>().firstOrNull()?.likeCount ?: -1
        } catch (e: Exception) {
            e.printStackTrace()
            -1
        }
    }

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

    /** Adds or removes a post from the current user's saved items. */
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
