package com.example.myapplicationkoG

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.example.myapplicationkoG.ui.editor.AiChatDrawer
import com.example.myapplicationkoG.ui.theme.Cyan
import com.example.myapplicationkoG.ui.theme.MidnightBlue

// Helper to determine if a URL string points to a video
fun isVideoUrl(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    val lowercase = url.lowercase()
    return lowercase.contains(".mp4") ||
            lowercase.contains(".mov") ||
            lowercase.contains(".mkv") ||
            lowercase.contains(".webm") ||
            lowercase.contains("video")
}

// Reusable component that automatically switches between Image (Coil) and Video (ExoPlayer)
@Composable
fun PostMediaView(
    mediaUrl: String,
    modifier: Modifier = Modifier
        .fillMaxWidth()
        .height(300.dp)
        .clip(RoundedCornerShape(12.dp))
) {
    val context = LocalContext.current

    if (isVideoUrl(mediaUrl)) {
        // Render video using AndroidX Media3 ExoPlayer
        val exoPlayer = remember(mediaUrl) {
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(mediaUrl))
                prepare()
                playWhenReady = false // Set to true if auto-play is desired
            }
        }

        // Release player resources when component leaves screen
        DisposableEffect(exoPlayer) {
            onDispose {
                exoPlayer.release()
            }
        }

        Box(
            modifier = modifier.background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = true // Show video playback controls
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    } else {
        // Render standard image
        AsyncImage(
            model = mediaUrl,
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop
        )
    }
}

// Filter button component
@Composable
fun FilterChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Box(
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isSelected) Cyan
                else if (isDark) MaterialTheme.colorScheme.surfaceVariant
                else Color.LightGray
            )
            .clickable { onClick() }
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontWeight = FontWeight.SemiBold,
            color = if (isSelected) MidnightBlue else if (isDark) Color(0xFFD0D0D0) else Color.Gray,
            maxLines = 1
        )
    }
}

// Main Home Screen Composable
@Composable
fun HomeScreen(
    refreshKey: Int = 0,
    initialFilter: String = "For You",
    onUserClick: ((userId: String, username: String, avatarUrl: String) -> Unit)? = null,
    onAcceptChallenge: ((Post) -> Unit)? = null,
    onOpenChallengeSource: ((Post) -> Unit)? = null
) {
    // Selected filter state
    var selectedFilter by remember { mutableStateOf(initialFilter) }
    LaunchedEffect(initialFilter) {
        if (initialFilter != selectedFilter) selectedFilter = initialFilter
    }

    // Posts fetched from Supabase / Backend
    var posts by remember { mutableStateOf<List<Post>>(emptyList()) }

    // Loading and error states
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val repository = remember { PostRepository() }

    val filters = listOf(
        "For You",
        "Trend",
        "Challenge",
        "Vintage",
        "Streetwear"
    )

    var showAiChat by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val backgroundColor = MaterialTheme.colorScheme.background

    // Fetch posts whenever selectedFilter or refreshKey changes
    LaunchedEffect(selectedFilter, refreshKey) {
        isLoading = true
        errorMessage = null

        try {
            posts = repository.getPosts(category = selectedFilter)
        } catch (e: Exception) {
            errorMessage = e.message ?: "Something went wrong"
        } finally {
            isLoading = false
        }
    }

    val filterScrollState = rememberScrollState()

    // Main screen container
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundColor),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header bar and filter options
            item {
                Column {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(80.dp)
                    ) {
                        // App logo / title
                        Text(
                            text = "AltThread",
                            fontSize = 40.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = textColorForTheme(MidnightBlue),
                            modifier = Modifier.align(Alignment.Center)
                        )

                        // AI Assistant button
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Cyan)
                                .align(Alignment.CenterEnd),
                            contentAlignment = Alignment.Center
                        ) {
                            IconButton(onClick = { showAiChat = true }) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ai),
                                    contentDescription = "AI",
                                    tint = MidnightBlue,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                        }
                    }

                    // Horizontal scrolling categories
                    Row(
                        modifier = Modifier
                            .height(40.dp)
                            .drawWithContent {
                                drawContent()
                                if (filterScrollState.canScrollBackward) {
                                    drawRect(
                                        brush = Brush.horizontalGradient(
                                            0.0f to backgroundColor,
                                            0.15f to Color.Transparent
                                        )
                                    )
                                }
                                if (filterScrollState.canScrollForward) {
                                    drawRect(
                                        brush = Brush.horizontalGradient(
                                            0.85f to Color.Transparent,
                                            1.0f to backgroundColor
                                        )
                                    )
                                }
                            }
                            .horizontalScroll(filterScrollState),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        filters.forEach { filter ->
                            FilterChip(
                                label = filter,
                                isSelected = selectedFilter == filter,
                                onClick = { selectedFilter = filter }
                            )
                        }
                    }
                }
            }

            // Display loading indicator
            if (isLoading) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = Cyan)
                    }
                }
            }
            // Display error state
            else if (errorMessage != null) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(30.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Failed to load posts\n\n$errorMessage",
                            color = Color.Red
                        )
                    }
                }
            }
            // Display empty state
            else if (posts.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No posts found.",
                            color = textColorForTheme(Color.Gray)
                        )
                    }
                }
            }
            // Render posts list
            else {
                items(
                    items = posts,
                    key = { it.id }
                ) { post ->
                    PostCard(
                        post = post,
                        onUserClick = onUserClick,
                        onAcceptChallenge = onAcceptChallenge,
                        onOpenChallengeSource = onOpenChallengeSource
                    )
                }
            }
        }

        // Drawer overlay for AI chat
        AiChatDrawer(visible = showAiChat, onDismiss = { showAiChat = false })
    }
}