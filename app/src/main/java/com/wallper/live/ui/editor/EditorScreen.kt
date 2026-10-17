package com.wallper.live.ui.editor

import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import coil3.compose.AsyncImage
import com.wallper.live.R
import com.wallper.live.WallperApplication
import com.wallper.live.core.Clip
import com.wallper.live.core.Focus
import com.wallper.live.core.Footage
import com.wallper.live.core.Playback
import com.wallper.live.core.WallpaperTarget
import com.wallper.live.core.formatDuration
import com.wallper.live.core.formatTimestamp
import com.wallper.live.media.ClipSource
import com.wallper.live.playback.LoopSession
import com.wallper.live.ui.EditorTarget
import com.wallper.live.ui.MainUiState
import com.wallper.live.ui.components.GlassSurface
import com.wallper.live.ui.components.Haptics
import com.wallper.live.ui.components.LoopVideo
import kotlinx.coroutines.delay

/** Callbacks of the editor, grouped to keep call sites readable. */
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

@Composable
fun EditorScreen(target: EditorTarget, state: MainUiState, actions: EditorActions, modifier: Modifier = Modifier) {
    when (target) {
        is EditorTarget.FromFootage -> SourceEditor(
            key = target.footage.id,
            title = target.footage.title,
            previewSource = target.footage.previewUrl?.let { ClipSource.Remote(it) },
            probeSource = ClipSource.Remote(target.footage.previewUrl ?: target.footage.masterUrl),
            still = target.footage.coverUrl,
            state = state,
            progress = state.runningExport(target.footage.id)?.progress,
            primaryLabel = stringResource(R.string.action_create),
            actions = actions,
            modifier = modifier,
            strip = {
                FootageStrip(
                    items = state.visibleFootage,
                    selectedId = target.footage.id,
                    onSelect = { actions.onOpen(EditorTarget.FromFootage(it)) },
                )
            },
            onCreate = { start, loop, speed, focus, where ->
                actions.onCreateFromFootage(target.footage, start, loop, speed, focus, where)
            },
        )
        is EditorTarget.FromDevice -> SourceEditor(
            key = target.uri.toString(),
            title = null,
            previewSource = ClipSource.Local(target.uri),
            probeSource = ClipSource.Local(target.uri),
            still = null,
            state = state,
            progress = null,
            primaryLabel = stringResource(R.string.action_create_local),
            actions = actions,
            modifier = modifier,
            strip = null,
            onCreate = { start, loop, speed, focus, where ->
                actions.onCreateFromDevice(target.uri, start, loop, speed, focus, where)
            },
        )
        is EditorTarget.FromClip -> {
            val clip = state.clips.firstOrNull { it.id == target.clipId } ?: return
            ClipEditor(clip, state, actions, modifier)
        }
    }
}

/** Editing a remote (Apple) or device video: choose fragment, speed and framing. */
@Composable
private fun SourceEditor(
    key: String,
    title: String?,
    previewSource: ClipSource?,
    probeSource: ClipSource,
    still: String?,
    state: MainUiState,
    progress: Float?,
    primaryLabel: String,
    actions: EditorActions,
    modifier: Modifier,
    strip: (@Composable () -> Unit)?,
    onCreate: (startMs: Long, loopMs: Long, speed: Float, focus: Focus, target: WallpaperTarget) -> Unit,
) {
    var askTarget by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val probe = remember(context) { (context.applicationContext as WallperApplication).container.probe }
    var duration by remember(key) { mutableStateOf<Long?>(null) }
    LaunchedEffect(key) { duration = probe.durationMs(probeSource) }

    var startMs by rememberSaveable(key) { mutableLongStateOf(-1L) }
    var loopMs by rememberSaveable(key) { mutableLongStateOf(Playback.MAX_LOOP_MS) }
    var speed by rememberSaveable(key) { mutableFloatStateOf(Playback.DEFAULT_SPEED) }
    var focus by remember(key) { mutableStateOf(Focus.Center) }

    // Default fragment: 20% in, past Apple's fade-in, 30 s long.
    LaunchedEffect(duration) {
        val total = duration ?: return@LaunchedEffect
        if (startMs < 0) {
            startMs = (total * 0.2).toLong().coerceAtMost((total - Playback.MIN_LOOP_MS).coerceAtLeast(0))
            loopMs = minOf(Playback.MAX_LOOP_MS, total - startMs - Playback.FADE_MS).coerceAtLeast(Playback.MIN_LOOP_MS)
        }
    }

    fun requestCreate() {
        if (state.separateLock) {
            askTarget = true
        } else {
            onCreate(startMs, loopMs, speed, focus, WallpaperTarget.Both)
        }
    }

    // The preview is rebuilt only when the fragment is committed (not on every drag frame).
    var committed by remember(key) { mutableStateOf<Pair<Long, Long>?>(null) }
    LaunchedEffect(startMs, loopMs) {
        if (startMs < 0) return@LaunchedEffect
        delay(250)
        committed = startMs to loopMs
    }
    val spec = remember(committed, previewSource) {
        if (previewSource == null) return@remember null
        committed?.let { (start, loop) ->
            val uri = when (previewSource) {
                is ClipSource.Remote -> Uri.parse(previewSource.url)
                is ClipSource.Local -> previewSource.uri
            }
            LoopSession.LoopSpec(
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

    EditorScaffold(
        spec = spec,
        state = state,
        speed = speed,
        focus = focus,
        onFocus = { focus = it },
        onBack = actions.onBack,
        modifier = modifier,
        still = if (previewSource == null) still else null,
    ) {
        strip?.invoke()
        PanelTitle(title ?: stringResource(R.string.tab_mine), subtitle = duration?.let { "4K · ${formatTimestamp(it)}" })
        val total = duration
        if (total != null && startMs >= 0) {
            FragmentSelector(totalMs = total, startMs = startMs, loopMs = loopMs) { s, l ->
                startMs = s
                loopMs = l
            }
        }
        SpeedSelector(speed = speed, loopMs = loopMs, onSpeed = { speed = it })
        PrimaryButton(
            label = primaryLabel,
            progress = progress,
            enabled = progress == null && startMs >= 0,
            onClick = ::requestCreate,
        )
    }

    if (askTarget) {
        TargetSheet(
            onPick = {
                askTarget = false
                onCreate(startMs, loopMs, speed, focus, it)
            },
            onDismiss = { askTarget = false },
        )
    }
}

/** A clip already in the library: tune speed/framing, set it, delete it. */
@Composable
private fun ClipEditor(clip: Clip, state: MainUiState, actions: EditorActions, modifier: Modifier) {
    val context = LocalContext.current
    val library = remember(context) { (context.applicationContext as WallperApplication).container.library }
    var speed by remember(clip.id) { mutableFloatStateOf(state.settings.speedOf(clip.id)) }
    var focus by remember(clip.id) { mutableStateOf(state.settings.focusOf(clip.id)) }
    var askTarget by remember { mutableStateOf(false) }
    val spec = remember(clip.id) {
        LoopSession.LoopSpec(
            mediaItem = MediaItem.fromUri(Uri.fromFile(library.fileOf(clip))),
            loopMs = clip.loopMs,
            fadeMs = clip.fadeMs,
        )
    }

    fun requestApply() {
        if (state.separateLock) {
            askTarget = true
        } else {
            actions.onApply(clip, WallpaperTarget.Both)
        }
    }

    // Persist framing shortly after the user stops dragging.
    LaunchedEffect(focus) {
        delay(400)
        if (focus != state.settings.focusOf(clip.id)) actions.onSaveFocus(clip.id, focus)
    }

    EditorScaffold(
        spec = spec,
        state = state,
        speed = speed,
        focus = focus,
        onFocus = { focus = it },
        onBack = actions.onBack,
        modifier = modifier,
    ) {
        if (state.clips.size > 1) {
            ClipStrip(
                clips = state.clips,
                selectedId = clip.id,
                onSelect = { actions.onOpen(EditorTarget.FromClip(it.id)) },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                PanelTitle(clip.title, subtitle = (if (clip.isUhd) "4K · " else "") + formatDuration(clip.loopMs))
            }
            IconButton(onClick = { actions.onDelete(clip) }) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_delete), tint = Color.White)
            }
        }
        SpeedSelector(
            speed = speed,
            loopMs = clip.loopMs,
            onSpeed = { speed = it },
            onSpeedFinished = { actions.onSaveSpeed(clip.id, speed) },
        )
        // Fully applied only when it is on every screen it can be on.
        val roles = state.rolesOf(clip)
        val everywhere = roles.size == (if (state.separateLock) 2 else 1)
        PrimaryButton(
            label = stringResource(if (everywhere) R.string.action_applied else R.string.action_set),
            progress = null,
            enabled = !everywhere,
            done = everywhere,
            onClick = ::requestApply,
        )
    }

    if (askTarget) {
        TargetSheet(
            onPick = {
                askTarget = false
                actions.onApply(clip, it)
            },
            onDismiss = { askTarget = false },
        )
    }
}

@Composable
private fun EditorScaffold(
    spec: LoopSession.LoopSpec?,
    state: MainUiState,
    speed: Float,
    focus: Focus,
    onFocus: (Focus) -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier,
    still: String? = null,
    panel: @Composable () -> Unit,
) {
    var chrome by rememberSaveable { mutableStateOf(ChromeMode.Lock) }
    var expanded by rememberSaveable { mutableStateOf(true) }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        LoopVideo(
            spec = spec,
            seamless = state.settings.seamlessLoop,
            speed = speed,
            focus = focus,
            frostReveal = state.settings.frostReveal,
            // Switching the mock from lock to home plays the reveal, like unlocking.
            revealKey = spec?.mediaItem?.mediaId to chrome,
            onFocusChange = onFocus,
            modifier = Modifier.fillMaxSize(),
        )
        if (still != null) StillPreview(still, focus)
        AnimatedContent(
            targetState = chrome,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "chrome",
        ) { mode -> DeviceChrome(mode) }

        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                GlassSurface(CircleShape, Modifier.size(48.dp)) {
                    IconButton(onClick = onBack, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                            tint = Color.White,
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
            }
            Spacer(Modifier.weight(1f))
            ChromeSwitcher(chrome) { chrome = it }
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(12.dp)
                .widthIn(max = 560.dp)
                .fillMaxWidth(),
        ) {
            GlassSurface(RoundedCornerShape(32.dp), Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clickable { expanded = !expanded },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (expanded) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.7f),
                        )
                    }
                    AnimatedVisibility(expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) { panel() }
                    }
                    if (!expanded) {
                        Text(
                            stringResource(R.string.preview_hint_focus),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.padding(bottom = 6.dp).align(Alignment.CenterHorizontally),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChromeSwitcher(mode: ChromeMode, onMode: (ChromeMode) -> Unit) {
    val options = listOf(
        ChromeMode.Lock to R.string.preview_mode_lock,
        ChromeMode.Home to R.string.preview_mode_home,
        ChromeMode.Clean to R.string.preview_mode_clean,
    )
    GlassSurface(RoundedCornerShape(50)) {
        Row(Modifier.padding(4.dp)) {
            options.forEach { (value, label) ->
                val selected = value == mode
                Box(
                    Modifier
                        .background(if (selected) Color.White else Color.Transparent, RoundedCornerShape(50))
                        .clickable { onMode(value) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text(
                        stringResource(label),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected) Color.Black else Color.White,
                    )
                }
            }
        }
    }
}

@Composable
private fun PanelTitle(title: String, subtitle: String?) {
    Column {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = Color.White)
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.7f))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FragmentSelector(totalMs: Long, startMs: Long, loopMs: Long, onChange: (Long, Long) -> Unit) {
    val view = LocalView.current
    val usable = (totalMs - Playback.FADE_MS).coerceAtLeast(Playback.MIN_LOOP_MS)
    Column {
        LabelRow(
            label = stringResource(R.string.editor_fragment),
            value = "${formatTimestamp(startMs)} – ${formatTimestamp(startMs + loopMs)} · ${formatDuration(loopMs)}",
        )
        RangeSlider(
            value = startMs.toFloat()..(startMs + loopMs).toFloat(),
            onValueChange = { range ->
                var start = range.start.toLong()
                var end = range.endInclusive.toLong()
                val movedStart = start != startMs
                if (end - start > Playback.MAX_LOOP_MS) {
                    if (movedStart) end = start + Playback.MAX_LOOP_MS else start = end - Playback.MAX_LOOP_MS
                }
                if (end - start < Playback.MIN_LOOP_MS) {
                    if (movedStart) start = end - Playback.MIN_LOOP_MS else end = start + Playback.MIN_LOOP_MS
                }
                start = start.coerceIn(0, usable - Playback.MIN_LOOP_MS)
                end = end.coerceIn(start + Playback.MIN_LOOP_MS, usable)
                if ((end - start) / 1000 != loopMs / 1000) Haptics.tick(view)
                onChange(start, end - start)
            },
            valueRange = 0f..usable.toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color.White.copy(alpha = 0.22f),
            ),
        )
    }
}

@Composable
private fun SpeedSelector(speed: Float, loopMs: Long, onSpeed: (Float) -> Unit, onSpeedFinished: () -> Unit = {}) {
    val view = LocalView.current
    Column {
        LabelRow(
            label = stringResource(R.string.editor_speed),
            value = "${formatSpeed(speed)} · " + stringResource(
                R.string.editor_loop_length,
                formatDuration((loopMs / speed).toLong()),
            ),
        )
        Slider(
            value = speed,
            onValueChange = {
                val snapped = (Math.round(it * 4) / 4f).coerceIn(Playback.MIN_SPEED, Playback.MAX_SPEED)
                if (snapped != speed) Haptics.tick(view)
                onSpeed(snapped)
            },
            onValueChangeFinished = onSpeedFinished,
            valueRange = Playback.MIN_SPEED..Playback.MAX_SPEED,
            steps = 6,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color.White.copy(alpha = 0.22f),
                activeTickColor = Color.Black.copy(alpha = 0.4f),
                inactiveTickColor = Color.White.copy(alpha = 0.4f),
            ),
        )
    }
}

@Composable
private fun LabelRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = Color.White, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.75f))
    }
}

@Composable
private fun PrimaryButton(
    label: String,
    progress: Float?,
    enabled: Boolean,
    onClick: () -> Unit,
    done: Boolean = false,
) {
    val view = LocalView.current
    Button(
        onClick = {
            Haptics.confirm(view)
            onClick()
        },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.White,
            contentColor = Color.Black,
            disabledContainerColor = Color.White.copy(alpha = 0.18f),
            disabledContentColor = Color.White,
        ),
    ) {
        when {
            progress != null -> {
                CircularProgressIndicator(
                    progress = { progress.coerceIn(0.02f, 1f) },
                    modifier = Modifier.size(20.dp),
                    color = Color.White,
                    trackColor = Color.White.copy(alpha = 0.25f),
                    strokeWidth = 2.5.dp,
                )
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.export_progress, (progress * 100).toInt()), fontWeight = FontWeight.SemiBold)
            }
            done -> {
                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(label)
            }
            else -> Text(label)
        }
    }
}

/**
 * 240 fps masters are too heavy to stream for a preview, so these scenes show Apple's
 * still with a slow Ken Burns drift until the 4K clip is prepared.
 */
@Composable
private fun StillPreview(url: String, focus: Focus) {
    val transition = rememberInfiniteTransition(label = "kenBurns")
    val scale by transition.animateFloat(
        initialValue = 1.04f,
        targetValue = 1.14f,
        animationSpec = infiniteRepeatable(tween(14_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "kenBurnsScale",
    )
    Box(Modifier.fillMaxSize()) {
        AsyncImage(
            model = url,
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
        GlassSurface(
            RoundedCornerShape(50),
            Modifier.align(Alignment.Center),
        ) {
            Text(
                stringResource(R.string.preview_hfr_hint),
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

private fun formatSpeed(speed: Float): String {
    val text = if (speed % 1f == 0f) speed.toInt().toString() else speed.toString().trimEnd('0')
    return "$text×"
}
