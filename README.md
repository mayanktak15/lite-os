# Lite TV

Lite TV is a high-performance, lightweight IPTV player engineered specifically for Android TV and low-resource embedded devices (such as Wisdom Share Smart TVs with 1 GB RAM, Android 9.0 Pie / API 28, and Mali-G31 MP2 GPUs). It features a native Server-Driven UI (SDUI), resilient streaming M3U parser, and an ultra-lean Media3 ExoPlayer implementation.

---

## What Has Been Achieved

A full native Android application stack has been designed, implemented, and verified with automated test suites and debug builds:

### 1. Server-Driven UI (SDUI) Framework
- **Declarative Schema**: Designed a modular JSON configuration format ([SduiConfig.kt](file:///home/linus/Desktop/tv/app/src/main/java/com/litetv/app/sdui/SduiConfig.kt)) supporting schema versioning, themes, dynamic section definitions, navigation tabs, and playlist sources.
- **Zero-Heavy-Dependency JSON Parsing**: Implemented [SduiConfigParser.kt](file:///home/linus/Desktop/tv/app/src/main/java/com/litetv/app/sdui/SduiConfigParser.kt) using Android's native `org.json`, eliminating bulky third-party serialization libraries (e.g., Jackson/Moshi/Gson) to conserve memory.
- **Three-Tier Fallback Repository**: Built [SduiConfigRepository.kt](file:///home/linus/Desktop/tv/app/src/main/java/com/litetv/app/sdui/SduiConfigRepository.kt) with an in-memory cache, persistent disk cache ([DiskCache.kt](file:///home/linus/Desktop/tv/app/src/main/java/com/litetv/app/cache/DiskCache.kt)), and bundled offline fallback ([default_config.json](file:///home/linus/Desktop/tv/app/src/main/res/raw/default_config.json)).
- **Native View Renderer**: Developed [SduiRenderer.kt](file:///home/linus/Desktop/tv/app/src/main/java/com/litetv/app/sdui/SduiRenderer.kt) and [SduiComponentFactory.kt](file:///home/linus/Desktop/tv/app/src/main/java/com/litetv/app/sdui/SduiComponentFactory.kt) that dynamically construct native Android layouts (Hero rows, category rows, horizontal scrolling lists) without WebViews.
- **Safe Degradation**: Gracefully handles unknown component types, malformed configs, or unsupported schema versions without crashing.

### 2. Streaming M3U Playlist Parser & Catalogue
- **Low-Allocation Line-by-Line Parser**: Built [M3uParser.kt](file:///home/linus/Desktop/tv/app/src/main/java/com/litetv/app/iptv/M3uParser.kt) using streaming `BufferedReader` to safely parse thousands of channel lines without causing Out-Of-Memory (OOM) spikes.
- **Metadata Extraction**: Robustly extracts `#EXTINF` tags, `tvg-id`, `tvg-name`, `tvg-logo`, `group-title`, and stream URLs.
- **Security & Quality Filters**: Sanitizes streams by strictly filtering for valid HTTP/HTTPS URLs (rejecting unsupported schemes like RTSP/RTMP), deduplicating channels by stream URL, and gracefully handling missing attributes.
- **Categorization**: Auto-categorizes channels into News, Sports, Entertainment, Movies, Music, Kids, Regional, and All Channels.
- **Playlist Management**: Implemented [PlaylistRepository.kt](file:///home/linus/Desktop/tv/app/src/main/java/com/litetv/app/iptv/PlaylistRepository.kt) with offline disk caching and dynamic source switching.

### 3. Media3 ExoPlayer Video Engine
- **Hardware-Accelerated Playback**: Configured AndroidX Media3 ExoPlayer ([PlayerManager.kt](file:///home/linus/Desktop/tv/app/src/main/java/com/litetv/app/player/PlayerManager.kt)) with HLS adaptive streaming support (`media3-exoplayer-hls`).
- **Low-Memory Buffer Tuning**: Custom `DefaultLoadControl` configured for low-RAM hardware (buffer parameters: 5s min, 15s max, 2.5s playback start buffer, ~10 MB RAM budget).
- **Playback Lifecycle**: Strict single-player instance model in [PlayerActivity.kt](file:///home/linus/Desktop/tv/app/src/main/java/com/litetv/app/PlayerActivity.kt) with automatic resource release upon exit or backgrounding.
- **Buffering & Error Handling**: On-screen buffering indicators, auto-retry logic with exponential backoff for flaky network streams, and error diagnostics overlay.
- **OSD Channel Info Overlay**: Auto-hiding 5-second channel banner displaying channel title and group category.

### 4. TV D-Pad Remote Navigation & UI
- **Complete D-Pad Key Handling**:
  - `DPAD_CENTER` / `ENTER` / `KEYCODE_BUTTON_A`: Select channel, toggle play/pause.
  - `DPAD_LEFT`: Switch to previous channel in the playlist.
  - `DPAD_RIGHT`: Switch to next channel in the playlist.
  - `DPAD_UP`: Display channel information overlay.
  - `DPAD_DOWN`: Dismiss channel information overlay.
  - `BACK`: Return to channel catalogue / home screen.
- **Visual Focus States**: High-visibility focus indicators with custom glow/border drawables ([bg_focused.xml](file:///home/linus/Desktop/tv/app/src/main/res/drawable/bg_focused.xml)) and card animations for remote navigation.
- **Universal Launcher Compatibility**: Configured `AndroidManifest.xml` with both `LEANBACK_LAUNCHER` and standard `LAUNCHER` intent filters, allowing installation on Android TV, non-leanback TV boxes, and standard Android devices.

### 5. Settings & Playlist Source Switcher
- **Dedicated Settings Screen**: [SettingsActivity.kt](file:///home/linus/Desktop/tv/app/src/main/java/com/litetv/app/SettingsActivity.kt) enables switching between preset feeds:
  - IPTV-Org India (`https://iptv-org.github.io/iptv/countries/in.m3u`)
  - IPTV-Org News (`https://iptv-org.github.io/iptv/categories/news.m3u`)
  - World IPTV (`https://romaxa55.github.io/world_ip_tv/output/index.m3u`)
- **Custom M3U Input**: Input any HTTP/HTTPS M3U playlist URL via on-screen keyboard.
- **Live Status Diagnostics**: Displays last updated timestamp and parsed channel count, with manual refresh capabilities.

### 6. Memory & Performance Optimizations
- **Bounded LRU Bitmap Cache**: [ImageCache.kt](file:///home/linus/Desktop/tv/app/src/main/java/com/litetv/app/cache/ImageCache.kt) capped at 20 channel logos, downsampling bitmaps to 96×96 using `RGB_565` (50% memory savings vs `ARGB_8888`).
- **Standard Heap Operation**: Configured `largeHeap="false"` in manifest to ensure the application runs reliably within a strict ~60 MB memory ceiling.

### 7. Automated Testing & Verification
- Comprehensive unit tests:
  - [M3uParserTest.kt](file:///home/linus/Desktop/tv/app/src/test/java/com/litetv/app/iptv/M3uParserTest.kt) validates full tag parsing, metadata fallback, duplicate deduplication, scheme filtering, and empty inputs.
  - [SduiConfigParserTest.kt](file:///home/linus/Desktop/tv/app/src/test/java/com/litetv/app/sdui/SduiConfigParserTest.kt) validates schema deserialization, theme parsing, section lists, and error tolerance.
- All unit tests pass cleanly in Gradle (`./gradlew test`).
- Debug APK successfully compiled (`app-debug.apk`, 16 MB).

---

## Hardware Target Profile

| Specification | Target Details |
|---|---|
| **Device Form Factor** | Android Smart TV / TV Box / Embedded Display |
| **Android OS** | Android 9.0 (API Level 28) |
| **RAM** | 1 GB total system memory |
| **Storage** | 8 GB onboard flash |
| **GPU** | ARM Mali-G31 MP2 |
| **Network** | Wi-Fi 802.11 b/g/n & 100M Ethernet |
| **Input** | Standard IR / Bluetooth TV Remote (D-pad + OK + Back) |

---

## Memory Budget

| Component | Target Allocation |
|---|---|
| Application baseline (Android runtime + views) | ~15 MB |
| Image cache (20 × 96px RGB_565 bitmaps) | ~0.4 MB |
| Channel catalogue (up to 5,000 items in memory) | ~2 MB |
| ExoPlayer buffers (5s–15s window) | ~10 MB |
| SDUI config + View hierarchy | ~3 MB |
| **Total Operational RAM** | **< 60 MB** |

---

## How to Run This Application

### Prerequisites

- **Java Development Kit**: JDK 17
- **Android SDK**: Build tools and platform for API 28+ (API 34 recommended)
- **Android Debug Bridge (ADB)**: Available at `$ANDROID_HOME/platform-tools/adb`
- Optional: Android Studio (Hedgehog 2023.1 or later)

Ensure your Android SDK path is configured in `local.properties`:
```properties
sdk.dir=/home/linus/Android/Sdk
```

---

### Step 1: Build the Application

From the project root directory, run:

```bash
# Make gradlew executable
chmod +x gradlew

# Run unit tests to verify logic
./gradlew test

# Compile Debug APK
./gradlew assembleDebug
```

The compiled APK will be generated at:
```
app/build/outputs/apk/debug/app-debug.apk
```

---

### Step 2: Install and Run on Android TV (Over Wi-Fi / Ethernet)

#### 1. Enable ADB Debugging on your TV
1. On your Android TV, open **Settings** > **Device Preferences** > **About**.
2. Scroll to **Build** and click it 7 times until developer mode is unlocked.
3. Go back to **Device Preferences** > **Developer Options**.
4. Enable **USB Debugging** and/or **Network Debugging**.
5. Note your TV's IP address in **Settings** > **Network & Internet** (e.g., `192.168.1.50`).

#### 2. Connect via ADB
From your terminal:
```bash
# Export adb to PATH if needed
export PATH=$PATH:/home/linus/Android/Sdk/platform-tools

# Connect to TV over port 5555
adb connect <TV_IP_ADDRESS>:5555

# Verify connection
adb devices
```
*(Accept the authorization prompt on your TV screen if requested).*

#### 3. Install the APK
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

#### 4. Launch Lite TV
```bash
adb shell am start -n com.litetv.app/.MainActivity
```

#### 5. Stop or Uninstall
```bash
# Force stop
adb shell am force-stop com.litetv.app

# Uninstall
adb uninstall com.litetv.app
```

---

### Step 3: Run on Android TV Emulator (Desktop)

1. In Android Studio, open the **Virtual Device Manager** (AVD Manager).
2. Create a new virtual device:
   - Category: **TV**
   - Device: **Television (1080p)**
   - System Image: **Android 9.0 (Pie) - API 28** (or API 34)
3. Start the emulator.
4. Run the Gradle install command:
```bash
./gradlew installDebug
```
5. Launch the app from the emulator app drawer or via ADB:
```bash
adb shell am start -n com.litetv.app/.MainActivity
```

---

### Step 4: Run on Android Phone or Tablet

Lite TV includes non-leanback manifest configurations (`touchscreen required=false`, standard `LAUNCHER` intent). It can be installed directly on standard Android phones or tablets:
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.litetv.app/.MainActivity
```
The application will launch in landscape mode with touch and virtual navigation support.

---

## Remote Control & Remote D-Pad Simulation

### TV Remote Key Mapping

| Key | Action on Home Screen | Action in Video Player |
|---|---|---|
| **D-Pad Up** | Move focus up | Show channel info banner |
| **D-Pad Down** | Move focus down | Hide channel info banner |
| **D-Pad Left** | Move focus left | Switch to **Previous Channel** |
| **D-Pad Right** | Move focus right | Switch to **Next Channel** |
| **OK / Enter** | Select channel / open card | Toggle **Play / Pause** |
| **Back** | Double-tap to exit app | Return to Channel List |

### Simulating Remote Navigation via ADB

You can test remote navigation from your development computer using ADB key events:

```bash
# D-pad Navigation
adb shell input keyevent 19    # DPAD_UP
adb shell input keyevent 20    # DPAD_DOWN
adb shell input keyevent 21    # DPAD_LEFT
adb shell input keyevent 22    # DPAD_RIGHT

# Select / OK
adb shell input keyevent 23    # DPAD_CENTER
adb shell input keyevent 66    # KEYCODE_ENTER

# Back
adb shell input keyevent 4     # KEYCODE_BACK

# Media Controls
adb shell input keyevent 85    # KEYCODE_MEDIA_PLAY_PAUSE
adb shell input keyevent 87    # KEYCODE_MEDIA_NEXT
adb shell input keyevent 88    # KEYCODE_MEDIA_PREVIOUS
```

---

## Live Monitoring & Diagnostics

### View Real-Time Application Logs
```bash
adb logcat -v time -s LiteTV:* MainActivity:* PlayerActivity:* PlayerManager:* M3uParser:* SduiConfigParser:*
```

### Inspect Memory Footprint
Verify runtime heap allocation on your physical TV:
```bash
adb shell dumpsys meminfo com.litetv.app
```
Look for `TOTAL PSS` to verify that memory remains strictly under the ~60 MB target during playback and channel switching.

### Check Device Architecture
```bash
adb shell getprop ro.product.cpu.abi
adb shell getprop ro.build.version.release
```

---

## Updating the UI Remotely (SDUI)

The app UI layout, sections, categories, and sources can be updated remotely without releasing a new APK.

1. Edit [sdui/config.json](file:///home/linus/Desktop/tv/sdui/config.json).
2. Host the JSON file on any HTTPS endpoint (e.g. Cloudflare Pages, GitHub Pages, or AWS S3):
   ```
   https://your-domain.pages.dev/config.json
   ```
3. Update the remote config URL in [SduiConfigRepository.kt](file:///home/linus/Desktop/tv/app/src/main/java/com/litetv/app/sdui/SduiConfigRepository.kt) (`REMOTE_CONFIG_URL`).
4. Lite TV will fetch and apply the updated schema on next launch, while retaining offline disk cache for instant startup.

---

## Project Structure

```
tv/
├── app/
│   ├── build.gradle.kts                   # Dependencies & build configuration
│   ├── proguard-rules.pro                 # Release code shrinking & R8 rules
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml        # TV Leanback & standard launcher configuration
│       │   ├── java/com/litetv/app/
│       │   │   ├── MainActivity.kt        # SDUI container & home screen controller
│       │   │   ├── PlayerActivity.kt      # Fullscreen ExoPlayer playback activity
│       │   │   ├── SettingsActivity.kt    # M3U playlist source manager
│       │   │   ├── iptv/
│       │   │   │   ├── Channel.kt         # Channel data model
│       │   │   │   ├── M3uParser.kt       # Streaming low-memory M3U parser
│       │   │   │   └── PlaylistRepository.kt # Playlist caching and retrieval
│       │   │   ├── player/
│       │   │   │   └── PlayerManager.kt   # Media3 ExoPlayer lifecycle wrapper
│       │   │   ├── sdui/
│       │   │   │   ├── SduiConfig.kt      # SDUI JSON schema models
│       │   │   │   ├── SduiConfigParser.kt # Lightweight JSON parser
│       │   │   │   ├── SduiConfigRepository.kt # Cache + remote fetch + fallback
│       │   │   │   ├── SduiRenderer.kt    # Native Android View renderer
│       │   │   │   ├── SduiComponentFactory.kt # View builders for rows and cards
│       │   │   │   └── SduiActionHandler.kt # Navigation & playback actions
│       │   │   ├── cache/
│       │   │   │   ├── DiskCache.kt       # File-based cache for playlists and config
│       │   │   │   └── ImageCache.kt      # Bounded LRU bitmap memory cache
│       │   │   ├── network/
│       │   │   │   └── HttpClient.kt      # Lightweight HTTP/HTTPS request utility
│       │   │   └── util/
│       │   │       └── Extensions.kt      # Coroutine and view helper extensions
│       │   └── res/                       # Layouts, themes, TV banners, and drawables
│       └── test/java/com/litetv/app/
│           ├── iptv/M3uParserTest.kt      # Unit tests for M3U playlist parser
│           └── sdui/SduiConfigParserTest.kt # Unit tests for SDUI JSON parser
├── sdui/
│   └── config.json                        # Reference SDUI server configuration
├── test-fixtures/                         # Sample M3U and JSON test data
└── README.md
```

---

## License & Usage

This project is created for personal, educational, and research use on low-resource TV hardware. Ensure that any IPTV streams accessed comply with local copyright laws and broadcaster distribution rights.
