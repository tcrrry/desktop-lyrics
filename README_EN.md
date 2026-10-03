<div align="center">
  <img src="app/src/main/res/drawable-nodpi/ic_launcher_art.png" width="104" alt="Desktop Lyrics app icon">

# Desktop Lyrics for Android

**A polished, real-time, freely resizable floating lyrics overlay for Android.**

[Video demo](https://www.bilibili.com/video/BV1jNu66eEkr/) · [Download](https://github.com/tcrrry/desktop-lyrics/releases/latest) · [简体中文](./README.md) · [Privacy](./PRIVACY.md) · [Changelog](./CHANGELOG.md)

[![Latest Release](https://img.shields.io/github/v/release/tcrrry/desktop-lyrics?display_name=tag&sort=semver&label=release)](https://github.com/tcrrry/desktop-lyrics/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/tcrrry/desktop-lyrics/total?label=downloads)](https://github.com/tcrrry/desktop-lyrics/releases)
![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-Android-7F52FF?logo=kotlin&logoColor=white)
</div>

## What is Desktop Lyrics?

Desktop Lyrics is a real-time Android floating lyrics overlay. It reads the active player's public Android MediaSession locally, so it can keep receiving track metadata and playback progress even when the player's own notification is hidden. Notification access must still be granted to Desktop Lyrics itself.

The app queries public lyrics providers directly and does not require a private backend.

## Video demo

[![Desktop Lyrics for Android — real-time floating lyrics for Apple Music and Spotify](./docs/assets/desktop-lyrics-cover.png)](https://www.bilibili.com/video/BV1jNu66eEkr/)

Watch on Bilibili: [Desktop Lyrics for Android — Apple Music / Spotify real-time floating lyrics](https://www.bilibili.com/video/BV1jNu66eEkr/).

## Highlights

- Local, real-time track title, artist, album, playback state, progress, and artwork
- Parallel searches across LRCLIB, QQ Music, and NetEase Cloud Music, ranked by title, artist, album, duration, and version metadata; an iTunes catalog lookup can supply localized aliases when every direct search fails
- QQ Music and NetEase word-timed lyrics and official translations, with original, bilingual, or translated display modes
- In-overlay previous, play/pause, and next controls, plus a seekable progress bar
- Full and compact overlay modes with continuous free resizing and dragging
- Rotate all full-overlay content by 90° inside a fixed outer frame without rotating the system UI; the horizontal view uses a 25% media / 75% lyrics split
- Double-tap the resize icon for an immersive full-screen lyrics view with its own portrait/landscape rotation
- Time-aware, one-direction lyric scrolling in compact mode
- Manual lyric browsing with inertial scrolling; tap a timed lyric line to seek, with automatic live-follow recovery
- Adjustable lyric font size from 35% to 150%, with a responsive minimum height
- Color presets, a full RGB color picker, and a ±5-second lyric timing offset remembered separately for each track and lyrics source
- Untimed plain lyrics remain usable with a clear label and smooth progress-based scrolling
- Optional on-device translation with downloadable ML Kit language packs, plus editable DeepSeek, GLM, Gemini, or custom Chat Completions-compatible API profiles
- Transparent, low-load, medium-load, and high-load album-color backgrounds; medium load throttles only the animated background, not word highlighting
- Media volume plus responsive playback controls and marquee information pills for narrow overlays
- A minimum width of roughly one third of the screen, with seamless marquee titles
- Keeps the screen awake while the overlay is visible and releases that behavior when closed
- Tries multiple candidates, rejects empty, title-only, credit-only, and provider-placeholder lyrics, supports symbol-only titles and cross-script aliases, and allows up to 10 seconds on slow networks
- Tap the source pill to cycle providers, double-tap to rematch the current provider and remember the selected recording, or long-press to restore automatic matching; a failed rematch keeps the current lyrics
- Manage remembered lyric choices by song, search the history, preview QQ Music, NetEase, and LRCLIB versions, choose a result, restore the initial match, or clear the complete matching cache
- Refined settings layout with a pure-black background and red accent, a separate dynamic-background card, stacked font-size and timing-offset controls, and side-by-side source/offset managers
- Expanded and compact modes remember independent lyric size, color, dynamic background, window geometry, and position; full-screen lyrics use the expanded appearance
- A lightweight GitHub Releases check runs at most once per day when opening settings; update prompts can be postponed or ignored for that release, and a manual check remains available

## Requirements and compatibility

- Android 8.0 (API 26) or later
- A 64-bit ARM device (arm64-v8a); 32-bit-only devices and x86 emulators are not supported by the release APK
- Android System WebView
- A music player that exposes a standard Android MediaSession

Apple Music and Kuwo Music have been tested on a vivo device. The implementation also recognizes common players such as QQ Music, NetEase Cloud Music, Kugou Music, Spotify, YouTube Music, TIDAL, Musicolet, AIMP, and VLC.

Long-running behavior may vary with vendor-specific battery restrictions. If the overlay is stopped in the background, allow auto-start and set the app's battery policy to unrestricted.

## Install and use

1. Download the latest APK from [Releases](https://github.com/tcrrry/desktop-lyrics/releases/latest).
2. Install and open Desktop Lyrics.
3. Grant Notification Access and the Display over other apps permission.
4. Start the floating lyrics overlay and play music.
5. Tap the resize icon to switch modes, or long-press and drag it for continuous resizing.
6. In full mode, tap the rotation icon to turn the overlay content by 90° while keeping the outer footprint unchanged.
7. Use the playback buttons or drag the progress bar to control the active player. After browsing the lyrics, tap a timed line to seek to it.
8. If the lyrics are mismatched, double-tap the QQ Music, NetEase, or LRCLIB source pill to try the next candidate from that provider. Long-press it to clear the remembered choice for that track and provider.

## Permissions and privacy

| Permission | Purpose |
| --- | --- |
| Notification Access | Access public MediaSession data; notification message bodies are not read |
| Display over other apps | Show the floating lyrics overlay |
| Network access | Search public lyrics/artwork providers and perform a low-frequency GitHub release check |
| Foreground service | Keep a user-started overlay running in the background |

Desktop Lyrics does not request location or microphone permission, upload location data, or upload a complete listening history. When all direct lyrics searches fail, an iTunes Search request may be used only to resolve localized track and artist aliases. Optional translation sends lyrics only to the translation mode or API selected by the user. See [PRIVACY.md](./PRIVACY.md) for details.

## Lyrics and artwork providers

The app queries LRCLIB, QQ Music, and NetEase Cloud Music on demand. Lyrics, artwork, and related data belong to their respective platforms and rights holders. This repository does not host a lyrics database. Provider availability may vary by region and platform policy.

## Build from source

Open the project in Android Studio, or use JDK 17 with an Android SDK:

```bash
./gradlew assembleRelease
```

To build a signed APK, copy `keystore.properties.example` to `keystore.properties` and supply your own signing information. Real keys and passwords are excluded by `.gitignore`.

## Project information

- Current release: `1.08` (versionCode 108), signed with the same original release certificate as 1.07. See Releases for the APK.
- Android package: `com.tcrrry.desktoplyrics`
- Author: Bilibili `@Tcrrrry`

If Desktop Lyrics is useful to you, consider giving the repository a **Star** so more Android users can discover it.
