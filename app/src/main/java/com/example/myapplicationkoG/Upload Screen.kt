package com.example.myapplicationkoG

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.myapplicationkoG.di.ServiceLocator
import com.example.myapplicationkoG.ui.theme.Cyan
import com.example.myapplicationkoG.ui.theme.LightGray
import com.example.myapplicationkoG.ui.theme.MidnightBlue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

@Composable
private fun UploadTabItem(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(25.dp))
            .background(if (isSelected) Cyan else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            color = if (isSelected) MidnightBlue else textColorForTheme(Color.Gray),
            fontSize = 14.sp
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UploadScreen(
    onClose: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { PostRepository() }
    val isDarkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val screenBackground = MaterialTheme.colorScheme.background
    val contentSurface = if (isDarkTheme) MaterialTheme.colorScheme.surface else Color.White
    // Match Challenge picker block: use surface/outline directly so Post and Challenge have same color in dark mode
    val pickerSurface = MaterialTheme.colorScheme.surface
    val pickerBorder = MaterialTheme.colorScheme.outline
    val tabBackground = if (isDarkTheme) MaterialTheme.colorScheme.surfaceVariant else LightGray
    val textFieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = textColorForTheme(Color.Black),
        unfocusedTextColor = textColorForTheme(Color.Black),
        focusedLabelColor = textColorForTheme(MidnightBlue),
        unfocusedLabelColor = textColorForTheme(Color.Gray),
        focusedPlaceholderColor = textColorForTheme(Color.Gray),
        unfocusedPlaceholderColor = textColorForTheme(Color.Gray),
        focusedBorderColor = MidnightBlue,
        unfocusedBorderColor = pickerBorder,
        focusedContainerColor = contentSurface,
        unfocusedContainerColor = contentSurface
    )

    var selectedTab by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("Post") } // Post, Challenge, Design

    // Post states
    var selectedUris by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(emptyList<Uri>()) }
    var captionInput by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var selectedCategory by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("Streetwear") }
    var isUploading by remember { mutableStateOf(false) }
    val categoriesList = listOf("Trend", "Vintage", "Streetwear")

    // Challenge states - separate caption from Post to avoid sharing
    var challengeTitleInput by remember { mutableStateOf("") }
    var challengeCaptionInput by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var frontOrigUri by remember { mutableStateOf<Uri?>(null) }
    var backOrigUri by remember { mutableStateOf<Uri?>(null) }
    var frontCutoutPath by remember { mutableStateOf<String?>(null) }
    var backCutoutPath by remember { mutableStateOf<String?>(null) }
    var frontError by remember { mutableStateOf<String?>(null) }
    var backError by remember { mutableStateOf<String?>(null) }
    var isProcessingFront by remember { mutableStateOf(false) }
    var isProcessingBack by remember { mutableStateOf(false) }

    var postCameraUri by remember { mutableStateOf<Uri?>(null) }
    var frontCameraUri by remember { mutableStateOf<Uri?>(null) }
    var backCameraUri by remember { mutableStateOf<Uri?>(null) }
    var pendingCameraSide by remember { mutableStateOf<String?>(null) }

    // Design share states
    val designRepository = remember { DesignRepository() }
    var designRows by remember { mutableStateOf<List<DesignRow>?>(null) }
    var selectedDesign by remember { mutableStateOf<DesignRow?>(null) }
    var designFrontFile by remember { mutableStateOf<File?>(null) }
    var designBackFile by remember { mutableStateOf<File?>(null) }
    var isRenderingDesign by remember { mutableStateOf(false) }
    var designCaptionInput by remember { mutableStateOf("") }
    var designExtraUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var showDesignPicker by remember { mutableStateOf(false) }

    fun loadMyDesigns() {
        scope.launch {
            designRows = try {
                designRepository.listMyDesigns()
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    LaunchedEffect(Unit) { loadMyDesigns() }

    fun onDesignPicked(row: DesignRow) {
        showDesignPicker = false
        selectedDesign = row
        designFrontFile = null
        designBackFile = null
        designExtraUris = emptyList()
        isRenderingDesign = true
        scope.launch {
            try {
                val (front, back) = renderDesignShareImages(context, designRepository, row)
                designFrontFile = front
                designBackFile = back
            } catch (e: Exception) {
                selectedDesign = null
                Toast.makeText(context, "Could not render design: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                isRenderingDesign = false
            }
        }
    }

    fun createCameraUri(): Uri {
        val file = File(context.cacheDir, "camera_${System.currentTimeMillis()}.jpg")
        file.parentFile?.mkdirs()
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }
    fun createVideoUri(): Uri {
        val file = File(context.cacheDir, "camera_${System.currentTimeMillis()}.mp4")
        file.parentFile?.mkdirs()
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    // Check if URI is a video
    fun isVideoUri(uri: Uri): Boolean {
        val type = context.contentResolver.getType(uri)
        return type?.startsWith("video/") == true || uri.toString().contains(".mp4")
    }

    // Gallery picker: handle 1 video or up to 9 photos
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 9)
    ) { uris ->
        if (uris.isNotEmpty()) {
            val containsVideo = uris.any { isVideoUri(it) }
            if (containsVideo) {
                // If contains video, take only first video, only 1 video allowed
                val videoUri = uris.first { isVideoUri(it) }
                selectedUris = listOf(videoUri)
                Toast.makeText(context, "Selected 1 video", Toast.LENGTH_SHORT).show()
            } else {
                // If all are images, check if exceeds 9
                if (selectedUris.any { isVideoUri(it) }) {
                    // If previously selected video, overwrite with new images
                    selectedUris = uris.take(9)
                } else if (selectedUris.size + uris.size > 9) {
                    Toast.makeText(context, "Max 9 photos allowed", Toast.LENGTH_SHORT).show()
                    selectedUris = (selectedUris + uris).take(9)
                } else {
                    selectedUris = selectedUris + uris
                }
            }
        }
    }

    val takePostPicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) postCameraUri?.let { uri ->
            if (selectedUris.any { isVideoUri(it) }) {
                selectedUris = listOf(uri)
            } else if (selectedUris.size < 9) {
                selectedUris = selectedUris + uri
            } else {
                Toast.makeText(context, "Max 9 photos allowed", Toast.LENGTH_SHORT).show()
            }
        }
    }
    var showPostCameraChoice by remember { mutableStateOf(false) }
    val capturePostVideo = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { success ->
        if (success) postCameraUri?.let { uri ->
            selectedUris = listOf(uri)
            Toast.makeText(context, "Captured 1 video", Toast.LENGTH_SHORT).show()
        }
    }

    val takeFrontPicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) frontCameraUri?.let { uri ->
            frontOrigUri = uri
            frontError = null
            frontCutoutPath = null
            isProcessingFront = true
            scope.launch {
                try {
                    val cutout = processChallengeImage(context, uri)
                    frontCutoutPath = cutout.absolutePath
                    frontError = null
                } catch (e: Exception) {
                    frontError = e.message ?: "Could not detect garment"
                    frontCutoutPath = null
                } finally {
                    isProcessingFront = false
                }
            }
        }
    }

    val takeBackPicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) backCameraUri?.let { uri ->
            backOrigUri = uri
            backError = null
            backCutoutPath = null
            isProcessingBack = true
            scope.launch {
                try {
                    val cutout = processChallengeImage(context, uri)
                    backCutoutPath = cutout.absolutePath
                    backError = null
                } catch (e: Exception) {
                    backError = e.message ?: "Could not detect garment"
                    backCutoutPath = null
                } finally {
                    isProcessingBack = false
                }
            }
        }
    }

    val takeDesignPicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) postCameraUri?.let { uri ->
            if (designExtraUris.size >= 7) {
                Toast.makeText(context, "Max 7 extra photos allowed", Toast.LENGTH_SHORT).show()
            } else {
                designExtraUris = designExtraUris + uri
            }
        }
    }

    val designGalleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 7)
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val photos = uris.filter { !isVideoUri(it) }
        val room = 7 - designExtraUris.size
        if (room <= 0) {
            Toast.makeText(context, "Max 7 extra photos allowed", Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        designExtraUris = (designExtraUris + photos).take(7)
        if (photos.size > room) {
            Toast.makeText(context, "Max 7 extra photos allowed", Toast.LENGTH_SHORT).show()
        }
    }

    val requestCameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            when (pendingCameraSide) {
                "post" -> {
                    val uri = createCameraUri()
                    postCameraUri = uri
                    takePostPicture.launch(uri)
                }
                "front" -> {
                    val uri = createCameraUri()
                    frontCameraUri = uri
                    takeFrontPicture.launch(uri)
                }
                "back" -> {
                    val uri = createCameraUri()
                    backCameraUri = uri
                    takeBackPicture.launch(uri)
                }
                "design" -> {
                    val uri = createCameraUri()
                    postCameraUri = uri
                    takeDesignPicture.launch(uri)
                }
            }
        } else {
            Toast.makeText(context, "Camera permission denied", Toast.LENGTH_SHORT).show()
        }
        pendingCameraSide = null
    }

    fun launchCamera(side: String) {
        val perm = Manifest.permission.CAMERA
        val granted = ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            val uri = createCameraUri()
            when (side) {
                "post" -> { postCameraUri = uri; takePostPicture.launch(uri) }
                "front" -> { frontCameraUri = uri; takeFrontPicture.launch(uri) }
                "back" -> { backCameraUri = uri; takeBackPicture.launch(uri) }
                "design" -> { postCameraUri = uri; takeDesignPicture.launch(uri) }
            }
        } else {
            pendingCameraSide = side
            requestCameraPermission.launch(perm)
        }
    }

    val pickFrontLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            frontOrigUri = it
            frontError = null
            frontCutoutPath = null
            isProcessingFront = true
            scope.launch {
                try {
                    val cutout = processChallengeImage(context, it)
                    frontCutoutPath = cutout.absolutePath
                    frontError = null
                } catch (e: Exception) {
                    frontError = e.message ?: "Could not detect garment"
                    frontCutoutPath = null
                } finally {
                    isProcessingFront = false
                }
            }
        }
    }

    val pickBackLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            backOrigUri = it
            backError = null
            backCutoutPath = null
            isProcessingBack = true
            scope.launch {
                try {
                    val cutout = processChallengeImage(context, it)
                    backCutoutPath = cutout.absolutePath
                    backError = null
                } catch (e: Exception) {
                    backError = e.message ?: "Could not detect garment"
                    backCutoutPath = null
                } finally {
                    isProcessingBack = false
                }
            }
        }
    }

    if (showDesignPicker) {
        ContinueDesignsScreen(
            pickMode = true,
            onOpenDesign = { row -> onDesignPicked(row) },
            onPickDesign = { row -> onDesignPicked(row) },
            onBack = {
                showDesignPicker = false
                loadMyDesigns()
            }
        )
        return
    }

    Scaffold(
        containerColor = screenBackground,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Upload", fontWeight = FontWeight.ExtraBold, color = textColorForTheme(MidnightBlue), fontSize = 22.sp) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = screenBackground,
                    titleContentColor = textColorForTheme(MidnightBlue),
                    navigationIconContentColor = textColorForTheme(MidnightBlue)
                ),
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(painter = painterResource(id = R.drawable.arrowleft), contentDescription = "Back", tint = textColorForTheme(MidnightBlue))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(screenBackground)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(25.dp))
                    .background(tabBackground)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                UploadTabItem(
                    text = "Post",
                    isSelected = selectedTab == "Post",
                    onClick = { selectedTab = "Post" },
                    modifier = Modifier.weight(1f)
                )
                UploadTabItem(
                    text = "Challenge",
                    isSelected = selectedTab == "Challenge",
                    onClick = { selectedTab = "Challenge" },
                    modifier = Modifier.weight(1f)
                )
                UploadTabItem(
                    text = "Design",
                    isSelected = selectedTab == "Design",
                    onClick = { selectedTab = "Design" },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            if (selectedTab == "Post") {
                // POST MODE ------------------------------------------------
                Text("Create Post", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = textColorForTheme(MidnightBlue))
                Spacer(modifier = Modifier.height(4.dp))
                Text("Share your outfit with the community", fontSize = 13.sp, color = textColorForTheme(Color.Gray))
                Spacer(modifier = Modifier.height(16.dp))

                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { if (selectedTab == "Post" && !isUploading) expanded = !expanded }
                ) {
                    OutlinedTextField(
                        value = selectedCategory,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Category") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                        enabled = selectedTab == "Post" && !isUploading,
                        colors = textFieldColors,
                        shape = RoundedCornerShape(12.dp)
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        categoriesList.forEach { category ->
                            DropdownMenuItem(
                                text = { Text(category) },
                                onClick = {
                                    if (selectedTab == "Post") {
                                        selectedCategory = category
                                        expanded = false
                                    }
                                },
                                enabled = selectedTab == "Post"
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Bio / Description input - capped at 5 lines, fixed height, internal scroll
                OutlinedTextField(
                    value = captionInput,
                    onValueChange = { if (it.lines().size <= 5) captionInput = it },
                    label = { Text("Description / Bio") },
                    placeholder = { Text("pink color cartoon clothes") },
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    maxLines = 5,
                    enabled = !isUploading,
                    shape = RoundedCornerShape(12.dp),
                    colors = textFieldColors
                )
                Spacer(modifier = Modifier.height(16.dp))

                // Image/video picker box
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(pickerSurface)
                        .border(1.dp, pickerBorder, RoundedCornerShape(16.dp))
                        .padding(12.dp)
                ) {
                    if (selectedUris.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(painter = painterResource(id = R.drawable.addfromalbum), contentDescription = null, tint = MidnightBlue, modifier = Modifier.size(28.dp))
                                Spacer(modifier = Modifier.height(8.dp))
                                // Updated copy: require 1 video or up to 9 photos
                                Text(
                                    "Tap the buttons below to add photos/videos\n(1 video or up to 9 photos)",
                                    color = textColorForTheme(Color.Gray),
                                    fontSize = 13.sp,
                                    textAlign = TextAlign.Center,
                                    lineHeight = 18.sp
                                )
                            }
                        }
                    } else {
                        Column(modifier = Modifier.fillMaxSize()) {
                            val isVideoSelected = selectedUris.any { isVideoUri(it) }
                            Text(
                                if (isVideoSelected) "1 Video selected" else "${selectedUris.size}/9 Photos selected",
                                color = textColorForTheme(MidnightBlue),
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.sp
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(bottom = 44.dp)
                            ) {
                                items(selectedUris) { uri ->
                                    AsyncImage(
                                        model = ImageRequest.Builder(context)
                                            .data(uri)
                                            .crossfade(true)
                                            .build(),
                                        contentDescription = null,
                                        modifier = Modifier.size(84.dp).clip(RoundedCornerShape(10.dp)),
                                        contentScale = ContentScale.Crop
                                    )
                                }
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.align(Alignment.BottomEnd),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Cyan)
                                .clickable {
                                    galleryLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                                    )
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(painter = painterResource(id = R.drawable.addfromalbum), contentDescription = "Gallery", tint = MidnightBlue, modifier = Modifier.size(18.dp))
                        }
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Cyan)
                                .clickable { showPostCameraChoice = true },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(painter = painterResource(id = R.drawable.addfromcamera), contentDescription = "Camera", tint = MidnightBlue, modifier = Modifier.size(18.dp))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                val postReady = selectedUris.isNotEmpty()
                Button(
                    onClick = {
                        if (selectedUris.isEmpty()) {
                            Toast.makeText(context, "Please select at least one photo or video", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        isUploading = true
                        scope.launch {
                            try {
                                val titleText = if (captionInput.isNotBlank()) captionInput.take(20) else selectedCategory
                                val success = repository.createPost(
                                    context = context,
                                    imageUris = selectedUris,
                                    title = titleText,
                                    category = selectedCategory,
                                    bio = captionInput
                                )
                                if (success) {
                                    Toast.makeText(context, "Post created!", Toast.LENGTH_SHORT).show()
                                    onClose()
                                } else {
                                    Toast.makeText(context, "Failed to create post", Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: Exception) {
                                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                            } finally {
                                isUploading = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(25.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = MidnightBlue, disabledContainerColor = Color(0xFFE0E0E0), disabledContentColor = Color.Gray),
                    enabled = postReady && !isUploading
                ) {
                    if (isUploading) CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MidnightBlue, strokeWidth = 2.dp)
                    else Text("Post", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }

            } else if (selectedTab == "Challenge") {
                // CHALLENGE MODE ------------------------------------------------
                Text("Join Challenge", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = textColorForTheme(MidnightBlue))
                Spacer(modifier = Modifier.height(4.dp))
                Text("Upload front and back garment photos. Each will be processed to isolate the garment.", fontSize = 13.sp, color = textColorForTheme(Color.Gray))
                Spacer(modifier = Modifier.height(16.dp))

                ChallengePickerBox(
                    label = "FRONT",
                    origUri = frontOrigUri,
                    cutoutPath = frontCutoutPath,
                    isProcessing = isProcessingFront,
                    error = frontError,
                    onAlbum = { pickFrontLauncher.launch("image/*") },
                    onCamera = { launchCamera("front") }
                )
                if (!frontError.isNullOrBlank() && frontCutoutPath == null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(frontError!!, color = Color.Red, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(modifier = Modifier.height(12.dp))
                ChallengePickerBox(
                    label = "BACK",
                    origUri = backOrigUri,
                    cutoutPath = backCutoutPath,
                    isProcessing = isProcessingBack,
                    error = backError,
                    onAlbum = { pickBackLauncher.launch("image/*") },
                    onCamera = { launchCamera("back") }
                )
                if (!backError.isNullOrBlank() && backCutoutPath == null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(backError!!, color = Color.Red, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = challengeTitleInput,
                    onValueChange = { challengeTitleInput = it },
                    label = { Text("Challenge Title") },
                    placeholder = { Text("My upcycle look") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = textFieldColors
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = challengeCaptionInput,
                    onValueChange = { if (it.lines().size <= 4) challengeCaptionInput = it },
                    label = { Text("Description") },
                    placeholder = { Text("Tell us your story...") },
                    modifier = Modifier.fillMaxWidth().height(100.dp),
                    maxLines = 4,
                    shape = RoundedCornerShape(12.dp),
                    colors = textFieldColors
                )

                Spacer(modifier = Modifier.height(24.dp))

                val challengeReady = frontCutoutPath != null && backCutoutPath != null && challengeTitleInput.isNotBlank() && !isProcessingFront && !isProcessingBack

                Button(
                    onClick = {
                        if (frontCutoutPath == null || backCutoutPath == null) {
                            Toast.makeText(context, "Please upload and process both front and back images", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        if (challengeTitleInput.isBlank()) {
                            Toast.makeText(context, "Please enter title", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        isUploading = true
                        scope.launch {
                            try {
                                val uris = listOf(Uri.fromFile(File(frontCutoutPath!!)), Uri.fromFile(File(backCutoutPath!!)))
                                val success = repository.createPost(
                                    context = context,
                                    imageUris = uris,
                                    title = challengeTitleInput,
                                    category = "Challenge",
                                    bio = challengeCaptionInput,
                                    isChallenge = true
                                )
                                if (success) {
                                    Toast.makeText(context, "Challenge posted!", Toast.LENGTH_SHORT).show()
                                    onClose()
                                } else {
                                    Toast.makeText(context, "Failed to post challenge", Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: Exception) {
                                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                            } finally {
                                isUploading = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(25.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = MidnightBlue, disabledContainerColor = Color(0xFFE0E0E0), disabledContentColor = Color.Gray),
                    enabled = !isUploading && challengeReady
                ) {
                    if (isUploading) CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MidnightBlue, strokeWidth = 2.dp)
                    else Text("Post Challenge", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            } else {
                // DESIGN MODE ------------------------------------------------
                Text("Share Design", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = textColorForTheme(MidnightBlue))
                Spacer(modifier = Modifier.height(4.dp))
                Text("Pick a saved design. Its finished front and back become the main photos of your post.", fontSize = 13.sp, color = textColorForTheme(Color.Gray))
                Spacer(modifier = Modifier.height(16.dp))

                val hasDesigns = !designRows.isNullOrEmpty()
                Button(
                    onClick = { showDesignPicker = true },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(25.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Cyan,
                        contentColor = MidnightBlue,
                        disabledContainerColor = Color(0xFFE0E0E0),
                        disabledContentColor = Color.Gray
                    ),
                    enabled = hasDesigns
                ) {
                    Text("Choose from My Designs", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                if (!hasDesigns) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (designRows == null) "Loading your designs..." else "Please save a design first",
                        color = if (designRows == null) Color.Gray else Color.Red,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                val pickedDesign = selectedDesign
                if (pickedDesign != null) {
                    Spacer(modifier = Modifier.height(20.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        DesignSidePreview(label = "FRONT", file = designFrontFile, modifier = Modifier.weight(1f))
                        DesignSidePreview(label = "BACK", file = designBackFile, modifier = Modifier.weight(1f))
                    }
                    if (isRenderingDesign) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = MidnightBlue, strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Rendering your design...", fontSize = 12.sp, color = textColorForTheme(Color.Gray))
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Extra photos (optional) - ${designExtraUris.size}/7", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = textColorForTheme(MidnightBlue))
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(if (designExtraUris.isEmpty()) 180.dp else 200.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(pickerSurface)
                            .border(1.dp, pickerBorder, RoundedCornerShape(16.dp))
                            .padding(12.dp)
                    ) {
                        if (designExtraUris.isEmpty()) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("Add 0-7 more photos (9 total max)", color = textColorForTheme(Color.Gray), fontSize = 13.sp, textAlign = TextAlign.Center)
                            }
                        } else {
                            Column(modifier = Modifier.fillMaxSize()) {
                                Text("${designExtraUris.size}/7 extra", color = textColorForTheme(MidnightBlue), fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                Spacer(modifier = Modifier.height(6.dp))
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.padding(bottom = 44.dp)
                                ) {
                                    items(designExtraUris) { uri ->
                                        Box {
                                            AsyncImage(
                                                model = ImageRequest.Builder(context).data(uri).crossfade(true).build(),
                                                contentDescription = null,
                                                modifier = Modifier.size(84.dp).clip(RoundedCornerShape(10.dp)),
                                                contentScale = ContentScale.Crop
                                            )
                                            Box(
                                                modifier = Modifier
                                                    .align(Alignment.TopEnd)
                                                    .size(20.dp)
                                                    .clip(CircleShape)
                                                    .background(Color.Black.copy(alpha = 0.6f))
                                                    .clickable { designExtraUris = designExtraUris - uri },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text("x", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        Row(
                            modifier = Modifier.align(Alignment.BottomEnd),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Cyan)
                                    .clickable {
                                        designGalleryLauncher.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                        )
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(painter = painterResource(id = R.drawable.addfromalbum), contentDescription = "Gallery", tint = MidnightBlue, modifier = Modifier.size(18.dp))
                            }
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Cyan)
                                    .clickable { launchCamera("design") },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(painter = painterResource(id = R.drawable.addfromcamera), contentDescription = "Camera", tint = MidnightBlue, modifier = Modifier.size(18.dp))
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = designCaptionInput,
                        onValueChange = { if (it.lines().size <= 5) designCaptionInput = it },
                        label = { Text("Description") },
                        placeholder = { Text("Tell us about your design...") },
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                        maxLines = 5,
                        enabled = !isUploading,
                        shape = RoundedCornerShape(12.dp),
                        colors = textFieldColors
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    val designReady = designFrontFile != null && designBackFile != null &&
                            designCaptionInput.isNotBlank() && !isUploading && !isRenderingDesign

                    Button(
                        onClick = {
                            val row = selectedDesign ?: return@Button
                            val front = designFrontFile
                            val back = designBackFile
                            if (front == null || back == null) {
                                Toast.makeText(context, "Design is still rendering", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            if (designCaptionInput.isBlank()) {
                                Toast.makeText(context, "Please enter a description", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            isUploading = true
                            scope.launch {
                                try {
                                    val success = repository.createPost(
                                        context = context,
                                        imageUris = designExtraUris,
                                        mediaFiles = listOf(front, back),
                                        title = row.name.ifBlank { "Untitled design" },
                                        category = "Design",
                                        bio = designCaptionInput,
                                        postType = "Design",
                                        designId = row.id,
                                        challengePostId = row.state.challengePostId?.takeIf { it.isNotBlank() }
                                    )
                                    if (success) {
                                        Toast.makeText(context, "Design posted!", Toast.LENGTH_SHORT).show()
                                        onClose()
                                    } else {
                                        Toast.makeText(context, "Failed to post design", Toast.LENGTH_SHORT).show()
                                    }
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                                } finally {
                                    isUploading = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(25.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Cyan,
                            contentColor = MidnightBlue,
                            disabledContainerColor = Color(0xFFE0E0E0),
                            disabledContentColor = Color.Gray
                        ),
                        enabled = designReady
                    ) {
                        if (isUploading) CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MidnightBlue, strokeWidth = 2.dp)
                        else Text("Post Design", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    if (showPostCameraChoice) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { showPostCameraChoice = false }) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(20.dp)
            ) {
                Column {
                    Text("Capture", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = textColorForTheme(MidnightBlue))
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        androidx.compose.material3.Button(
                            onClick = {
                                showPostCameraChoice = false
                                val uri = createCameraUri()
                                postCameraUri = uri
                                takePostPicture.launch(uri)
                            },
                            modifier = Modifier.weight(1f),
                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = MidnightBlue)
                        ) { Text("Photo", fontWeight = FontWeight.Bold) }
                        androidx.compose.material3.Button(
                            onClick = {
                                showPostCameraChoice = false
                                val uri = createVideoUri()
                                postCameraUri = uri
                                capturePostVideo.launch(uri)
                            },
                            modifier = Modifier.weight(1f),
                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = MidnightBlue)
                        ) { Text("Video", fontWeight = FontWeight.Bold) }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    androidx.compose.material3.TextButton(onClick = { showPostCameraChoice = false }, modifier = Modifier.fillMaxWidth()) {
                        Text("Cancel", color = Color.Gray)
                    }
                }
            }
        }
    }
}

@Composable
private fun DesignSidePreview(
    label: String,
    file: File?,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(160.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.5.dp, MidnightBlue, RoundedCornerShape(16.dp))
    ) {
        if (file != null && file.exists()) {
            AsyncImage(
                model = file,
                contentDescription = label,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(label, color = textColorForTheme(Color.Gray), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(6.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                .padding(horizontal = 8.dp, vertical = 3.dp)
        ) {
            Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = textColorForTheme(MidnightBlue))
        }
    }
}

@Composable
private fun ChallengePickerBox(
    label: String,
    origUri: Uri?,
    cutoutPath: String?,
    isProcessing: Boolean,
    error: String?,
    onAlbum: () -> Unit,
    onCamera: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (cutoutPath != null) Cyan.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surface)
            .border(1.5.dp, if (cutoutPath != null) MidnightBlue else MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
            .clickable { if (!isProcessing) onAlbum() }
            .padding(12.dp)
    ) {
        Text(label, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = textColorForTheme(MidnightBlue), modifier = Modifier.align(Alignment.TopStart))
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when {
                isProcessing -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MidnightBlue, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Processing $label image...", fontSize = 12.sp, color = textColorForTheme(Color.Gray))
                    }
                }
                cutoutPath != null -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Original", fontSize = 10.sp, color = textColorForTheme(Color.Gray))
                            Spacer(modifier = Modifier.height(4.dp))
                            AsyncImage(model = origUri, contentDescription = null, modifier = Modifier.size(80.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFFEEEEEE)), contentScale = ContentScale.Crop)
                        }
                        Text(">", color = textColorForTheme(MidnightBlue), fontWeight = FontWeight.Bold)
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Cutout", fontSize = 10.sp, color = textColorForTheme(MidnightBlue), fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(4.dp))
                            AsyncImage(model = cutoutPath, contentDescription = null, modifier = Modifier.size(80.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surface).border(1.dp, MidnightBlue, RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
                        }
                    }
                }
                origUri != null && error != null -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(error, color = Color.Red, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text("Tap to retry", color = textColorForTheme(Color.Gray), fontSize = 11.sp)
                    }
                }
                else -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(painter = painterResource(id = R.drawable.addfromalbum), contentDescription = null, tint = textColorForTheme(Color(0xFF1B1B1B)), modifier = Modifier.size(26.dp))
                        Spacer(modifier = Modifier.height(6.dp))
                        Text("Select $label photo", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = textColorForTheme(Color(0xFF1B1B1B)))
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Garment will be isolated", fontSize = 11.sp, color = textColorForTheme(Color.Gray))
                    }
                }
            }
        }
        Row(
            modifier = Modifier.align(Alignment.BottomEnd),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Cyan).clickable { if (!isProcessing) onAlbum() },
                contentAlignment = Alignment.Center
            ) {
                Icon(painter = painterResource(id = R.drawable.addfromalbum), contentDescription = "Album", tint = MidnightBlue, modifier = Modifier.size(18.dp))
            }
            Box(
                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Cyan).clickable { if (!isProcessing) onCamera() },
                contentAlignment = Alignment.Center
            ) {
                Icon(painter = painterResource(id = R.drawable.addfromcamera), contentDescription = "Camera", tint = MidnightBlue, modifier = Modifier.size(18.dp))
            }
        }
    }
}

private suspend fun processChallengeImage(context: android.content.Context, uri: Uri): File = withContext(Dispatchers.IO) {
    val inference = ServiceLocator.inferencePipeline(context)
    val cacheDir = File(context.filesDir, "garment_assets").apply { mkdirs() }
    val imported = copyToCache(context, uri, cacheDir) ?: error("Could not import image")
    try {
        val out = File(cacheDir, "cutout2_${sha256(imported)}.png")
        if (!(out.exists() && out.length() > 0L)) {
            val result = inference.run(imported)
            try {
                savePng(result.cutout, out)
            } finally {
                runCatching { if (!result.cutout.isRecycled) result.cutout.recycle() }
            }
        }
        if (!out.exists() || out.length() == 0L) error("Failed to process image - no garment detected")
        out
    } finally {
        runCatching { imported.delete() }
    }
}

private fun copyToCache(context: android.content.Context, uri: Uri, cacheDir: File): File? {
    val target = File(cacheDir, "in_upload_${UUID.randomUUID()}.jpg")
    return try {
        context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
        if (target.length() > 0L) target else null
    } catch (_: Exception) { null }
}

private fun savePng(bitmap: Bitmap, file: File) {
    file.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
}

private fun sha256(file: File): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    file.inputStream().use { ins ->
        val buf = ByteArray(8192)
        while (true) {
            val n = ins.read(buf)
            if (n <= 0) break
            digest.update(buf, 0, n)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

@Composable
fun ActionIcon(
    modifier: Modifier = Modifier,
    @DrawableRes iconResId: Int,
    contentDescription: String,
    size: androidx.compose.ui.unit.Dp = 30.dp,
    tint: Color = Color.White,
    onClick: () -> Unit
) {
    androidx.compose.material3.IconButton(onClick = onClick, modifier = modifier) {
        androidx.compose.material3.Icon(
            painter = painterResource(id = iconResId),
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(size)
        )
    }
}