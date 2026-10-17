package com.wallper.live.ui.editor

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wallper.live.R
import com.wallper.live.core.Playback
import com.wallper.live.core.WallpaperTarget
import com.wallper.live.core.formatDuration
import com.wallper.live.core.formatTimestamp
import com.wallper.live.ui.components.GlassSurface
import com.wallper.live.ui.components.Haptics
import com.wallper.live.ui.components.pressScale

/** A ≤ 30 s window inside a longer video. */
class FragmentModel(
    val totalMs: Long,
    val startMs: Long,
    val loopMs: Long,
    val onChange: (startMs: Long, loopMs: Long) -> Unit,
)

/** What the install split button shows. */
data class InstallModel(
    val progress: Float? = null,
    val done: Boolean = false,
    val lockChoice: Boolean = true,
)

private enum class DockPanel { Speed, Fragment }

/**
 * Floating dock (Material 3 Expressive floating toolbar): small chips that open a
 * compact panel above it, and a split button. It occupies one 52 dp row, so the video
 * stays the hero; sliders only appear while being used.
 */
@Composable
fun Dock(
    speed: Float,
    onSpeed: (Float) -> Unit,
    onSpeedDone: () -> Unit,
    fragment: FragmentModel?,
    /** Shows the fragment chip from the first frame (disabled until the length is known). */
    hasFragment: Boolean,
    framing: Boolean,
    onFraming: () -> Unit,
    install: InstallModel,
    onInstall: (WallpaperTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    var panel by rememberSaveable { mutableStateOf<DockPanel?>(null) }
    Column(modifier.widthIn(max = 460.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        AnimatedVisibility(
            visible = panel != null,
            enter = fadeIn() + scaleIn(spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium), 0.92f),
            exit = fadeOut() + scaleOut(targetScale = 0.96f),
        ) {
            GlassSurface(RoundedCornerShape(26.dp), Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                AnimatedContent(panel, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "dockPanel") {
                    when (it) {
                        DockPanel.Speed -> SpeedPanel(speed, onSpeed, onSpeedDone)
                        DockPanel.Fragment -> fragment?.let { model -> FragmentPanel(model) }
                        null -> Unit
                    }
                }
            }
        }
        GlassSurface(RoundedCornerShape(50)) {
            Row(
                Modifier.padding(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                DockChip(formatSpeed(speed), panel == DockPanel.Speed) {
                    panel = if (panel == DockPanel.Speed) null else DockPanel.Speed
                }
                if (hasFragment) {
                    DockChip(
                        label = formatDuration(fragment?.loopMs ?: Playback.MAX_LOOP_MS),
                        selected = panel == DockPanel.Fragment,
                        enabled = fragment != null,
                    ) {
                        panel = if (panel == DockPanel.Fragment) null else DockPanel.Fragment
                    }
                }
                DockChip(stringResource(if (framing) R.string.dock_frame_done else R.string.dock_frame), framing) {
                    panel = null
                    onFraming()
                }
                Spacer(Modifier.width(4.dp))
                InstallSplitButton(install, onInstall)
            }
        }
    }
}

@Composable
private fun DockChip(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val container by animateColorAsState(
        if (selected) Color.White.copy(alpha = 0.22f) else Color.Transparent,
        label = "dockChip",
    )
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .pressScale(interaction)
            .height(40.dp)
            .clip(RoundedCornerShape(50))
            .background(container)
            .clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = if (enabled) 1f else 0.45f))
    }
}

/** Split button: the main part sets the home screen, the arrow offers lock-screen options. */
@Composable
private fun InstallSplitButton(model: InstallModel, onInstall: (WallpaperTarget) -> Unit) {
    val view = LocalView.current
    var menu by remember { mutableStateOf(false) }
    val busy = model.progress != null
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        SplitPart(
            shape = if (model.lockChoice) LeadingShape else RoundedCornerShape(50),
            enabled = !busy,
            onClick = {
                Haptics.confirm(view)
                onInstall(WallpaperTarget.Home)
            },
        ) {
            when {
                busy -> {
                    CircularProgressIndicator(
                        progress = { model.progress!!.coerceIn(0.02f, 1f) },
                        modifier = Modifier.size(16.dp),
                        color = Color.Black,
                        trackColor = Color.Black.copy(alpha = 0.15f),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("${(model.progress!! * 100).toInt()}%", fontWeight = FontWeight.SemiBold, color = Color.Black)
                }
                model.done -> {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = Color.Black, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.action_applied), style = MaterialTheme.typography.labelLarge, color = Color.Black)
                }
                else -> Text(stringResource(R.string.action_install), style = MaterialTheme.typography.labelLarge, color = Color.Black)
            }
        }
        if (model.lockChoice) {
            Box {
                SplitPart(shape = TrailingShape, enabled = !busy, horizontalPadding = 8.dp, onClick = { menu = true }) {
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = stringResource(R.string.install_more), tint = Color.Black)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.install_lock)) },
                        leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
                        onClick = {
                            menu = false
                            onInstall(WallpaperTarget.Lock)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.install_both)) },
                        leadingIcon = { Icon(Icons.Filled.Home, contentDescription = null) },
                        onClick = {
                            menu = false
                            onInstall(WallpaperTarget.Both)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SplitPart(
    shape: Shape,
    enabled: Boolean,
    onClick: () -> Unit,
    horizontalPadding: androidx.compose.ui.unit.Dp = 18.dp,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .pressScale(interaction)
            .height(40.dp)
            .clip(shape)
            .background(Color.White.copy(alpha = if (enabled) 1f else 0.85f))
            .clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = horizontalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
private fun SpeedPanel(speed: Float, onSpeed: (Float) -> Unit, onDone: () -> Unit) {
    val view = LocalView.current
    PanelRow(stringResource(R.string.editor_speed), formatSpeed(speed)) {
        Slider(
            value = speed,
            onValueChange = {
                val snapped = (Math.round(it * 4) / 4f).coerceIn(Playback.MIN_SPEED, Playback.MAX_SPEED)
                if (snapped != speed) Haptics.tick(view)
                onSpeed(snapped)
            },
            onValueChangeFinished = onDone,
            valueRange = Playback.MIN_SPEED..Playback.MAX_SPEED,
            steps = 6,
            colors = sliderColors(),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FragmentPanel(model: FragmentModel) {
    val view = LocalView.current
    val usable = (model.totalMs - Playback.FADE_MS).coerceAtLeast(Playback.MIN_LOOP_MS)
    PanelRow(
        stringResource(R.string.editor_fragment),
        "${formatTimestamp(model.startMs)} – ${formatTimestamp(model.startMs + model.loopMs)}",
    ) {
        RangeSlider(
            value = model.startMs.toFloat()..(model.startMs + model.loopMs).toFloat(),
            onValueChange = { range ->
                var start = range.start.toLong()
                var end = range.endInclusive.toLong()
                val movedStart = start != model.startMs
                if (end - start > Playback.MAX_LOOP_MS) {
                    if (movedStart) end = start + Playback.MAX_LOOP_MS else start = end - Playback.MAX_LOOP_MS
                }
                if (end - start < Playback.MIN_LOOP_MS) {
                    if (movedStart) start = end - Playback.MIN_LOOP_MS else end = start + Playback.MIN_LOOP_MS
                }
                start = start.coerceIn(0, usable - Playback.MIN_LOOP_MS)
                end = end.coerceIn(start + Playback.MIN_LOOP_MS, usable)
                if ((end - start) / 1000 != model.loopMs / 1000) Haptics.tick(view)
                model.onChange(start, end - start)
            },
            valueRange = 0f..usable.toFloat(),
            colors = sliderColors(),
        )
    }
}

@Composable
private fun PanelRow(label: String, value: String, slider: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = 18.dp, vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = Color.White, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.75f))
        }
        slider()
    }
}

@Composable
private fun sliderColors() = SliderDefaults.colors(
    thumbColor = Color.White,
    activeTrackColor = Color.White,
    inactiveTrackColor = Color.White.copy(alpha = 0.22f),
    activeTickColor = Color.Black.copy(alpha = 0.35f),
    inactiveTickColor = Color.White.copy(alpha = 0.35f),
)

internal fun formatSpeed(speed: Float): String {
    val text = if (speed % 1f == 0f) speed.toInt().toString() else speed.toString().trimEnd('0')
    return "$text×"
}

private val LeadingShape = RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp, topEnd = 6.dp, bottomEnd = 6.dp)
private val TrailingShape = RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp, topEnd = 20.dp, bottomEnd = 20.dp)
