package com.example.myapplicationkoG.ui.editor

import android.graphics.drawable.ColorDrawable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.myapplicationkoG.AiRepository
import com.example.myapplicationkoG.ChatMessage
import com.example.myapplicationkoG.R
import com.example.myapplicationkoG.textColorForTheme
import com.example.myapplicationkoG.ui.theme.Cyan
import com.example.myapplicationkoG.ui.theme.MidnightBlue
import kotlinx.coroutines.launch

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AiChatDrawer(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    // "studio" = design coach (dye/patch KB + garment sidecar),
    // "home" = daily outfit advisor (no design uploads).
    mode: String = "studio",
    // Current design-space garment cutouts (studio mode only).
    designFrontPath: String? = null,
    designBackPath: String? = null
) {
    val isStudio = mode == "studio"
    var messages by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var inputText by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    // User-picked photos (data URIs, downscaled). Max 3 per message (token cost).
    var attachedImages by remember { mutableStateOf<List<String>>(emptyList()) }
    // One-shot: include front+back cutouts with the next send, then auto-off.
    // Studio only — Home has no design sidecar.
    var attachDesign by remember(designFrontPath, designBackPath) {
        mutableStateOf(isStudio && (designFrontPath != null || designBackPath != null))
    }
    val scope = rememberCoroutineScope()
    val repo = remember { AiRepository() }
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    val context = androidx.compose.ui.platform.LocalContext.current

    fun uriToDataUri(uri: android.net.Uri): String? {
        return try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return null
            if (bytes.size > 12 * 1024 * 1024) return null
            var bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: return null
            try {
                val longEdge = maxOf(bmp.width, bmp.height)
                // 1024px JPEG75 keeps vision tokens cheap (~200-400KB).
                if (longEdge > 1024) {
                    val scale = 1024f / longEdge
                    val scaled = android.graphics.Bitmap.createScaledBitmap(
                        bmp, (bmp.width * scale).toInt().coerceAtLeast(1),
                        (bmp.height * scale).toInt().coerceAtLeast(1), true
                    )
                    if (scaled !== bmp) bmp.recycle()
                    bmp = scaled
                }
                val out = java.io.ByteArrayOutputStream()
                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, out)
                "data:image/jpeg;base64," + android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
            } finally {
                runCatching { if (!bmp.isRecycled) bmp.recycle() }
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Design cutout file -> downscaled data URI (same budget as uploads). */
    fun fileToDataUri(path: String): String? {
        return try {
            val f = java.io.File(path)
            if (!f.exists() || f.length() == 0L || f.length() > 12 * 1024 * 1024) return null
            var bmp = android.graphics.BitmapFactory.decodeFile(path) ?: return null
            try {
                val longEdge = maxOf(bmp.width, bmp.height)
                if (longEdge > 1024) {
                    val scale = 1024f / longEdge
                    val scaled = android.graphics.Bitmap.createScaledBitmap(
                        bmp, (bmp.width * scale).toInt().coerceAtLeast(1),
                        (bmp.height * scale).toInt().coerceAtLeast(1), true
                    )
                    if (scaled !== bmp) bmp.recycle()
                    bmp = scaled
                }
                val out = java.io.ByteArrayOutputStream()
                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, out)
                "data:image/jpeg;base64," + android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
            } finally {
                runCatching { if (!bmp.isRecycled) bmp.recycle() }
            }
        } catch (_: Exception) {
            null
        }
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: android.net.Uri? ->
        if (uri != null && attachedImages.size < 3) {
            uriToDataUri(uri)?.let { attachedImages = attachedImages + it }
        }
    }
    fun hideKeyboard() {
        keyboardController?.hide()
        focusManager.clearFocus()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            runCatching {
                view.windowInsetsController?.hide(android.view.WindowInsets.Type.ime())
                view.clearFocus()
            }
        }
    }
    fun doSend() {
        val text = inputText.trim()
        // Design cutouts ride along one-shot when the chip is on.
        val designImages = if (attachDesign) {
            listOfNotNull(designFrontPath, designBackPath).mapNotNull { fileToDataUri(it) }
        } else emptyList()
        val images = (designImages + attachedImages).take(3)
        if ((text.isEmpty() && images.isEmpty()) || isLoading) return
        // NOTE: keyboard + focus intentionally left alone — user usually sends
        // several messages in a row; dismissing the keyboard each time is hostile.
        inputText = ""
        attachedImages = emptyList()
        attachDesign = false
        errorText = null
        messages = messages + ChatMessage("user", text, true, images)
        isLoading = true
        scope.launch {
            try {
                val history = messages.dropLast(1)
                val reply = repo.sendMessage(text, history, images, mode)
                messages = messages + ChatMessage("assistant", reply, false)
            } catch (e: Exception) {
                errorText = e.message ?: "Failed to get response"
            } finally {
                isLoading = false
            }
        }
    }

    fun askAboutDesign() {
        attachDesign = true
        inputText = "What do you think of my current design? What dye and patches would suit it?"
        doSend()
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }
    if (!visible) return
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        (LocalView.current.parent as? DialogWindowProvider)?.window?.let { w ->
            w.setDimAmount(0f)
            w.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
        }
        Box(modifier = modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f))
                    .clickable { onDismiss() }
            )

            // Drawer rides the keyboard: dialog window resizes for IME
            AnimatedVisibility(
                visible = true,
                modifier = Modifier.align(Alignment.CenterEnd),
                enter = slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(280)),
                exit = slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(280))
            ) {
                Column(
                    modifier = Modifier
                        // Leave breathing room above and below the drawer on tall phones.
                        .fillMaxHeight(0.88f)
                        // Use the available phone width instead of a fixed drawer width.
                        // The max keeps the drawer comfortable on tablets.
                        .fillMaxWidth(0.92f)
                        .widthIn(max = 420.dp)
                        // Keep the message input above Android navigation buttons and the keyboard.
                        .navigationBarsPadding()
                        .imePadding()
                        .clip(RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp))
                        .background(MaterialTheme.colorScheme.surface)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MidnightBlue)
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "Thread",
                                color = Color.White,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 18.sp
                            )
                            Text(
                                text = if (isStudio) "Your studio stylist" else "Your daily stylist",
                                color = Cyan,
                                fontSize = 11.sp
                            )
                        }
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.White.copy(alpha = 0.15f))
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.close),
                                contentDescription = "Close",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    if (messages.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(
                                    modifier = Modifier
                                        .size(64.dp)
                                        .clip(CircleShape)
                                        .background(Cyan),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        painter = painterResource(id = com.example.myapplicationkoG.R.drawable.ai),
                                        contentDescription = "AI",
                                        tint = MidnightBlue,
                                        modifier = Modifier.size(36.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "Hi! I'm Thread",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    color = textColorForTheme(MidnightBlue)
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = if (isStudio) "Ask me about colors, fabrics, or show me your design!"
                                    else "Ask me what to wear today!",
                                    fontSize = 13.sp,
                                    color = textColorForTheme(Color.Gray),
                                    lineHeight = 18.sp
                                )
                                if (isStudio && (designFrontPath != null || designBackPath != null)) {
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(20.dp))
                                            .background(Cyan)
                                            .clickable(enabled = !isLoading) { askAboutDesign() }
                                            .padding(horizontal = 16.dp, vertical = 10.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "Review my current design",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MidnightBlue
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(messages) { msg ->
                                ChatBubble(msg)
                            }
                            if (isLoading) {
                                item {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.Start
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp))
                                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                                .padding(horizontal = 14.dp, vertical = 10.dp)
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(14.dp),
                                                    color = MidnightBlue,
                                                    strokeWidth = 2.dp
                                                )
                                                Text("Thinking...", fontSize = 13.sp, color = textColorForTheme(Color.Gray))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (errorText != null) {
                        Text(
                            text = errorText!!,
                            color = Color(0xFFD32F2F),
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                        )
                    }

                    // Design-sidecar chip + picked-photo previews (above the input).
                    // Home mode has no sidecar: only user-picked photos show.
                    val hasDesign = isStudio && (designFrontPath != null || designBackPath != null)
                    if ((hasDesign && attachDesign) || attachedImages.isNotEmpty()) {
                        LazyRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (hasDesign && attachDesign) {
                                item(key = "design-chip") {
                                    Row(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(16.dp))
                                            .background(Cyan.copy(alpha = 0.25f))
                                            .clickable { attachDesign = false }
                                            .padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            text = "Front + back will be sent",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = textColorForTheme(MidnightBlue)
                                        )
                                        Icon(
                                            painter = painterResource(id = R.drawable.close),
                                            contentDescription = "Remove design photos",
                                            tint = textColorForTheme(Color.Gray),
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            }
                            itemsIndexed(attachedImages, key = { i, _ -> "pick-$i" }) { i, uri ->
                                Box(
                                    modifier = Modifier
                                        .size(52.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                ) {
                                    AsyncImage(
                                        model = uri,
                                        contentDescription = "Attached photo ${i + 1}",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                    )
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .size(20.dp)
                                            .clip(CircleShape)
                                            .background(Color.Black.copy(alpha = 0.6f))
                                            .clickable {
                                                attachedImages = attachedImages.filterIndexed { j, _ -> j != i }
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            painter = painterResource(id = R.drawable.close),
                                            contentDescription = "Remove photo",
                                            tint = Color.White,
                                            modifier = Modifier.size(12.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Photo picker (max 3 per message, token budget).
                        // Home users can still attach outfit photos; studio adds the sidecar.
                        val canSend = inputText.isNotBlank() || attachedImages.isNotEmpty() ||
                            (hasDesign && attachDesign)
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (attachedImages.size < 3 && !isLoading) MaterialTheme.colorScheme.surfaceVariant
                                    else Color(0xFFE0E0E0)
                                )
                                .clickable(enabled = attachedImages.size < 3 && !isLoading) {
                                    pickImage.launch("image/*")
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.addfromcamera),
                                contentDescription = "Attach photo",
                                tint = if (attachedImages.size < 3 && !isLoading) MidnightBlue else Color.Gray,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        OutlinedTextField(
                            value = inputText,
                            // 500-char cap: every char costs tokens AND the full history is
                            // resent every turn, so per-turn cost compounds. 500 chars ≈
                            // 125-250 tokens, enough for a fashion question, cheap per reply.
                            // Huge pastes can also exceed the model's context window and error out.
                            onValueChange = { if (it.length <= 500) inputText = it },
                            placeholder = { Text(if (isStudio) "Ask Thread about your design..." else "Ask Thread: what to wear today?", fontSize = 13.sp, color = textColorForTheme(Color.Gray)) },
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { doSend() }),
                            shape = RoundedCornerShape(24.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MidnightBlue,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                focusedTextColor = textColorForTheme(Color.Black),
                                unfocusedTextColor = textColorForTheme(Color.Black)
                            ),
                            maxLines = 3
                        )
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (canSend && !isLoading) MidnightBlue else Color(0xFFE0E0E0))
                                .clickable(enabled = canSend && !isLoading) {
                                    doSend()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.send),
                                contentDescription = "Send",
                                tint = if (canSend && !isLoading) Color.White else Color.Gray,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatBubble(msg: ChatMessage) {
    val isUser = msg.isUser
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier.widthIn(max = 240.dp),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
        ) {
            if (msg.images.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(bottom = 6.dp)
                ) {
                    msg.images.take(3).forEach { uri ->
                        AsyncImage(
                            model = uri,
                            contentDescription = "Sent photo",
                            modifier = Modifier
                                .size(72.dp)
                                .clip(RoundedCornerShape(10.dp)),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop
                        )
                    }
                }
            }
            if (msg.text.isNotBlank()) {
            Box(
                modifier = Modifier
                    .clip(
                        if (isUser) RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)
                        else RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)
                    )
                    .background(if (isUser) MidnightBlue else MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Text(
                    text = msg.text,
                    color = if (isUser) Color.White else textColorForTheme(Color(0xFF222222)),
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            }
            }
        }
    }
}