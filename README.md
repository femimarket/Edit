# ImageEdit

A cross-platform, self-contained UI component for selecting and editing images.

`ImageEdit` provides a unified image selection interface available on **iOS (SwiftUI)**, **Android (Jetpack Compose)**, and **Web (Compose Multiplatform)**. It manages the full lifecycle of image selection: importing from system pickers, displaying a date-bucketed grid of thumbnails, handling selection state, and signaling the host application to proceed with an edit action.

## Features

*   **Cross-Platform Consistency**: Identical logic and UI structure across iOS, Android, and Web.
*   **Date-Bucketed Grid**: Images are automatically grouped into sections: *Today*, *Yesterday*, *Last 7 Days*, *Last 30 Days*, and *Older*.
*   **Lazy Thumbnail Loading**: Thumbnails are decoded off the main thread and cached to ensure smooth scrolling.
*   **System File Picker Integration**:
    *   **iOS**: Uses `UIActivityViewController`/`PHPicker` via `fileImporter`.
    *   **Android**: Uses the system document picker (`OpenMultipleDocuments`).
    *   **Web**: Uses the File System Access API (`showOpenFilePicker`) with OPFS storage.
*   **State Persistence**: Restores the previously selected image on appearance.
*   **Haptic Feedback**: Provides tactile feedback on selection and trigger (iOS/Android).
*   **Dark Mode Support**: Automatically adapts to the system theme using semantic color palettes.

## Architecture

The project is structured as a multi-platform library with a shared core logic pattern implemented in platform-specific UI frameworks.

### Project Structure

```text
ImageEdit/
├── ImageEdit/                  # iOS SwiftUI Implementation
│   ├── ContentView.swift       # Main view logic, grid, and selection handling
│   └── ImageEditApp.swift      # iOS App entry point
├── Kmp/                        # Kotlin Multiplatform Implementation
│   ├── imageedit/
│   │   ├── src/androidMain/    # Android Jetpack Compose Implementation
│   │   │   └── market/femi/imageedit/ImageEdit.kt
│   │   └── src/webMain/        # Web Compose Multiplatform Implementation
│   │       └── market/femi/imageedit/ImageEdit.kt
│   └── demo/                   # Demo applications for Android
├── Tests/                      # Unit Tests
│   └── ImageEditTests/         # Swift Testing framework tests for DateBucket
└── Package.swift               # Swift Package Manager manifest
```

### Key Components

1.  **`ContentView` (iOS) / `ImageEdit` (KMP)**:
    *   The primary entry point.
    *   Manages the state of the grid (`groups`), selected filename (`selectedFilename`), and import status (`isImporting`).
    *   Handles the `onTrigger` callback, which is a pure "go" signal without payload.

2.  **`ProjectService`**:
    *   An external dependency (`swift-project-service`) that handles file I/O.
    *   **Contract**: The view writes the selected filename to `ProjectService.setImageEdit(_:)` before triggering. Downstream consumers read the filename via `ProjectService.getImageEdit()`.
    *   Files are stored in the app's `Documents/` directory (iOS) or equivalent storage (Android/Web).

3.  **`DateBucket`**:
    *   An enum (`today`, `yesterday`, `lastWeek`, `lastMonth`, `older`) that categorizes images based on their modification time (`mtime`).
    *   Logic is mirrored in Swift (`DateBucket.swift`), Kotlin Android (`ImageEdit.kt`), and Kotlin Web (`ImageEdit.kt`).

4.  **`Tile`**:
    *   A reusable component representing a single image in the grid.
    *   Handles thumbnail loading, caching, selection state, and long-press context menus (Delete).

## Installation

### iOS (Swift Package Manager)

Add the package to your `Package.swift` or Xcode project:

```swift
dependencies: [
    .package(url: "https://github.com/femimarket/swift-project-service", branch: "main"),
]
```

Include the `ImageEdit` target in your app's dependencies.

### Android & Web (Kotlin Multiplatform)

The KMP module is located in `Kmp/imageedit`. Import it into your `build.gradle.kts`:

```kotlin
dependencies {
    implementation(project(":imageedit"))
    // Ensure ProjectService is available
    implementation("market.femi.api:project-service:latest") 
}
```

## Usage

### iOS

Import the module and instantiate `ContentView` in your SwiftUI view hierarchy.

```swift
import SwiftUI
import ImageEdit

struct HostView: View {
    var body: some View {
        ContentView {
            // This block is called after the user selects an image and taps "Edit image".
            // The filename is now available via ProjectService.getImageEdit().
            runEditAction()
        }
    }
    
    func runEditAction() {
        if let filename = ProjectService.getImageEdit() {
            // Proceed with editing `filename`
        }
    }
}
```

### Android

Use the `ImageEdit` composable in your Jetpack Compose activity.

```kotlin
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ImageEditTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ImageEdit(onTrigger = {
                        // Triggered after selection.
                        // Read filename from ProjectService.getImageEdit()
                    })
                }
            }
        }
    }
}
```

### Web

The usage is identical to Android, as both use Compose Multiplatform.

```kotlin
@Composable
fun App() {
    ImageEdit(onTrigger = {
        // Handle trigger
    })
}
```

## How It Works

### 1. Initialization
On appear, the view calls `reload()` (iOS) or `LaunchedEffect(Unit) { reload() }` (KMP). This fetches all files from `ProjectService.getAllGenerations()`, filters for image extensions, and sorts them by modification date.

### 2. Date Bucketing
Files are grouped into `DateBucket` categories. The logic calculates the difference in days between the file's modification date and the current date to determine the bucket.

### 3. Thumbnail Loading
*   **iOS**: Uses `CGImageSourceCreateThumbnailAtIndex` with a max pixel size of `max(320, 180 * displayScale)`. Thumbnails are cached in an `NSCache`.
*   **Android**: Uses `BitmapFactory` with `inSampleSize` to decode down to the target size. EXIF orientation is applied to ensure images are upright. Cached in an `LruCache`.
*   **Web**: Reads bytes from OPFS and decodes via Skia (`decodeImageBitmap`). Cached in a `LinkedHashMap` (LRU).

### 4. Selection & Trigger
*   Tapping a tile selects it (haptic feedback on iOS/Android).
*   Tapping "Edit image" in the bottom bar:
    1.  Writes the filename to `ProjectService.setImageEdit(filename)`.
    2.  Triggers haptic success (iOS/Android).
    3.  Invokes the `onTrigger` closure.

### 5. Importing Images
*   Tapping the "+" button opens the system file picker.
*   Selected files are read and saved to `ProjectService` with a generated name (`img-<UUID>.<ext>`).
*   The grid refreshes, and the newly imported image is automatically selected.

## Non-Obvious Conventions

*   **Decoupled Trigger**: The `onTrigger` callback does not receive the filename. The view is responsible for writing the state to `ProjectService`. This keeps the view agnostic of the downstream edit logic.
*   **Security Scoped Resources (iOS)**: When importing from iCloud or other providers, the view handles `startAccessingSecurityScopedResource` and `stopAccessingSecurityScopedResource` to ensure file access.
*   **EXIF Orientation (Android)**: Android thumbnails explicitly apply EXIF orientation transforms to match iOS behavior, where `CGImageSource` handles this automatically.
*   **Web OPFS**: On Web, imported files are staged in the Origin Private File System (OPFS) before being saved to the `ProjectService` storage location.

## Testing

Run the Swift unit tests for the `DateBucket` logic:

```bash
swift test
```

This verifies the date bucketing boundaries (Today, Yesterday, Last 7/30 Days, Older).