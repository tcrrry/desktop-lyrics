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

The app queries public lyrics providers directly. It does not require a private backend and does not route requests through `tcrrry.com`.

## Video demo

[![Desktop Lyrics for Android — real-time floating lyrics for Apple Music and Spotify](./docs/assets/desktop-lyrics-cover.jpg)](https://www.bilibili.com/video/BV1jNu66eEkr/)

Watch on Bilibili: [Desktop Lyrics for Android — Apple Music / Spotify real-time floating lyrics](https://www.bilibili.com/video/BV1jNu66eEkr/).

## Highlights

- Local, real-time track title, artist, album, playback state, progress, and artwork
- Synchronized lyrics and fallback artwork from LRCLIB, QQ Music, and NetEase Cloud Music
- Full and compact overlay modes with continuous free resizing and dragging
- Time-aware, one-direction lyric scrolling in compact mode
- Manual lyric browsing with inertial scrolling and automatic live-follow recovery
- Adjustable lyric font size from 75% to 150%, with a responsive minimum height
- Transparent, low-load, and high-load animated album-color backgrounds
- Media volume and the current Bluetooth, wired, or USB audio output device
- A minimum width of roughly one third of the screen, with seamless marquee titles

## Requirements and compatibility

- Android 8.0 (API 26) or later
- Android System WebView
- A music player that exposes a standard Android MediaSession

Apple Music and Kuwo Music have been tested on a vivo device. The implementation also recognizes common players such as QQ Music, NetEase Cloud Music, Kugou Music, Spotify, YouTube Music, TIDAL, Musicolet, AIMP, and VLC.

Long-running behavior may vary with vendor-specific battery restrictions. If the overlay is stopped in the background, allow auto-start and set the app's battery policy to unrestricted.

## Install and use

1. Download the latest APK from [Releases](https://github.com/tcrrry/desktop-lyrics/releases/latest).
2. Install and open Desktop Lyrics.
3. Grant Notification Access and the Display over other apps permission.
4. Optionally grant Nearby devices permission to show the connected Bluetooth device name.
5. Start the floating lyrics overlay and play music.
6. Tap the resize icon to switch modes, or long-press and drag it for continuous resizing.

## Permissions and privacy

| Permission | Purpose |
| --- | --- |
| Notification Access | Access public MediaSession data; notification message bodies are not read |
| Display over other apps | Show the floating lyrics overlay |
| Nearby devices | Display the connected Bluetooth audio device name |
| Network access | Search public lyrics and artwork providers |
| Foreground service | Keep a user-started overlay running in the background |

Desktop Lyrics does not request location or microphone permission, upload location data, or upload a complete listening history. See [PRIVACY.md](./PRIVACY.md) for details.

## Lyrics and artwork providers

The app queries LRCLIB, QQ Music, and NetEase Cloud Music on demand. Lyrics, artwork, and related data belong to their respective platforms and rights holders. This repository does not host a lyrics database. Provider availability may vary by region and platform policy.

## Build from source

Open the project in Android Studio, or use JDK 17 with an Android SDK:

```bash
./gradlew assembleRelease
```

To build a signed APK, copy `keystore.properties.example` to `keystore.properties` and supply your own signing information. Real keys and passwords are excluded by `.gitignore`.

## Project information

- Current release: `1.00` (versionCode 100)
- Android package: `com.tcrrry.desktoplyrics`
- Author: Bilibili `@Tcrrrry`

If Desktop Lyrics is useful to you, consider giving the repository a **Star** so more Android users can discover it.
