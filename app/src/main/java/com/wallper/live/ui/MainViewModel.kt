package com.wallper.live.ui

import android.app.Application
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.wallper.live.R
import com.wallper.live.WallperApplication
import com.wallper.live.core.Clip
import com.wallper.live.core.ClipOrigin
import com.wallper.live.core.Focus
import com.wallper.live.core.Footage
import com.wallper.live.core.FootageCategory
import com.wallper.live.core.Playback
import com.wallper.live.core.WallpaperRole
import com.wallper.live.core.WallpaperRoles
import com.wallper.live.core.WallpaperTarget
import com.wallper.live.data.catalog.CatalogState
import com.wallper.live.data.settings.WallpaperSettings
import com.wallper.live.media.ClipSource
import com.wallper.live.wallpaper.LiveWallpaper
import com.wallper.live.work.ExportJob
import com.wallper.live.work.ExportRequest
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class HomeTab { Apple, Mine }

/** What the editor/preview pane shows. */
@Immutable
sealed interface EditorTarget {
    data class FromFootage(val footage: Footage) : EditorTarget
    data class FromClip(val clipId: String) : EditorTarget
    data class FromDevice(val uri: Uri) : EditorTarget
}

@Immutable
data class MainUiState(
    val tab: HomeTab = HomeTab.Apple,
    val category: FootageCategory? = null,
    val catalog: CatalogState = CatalogState.Loading,
    val clips: List<Clip> = emptyList(),
    val settings: WallpaperSettings = WallpaperSettings(),
    val exports: List<ExportJob> = emptyList(),
    val liveWallpaperActive: Boolean = false,
    val editor: EditorTarget? = null,
    /** Android 14+: the user can pick home screen, lock screen or both. */
    val separateLock: Boolean = WallpaperRoles.separateLockSupported,
) {
    val visibleFootage: List<Footage>
        get() = (catalog as? CatalogState.Ready)?.items.orEmpty().filter { category == null || category.includes(it) }

    /** Topics that have scenes, in the order of the filter buttons. */
    val categories: List<FootageCategory>
        get() {
            val items = (catalog as? CatalogState.Ready)?.items.orEmpty()
            return FootageCategory.entries.filter { topic -> items.any(topic::includes) }
        }

    fun runningExport(footageId: String?): ExportJob? =
        exports.firstOrNull { it.footageId == footageId && it.state == ExportJob.State.Running }

    /** Screens currently showing [clip]. */
    fun rolesOf(clip: Clip): Set<WallpaperRole> =
        if (liveWallpaperActive) settings.rolesOf(clip.id) else emptySet()

    fun isOnScreen(clip: Clip) = rolesOf(clip).isNotEmpty()
}

sealed interface UiAction {
    data object OpenPicker : UiAction
    data class UndoDelete(val clipId: String) : UiAction
}

sealed interface UiEvent {
    data class Message(@StringRes val text: Int, val action: UiAction? = null, @StringRes val actionLabel: Int? = null) :
        UiEvent

    data object OpenPicker : UiEvent
}

class MainViewModel(application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application) {

    private val container = (application as WallperApplication).container

    // Where the person was survives the process being killed in the background.
    private val tab = MutableStateFlow(saved.get<String>(KEY_TAB)?.let { name -> HomeTab.entries.find { it.name == name } } ?: HomeTab.Apple)
    private val category = MutableStateFlow(saved.get<String>(KEY_CATEGORY)?.let { name -> FootageCategory.entries.find { it.name == name } })
    private val editor = MutableStateFlow<EditorTarget?>(null)
    private var restoringFootage = false
    private val liveActive = MutableStateFlow(LiveWallpaper.isActive(application))
    private val pendingDeletes = MutableStateFlow<Set<String>>(emptySet())

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events: Flow<UiEvent> = _events.receiveAsFlow()

    private val navigation = combine(tab, category, editor) { t, c, e -> Triple(t, c, e) }
    private val clips = combine(container.library.clips, pendingDeletes) { list, hidden ->
        list.filterNot { it.id in hidden }
    }

    val state: StateFlow<MainUiState> = combine(
        navigation,
        container.catalog.state,
        clips,
        combine(container.settings.settings, liveActive) { s, a -> s to a },
        container.exports.jobs,
    ) { (t, c, e), catalog, clipList, (settings, active), jobs ->
        MainUiState(
            tab = t,
            category = c,
            catalog = catalog,
            clips = clipList,
            settings = settings,
            exports = jobs,
            liveWallpaperActive = active,
            editor = e,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState())

    private val seenJobs = HashSet<java.util.UUID>()
    private val pendingTargets = HashMap<String, WallpaperTarget>()
    private var jobsPrimed = false

    init {
        restoreEditor(saved.get<String>(KEY_EDITOR))
        viewModelScope.launch { tab.collect { saved[KEY_TAB] = it.name } }
        viewModelScope.launch { category.collect { saved[KEY_CATEGORY] = it?.name } }
        // While a wallpaper waits for the catalog to come back, keep it saved.
        viewModelScope.launch { editor.collect { if (it != null || !restoringFootage) saved[KEY_EDITOR] = encode(it) } }
        container.catalog.refresh()
        viewModelScope.launch { container.exports.jobs.collect { onJobs(it) } }
    }

    fun selectTab(value: HomeTab) = tab.update { value }
    fun selectCategory(value: FootageCategory?) = category.update { value }
    fun retryCatalog() = container.catalog.refresh(force = true)

    fun open(target: EditorTarget) = editor.update { target }

    private fun encode(target: EditorTarget?): String? = when (target) {
        is EditorTarget.FromFootage -> "footage:" + target.footage.id
        is EditorTarget.FromClip -> "clip:" + target.clipId
        is EditorTarget.FromDevice -> "device:" + target.uri
        null -> null
    }

    private fun restoreEditor(saved: String?) {
        val kind = saved?.substringBefore(':') ?: return
        val value = saved.substringAfter(':')
        when (kind) {
            "clip" -> editor.value = EditorTarget.FromClip(value)
            "device" -> editor.value = EditorTarget.FromDevice(Uri.parse(value))
            // Footage comes back once the catalog has loaded again.
            "footage" -> {
                restoringFootage = true
                viewModelScope.launch {
                    val ready = container.catalog.state.first { it is CatalogState.Ready } as CatalogState.Ready
                    val footage = ready.items.firstOrNull { it.id == value }
                    restoringFootage = false
                    if (footage != null && editor.value == null) editor.value = EditorTarget.FromFootage(footage)
                }
            }
        }
    }
    fun closeEditor() = editor.update { null }

    fun refreshLiveWallpaper() {
        liveActive.value = LiveWallpaper.isActive(getApplication())
    }

    /** Returns true when the system picker actually set our wallpaper. */
    fun onPickerClosed(): Boolean {
        refreshLiveWallpaper()
        if (liveActive.value) send(UiEvent.Message(R.string.toast_applied))
        return liveActive.value
    }

    fun createFromFootage(
        footage: Footage,
        startMs: Long,
        loopMs: Long,
        speed: Float,
        focus: Focus,
        target: WallpaperTarget,
    ) = enqueue(
        ClipSource.of(footage.masterUrl), footage.title, ClipOrigin.Apple, footage.id,
        startMs, loopMs, speed, focus, target,
    )

    fun createFromDevice(
        uri: Uri,
        title: String,
        startMs: Long,
        loopMs: Long,
        speed: Float,
        focus: Focus,
        target: WallpaperTarget,
    ) {
        enqueue(ClipSource.Local(uri), title, ClipOrigin.Device, null, startMs, loopMs, speed, focus, target)
        tab.value = HomeTab.Mine
        closeEditor()
    }

    private fun enqueue(
        source: ClipSource,
        title: String,
        origin: ClipOrigin,
        footageId: String?,
        startMs: Long,
        loopMs: Long,
        speed: Float,
        focus: Focus,
        target: WallpaperTarget,
    ) {
        pendingTargets[footageId.orEmpty()] = target
        container.exports.enqueue(
            ExportRequest(
                source = source,
                title = title,
                origin = origin,
                footageId = footageId,
                startMs = startMs,
                loopMs = loopMs.coerceIn(Playback.MIN_LOOP_MS, Playback.MAX_LOOP_MS),
                fadeMs = Playback.FADE_MS,
                speed = speed,
                focus = focus,
                assign = target,
            ),
        )
    }

    fun saveSpeed(clipId: String, speed: Float) = viewModelScope.launch { container.settings.setSpeed(clipId, speed) }
    fun saveFocus(clipId: String, focus: Focus) = viewModelScope.launch { container.settings.setFocus(clipId, focus) }

    fun apply(clip: Clip, target: WallpaperTarget) = viewModelScope.launch {
        container.settings.assign(clip.id, target)
        refreshLiveWallpaper()
        if (LiveWallpaper.covers(getApplication(), target)) {
            send(UiEvent.Message(R.string.toast_updated))
        } else {
            send(UiEvent.OpenPicker)
        }
    }

    /** Deletion is deferred until the undo snackbar is dismissed. */
    fun requestDelete(clip: Clip) {
        pendingDeletes.update { it + clip.id }
        closeEditor()
        send(UiEvent.Message(R.string.toast_deleted, UiAction.UndoDelete(clip.id), R.string.action_undo))
    }

    fun undoDelete(clipId: String) = pendingDeletes.update { it - clipId }

    fun commitDelete(clipId: String) = viewModelScope.launch {
        if (clipId !in pendingDeletes.value) return@launch
        container.library.remove(clipId)
        container.settings.forget(clipId)
        pendingDeletes.update { it - clipId }
    }

    fun setSeamless(enabled: Boolean) = viewModelScope.launch { container.settings.setSeamlessLoop(enabled) }
    fun setParallax(enabled: Boolean) = viewModelScope.launch { container.settings.setParallax(enabled) }
    fun setPauseOnPowerSave(enabled: Boolean) = viewModelScope.launch { container.settings.setPauseOnPowerSave(enabled) }
    fun setFrostReveal(enabled: Boolean) = viewModelScope.launch { container.settings.setFrostReveal(enabled) }

    fun storageBytes(): Long = container.library.sizeOnDisk()

    private fun onJobs(jobs: List<ExportJob>) {
        val finished = jobs.filter { it.state != ExportJob.State.Running && it.id !in seenJobs }
        seenJobs += finished.map { it.id }
        if (!jobsPrimed) {
            jobsPrimed = true
            return
        }
        for (job in finished) {
            if (job.state == ExportJob.State.Failed) {
                send(UiEvent.Message(R.string.export_failed))
                continue
            }
            refreshLiveWallpaper()
            val target = pendingTargets.remove(job.footageId.orEmpty()) ?: WallpaperTarget.Both
            val editingThis = (editor.value as? EditorTarget.FromFootage)?.footage?.id == job.footageId
            when {
                LiveWallpaper.covers(getApplication(), target) -> send(UiEvent.Message(R.string.toast_applied))
                editingThis -> send(UiEvent.OpenPicker)
                else -> send(UiEvent.Message(R.string.toast_ready, UiAction.OpenPicker, R.string.action_set))
            }
            if (editingThis) job.clipId?.let { editor.value = EditorTarget.FromClip(it) }
        }
    }

    private fun send(event: UiEvent) {
        _events.trySend(event)
    }
}

private const val KEY_TAB = "tab"
private const val KEY_CATEGORY = "category"
private const val KEY_EDITOR = "editor"
