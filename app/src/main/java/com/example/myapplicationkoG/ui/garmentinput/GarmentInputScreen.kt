package com.example.myapplicationkoG.ui.garmentinput

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.myapplicationkoG.R
import com.example.myapplicationkoG.textColorForTheme
import com.example.myapplicationkoG.domain.model.GarmentSideId
import com.example.myapplicationkoG.ui.theme.Cyan
import com.example.myapplicationkoG.ui.theme.MidnightBlue
import java.io.File

private fun createCameraUri(context: Context): Uri {
    val file = File(context.cacheDir, "camera_${System.currentTimeMillis()}.jpg")
    file.parentFile?.mkdirs()
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

@Composable
fun GarmentInputScreen(
    onOpenEditor: () -> Unit,
    onBack: () -> Unit,
    viewModel: GarmentInputViewModel = viewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var frontCameraUri by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<Uri?>(null) }
    var backCameraUri by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<Uri?>(null) }
    var pendingCameraSide by androidx.compose.runtime.saveable.rememberSaveable(stateSaver = androidx.compose.runtime.saveable.Saver(
        save = { it?.name },
        restore = { it?.let { runCatching { GarmentSideId.valueOf(it) }.getOrNull() } }
    )) { mutableStateOf<GarmentSideId?>(null) }

    val pickFrontAlbum = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { viewModel.onPickedImage(GarmentSideId.FRONT, it) }
    }
    val pickBackAlbum = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { viewModel.onPickedImage(GarmentSideId.BACK, it) }
    }
    val takeFrontPicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success: Boolean ->
        if (success) frontCameraUri?.let { viewModel.onPickedImage(GarmentSideId.FRONT, it) }
    }
    val takeBackPicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success: Boolean ->
        if (success) backCameraUri?.let { viewModel.onPickedImage(GarmentSideId.BACK, it) }
    }
    val requestCameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted: Boolean ->
        if (granted) {
            pendingCameraSide?.let { side ->
                when (side) {
                    GarmentSideId.FRONT -> {
                        val uri = createCameraUri(context)
                        frontCameraUri = uri
                        takeFrontPicture.launch(uri)
                    }
                    GarmentSideId.BACK -> {
                        val uri = createCameraUri(context)
                        backCameraUri = uri
                        takeBackPicture.launch(uri)
                    }
                }
            }
        } else {
            android.widget.Toast.makeText(context, "Camera permission required", android.widget.Toast.LENGTH_SHORT).show()
        }
        pendingCameraSide = null
    }

    fun launchCamera(side: GarmentSideId) {
        if (state.isLoading) return
        val perm = Manifest.permission.CAMERA
        val granted = ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            val uri = createCameraUri(context)
            when (side) {
                GarmentSideId.FRONT -> { frontCameraUri = uri; takeFrontPicture.launch(uri) }
                GarmentSideId.BACK -> { backCameraUri = uri; takeBackPicture.launch(uri) }
            }
        } else {
            pendingCameraSide = side
            requestCameraPermission.launch(perm)
        }
    }

    val ready = state.frontCutoutPath != null && state.backCutoutPath != null && !state.isLoading
    val enabled = !state.isLoading
    val isDarkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val screenBackground = if (isDarkTheme) MaterialTheme.colorScheme.background else Color(0xFFFAFAFA)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(screenBackground)
            .padding(horizontal = 24.dp, vertical = 32.dp)
    ) {
        Text("New Design Space", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = textColorForTheme(Color(0xFF1B1B1B)))
        Spacer(Modifier.height(6.dp))
        Text(
            "Upload a clear FRONT photo, then a BACK photo. Lay the garment flat so the garment can be isolated clearly.",
            fontSize = 14.sp,
            color = textColorForTheme(Color(0xFF666666)),
        )
        Spacer(Modifier.height(24.dp))

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            PickerBox(
                label = "FRONT",
                cutoutPath = state.frontCutoutPath,
                enabled = enabled,
                modifier = Modifier.weight(1f),
                onAlbumClick = { pickFrontAlbum.launch("image/*") },
                onCameraClick = { launchCamera(GarmentSideId.FRONT) }
            )
            PickerBox(
                label = "BACK",
                cutoutPath = state.backCutoutPath,
                enabled = enabled,
                modifier = Modifier.weight(1f),
                onAlbumClick = { pickBackAlbum.launch("image/*") },
                onCameraClick = { launchCamera(GarmentSideId.BACK) }
            )
        }

        // Center vertically between BACK box and buttons
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                if (state.isLoading) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = textColorForTheme(Color(0xFF1B1B1B)))
                        Spacer(Modifier.size(12.dp))
                        val sideLabel = when (state.loadingSide) {
                            GarmentSideId.FRONT -> "Processing FRONT image…"
                            GarmentSideId.BACK -> "Processing BACK image…"
                            else -> "Processing image…"
                        }
                        Text(sideLabel, fontSize = 13.sp, color = textColorForTheme(Color(0xFF1B1B1B)))
                    }
                }
                state.frontError?.let {
                    if (state.isLoading) Spacer(Modifier.height(4.dp))
                    Text("FRONT: $it", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
                state.backError?.let {
                    Spacer(Modifier.height(2.dp))
                    Text("BACK: $it", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onBack,
                modifier = Modifier.weight(1f).height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isDarkTheme) MaterialTheme.colorScheme.surfaceVariant else Color(0xFFEEEEEE),
                    contentColor = textColorForTheme(Color(0xFF1B1B1B))
                ),
            ) { Text("Back", fontWeight = FontWeight.SemiBold) }

            Button(
                onClick = onOpenEditor,
                enabled = ready,
                modifier = Modifier.weight(1f).height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1B1B1B), contentColor = Color.White),
            ) { Text("Open Editor", fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun PickerBox(
    label: String,
    cutoutPath: String?,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onAlbumClick: () -> Unit,
    onCameraClick: () -> Unit
) {
    val hasImage = cutoutPath != null
    val isDarkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (hasImage) Cyan.copy(alpha = 0.15f) else if (isDarkTheme) MaterialTheme.colorScheme.surface else Color.White)
            .border(
                width = 1.5.dp,
                color = if (hasImage) MidnightBlue else if (isDarkTheme) MaterialTheme.colorScheme.outline else Color(0xFFCCCCCC),
                shape = RoundedCornerShape(16.dp),
            )
    ) {
        // Main center content - large box no longer opens album, use bottom-right buttons only
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            if (hasImage) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    AsyncImage(
                        model = cutoutPath,
                        contentDescription = null,
                        modifier = Modifier.size(120.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFFEEEEEE)),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(label, fontWeight = FontWeight.Bold, color = textColorForTheme(MidnightBlue))
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(painter = painterResource(id = R.drawable.addfromalbum), contentDescription = null, modifier = Modifier.size(28.dp), tint = textColorForTheme(Color(0xFF1B1B1B)))
                    Spacer(Modifier.height(6.dp))
                    Text("Select $label photo", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = textColorForTheme(Color(0xFF1B1B1B)))
                }
            }
        }
        // Right-bottom two small buttons (mimic Home's Cyan 40dp button)
        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SmallActionButton(
                iconRes = R.drawable.addfromalbum,
                enabled = enabled,
                onClick = onAlbumClick
            )
            SmallActionButton(
                iconRes = R.drawable.addfromcamera,
                enabled = enabled,
                onClick = onCameraClick
            )
        }
    }
}

@Composable
private fun SmallActionButton(
    @DrawableRes iconRes: Int,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (enabled) Cyan else Color.LightGray)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = null,
            tint = MidnightBlue,
            modifier = Modifier.size(22.dp)
        )
    }
}