# Master Project Prompt: Lite TV — SDUI-Based Android TV IPTV Application

Act as a senior Android engineer and software architect specializing in Kotlin, Android TV, Server-Driven UI (SDUI), IPTV, video streaming, and low-memory embedded devices.

Build a complete, installable Android application called **Lite TV** for my Wisdom Share smart television. The application must use a native, JSON-driven Server-Driven UI architecture and support publicly available, legally accessible live-TV streams.

The primary objective is to create a lightweight IPTV player optimized for my actual television hardware, with a remotely configurable interface, M3U playlist support, and reliable video playback.

## 1. Actual TV Hardware Specifications

Use the following specifications as the target hardware profile:

- **Device type:** Smart LED/LCD television
- **Device identifier:** SMART_TV
- **Operating system:** Android 9.0
- **Android API level:** 28
- **RAM:** 1 GB
- **Internal storage:** 8 GB
- **CPU:** Quad-core processor; exact model and clock speed unknown
- **GPU:** ARM Mali-G31 MP2
- **TV branding:** Sony logo visible on the television
- **Network connectivity:** Wi-Fi and Ethernet interfaces are listed in the device information
- **Firmware:** Wisdom Share Android-based TV firmware
- **Google services:** Availability and compatibility have not yet been verified

The CPU architecture, available RAM at runtime, supported video codecs, maximum hardware-decoding resolution, and actual network performance must be verified on the physical device before final optimization.

Do not assume that the Sony branding confirms the motherboard manufacturer or the television's original manufacturer.

### Hardware optimization requirements

Because the TV has only 1 GB RAM and 8 GB internal storage:

- Use native Android Views instead of a heavy web-based UI.
- Avoid unnecessary background services and dependencies.
- Maintain only one active video player.
- Do not preload multiple video streams.
- Avoid autoplay previews, animated backgrounds, and expensive visual effects.
- Use bounded image caching and efficient list rendering.
- Parse playlists efficiently and limit memory allocations.
- Release player, network, and activity resources correctly.
- Keep the installed application and its cached data small.
- Test memory usage during prolonged playback and repeated channel switching.

## 2. Technology Stack

Use:

- Kotlin
- Android Studio
- Android SDK with `minSdk = 28`
- Compatible, pinned Gradle and Android Gradle Plugin versions
- AndroidX Media3 ExoPlayer
- Native Android Views
- JSON-based SDUI configuration
- M3U/M3U8 playlist parsing
- HTTPS networking
- GitHub Releases for APK distribution
- Cloudflare Pages or equivalent HTTPS hosting for configuration files

Provide a reproducible Gradle build. Verify all dependency versions against the selected Android build toolchain.

## 3. Mandatory Server-Driven UI Architecture

The UI must be controlled by a remotely hosted JSON configuration.

Implement the following pipeline:

Remote HTTPS Configuration → Configuration Repository → JSON Parser and Validator → Native SDUI Renderer → Android Views.

The SDUI system must control:

- Home screen layout
- Section visibility and ordering
- Channel categories
- Featured channel IDs
- Display labels and headings
- Theme colors and supported visual properties
- Navigation sections
- Channel card presentation
- Empty, loading, and error states
- Search interface
- Settings screen presentation
- Playlist selection options

Use a predefined registry of supported native components. The server must supply declarative data, never executable code, arbitrary classes, scripts, or downloadable UI plugins.

Unknown component types must fail safely.

## 4. IPTV Playlist Sources

Integrate configurable playlist sources from these repositories:

### Source A: iptv-org

Repository:
https://github.com/iptv-org/iptv

India playlist:
https://iptv-org.github.io/iptv/countries/in.m3u

News playlist:
https://iptv-org.github.io/iptv/categories/news.m3u

### Source B: Romaxa55/world_ip_tv

Repository:
https://github.com/Romaxa55/world_ip_tv

Published playlist:
https://romaxa55.github.io/world_ip_tv/output/index.m3u

Verify the current availability, format, and suitability of these endpoints before use.

Implement a settings screen allowing the user to select a source, enter a custom M3U URL, refresh a playlist, and inspect its status.

These sources are starting points, not guarantees that every listed stream is available, authorized for redistribution, or compatible with the television.

## 5. M3U Parser and Channel Catalogue

Implement an efficient M3U parser that supports:

- `#EXTM3U`
- `#EXTINF`
- `tvg-id`
- `tvg-name`
- `tvg-logo`
- `group-title`
- Channel display names
- Stream URLs

Handle missing metadata, malformed entries, duplicate channels, empty playlists, redirects, and failed downloads.

Provide categories for News, Sports, Movies, Entertainment, Regional, Kids, Music, and All Channels when sufficient metadata is available.

Prioritize Indian channels and regional languages where reliable metadata permits.

Keep the channel catalogue separate from the SDUI configuration. The UI configuration should reference channel IDs rather than embedding entire playlists.

Cache the last successfully parsed catalogue and preserve it across temporary network failures.

## 6. Video Playback

Use AndroidX Media3 ExoPlayer.

Support HLS and other formats only when compatible with the selected Media3 version, TV firmware, and available codecs.

Implement:

- Channel selection and switching
- Buffering indicators
- Playback error handling
- Bounded retry logic
- Network failure recovery
- Proper player lifecycle management
- Supported-resolution handling
- Resource cleanup
- Clear playback status

Start with one verified, authorized HLS stream before adding a large channel catalogue.

Do not bypass DRM, subscriptions, authentication, geoblocking, or broadcaster access controls.

For premium channels such as Star Plus and Sony Entertainment Television, use an authorized provider or the official application where available. Do not assume a public playlist entry establishes permission to redistribute a channel.

## 7. SDUI Configuration and Hosting

Host the SDUI JSON over HTTPS using Cloudflare Pages or an equivalent static hosting provider.

The configuration should include:

- Schema version
- Minimum supported app version
- Theme settings
- Home screen component definitions
- Component ordering and visibility
- Featured channel IDs
- Category configuration
- Playlist source options
- Refresh policy

The app must:

1. Launch immediately using a bundled default configuration.
2. Fetch remote configuration asynchronously.
3. Validate the JSON before applying it.
4. Cache the last valid configuration.
5. Fall back safely when the server is unavailable.
6. Apply valid changes without restarting where practical.
7. Reject unsupported schemas and malformed data.
8. Avoid unnecessary repeated network requests.

Allow layout, headings, category ordering, and featured channels to change without rebuilding the APK.

Do not allow remote configuration to execute arbitrary code or initiate arbitrary network actions.

## 8. Remote Control and TV Interface

Provide a 16:9 interface with clear text and focus indicators.

Support:

- D-pad navigation
- OK/Enter selection
- Back navigation
- Category switching
- Channel selection
- Search
- Player controls
- Settings navigation
- Predictable focus restoration after SDUI updates

The application must not depend on touchscreen input.

## 9. Project Structure

Generate a complete Android Studio project containing:

- `MainActivity.kt`
- `SduiConfig.kt`
- `SduiConfigRepository.kt`
- `SduiConfigParser.kt`
- `SduiRenderer.kt`
- `SduiComponentFactory.kt`
- `SduiActionHandler.kt`
- `Channel.kt`
- `M3uParser.kt`
- `PlaylistRepository.kt`
- `PlayerManager.kt`
- `SettingsActivity.kt`
- `AndroidManifest.xml`
- Layout and drawable resources
- Sample SDUI JSON
- Sample M3U fixtures
- Gradle configuration
- Gradle wrapper files
- `README.md`

Separate presentation, configuration, catalogue, networking, and playback responsibilities.

## 10. Security and Reliability

- Use HTTPS for remote configuration and playlists by default.
- Validate all downloaded data.
- Restrict unsafe URL schemes.
- Limit download sizes and network timeouts.
- Never execute downloaded code.
- Do not store private credentials in the APK.
- Avoid unrestricted cleartext networking.
- Handle invalid streams without crashing the app.
- Retain usable cached configuration and catalogue data.
- Include appropriate logging without exposing secrets.

## 11. Testing on the Actual Television

Provide a testing checklist covering:

- APK installation on Android 9
- App launch time
- SDUI configuration parsing
- Offline fallback
- M3U parsing
- D-pad and Back navigation
- HLS playback
- Channel switching
- Memory consumption
- Long-duration playback
- Network interruption
- Codec compatibility
- Player resource cleanup
- Recovery from invalid playlists
- APK update and installation behavior

Use ADB where supported to inspect Android logs, available memory, CPU architecture, and installed package information.

Do not claim successful device testing without actual results.

## 12. Required Deliverables

Deliver:

1. Complete Android Studio source project.
2. Working native SDUI renderer.
3. M3U parser and playlist selection.
4. Channel catalogue and caching.
5. Media3 video player.
6. Remote-control navigation.
7. Example remote SDUI configuration.
8. Build and installation instructions.
9. Debugging and troubleshooting documentation.
10. A reproducible debug APK build and instructions for producing a signed release APK.

## 13. Development Phases

Phase 1: Create a buildable Android 9 application.

Phase 2: Implement the SDUI JSON parser and native renderer.

Phase 3: Implement M3U parsing and display channels.

Phase 4: Play one verified, authorized stream.

Phase 5: Add remote configuration hosting, caching, and playlist selection.

Phase 6: Optimize for the actual 1 GB RAM TV.

Phase 7: Test on the physical television and prepare the release APK.

Begin with a minimal working project, prioritize compatibility with the specified hardware, and implement the features incrementally.