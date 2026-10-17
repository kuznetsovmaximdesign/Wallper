package com.wallper.live.ui.editor

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.wallper.live.WallperApplication
import com.wallper.live.core.Clip
import com.wallper.live.core.Footage
import com.wallper.live.ui.components.Haptics
import com.wallper.live.ui.components.shimmer

/**
 * Film strip of neighbouring wallpapers: one tap swaps the live preview, so browsing
 * never requires going back to the gallery.
 */
@Composable
fun FootageStrip(items: List<Footage>, selectedId: String, onSelect: (Footage) -> Unit) {
    val context = LocalContext.current
    val covers = remember(context) { (context.applicationContext as WallperApplication).container.covers }
    Strip(items, selectedId, key = { it.id }, onSelect = onSelect) { footage ->
        var cover by remember(footage.id) { mutableStateOf<Any?>(footage.coverUrl ?: covers.cached(footage)) }
        LaunchedEffect(footage.id) { if (cover == null) cover = covers.cover(footage) }
        Thumb(cover)
    }
}

@Composable
fun ClipStrip(clips: List<Clip>, selectedId: String, onSelect: (Clip) -> Unit) {
    val context = LocalContext.current
    val library = remember(context) { (context.applicationContext as WallperApplication).container.library }
    Strip(clips, selectedId, key = { it.id }, onSelect = onSelect) { clip -> Thumb(library.coverOf(clip)) }
}

@Composable
private fun <T> Strip(
    items: List<T>,
    selectedId: String,
    key: (T) -> String,
    onSelect: (T) -> Unit,
    thumb: @Composable (T) -> Unit,
) {
    val view = LocalView.current
    val listState = rememberLazyListState()
    LaunchedEffect(selectedId, items.size) {
        val index = items.indexOfFirst { key(it) == selectedId }
        if (index >= 0) listState.animateScrollToItem((index - 2).coerceAtLeast(0))
    }
    LazyRow(
        state = listState,
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items, key = key) { item ->
            val selected = key(item) == selectedId
            Box(
                Modifier
                    .size(width = 48.dp, height = 72.dp)
                    .clip(ThumbShape)
                    .border(if (selected) 2.dp else 1.dp, Color.White.copy(alpha = if (selected) 1f else 0.2f), ThumbShape)
                    .clickable {
                        if (!selected) {
                            Haptics.tick(view)
                            onSelect(item)
                        }
                    },
            ) { thumb(item) }
        }
    }
}

@Composable
private fun Thumb(cover: Any?) {
    Box(Modifier.fillMaxSize().shimmer())
    if (cover != null) {
        AsyncImage(
            model = cover,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private val ThumbShape = RoundedCornerShape(12.dp)
