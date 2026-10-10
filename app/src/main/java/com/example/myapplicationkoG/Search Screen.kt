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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.example.myapplicationkoG.ui.theme.Cyan
import com.example.myapplicationkoG.ui.theme.MidnightBlue
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.compose.ui.platform.LocalContext
import coil.ImageLoader
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import coil.request.videoFrameMillis

/**
 * Search screen: debounced keyword search over posts with a sort menu and a grid of results.
 */
@Composable
fun SearchScreen() {

    var keyword by remember { mutableStateOf("") }
    var selectedSortBy by remember { mutableStateOf("latest") }
    var isFilterMenuExpanded by remember { mutableStateOf(false) }

    var results by remember { mutableStateOf<List<Post>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var selectedPostForDetail by remember { mutableStateOf<Post?>(null) }

    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }

    val thumbnailImageLoader = remember(appContext) {
        ImageLoader.Builder(appContext)
            .components {
                add(VideoFrameDecoder.Factory())
            }
            .build()
    }

    val repository = remember { PostRepository() }

    // Observe keyword and sort changes: debounce first, then query; drop stale results.
    LaunchedEffect(keyword, selectedSortBy) {
        val currentKeyword = keyword
        val currentSort = selectedSortBy
        // Debounce before showing loading to avoid flicker on fast typing
        delay(400)
        // If keyword/sort changed during debounce, this effect is cancelled and relaunched;
        // cooperative check so a cancelled effect does not touch state.
        if (!coroutineContext.isActive) return@LaunchedEffect
        if (currentKeyword != keyword || currentSort != selectedSortBy) return@LaunchedEffect
        isLoading = true
        try {
            results = if (currentKeyword.isBlank()) {
                repository.getPosts(category = "All", sortBy = currentSort)
            } else {
                repository.searchPosts(query = currentKeyword.trim(), sortBy = currentSort)
            }
            // Drop stale response if user typed meanwhile
            if (!coroutineContext.isActive) return@LaunchedEffect
            if (currentKeyword != keyword || currentSort != selectedSortBy) return@LaunchedEffect
        } catch (e: Exception) {
            if (currentKeyword == keyword && currentSort == selectedSortBy) results = emptyList()
        } finally {
            if (currentKeyword == keyword && currentSort == selectedSortBy) isLoading = false
        }
    }

    selectedPostForDetail?.let { post ->
        Dialog(onDismissRequest = { selectedPostForDetail = null }) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp)
            ) {
                PostCard(post = post)
            }
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
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {

            OutlinedTextField(
                value = keyword,
                onValueChange = { if (it.length <= 30) keyword = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text(text = "Search clothes, tags...") },
                leadingIcon = {
                    Icon(
                        painter = painterResource(id = R.drawable.search),
                        contentDescription = "Search",
                        tint = Color.Gray
                    )
                },
                trailingIcon = {
                    if (keyword.isNotEmpty()) {
                        IconButton(onClick = { keyword = "" }) {
                            Icon(
                                painter = painterResource(id = R.drawable.close),
                                contentDescription = "Clear",
                                tint = Color.Gray
                            )
                        }
                    }
                },
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Box {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Cyan)
                        .clickable { isFilterMenuExpanded = true },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.filter),
                        contentDescription = "Filter Options",
                        tint = MidnightBlue,
                        modifier = Modifier.size(22.dp)
                    )
                }

                DropdownMenu(
                    expanded = isFilterMenuExpanded,
                    onDismissRequest = { isFilterMenuExpanded = false },
                    modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                ) {
                    DropdownMenuItem(
                        text = { Text("Latest Posts") },
                        onClick = {
                            selectedSortBy = "latest"
                            isFilterMenuExpanded = false
                        },
                        trailingIcon = {
                            if (selectedSortBy == "latest") {
                                Icon(painter = painterResource(id = R.drawable.check), contentDescription = null, tint = MidnightBlue)
                            }
                        }
                    )

                    DropdownMenuItem(
                        text = { Text("Most Liked ❤️") },
                        onClick = {
                            selectedSortBy = "highest_likes"
                            isFilterMenuExpanded = false
                        },
                        trailingIcon = {
                            if (selectedSortBy == "highest_likes") {
                                Icon(painter = painterResource(id = R.drawable.check), contentDescription = null, tint = MidnightBlue)
                            }
                        }
                    )
                }
            }
        }

        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = MidnightBlue)
            }
        } else if (results.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No outfits found",
                    color = textColorForTheme(Color.Gray),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(
                    items = results,
                    key = { it.id }
                ) { post ->
                    val coverUrl = post.mediaUrls.firstOrNull() ?: post.mediaUrl

                    AsyncImage(
                        model = ImageRequest.Builder(appContext)
                            .data(coverUrl)
                            .size(600)
                            .videoFrameMillis(1_000)
                            .crossfade(true)
                            .build(),
                        imageLoader = thumbnailImageLoader,
                        contentDescription = post.caption,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(230.dp)
                            .clip(RoundedCornerShape(15.dp))
                            .clickable {
                                selectedPostForDetail = post
                            },
                        contentScale = ContentScale.Crop
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun HScreenPreview() {
    SearchScreen()
}
