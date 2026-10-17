package com.wallper.live.work

import android.net.Uri
import androidx.work.Data
import androidx.work.workDataOf
import com.wallper.live.core.ClipOrigin
import com.wallper.live.core.Focus
import com.wallper.live.media.ClipSource

/** Everything needed to cut one wallpaper clip in the background. */
data class ExportRequest(
    val source: ClipSource,
    val title: String,
    val origin: ClipOrigin,
    val footageId: String?,
    val startMs: Long,
    val loopMs: Long,
    val fadeMs: Long,
    val speed: Float,
    val focus: Focus,
    val activate: Boolean,
) {
    fun toData(): Data = workDataOf(
        KEY_REMOTE to (source as? ClipSource.Remote)?.url,
        KEY_LOCAL to (source as? ClipSource.Local)?.uri?.toString(),
        KEY_TITLE to title,
        KEY_ORIGIN to origin.name,
        KEY_FOOTAGE to footageId,
        KEY_START to startMs,
        KEY_LOOP to loopMs,
        KEY_FADE to fadeMs,
        KEY_SPEED to speed,
        KEY_FOCUS_X to focus.x,
        KEY_FOCUS_Y to focus.y,
        KEY_ACTIVATE to activate,
    )

    companion object {
        private const val KEY_REMOTE = "remote"
        private const val KEY_LOCAL = "local"
        private const val KEY_TITLE = "title"
        private const val KEY_ORIGIN = "origin"
        private const val KEY_FOOTAGE = "footage"
        private const val KEY_START = "start"
        private const val KEY_LOOP = "loop"
        private const val KEY_FADE = "fade"
        private const val KEY_SPEED = "speed"
        private const val KEY_FOCUS_X = "focus_x"
        private const val KEY_FOCUS_Y = "focus_y"
        private const val KEY_ACTIVATE = "activate"

        fun fromData(data: Data): ExportRequest? {
            val source = data.getString(KEY_REMOTE)?.let { ClipSource.Remote(it) }
                ?: data.getString(KEY_LOCAL)?.let { ClipSource.Local(Uri.parse(it)) }
                ?: return null
            return ExportRequest(
                source = source,
                title = data.getString(KEY_TITLE).orEmpty(),
                origin = runCatching { ClipOrigin.valueOf(data.getString(KEY_ORIGIN).orEmpty()) }
                    .getOrDefault(ClipOrigin.Device),
                footageId = data.getString(KEY_FOOTAGE),
                startMs = data.getLong(KEY_START, 0),
                loopMs = data.getLong(KEY_LOOP, 0),
                fadeMs = data.getLong(KEY_FADE, 0),
                speed = data.getFloat(KEY_SPEED, 1f),
                focus = Focus(data.getFloat(KEY_FOCUS_X, 0.5f), data.getFloat(KEY_FOCUS_Y, 0.5f)),
                activate = data.getBoolean(KEY_ACTIVATE, false),
            )
        }
    }
}
