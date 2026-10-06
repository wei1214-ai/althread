package com.example.myapplicationkoG.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import androidx.compose.ui.graphics.toArgb
import com.example.myapplicationkoG.R
import com.example.myapplicationkoG.DesignRepository
import com.example.myapplicationkoG.SavedButton
import com.example.myapplicationkoG.SavedDye
import com.example.myapplicationkoG.domain.model.GarmentSideId
import com.example.myapplicationkoG.textColorForTheme
import com.example.myapplicationkoG.ui.garmentinput.GarmentInputViewModel
import com.example.myapplicationkoG.ui.theme.Cyan
import com.example.myapplicationkoG.ui.theme.MidnightBlue
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.luminance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.example.myapplicationkoG.textColorForTheme

data class DyeState(val color: Color, val strength: Float)
data class PlacedButton(val pos: Offset, val scale: Float, val color: Color, val style: Int = 0, val rotation: Float = 0f)
data class EditorSnapshot(
    val dye: Map<GarmentSideId, DyeState>,
    val buttons: Map<GarmentSideId, List<PlacedButton>>
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorPlaceHolder(
    viewModel: GarmentInputViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var currentSide by remember { mutableStateOf(GarmentSideId.FRONT) }
    val currentPath = when (currentSide) {
        GarmentSideId.FRONT -> state.frontCutoutPath
        GarmentSideId.BACK -> state.backCutoutPath
    }

    // Zoom state for garment
    var scale by remember { mutableFloatStateOf(1.4f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var saveDialogVisible by remember { mutableStateOf(false) }
    var saveName by remember { mutableStateOf("") }
    var isSaving by remember { mutableStateOf(false) }
    var showAiChat by remember { mutableStateOf(false) }
    var showVton by remember { mutableStateOf(false) }
    var vtonFile by remember { mutableStateOf<java.io.File?>(null) }
    var vtonPreparing by remember { mutableStateOf(false) }

    fun saveCurrent() {
        saveName = viewModel.openDesignName
        saveDialogVisible = true
    }

    // Tool + dye editing state - default no tool open, no dim
    var selectedTool by remember { mutableStateOf<Int?>(null) }
    var dyeColor by remember { mutableStateOf(Color(0xFF1A237E)) }
    var dyeStrength by remember { mutableFloatStateOf(0.55f) }
    var dyeMap by remember { mutableStateOf(mapOf<GarmentSideId, DyeState>()) }
    var dyedImage by remember { mutableStateOf<ImageBitmap?>(null) }
    // Patch buttons, per side
    var buttonMap by remember { mutableStateOf(mapOf<GarmentSideId, List<PlacedButton>>()) }
    var overlaySize by remember { mutableStateOf(IntSize.Zero) }
    var buttonImgs by remember { mutableStateOf(mapOf<Int, ImageBitmap>()) }
    var buttonContent by remember { mutableStateOf(mapOf<Int, Pair<Float, Float>>()) }
    // Undo/redo stacks, each entry is one committed step
    var undoStack by remember { mutableStateOf(listOf<EditorSnapshot>()) }
    var redoStack by remember { mutableStateOf(listOf<EditorSnapshot>()) }
    var prevTool by remember { mutableStateOf<Int?>(null) }
    val dyeColors = listOf(
        Color(0xFF1A237E),
        Color(0xFF000000),
        Color(0xFFB71C1C),
        Color(0xFF00695C),
        Color(0xFF4A148C),
        Color(0xFFE65100),
        Color(0xFFEC407A),
        Color(0xFF33691E)
    )

    var dyeActive by remember { mutableStateOf(false) }
    var buttonScale by remember { mutableFloatStateOf(1f) }
    var buttonStyle by remember { mutableIntStateOf(0) }
    var buttonRotation by remember { mutableFloatStateOf(0f) }
    var selectedButtonIdx by remember { mutableIntStateOf(-1) }
    var sizeDragStart by remember { mutableStateOf<Float?>(null) }
    var rotDragStart by remember { mutableStateOf<Float?>(null) }

    LaunchedEffect(currentSide) {
        selectedButtonIdx = -1
        sizeDragStart = null
        rotDragStart = null
    }

    // Load saved design if opened from Continue screen
    LaunchedEffect(Unit) {
        DesignSession.consume()?.let { loaded ->
            dyeMap = loaded.dye
            buttonMap = loaded.buttons
            loaded.dye[GarmentSideId.FRONT]?.let { dyeActive = true; dyeColor = it.color; dyeStrength = it.strength }
        }
    }

    fun currentSnapshot() = EditorSnapshot(dyeMap, buttonMap)
    fun applySnapshot(s: EditorSnapshot) {
        dyeMap = s.dye
        buttonMap = s.buttons
        if (selectedTool == 0) {
            val d = s.dye[currentSide]
            dyeActive = d != null
            d?.let { dyeColor = it.color; dyeStrength = it.strength }
        }
    }
    fun pushHistory() {
        undoStack = undoStack + currentSnapshot()
        redoStack = emptyList()
    }
    fun undo() {
        if (undoStack.isEmpty()) return
        redoStack = redoStack + currentSnapshot()
        val prev = undoStack.last()
        undoStack = undoStack.dropLast(1)
        applySnapshot(prev)
    }
    fun redo() {
        if (redoStack.isEmpty()) return
        undoStack = undoStack + currentSnapshot()
        val next = redoStack.last()
        redoStack = redoStack.dropLast(1)
        applySnapshot(next)
    }
    fun updateSelectedButton(transform: (PlacedButton) -> PlacedButton) {
        val list = buttonMap[currentSide].orEmpty()
        if (selectedButtonIdx in list.indices) {
            pushHistory()
            buttonMap = buttonMap + (currentSide to list.toMutableList().also { it[selectedButtonIdx] = transform(it[selectedButtonIdx]) })
        }
    }
    fun deleteSelectedButton() {
        val list = buttonMap[currentSide].orEmpty()
        if (selectedButtonIdx in list.indices) {
            pushHistory()
            buttonMap = buttonMap + (currentSide to list.toMutableList().also { it.removeAt(selectedButtonIdx) })
            selectedButtonIdx = -1
            sizeDragStart = null
        }
    }
    fun selectStyle(s: Int) {
        buttonStyle = s
        buttonScale = buttonScale.coerceIn(minScaleFor(s), maxScaleFor(s))
        updateSelectedButton { it.copy(style = s) }
    }

    suspend fun buildCompositeGarment(): java.io.File? = withContext(Dispatchers.Default) {
        val path = state.frontCutoutPath ?: return@withContext null
        val dye = dyeMap[GarmentSideId.FRONT]
        var bmp = runCatching {
            if (dye != null) makeDyedBitmap(path, dye.color, dye.strength)
            else android.graphics.BitmapFactory.decodeFile(path)
        }.getOrNull() ?: return@withContext null
        bmp = bmp.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
        val list = buttonMap[GarmentSideId.FRONT].orEmpty()
        if (list.isNotEmpty()) {
            val canvas = android.graphics.Canvas(bmp)
            val paint = android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG
            )
            val base = 0.05f * minOf(bmp.width, bmp.height)
            list.forEach { btn ->
                val tex = (buttonImgs[btn.style] ?: buttonImgs[0])?.asAndroidBitmap() ?: return@forEach
                val (cw, ch) = buttonContent[btn.style] ?: buttonContent[0]
                ?: (tex.width.toFloat() to tex.height.toFloat())
                val target = (base * btn.scale).coerceIn(
                    base * minScaleFor(btn.style), base * maxScaleFor(btn.style)
                )
                val s = target / maxOf(cw.coerceAtLeast(1f), ch.coerceAtLeast(1f))
                val m = android.graphics.Matrix()
                m.postTranslate(-tex.width / 2f, -tex.height / 2f)
                m.postScale(s, s)
                m.postRotate(btn.rotation)
                m.postTranslate(btn.pos.x * bmp.width, btn.pos.y * bmp.height)
                canvas.drawBitmap(tex, m, paint)
            }
        }
        val out = java.io.File(context.cacheDir, "vton_garment_${System.currentTimeMillis()}.png")
        out.outputStream().use { o -> bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, o) }
        runCatching { if (!bmp.isRecycled) bmp.recycle() }
        out
    }
    fun clearDesign() {
        pushHistory()
        dyeMap = emptyMap()
        buttonMap = emptyMap()
        dyedImage = null
        dyeActive = false
        selectedTool = null
        selectedButtonIdx = -1
        sizeDragStart = null
        rotDragStart = null
        ChallengeSession.title = null
        ChallengeSession.description = null
        Toast.makeText(context, "Design cleared", Toast.LENGTH_SHORT).show()
    }

    // Leaving dye mode commits one step; entering restores committed values
    LaunchedEffect(selectedTool) {
        if (prevTool == 0 && selectedTool != 0) {
            val live = if (dyeActive) DyeState(dyeColor, dyeStrength) else null
            if (live != dyeMap[currentSide]) {
                pushHistory()
                dyeMap = if (live != null) dyeMap + (currentSide to live) else dyeMap - currentSide
            }
        }
        if (selectedTool == 0 && prevTool != 0) {
            val d = dyeMap[currentSide]
            dyeActive = d != null
            d?.let { dyeColor = it.color; dyeStrength = it.strength }
        }
        prevTool = selectedTool
    }

    // Dye preview while editing, committed dye otherwise
    val activeDye: DyeState? = if (selectedTool == 0) {
        if (dyeActive) DyeState(dyeColor, dyeStrength) else null
    } else dyeMap[currentSide]
    LaunchedEffect(currentPath, activeDye) {
        val path = currentPath
        val dye = activeDye
        if (path == null || dye == null) {
            dyedImage = null
            return@LaunchedEffect
        }
        val result = withContext(Dispatchers.Default) {
            runCatching { makeDyedBitmap(path, dye.color, dye.strength) }.getOrNull()
        }
        dyedImage = result?.asImageBitmap()
    }

    // Textures: buttons 0-2 (round, circular mask), patches 3-6 (keep shape, gentle key)
    LaunchedEffect(Unit) {
        val (imgs, boxes) = withContext(Dispatchers.Default) {
            val mImg = mutableMapOf<Int, ImageBitmap>()
            val mBox = mutableMapOf<Int, Pair<Float, Float>>()
            listOf(
                Triple(0, R.drawable.patchbutton1, 0),
                Triple(1, R.drawable.patchbutton2, 0),
                Triple(2, R.drawable.patchbutton3, 1),
                Triple(3, R.drawable.patch1, 2),
                Triple(4, R.drawable.patch2, 2),
                Triple(5, R.drawable.patch3, 2),
                Triple(6, R.drawable.patch4, 2)
            ).forEach { (style, res, mode) ->
                runCatching {
                    BitmapFactory.decodeResource(context.resources, res)?.let {
                        val keyed = when (mode) {
                            0 -> keyOutBlack(it)
                            1 -> it
                            else -> keyOutBlack(it, 10f, 50f)
                        }
                        val final = if (style < 3) applyCircularMask(keyed) else keyed
                        mBox[style] = contentBounds(final)
                        final.asImageBitmap()
                    }
                }.getOrNull()?.let { mImg[style] = it }
            }
            mImg to mBox
        }
        buttonImgs = imgs
        buttonContent = boxes
    }
    val curButtons = buttonMap[currentSide].orEmpty()

    val isDarkDesign = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Scaffold(
        containerColor = if (isDarkDesign) Color.Black else Color.White,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(if (isDarkDesign) Color.Black else Color.White)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = onBack) {
                    Icon(painter = painterResource(id = R.drawable.arrowleft), contentDescription = "Back", tint = if (isDarkDesign) Color.White else MidnightBlue, modifier = Modifier.size(24.dp))
                }
                Text("Design Space", fontWeight = FontWeight.ExtraBold, color = if (isDarkDesign) Color.White else MidnightBlue, fontSize = 24.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                Spacer(modifier = Modifier.size(48.dp))
            }
            Spacer(modifier = Modifier.height(8.dp))
            var showBriefDialog by remember { mutableStateOf(false) }
            val challengeBrief = remember { ChallengeSession.peekPair() }
            val challengeTitle = challengeBrief.first
            val challengeDesc = challengeBrief.second
            if (!challengeTitle.isNullOrBlank() || !challengeDesc.isNullOrBlank()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (isDarkDesign) Cyan.copy(alpha = 0.35f) else Cyan.copy(alpha = 0.18f))
                        .border(1.5.dp, Cyan, RoundedCornerShape(16.dp))
                        .clickable { showBriefDialog = true }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Challenge Brief",
                        color = if (isDarkDesign) Color.White else MidnightBlue,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 17.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(Cyan)
                            .padding(horizontal = 14.dp, vertical = 7.dp)
                    ) {
                        Text("View", color = MidnightBlue, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                if (showBriefDialog) {
                    AlertDialog(
                        onDismissRequest = { showBriefDialog = false },
                        containerColor = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(20.dp),
                        title = {
                            Text(
                                "Challenge Brief",
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 18.sp,
                                color = textColorForTheme(Color.Black)
                            )
                        },
                        text = {
                            Column {
                                if (!challengeTitle.isNullOrBlank()) {
                                    Text(challengeTitle, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = textColorForTheme(Color.Black))
                                }
                                if (!challengeDesc.isNullOrBlank()) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(challengeDesc, fontSize = 14.sp, lineHeight = 20.sp, color = textColorForTheme(Color.Gray))
                                }
                                if (challengeTitle.isNullOrBlank() && challengeDesc.isNullOrBlank()) {
                                    Text("No details", fontSize = 13.sp, color = textColorForTheme(Color.Gray))
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showBriefDialog = false }) {
                                Text("Close", color = Cyan, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
                            }
                        }
                    )
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Cyan)
                                    .clickable { undo() },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.undo),
                                    contentDescription = "Undo",
                                    tint = MidnightBlue,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Cyan)
                                    .clickable { redo() },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.redo),
                                    contentDescription = "Redo",
                                    tint = MidnightBlue,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Cyan)
                                    .clickable { clearDesign() },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.removedesign),
                                    contentDescription = "Remove design",
                                    tint = MidnightBlue,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Cyan)
                                    .clickable { showAiChat = true },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ai),
                                    contentDescription = "AI",
                                    tint = MidnightBlue,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Cyan)
                                    .clickable { saveCurrent() },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.save),
                                    contentDescription = "Save",
                                    tint = MidnightBlue,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Cyan)
                                    .clickable(enabled = !vtonPreparing) {
                                        if (state.frontCutoutPath == null) {
                                            Toast.makeText(context, "No garment to try on", Toast.LENGTH_SHORT).show()
                                            return@clickable
                                        }
                                        vtonPreparing = true
                                        scope.launch {
                                            vtonFile = runCatching { buildCompositeGarment() }.getOrNull()
                                            vtonPreparing = false
                                            if (vtonFile != null) showVton = true
                                            else Toast.makeText(context, "Failed to prepare garment", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                if (vtonPreparing) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), color = MidnightBlue, strokeWidth = 2.dp)
                                } else {
                                    Icon(
                                        painter = painterResource(id = R.drawable.wardrobebutton),
                                        contentDescription = "Try On",
                                        tint = MidnightBlue,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.Center),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(Cyan)
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(currentSide.name, color = MidnightBlue, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (isDarkDesign) Color(0xFF121212) else Color.White)
                            .border(1.dp, if (isDarkDesign) Color(0xFF333333) else Color(0xFFE0E0E0), RoundedCornerShape(20.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (currentPath != null) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer(
                                        scaleX = scale,
                                        scaleY = scale,
                                        translationX = offset.x,
                                        translationY = offset.y
                                    )
                            ) {
                                if (dyedImage != null) {
                                    Image(
                                        bitmap = dyedImage!!,
                                        contentDescription = currentSide.name,
                                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)).padding(16.dp),
                                        contentScale = ContentScale.Fit,
                                        alignment = Alignment.Center
                                    )
                                } else {
                                    AsyncImage(
                                        model = currentPath,
                                        contentDescription = currentSide.name,
                                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)).padding(16.dp),
                                        contentScale = ContentScale.Fit,
                                        alignment = Alignment.Center
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .onSizeChanged { overlaySize = it }
                                ) {
                                    val bwPx = overlaySize.width.toFloat().coerceAtLeast(1f)
                                    val bhPx = overlaySize.height.toFloat().coerceAtLeast(1f)
                                    fun toGarmentPixel(fx: Float, fy: Float): Boolean {
                                        val path = currentPath ?: return false
                                        val bmp = hitBitmap(path) ?: return false
                                        val ox = fx * bwPx
                                        val oy = fy * bhPx
                                        val cx = bwPx / 2f
                                        val cy = bhPx / 2f
                                        val lx = (ox - cx - offset.x) / scale + cx
                                        val ly = (oy - cy - offset.y) / scale + cy
                                        val s = minOf(bwPx / bmp.width, bhPx / bmp.height)
                                        val dw = bmp.width * s
                                        val dh = bmp.height * s
                                        val left = (bwPx - dw) / 2f
                                        val top = (bhPx - dh) / 2f
                                        val bx = ((lx - left) / dw * bmp.width).toInt()
                                        val by = ((ly - top) / dh * bmp.height).toInt()
                                        if (bx !in 0 until bmp.width || by !in 0 until bmp.height) return false
                                        val p = bmp.getPixel(bx, by)
                                        val r = (p shr 16) and 0xFF
                                        val g = (p shr 8) and 0xFF
                                        val b = p and 0xFF
                                        return r < 250 || g < 250 || b < 250
                                    }
                                    fun toGarmentFraction(fx: Float, fy: Float): Pair<Float, Float>? {
                                        val path = currentPath ?: return null
                                        val bmp = hitBitmap(path) ?: return null
                                        val ox = fx * bwPx
                                        val oy = fy * bhPx
                                        val cx = bwPx / 2f
                                        val cy = bhPx / 2f
                                        val lx = (ox - cx - offset.x) / scale + cx
                                        val ly = (oy - cy - offset.y) / scale + cy
                                        val s = minOf(bwPx / bmp.width, bhPx / bmp.height)
                                        val dw = bmp.width * s
                                        val dh = bmp.height * s
                                        val left = (bwPx - dw) / 2f
                                        val top = (bhPx - dh) / 2f
                                        val gx = (lx - left) / dw
                                        val gy = (ly - top) / dh
                                        if (gx !in 0f..1f || gy !in 0f..1f) return null
                                        return gx to gy
                                    }
                                    fun garmentToOverlay(gx: Float, gy: Float): Offset {
                                        val bmp = currentPath?.let { hitBitmap(it) }
                                        val cx = bwPx / 2f
                                        val cy = bhPx / 2f
                                        val lx: Float
                                        val ly: Float
                                        if (bmp != null) {
                                            val s = minOf(bwPx / bmp.width, bhPx / bmp.height)
                                            val dw = bmp.width * s
                                            val dh = bmp.height * s
                                            val left = (bwPx - dw) / 2f
                                            val top = (bhPx - dh) / 2f
                                            lx = left + gx * dw
                                            ly = top + gy * dh
                                        } else {
                                            lx = gx * bwPx
                                            ly = gy * bhPx
                                        }
                                        return Offset(
                                            cx + (lx - cx) * scale + offset.x,
                                            cy + (ly - cy) * scale + offset.y
                                        )
                                    }
                                    Canvas(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .then(
                                                if (selectedTool == 1) Modifier.pointerInput(selectedTool, currentPath, overlaySize, scale, offset, buttonMap) {
                                                    detectTapGestures(
                                                        onTap = { pos ->
                                                            val fx = (pos.x / bwPx).coerceIn(0f, 1f)
                                                            val fy = (pos.y / bhPx).coerceIn(0f, 1f)
                                                            // 1) tap on an existing button selects it (inner disc only)
                                                            val hitIdx = curButtons.indexOfFirst { btn ->
                                                                if ((buttonImgs[btn.style] ?: buttonImgs[0]) == null) return@indexOfFirst false
                                                                val bBase = 0.05f * minOf(bwPx, bhPx) * scale
                                                                val bTarget = (bBase * btn.scale).coerceIn(bBase * minScaleFor(btn.style), bBase * maxScaleFor(btn.style))
                                                                val c = garmentToOverlay(btn.pos.x, btn.pos.y)
                                                                val dx = pos.x - c.x
                                                                val dy = pos.y - c.y
                                                                kotlin.math.sqrt(dx * dx + dy * dy) <= bTarget * 0.32f
                                                            }
                                                            if (hitIdx >= 0) {
                                                                if (hitIdx == selectedButtonIdx) {
                                                                    selectedButtonIdx = -1
                                                                    sizeDragStart = null
                                                                    return@detectTapGestures
                                                                }
                                                                val btn = curButtons[hitIdx]
                                                                selectedButtonIdx = hitIdx
                                                                buttonScale = btn.scale
                                                                buttonStyle = btn.style
                                                                buttonRotation = btn.rotation
                                                                sizeDragStart = null
                                                                rotDragStart = null
                                                                return@detectTapGestures
                                                            }
                                                            // 2) otherwise place a new one (garment only, no white space)
                                                            if (!toGarmentPixel(fx, fy)) {
                                                                Toast.makeText(context, "Please place buttons on the garment", Toast.LENGTH_SHORT).show()
                                                                return@detectTapGestures
                                                            }
                                                            val g = toGarmentFraction(fx, fy) ?: run {
                                                                Toast.makeText(context, "Please place buttons on the garment", Toast.LENGTH_SHORT).show()
                                                                return@detectTapGestures
                                                            }
                                                            pushHistory()
                                                            buttonMap = buttonMap + (currentSide to (buttonMap[currentSide].orEmpty() + PlacedButton(Offset(g.first, g.second), buttonScale, Color.White, buttonStyle, buttonRotation)))
                                                            selectedButtonIdx = buttonMap[currentSide].orEmpty().size - 1
                                                            sizeDragStart = null
                                                            rotDragStart = null
                                                        }
                                                    )
                                                } else Modifier
                                            )
                                    ) {
                                        val imgs = buttonImgs
                                        if (imgs.isNotEmpty()) {
                                            val base = 0.05f * minOf(size.width, size.height) * scale
                                            curButtons.forEachIndexed { i, btn ->
                                                val b = imgs[btn.style] ?: imgs[0] ?: return@forEachIndexed
                                                val (cw, ch) = buttonContent[btn.style] ?: buttonContent[0] ?: (b.width.toFloat() to b.height.toFloat())
                                                // Size bar range is the clamp: 0.5x-2.0x of base
                                                val (wdt, h) = buttonDstSize(b.width, b.height, cw, ch, base, btn.scale, minScaleFor(btn.style), maxScaleFor(btn.style))
                                                val oc = garmentToOverlay(btn.pos.x, btn.pos.y)
                                                val cx = oc.x
                                                val cy = oc.y
                                                rotate(btn.rotation, Offset(cx, cy)) {
                                                    drawImage(
                                                        b,
                                                        dstOffset = IntOffset((cx - wdt / 2).toInt(), (cy - h / 2).toInt()),
                                                        dstSize = IntSize(wdt.toInt(), h.toInt()),
                                                        colorFilter = null
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            Text("No image", color = Color(0xFF999999))
                        }
                    }



                }

                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    if (currentSide == GarmentSideId.FRONT && state.backCutoutPath != null) {
                        Box(
                            modifier = Modifier
                                .size(width = 220.dp, height = 44.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(Cyan)
                                .clickable {
                                    scale = 1.4f; offset = Offset.Zero
                                    currentSide = GarmentSideId.BACK
                                    dyeMap[GarmentSideId.BACK]?.let { dyeColor = it.color; dyeStrength = it.strength }
                                }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Box(
                                    modifier = Modifier.size(32.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.arrowleft),
                                        contentDescription = "Go to back",
                                        tint = MidnightBlue,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                Text(
                                    text = "View Back",
                                    color = MidnightBlue,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    modifier = Modifier.weight(1f),
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.size(32.dp))
                            }
                        }
                    }
                    if (currentSide == GarmentSideId.BACK && state.frontCutoutPath != null) {
                        Box(
                            modifier = Modifier
                                .size(width = 220.dp, height = 44.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(Cyan)
                                .clickable {
                                    scale = 1.4f; offset = Offset.Zero
                                    currentSide = GarmentSideId.FRONT
                                    dyeMap[GarmentSideId.FRONT]?.let { dyeColor = it.color; dyeStrength = it.strength }
                                }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Spacer(modifier = Modifier.size(32.dp))
                                Text(
                                    text = "View Front",
                                    color = MidnightBlue,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    modifier = Modifier.weight(1f),
                                    textAlign = TextAlign.Center
                                )
                                Box(
                                    modifier = Modifier.size(32.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.arrowright),
                                        contentDescription = "Go to front",
                                        tint = MidnightBlue,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }



                Spacer(modifier = Modifier.height(8.dp))
                if (selectedTool == 0) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (isDarkDesign) Color(0xFF121212) else Color.White)
                            .border(1.dp, if (isDarkDesign) Color(0xFF333333) else Color(0xFFE0E0E0), RoundedCornerShape(14.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text("Dye whole garment", color = if (isDarkDesign) Color.White else MidnightBlue, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            dyeColors.forEach { c ->
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(c)
                                        .border(
                                            2.dp,
                                            if (dyeActive && dyeColor == c) MidnightBlue else Color.Transparent,
                                            CircleShape
                                        )
                                        .clickable {
                                            if (dyeActive && dyeColor == c) {
                                                dyeActive = false
                                            } else {
                                                dyeColor = c
                                                dyeActive = true
                                            }
                                        }
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text("Strength", color = Color.Gray, fontSize = 11.sp)
                        Slider(
                            value = dyeStrength,
                            onValueChange = { dyeStrength = it },
                            valueRange = 0.2f..0.85f,
                            modifier = Modifier.height(24.dp)
                        )
                    }
                }
                if (selectedTool == 1) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (isDarkDesign) Color(0xFF121212) else Color.White)
                            .border(1.dp, if (isDarkDesign) Color(0xFF333333) else Color(0xFFE0E0E0), RoundedCornerShape(14.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text("Patch", color = if (isDarkDesign) Color.White else MidnightBlue, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            LazyRow(
                                modifier = Modifier.weight(1f).padding(end = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                listOf(0 to "Round", 1 to "Square", 2 to "Black", 3 to "Ghost", 4 to "Bear", 5 to "Skate", 6 to "Smile").forEach { (style, name) ->
                                    item(key = style) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Box(
                                                modifier = Modifier
                                                    .size(44.dp)
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .background(Color(0xFFF0F0F0))
                                                    .border(
                                                        2.dp,
                                                        if (buttonStyle == style) MidnightBlue else Color.Transparent,
                                                        RoundedCornerShape(12.dp)
                                                    )
                                                    .clickable { selectStyle(style) }
                                                    .padding(6.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                val bmp = buttonImgs[style]
                                                if (bmp != null) {
                                                    Image(
                                                        bitmap = bmp,
                                                        contentDescription = name,
                                                        modifier = Modifier.fillMaxSize()
                                                    )
                                                }
                                            }
                                            Text(
                                                text = name,
                                                color = if (buttonStyle == style) MidnightBlue else Color.Gray,
                                                fontSize = 10.sp
                                            )
                                        }
                                    }
                                }
                            }
                            val hasSelection = selectedButtonIdx in buttonMap[currentSide].orEmpty().indices
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(if (hasSelection) Cyan else Color(0xFFE8E8E8))
                                        .clickable(enabled = hasSelection) { deleteSelectedButton() },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.deletedesign),
                                        contentDescription = "Remove",
                                        tint = if (hasSelection) MidnightBlue else Color.Gray,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                Text(
                                    text = "Remove",
                                    color = if (hasSelection) MidnightBlue else Color.Gray,
                                    fontSize = 10.sp
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        val scaleMax = maxScaleFor(buttonStyle)
                        val scaleMin = minScaleFor(buttonStyle)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Size", color = Color.Gray, fontSize = 11.sp)
                                Slider(
                                    value = buttonScale.coerceIn(scaleMin, scaleMax),
                                    onValueChange = {
                                        buttonScale = it.coerceIn(scaleMin, scaleMax)
                                        val list = buttonMap[currentSide].orEmpty()
                                        if (selectedButtonIdx in list.indices) {
                                            if (sizeDragStart == null) {
                                                sizeDragStart = list[selectedButtonIdx].scale
                                                pushHistory()
                                            }
                                            buttonMap = buttonMap + (currentSide to list.toMutableList().also { m -> m[selectedButtonIdx] = m[selectedButtonIdx].copy(scale = buttonScale) })
                                        }
                                    },
                                    onValueChangeFinished = { sizeDragStart = null },
                                    valueRange = scaleMin..scaleMax,
                                    modifier = Modifier.height(24.dp)
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Rotate", color = Color.Gray, fontSize = 11.sp)
                                Slider(
                                    value = buttonRotation,
                                    onValueChange = {
                                        buttonRotation = it.coerceIn(-180f, 180f)
                                        val list = buttonMap[currentSide].orEmpty()
                                        if (selectedButtonIdx in list.indices) {
                                            if (rotDragStart == null) {
                                                rotDragStart = list[selectedButtonIdx].rotation
                                                pushHistory()
                                            }
                                            buttonMap = buttonMap + (currentSide to list.toMutableList().also { m -> m[selectedButtonIdx] = m[selectedButtonIdx].copy(rotation = buttonRotation) })
                                        }
                                    },
                                    onValueChangeFinished = { rotDragStart = null },
                                    valueRange = -180f..180f,
                                    modifier = Modifier.height(24.dp)
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(if (isDarkDesign) Color(0xFF121212) else Color.White)
                        .border(1.dp, if (isDarkDesign) Color(0xFF333333) else Color(0xFFE0E0E0), RoundedCornerShape(24.dp))
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val toolIcons = listOf(R.drawable.dye, R.drawable.patch)
                        val toolNames = listOf("Dye", "Patch")
                        toolIcons.forEachIndexed { idx, iconRes ->
                            val isSelected = selectedTool == idx
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(56.dp)
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(if (isSelected) Cyan else Color(0xFFF0F0F0))
                                        .clickable {
                                            selectedTool = if (isSelected) null else idx
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        painter = painterResource(id = iconRes),
                                        contentDescription = toolNames[idx],
                                        tint = if (isSelected) MidnightBlue else Color.Gray,
                                        modifier = Modifier.size(26.dp)
                                    )
                                }
                                Text(
                                    text = toolNames[idx],
                                    color = if (isSelected) MidnightBlue else Color.Gray,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (saveDialogVisible) {
        AlertDialog(
            onDismissRequest = { if (!isSaving) saveDialogVisible = false },
            title = { Text("Save Design", color = textColorForTheme(MidnightBlue), fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("Name your design", color =textColorForTheme(Color.Gray), fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = saveName,
                        onValueChange = { saveName = it },
                        singleLine = true,
                        placeholder = { Text("My design", color = textColorForTheme(Color.Gray)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        isSaving = true
                        scope.launch {
                            try {
                                val repo = DesignRepository()
                                val frontPath = state.frontCutoutPath
                                val backPath = state.backCutoutPath
                                if (frontPath == null || backPath == null) {
                                    Toast.makeText(context, "No garment image to save", Toast.LENGTH_SHORT).show()
                                    isSaving = false
                                    saveDialogVisible = false
                                    return@launch
                                }
                                val frontFile = File(frontPath)
                                val backFile = File(backPath)
                                val dyeSaved = dyeMap.mapValues { SavedDye(it.value.color.toArgb(), it.value.strength) }
                                val btnSaved = buttonMap.mapValues { entry -> entry.value.map { SavedButton(it.pos.x, it.pos.y, it.scale, it.color.toArgb(), it.style, it.rotation) } }
                                val existingId = viewModel.openDesignId
                                if (existingId != null) {
                                    val updated = repo.updateDesign(
                                        rowId = existingId,
                                        name = saveName.ifBlank { viewModel.openDesignName },
                                        frontFile = frontFile,
                                        backFile = backFile,
                                        dye = dyeSaved,
                                        buttons = btnSaved,
                                        challengePostId = com.example.myapplicationkoG.ui.editor.ChallengeSession.postId
                                            ?.takeIf { it.isNotBlank() }
                                            ?: viewModel.openDesignChallengePostId
                                    )
                                    viewModel.openDesignName = updated.name
                                    Toast.makeText(context, "Design updated!", Toast.LENGTH_SHORT).show()
                                } else {
                                    val created = repo.saveDesign(
                                        name = saveName.ifBlank { "Untitled design" },
                                        frontFile = frontFile,
                                        backFile = backFile,
                                        dye = dyeSaved,
                                        buttons = btnSaved
                                    )
                                    viewModel.openDesignId = created.id
                                    viewModel.openDesignName = created.name
                                    Toast.makeText(context, "Design saved!", Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: Exception) {
                                Toast.makeText(context, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
                            } finally {
                                isSaving = false
                                saveDialogVisible = false
                            }
                        }
                    },
                    enabled = !isSaving
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = MidnightBlue, strokeWidth = 2.dp)
                    } else {
                        Text("Save", color = textColorForTheme(MidnightBlue))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { saveDialogVisible = false }, enabled = !isSaving) {
                    Text("Cancel", color = textColorForTheme(MidnightBlue))
                }
            }
        )
    }

    AiChatDrawer(
        visible = showAiChat,
        onDismiss = { showAiChat = false }
    )

    if (showVton) {
        VtonDialog(
            garmentFile = vtonFile,
            onDismiss = { showVton = false }
        )
    }
}

private fun minScaleFor(style: Int): Float = if (style < 3) 0.125f else 0.5f
private fun maxScaleFor(style: Int): Float = if (style < 3) 0.5f else 3.5f

private fun buttonDstSize(texW: Int, texH: Int, contentW: Float, contentH: Float, base: Float, scale: Float, minScale: Float = 0.5f, maxScale: Float = 2f): Pair<Float, Float> {
    // Normalize by content box so every style renders the same visible size at the same scale.
    val target = (base * scale).coerceIn(base * minScale, base * maxScale)
    val s = target / maxOf(contentW.coerceAtLeast(1f), contentH.coerceAtLeast(1f))
    return texW * s to texH * s
}

private fun contentBounds(bmp: android.graphics.Bitmap): Pair<Float, Float> {
    val w = bmp.width
    val h = bmp.height
    val pixels = IntArray(w * h)
    bmp.getPixels(pixels, 0, w, 0, 0, w, h)
    var minX = w
    var minY = h
    var maxX = -1
    var maxY = -1
    for (y in 0 until h) {
        for (x in 0 until w) {
            if ((pixels[y * w + x] ushr 24) > 16) {
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
    }
    if (maxX < 0) return w.toFloat() to h.toFloat()
    return (maxX - minX + 1).toFloat() to (maxY - minY + 1).toFloat()
}

private fun applyCircularMask(src: android.graphics.Bitmap): android.graphics.Bitmap {
    // Buttons are round: kill any square halo outside the disc, feather the edge.
    val out = src.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
    val w = out.width
    val h = out.height
    val cx = w / 2f
    val cy = h / 2f
    val r = minOf(w, h) / 2f
    val pixels = IntArray(w * h)
    out.getPixels(pixels, 0, w, 0, 0, w, h)
    for (y in 0 until h) {
        for (x in 0 until w) {
            val dx = (x + 0.5f - cx) / r
            val dy = (y + 0.5f - cy) / r
            val d = kotlin.math.sqrt(dx * dx + dy * dy)
            val m = ((0.82f - d) / 0.08f).coerceIn(0f, 1f)
            if (m < 1f) {
                val i = y * w + x
                val p = pixels[i]
                val a = ((p ushr 24) * m).toInt()
                pixels[i] = (a shl 24) or (p and 0x00FFFFFF)
            }
        }
    }
    out.setPixels(pixels, 0, w, 0, 0, w, h)
    return out
}

private val hitCache = mutableMapOf<String, android.graphics.Bitmap>()

private fun hitBitmap(path: String): android.graphics.Bitmap? = synchronized(hitCache) {
    hitCache[path]?.takeIf { !it.isRecycled }?.let { return it }
    if (hitCache.size > 4) {
        hitCache.values.forEach { runCatching { if (!it.isRecycled) it.recycle() } }
        hitCache.clear()
    }
    val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
    val bmp = runCatching { BitmapFactory.decodeFile(path, opts) }.getOrNull()
    if (bmp != null) hitCache[path] = bmp
    bmp
}

private fun keyOutBlack(src: android.graphics.Bitmap, lo: Float = 25f, hi: Float = 140f): android.graphics.Bitmap {
    // Photo has a black background: make dark pixels transparent.
    val w = src.width
    val h = src.height
    val out = src.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
    val pixels = IntArray(w * h)
    out.getPixels(pixels, 0, w, 0, 0, w, h)
    for (i in pixels.indices) {
        val p = pixels[i]
        val m = maxOf((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
        val a = ((m - lo) / (hi - lo)).coerceIn(0f, 1f)
        pixels[i] = ((a * 255).toInt() shl 24) or (p and 0x00FFFFFF)
    }
    out.setPixels(pixels, 0, w, 0, 0, w, h)
    return out
}

private fun makeDyedBitmap(
    path: String,
    dye: Color,
    strength: Float
): android.graphics.Bitmap {
    val src = BitmapFactory.decodeFile(path) ?: error("Cannot decode $path")
    val w = src.width
    val h = src.height
    val out = src.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
    val pixels = IntArray(w * h)
    out.getPixels(pixels, 0, w, 0, 0, w, h)
    val dyeR = (dye.red * 255).toInt()
    val dyeG = (dye.green * 255).toInt()
    val dyeB = (dye.blue * 255).toInt()
    // Whole-garment dye, no gradient edge
    val k = (strength * 0.85f).coerceIn(0f, 1f)
    for (i in pixels.indices) {
        val p = pixels[i]
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        // keep pure white canvas background untouched
        if (r > 242 && g > 242 && b > 242) continue
        // preserve shading: scale dye by original luminance so wrinkles stay
        val lum = (r + g + b) / 3f / 255f
        val shade = 0.35f + 0.65f * lum
        val nr = (r * (1f - k) + dyeR * shade * k).toInt().coerceIn(0, 255)
        val ng = (g * (1f - k) + dyeG * shade * k).toInt().coerceIn(0, 255)
        val nb = (b * (1f - k) + dyeB * shade * k).toInt().coerceIn(0, 255)
        pixels[i] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
    }
    out.setPixels(pixels, 0, w, 0, 0, w, h)
    if (out !== src) runCatching { if (!src.isRecycled) src.recycle() }
    return out
}
