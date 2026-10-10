package com.example.myapplicationkoG

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.myapplicationkoG.ui.theme.Cyan
import com.example.myapplicationkoG.ui.theme.MidnightBlue
import kotlinx.coroutines.launch

/**
 * Read-only profile view for another user: header, follow controls, and their post grid.
 */
@Composable
fun OtherUserProfileScreen(
    userId: String,
    username: String,
    avatarUrl: String,
    onBackClick: () -> Unit,
    onShowFollowers: (String) -> Unit,
    onShowFollowing: (String) -> Unit
) {
    val postRepository = remember { PostRepository() }
    val followRepository = remember { FollowRepository() }
    val profileRepository = remember { com.example.myapplicationkoG.ui.ProfileRepository() }
    val scope = rememberCoroutineScope()

    var userPosts by remember { mutableStateOf<List<Post>>(emptyList()) }
    var userBio by remember { mutableStateOf<String?>(null) }
    var freshAvatarUrl by remember { mutableStateOf<String?>(null) }
    var followerCount by remember { mutableIntStateOf(0) }
    var followingCount by remember { mutableIntStateOf(0) }
    var isFollowing by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    var isFollowActionLoading by remember { mutableStateOf(false) }

    suspend fun loadProfileData() {
        isLoading = true

        try {
            val allPosts = postRepository.getPosts(category = "All")

            userPosts = allPosts.filter { it.userId == userId }
            followerCount = followRepository.getFollowerCount(userId)
            followingCount = followRepository.getFollowingCount(userId)
            isFollowing = followRepository.isFollowing(userId)
            val profile = profileRepository.getProfileById(userId)
            userBio = profile?.bio?.takeIf { it.isNotBlank() }
            // Avatar from profiles (live), not the nav arg: post rows carry a
            // stale snapshot from post time, and miss later avatar changes.
            freshAvatarUrl = profile?.avatar_url?.takeIf { it.isNotBlank() }
        } finally {
            isLoading = false
        }
    }

    LaunchedEffect(userId) {
        loadProfileData()
    }

    fun doFollowToggle() {
        // Guard synchronously: setting the flag inside the coroutine would let a
        // second tap slip through before the first one starts running.
        if (isFollowActionLoading) return
        isFollowActionLoading = true
        scope.launch {
            try {
                if (isFollowing) {
                    followRepository.unfollow(userId)
                } else {
                    followRepository.follow(userId)
                }

                // Refreshes the button and follower number immediately.
                isFollowing = followRepository.isFollowing(userId)
                followerCount = followRepository.getFollowerCount(userId)
            } finally {
                isFollowActionLoading = false
            }
        }
    }

    // Landscape (~360dp tall): pinned header + weighted list would squeeze the
    // list to zero. Instead the whole page scrolls; portrait keeps the pinned
    // header + internal list.
    val isLandscape =
        LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBackClick) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = textColorForTheme(MidnightBlue)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = username,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = textColorForTheme(MidnightBlue)
            )
        }

        if (isLandscape) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                OtherUserHeader(
                    username = username,
                    avatarUrl = (freshAvatarUrl ?: avatarUrl).ifBlank { "https://via.placeholder.com/150" },
                    userBio = userBio,
                    isFollowing = isFollowing,
                    isFollowActionLoading = isFollowActionLoading,
                    followerCount = followerCount,
                    followingCount = followingCount,
                    postCount = userPosts.size,
                    onFollowClick = { doFollowToggle() },
                    onShowFollowers = { onShowFollowers(userId) },
                    onShowFollowing = { onShowFollowing(userId) }
                )
                Spacer(modifier = Modifier.height(16.dp))
                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = Cyan)
                    }
                } else if (userPosts.isEmpty()) {
                    Text(text = "No posts yet.", color = textColorForTheme(Color.Gray))
                } else {
                    userPosts.forEach { post ->
                        PostCard(post = post)
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }
        } else {
            OtherUserHeader(
                username = username,
                avatarUrl = avatarUrl,
                userBio = userBio,
                isFollowing = isFollowing,
                isFollowActionLoading = isFollowActionLoading,
                followerCount = followerCount,
                followingCount = followingCount,
                postCount = userPosts.size,
                onFollowClick = { doFollowToggle() },
                onShowFollowers = { onShowFollowers(userId) },
                onShowFollowing = { onShowFollowing(userId) }
            )

            if (isLoading) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Cyan)
                }
            } else if (userPosts.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "No posts yet.", color = textColorForTheme(Color.Gray))
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(userPosts, key = { it.id }) { post ->
                        PostCard(post = post)
                    }
                }
            }
        }
    }
}

@Composable
private fun OtherUserHeader(
    username: String,
    avatarUrl: String,
    userBio: String?,
    isFollowing: Boolean,
    isFollowActionLoading: Boolean,
    followerCount: Int,
    followingCount: Int,
    postCount: Int,
    onFollowClick: () -> Unit,
    onShowFollowers: () -> Unit,
    onShowFollowing: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AsyncImage(
            model = avatarUrl.ifBlank {
                "https://via.placeholder.com/150"
            },
            contentDescription = "Avatar",
            modifier = Modifier
                .size(90.dp)
                .clip(CircleShape),
            contentScale = ContentScale.Crop
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "@$username",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = textColorForTheme(MidnightBlue)
        )

        if (!userBio.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = userBio,
                fontSize = 13.sp,
                color = textColorForTheme(Color.Gray)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            enabled = !isFollowActionLoading,
            onClick = onFollowClick,
            modifier = Modifier
                .width(200.dp)
                .height(52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Cyan,
                contentColor = MidnightBlue,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        ) {
            Text(
                if (isFollowActionLoading) "Please wait..."
                else if (isFollowing) "Unfollow" else "Follow",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "$postCount",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = textColorForTheme(MidnightBlue)
                )
                Text("Posts", fontSize = 12.sp, color = textColorForTheme(Color.Gray))
            }

            Column(
                modifier = Modifier.clickable(onClick = onShowFollowers),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "$followerCount",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = textColorForTheme(MidnightBlue)
                )
                Text("Followers", fontSize = 12.sp, color = textColorForTheme(Color.Gray))
            }

            Column(
                modifier = Modifier.clickable(onClick = onShowFollowing),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "$followingCount",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = textColorForTheme(MidnightBlue)
                )
                Text("Following", fontSize = 12.sp, color = textColorForTheme(Color.Gray))
            }
        }
    }
}
