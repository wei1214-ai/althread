package com.example.myapplicationkoG

import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/**
 * Custom ExoPlayer Video Player composable.
 * Supports auto-play, seamless looping, center-crop scaling, and reliable lifecycle management.
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayer(
    videoUrl: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // Initialize ExoPlayer instance for video rendering
    val exoPlayer = remember(videoUrl) {
        ExoPlayer.Builder(context).build().apply {
            val mediaItem = MediaItem.fromUri(Uri.parse(videoUrl))
            setMediaItem(mediaItem)
            repeatMode = Player.REPEAT_MODE_ONE // Loop video endlessly
            prepare()
            playWhenReady = true // Auto-start video playback
        }
    }

    // Release player memory when component leaves screen
    DisposableEffect(videoUrl) {
        onDispose {
            exoPlayer.stop()
            exoPlayer.release()
        }
    }

    // Embed Android Native PlayerView in Compose
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = exoPlayer
                useController = false // Hide playback controls for clean Feed UI
                // 💡 Center Crop (Aspect Ratio Zoom) to fill the 300dp container smoothly without black bars
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            }
        },
        update = { playerView ->
            // 💡 Ensure the player remains bound even if Compose reuses the view
            if (playerView.player != exoPlayer) {
                playerView.player = exoPlayer
            }
        },
        modifier = modifier.fillMaxSize()
    )
}