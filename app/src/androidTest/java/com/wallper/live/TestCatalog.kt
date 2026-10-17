package com.wallper.live

import android.content.Context
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.wallper.live.core.Footage
import com.wallper.live.core.FootageCategory
import com.wallper.live.data.catalog.Catalog
import com.wallper.live.data.catalog.CatalogSource
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Gallery of generated test videos (scripts/make-test-videos.sh, bundled as test
 * assets): full-frame colour cycling, so a frozen or black picture is easy to detect.
 * Covers every playback path: 1080p H.264 with a "preview stream", and 60 fps HEVC
 * through the layer-dropping decoder.
 */
class TestCatalog(private val context: Context) : CatalogSource {

    override suspend fun load(forceRefresh: Boolean): Catalog = withContext(Dispatchers.IO) {
        fun video(name: String): String = Uri.fromFile(copyAsset(name)).toString()
        Catalog(
            items = listOf(
                Footage(
                    id = SCENE_A,
                    title = "Сцена A",
                    collection = "Тест",
                    category = FootageCategory.Landscapes,
                    masterUrl = video("scene-a.mp4"),
                    previewUrl = video("scene-a.mp4"),
                    coverUrl = null,
                    highFrameRate = false,
                ),
                Footage(
                    id = SCENE_B,
                    title = "Сцена B",
                    collection = "Тест",
                    category = FootageCategory.Underwater,
                    masterUrl = video("scene-b.mp4"),
                    previewUrl = video("scene-b.mp4"),
                    coverUrl = null,
                    highFrameRate = false,
                ),
                Footage(
                    id = SCENE_HFR,
                    title = "Сцена HFR",
                    collection = "Тест",
                    category = FootageCategory.Space,
                    masterUrl = video("scene-hfr.mp4"),
                    previewUrl = null,
                    coverUrl = null,
                    highFrameRate = true,
                ),
                Footage(
                    id = SCENE_C,
                    title = "Сцена C",
                    collection = "Тест",
                    category = FootageCategory.Cities,
                    masterUrl = video("scene-c.mp4"),
                    previewUrl = video("scene-c.mp4"),
                    coverUrl = null,
                    highFrameRate = false,
                ),
            ),
            complete = true,
        )
    }

    private fun copyAsset(name: String): File {
        val target = File(context.filesDir, "test-videos/$name")
        if (target.exists() && target.length() > 0) return target
        target.parentFile?.mkdirs()
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        assets.open("videos/$name").use { input -> target.outputStream().use { input.copyTo(it) } }
        return target
    }

    companion object {
        const val SCENE_A = "test-a"
        const val SCENE_B = "test-b"
        const val SCENE_HFR = "test-hfr"
        const val SCENE_C = "test-c"
    }
}
