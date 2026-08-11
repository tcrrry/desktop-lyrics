<div align="center">
  <img src="app/src/main/res/drawable-nodpi/ic_launcher_art.png" width="104" alt="桌面歌词应用图标">

# 桌面歌词 / Desktop Lyrics

**精致、实时、自由缩放的 Android 悬浮歌词软件**

Android floating lyrics overlay with MediaSession playback detection, synchronized lyrics, fluid resizing, and dynamic album-color backgrounds.

[下载最新版](https://github.com/tcrrry/desktop-lyrics/releases/latest) · [English](./README_EN.md) · [隐私说明](./PRIVACY.md) · [更新记录](./CHANGELOG.md)

[![Latest Release](https://img.shields.io/github/v/release/tcrrry/desktop-lyrics?display_name=tag&sort=semver&label=release)](https://github.com/tcrrry/desktop-lyrics/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/tcrrry/desktop-lyrics/total?label=downloads)](https://github.com/tcrrry/desktop-lyrics/releases)
![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-Android-7F52FF?logo=kotlin&logoColor=white)
</div>

## 这是什么

桌面歌词是一款本地实时同步的 Android 歌词悬浮窗。它直接读取播放器公开的 Android MediaSession；在授予桌面歌词“通知使用权”后，即使关闭音乐软件自身的通知展示，也能持续获取当前歌曲和播放进度。

应用按需直连公共歌词源，不依赖自建服务器，也不经过 `tcrrry.com`。它把同步歌词、自由缩放、单行紧凑模式、手动歌词浏览和专辑色动态背景整合在一个应用中。

## 核心功能

- 本机实时读取歌名、歌手、专辑、播放状态、进度和封面
- 直连 LRCLIB、QQ 音乐和网易云，搜索同步歌词与备用封面
- 标准/收起两种悬浮形态，支持连续自由缩放和拖动
- 收起形态按每句时长单向滚动；双行时下一句保持静止
- 展开歌词可手动惯性滚动，停止操作后自动恢复实时跟随
- 歌词字号支持 75%–150%，最小窗口高度随字号动态变化
- 透明、低负载和高负载三种背景，首次安装默认高负载
- 显示系统媒体音量和真实蓝牙、有线、USB 输出设备
- 最窄约为屏幕宽度的三分之一，窄窗口标题连续循环滚动

## 兼容性

- Android 8.0（API 26）及以上
- 需要 Android System WebView
- 播放器需要提供标准 Android MediaSession

目前已在 vivo 设备上验证 Apple Music 与酷我音乐。代码也适配 QQ 音乐、网易云音乐、酷狗音乐、Spotify、YouTube Music、TIDAL、Musicolet、AIMP、VLC 等常见播放器。

不同手机厂商的后台省电策略可能影响长时间运行。如果悬浮窗被系统清理，请允许应用自启动，并将电池策略设为“不限制”。

## 安装与使用

1. 前往 [Releases](https://github.com/tcrrry/desktop-lyrics/releases/latest) 下载最新版 APK。
2. 安装并打开“桌面歌词”。
3. 依次授予“通知使用权”和“悬浮窗权限”。
4. 如需显示具体蓝牙设备名，请允许“附近设备”权限。
5. 点击“开启歌词悬浮窗”，然后播放音乐。
6. 单击缩放图标切换标准/收起形态；长按并拖动可自由调整宽高。

## 权限与隐私

| 权限 | 用途 |
| --- | --- |
| 通知使用权 | 仅用于访问其他播放器公开的 MediaSession，不读取通知正文 |
| 悬浮窗 | 在其他应用上方显示实时歌词 |
| 附近设备 | 显示已连接蓝牙音频设备的名称 |
| 网络访问 | 向公共歌词/音乐平台搜索歌词和备用封面 |
| 前台服务 | 在退到后台后保持用户主动开启的悬浮窗 |

应用不申请定位权限，不读取麦克风，不上传位置或完整播放历史。详细内容见 [PRIVACY.md](./PRIVACY.md)。

## 歌词与封面来源

应用按需查询 LRCLIB、QQ 音乐和网易云。数据、歌词和封面版权归相应平台及权利人所有。本项目不托管歌词数据库，用户应遵守所在地法律以及相关服务条款；各来源的可用性可能随地区和平台策略变化。

## 从源码构建

使用 Android Studio 打开仓库，或在已配置 Android SDK 与 JDK 17 的环境中运行：

```bash
./gradlew assembleRelease
```

如需生成签名 APK，将 `keystore.properties.example` 复制为 `keystore.properties`，然后填写自己的签名信息。真实密钥与密码已被 `.gitignore` 排除。

## 常见问题

### 它会录制或分析手机正在播放的声音吗？

不会。应用不申请麦克风权限，也不录制系统音频；歌曲信息来自播放器公开的 MediaSession。

### 为什么需要“通知使用权”？

这是 Android 提供跨应用访问 MediaSession 的系统入口。桌面歌词只使用媒体会话数据，不读取聊天或普通通知正文。

### 所有播放器都能使用吗？

只要播放器正确提供标准 MediaSession，通常就能读取。个别播放器、系统定制或省电策略可能导致兼容性差异。

### 歌词为什么偶尔需要几秒才能出现？

首次识别歌曲时需要向多个公开来源搜索并匹配歌词。匹配完成后，歌词会按照本机播放进度实时同步。

## 项目信息

- 当前正式版：`1.00`（versionCode 100）
- Android 包名：`com.tcrrry.desktoplyrics`
- 作者：B站 `@Tcrrrry`

如果这个项目对你有帮助，欢迎点亮右上角的 **Star**，让更多需要 Android 桌面歌词的人看到它。
