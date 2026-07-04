//
//  ImageEdit.kt (webMain)
//  ImageEditKmp
//
//  A self-contained screen for selecting an image from
//  ProjectService.getAllGenerations() and signaling the host to run the edit
//  action. Compose Multiplatform (js + wasmJs) port of the SwiftUI ImageEdit
//  ContentView.
//
//  ## Contract
//  The view owns the argument-handoff. When the user taps the "Edit image"
//  CTA the view:
//  1. Writes the selected filename via ProjectService.setImageEdit(_:).
//  2. Calls onTrigger.
//
//  onTrigger is a pure "go" signal — it carries no payload. Read the filename
//  downstream with ProjectService.getImageEdit(). This keeps the view
//  decoupled from whatever action follows it.
//
//  ## Features
//  - Imports through the browser's file picker (showOpenFilePicker). Images
//    land in the app's OPFS documents root via
//    ProjectService.saveFile(_:named:).
//  - Grid is bucketed by date (Today, Yesterday, Last 7 / 30 Days, Older)
//    using file mtime. Lazy thumbnails are decoded on demand and cached.
//  - Restores the previous selection from ProjectService.getImageEdit() on
//    appear so the CTA is already armed when the user returns.
//  - Long-press a tile for a Delete action.
//

@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package market.femi.imageedit

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.js.js
import kotlin.math.round
import kotlin.random.Random
import kotlinx.coroutines.launch
import market.femi.api.ProjectService
import market.femi.api.decodeImageBitmap
import market.femi.api.deleteFile
import market.femi.api.jsDateNow
import market.femi.api.pickFiles
import market.femi.api.readFileBytes
import market.femi.api.readFileMtime
import market.femi.api.toByteArray

// MARK: - Models

private data class ImageFile(
    /** Filename under the ProjectService documents root. */
    val name: String,
    /** "Path" resolved through ProjectService.getUrl — on web it is the filename. */
    val path: String,
    /** File mtime in epoch millis (0 = missing → distant past → Older). */
    val modifiedAt: Double,
)

private data class DateGroup(
    val bucket: DateBucket,
    val files: List<ImageFile>,
)

// MARK: - Date bucketing

/**
 * Local-midnight timestamp (millis) for a moment — Calendar.startOfDay(for:).
 * Date.setHours mutates in the browser's local timezone and returns the new
 * epoch millis.
 */
private fun startOfDayMs(ms: Double): Double = js("new Date(ms).setHours(0, 0, 0, 0)")

private enum class DateBucket(val title: String) {
    Today("Today"),
    Yesterday("Yesterday"),
    LastWeek("Last 7 Days"),
    LastMonth("Last 30 Days"),
    Older("Older");

    companion object {
        /**
         * Mirrors the Swift DateBucket.from(_:) — calendar-day distance
         * between the file's day and today. Rounded so a DST hour shift
         * can't skew the bucket.
         */
        fun from(mtimeMs: Double): DateBucket {
            val days = round((startOfDayMs(jsDateNow()) - startOfDayMs(mtimeMs)) / 86_400_000.0)
            return when {
                days <= 0.0 -> Today
                days == 1.0 -> Yesterday
                days <= 7.0 -> LastWeek
                days <= 30.0 -> LastMonth
                else -> Older
            }
        }
    }
}

// MARK: - Thumbnail cache
//
// NSCache(countLimit: 500) equivalent: a count-bounded LRU keyed by the
// ProjectService "path" (on web, the filename). Bytes are read from OPFS and
// decoded through Skia on demand.

private object ThumbCache {
    private const val CountLimit = 500
    private val cache = LinkedHashMap<String, ImageBitmap>()

    fun get(path: String): ImageBitmap? {
        val hit = cache.remove(path) ?: return null
        cache[path] = hit // refresh recency
        return hit
    }

    fun put(path: String, bitmap: ImageBitmap) {
        cache.remove(path)
        cache[path] = bitmap
        while (cache.size > CountLimit) {
            cache.remove(cache.keys.first())
        }
    }

    fun invalidate(path: String) {
        cache.remove(path)
    }
}

/**
 * Read + decode a thumbnail for an OPFS file. The Swift Tile decodes through
 * ImageIO with a max-pixel-size budget; the web api decodes the full image via
 * Skia (decodeImageBitmap) — the tile then crops/scales at draw time.
 */
private suspend fun loadThumb(path: String): ImageBitmap? {
    ThumbCache.get(path)?.let { return it }
    val decoded = runCatching {
        decodeImageBitmap(readFileBytes(path).toByteArray())
    }.getOrNull()
    if (decoded != null) ThumbCache.put(path, decoded)
    return decoded
}

// MARK: - iOS system palette
//
// The Swift screen renders in UIKit semantic colors that adapt to light/dark.
// Mirror the exact system values so the port follows the system theme too.

private data class Palette(
    val systemBackground: Color,
    val label: Color,
    val secondaryLabel: Color,
    val secondarySystemFill: Color,
    val separator: Color,
    /** Default iOS accent — glass/glassProminent buttons tint with it. */
    val accent: Color,
    /** systemRed — the destructive context-menu action. */
    val destructive: Color,
)

private val LightPalette = Palette(
    systemBackground = Color.White,
    label = Color.Black,
    secondaryLabel = Color(0x993C3C43),
    secondarySystemFill = Color(0x29787880),
    separator = Color(0x4A3C3C43),
    accent = Color(0xFF007AFF),
    destructive = Color(0xFFFF3B30),
)

private val DarkPalette = Palette(
    systemBackground = Color.Black,
    label = Color.White,
    secondaryLabel = Color(0x99EBEBF5),
    secondarySystemFill = Color(0x52787880),
    separator = Color(0x99545458),
    accent = Color(0xFF0A84FF),
    destructive = Color(0xFFFF453A),
)

// MARK: - Constants

private val imageExtensions = setOf(
    "jpg", "jpeg", "png", "heic", "heif", "gif", "tiff", "webp", "bmp",
)

/** spring(response: 0.32, dampingFraction: 0.85) — stiffness = (2π/T)². */
private const val SelectSpringStiffness = 386f

/** spring(response: 0.45, dampingFraction: 0.85) — the bottom bar transition. */
private const val BarSpringStiffness = 195f

private const val SpringDamping = 0.85f

/**
 * GridItem(.adaptive(minimum: 108, maximum: 180)) — GridCells.Adaptive plus
 * the SwiftUI maximum: fit as many min-width columns as possible, then grow
 * each column no wider than max, leaving whitespace on very wide layouts.
 */
private class AdaptiveWithMax(
    private val minSize: Dp,
    private val maxSize: Dp,
) : GridCells {
    override fun Density.calculateCrossAxisCellSizes(
        availableSize: Int,
        spacing: Int,
    ): List<Int> {
        val min = minSize.roundToPx()
        val max = maxSize.roundToPx()
        val count = maxOf((availableSize + spacing) / (min + spacing), 1)
        val size = minOf((availableSize - spacing * (count - 1)) / count, max)
        return List(count) { size }
    }

    override fun hashCode(): Int = minSize.hashCode() * 31 + maxSize.hashCode()

    override fun equals(other: Any?): Boolean =
        other is AdaptiveWithMax && other.minSize == minSize && other.maxSize == maxSize
}

/**
 * `UUID().uuidString` equivalent — a random (v4) UUID rendered uppercase in
 * the canonical 8-4-4-4-12 form, matching the Swift import filename scheme.
 */
private fun randomUuidString(): String {
    val bytes = ByteArray(16).also { Random.nextBytes(it) }
    bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x40).toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()
    val hex = bytes.joinToString("") {
        (it.toInt() and 0xFF).toString(16).padStart(2, '0')
    }
    return buildString {
        append(hex, 0, 8); append('-')
        append(hex, 8, 12); append('-')
        append(hex, 12, 16); append('-')
        append(hex, 16, 20); append('-')
        append(hex, 20, 32)
    }.uppercase()
}

// MARK: - ImageEdit (ContentView)

/**
 * Library entry composable — port of the SwiftUI ContentView.
 *
 * @param onTrigger Pure "go" signal called after the view sets the filename
 *   on ProjectService. Default is null. Read the selected filename downstream
 *   with ProjectService.getImageEdit().
 */
@Composable
fun ImageEdit(onTrigger: (() -> Unit)? = null) {
    val scope = rememberCoroutineScope()
    val dark = isSystemInDarkTheme()
    val palette = if (dark) DarkPalette else LightPalette

    var groups by remember { mutableStateOf(emptyList<DateGroup>()) }
    var selectedFilename by remember { mutableStateOf<String?>(null) }
    var isImporting by remember { mutableStateOf(false) }

    // MARK: Logic

    /**
     * Mirrors the Swift reload(): filter generations to image extensions,
     * sort by mtime descending, bucket by date, then reconcile the selection
     * with ProjectService.getImageEdit().
     */
    suspend fun reload() {
        val entries = ProjectService.getAllGenerations()
            .map { it.substringAfterLast('/') }
            .filter { imageExtensions.contains(it.substringAfterLast('.', "").lowercase()) }
            .map { name ->
                // getUrl on web returns just the filename; mtime comes from
                // the OPFS file entry (missing file → 0 → Older).
                val path = ProjectService.getUrl(name)
                ImageFile(name = name, path = path, modifiedAt = readFileMtime(path))
            }
            .sortedByDescending { it.modifiedAt }

        val bucketMap = HashMap<DateBucket, MutableList<ImageFile>>()
        for (file in entries) {
            bucketMap.getOrPut(DateBucket.from(file.modifiedAt)) { mutableListOf() }.add(file)
        }
        groups = DateBucket.entries.mapNotNull { bucket ->
            val files = bucketMap[bucket]
            if (files.isNullOrEmpty()) null else DateGroup(bucket, files)
        }

        val allNames = entries.mapTo(HashSet()) { it.name }
        val current = selectedFilename
        if (current == null) {
            val stored = ProjectService.getImageEdit()
            if (stored != null && allNames.contains(stored)) {
                selectedFilename = stored
            }
        } else if (!allNames.contains(current)) {
            selectedFilename = null
        }
    }

    /** sensoryFeedback(.selection) has no web equivalent — just the toggle. */
    fun select(name: String) {
        selectedFilename = if (selectedFilename == name) null else name
    }

    fun delete(file: ImageFile) {
        scope.launch {
            // try? FileManager.default.removeItem(at:) → OPFS removeEntry.
            runCatching { deleteFile(file.name) }
            ThumbCache.invalidate(file.path)
            if (selectedFilename == file.name) {
                ProjectService.clearImageEdit()
            }
            // reload() drops the (now missing) name from the grid and clears
            // the selection — same as the Swift withAnimation { reload() }.
            reload()
        }
    }

    /**
     * fileImporter(allowedContentTypes: [.image], allowsMultipleSelection:
     * true) → showOpenFilePicker. pickFiles() stages each chosen file into
     * OPFS under its own name; re-save the bytes through
     * ProjectService.saveFile under the img-UUID name (the Swift naming
     * scheme), then drop the staging copy.
     */
    fun importFromPicker() {
        scope.launch {
            // The picker promise rejects when the user cancels — treat as
            // no selection, same as the Swift guard on the importer result.
            val picked = runCatching { pickFiles() }.getOrDefault(emptyList())
            if (picked.isEmpty()) return@launch
            isImporting = true
            var lastImported: String? = null
            for (original in picked) {
                val data = runCatching {
                    readFileBytes(original).toByteArray()
                }.getOrNull()
                if (data == null) {
                    runCatching { deleteFile(original) }
                    continue
                }
                val ext = original.substringAfterLast('.', "").ifEmpty { "jpg" }
                val name = "img-${randomUuidString()}.$ext"
                ProjectService.saveFile(data, named = name)
                runCatching { deleteFile(original) }
                lastImported = name
            }
            reload()
            lastImported?.let { selectedFilename = it }
            isImporting = false
        }
    }

    /**
     * The CTA. Order matters and matches the Swift contract: write the
     * filename first, then the payload-free signal. (sensoryFeedback(.success)
     * has no web equivalent.)
     */
    fun trigger() {
        val name = selectedFilename ?: return
        scope.launch {
            ProjectService.setImageEdit(name)
            onTrigger?.invoke()
        }
    }

    LaunchedEffect(Unit) { reload() }

    // MARK: Layout

    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(palette.systemBackground),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            ) {
                LazyVerticalGrid(
                    columns = AdaptiveWithMax(minSize = 108.dp, maxSize = 180.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                ) {
                    item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
                        Header(
                            palette = palette,
                            isImporting = isImporting,
                            onAdd = { importFromPicker() },
                            // Header keeps the Swift 24dp gutter: 16 from the
                            // grid content padding + 8 here.
                            modifier = Modifier.padding(
                                start = 8.dp,
                                end = 8.dp,
                                top = 12.dp,
                                bottom = 24.dp,
                            ),
                        )
                    }
                    if (groups.isEmpty()) {
                        item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                            EmptyState(
                                palette = palette,
                                onAdd = { importFromPicker() },
                                modifier = Modifier.padding(top = 80.dp),
                            )
                        }
                    } else {
                        for (group in groups) {
                            item(
                                key = "bucket-${group.bucket.name}",
                                span = { GridItemSpan(maxLineSpan) },
                            ) {
                                SectionHeader(
                                    group = group,
                                    palette = palette,
                                    modifier = Modifier.animateItem(),
                                )
                            }
                            items(group.files, key = { it.name }) { file ->
                                Tile(
                                    file = file,
                                    isSelected = selectedFilename == file.name,
                                    palette = palette,
                                    onTap = { select(file.name) },
                                    onDelete = { delete(file) },
                                    modifier = Modifier.animateItem(),
                                )
                            }
                        }
                    }
                }

                // safeAreaInset(edge: .bottom) — the CTA slides in from the
                // bottom edge combined with opacity, spring(0.45, 0.85).
                AnimatedVisibility(
                    visible = selectedFilename != null,
                    enter = slideInVertically(
                        animationSpec = spring(
                            dampingRatio = SpringDamping,
                            stiffness = BarSpringStiffness,
                            visibilityThreshold = IntOffset.VisibilityThreshold,
                        ),
                    ) { it } + fadeIn(),
                    exit = slideOutVertically(
                        animationSpec = spring(
                            dampingRatio = SpringDamping,
                            stiffness = BarSpringStiffness,
                            visibilityThreshold = IntOffset.VisibilityThreshold,
                        ),
                    ) { it } + fadeOut(),
                ) {
                    BottomBar(palette = palette, onEdit = { trigger() })
                }
            }
        }
    }
}

// MARK: - Header

@Composable
private fun Header(
    palette: Palette,
    isImporting: Boolean,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "Image Edit",
                fontSize = 34.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.4).sp,
                color = palette.label,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = "Select an image to edit",
                fontSize = 15.sp,
                color = palette.secondaryLabel,
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        // .buttonStyle(.glass) 44×44 — translucent circle, accent-tinted plus.
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(palette.secondarySystemFill)
                .clickable(enabled = !isImporting) { onAdd() }
                .semantics {
                    contentDescription =
                        if (isImporting) "Importing images" else "Add image from Files"
                },
            contentAlignment = Alignment.Center,
        ) {
            if (isImporting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = palette.secondaryLabel,
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null,
                    tint = palette.accent,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

// MARK: - Section header

@Composable
private fun SectionHeader(
    group: DateGroup,
    palette: Palette,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .padding(top = 22.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = group.bucket.title,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            color = palette.label,
            modifier = Modifier
                .alignByBaseline()
                .semantics { heading() },
        )
        Text(
            text = "${group.files.size}",
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = palette.secondaryLabel,
            // monospacedDigit() — tabular figures.
            style = TextStyle(fontFeatureSettings = "tnum"),
            modifier = Modifier
                .alignByBaseline()
                .semantics { contentDescription = "${group.files.size} images" },
        )
        Spacer(modifier = Modifier.weight(1f))
    }
}

// MARK: - Empty state

@Composable
private fun EmptyState(
    palette: Palette,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(palette.secondarySystemFill)
                .clearAndSetSemantics {},
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.PhotoLibrary,
                contentDescription = null,
                tint = palette.secondaryLabel,
                modifier = Modifier.size(28.dp),
            )
        }
        Column(
            modifier = Modifier.padding(horizontal = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "No images yet",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = palette.label,
            )
            Text(
                text = "Pick from Files, iCloud Drive, or any provider.",
                fontSize = 13.sp,
                color = palette.secondaryLabel,
                textAlign = TextAlign.Center,
            )
        }
        // .buttonStyle(.glassProminent) — accent capsule with white label.
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(palette.accent)
                .clickable { onAdd() }
                .semantics { contentDescription = "Add image from Files" }
                .padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = "Add image",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
        }
    }
}

// MARK: - Bottom bar

@Composable
private fun BottomBar(
    palette: Palette,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 12.dp),
    ) {
        // Accessibility: label "Edit image"; the click action triggers the
        // edit flow with the selected image (Swift accessibilityHint).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(CircleShape)
                .background(palette.accent)
                .clickable(onClickLabel = "Edit image") { onEdit() }
                .padding(vertical = 12.dp)
                .heightIn(min = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Edit image",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

// MARK: - Tile

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Tile(
    file: ImageFile,
    isSelected: Boolean,
    palette: Palette,
    onTap: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(18.dp)
    var image by remember(file.path) { mutableStateOf(ThumbCache.get(file.path)) }
    var menuOpen by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isSelected) 0.97f else 1f,
        animationSpec = spring(dampingRatio = SpringDamping, stiffness = SelectSpringStiffness),
        label = "tileScale",
    )

    LaunchedEffect(file.path) {
        if (image == null) {
            image = loadThumb(file.path)
        }
    }

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(palette.secondarySystemFill)
            .border(
                width = if (isSelected) 2.dp else 0.5.dp,
                color = if (isSelected) palette.label else palette.separator,
                shape = shape,
            )
            .combinedClickable(
                onClick = onTap,
                onLongClick = { menuOpen = true },
                onLongClickLabel = "Options",
            )
            // "Image, selected" / "Image" + selected trait; long press for
            // options is exposed through the custom long-click action above.
            .semantics {
                contentDescription = if (isSelected) "Image, selected" else "Image"
                selected = isSelected
            },
    ) {
        image?.let { img ->
            Image(
                bitmap = img,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (isSelected) {
            // Selection overlay — checkmark badge in the top-right corner.
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(palette.label)
                    .clearAndSetSemantics {},
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = palette.systemBackground,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
        // contextMenu — destructive Delete.
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("Delete", color = palette.destructive) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = null,
                        tint = palette.destructive,
                    )
                },
                onClick = {
                    menuOpen = false
                    onDelete()
                },
            )
        }
    }
}
