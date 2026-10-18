package com.wallper.live.ui.editor

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
import com.wallper.live.data.settings.EditChoice
import com.wallper.live.media.ClipSource
import com.wallper.live.playback.LoopSpec
import com.wallper.live.ui.EditorTarget
import com.wallper.live.ui.MainUiState
import com.wallper.live.ui.components.GlassSurface
import com.wallper.live.ui.components.InlineVideo
import com.wallper.live.ui.components.LoopVideo
import com.wallper.live.ui.components.TestTags
import com.wallper.live.ui.components.focusDrag
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

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
    val context = LocalContext.current
    val container = remember(context) { (context.applicationContext as WallperApplication).container }
    val covers = container.covers
    val motion = container.motionThumbs
    val items = state.visibleFootage.takeIf { list -> list.any { it.id == footage.id } } ?: listOf(footage)
    val edit = rememberSourceEdit(footage.id, ClipSource.of(footage.previewUrl ?: footage.masterUrl))
    // Light 1080p stream when Apple has one; otherwise the 4K 240 fps master, played by
    // the layer-dropping decoder at 60/30 fps.
    val preview = footage.previewUrl
    val spec = if (preview != null) {
        edit.spec(Uri.parse(preview), highFrameRate = false)
    } else {
        edit.spec(Uri.parse(footage.masterUrl), highFrameRate = true)
    }
    var framing by remember { mutableStateOf(false) }
    LaunchedEffect(footage.id) { framing = false } // swiping must always mean "next wallpaper"
    val pager = rememberViewerPager(items, footage.id, { it.id }) { actions.onOpen(EditorTarget.FromFootage(it)) }

    ViewerFrame(
        modifier = modifier,
        title = footage.title,
        subtitle = footage.collection?.takeIf { it != footage.title } ?: stringResource(R.string.label_4k),
        onBack = actions.onBack,
        framing = framing,
        pagerState = pager,
        pageCount = items.size,
        pager = {
            ViewerPager(pager, items, footage.id, { it.id }, !framing) { item, active ->
                VideoPage(
                    spec = if (active) spec else null,
                    still = covers.cached(item) ?: motion.poster(item.id) ?: item.coverUrl,
                    loop = if (active) motion.cached(item.id) else null,
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
                onSpeed = edit::chooseSpeed,
                onSpeedDone = {},
                fragment = edit.fragment(),
                hasFragment = true,
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
    val container = remember(context) { (context.applicationContext as WallperApplication).container }
    val library = container.library
    val motion = container.motionThumbs
    var speed by remember(clip.id) { mutableFloatStateOf(state.settings.speedOf(clip.id)) }
    var focus by remember(clip.id) { mutableStateOf(state.settings.focusOf(clip.id)) }
    var framing by remember { mutableStateOf(false) }
    LaunchedEffect(clip.id) { framing = false } // swiping must always mean "next wallpaper"

    // Persist framing shortly after the finger lifts.
    LaunchedEffect(clip.id, focus) {
        delay(400)
        if (focus != state.settings.focusOf(clip.id)) actions.onSaveFocus(clip.id, focus)
    }

    val roles = state.rolesOf(clip)
    val pager = rememberViewerPager(state.clips, clip.id, { it.id }) { actions.onOpen(EditorTarget.FromClip(it.id)) }
    ViewerFrame(
        modifier = modifier,
        title = clip.title,
        subtitle = (if (clip.isUhd) "4K · " else "") + formatDuration(clip.loopMs),
        onBack = actions.onBack,
        framing = framing,
        onDelete = { actions.onDelete(clip) },
        pagerState = pager,
        pageCount = state.clips.size,
        pager = {
            ViewerPager(pager, state.clips, clip.id, { it.id }, !framing) { item, active ->
                VideoPage(
                    spec = if (active) {
                        LoopSpec(
                            uri = Uri.fromFile(library.fileOf(item)),
                            loopMs = item.loopMs,
                            fadeMs = item.fadeMs,
                        )
                    } else {
                        null
                    },
                    still = library.coverOf(item),
                    loop = if (active) motion.cached("clip-${item.id}") else null,
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
                hasFragment = false,
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
    val spec = edit.spec(uri, highFrameRate = false)
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
                loop = null,
                state = state,
                speed = edit.speed,
                focus = edit.focus,
                onFocus = if (framing) ({ edit.focus = it }) else null,
            )
        },
        dock = {
            Dock(
                speed = edit.speed,
                onSpeed = edit::chooseSpeed,
                onSpeedDone = {},
                fragment = edit.fragment(),
                hasFragment = true,
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

    /** True once the speed was picked for this source (not carried over from another). */
    var speedChosen by mutableStateOf(false)
    var focus by mutableStateOf(Focus.Center)

    /** Called when the person picks a speed (not when one is restored or carried over). */
    var onSpeedPicked: (Float) -> Unit = {}

    fun chooseSpeed(value: Float) {
        speed = value
        speedChosen = true
        onSpeedPicked(value)
    }
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
    fun spec(uri: Uri, highFrameRate: Boolean): LoopSpec? {
        val (start, loop) = committed ?: return null
        return LoopSpec(
            uri = uri,
            loopMs = loop,
            fadeMs = Playback.FADE_MS,
            clipStartMs = start,
            clipEndMs = start + loop + Playback.FADE_MS,
            highFrameRate = highFrameRate,
        )
    }
}

@Composable
private fun rememberSourceEdit(key: String, probeSource: ClipSource): SourceEdit {
    val context = LocalContext.current
    val container = remember(context) { (context.applicationContext as WallperApplication).container }
    val probe = container.probe
    val memory = container.edits
    // Coming back to a wallpaper restores what was chosen for it; a new one starts at the
    // speed picked last.
    val edit = remember(key) {
        SourceEdit().apply {
            val saved = memory.choiceFor(key)
            if (saved != null) {
                startMs = saved.startMs
                loopMs = saved.loopMs
                speed = saved.speed ?: memory.startingSpeed()
                speedChosen = saved.speed != null
                focus = saved.focus
            } else {
                speed = memory.startingSpeed()
            }
            onSpeedPicked = memory::pickedSpeed
        }
    }
    LaunchedEffect(key) {
        snapshotFlow { EditChoice(edit.startMs, edit.loopMs, edit.speed.takeIf { edit.speedChosen }, edit.focus) }
            .collectLatest { choice ->
                if (choice.startMs < 0) return@collectLatest
                delay(SAVE_EDIT_MS) // not on every slider tick
                memory.remember(key, choice)
            }
    }
    LaunchedEffect(key) {
        val total = probe.durationMs(probeSource) ?: return@LaunchedEffect
        edit.duration = total
        // A remembered fragment that no longer fits the video starts over.
        if (edit.startMs + Playback.MIN_LOOP_MS > total) edit.startMs = -1L
        if (edit.startMs < 0) {
            // Default fragment: 20% in (past Apple's fade-in), 30 s long.
            edit.startMs = (total * 0.2).toLong().coerceAtMost((total - Playback.MIN_LOOP_MS).coerceAtLeast(0))
            edit.loopMs = minOf(Playback.MAX_LOOP_MS, total - edit.startMs - Playback.FADE_MS)
                .coerceAtLeast(Playback.MIN_LOOP_MS)
        }
    }
    // The first fragment starts playing at once; later edits rebuild the preview only
    // once the fragment stops changing.
    LaunchedEffect(key, edit.startMs, edit.loopMs) {
        if (edit.startMs < 0) return@LaunchedEffect
        if (edit.committed != null) delay(250)
        edit.committed = edit.startMs to edit.loopMs
    }
    return edit
}

@Composable
private fun <T> rememberViewerPager(
    items: List<T>,
    currentId: String,
    id: (T) -> String,
    onSettled: (T) -> Unit,
): PagerState {
    val initial = items.indexOfFirst { id(it) == currentId }.coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = initial) { items.size }
    val latestId by rememberUpdatedState(currentId)
    val settle by rememberUpdatedState(onSettled)
    LaunchedEffect(pagerState, items) {
        snapshotFlow { pagerState.settledPage }.collect { index ->
            items.getOrNull(index)?.let { if (id(it) != latestId) settle(it) }
        }
    }
    // A wallpaper chosen outside the pager (the gallery beside it on a Fold) moves it there;
    // otherwise the pager would keep showing the previous one.
    LaunchedEffect(pagerState, items, currentId) {
        val index = items.indexOfFirst { id(it) == currentId }
        if (index >= 0 && index != pagerState.currentPage && !pagerState.isScrollInProgress) {
            pagerState.scrollToPage(index)
        }
    }
    return pagerState
}

@Composable
private fun <T> ViewerPager(
    pagerState: PagerState,
    items: List<T>,
    currentId: String,
    id: (T) -> String,
    swipeEnabled: Boolean,
    page: @Composable (item: T, active: Boolean) -> Unit,
) {
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

/**
 * One page of the viewer. The real video plays underneath; until it has a picture on
 * screen, the card's live loop (or a still) covers it, so the viewer moves the instant
 * it opens and never shows black — not on open, not while the fragment or a setting
 * rebuilds the player, not after returning to the app.
 */
@Composable
private fun VideoPage(
    spec: LoopSpec?,
    still: Any?,
    loop: File?,
    state: MainUiState,
    speed: Float,
    focus: Focus,
    onFocus: ((Focus) -> Unit)?,
) {
    var picture by remember(spec != null) { mutableStateOf(false) }
    // Until the video has a picture, background thumbnail cutting steps aside for it.
    val context = LocalContext.current
    val motionThumbs = remember(context) { (context.applicationContext as WallperApplication).container.motionThumbs }
    val starting = spec != null && !picture
    LaunchedEffect(starting) {
        if (!starting) return@LaunchedEffect
        motionThumbs.hold()
        try {
            delay(START_HOLD_MS)
        } finally {
            motionThumbs.release()
        }
    }
    val covered = spec == null || !picture
    val coverAlpha by animateFloatAsState(
        targetValue = if (covered) 1f else 0f,
        animationSpec = if (covered) snap() else tween(COVER_FADE_MS),
        label = "coverAlpha",
    )
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusDrag(focus, onFocus)
            .testTag(TestTags.VIDEO),
    ) {
        // The 9:16 loop only matches portrait screens; wide ones keep the still.
        val portrait = maxWidth < maxHeight * PORTRAIT_LIMIT
        if (spec != null) {
            LoopVideo(
                spec = spec,
                seamless = state.settings.seamlessLoop,
                speed = speed,
                focus = focus,
                frostReveal = false,
                onPicture = { picture = it },
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (coverAlpha > 0f) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = coverAlpha }) {
                if (still != null) Still(still, focus)
                if (loop != null && portrait) {
                    InlineVideo(LoopSpec(Uri.fromFile(loop), loopMs = 0, fadeMs = 0))
                }
                if (spec != null && loop == null) {
                    LoadingIndicator(Modifier.align(Alignment.Center), color = Color.White)
                }
            }
        }
    }
}

/** A still frame; Apple's small stills are softened instead of showing their pixels. */
@Composable
private fun Still(model: Any, focus: Focus) {
    AsyncImage(
        model = model,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        alignment = BiasAlignment(focus.x * 2f - 1f, focus.y * 2f - 1f),
        modifier = Modifier
            .fillMaxSize()
            .then(if (model is String) Modifier.blur(16.dp) else Modifier),
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
    pagerState: PagerState? = null,
    pageCount: Int = 1,
    pager: @Composable () -> Unit,
    dock: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    Box(modifier.fillMaxSize().background(Color.Black).testTag(TestTags.VIEWER)) {
        pager()

        // Framing mode is unmistakable: the screen gets a frame, dragging moves the picture.
        AnimatedVisibility(framing, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.matchParentSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(8.dp)
                    .border(2.dp, Color.White.copy(alpha = 0.9f), RoundedCornerShape(28.dp)),
            )
        }

        // Visible paging affordances: arrows on the sides and a one-time swipe hint.
        if (pagerState != null && pageCount > 1 && !framing) {
            val page = pagerState.currentPage
            if (page > 0) {
                RoundGlassButton(
                    onClick = { scope.launch { pagerState.animateScrollToPage(page - 1) } },
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 10.dp).testTag(TestTags.PREVIOUS),
                ) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.action_previous), tint = Color.White)
                }
            }
            if (page < pageCount - 1) {
                RoundGlassButton(
                    onClick = { scope.launch { pagerState.animateScrollToPage(page + 1) } },
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 10.dp).testTag(TestTags.NEXT),
                ) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.action_next), tint = Color.White)
                }
            }
            SwipeHint(Modifier.align(Alignment.Center))
        }

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
                RoundGlassButton(onClick = onBack, modifier = Modifier.testTag(TestTags.BACK)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), tint = Color.White)
                }
            } else {
                Spacer(Modifier.size(TOUCH_TARGET))
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
                RoundGlassButton(onClick = onDelete, modifier = Modifier.testTag(TestTags.DELETE)) {
                    Icon(Icons.Filled.Delete, stringResource(R.string.action_delete), tint = Color.White)
                }
            } else {
                Spacer(Modifier.size(TOUCH_TARGET))
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
                val counter = pagerState?.takeIf { pageCount > 1 }?.let { "${it.currentPage + 1} / $pageCount" }
                val caption = listOfNotNull(subtitle?.uppercase(), counter).joinToString("  ·  ")
                if (caption.isNotEmpty()) {
                    Text(caption, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.75f))
                }
                Text(
                    title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                    modifier = Modifier.testTag(TestTags.TITLE),
                )
            }
            dock()
        }
    }
}

/** Expressive tonal icon button (its corners morph when pressed), tinted for video. */
@Composable
private fun RoundGlassButton(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    FilledTonalIconButton(
        onClick = onClick,
        shapes = IconButtonDefaults.shapes(),
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = Color.Black.copy(alpha = 0.42f),
            contentColor = Color.White,
        ),
        // 48 dp: the minimum touch target of Material and Android accessibility.
        modifier = modifier.size(TOUCH_TARGET),
        content = content,
    )
}

/** Shown once per app session, then fades away. */
@Composable
private fun SwipeHint(modifier: Modifier) {
    var visible by remember { mutableStateOf(!swipeHintShown) }
    LaunchedEffect(Unit) {
        swipeHintShown = true
        delay(2_500)
        visible = false
    }
    AnimatedVisibility(visible, modifier = modifier, enter = fadeIn(), exit = fadeOut()) {
        GlassSurface(RoundedCornerShape(50)) {
            Text(
                stringResource(R.string.hint_swipe),
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

private var swipeHintShown = false

// endregion

private const val COVER_FADE_MS = 320
private val TOUCH_TARGET = 48.dp
private const val START_HOLD_MS = 8_000L
private const val SAVE_EDIT_MS = 300L
private const val PORTRAIT_LIMIT = 0.8f
