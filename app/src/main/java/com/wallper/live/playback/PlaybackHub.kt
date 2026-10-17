package com.wallper.live.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.MediaSource
import com.wallper.live.core.Clip
import com.wallper.live.data.library.ClipLibrary
import com.wallper.live.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * One playback session shared by every wallpaper engine in the process.
 *
 * Foldables run separate wallpaper engines for the cover and the inner display. Because
 * they share this hub, folding or unfolding only moves the decoder output to the
 * engine that became visible: the video keeps its position instead of restarting.
 * Main thread only.
 */
@OptIn(UnstableApi::class)
class PlaybackHub(
    private val context: Context,
    private val mediaSourceFactory: MediaSource.Factory,
    private val library: ClipLibrary,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    private data class Desired(
        val clip: Clip?,
        val speed: Float,
        val seamless: Boolean,
        val pauseOnPowerSave: Boolean,
        val powerSave: Boolean,
    )

    private data class SessionKey(val clipId: String, val seamless: Boolean)

    private val handler = Handler(Looper.getMainLooper())
    private val owners = HashSet<Any>()
    private val visible = ArrayList<VideoTarget>()
    private val powerSave = MutableStateFlow(false)

    private var desired: Desired? = null
    private var session: LoopSession? = null
    private var sessionKey: SessionKey? = null
    private var resumeClipId: String? = null
    private var resumePositionMs = 0L
    private var collectJob: Job? = null

    private val pauseRunnable = Runnable { updatePlayState() }
    private val releaseRunnable = Runnable { releaseSession(rememberPosition = true) }

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            powerSave.value = context.getSystemService(PowerManager::class.java).isPowerSaveMode
        }
    }

    fun register(owner: Any) {
        owners += owner
        if (collectJob != null) return
        ContextCompat.registerReceiver(
            context,
            powerReceiver,
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        powerSave.value = context.getSystemService(PowerManager::class.java).isPowerSaveMode
        collectJob = scope.launch {
            combine(settings.settings, library.clips, powerSave) { s, clips, saving ->
                val clip = clips.firstOrNull { it.id == s.activeClipId }
                Desired(
                    clip = clip,
                    speed = clip?.let { s.speedOf(it.id) } ?: 1f,
                    seamless = s.seamlessLoop,
                    pauseOnPowerSave = s.pauseOnPowerSave,
                    powerSave = saving,
                )
            }.distinctUntilChanged().collect { apply(it) }
        }
    }

    fun unregister(owner: Any) {
        owners -= owner
        if (owners.isNotEmpty()) return
        collectJob?.cancel()
        collectJob = null
        runCatching { context.unregisterReceiver(powerReceiver) }
        handler.removeCallbacksAndMessages(null)
        releaseSession(rememberPosition = false)
        visible.clear()
    }

    fun show(target: VideoTarget) {
        handler.removeCallbacks(pauseRunnable)
        handler.removeCallbacks(releaseRunnable)
        visible.remove(target)
        visible += target
        if (session == null) desired?.let(::apply) else session?.attach(target)
        updatePlayState()
    }

    fun hide(target: VideoTarget) {
        visible.remove(target)
        val next = visible.lastOrNull()
        if (next != null) {
            session?.attach(next)
            return
        }
        // Grace period: a fold/unfold hides one engine just before showing the other.
        handler.postDelayed(pauseRunnable, PAUSE_GRACE_MS)
        handler.postDelayed(releaseRunnable, RELEASE_AFTER_MS)
    }

    /** The engine owning [target] is going away: stop drawing into its surfaces. */
    fun forget(target: VideoTarget) {
        visible.remove(target)
        session?.detach(target)
        visible.lastOrNull()?.let { session?.attach(it) }
    }

    private fun apply(next: Desired) {
        desired = next
        val clip = next.clip
        if (clip == null) {
            releaseSession(rememberPosition = false)
            return
        }
        val key = SessionKey(clip.id, next.seamless && clip.fadeMs > 0)
        if (key != sessionKey) {
            val position = when {
                sessionKey?.clipId == clip.id -> session?.positionMs ?: 0
                resumeClipId == clip.id -> resumePositionMs
                else -> 0
            }
            releaseSession(rememberPosition = false)
            resumeClipId = clip.id
            resumePositionMs = position
            if (visible.isEmpty()) return // created lazily by show()
            session = LoopSession(
                context = context,
                mediaSourceFactory = mediaSourceFactory,
                spec = LoopSession.LoopSpec(
                    mediaItem = MediaItem.fromUri(Uri.fromFile(library.fileOf(clip))),
                    loopMs = clip.loopMs,
                    fadeMs = clip.fadeMs,
                ),
                seamless = key.seamless,
                startPositionMs = position,
            )
            sessionKey = key
        }
        visible.lastOrNull()?.let { session?.attach(it) }
        session?.speed = next.speed
        updatePlayState()
    }

    private fun updatePlayState() {
        val current = session ?: return
        val blockedByPowerSave = desired?.let { it.pauseOnPowerSave && it.powerSave } == true
        if (visible.isNotEmpty() && !blockedByPowerSave) current.play() else current.pause()
    }

    private fun releaseSession(rememberPosition: Boolean) {
        val current = session ?: return
        if (rememberPosition) {
            resumeClipId = sessionKey?.clipId
            resumePositionMs = current.positionMs
        }
        current.release()
        session = null
        sessionKey = null
    }

    private companion object {
        const val PAUSE_GRACE_MS = 400L
        const val RELEASE_AFTER_MS = 120_000L
    }
}
