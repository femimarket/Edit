//
//  ImageEdit.kt
//  AndroidImageEdit
//
//  A self-contained screen for selecting an image from
//  ProjectService.getAllGenerations() and signaling the host to run the edit
//  action. Compose port of the SwiftUI ImageEdit ContentView.
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
//  - Imports from the system document picker (any installed provider).
//    Images land in the app's documents root via
//    ProjectService.saveFile(_:named:).
//  - Grid is bucketed by date (Today, Yesterday, Last 7 / 30 Days, Older)
//    using file mtime. Lazy thumbnails are decoded off-main and cached.
//  - Restores the previous selection from ProjectService.getImageEdit() on
//    appear so the CTA is already armed when the user returns.
//  - Long-press a tile for a Delete action; uses haptic feedback on
//    selection and trigger.
//

package market.femi.imageedit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.OpenableColumns
import android.util.LruCache
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.util.Calendar
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import market.femi.api.ProjectService

// MARK: - Models

private data class ImageFile(
    /** Filename under the ProjectService documents root. */
    val name: String,
    /** Absolute on-disk path resolved through ProjectService.getUrl. */
    val path: String,
    /** File mtime in epoch millis (0 = missing → distant past → Older). */
    val modifiedAt: Long,
)

private data class DateGroup(
    val bucket: DateBucket,
    val files: List<ImageFile>,
)

// MARK: - Date bucketing

private fun startOfDay(millis: Long): Long {
    val cal = Calendar.getInstance()
    cal.timeInMillis = millis
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}

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
        fun from(millis: Long): DateBucket {
            val days = Math.round(
                (startOfDay(System.currentTimeMillis()) - startOfDay(millis)) / 86_400_000.0,
            )
            return when {
                days == 0L -> Today
                days == 1L -> Yesterday
                days <= 7L -> LastWeek
                days <= 30L -> LastMonth
                else -> Older
            }
        }
    }
}

// MARK: - Thumbnail cache
//
// NSCache(countLimit: 500) equivalent: a count-bounded LruCache keyed by the
// absolute file path. Decodes happen off-main with an inSampleSize chosen so
// the longest side lands near max(320, 180 × density) pixels — the same
// budget the Swift Tile passes to ImageIO.

private object ThumbCache {
    private val cache = LruCache<String, Bitmap>(500)

    fun get(path: String): Bitmap? = cache.get(path)

    fun put(path: String, bitmap: Bitmap) {
        cache.put(path, bitmap)
    }

    fun invalidate(path: String) {
        cache.remove(path)
    }
}

/**
 * kCGImageSourceCreateThumbnailWithTransform: true — bake the EXIF
 * orientation into the pixels so camera JPEGs render upright, exactly like
 * the ImageIO thumbnail on iOS. Unknown/absent orientation is a no-op.
 */
private fun applyExifOrientation(path: String, bitmap: Bitmap): Bitmap {
    val orientation = runCatching {
        ExifInterface(path).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            matrix.postRotate(90f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.postRotate(270f)
            matrix.postScale(-1f, 1f)
        }
        else -> return bitmap
    }
    return runCatching {
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }.getOrDefault(bitmap)
}

private suspend fun loadThumb(path: String, maxPixelSize: Int): Bitmap? {
    ThumbCache.get(path)?.let { return it }
    val decoded = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        if (maxDim > 0) {
            while (maxDim / (sample * 2) >= maxPixelSize) sample *= 2
        }
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?.let { applyExifOrientation(path, it) }
    }
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
 * Extension for an imported document — the display-name extension, "jpg" when
 * empty. Mirrors the Swift `url.pathExtension.isEmpty ? "jpg" : url.pathExtension`
 * (the display name is the SAF analog of the picked URL's last path component).
 */
private fun fileExtension(context: Context, uri: Uri): String {
    var displayName: String? = null
    runCatching {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) displayName = cursor.getString(0)
            }
    }
    return displayName?.substringAfterLast('.', "").orEmpty().ifEmpty { "jpg" }
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
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val dark = isSystemInDarkTheme()
    val palette = if (dark) DarkPalette else LightPalette

    // ProjectService self-configures its documents root at process start
    // (api ≥4.11.0 ships an androidx.startup initializer) — the screen
    // assumes storage exists, exactly like the Swift original.

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
        // The suspend ProjectService calls are blocking JNI passthroughs on
        // Android — hop the whole listing to Dispatchers.IO.
        val entries = withContext(Dispatchers.IO) {
            ProjectService.getAllGenerations()
                .map { it.substringAfterLast('/') }
                .filter { imageExtensions.contains(it.substringAfterLast('.', "").lowercase()) }
                .map { name ->
                    // getUrl returns a plain absolute file path (no scheme).
                    val path = ProjectService.getUrl(name)
                    ImageFile(name = name, path = path, modifiedAt = File(path).lastModified())
                }
                .sortedByDescending { it.modifiedAt }
        }

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
            val stored = withContext(Dispatchers.IO) { ProjectService.getImageEdit() }
            if (stored != null && allNames.contains(stored)) {
                selectedFilename = stored
            }
        } else if (!allNames.contains(current)) {
            selectedFilename = null
        }
    }

    /** sensoryFeedback(.selection) + spring-animated toggle. */
    fun select(name: String) {
        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
        selectedFilename = if (selectedFilename == name) null else name
    }

    fun delete(file: ImageFile) {
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { File(file.path).delete() } }
            ThumbCache.invalidate(file.path)
            if (selectedFilename == file.name) {
                withContext(Dispatchers.IO) { ProjectService.clearImageEdit() }
            }
            // reload() drops the (now missing) name from the grid and clears
            // the selection — same as the Swift withAnimation { reload() }.
            reload()
        }
    }

    fun handleFileImport(uris: List<Uri>) {
        if (uris.isEmpty()) return
        isImporting = true
        scope.launch {
            // Task.detached(priority: .userInitiated) — the whole read + save
            // loop runs off-main (saveFile is a blocking JNI disk write); only
            // the final state mutation returns to Main, like MainActor.run.
            val lastImported = withContext(Dispatchers.IO) {
                var last: String? = null
                for (uri in uris) {
                    val data = runCatching {
                        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    }.getOrNull() ?: continue
                    val ext = fileExtension(context, uri)
                    val name = "img-${UUID.randomUUID().toString().uppercase()}.$ext"
                    ProjectService.saveFile(data, named = name)
                    last = name
                }
                last
            }
            reload()
            lastImported?.let { selectedFilename = it }
            isImporting = false
        }
    }

    /**
     * The CTA. Order matters and matches the Swift contract: write the
     * filename first, then haptic success, then the payload-free signal.
     */
    fun trigger() {
        val name = selectedFilename ?: return
        scope.launch {
            withContext(Dispatchers.IO) { ProjectService.setImageEdit(name) }
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            onTrigger?.invoke()
        }
    }

    // fileImporter(allowedContentTypes: [.image], allowsMultipleSelection: true)
    // → the system document picker filtered to images.
    val pickImages = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> handleFileImport(uris) }

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
                            onAdd = { pickImages.launch(arrayOf("image/*")) },
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
                                onAdd = { pickImages.launch(arrayOf("image/*")) },
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
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val shape = RoundedCornerShape(18.dp)
    var image by remember(file.path) {
        mutableStateOf(ThumbCache.get(file.path)?.asImageBitmap())
    }
    var menuOpen by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isSelected) 0.97f else 1f,
        animationSpec = spring(dampingRatio = SpringDamping, stiffness = SelectSpringStiffness),
        label = "tileScale",
    )

    LaunchedEffect(file.path) {
        if (image == null) {
            val pixelSize = maxOf(320, (180 * density.density).toInt())
            image = loadThumb(file.path, pixelSize)?.asImageBitmap()
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
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    menuOpen = true
                },
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

@Preview
@Composable
private fun ImageEditPreview() {
    ImageEdit()
}
