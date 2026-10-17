package com.wallper.live.ui.home

import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
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
import com.wallper.live.core.WallpaperRole
import com.wallper.live.core.WallpaperRoles
import com.wallper.live.core.formatDuration
import com.wallper.live.data.catalog.CatalogState
import com.wallper.live.playback.LoopSpec
import com.wallper.live.ui.HomeTab
import com.wallper.live.ui.MainUiState
import com.wallper.live.ui.components.GlassSurface
import com.wallper.live.ui.components.InlineVideo
import com.wallper.live.ui.components.pressScale
import com.wallper.live.ui.components.shimmer
import com.wallper.live.work.ExportJob
import java.io.File

/**
 * Video-first gallery: a hero card, a grid of portrait cards and a floating toolbar.
 * The cards nearest to the centre of the viewport play in place: the hero streams the
 * real video, grid cards play short seamless loops cut on the device (light enough to
 * run several at once). Cards further away show a sharp still.
 */
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
    /** Off while the side-by-side viewer is playing, so only one decoder runs. */
    autoplay: Boolean = true,
) {
    val insets = WindowInsets.safeDrawing.asPaddingValues()
    val gridState = rememberLazyGridState()
    val mediaIds = remember(state.tab, state.visibleFootage, state.clips) {
        if (state.tab == HomeTab.Apple) {
            state.visibleFootage.mapTo(HashSet()) { it.id }
        } else {
            state.clips.mapTo(HashSet()) { it.id }
        }
    }
    val centered by remember(gridState, mediaIds) { derivedStateOf { centeredItems(gridState, mediaIds, MAX_LIVE_CARDS) } }
    val playing = if (autoplay) centered else emptySet()

    // Start from the top whenever the content set changes.
    LaunchedEffect(state.tab, state.category) { gridState.scrollToItem(0) }

    Box(modifier.fillMaxSize()) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(minSize = 160.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = insets.calculateTopPadding() + 8.dp,
                bottom = insets.calculateBottomPadding() + 112.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            fullWidth("header") { Header(state.tab, onSettings) }
            when (state.tab) {
                HomeTab.Apple -> appleTab(state, onCategory, onRetry, onFootage, selectedId, playing)
                HomeTab.Mine -> mineTab(state, onClip, selectedId, playing)
            }
        }

        // Let the grid fade out under the floating toolbar.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(insets.calculateBottomPadding() + 120.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.background))),
        )
        BottomToolbar(
            tab = state.tab,
            onTab = onTab,
            onImport = onImport,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp),
        )
    }
}

/** Keys of the (at most [count]) visible media cards closest to the viewport centre. */
private fun centeredItems(grid: LazyGridState, ids: Set<String>, count: Int): Set<String> {
    val info = grid.layoutInfo
    val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
    return info.visibleItemsInfo
        .filter { it.key in ids }
        .sortedBy { kotlin.math.abs(it.offset.y + it.size.height / 2 - center) }
        .take(count)
        .mapNotNullTo(HashSet()) { it.key as? String }
}

private fun LazyGridScope.fullWidth(key: String, content: @Composable () -> Unit) =
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }

@Composable
private fun Header(tab: HomeTab, onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(if (tab == HomeTab.Apple) R.string.home_title_gallery else R.string.tab_mine),
            style = MaterialTheme.typography.displaySmall,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = onSettings,
            modifier = Modifier.size(44.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
        ) {
            Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.action_settings))
        }
    }
}

private fun LazyGridScope.appleTab(
    state: MainUiState,
    onCategory: (FootageCategory?) -> Unit,
    onRetry: () -> Unit,
    onFootage: (Footage) -> Unit,
    selectedId: String?,
    playing: Set<String>,
) {
    fullWidth("categories") { Categories(state.category, onCategory) }
    when (state.catalog) {
        CatalogState.Loading -> {
            fullWidth("hero-skeleton") { Box(Modifier.aspectRatio(HERO_RATIO).clip(HeroShape).shimmer()) }
            items(6, key = { "skeleton-$it" }) { Box(Modifier.aspectRatio(CARD_RATIO).clip(CardShape).shimmer()) }
        }
        CatalogState.Error -> fullWidth("error") {
            Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.catalog_error), color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
            }
        }
        is CatalogState.Ready -> {
            val all = state.visibleFootage
            val hero = all.firstOrNull()
            if (hero != null) {
                item(key = hero.id, span = { GridItemSpan(maxLineSpan) }) {
                    FootageCard(hero, state, hero.id == selectedId, hero.id in playing, hero = true) {
                        onFootage(hero)
                    }
                }
            }
            items(all.drop(1), key = { it.id }) { footage ->
                FootageCard(footage, state, footage.id == selectedId, footage.id in playing) {
                    onFootage(footage)
                }
            }
        }
    }
}

private fun LazyGridScope.mineTab(
    state: MainUiState,
    onClip: (Clip) -> Unit,
    selectedId: String?,
    playing: Set<String>,
) {
    state.exports.filter { it.state == ExportJob.State.Running && it.footageId == null }.forEach { job ->
        item(key = "job-${job.id}") {
            Box(Modifier.aspectRatio(CARD_RATIO).clip(CardShape).shimmer(), contentAlignment = Alignment.Center) {
                ProgressBadge(job.progress)
            }
        }
    }
    items(state.clips, key = { it.id }) { clip ->
        ClipCard(clip, state.rolesOf(clip), clip.id == selectedId, clip.id in playing) { onClip(clip) }
    }
    if (state.clips.isEmpty()) {
        fullWidth("empty") {
            Column(Modifier.fillMaxWidth().padding(vertical = 32.dp)) {
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

/** Expressive pill row: the selected chip fills and morphs from a rounded square to a pill. */
@Composable
private fun Categories(selected: FootageCategory?, onCategory: (FootageCategory?) -> Unit) {
    val options = listOf(
        null to R.string.category_all,
        FootageCategory.Landscapes to R.string.category_landscapes,
        FootageCategory.Underwater to R.string.category_underwater,
        FootageCategory.Space to R.string.category_space,
        FootageCategory.Cities to R.string.category_cities,
        FootageCategory.Mac to R.string.category_mac,
    )
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 6.dp)) {
        items(options, key = { it.second }) { (category, label) ->
            val isSelected = selected == category
            val radius by animateDpAsState(
                if (isSelected) 20.dp else 12.dp,
                spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
                label = "chipRadius",
            )
            val container by animateColorAsState(
                if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                label = "chipColor",
            )
            val interaction = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .pressScale(interaction)
                    .clip(RoundedCornerShape(radius))
                    .background(container)
                    .clickable(interactionSource = interaction, indication = null) { onCategory(category) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(
                    stringResource(label),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun FootageCard(
    footage: Footage,
    state: MainUiState,
    selected: Boolean,
    playing: Boolean,
    hero: Boolean = false,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val container = remember(context) { (context.applicationContext as WallperApplication).container }
    val covers = container.covers
    val motion = container.motionThumbs
    // Apple's own stills are tiny (356 px): until something sharp exists they are shown blurred.
    var cover by remember(footage.id) {
        mutableStateOf<Any?>(
            if (hero) {
                covers.cached(footage) ?: motion.poster(footage.id) ?: footage.coverUrl
            } else {
                motion.poster(footage.id) ?: covers.cached(footage) ?: footage.coverUrl
            },
        )
    }
    var loop by remember(footage.id) { mutableStateOf(if (hero) null else motion.cached(footage.id)) }
    LaunchedEffect(footage.id) {
        if (hero) {
            // Full-width card: a 2560 px frame of the master, the live stream plays over it.
            if (covers.cached(footage) == null) covers.cover(footage)?.let { cover = it }
        } else if (loop == null) {
            val made = motion.get(footage.id, footage.previewUrl ?: footage.masterUrl)
            if (made != null) {
                motion.poster(footage.id)?.let { cover = it }
                loop = made
            } else if (cover !is File) {
                covers.cover(footage)?.let { cover = it }
            }
        }
    }
    val video = when {
        !playing -> null
        hero -> footage.inlineSpec()
        else -> loop?.let { LoopSpec(Uri.fromFile(it), loopMs = 0, fadeMs = 0) }
    }
    MediaCard(
        cover = cover,
        video = video,
        title = footage.title,
        caption = footage.collection?.takeIf { it != footage.title },
        selected = selected,
        hero = hero,
        onClick = onClick,
        badge = { state.runningExport(footage.id)?.let { ProgressBadge(it.progress) } },
    )
}

@Composable
private fun ClipCard(
    clip: Clip,
    roles: Set<WallpaperRole>,
    selected: Boolean,
    playing: Boolean,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val container = remember(context) { (context.applicationContext as WallperApplication).container }
    val library = container.library
    val motion = container.motionThumbs
    // The clip itself is 4K: a light loop cut from it lets several cards play at once.
    val motionId = "clip-${clip.id}"
    var loop by remember(clip.id) { mutableStateOf(motion.cached(motionId)) }
    LaunchedEffect(clip.id) {
        if (loop == null) loop = motion.get(motionId, Uri.fromFile(library.fileOf(clip)).toString())
    }
    MediaCard(
        cover = library.coverOf(clip),
        video = if (playing) loop?.let { LoopSpec(Uri.fromFile(it), loopMs = 0, fadeMs = 0) } else null,
        title = clip.title,
        caption = (if (clip.isUhd) "4K · " else "") + formatDuration(clip.loopMs),
        selected = selected,
        hero = false,
        onClick = onClick,
        badge = {
            when {
                roles.size == 2 || (roles.isNotEmpty() && !WallpaperRoles.separateLockSupported) ->
                    StatusBadge(R.string.badge_active)
                WallpaperRole.Home in roles -> StatusBadge(R.string.badge_home)
                WallpaperRole.Lock in roles -> StatusBadge(R.string.badge_lock)
                else -> Unit
            }
        },
    )
}

@Composable
private fun MediaCard(
    cover: Any?,
    video: LoopSpec?,
    title: String,
    caption: String?,
    selected: Boolean,
    hero: Boolean,
    onClick: () -> Unit,
    badge: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val shape = if (hero) HeroShape else CardShape
    Box(
        Modifier
            .pressScale(interaction, pressed = 0.97f)
            .aspectRatio(if (hero) HERO_RATIO else CARD_RATIO)
            .clip(shape)
            .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
    ) {
        Box(Modifier.fillMaxSize().shimmer())
        if (cover != null) {
            // A remote URL is Apple's small still: soften it instead of showing pixels.
            val lowRes = cover is String
            AsyncImage(
                model = cover,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().then(if (lowRes) Modifier.blur(LOW_RES_BLUR) else Modifier),
            )
        }
        if (video != null) InlineVideo(video)
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(if (hero) 160.dp else 104.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)))),
        )
        Column(Modifier.align(Alignment.BottomStart).padding(if (hero) 20.dp else 14.dp)) {
            if (caption != null) {
                Text(caption.uppercase(), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.75f))
            }
            Text(
                title,
                style = if (hero) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(Modifier.align(Alignment.TopEnd).padding(10.dp)) { badge() }
    }
}

/** Floating toolbar (Material 3 Expressive): tabs in a pill plus a separate add action. */
@Composable
private fun BottomToolbar(tab: HomeTab, onTab: (HomeTab) -> Unit, onImport: () -> Unit, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        GlassSurface(RoundedCornerShape(50), tint = Color(0xFF16161D)) {
            Row(Modifier.padding(5.dp)) {
                ToolbarTab(stringResource(R.string.tab_gallery), tab == HomeTab.Apple) { onTab(HomeTab.Apple) }
                ToolbarTab(stringResource(R.string.tab_mine), tab == HomeTab.Mine) { onTab(HomeTab.Mine) }
            }
        }
        val interaction = remember { MutableInteractionSource() }
        Box(
            Modifier
                .pressScale(interaction)
                .size(56.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.primary)
                .clickable(interactionSource = interaction, indication = null, onClick = onImport),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Add,
                contentDescription = stringResource(R.string.action_import),
                tint = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

@Composable
private fun ToolbarTab(label: String, selected: Boolean, onClick: () -> Unit) {
    val container by animateColorAsState(if (selected) Color.White else Color.Transparent, label = "tabColor")
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .pressScale(interaction)
            .clip(RoundedCornerShape(50))
            .background(container)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 13.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) Color.Black else Color.White)
    }
}

@Composable
private fun StatusBadge(label: Int) {
    Row(
        Modifier
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).background(Color(0xFF5BE38A), CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = Color.White)
    }
}

@Composable
private fun ProgressBadge(progress: Float) {
    Box(Modifier.size(40.dp).background(Color.Black.copy(alpha = 0.55f), CircleShape), contentAlignment = Alignment.Center) {
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
private const val MAX_LIVE_CARDS = 6
private val LOW_RES_BLUR = 14.dp
private const val HERO_RATIO = 4f / 5f
private const val PREVIEW_START_MS = 30_000L
private const val PREVIEW_LENGTH_MS = 20_000L

/**
 * What a card plays in place: Apple's light stream when there is one, otherwise the
 * 4K 240 fps master through the layer-dropping decoder, capped at 30 fps for a card.
 */
private fun Footage.inlineSpec(): LoopSpec {
    val light = previewUrl
    return LoopSpec(
        uri = Uri.parse(light ?: masterUrl),
        loopMs = PREVIEW_LENGTH_MS,
        fadeMs = 0,
        clipStartMs = PREVIEW_START_MS,
        clipEndMs = PREVIEW_START_MS + PREVIEW_LENGTH_MS,
        highFrameRate = light == null && highFrameRate,
        maxFps = 30.0,
    )
}
private val CardShape = RoundedCornerShape(24.dp)
private val HeroShape = RoundedCornerShape(32.dp)
