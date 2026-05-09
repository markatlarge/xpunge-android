# xPunge Android SDK

On-device NSFW detection for Android. No uploads. No cloud. Pure margin.

**[Get a free API key →](https://xpunge-backend.onrender.com/dashboard/)**

---

## Installation

### Gradle (GitHub Packages)

Add the GitHub Packages repository to your project-level `build.gradle`:

```groovy
repositories {
    maven {
        url = uri("https://maven.pkg.github.com/markatlarge/xpunge-android")
        credentials {
            username = project.findProperty("gpr.user") ?: System.getenv("GITHUB_USERNAME")
            password = project.findProperty("gpr.key")  ?: System.getenv("GITHUB_TOKEN")
        }
    }
}
```

Add the dependency to your app-level `build.gradle`:

```groovy
dependencies {
    implementation 'com.xpunge:xpunge-android:0.1.0'
}
```

> **Note:** A GitHub account with read access to the `markatlarge/xpunge-android` repo is required
> to resolve the package. Set `gpr.user` and `gpr.key` (a GitHub personal access token with
> `read:packages` scope) in your `~/.gradle/gradle.properties`.

---

## Quick Start

```kotlin
import com.xpunge.android.XpungeDetector
import com.xpunge.android.XpungeException

val detector = XpungeDetector(context)

// Initialize once (validates your API key and loads the model)
try {
    detector.initialize("xp1.YOUR_API_KEY")
} catch (e: XpungeException) {
    Log.e("xPunge", "Init failed: ${e.message}")
}

// Analyze an image from a file path
val detections = detector.analyzeFile("/path/to/image.jpg")
for (d in detections) {
    Log.d("xPunge", "${d.label} ${(d.confidence * 100).toInt()}% at (${d.x}, ${d.y})")
}

// Or from raw bytes
val detections = detector.analyzeImage(imageByteArray)

// Clean up when done
detector.dispose()
```

---

## API Reference

### `XpungeDetector(context: Context)`

Create an instance. One instance per app is typical — hold it in a ViewModel or Application class.

### `initialize(apiKey: String)`

Validates the API key and loads the model into memory. Call once before any detection.
Throws `XpungeException` on failure (bad key, wrong package, expired key).

### `analyzeImage(imageBytes: ByteArray): List<Detection>`

Runs detection on raw image bytes (JPEG, PNG, HEIF, WebP all supported).
Throws `XpungeException` if not initialized or image cannot be decoded.

### `analyzeFile(filePath: String): List<Detection>`

Runs detection on an image file. Handles EXIF rotation automatically.
Throws `XpungeException` if not initialized or file cannot be decoded.

### `tier: String`

`"free"` or the paid tier name after successful initialization.
Free tier returns detections with bounding box coordinates set to `0.0`.

### `isInitialized: Boolean`

`true` after a successful `initialize()` call.

### `dispose()`

Releases the TFLite interpreter. Call when the detector is no longer needed.

---

### `Detection`

```kotlin
data class Detection(
    val label: String,      // "breast" | "penis" | "anus" | "rear" | "vagina"
    val confidence: Double, // 0.0–1.0
    val x: Double,          // bounding box left edge, normalized 0–1 (0.0 on free tier)
    val y: Double,          // bounding box top edge,  normalized 0–1 (0.0 on free tier)
    val width: Double,      // normalized 0–1                         (0.0 on free tier)
    val height: Double,     // normalized 0–1                         (0.0 on free tier)
)
```

---

## Requirements

- Android API 24+ (Android 7.0)
- `minSdkVersion 24` in your app's `build.gradle`

---

## Pricing

| Tier | Price | Images/month |
|---|---|---|
| Free | $0 | 1,000 |
| Basic | $29/mo | 50,000 |
| Pro | $79/mo | 200,000 |
| Growth | $149/mo | 500,000 |
| Scale | $499/mo | 5,000,000 |
| Enterprise | Custom | 5M+ |

[Sign up and get a free key →](https://xpunge-backend.onrender.com/dashboard/)

---

## License

Usage is subject to your xPunge subscription agreement.
See [terms](https://xpunge.markatlarge.com/terms/) for details.
