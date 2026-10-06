package com.example.myapplicationkoG

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.example.myapplicationkoG.ui.UserProfile
import com.example.myapplicationkoG.ui.theme.MidnightBlue

@Composable
fun FollowListScreen(
    userId: String,
    showFollowers: Boolean,
    onBackClick: () -> Unit,
    onUserClick: (userId: String, username: String, avatarUrl: String) -> Unit
) {
    val repository = remember { FollowRepository() }

    var users by remember { mutableStateOf<List<UserProfile>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(userId, showFollowers) {
        isLoading = true

        try {
            users = if (showFollowers) {
                repository.getFollowers(userId)
            } else {
                repository.getFollowing(userId)
            }
        } finally {
            isLoading = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBackClick) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = textColorForTheme(MidnightBlue)
                )
            }

            Text(
                text = if (showFollowers) "Followers" else "Following",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = textColorForTheme(MidnightBlue)
            )
        }

        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = MidnightBlue)
            }
        } else if (users.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (showFollowers) {
                        "No followers yet."
                    } else {
                        "Not following anyone yet."
                    },
                    color = textColorForTheme(Color.Gray)
                )
            }
        } else {
            LazyColumn {
                items(users, key = { it.id }) { user ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onUserClick(
                                    user.id,
                                    user.username ?: "User",
                                    user.avatar_url ?: ""
                                )
                            }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AsyncImage(
                            model = user.avatar_url
                                ?: "https://via.placeholder.com/150",
                            contentDescription = "User avatar",
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape),
                            contentScale = ContentScale.Crop
                        )

                        Spacer(modifier = Modifier.width(12.dp))

                        Column {
                            Text(
                                text = user.username ?: "User",
                                fontWeight = FontWeight.Bold,
                                color = textColorForTheme(MidnightBlue)
                            )

                            Text(
                                text = "@${user.username ?: "user"}",
                                color = textColorForTheme(Color.Gray),
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
