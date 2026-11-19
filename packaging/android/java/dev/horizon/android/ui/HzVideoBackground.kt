package dev.horizon.android.ui

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import androidx.annotation.DrawableRes
import androidx.annotation.RawRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import dev.horizon.android.ui.theme.HzMotion

/**
 * Looping, muted video background (the wallpaper videos from the RussoViller Purple theme).
 * The still frame is drawn underneath and the video fades in over it once the first frame is rendered, so a
 * device that cannot decode the clip simply keeps the still. Playback pauses with the activity and the player is
 * released as soon as the composable leaves, so nothing decodes while the game runs.
 */
@Composable
internal fun HzVideoBackground(
    @RawRes video: Int,
    @DrawableRes still: Int,
    modifier: Modifier = Modifier,
    paused: Boolean = false
) {
    val context = LocalContext.current
    var firstFrame by remember(video) { mutableStateOf(false) }
    val fade by animateFloatAsState(if (firstFrame) 1f else 0f, tween(HzMotion.Medium), label = "VideoFade")
    val player = remember(video) { HzLoopingVideo(context.applicationContext, video) { firstFrame = true } }

    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(player, paused) { player.setPaused(paused) }
    DisposableEffect(player, context, paused) {
        val owner = context.findLifecycleOwner()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) player.setPaused(true)
            else if (event == Lifecycle.Event.ON_RESUME) player.setPaused(paused)
        }
        owner?.lifecycle?.addObserver(observer)
        onDispose { owner?.lifecycle?.removeObserver(observer) }
    }

    Box(modifier) {
        Image(
            painter = painterResource(still),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        AndroidView(
            factory = { ctx -> TextureView(ctx).also { player.bind(it) } },
            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = fade }
        )
    }
}

private tailrec fun Context.findLifecycleOwner(): LifecycleOwner? = when (this) {
    is LifecycleOwner -> this
    is ContextWrapper -> baseContext.findLifecycleOwner()
    else -> null
}

private class HzLoopingVideo(
    private val context: Context,
    @RawRes private val rawRes: Int,
    private val onFirstFrame: () -> Unit
) : TextureView.SurfaceTextureListener {
    private var view: TextureView? = null
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var wantPlay = true
    private var videoW = 0
    private var videoH = 0

    fun bind(v: TextureView) {
        view = v
        v.isOpaque = false
        v.surfaceTextureListener = this
        val st = v.surfaceTexture
        if (v.isAvailable && st != null) start(st)
    }

    fun setPaused(paused: Boolean) {
        wantPlay = !paused
        val mp = player ?: return
        try {
            if (paused) { if (mp.isPlaying) mp.pause() } else mp.start()
        } catch (_: IllegalStateException) {
            // Not prepared yet: onPrepared starts it when wantPlay is still true.
        }
    }

    fun release() {
        view?.surfaceTextureListener = null
        stop()
        view = null
    }

    private fun stop() {
        runCatching { player?.release() }
        player = null
        runCatching { surface?.release() }
        surface = null
    }

    private fun start(st: SurfaceTexture) {
        stop()
        val s = Surface(st)
        val mp = MediaPlayer()
        try {
            context.resources.openRawResourceFd(rawRes).use { mp.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            mp.setSurface(s)
            mp.isLooping = true
            mp.setVolume(0f, 0f)
            mp.setOnVideoSizeChangedListener { _, w, h ->
                videoW = w
                videoH = h
                view?.let { applyCenterCrop(it.width, it.height) }
            }
            mp.setOnInfoListener { _, what, _ ->
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) onFirstFrame()
                false
            }
            mp.setOnErrorListener { _, _, _ -> true } // keep the still frame
            mp.setOnPreparedListener { if (wantPlay) runCatching { it.start() } }
            mp.prepareAsync()
            player = mp
            surface = s
        } catch (_: Exception) {
            runCatching { mp.release() }
            runCatching { s.release() }
        }
    }

    /** TextureView stretches the video to the view; scale one axis back up so it covers the view without distortion. */
    private fun applyCenterCrop(w: Int, h: Int) {
        val v = view ?: return
        if (w <= 0 || h <= 0 || videoW <= 0 || videoH <= 0) return
        val viewRatio = w.toFloat() / h
        val videoRatio = videoW.toFloat() / videoH
        val sx = if (videoRatio > viewRatio) videoRatio / viewRatio else 1f
        val sy = if (videoRatio > viewRatio) 1f else viewRatio / videoRatio
        v.setTransform(Matrix().apply { setScale(sx, sy, w / 2f, h / 2f) })
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        start(st)
        applyCenterCrop(width, height)
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = applyCenterCrop(width, height)

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        stop()
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
}
