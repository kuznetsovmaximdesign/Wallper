package com.wallper.live.ui.editor

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import coil3.compose.AsyncImage
import com.wallper.live.R
import com.wallper.live.WallperApplication
import com.wallper.live.core.Clip
import com.wallper.live.core.Focus
import com.wallper.live.core.Footage
import com.wallper.live.core.Playback
import com.wallper.live.core.WallpaperRoles
import com.wallper.live.core.WallpaperTarget
import com.wallper.live.core.formatDuration
import com.wallper.live.media.ClipSource
import com.wallper.live.playback.LoopSession
import com.wallper.live.ui.EditorTarget
import com.wallper.live.ui.MainUiState
import com.wallper.live.ui.components.GlassSurface
import com.wallper.live.ui.components.LoopVideo
import kotlinx.coroutines.delay

/** Callbacks of the viewer, grouped to keep call sites readable. */
data class EditorActions(
    val onBack: (() -> Unit)?,
    val onOpen: (EditorTarget) -> Unit,
    val onCreateFromFootage: (Footage, Long, Long, Float, Focus, WallpaperTarget) -> Unit,
    val onCreateFromDevice: (Uri, Long, Long, Float, Focus, WallpaperTarget) -> Unit,
    val onApply: (Clip, WallpaperTarget) -> Unit,
    val onDelete: (Clip) -> Unit,
    val onSaveSpeed: (String, Float) -> Unit,
    val onSaveFocus: (String, Focus) -> Unit,
)

/**
 * Immersive viewer: the wallpaper plays full screen, a swipe moves to the next one,
 * and every control lives in a single floating dock at the bottom.
 */
@Composable
fun EditorScreen(target: EditorTarget, state: MainUiState, actions: EditorActions, modifier: Modifier = Modifier) {
    when (target) {
        is EditorTarget.FromFootage -> FootageViewer(target.footage, state, actions, modifier)
        is EditorTarget.FromClip -> ClipViewer(target.clipId, state, actions, modifier)
        is EditorTarget.FromDevice -> DeviceViewer(target.uri, state, actions, modifier)
    }
}

// region Footage (Apple Aerials)

@Composable
private fun FootageViewer(footage: Footage, state: MainUiState, actions: EditorActions, modifier: Modifier) {
    val items = state.visibleFootage.takeIf { list -> list.any { it.id == footage.id } } ?: listOf(footage)
    val edit = rememberSourceEdit(footage.id, ClipSource.Remote(footage.previewUrl ?: footage.masterUrl))
    val spec = edit.spec(footage.previewUrl?.let(Uri::parse))
    var framing by remember { mutableStateOf(false) }

    ViewerFrame(
        modifier = modifier,
        title = footage.title,
        subtitle = footage.collection?.takeIf { it != footage.title } ?: stringResource(R.string.label_4k),
        onBack = actions.onBack,
        framing = framing,
        pager = {
            ViewerPager(items, footage.id, { it.id }, { actions.onOpen(EditorTarget.FromFootage(it)) }, !framing) { item, active ->
                VideoPage(
                    spec = if (active) spec else null,
                    still = item.coverUrl,
                    animateStill = active && item.previewUrl == null,
                    state = state,
                    speed = edit.speed,
                    focus = if (active) edit.focus else Focus.Center,
                    onFocus = if (active && framing) ({ edit.focus = it }) else null,
                )
            }
        },
        dock = {
            Dock(
                speed = edit.speed,
                onSpeed = { edit.speed = it },
                onSpeedDone = {},
                fragment = edit.fragment(),
                framing = framing,
                onFraming = { framing = !framing },
                install = InstallModel(
                    progress = state.runningExport(footage.id)?.progress,
                    lockChoice = WallpaperRoles.separateLockSupported,
                ),
                onInstall = { where ->
                    actions.onCreateFromFootage(footage, edit.startMs, edit.loopMs, edit.speed, edit.focus, where)
                },
            )
        },
    )
}

// endregion

// region Library clip

@Composable
private fun ClipViewer(clipId: String, state: MainUiState, actions: EditorActions, modifier: Modifier) {
    val clip = state.clips.firstOrNull { it.id == clipId } ?: return
    val context = LocalContext.current
    val library = remember(context) { (context.applicationContext as WallperApplication).container.library }
    var speed by remember(clip.id) { mutableFloatStateOf(state.settings.speedOf(clip.id)) }
    var focus by remember(clip.id) { mutableStateOf(state.settings.focusOf(clip.id)) }
    var framing by remember { mutableStateOf(false) }

    // Persist framing shortly after the finger lifts.
    LaunchedEffect(clip.id, focus) {
        delay(400)
        if (focus != state.settings.focusOf(clip.id)) actions.onSaveFocus(clip.id, focus)
    }

    val roles = state.rolesOf(clip)
    ViewerFrame(
        modifier = modifier,
        title = clip.title,
        subtitle = (if (clip.isUhd) "4K · " else "") + formatDuration(clip.loopMs),
        onBack = actions.onBack,
        framing = framing,
        onDelete = { actions.onDelete(clip) },
        pager = {
            ViewerPager(state.clips, clip.id, { it.id }, { actions.onOpen(EditorTarget.FromClip(it.id)) }, !framing) { item, active ->
                VideoPage(
                    spec = if (active) {
                        LoopSession.LoopSpec(
                            mediaItem = MediaItem.fromUri(Uri.fromFile(library.fileOf(item))),
                            loopMs = item.loopMs,
                            fadeMs = item.fadeMs,
                        )
                    } else {
                        null
                    },
                    still = library.coverOf(item),
                    animateStill = false,
                    state = state,
                    speed = speed,
                    focus = if (active) focus else state.settings.focusOf(item.id),
                    onFocus = if (active && framing) ({ focus = it }) else null,
                )
            }
        },
        dock = {
            Dock(
                speed = speed,
                onSpeed = { speed = it },
                onSpeedDone = { actions.onSaveSpeed(clip.id, speed) },
                fragment = null,
                framing = framing,
                onFraming = { framing = !framing },
                install = InstallModel(
                    done = roles.isNotEmpty(),
                    lockChoice = WallpaperRoles.separateLockSupported,
                ),
                onInstall = { where -> actions.onApply(clip, where) },
            )
        },
    )
}

// endregion

// region Device video

@Composable
private fun DeviceViewer(uri: Uri, state: MainUiState, actions: EditorActions, modifier: Modifier) {
    val edit = rememberSourceEdit(uri.toString(), ClipSource.Local(uri))
    val spec = edit.spec(uri)
    var framing by remember { mutableStateOf(false) }
    ViewerFrame(
        modifier = modifier,
        title = stringResource(R.string.tab_mine),
        subtitle = edit.duration?.let { formatDuration(it) },
        onBack = actions.onBack,
        framing = framing,
        pager = {
            VideoPage(
                spec = spec,
                still = null,
                animateStill = false,
                state = state,
                speed = edit.speed,
                focus = edit.focus,
                onFocus = if (framing) ({ edit.focus = it }) else null,
            )
        },
        dock = {
            Dock(
                speed = edit.speed,
                onSpeed = { edit.speed = it },
                onSpeedDone = {},
                fragment = edit.fragment(),
                framing = framing,
                onFraming = { framing = !framing },
                install = InstallModel(lockChoice = WallpaperRoles.separateLockSupported),
                onInstall = { where ->
                    actions.onCreateFromDevice(uri, edit.startMs, edit.loopMs, edit.speed, edit.focus, where)
                },
            )
        },
    )
}

// endregion

// region Building blocks

/** Fragment/speed/framing of a source being turned into a wallpaper. */
@Stable
private class SourceEdit {
    var duration by mutableStateOf<Long?>(null)
    var startMs by mutableLongStateOf(-1L)
    var loopMs by mutableLongStateOf(Playback.MAX_LOOP_MS)
    var speed by mutableFloatStateOf(Playback.DEFAULT_SPEED)
    var focus by mutableStateOf(Focus.Center)
    var committed by mutableStateOf<Pair<Long, Long>?>(null)

    fun fragment(): FragmentModel? {
        val total = duration ?: return null
        if (startMs < 0) return null
        return FragmentModel(total, startMs, loopMs) { start, loop ->
            startMs = start
            loopMs = loop
        }
    }

    /** Preview loop of the committed fragment, streamed from [uri]. */
    fun spec(uri: Uri?): LoopSession.LoopSpec? {
        val (start, loop) = committed ?: return null
        uri ?: return null
        return LoopSession.LoopSpec(
            mediaItem = MediaItem.Builder()
                .setUri(uri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(start)
                        .setEndPositionMs(start + loop + Playback.FADE_MS)
                        .build(),
                )
                .build(),
            loopMs = loop,
            fadeMs = Playback.FADE_MS,
        )
    }
}

@Composable
private fun rememberSourceEdit(key: String, probeSource: ClipSource): SourceEdit {
    val context = LocalContext.current
    val probe = remember(context) { (context.applicationContext as WallperApplication).container.probe }
    val edit = remember(key) { SourceEdit() }
    LaunchedEffect(key) {
        val total = probe.durationMs(probeSource) ?: return@LaunchedEffect
        edit.duration = total
        if (edit.startMs < 0) {
            // Default fragment: 20% in (past Apple's fade-in), 30 s long.
            edit.startMs = (total * 0.2).toLong().coerceAtMost((total - Playback.MIN_LOOP_MS).coerceAtLeast(0))
            edit.loopMs = minOf(Playback.MAX_LOOP_MS, total - edit.startMs - Playback.FADE_MS)
                .coerceAtLeast(Playback.MIN_LOOP_MS)
        }
    }
    // Rebuild the preview only once the fragment stops changing.
    LaunchedEffect(key, edit.startMs, edit.loopMs) {
        if (edit.startMs < 0) return@LaunchedEffect
        delay(250)
        edit.committed = edit.startMs to edit.loopMs
    }
    return edit
}

@Composable
private fun <T> ViewerPager(
    items: List<T>,
    currentId: String,
    id: (T) -> String,
    onSettled: (T) -> Unit,
    swipeEnabled: Boolean,
    page: @Composable (item: T, active: Boolean) -> Unit,
) {
    val initial = items.indexOfFirst { id(it) == currentId }.coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = initial) { items.size }
    LaunchedEffect(pagerState, items) {
        snapshotFlow { pagerState.settledPage }.collect { index ->
            items.getOrNull(index)?.let { if (id(it) != currentId) onSettled(it) }
        }
    }
    HorizontalPager(
        state = pagerState,
        key = { id(items[it]) },
        userScrollEnabled = swipeEnabled,
        beyondViewportPageCount = 0,
        modifier = Modifier.fillMaxSize(),
    ) { index ->
        page(items[index], index == pagerState.settledPage && id(items[index]) == currentId)
    }
}

@Composable
private fun VideoPage(
    spec: LoopSession.LoopSpec?,
    still: Any?,
    animateStill: Boolean,
    state: MainUiState,
    speed: Float,
    focus: Focus,
    onFocus: ((Focus) -> Unit)?,
) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (still != null) Still(still, focus, animate = animateStill)
        if (spec != null) {
            LoopVideo(
                spec = spec,
                seamless = state.settings.seamlessLoop,
                speed = speed,
                focus = focus,
                frostReveal = state.settings.frostReveal,
                revealKey = spec.mediaItem.localConfiguration?.uri,
                onFocusChange = onFocus,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Apple's still; 240 fps scenes drift slowly (Ken Burns) until the 4K clip is prepared. */
@Composable
private fun Still(model: Any, focus: Focus, animate: Boolean) {
    val scale = if (animate) {
        val transition = rememberInfiniteTransition(label = "kenBurns")
        transition.animateFloat(
            initialValue = 1.03f,
            targetValue = 1.14f,
            animationSpec = infiniteRepeatable(tween(16_000, easing = LinearEasing), RepeatMode.Reverse),
            label = "kenBurnsScale",
        ).value
    } else {
        1f
    }
    AsyncImage(
        model = model,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        alignment = BiasAlignment(focus.x * 2f - 1f, focus.y * 2f - 1f),
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
    )
}

/** Shared chrome: back/delete on top, title over a scrim and the dock at the bottom. */
@Composable
private fun ViewerFrame(
    modifier: Modifier,
    title: String,
    subtitle: String?,
    onBack: (() -> Unit)?,
    framing: Boolean,
    onDelete: (() -> Unit)? = null,
    pager: @Composable () -> Unit,
    dock: @Composable () -> Unit,
) {
    Box(modifier.fillMaxSize().background(Color.Black)) {
        pager()

        // Scrims keep the chrome legible over bright skies.
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(140.dp)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent))),
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(260.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f)))),
        )

        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            if (onBack != null) {
                RoundGlassButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), tint = Color.White)
                }
            } else {
                Spacer(Modifier.size(40.dp))
            }
            AnimatedVisibility(framing, enter = fadeIn(), exit = fadeOut()) {
                GlassSurface(RoundedCornerShape(50)) {
                    Text(
                        stringResource(R.string.hint_frame),
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                    )
                }
            }
            if (onDelete != null) {
                RoundGlassButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, stringResource(R.string.action_delete), tint = Color.White)
                }
            } else {
                Spacer(Modifier.size(40.dp))
            }
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(start = 20.dp, end = 20.dp, bottom = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
                if (subtitle != null) {
                    Text(
                        subtitle.uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.75f),
                    )
                }
                Text(title, style = MaterialTheme.typography.headlineMedium, color = Color.White)
            }
            dock()
        }
    }
}

@Composable
private fun RoundGlassButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    GlassSurface(CircleShape, Modifier.size(40.dp)) {
        IconButton(onClick = onClick, modifier = Modifier.fillMaxSize()) { content() }
    }
}

// endregion
