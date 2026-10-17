package com.wallper.live.data.catalog

import com.wallper.live.core.Footage
import kotlinx.coroutines.CoroutineScope
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
    private val _state = MutableStateFlow<CatalogState>(CatalogState.Loading)
    val state: StateFlow<CatalogState> = _state.asStateFlow()

    fun refresh(force: Boolean = false) {
        scope.launch {
            mutex.withLock {
                if (!force && _state.value is CatalogState.Ready) return@withLock
                if (_state.value !is CatalogState.Ready) _state.value = CatalogState.Loading
                _state.value = runCatching { apple.load(force) }
                    .fold(onSuccess = { CatalogState.Ready(it) }, onFailure = { CatalogState.Error })
            }
        }
    }

    fun find(id: String): Footage? = (state.value as? CatalogState.Ready)?.items?.firstOrNull { it.id == id }
}
