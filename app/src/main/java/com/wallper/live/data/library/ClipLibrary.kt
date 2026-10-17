package com.wallper.live.data.library

import com.wallper.live.core.Clip
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Wallpaper clips stored in app-private storage, indexed by a small JSON file. */
class ClipLibrary(
    val directory: File,
    private val json: Json,
    scope: CoroutineScope,
) {
    private val index = File(directory, "library.json")
    private val mutex = Mutex()
    private val _clips = MutableStateFlow<List<Clip>>(emptyList())
    val clips: StateFlow<List<Clip>> = _clips.asStateFlow()

    init {
        scope.launch { mutex.withLock { _clips.value = read() } }
    }

    fun fileOf(clip: Clip): File = File(directory, clip.fileName)
    fun coverOf(clip: Clip): File? = clip.coverFileName?.let { File(directory, it) }

    fun newFile(name: String): File = File(directory.apply { mkdirs() }, name)

    suspend fun add(clip: Clip) = update { listOf(clip) + it.filterNot { c -> c.id == clip.id } }

    suspend fun remove(clipId: String): Clip? {
        var removed: Clip? = null
        update { list ->
            removed = list.firstOrNull { it.id == clipId }
            list.filterNot { it.id == clipId }
        }
        removed?.let { clip ->
            withContext(Dispatchers.IO) {
                fileOf(clip).delete()
                coverOf(clip)?.delete()
            }
        }
        return removed
    }

    fun sizeOnDisk(): Long = directory.listFiles()?.sumOf { it.length() } ?: 0L

    private suspend fun update(transform: (List<Clip>) -> List<Clip>) = mutex.withLock {
        val next = transform(_clips.value)
        withContext(Dispatchers.IO) {
            directory.mkdirs()
            val tmp = File(directory, "library.json.tmp")
            tmp.writeText(json.encodeToString(ListSerializer(Clip.serializer()), next))
            tmp.renameTo(index)
        }
        _clips.value = next
    }

    private suspend fun read(): List<Clip> = withContext(Dispatchers.IO) {
        if (!index.exists()) return@withContext emptyList()
        runCatching { json.decodeFromString(ListSerializer(Clip.serializer()), index.readText()) }
            .getOrDefault(emptyList())
            .filter { File(directory, it.fileName).exists() }
    }
}
