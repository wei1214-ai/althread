package com.example.myapplicationkoG

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Color.Companion.White
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.example.myapplicationkoG.ui.ProfileRepository
import com.example.myapplicationkoG.ui.UserProfile
import com.example.myapplicationkoG.ui.theme.Cyan
import com.example.myapplicationkoG.ui.theme.MidnightBlue
import kotlinx.coroutines.launch
import coil.ImageLoader
import coil.request.ImageRequest
import coil.decode.VideoFrameDecoder
import coil.request.videoFrameMillis

/**
 * Custom TabBar component for switching between combined posts/challenges and saved posts.
 */
@Composable
fun CustomTabBar(
    selectedTabIndex: Int,
    onTabSelected: (Int) -> Unit
) {
    val tabs = listOf("Uploaded", "Saved")
    val activeColor = textColorForTheme(MidnightBlue)
    val inactiveColor = textColorForTheme(Color.Gray)

    Column {
        TabRow(
            selectedTabIndex = selectedTabIndex,
            containerColor = MaterialTheme.colorScheme.surface,
            indicator = { tabPositions ->
                TabRowDefaults.SecondaryIndicator(
                    Modifier.tabIndicatorOffset(tabPositions[selectedTabIndex]),
                    color = activeColor
                )
            }
        ) {
            tabs.forEachIndexed { index, title ->
                val isSelected = selectedTabIndex == index

                Tab(
                    selected = isSelected,
                    onClick = { onTabSelected(index) },
                    text = {
                        Text(
                            text = title,
                            color = if (isSelected) activeColor else inactiveColor,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                )
            }
        }
    }
}

/**
 * Main Profile Screen displaying user profile info, metrics, digital wardrobe banner, and grid content tabs.
 */
@Composable
fun ProfileScreen(
    refreshKey: Int = 0,
    onEditProfile: () -> Unit,
    onShowFollowers: (String) -> Unit,
    onShowFollowing: (String) -> Unit,
    onOpenSetting: () -> Unit
) {
    var profile by remember { mutableStateOf<UserProfile?>(null) }
    var errorMessage by remember { mutableStateOf("") }
    val repository = remember { ProfileRepository() }
    val postRepository = remember { PostRepository() }
    val followRepository = remember { FollowRepository() }
    val context = LocalContext.current
    val thumbnailImageLoader = remember {
        ImageLoader.Builder(context)
            .components {
                add(VideoFrameDecoder.Factory())
            }
            .build()
    }
    val scope = rememberCoroutineScope()

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    var savedPosts by remember { mutableStateOf<List<Post>>(emptyList()) }
    var isSavedLoading by remember { mutableStateOf(false) }
    var selectedPostForDetail by remember { mutableStateOf<Post?>(null) }
    var myPosts by remember { mutableStateOf<List<Post>>(emptyList()) }
    var isMyPostsLoading by remember { mutableStateOf(true) }

    var postCount by remember { mutableIntStateOf(0) }
    var followerCount by remember { mutableIntStateOf(0) }
    var followingCount by remember { mutableIntStateOf(0) }

    val avatarPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { imageUri ->
        if (imageUri != null) {
            scope.launch {
                try {
                    repository.uploadAvatar(context, imageUri)
                    profile = repository.getMyProfile()
                } catch (e: Exception) {
                    errorMessage = e.message ?: "Could not upload avatar"
                }
            }
        }
    }

    // Helper to refresh posts and count after deletion
    fun refreshMyPosts(userId: String) {
        scope.launch {
            try {
                val allPosts = postRepository.getPosts(category = "All")
                myPosts = allPosts.filter { post -> post.userId == userId }
                postCount = postRepository.getPostCount(userId)
            } catch (e: Exception) {
                errorMessage = e.message ?: "Could not refresh posts"
            }
        }
    }

    // Reload when a post/challenge is published, including when this destination
    // remains on the navigation back stack.
    LaunchedEffect(refreshKey) {
        isMyPostsLoading = true
        try {
            val loadedProfile = repository.getMyProfile()
            profile = loadedProfile

            val allPosts = postRepository.getPosts(category = "All")
            myPosts = allPosts.filter { post -> post.userId == loadedProfile.id }
            postCount = postRepository.getPostCount(loadedProfile.id)
            followerCount = followRepository.getFollowerCount(loadedProfile.id)
            followingCount = followRepository.getFollowingCount(loadedProfile.id)
        } catch (e: Exception) {
            errorMessage = e.message ?: "Could not load profile"
        } finally {
            isMyPostsLoading = false
        }
    }

    LaunchedEffect(selectedTabIndex) {
        if (selectedTabIndex == 1) {
            isSavedLoading = true
            errorMessage = ""
            try {
                savedPosts = postRepository.getFavouritePosts()
            } catch (e: Exception) {
                errorMessage = e.message ?: "Could not load saved posts"
            } finally {
                isSavedLoading = false
            }
        }
    }

    // Detail Modal Dialog when post thumbnail is clicked
    selectedPostForDetail?.let { selectedPost ->
        Dialog(onDismissRequest = {
            selectedPostForDetail = null
            if (selectedTabIndex == 1) {
                scope.launch { savedPosts = postRepository.getFavouritePosts() }
            }
        }) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp)
            ) {
                PostCard(
                    post = selectedPost,
                    onDeletePost = { deletedPost ->
                        // 1. Close modal immediately
                        selectedPostForDetail = null

                        // 2. Remove post from UI state instantly
                        myPosts = myPosts.filter { it.id != deletedPost.id }
                        postCount = (postCount - 1).coerceAtLeast(0)

                        // 3. Sync with backend DB
                        profile?.let { currentProfile ->
                            refreshMyPosts(currentProfile.id)
                        }
                    }
                )
            }
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier
            .fillMaxSize()
            .background(color = MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(shape = RoundedCornerShape(size = 12.dp))
                        .background(color = Cyan)
                        .align(Alignment.End),
                    contentAlignment = Alignment.Center
                ) {
                    IconButton(onClick = onOpenSetting) {
                        Icon(
                            painter = painterResource(id = R.drawable.setting),
                            contentDescription = "Settings",
                            tint = MidnightBlue,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                }

                Row {
                    Box(modifier = Modifier.size(120.dp)) {
                        AsyncImage(
                            model = profile?.avatar_url?.takeIf { it.isNotBlank() }
                                ?: R.drawable.avatar,
                            contentDescription = "Avatar",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(CircleShape)
                        )

                        IconButton(
                            onClick = { avatarPicker.launch("image/*") },
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .size(36.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Cyan)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.edit),
                                contentDescription = "Change avatar",
                                tint = MidnightBlue,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .height(120.dp)
                            .padding(start = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(
                            space = 5.dp,
                            alignment = Alignment.CenterVertically
                        )
                    ) {
                        Text(
                            text = profile?.username ?: "New user",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            color = textColorForTheme(MidnightBlue)
                        )
                        Text(
                            text = "@${profile?.username ?: "newuser"}",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp,
                            color = textColorForTheme(MidnightBlue)
                        )
                        Text(
                            text = profile?.bio ?: "Add a bio in Edit profile",
                            fontSize = 14.sp,
                            color = textColorForTheme(MidnightBlue)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = onEditProfile,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Cyan,
                        contentColor = MidnightBlue
                    )
                ) {
                    Text("Edit profile", fontWeight = FontWeight.Bold, color = MidnightBlue)
                }

                if (errorMessage.isNotBlank()) {
                    Text(
                        text = errorMessage,
                        color = Color.Red
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row {
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "$postCount",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            color = textColorForTheme(MidnightBlue)
                        )
                        Text("Posts", fontSize = 14.sp, color = textColorForTheme(MidnightBlue))
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { profile?.let { onShowFollowers(it.id) } },
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "$followerCount",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            color = textColorForTheme(MidnightBlue)
                        )
                        Text("Followers", fontSize = 14.sp, color = textColorForTheme(MidnightBlue))
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { profile?.let { onShowFollowing(it.id) } },
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "$followingCount",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            color = textColorForTheme(MidnightBlue)
                        )
                        Text("Following", fontSize = 14.sp, color = textColorForTheme(MidnightBlue))
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                CustomTabBar(
                    selectedTabIndex = selectedTabIndex,
                    onTabSelected = { index -> selectedTabIndex = index }
                )
            }
        }

        when (selectedTabIndex) {
            0 -> {
                if (isMyPostsLoading) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(150.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = MidnightBlue)
                        }
                    }
                } else if (myPosts.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(30.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No uploaded posts yet.",
                                color = textColorForTheme(Color.Gray)
                            )
                        }
                    }
                } else {
                    items(myPosts, key = { it.id }) { post ->
                        val coverUrl = post.mediaUrls.firstOrNull() ?: post.mediaUrl

                        Box(
                            modifier = Modifier
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable {
                                    selectedPostForDetail = post
                                }
                        ) {
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(coverUrl)
                                    .videoFrameMillis(1_000)
                                    .crossfade(true)
                                    .build(),
                                imageLoader = thumbnailImageLoader,
                                contentDescription = post.clothingTitle,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )

                        }
                    }
                }
            }

            1 -> {
                if (isSavedLoading) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(150.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = MidnightBlue)
                        }
                    }
                } else if (savedPosts.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(30.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "No saved posts found.",
                                color = textColorForTheme(Color.Gray)
                            )
                        }
                    }
                } else {
                    items(savedPosts, key = { it.id }) { post ->
                        val coverUrl = post.mediaUrls.firstOrNull() ?: post.mediaUrl

                        Box(
                            modifier = Modifier
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable {
                                    selectedPostForDetail = post
                                }
                        ) {
                            AsyncImage(
                                model = coverUrl,
                                contentDescription = post.clothingTitle,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )

                        }
                    }
                }
            }
        }
    }
}
