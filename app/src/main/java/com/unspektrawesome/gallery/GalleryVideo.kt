package com.unspektrawesome.gallery

import com.unspektrawesome.language.languageSemantics
import android.view.LayoutInflater
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.PlayerView
import com.unspektrawesome.R
import com.unspektrawesome.language.AppLanguage
import com.unspektrawesome.language.LanguageText as Text
import com.unspektrawesome.ui.camera.CameraColors
import com.unspektrawesome.ui.camera.Diagram
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.delay
import java.util.Locale

internal fun videoDuration(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
    else String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
}

@androidx.annotation.OptIn(UnstableApi::class, ExperimentalApi::class)
@Composable
internal fun GalleryVideo(photo: GalleryPhoto, active: Boolean, initialPosition: Long, onPositionChanged: (Long) -> Unit,
    keepControlsVisible: Boolean = false, onControlsHidden: () -> Unit = {},
    onControlsHeightChanged: (Dp) -> Unit = {},
    topControls: (@Composable (() -> Unit) -> Unit)? = null,
    bottomActions: (@Composable (() -> Unit) -> Unit)? = null) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var position by remember(photo.uri) { mutableLongStateOf(initialPosition) }
    val savePosition by rememberUpdatedState(onPositionChanged)
    var duration by remember(photo.uri, photo.generation) { mutableLongStateOf(photo.durationMs) }
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    var playing by remember { mutableStateOf(false) }
    var playRequested by remember { mutableStateOf(false) }
    var scrubbing by remember { mutableStateOf(false) }
    var error by remember(photo.uri) { mutableStateOf(false) }
    var frameReady by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var controlsVisible by remember(photo.uri) { mutableStateOf(true) }
    var controlsInteraction by remember { mutableIntStateOf(0) }
    val accessibility = LocalAccessibilityManager.current
    val density = LocalDensity.current
    fun revealControls() { controlsVisible = true; controlsInteraction++ }
    LaunchedEffect(active, resumed) { if (active && resumed) revealControls() }
    LaunchedEffect(active, resumed, controlsVisible, controlsInteraction, scrubbing, keepControlsVisible, error) {
        if (active && resumed && controlsVisible && !scrubbing && !keepControlsVisible && !error) {
            delay(accessibility?.calculateRecommendedTimeoutMillis(3_000L, containsIcons = true,
                containsText = true, containsControls = true) ?: 3_000L)
            controlsVisible = false
        }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    DisposableEffect(photo.uri, photo.generation, active, resumed, retry) {
        frameReady = false
        val instance = if (active && resumed) ExoPlayer.Builder(context,
            DefaultRenderersFactory(context).forceEnableMediaCodecAsynchronousQueueing())
            .experimentalSetDynamicSchedulingEnabled(true).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            setHandleAudioBecomingNoisy(true)
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
                override fun onEvents(player: Player, events: Player.Events) {
                    playRequested = player.playWhenReady && player.playbackState != Player.STATE_ENDED
                    player.duration.takeIf { it > 0 }?.let { duration = it }
                }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) playing = false
                }
                override fun onPlayerError(failure: PlaybackException) { error = true; playing = false }
                override fun onRenderedFirstFrame() { frameReady = true }
            })
            setMediaItem(MediaItem.fromUri(photo.uri))
            seekTo(position)
        } else null
        player = instance
        onDispose {
            if (instance != null) {
                position = instance.currentPosition
                savePosition(position)
                instance.release()
            }
            player = null
            playing = false
            playRequested = false
            scrubbing = false
        }
    }
    LaunchedEffect(player) {
        val current = player ?: return@LaunchedEffect
        while (true) {
            if (!scrubbing) position = current.currentPosition
            savePosition(position)
            current.duration.takeIf { it > 0 }?.let { duration = it }
            delay(250)
        }
    }
    LaunchedEffect(player, playing, scrubbing) {
        val current = player ?: return@LaunchedEffect
        if (playing && !scrubbing) while (true) {
            position = current.currentPosition
            delay(16)
        }
    }
    fun togglePlayback() {
        revealControls()
        player?.let {
            if (it.playWhenReady && it.playbackState != Player.STATE_ENDED) it.pause() else {
                if (it.playbackState == Player.STATE_ENDED) it.seekTo(0)
                it.play()
            }
        }
    }
    val enabled = player != null && !error
    val label = AppLanguage.text(if (playRequested) "Pause video" else "Play video")
    Box(Modifier.fillMaxSize().testTag("Gallery video").languageSemantics { language ->
        stateDescription = language.text(if (playing) "Playing" else "Paused")
    }) {
        Box(Modifier.fillMaxSize().clipToBounds().testTag("Video viewport"), contentAlignment = Alignment.Center) {
            player?.let { current ->
                AndroidView(factory = {
                    (LayoutInflater.from(it).inflate(R.layout.gallery_video, null) as PlayerView).apply {
                        setEnableComposeSurfaceSyncWorkaround(true)
                    }
                }, update = {
                    if (it.player !== current) it.player = current
                    it.keepScreenOn = playing
                    // Prepare after attaching the output so a paused video renders its first frame.
                    if (current.playbackState == Player.STATE_IDLE && current.playerError == null) current.prepare()
                },
                    onRelease = { it.player = null; it.keepScreenOn = false }, modifier = Modifier.fillMaxSize().clipToBounds()
                        .then(if (frameReady) Modifier.testTag("Video picture") else Modifier))
            }
            Box(Modifier.matchParentSize().testTag("Video playback surface")
                .clickable(interactionSource = null, indication = null, role = Role.Button) {
                    if (controlsVisible) { controlsVisible = false; onControlsHidden() } else revealControls()
                }
                .languageSemantics { language -> contentDescription = language.text(if (controlsVisible) "Hide controls" else "Show controls") })
            if (!frameReady && !error && active) MediaSpinner(size = 24.dp, track = Diagram.Border)
            if (error) Column(Modifier.clip(Diagram.ControlShape).background(Color(0xE60A0A0A))
                .border(1.dp, Diagram.Border, Diagram.ControlShape).padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Unable to play video", color = CameraColors.Text, fontSize = 12.5.sp, lineHeight = 17.sp)
                GalleryAction("Try again") { error = false; retry++ }
            }
        }
        if (topControls != null) AnimatedVisibility(controlsVisible,
            modifier = Modifier.align(Alignment.TopCenter).padding(12.dp),
            enter = fadeIn(tween(180)), exit = fadeOut(tween(220))) {
            topControls(::revealControls)
        }
        AnimatedVisibility(controlsVisible, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp),
            enter = fadeIn(tween(180)), exit = fadeOut(tween(220))) {
            BoxWithConstraints(Modifier.fillMaxWidth()
                .onSizeChanged { onControlsHeightChanged(with(density) { it.height.toDp() } + 16.dp) }) {
                val shape = Diagram.ControlShape
                val playback: @Composable (Modifier) -> Unit = { playbackModifier ->
                Row(playbackModifier.widthIn(max = 600.dp).fillMaxWidth()
                    .padding(horizontal = 4.dp).testTag("Video controls").clip(shape)
                    .background(Color(0xD90C0C0C)).border(1.dp, Diagram.Border, shape)
                    .padding(start = 2.dp, end = 10.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(48.dp).testTag("Video play pause")
                        .clickable(enabled = enabled, role = Role.Button, onClick = ::togglePlayback)
                        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
                        Box(Modifier.size(40.dp).clip(CircleShape)
                            .border(1.dp, if (enabled) CameraColors.Accent else Diagram.Line, CircleShape),
                            contentAlignment = Alignment.Center) {
                            GallerySymbolIcon(if (playRequested) GallerySymbol.PAUSE else GallerySymbol.PLAY,
                                if (enabled) CameraColors.Accent else Diagram.Hint, Modifier.size(16.dp))
                        }
                    }
                    VideoSeekBar(position, duration, enabled = enabled && duration > 0, modifier = Modifier.weight(1f),
                            onScrubStart = {
                                revealControls()
                                scrubbing = true
                                player?.setScrubbingModeEnabled(true)
                                player?.setSeekParameters(SeekParameters(100_000, 100_000))
                            }, onSeek = {
                                if (position != it) {
                                    position = it
                                    player?.seekTo(it)
                                }
                            }, onScrubStop = {
                                player?.setSeekParameters(SeekParameters.EXACT)
                                player?.seekTo(position)
                                player?.setScrubbingModeEnabled(false)
                                scrubbing = false
                                revealControls()
                                savePosition(position)
                            })
                    Column(horizontalAlignment = Alignment.End) {
                        Text(videoDuration(position), translate = false, color = CameraColors.Text, fontWeight = FontWeight.Medium,
                            fontSize = 11.sp, lineHeight = 13.sp, fontFamily = Diagram.Mono, maxLines = 1)
                        Text(videoDuration(duration), translate = false, color = Diagram.Hint,
                            fontSize = 10.sp, lineHeight = 13.sp, fontFamily = Diagram.Mono, maxLines = 1)
                    }
                }
                }
                if (maxWidth >= 720.dp * density.fontScale && bottomActions != null) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        playback(Modifier.weight(1f))
                        Box(Modifier.width(350.dp)) { bottomActions(::revealControls) }
                    }
                } else Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    playback(Modifier.fillMaxWidth())
                    bottomActions?.invoke(::revealControls)
                }
            }
        }
    }
}
