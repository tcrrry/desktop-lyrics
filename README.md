<div align="center">
  <img src="app/src/main/res/drawable-nodpi/ic_launcher_art.png" width="104" alt="桌面歌词应用图标">

# 桌面歌词 / Desktop Lyrics

**精致、实时、自由缩放的 Android 悬浮歌词软件**

Android floating lyrics overlay with MediaSession playback detection, synchronized lyrics, fluid resizing, and dynamic album-color backgrounds.

[B站视频演示](https://www.bilibili.com/video/BV1jNu66eEkr/) · [下载最新版](https://github.com/tcrrry/desktop-lyrics/releases/latest) · [English](./README_EN.md) · [隐私说明](./PRIVACY.md) · [更新记录](./CHANGELOG.md)

[![Latest Release](https://img.shields.io/github/v/release/tcrrry/desktop-lyrics?display_name=tag&sort=semver&label=release)](https://github.com/tcrrry/desktop-lyrics/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/tcrrry/desktop-lyrics/total?label=downloads)](https://github.com/tcrrry/desktop-lyrics/releases)
![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-Android-7F52FF?logo=kotlin&logoColor=white)
</div>

## 这是什么

桌面歌词是一款本地实时同步的 Android 歌词悬浮窗。它直接读取播放器公开的 Android MediaSession；在授予桌面歌词“通知使用权”后，即使关闭音乐软件自身的通知展示，也能持续获取当前歌曲和播放进度。

应用按需直连公共歌词源，不依赖自建服务器。它把同步歌词、自由缩放、单行紧凑模式、手动歌词浏览和专辑色动态背景整合在一个应用中。

## 视频演示

[![可能是最精致的安卓桌面歌词软件｜Apple Music / Spotify 实时悬浮显示](./docs/assets/desktop-lyrics-cover.png)](https://www.bilibili.com/video/BV1jNu66eEkr/)

点击封面或前往 B站观看：[可能是最精致的安卓桌面歌词软件｜Apple Music / Spotify 实时悬浮显示](https://www.bilibili.com/video/BV1jNu66eEkr/)。

## 核心功能

- 本机实时读取歌名、歌手、专辑、播放状态、进度和封面
- 直连 LRCLIB、QQ 音乐和网易云，并行搜索后综合歌名、歌手、专辑、时长和版本信息选择可靠歌词；必要时借助 iTunes 目录补充跨语言别名后重新匹配
- 支持 QQ 音乐与网易云逐字歌词、官方翻译，可切换原文、双语或中文显示；同一来源存在多个版本时也会继续比较完整度
- 悬浮窗内可直接播放/暂停、上一首、下一首，进度条支持拖动跳转
- 标准/收起两种悬浮形态，支持连续自由缩放和拖动
- 展开态可在固定外框内将全部内容旋转 90°，不改变手机系统方向；横向采用左侧播放信息、右侧歌词的 25% / 75% 分栏
- 双击缩放图标可进入沉浸式全屏歌词，全屏页支持独立横竖翻转，不改变启动界面或整个系统方向
- 收起形态按每句时长单向滚动；双行时下一句保持静止
- 展开歌词可手动惯性滚动；点击任意带时间轴的歌词即可跳转，停止操作后自动恢复实时跟随
- 歌词字号支持 35%–150%，最小窗口高度随字号动态变化
- 新增发音独立开关，读取 QQ／网易云提供的罗马音；支持歌词对比色描边，以及下拉快捷设置磁贴开关悬浮窗
- 歌词支持颜色预设、全色域无级调色与 ±5 秒同步偏移；偏移会按歌曲和歌词源分别记忆，并可集中管理
- 没有时间轴的纯文本歌词也能按歌曲进度平滑滚动，并明确标注“无时间轴歌词”
- 缺少官方译文时可选择离线机翻，语言包按需下载与删除；也可配置兼容 Chat Completions 的 DeepSeek、智谱 GLM、Gemini 或其他 HTTPS API
- 透明、低负载、中负载和高负载四种背景；中负载仅降低动态背景帧率，不影响逐字歌词进度
- 显示系统媒体音量；播放控制与信息胶囊在窄窗口中会自动让位和循环滚动
- 最窄约为屏幕宽度的三分之一，窄窗口标题连续循环滚动
- 悬浮窗开启期间保持屏幕常亮，关闭后自动恢复系统息屏策略
- 歌词搜索会自动尝试多个候选，跳过空歌词、只有歌名/制作信息的平台占位内容和歌手不符的同名结果；支持纯符号歌名、跨文字标题及片头曲等附注精简，弱网下最长等待 10 秒
- 歌词源胶囊支持单击切源、双击只在当前来源重新匹配下一候选并记忆、长按恢复自动匹配；失败时保留正在显示的歌词
- 设置页提供歌词源管理：按歌曲合并 QQ 音乐、网易云和 LRCLIB 的选择记录，可搜索歌曲、预览各来源版本、指定结果、恢复初始匹配或清除全部匹配缓存
- 设置界面采用纯黑底与红色强调，动态背景独立分组；歌词字号与偏移集中调节，歌词源和偏移记忆可并排管理
- 展开与收起形态可分别保存歌词字号、颜色、动态背景、窗口尺寸和位置；全屏歌词沿用展开形态设置
- 启动设置页后每天最多静默检查一次 GitHub Releases；发现新版本时可跳转更新、稍后处理或仅忽略当前版本，也可在使用说明底部手动检查

## 兼容性

- Android 8.0（API 26）及以上
- 64 位 ARM 设备（arm64-v8a）；覆盖绝大多数现代 Android 手机，不支持纯 32 位旧机与 x86 模拟器
- 需要 Android System WebView
- 播放器需要提供标准 Android MediaSession

目前已在 vivo 设备上验证 Apple Music 与酷我音乐。代码也适配 QQ 音乐、网易云音乐、酷狗音乐、Spotify、YouTube Music、TIDAL、Musicolet、AIMP、VLC 等常见播放器。

不同手机厂商的后台省电策略可能影响长时间运行。如果悬浮窗被系统清理，请允许应用自启动，并将电池策略设为“不限制”。

## 安装与使用

1. 前往 [Releases](https://github.com/tcrrry/desktop-lyrics/releases/latest) 下载最新版 APK。
2. 安装并打开“桌面歌词”。
3. 依次授予“通知使用权”和“悬浮窗权限”。
4. 点击“开启歌词悬浮窗”，然后播放音乐。
5. 单击缩放图标切换标准/收起形态；长按并拖动可自由调整宽高。
6. 展开形态下，点击旋转图标可在外框尺寸保持不变的情况下，将悬浮窗内容旋转 90°。
7. 使用悬浮窗内的播放键或拖动进度条控制当前播放器；滑动浏览歌词后，点击歌词可跳转到对应时间。
8. 歌词不匹配时，双击“QQ音乐 / 网易云音乐 / LRCLIB”来源胶囊尝试该来源的下一候选；长按可清除这首歌在该来源的选择记忆。

## 权限与隐私

| 权限 | 用途 |
| --- | --- |
| 通知使用权 | 仅用于访问其他播放器公开的 MediaSession，不读取通知正文 |
| 悬浮窗 | 在其他应用上方显示实时歌词 |
| 网络访问 | 向公共歌词/音乐平台搜索歌词和备用封面，并按低频率检查 GitHub 新版本 |
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

### 1.08 新增入口

- “描边”位于无级调色左侧，“发音”位于补充翻译左侧，两行均按 2:8 分配空间。两个按钮与右侧操作胶囊同高、大小固定；关闭时为相同深色背景，开启时为与选中项一致的白红渐变，不再伸缩。
- 发音来自 QQ／网易云平台的发音轨，不把中文翻译当成发音，也不自动生成无依据的读音。展开与全屏按发音、原文、译文排列；发音与译文同字号、同亮度，突出原文。收起窗口足够高时采用相同三层排布，两行高度保留原文与译文，一行高度只显示主歌词；关闭发音时最多两行；无发音时保留原双行行为。
- 点击“添加下拉快捷磁贴”，或下拉系统快捷面板进入编辑，把“桌面歌词”拖入面板。首次使用需先授予通知使用权与悬浮窗权限。

## 常见问题

### 它会录制或分析手机正在播放的声音吗？

不会。应用不申请麦克风权限，也不录制系统音频；歌曲信息来自播放器公开的 MediaSession。

### 为什么需要“通知使用权”？

这是 Android 提供跨应用访问 MediaSession 的系统入口。桌面歌词只使用媒体会话数据，不读取聊天或普通通知正文。

### 所有播放器都能使用吗？

只要播放器正确提供标准 MediaSession，通常就能读取。个别播放器、系统定制或省电策略可能导致兼容性差异。

### 歌词为什么偶尔需要几秒才能出现？

首次识别歌曲时需要并行查询多个公开来源、验证候选质量，必要时再补充跨语言别名重试。匹配完成后，歌词会按照本机播放进度实时同步。

## 项目信息

- 当前正式版：`1.08`（versionCode 108），使用与 1.07 相同的原 Release 签名；下载见 Releases
- Android 包名：`com.tcrrry.desktoplyrics`
- 作者：B站 `@Tcrrrry`

如果这个项目对你有帮助，欢迎点亮右上角的 **Star**，让更多需要 Android 桌面歌词的人看到它。
