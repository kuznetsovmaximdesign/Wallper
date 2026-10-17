package com.wallper.live.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.wallper.live.core.Clip
import com.wallper.live.core.WallpaperRole
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
 * The system runs separate wallpaper engines for the home screen, the lock screen
 * (Android 14+) and each display of a foldable. The hub always plays the clip assigned
 * to the role of the engine that is visible now:
 *  - same clip: unlocking, folding or unfolding only moves the decoder output to the
 *    engine that became visible, so the video continues on the very same frame;
 *  - different clips (home ≠ lock): the session is switched and each clip resumes
 *    where it stopped.
 * Only one session (≤ 2 decoders) exists at a time. Main thread only.
 */
class PlaybackHub(
    private val context: Context,
    private val players: LoopPlayerFactory,
    private val library: ClipLibrary,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    private data class Desired(
        val home: Clip?,
        val lock: Clip?,
        val speeds: Map<String, Float>,
        val seamless: Boolean,
        val pauseOnPowerSave: Boolean,
        val powerSave: Boolean,
    ) {
        fun clipFor(role: WallpaperRole) = if (role == WallpaperRole.Lock) lock else home
    }

    private data class SessionKey(val clipId: String, val seamless: Boolean)

    private class Visible(val target: VideoTarget, var role: WallpaperRole)

    private val handler = Handler(Looper.getMainLooper())
    private val owners = HashSet<Any>()
    private val visible = ArrayList<Visible>()
    private val powerSave = MutableStateFlow(false)
    private val resumePositions = HashMap<String, Long>()

    private var desired: Desired? = null
    private var session: LoopSession? = null
    private var sessionKey: SessionKey? = null
    private var collectJob: Job? = null

    private val pauseRunnable = Runnable { session?.pause() }
    private val releaseRunnable = Runnable { releaseSession() }

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
                fun clip(id: String?) = id?.let { wanted -> clips.firstOrNull { it.id == wanted } }
                Desired(
                    home = clip(s.clipIdFor(WallpaperRole.Home)),
                    lock = clip(s.clipIdFor(WallpaperRole.Lock)),
                    speeds = s.speeds,
                    seamless = s.seamlessLoop,
                    pauseOnPowerSave = s.pauseOnPowerSave,
                    powerSave = saving,
                )
            }.distinctUntilChanged().collect {
                desired = it
                reconcile()
            }
        }
    }

    fun unregister(owner: Any) {
        owners -= owner
        if (owners.isNotEmpty()) return
        collectJob?.cancel()
        collectJob = null
        runCatching { context.unregisterReceiver(powerReceiver) }
        handler.removeCallbacksAndMessages(null)
        releaseSession()
        visible.clear()
        resumePositions.clear()
    }

    fun show(target: VideoTarget, role: WallpaperRole) {
        handler.removeCallbacks(pauseRunnable)
        handler.removeCallbacks(releaseRunnable)
        visible.removeAll { it.target === target }
        visible += Visible(target, role)
        reconcile()
    }

    /** The engine was moved between the home and the lock screen. */
    fun updateRole(target: VideoTarget, role: WallpaperRole) {
        val entry = visible.firstOrNull { it.target === target } ?: return
        if (entry.role == role) return
        entry.role = role
        reconcile()
    }

    fun hide(target: VideoTarget) {
        visible.removeAll { it.target === target }
        if (visible.isNotEmpty()) {
            reconcile()
            return
        }
        // Grace period: a fold/unfold hides one engine just before showing the other.
        handler.postDelayed(pauseRunnable, PAUSE_GRACE_MS)
        handler.postDelayed(releaseRunnable, RELEASE_AFTER_MS)
    }

    /** The engine owning [target] is going away: stop drawing into its surfaces. */
    fun forget(target: VideoTarget) {
        visible.removeAll { it.target === target }
        session?.detach(target)
        if (visible.isNotEmpty()) reconcile()
    }

    private fun reconcile() {
        val wanted = desired ?: return
        val current = visible.lastOrNull() ?: return
        val clip = wanted.clipFor(current.role)
        if (clip == null) {
            releaseSession()
            return
        }
        val key = SessionKey(clip.id, wanted.seamless && clip.fadeMs > 0)
        if (key != sessionKey) {
            // Continue the same clip from where it is (e.g. seamless toggled), otherwise
            // resume the newly shown clip from where it was last left.
            val position = if (sessionKey?.clipId == clip.id) session?.positionMs else null
            releaseSession()
            session = LoopSession(
                factory = players,
                spec = LoopSpec(
                    uri = Uri.fromFile(library.fileOf(clip)),
                    loopMs = clip.loopMs,
                    fadeMs = clip.fadeMs,
                ),
                seamless = key.seamless,
                startPositionMs = position ?: resumePositions[clip.id] ?: 0,
            )
            sessionKey = key
        }
        val active = session ?: return
        active.attach(current.target)
        active.speed = wanted.speeds[clip.id] ?: 1f
        if (wanted.pauseOnPowerSave && wanted.powerSave) active.pause() else active.play()
    }

    private fun releaseSession() {
        val current = session ?: return
        sessionKey?.let { resumePositions[it.clipId] = current.positionMs }
        current.release()
        session = null
        sessionKey = null
    }

    private companion object {
        const val PAUSE_GRACE_MS = 400L
        const val RELEASE_AFTER_MS = 120_000L
    }
}
