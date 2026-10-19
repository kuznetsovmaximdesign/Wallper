package com.wallper.live

import android.app.Application
import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.work.WorkManager
import com.wallper.live.data.catalog.AppleAerials
import com.wallper.live.data.catalog.CatalogRepository
import com.wallper.live.data.catalog.CatalogSource
import com.wallper.live.data.library.ClipLibrary
import com.wallper.live.data.settings.EditMemory
import com.wallper.live.data.settings.SettingsRepository
import com.wallper.live.media.ClipExporter
import com.wallper.live.media.CoverRepository
import com.wallper.live.media.MediaProbe
import com.wallper.live.media.MotionThumbnails
import com.wallper.live.media.RemoteFiles
import com.wallper.live.net.HttpClients
import com.wallper.live.net.NetworkMonitor
import com.wallper.live.playback.LoopPlayerFactory
import com.wallper.live.playback.LoopSession
import com.wallper.live.playback.LoopSpec
import com.wallper.live.playback.PlaybackHub
import com.wallper.live.work.ExportScheduler
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/** Manual dependency graph: one instance per process, shared by UI, worker and wallpaper. */
@OptIn(UnstableApi::class)
class AppContainer(
    private val app: Application,
    /** Replaces Apple's feeds (UI tests). */
    private val catalogSource: CatalogSource? = null,
) {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    val http by lazy { HttpClients.create() }

    val settings by lazy { SettingsRepository(app) }

    val library by lazy { ClipLibrary(File(app.filesDir, "clips"), json, appScope) }

    val catalog by lazy { CatalogRepository(catalogSource ?: AppleAerials(http, json, app.cacheDir), appScope) }

    val remoteFiles by lazy { RemoteFiles(http, File(app.cacheDir, "ranges")) }

    val covers by lazy { CoverRepository(app, remoteFiles, File(app.cacheDir, "covers")) }

    val motionThumbs by lazy { MotionThumbnails(app, remoteFiles, File(app.cacheDir, "motion")) }

    /** VPN on/off and other network switches: reconnect and retry what failed. */
    val network by lazy {
        NetworkMonitor(app, http).also { monitor ->
            appScope.launch { monitor.changes.drop(1).collect { catalog.retryIfFailed() } }
        }
    }

    val exporter by lazy { ClipExporter(app, remoteFiles) }

    val probe by lazy { MediaProbe(app, remoteFiles) }
    val edits by lazy { EditMemory(EditMemory.Preferences(app.getSharedPreferences("viewer-edits", Context.MODE_PRIVATE))) }

    val exports by lazy { ExportScheduler(WorkManager.getInstance(app)) }

    val mediaSourceFactory: MediaSource.Factory by lazy {
        DefaultMediaSourceFactory(DefaultDataSource.Factory(app, OkHttpDataSource.Factory(http)))
    }

    val loopPlayers by lazy { LoopPlayerFactory(app, mediaSourceFactory, remoteFiles) }

    val playbackHub by lazy { PlaybackHub(app, loopPlayers, library, settings, mainScope) }

    /** A standalone loop for in-app previews (not shared with the wallpaper). */
    fun newLoopSession(spec: LoopSpec, seamless: Boolean, startPositionMs: Long = 0): LoopSession =
        LoopSession(loopPlayers, spec, seamless, startPositionMs)
}
