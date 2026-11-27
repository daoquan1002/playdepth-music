# PlayDepth Coding Conventions & Architecture Map

This guide outlines the code structure, style conventions, safety invariants, and module layout used in PlayDepth.

---

## 1. File Templates

Every source file (`.kt`, `.h`, `.cpp`) starts with a short header docblock explaining its **purpose** and **why** (design invariants, threading rules, hardware quirks) rather than restating obvious code.

### Kotlin

```kotlin
/**
 * ComponentOrFileName — concise single-line summary.
 *
 * @remarks
 * Explain "why" (invariants, OS/hardware constraints, lifecycle tradeoffs).
 *
 * @see RelatedComponent
 */
package com.quandot.musicplayer.example

// 1. External SDK / Jetpack / third-party libraries
import android.content.Context
import androidx.compose.runtime.Composable

// 2. Internal modules
import com.quandot.musicplayer.audio.UsbDacManager

// 3. Domain models & theme tokens (last)
import com.quandot.musicplayer.model.Song
import com.quandot.musicplayer.ui.theme.AppTheme

// ═══ Constants ═══
private const val BUFFER_LIMIT = 4096

// ═══ Public API ═══
@Composable
fun ExampleComponent() { /* ... */ }

// ═══ Private Helpers ═══
private fun computeValue() { /* ... */ }
```

### C++17 (`app/src/main/cpp`)

```cpp
/**
 * module_name — concise single-line summary.
 *
 * @remarks
 * Explain "why" (usbdevfs timing, atomic ordering, RT thread safety).
 *
 * @see UsbDacNative
 */
#pragma once // headers only

// ═══ Includes ═══
#include "module_name.h" // matching header first in .cpp files
#include <atomic>
#include <cstdint>

// ═══ Constants ═══
constexpr int MAX_PACKET_SAMPLES = 1024;

// ═══ Implementation ═══
// ...
```

---

## 2. Core Guidelines

- **Import / Include Order:**
  - **Kotlin:** External libraries → internal modules → models & theme tokens.
  - **C++:** Matching `.h` (in `.cpp`) → project headers (`"..."`) → STL & Linux/NDK headers (`<...>`).
- **Section Banners:** Separate logical zones with `// ═══ Section Name ═══` (e.g., `Constants`, `Types / State`, `Public API`, `Private Helpers`).
- **Constants & Stability:** Use `UPPER_SNAKE_CASE` for constants (`const val`, `constexpr`). Never break persisted DataStore/SharedPreferences keys, SQLite schema columns, `Intent` actions, or JNI signatures.
- **Comments & Localization:** Write code comments and docblocks in **English**, focusing on *why*. Keep all user-facing text in `res/values*/strings.xml` using explicit positional format specifiers (`%1$s`, `%1$d`) and `<plurals>` for countable nouns.
- **Compose Performance:** Hoist state out of leaf composables, provide stable `key` lambdas in all `LazyColumn`/`LazyRow` lists, and keep I/O and bitmap decoding off the main thread.
- **Native Real-Time Audio Safety (`playdepth_usbdac`):** Inside the isochronous URB streaming loop, never allocate heap memory, acquire blocking mutexes, make JNI upcalls, or perform blocking log/file I/O. Communicate strictly through `SpscRingBuffer` and atomics.

---

## 3. Architecture Map

```text
app/src/
├── main/
│   ├── cpp/                                  # Native C++17 UAC 1.0/2.0/3.0 driver & JNI bridge
│   │   ├── CMakeLists.txt                    # NDK CMake config for libplaydepth_usbdac.so
│   │   ├── pd_log.h                          # Native logging & lock-free diagnostics helpers
│   │   ├── spsc_ring_buffer.h                # Lock-free single-producer/single-consumer ring buffer
│   │   ├── uac_descriptor.{h,cpp}            # UAC 1.0/2.0/3.0 descriptor & alt-setting parser
│   │   ├── usb_transport.{h,cpp}             # Linux usbdevfs ioctl & pre-allocated ISO URB pool
│   │   ├── clock_sync.{h,cpp}                # Async (10.14 / 16.16) & Adaptive/Sync clock engine
│   │   ├── audio_dsp_pipeline.{h,cpp}        # Format negotiation, DoP/DSD handling & DSP fallback
│   │   ├── usb_audio_driver.{h,cpp}          # Driver orchestrator, HW volume & RT ISO thread
│   │   └── usb_audio_jni.cpp                 # JNI entrypoints bound to UsbDacNative
│   │
│   ├── java/com/quandot/musicplayer/
│   │   ├── MusicApplication.kt               # Global Coil ImageLoader & memory cache setup
│   │   ├── WelcomeActivity.kt                # First-launch onboarding & permissions host
│   │   ├── MainActivity.kt                   # Edge-to-edge main activity & hardware volume routing
│   │   ├── audio/                            # Bit-perfect USB DAC pipeline & custom Media3 extractors
│   │   │   ├── AiffExtractor.kt              # Media3 extractor for AIFF/AIF/AIFF-C containers
│   │   │   ├── DsdExtractor.kt               # Media3 extractor for DSF & DFF (DSDIFF) DSD containers
│   │   │   ├── SilentAudioKeepAlive.kt       # Sub-audible keep-alive against aggressive OEM throttling
│   │   │   ├── SourceFormatTrackingAudioRenderer.kt # Preserves source PCM/DSD format metadata for routing
│   │   │   ├── UsbDacAudioSink.kt            # ExoPlayer AudioSink router (USB Direct vs DefaultAudioSink)
│   │   │   ├── UsbDacManager.kt              # USB device discovery, permission flow & HW volume state
│   │   │   ├── UsbDacNative.kt               # JNI declarations & native library loader
│   │   │   └── WavFloatFixExtractorsFactory.kt # Routes 32-bit float WAV, AIFF & DSD cleanly
│   │   ├── data/                             # SQLite persistence, preferences, metadata, artwork & lyrics
│   │   │   ├── scanner/                      # Hybrid MediaStore + direct filesystem library scanner
│   │   │   ├── AiffMetadataHelper.kt         # AIFF/AIFC metadata & audio-spec parser
│   │   │   ├── AlbumArtCache.kt              # Embedded/folder artwork disk & RAM LRU cache
│   │   │   ├── AlbumArtManager.kt            # Deduplicated album-key artwork request coordinator
│   │   │   ├── AudioCoverFetcher.kt          # Custom Coil Fetcher for embedded track artwork
│   │   │   ├── AudioSpecsHelper.kt           # Bit-depth, sample-rate & lossless spec extraction
│   │   │   ├── AudioTagYearHelper.kt         # Container release-year parser (DATE/TDRC/TYER/©day)
│   │   │   ├── LyricsHelper.kt               # Offline synced (.lrc) & static lyrics loader
│   │   │   ├── MusicDbHelper.kt              # SQLite schema for playlists, favorites, history & cache
│   │   │   ├── MusicRepository.kt            # Library queries, scanner orchestration & user data
│   │   │   ├── OpenSourceLicenses.kt         # Bundled offline third-party license notices
│   │   │   └── SettingsPreferencesRepository.kt # Jetpack DataStore user preferences
│   │   ├── debug/
│   │   │   ├── AppLogManager.kt              # Ring-buffered diagnostics & shareable debug reports
│   │   │   └── PDLog.kt                      # Lightweight structured logger facade
│   │   ├── model/
│   │   │   ├── MusicModels.kt                # Immutable domain models (Song, Album, Artist, Playlist, specs)
│   │   │   └── NowPlayingStyle.kt            # Classic/Modern presentation, blend & seekbar style enums
│   │   ├── service/
│   │   │   └── PlaybackService.kt            # Media3 MediaSessionService, ExoPlayer, Focus & WakeLock
│   │   ├── ui/
│   │   │   ├── MusicViewModel.kt             # Shared playback state, queue engine & library actions
│   │   │   ├── components/                   # Reusable Compose UI (MiniPlayer, NowPlayingSheet, lyrics, dialogs, telemetry)
│   │   │   ├── screens/                      # Top-level & detail screens (Main, Home, Library, Detail, Settings, About, Welcome)
│   │   │   └── theme/                        # Material 3 color engine, adaptive layout, typography & motion tokens
│   │   └── util/
│   │       ├── LocaleUtils.kt                # System locale helper (English default, Vietnamese)
│   │       ├── PermissionHelper.kt           # Storage & notification runtime permission helpers
│   │       └── ScanNotificationHelper.kt     # Foreground progress notification for library scanning
│   │
│   └── res/                                  # Drawables, XML configs & localized strings (values, values-vi)
│
└── test/java/com/quandot/musicplayer/        # JUnit unit tests (specs formatting, hybrid scanner, permissions)
```
