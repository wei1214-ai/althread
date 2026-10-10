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
import androidx.media3.common.VideoSize
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
    modifier: Modifier = Modifier,
    autoPlay: Boolean = false,
    // FIT shows the full frame (no crop). The feed box already adapts to the
    // video aspect via onVideoSizeChanged, so no black bars in practice.
    resizeMode: Int = AspectRatioFrameLayout.RESIZE_MODE_FIT,
    onVideoSizeChanged: ((width: Int, height: Int) -> Unit)? = null
) {
    val appContext = LocalContext.current.applicationContext

    // Initialize ExoPlayer instance for video rendering
    // Use applicationContext to avoid leaking Activity on rotation.
    val exoPlayer = remember(videoUrl) {
        ExoPlayer.Builder(appContext).build().apply {
            val mediaItem = MediaItem.fromUri(Uri.parse(videoUrl))
            setMediaItem(mediaItem)
            repeatMode = Player.REPEAT_MODE_ONE // Loop video endlessly
            prepare()
            playWhenReady = autoPlay // Default paused: feed must not autoplay all videos
        }
    }

    // Release player memory when component leaves screen
    DisposableEffect(videoUrl) {
        val sizeListener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    onVideoSizeChanged?.invoke(videoSize.width, videoSize.height)
                }
            }
        }
        exoPlayer.addListener(sizeListener)
        onDispose {
            runCatching { exoPlayer.removeListener(sizeListener) }
            runCatching { exoPlayer.pause() }
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
                setResizeMode(resizeMode)
            }
        },
        update = { playerView ->
            // Rebind the player if Compose recycled the underlying view.
            if (playerView.player != exoPlayer) {
                playerView.player = exoPlayer
            }
            if (playerView.resizeMode != resizeMode) {
                playerView.setResizeMode(resizeMode)
            }
        },
        modifier = modifier.fillMaxSize()
    )
}