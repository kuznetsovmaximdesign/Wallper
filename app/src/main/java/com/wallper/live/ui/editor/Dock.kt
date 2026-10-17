package com.wallper.live.ui.editor

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wallper.live.R
import com.wallper.live.core.Playback
import com.wallper.live.core.WallpaperTarget
import com.wallper.live.core.formatDuration
import com.wallper.live.core.formatTimestamp
import com.wallper.live.ui.components.GlassSurface
import com.wallper.live.ui.components.Haptics
import com.wallper.live.ui.components.TestTags

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
 * Floating dock: a Material 3 Expressive floating toolbar with toggle buttons (their
 * corners morph when checked) that open a compact panel above it, and a split button.
 * It occupies one row, so the video stays the hero; sliders only appear while used.
 */
@Composable
fun Dock(
    speed: Float,
    onSpeed: (Float) -> Unit,
    onSpeedDone: () -> Unit,
    fragment: FragmentModel?,
    /** Shows the fragment toggle from the first frame (disabled until the length is known). */
    hasFragment: Boolean,
    framing: Boolean,
    onFraming: () -> Unit,
    install: InstallModel,
    onInstall: (WallpaperTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    var panel by rememberSaveable { mutableStateOf<DockPanel?>(null) }
    Column(modifier.widthIn(max = 520.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        AnimatedVisibility(
            visible = panel != null,
            enter = fadeIn() + scaleIn(spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium), 0.92f),
            exit = fadeOut() + scaleOut(targetScale = 0.96f),
        ) {
            GlassSurface(RoundedCornerShape(28.dp), Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                AnimatedContent(panel, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "dockPanel") {
                    when (it) {
                        DockPanel.Speed -> SpeedPanel(speed, onSpeed, onSpeedDone)
                        DockPanel.Fragment -> fragment?.let { model -> FragmentPanel(model) }
                        null -> Unit
                    }
                }
            }
        }
        HorizontalFloatingToolbar(
            expanded = true,
            colors = FloatingToolbarDefaults.standardFloatingToolbarColors(
                toolbarContainerColor = DockContainer,
                toolbarContentColor = Color.White,
            ),
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
        ) {
            DockToggle(formatSpeed(speed), panel == DockPanel.Speed, TestTags.DOCK_SPEED) {
                panel = if (panel == DockPanel.Speed) null else DockPanel.Speed
            }
            if (hasFragment) {
                DockToggle(
                    label = formatDuration(fragment?.loopMs ?: Playback.MAX_LOOP_MS),
                    checked = panel == DockPanel.Fragment,
                    tag = TestTags.DOCK_FRAGMENT,
                    enabled = fragment != null,
                ) {
                    panel = if (panel == DockPanel.Fragment) null else DockPanel.Fragment
                }
            }
            DockToggle(
                label = stringResource(if (framing) R.string.dock_frame_done else R.string.dock_frame),
                checked = framing,
                tag = TestTags.DOCK_FRAME,
            ) {
                panel = null
                onFraming()
            }
            Spacer(Modifier.width(4.dp))
            InstallSplitButton(install, onInstall)
        }
    }
}

@Composable
private fun DockToggle(label: String, checked: Boolean, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    ToggleButton(
        checked = checked,
        onCheckedChange = { onClick() },
        enabled = enabled,
        colors = ToggleButtonDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Color.White,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = Color.White.copy(alpha = 0.4f),
            checkedContainerColor = Color.White.copy(alpha = 0.92f),
            checkedContentColor = Color.Black,
        ),
        elevation = null,
        contentPadding = PaddingValues(horizontal = 11.dp),
        modifier = Modifier.heightIn(min = 40.dp).testTag(tag),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/** Split button: the main part sets the home screen, the arrow offers lock-screen options. */
@Composable
private fun InstallSplitButton(model: InstallModel, onInstall: (WallpaperTarget) -> Unit) {
    val view = LocalView.current
    var menu by remember { mutableStateOf(false) }
    val busy = model.progress != null
    val colors = ButtonDefaults.buttonColors(
        containerColor = Color.White,
        contentColor = Color.Black,
        disabledContainerColor = Color.White.copy(alpha = 0.85f),
        disabledContentColor = Color.Black,
    )
    val label: @Composable RowScope.() -> Unit = {
        when {
            busy -> {
                CircularWavyProgressIndicator(
                    progress = { model.progress!!.coerceIn(0.02f, 1f) },
                    modifier = Modifier.size(18.dp),
                    color = Color.Black,
                    trackColor = Color.Black.copy(alpha = 0.15f),
                )
                Spacer(Modifier.width(8.dp))
                Text("${(model.progress!! * 100).toInt()}%", fontWeight = FontWeight.SemiBold)
            }
            model.done -> {
                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.action_applied), style = MaterialTheme.typography.labelLarge)
            }
            else -> Text(stringResource(R.string.action_install), style = MaterialTheme.typography.labelLarge)
        }
    }
    val install = {
        Haptics.confirm(view)
        onInstall(WallpaperTarget.Home)
    }
    if (!model.lockChoice) {
        Button(onClick = install, enabled = !busy, colors = colors, modifier = Modifier.testTag(TestTags.INSTALL), content = label)
        return
    }
    SplitButtonLayout(
        leadingButton = {
            SplitButtonDefaults.LeadingButton(
                onClick = install,
                enabled = !busy,
                colors = colors,
                modifier = Modifier.testTag(TestTags.INSTALL),
                content = label,
            )
        },
        trailingButton = {
            Box {
                val rotation by animateFloatAsState(if (menu) 180f else 0f, label = "installArrow")
                SplitButtonDefaults.TrailingButton(
                    checked = menu,
                    onCheckedChange = { menu = it },
                    enabled = !busy,
                    colors = colors,
                    modifier = Modifier.testTag(TestTags.INSTALL_MORE),
                ) {
                    Icon(
                        Icons.Filled.ArrowDropDown,
                        contentDescription = stringResource(R.string.install_more),
                        modifier = Modifier.graphicsLayer { rotationZ = rotation },
                    )
                }
                DropdownMenu(
                    expanded = menu,
                    onDismissRequest = { menu = false },
                    modifier = Modifier.semantics { testTagsAsResourceId = true }, // a popup window
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.install_lock)) },
                        leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
                        modifier = Modifier.testTag(TestTags.INSTALL_LOCK),
                        onClick = {
                            menu = false
                            onInstall(WallpaperTarget.Lock)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.install_both)) },
                        leadingIcon = { Icon(Icons.Filled.Home, contentDescription = null) },
                        modifier = Modifier.testTag(TestTags.INSTALL_BOTH),
                        onClick = {
                            menu = false
                            onInstall(WallpaperTarget.Both)
                        },
                    )
                }
            }
        },
    )
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
            modifier = Modifier.testTag(TestTags.SPEED_SLIDER),
        )
    }
}

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
            modifier = Modifier.testTag(TestTags.FRAGMENT_SLIDER),
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

private val DockContainer = Color(0xFF15151C).copy(alpha = 0.9f)
