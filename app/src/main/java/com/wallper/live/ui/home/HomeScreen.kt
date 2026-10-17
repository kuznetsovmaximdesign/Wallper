package com.wallper.live.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.wallper.live.R
import com.wallper.live.WallperApplication
import com.wallper.live.core.Clip
import com.wallper.live.core.Footage
import com.wallper.live.core.FootageCategory
import com.wallper.live.core.formatDuration
import com.wallper.live.data.catalog.CatalogState
import com.wallper.live.ui.HomeTab
import com.wallper.live.ui.MainUiState
import com.wallper.live.ui.components.pressScale
import com.wallper.live.ui.components.shimmer
import java.io.File

@Composable
fun HomeScreen(
    state: MainUiState,
    onTab: (HomeTab) -> Unit,
    onCategory: (FootageCategory?) -> Unit,
    onRetry: () -> Unit,
    onFootage: (Footage) -> Unit,
    onClip: (Clip) -> Unit,
    onImport: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    selectedId: String? = null,
) {
    val insets = WindowInsets.safeDrawing.asPaddingValues()
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 156.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = insets.calculateTopPadding() + 12.dp,
            bottom = insets.calculateBottomPadding() + 24.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        fullWidth("header") { Header(onSettings) }
        fullWidth("tabs") { Tabs(state.tab, onTab) }
        when (state.tab) {
            HomeTab.Apple -> appleTab(state, onCategory, onRetry, onFootage, selectedId)
            HomeTab.Mine -> mineTab(state, onClip, onImport, selectedId)
        }
    }
}

private fun LazyGridScope.fullWidth(key: String, content: @Composable () -> Unit) =
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }

@Composable
private fun Header(onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.home_title), style = MaterialTheme.typography.displaySmall)
            Text(
                stringResource(R.string.home_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FilledTonalIconButton(onClick = onSettings, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.action_settings))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Tabs(tab: HomeTab, onTab: (HomeTab) -> Unit) {
    val tabs = listOf(HomeTab.Apple to R.string.tab_apple, HomeTab.Mine to R.string.tab_mine)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        tabs.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = tab == value,
                onClick = { onTab(value) },
                shape = SegmentedButtonDefaults.itemShape(index, tabs.size),
                label = { Text(stringResource(label)) },
            )
        }
    }
}

private fun LazyGridScope.appleTab(
    state: MainUiState,
    onCategory: (FootageCategory?) -> Unit,
    onRetry: () -> Unit,
    onFootage: (Footage) -> Unit,
    selectedId: String?,
) {
    fullWidth("categories") { Categories(state.category, onCategory) }
    when (val catalog = state.catalog) {
        CatalogState.Loading -> items(8, key = { "skeleton-$it" }) {
            Box(Modifier.aspectRatio(CARD_RATIO).clip(MaterialTheme.shapes.large).shimmer())
        }
        CatalogState.Error -> fullWidth("error") {
            Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.catalog_error), color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
            }
        }
        is CatalogState.Ready -> items(state.visibleFootage, key = { it.id }) { footage ->
            FootageCard(
                footage = footage,
                progress = state.runningExport(footage.id)?.progress,
                selected = footage.id == selectedId,
                onClick = { onFootage(footage) },
            )
        }
    }
}

private fun LazyGridScope.mineTab(
    state: MainUiState,
    onClip: (Clip) -> Unit,
    onImport: () -> Unit,
    selectedId: String?,
) {
    item(key = "import") { ImportCard(onImport) }
    state.exports.filter { it.state == com.wallper.live.work.ExportJob.State.Running && it.footageId == null }
        .forEach { job ->
            item(key = "job-${job.id}") {
                Box(
                    Modifier.aspectRatio(CARD_RATIO).clip(MaterialTheme.shapes.large).shimmer(),
                    contentAlignment = Alignment.Center,
                ) { ProgressBadge(job.progress) }
            }
        }
    items(state.clips, key = { it.id }) { clip ->
        ClipCard(
            clip = clip,
            onScreen = state.isOnScreen(clip),
            selected = clip.id == selectedId,
            onClick = { onClip(clip) },
        )
    }
    if (state.clips.isEmpty()) {
        fullWidth("empty") {
            Column(Modifier.fillMaxWidth().padding(vertical = 24.dp)) {
                Text(stringResource(R.string.library_empty_title), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.library_empty_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Categories(selected: FootageCategory?, onCategory: (FootageCategory?) -> Unit) {
    val options = listOf(
        null to R.string.category_all,
        FootageCategory.Landscapes to R.string.category_landscapes,
        FootageCategory.Underwater to R.string.category_underwater,
        FootageCategory.Space to R.string.category_space,
        FootageCategory.Cities to R.string.category_cities,
    )
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(options, key = { it.second }) { (category, label) ->
            FilterChip(
                selected = selected == category,
                onClick = { onCategory(category) },
                label = { Text(stringResource(label)) },
                shape = CircleShape,
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        }
    }
}

@Composable
private fun FootageCard(footage: Footage, progress: Float?, selected: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    val covers = remember(context) { (context.applicationContext as WallperApplication).container.covers }
    val cover by produceState(covers.cached(footage), footage.id) { value = value ?: covers.cover(footage) }
    MediaCard(
        cover = cover,
        title = footage.title,
        caption = null,
        selected = selected,
        onClick = onClick,
        badge = { if (progress != null) ProgressBadge(progress) },
    )
}

@Composable
private fun ClipCard(clip: Clip, onScreen: Boolean, selected: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    val library = remember(context) { (context.applicationContext as WallperApplication).container.library }
    MediaCard(
        cover = library.coverOf(clip),
        title = clip.title,
        caption = (if (clip.isUhd) "4K · " else "") + formatDuration(clip.loopMs),
        selected = selected,
        onClick = onClick,
        badge = { if (onScreen) ActiveBadge() },
    )
}

@Composable
private fun MediaCard(
    cover: File?,
    title: String,
    caption: String?,
    selected: Boolean,
    onClick: () -> Unit,
    badge: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val shape = MaterialTheme.shapes.large
    Box(
        Modifier
            .pressScale(interaction)
            .aspectRatio(CARD_RATIO)
            .clip(shape)
            .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
    ) {
        Box(Modifier.fillMaxSize().shimmer())
        AnimatedVisibility(cover != null, enter = fadeIn(), exit = fadeOut()) {
            AsyncImage(
                model = cover,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(96.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f)))),
        )
        Column(Modifier.align(Alignment.BottomStart).padding(14.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (caption != null) {
                Text(caption, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.75f))
            }
        }
        Box(Modifier.align(Alignment.TopEnd).padding(10.dp)) { badge() }
    }
}

@Composable
private fun ImportCard(onImport: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        Modifier
            .pressScale(interaction)
            .aspectRatio(CARD_RATIO)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), MaterialTheme.shapes.large)
            .clickable(interactionSource = interaction, indication = null, onClick = onImport)
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(56.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
        }
        Spacer(Modifier.height(14.dp))
        Text(
            stringResource(R.string.action_import),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ActiveBadge() {
    Row(
        Modifier
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50))
            .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).background(Color(0xFF5BE38A), CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.badge_active), style = MaterialTheme.typography.labelMedium, color = Color.White)
    }
}

@Composable
private fun ProgressBadge(progress: Float) {
    Box(
        Modifier.size(40.dp).background(Color.Black.copy(alpha = 0.55f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            progress = { progress.coerceIn(0.02f, 1f) },
            modifier = Modifier.size(28.dp),
            color = Color.White,
            trackColor = Color.White.copy(alpha = 0.2f),
            strokeWidth = 3.dp,
        )
    }
}

private const val CARD_RATIO = 9f / 16f
