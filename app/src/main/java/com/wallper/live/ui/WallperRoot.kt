package com.wallper.live.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wallper.live.R
import com.wallper.live.core.Foldables
import com.wallper.live.core.PanelKind
import com.wallper.live.ui.components.Haptics
import com.wallper.live.ui.editor.EditorActions
import com.wallper.live.ui.editor.EditorScreen
import com.wallper.live.ui.home.HomeScreen
import com.wallper.live.ui.settings.SettingsSheet
import com.wallper.live.wallpaper.LiveWallpaper
import kotlinx.coroutines.launch

/**
 * Adaptive shell. Compact widths (phones, foldable cover screens) navigate between the
 * gallery and a full-screen editor; expanded widths (foldable inner screens, tablets)
 * show both side by side, list-detail style.
 */
@Composable
fun WallperRoot(vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val view = LocalView.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showSettings by rememberSaveable { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        vm.refreshLiveWallpaper()
        onPauseOrDispose { }
    }

    // Galaxy Fold & co. keep a separate home wallpaper per display, and the system picker
    // only sets the display it is opened on. After a successful set, the first switch to
    // the other display opens the picker there too, so both screens end up with it.
    val foldable = remember(context) { Foldables.isFoldable(context) }
    val configuration = LocalConfiguration.current
    val panel = Foldables.classify(configuration.screenWidthDp, configuration.screenHeightDp, foldable)
    var pendingFold by remember { mutableStateOf<Pair<PanelKind, Long>?>(null) }
    var foldFollowUp by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val applied = vm.onPickerClosed()
        when {
            foldFollowUp -> foldFollowUp = false // the second display is done, nothing left to chase
            applied && foldable -> pendingFold = panel to System.currentTimeMillis()
        }
    }
    fun openPicker() {
        try {
            picker.launch(LiveWallpaper.pickerIntent(context))
        } catch (_: ActivityNotFoundException) {
            picker.launch(LiveWallpaper.chooserIntent())
        }
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            vm.open(EditorTarget.FromDevice(uri))
        }
    }

    LaunchedEffect(panel) {
        val pending = pendingFold ?: return@LaunchedEffect
        if (pending.first == panel) return@LaunchedEffect
        pendingFold = null
        if (System.currentTimeMillis() - pending.second < FOLD_FOLLOW_UP_MS) {
            foldFollowUp = true
            openPicker()
            snackbar.showSnackbar(context.getString(R.string.fold_other_screen), duration = SnackbarDuration.Short)
        }
    }

    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                UiEvent.OpenPicker -> openPicker()
                is UiEvent.Message -> scope.launch {
                    if (event.text == R.string.toast_applied || event.text == R.string.toast_updated) Haptics.confirm(view)
                    val result = snackbar.showSnackbar(
                        message = context.getString(event.text),
                        actionLabel = event.actionLabel?.let(context::getString),
                        duration = if (event.action != null) SnackbarDuration.Long else SnackbarDuration.Short,
                    )
                    val action = event.action
                    val accepted = result == SnackbarResult.ActionPerformed
                    if (action is UiAction.OpenPicker && accepted) openPicker()
                    if (action is UiAction.UndoDelete) {
                        if (accepted) vm.undoDelete(action.clipId) else vm.commitDelete(action.clipId)
                    }
                }
            }
        }
    }

    val actions = EditorActions(
        onBack = vm::closeEditor,
        onOpen = vm::open,
        onCreateFromFootage = { footage, start, loop, speed, focus, target ->
            vm.createFromFootage(footage, start, loop, speed, focus, target)
        },
        onCreateFromDevice = { uri, start, loop, speed, focus, target ->
            vm.createFromDevice(uri, context.getString(R.string.tab_mine), start, loop, speed, focus, target)
        },
        onApply = { clip, target -> vm.apply(clip, target) },
        onDelete = vm::requestDelete,
        onSaveSpeed = { id, speed -> vm.saveSpeed(id, speed) },
        onSaveFocus = { id, focus -> vm.saveFocus(id, focus) },
    )

    BackHandler(enabled = state.editor != null) { vm.closeEditor() }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val expanded = maxWidth >= 600.dp
            val home: @Composable (Modifier) -> Unit = { modifier ->
                HomeScreen(
                    autoplay = !(expanded && state.editor != null),
                    state = state,
                    onTab = vm::selectTab,
                    onCategory = vm::selectCategory,
                    onRetry = vm::retryCatalog,
                    onFootage = { vm.open(EditorTarget.FromFootage(it)) },
                    onClip = { vm.open(EditorTarget.FromClip(it.id)) },
                    onImport = {
                        importer.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                    },
                    onSettings = { showSettings = true },
                    selectedId = when (val e = state.editor) {
                        is EditorTarget.FromFootage -> e.footage.id
                        is EditorTarget.FromClip -> e.clipId
                        else -> null
                    },
                    modifier = modifier,
                )
            }

            if (expanded) {
                Row(Modifier.fillMaxSize()) {
                    home(Modifier.weight(1f))
                    Box(
                        Modifier
                            .weight(1.05f)
                            .fillMaxHeight()
                            .windowInsetsPadding(WindowInsets.safeDrawing)
                            .padding(top = 12.dp, end = 16.dp, bottom = 16.dp),
                    ) {
                        val target = state.editor
                        if (target == null) {
                            Box(
                                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    stringResource(R.string.select_placeholder),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            EditorScreen(target, state, actions.copy(onBack = null))
                        }
                        // A SurfaceView ignores clipping, so round the pane by painting its corners.
                        CornerMask(radius = 32.dp, color = MaterialTheme.colorScheme.background)
                    }
                }
            } else {
                AnimatedContent(
                    targetState = state.editor,
                    contentKey = { it?.javaClass },
                    transitionSpec = {
                        (fadeIn(spring(stiffness = Spring.StiffnessMediumLow)) +
                            scaleIn(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow), 0.94f))
                            .togetherWith(fadeOut(spring(stiffness = Spring.StiffnessMedium)) + scaleOut(targetScale = 1.02f))
                    },
                    label = "nav",
                ) { target ->
                    if (target == null) home(Modifier) else EditorScreen(target, state, actions)
                }
            }
        }

        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(bottom = 8.dp),
        )
    }

    if (showSettings) {
        SettingsSheet(
            settings = state.settings,
            storageBytes = remember(state.clips) { vm.storageBytes() },
            onSeamless = vm::setSeamless,
            onParallax = vm::setParallax,
            onPowerSave = vm::setPauseOnPowerSave,
            onFrost = vm::setFrostReveal,
            onDismiss = { showSettings = false },
        )
    }
}

@Composable
private fun CornerMask(radius: androidx.compose.ui.unit.Dp, color: Color) {
    Canvas(Modifier.fillMaxSize()) {
        val r = radius.toPx()
        val path = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height))
            addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(r, r)))
        }
        drawPath(path, color)
    }
}

private const val FOLD_FOLLOW_UP_MS = 15 * 60 * 1000L
