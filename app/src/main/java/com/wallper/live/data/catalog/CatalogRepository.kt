package com.wallper.live.data.catalog

import com.wallper.live.core.Footage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface CatalogState {
    data object Loading : CatalogState
    data class Ready(val items: List<Footage>) : CatalogState
    data object Error : CatalogState
}

class CatalogRepository(
    private val apple: AppleAerials,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    @Volatile private var complete = false
    private val _state = MutableStateFlow<CatalogState>(CatalogState.Loading)
    val state: StateFlow<CatalogState> = _state.asStateFlow()

    fun refresh(force: Boolean = false) {
        scope.launch {
            mutex.withLock {
                if (!force && _state.value is CatalogState.Ready) return@withLock
                if (_state.value !is CatalogState.Ready) _state.value = CatalogState.Loading
                val catalog = loadWithRetry(force)
                complete = catalog?.complete ?: false
                // A failed refresh keeps what is already on screen.
                _state.value = catalog?.let { CatalogState.Ready(it.items) } ?: (_state.value as? CatalogState.Ready ?: CatalogState.Error)
            }
        }
    }

    /** Loads again after a failure or a partial load, e.g. once the network comes back. */
    fun retryIfFailed() {
        if (_state.value is CatalogState.Error || (_state.value is CatalogState.Ready && !complete)) refresh(force = true)
    }

    private suspend fun loadWithRetry(force: Boolean): AppleAerials.Catalog? {
        repeat(ATTEMPTS) { attempt ->
            runCatching { apple.load(force) }.onSuccess { return it }
            if (attempt < ATTEMPTS - 1) delay(RETRY_MS * (attempt + 1))
        }
        return null
    }

    fun find(id: String): Footage? = (state.value as? CatalogState.Ready)?.items?.firstOrNull { it.id == id }

    private companion object {
        const val ATTEMPTS = 3
        const val RETRY_MS = 1_500L
    }
}
