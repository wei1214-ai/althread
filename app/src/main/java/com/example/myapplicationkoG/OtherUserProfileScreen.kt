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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.myapplicationkoG.ui.theme.Cyan
import com.example.myapplicationkoG.ui.theme.MidnightBlue
import kotlinx.coroutines.launch

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
    val scope = rememberCoroutineScope()

    var userPosts by remember { mutableStateOf<List<Post>>(emptyList()) }
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
        } finally {
            isLoading = false
        }
    }

    LaunchedEffect(userId) {
        loadProfileData()
    }

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

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                enabled = !isFollowActionLoading,
                onClick = {
                    scope.launch {
                        isFollowActionLoading = true

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
            ) {
                Text(if (isFollowing) "Unfollow" else "Follow")
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "${userPosts.size}",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = textColorForTheme(MidnightBlue)
                    )
                    Text("Posts", fontSize = 12.sp, color = textColorForTheme(Color.Gray))
                }

                Column(
                    modifier = Modifier.clickable {
                        onShowFollowers(userId)
                    },
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
                    modifier = Modifier.clickable {
                        onShowFollowing(userId)
                    },
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

        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Cyan)
            }
        } else if (userPosts.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(text = "No posts yet.", color = textColorForTheme(Color.Gray))
            }
        } else {
            LazyColumn(
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
