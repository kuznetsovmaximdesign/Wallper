package com.wallper.live

import android.app.Application
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.work.WorkManager
import com.wallper.live.data.catalog.AppleAerials
import com.wallper.live.data.catalog.CatalogRepository
import com.wallper.live.data.library.ClipLibrary
import com.wallper.live.data.settings.SettingsRepository
import com.wallper.live.media.ClipExporter
import com.wallper.live.media.CoverRepository
import com.wallper.live.media.MediaProbe
import com.wallper.live.net.HttpClients
import com.wallper.live.playback.LoopSession
import com.wallper.live.playback.PlaybackHub
import com.wallper.live.work.ExportScheduler
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json

/** Manual dependency graph: one instance per process, shared by UI, worker and wallpaper. */
@OptIn(UnstableApi::class)
class AppContainer(private val app: Application) {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    val http by lazy { HttpClients.create() }

    val settings by lazy { SettingsRepository(app) }

    val library by lazy { ClipLibrary(File(app.filesDir, "clips"), json, appScope) }

    val catalog by lazy { CatalogRepository(AppleAerials(http, json, app.cacheDir), appScope) }

    val covers by lazy { CoverRepository(http, File(app.cacheDir, "covers")) }

    val exporter by lazy { ClipExporter(app, http) }

    val probe by lazy { MediaProbe(app, http) }

    val exports by lazy { ExportScheduler(WorkManager.getInstance(app)) }

    val mediaSourceFactory: MediaSource.Factory by lazy {
        DefaultMediaSourceFactory(DefaultDataSource.Factory(app, OkHttpDataSource.Factory(http)))
    }

    val playbackHub by lazy { PlaybackHub(app, mediaSourceFactory, library, settings, mainScope) }

    /** A muted player for inline gallery previews (one shared instance per screen). */
    fun newPreviewPlayer(): ExoPlayer = ExoPlayer.Builder(app)
        .setMediaSourceFactory(mediaSourceFactory)
        .build()
        .apply {
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ONE
        }

    /** A standalone loop for in-app previews (not shared with the wallpaper). */
    fun newLoopSession(spec: LoopSession.LoopSpec, seamless: Boolean): LoopSession =
        LoopSession(app, mediaSourceFactory, spec, seamless)
}
