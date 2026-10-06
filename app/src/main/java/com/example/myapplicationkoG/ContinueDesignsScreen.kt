package com.example.myapplicationkoG

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.myapplicationkoG.ui.theme.MidnightBlue
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ContinueDesignsScreen(
    onOpenDesign: (DesignRow) -> Unit,
    onBack: () -> Unit,
    pickMode: Boolean = false,
    onPickDesign: ((DesignRow) -> Unit)? = null
) {
    val context = LocalContext.current
    val repo = remember { DesignRepository() }
    val scope = rememberCoroutineScope()
    var designs by remember { mutableStateOf<List<DesignRow>>(emptyList()) }
    var thumbs by remember { mutableStateOf(mapOf<String, java.io.File>()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<DesignRow?>(null) }
    val isDarkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val screenBackground = if (isDarkTheme) MaterialTheme.colorScheme.background else Color.White

    LaunchedEffect(Unit) {
        isLoading = true
        error = null
        try {
            designs = repo.listMyDesigns()
            val rendered = mutableMapOf<String, java.io.File>()
            designs.forEach { row ->
                runCatching {
                    val front = repo.ensureFrontFile(context, row)
                    rendered[row.id] = renderDesignThumb(context, row, front)
                }
            }
            thumbs = rendered
        } catch (e: Exception) {
            error = e.message ?: "Failed to load designs"
        } finally {
            isLoading = false
        }
    }

    if (deleteTarget != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete design?") },
            text = { Text("This will permanently remove \"${deleteTarget!!.name}\".") },
            confirmButton = {
                TextButton(onClick = {
                    val row = deleteTarget!!
                    deleteTarget = null
                    scope.launch {
                        repo.deleteDesign(context, row)
                        designs = designs.filter { it.id != row.id }
                    }
                }) { Text("Delete", color = Color.Red) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        containerColor = screenBackground,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        if (pickMode) "Choose a Design" else "My Designs",
                        fontWeight = FontWeight.Bold,
                        color = textColorForTheme(MidnightBlue)
                    )
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = screenBackground,
                    titleContentColor = textColorForTheme(MidnightBlue),
                    navigationIconContentColor = textColorForTheme(MidnightBlue)
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painter = painterResource(id = R.drawable.arrowleft), contentDescription = "Back", tint = textColorForTheme(MidnightBlue))
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(screenBackground)
        ) {
            when {
                isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = MidnightBlue
                    )
                }
                error != null -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(text = "Failed to load", color = textColorForTheme(MidnightBlue), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(text = error!!, color = textColorForTheme(Color.Gray), fontSize = 13.sp)
                    }
                }
                designs.isEmpty() -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (isDarkTheme) MaterialTheme.colorScheme.surfaceVariant else Color(0xFFF0F0F0)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = "◇", fontSize = 32.sp, color = textColorForTheme(Color.Gray))
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(text = "No saved designs yet", color = textColorForTheme(MidnightBlue), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (pickMode) "Save a design first,\nthen come back to share it" else "Start a design and hit Save\nto see it here",
                            color = textColorForTheme(Color.Gray),
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                    }
                }
                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp)
                    ) {
                        items(designs, key = { it.id }) { row ->
                            DesignBlock(
                                row = row,
                                thumbFile = thumbs[row.id],
                                pickMode = pickMode,
                                onClick = {
                                    if (pickMode) onPickDesign?.invoke(row) else onOpenDesign(row)
                                },
                                onLongPress = { if (!pickMode) deleteTarget = row }
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DesignBlock(
    row: DesignRow,
    thumbFile: java.io.File?,
    pickMode: Boolean = false,
    onClick: () -> Unit,
    onLongPress: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(25.dp),
        colors = CardDefaults.cardColors(containerColor = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) MaterialTheme.colorScheme.surface else Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(230.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = if (pickMode) null else onLongPress
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 10.dp, top = 10.dp, end = 10.dp, bottom = 0.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
            ) {
                val imageModel = thumbFile?.takeIf { it.exists() } ?: row.frontUrl?.takeIf { it.isNotBlank() }
                if (imageModel != null) {
                    AsyncImage(
                        model = imageModel,
                        contentDescription = row.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.studio),
                            contentDescription = "No image",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
                if (!pickMode) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .size(30.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(com.example.myapplicationkoG.ui.theme.Cyan)
                            .clickable { onLongPress() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(id = com.example.myapplicationkoG.R.drawable.deletedesign),
                            contentDescription = "Delete design",
                            tint = com.example.myapplicationkoG.ui.theme.MidnightBlue,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
            Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 10.dp)) {
                Text(
                    text = row.name.ifBlank { "Untitled" },
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 14.sp,
                    color = textColorForTheme(MidnightBlue),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (row.updatedAt.length >= 10) row.updatedAt.take(10) else row.updatedAt,
                    fontSize = 11.sp,
                    color = textColorForTheme(Color.Gray)
                )
            }
        }
    }
}