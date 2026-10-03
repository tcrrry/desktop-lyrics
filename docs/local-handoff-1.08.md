# 桌面歌词 1.08：本地 Codex 接手与发布

交接日期：2026-10-02。目标仓库：`tcrrry/desktop-lyrics`，不是 `live-music-sync` 或 AM++ 仓库。

## 用户当前要求与授权

用户要求先把云端修改上传，让下次本地 Codex 可以直接接手并发布 1.08。用户确认 **1.07 的原签名保存在本地 Codex 所在机器**；云端没有这些签名私密文件，具体本地路径未提供。优先复用该机器既有的签名配置、先前工作区和既有交接记录，不重新生成签名密钥，也不要求用户把密钥上传到 GitHub。

代码在 `feature/1.08`，草稿 PR：<https://github.com/tcrrry/desktop-lyrics/pull/5>。云端实现提交为 `aa1e8f7e8813fc12c123b6b34a9c17d9bd7737df`；后续交接文档提交也必须一并获取。仓库根目录 `AGENTS.md` 指向本文，使本地 Codex 进入仓库后能发现交接信息。当前正式发布仍为 `v1.07`；版本配置已改为 `versionName 1.08` / `versionCode 108`。

## 最终确认的功能与布局

1. 发音沿用 AM++ `fix/v1-1-pronunciation-ranking` 分支的平台处理：QQ `contentroma`、`roma=1` 与 `GetPlayLyricInfo` 备用接口，网易云 `romalrc` / `yromalrc` / `rromalrc`。优先交付可用主歌词，发音备用接口失败不使主歌词失败。
2. 展开及全屏排列为 **发音 → 原文 → 翻译**。发音和翻译使用相同字号、颜色亮度及非当前行淡化规则，原文保留视觉重点。
3. 收起窗口保留 1.07 一行／两行高度边界：一行只显示主歌词；双语模式下两行显示原文＋翻译；开启发音、有实际发音数据且窗口达到三行高度时显示发音＋原文＋翻译。关闭发音时最多两行。按窗口实际高度和字号计算，不强行改动已保存的窗口尺寸；缺发音时保持原行为。原文／双语／中文选项仍保留各自原有语义，上述三层描述以双语模式为基准。
4. 描边为自动对比色细描边，保护逐字渐变高亮；展开／收起分别记忆，全屏使用展开设置。实际使用组合 `drop-shadow` 给最终文字轮廓描边，不能直接换成会遮住透明文字渐变的 `-webkit-text-stroke`。
5. 两行设置的左右空间都是 **2:8**：描边在无级调色左边；发音在补充翻译左边。两个按钮共用同一函数，关闭时 32dp 白色圆按钮、红字；开启时 52×32dp 红色胶囊、白字，动画 220ms。再次点击关闭。
6. Android 下拉快捷设置增加“桌面歌词”磁贴，点击开启／关闭悬浮窗，状态随服务同步。缺权限时打开设置；Android 13+ 可请求系统添加磁贴，旧系统提示手动编辑面板；Android 14 使用 PendingIntent 打开设置。
7. 歌词缓存 schema 升为 4，保存发音数据并保留同一歌曲已验证的发音／逐字轨。顺带修复 Android 8／9 调用 API 29 音译接口的兼容问题。

## 主要代码位置

- `app/src/main/kotlin/com/tcrrry/desktoplyrics/DirectLyricsRepository.kt`：平台发音轨、JSON 字段 `romanizedLyrics`、备用接口及质量排序。
- `OptionalQqPronunciation.kt`：可选发音补全失败时保留歌词。
- `app/src/main/assets/lyrics_overlay.html`：三层顺序、辅助行亮度、逐字描边、收起 1/2/3 行规则。
- `MainActivity.kt` 与 `app/src/main/res/layout/activity_main.xml`：2:8 设置布局、共用按钮动画与添加磁贴入口。
- `LyricsOverlayService.kt`：偏好记忆及实时应用；`LyricsTileService.kt`、Manifest 和 `ic_lyrics_tile.xml`：系统快捷磁贴。
- `CHANGELOG.md`、`docs/releases/1.08.md`：本次变更与发布说明。

以上 Kotlin 文件未写完整目录时，均位于 `app/src/main/kotlin/com/tcrrry/desktoplyrics/`。

## 已完成的验证

- JDK 17 + Android SDK 34 下 `assembleRelease testDebugUnitTest lint` 在本地再次通过；正式 Release 已使用原密钥签名。
- `PlatformPronunciationTest` 9 项单元测试全部通过，覆盖平台轨解析、QRC 时间戳、持久化、备用接口失败与提前交付。
- `node tests/match-memory.test.cjs`、`node tests/readability.test.cjs` 通过。
- Chromium 实际运行 WebView 页面，验证发音开关、时间对齐、HTML 转义、逐字高亮与描边共存、发音／翻译同亮度，以及收起一／二／三行可见性。浏览器检查为云端临时脚本，仓库保留 Node 回归测试与预览图，未把浏览器脚本作为可复用测试上传。
- 网易云歌曲 ID `488388942` 实际接口返回 `code 200` 及非空 `romalrc`。
- `docs/assets/1.08-expanded-preview.png`、`1.08-compact-preview.png`、`1.08-white-background-preview.png` 为浏览器预览，不是真机截图。
- Debug 测试 APK 签名验证通过；包名 `com.tcrrry.desktoplyrics.dev`，版本 `1.08-test`，可与正式版共存。

**本地发布收尾**：正式 APK 的签名证书 SHA-256 已确认与 GitHub 1.07 APK 完全一致，生产包名、1.08 版本号和 arm64-v8a 架构均已核对。发布时没有 ADB 设备在线，因此没有新增 vivo 真机验证结论，也没有宣称快捷磁贴已在 vivo 真机测试通过。

## 本地接手步骤

1. 查看本地 `git status`，保留已有修改和私密配置。获取远端 `feature/1.08`，或创建该分支的独立工作区；不要用硬重置覆盖本地工作。原签名保留在原位置，新工作区如有需要使用本地配置引用它。
2. 确认该工作区使用 JDK 17、Android SDK 34，并让 `local.properties` 指向本地 SDK。
3. 复用原 `keystore.properties`。项目读取字段为 `storeFile`、`storePassword`、`keyAlias`、`keyPassword`；`storeFile` 按仓库根目录解析，也可使用有效绝对路径。不要打印密码或把私密文件加入提交。
4. 运行以下检查。Windows 使用 `gradlew.bat` 替代 `./gradlew`：

   ```sh
   ./gradlew assembleRelease testDebugUnitTest lint
   node tests/match-memory.test.cjs
   node tests/readability.test.cjs
   ```

5. 正式 APK 应为 `app/build/outputs/apk/release/app-release.apk`。如果仍只生成 `app-release-unsigned.apk`，说明签名未接入，先修复本地签名配置。
6. 使用 SDK 的 `apksigner verify --print-certs` 检查新正式 APK，并下载 `v1.07` 的原 APK进行同样检查。比较 **Signer certificate SHA-256 digest**，必须一致。用 `aapt dump badging` 确认生产包名 `com.tcrrry.desktoplyrics`、versionName `1.08`、versionCode `108`，不是 `.dev` / `1.08-test`。
7. 如手机已连接，直接覆盖安装签名后的正式 APK，验证：三层歌词顺序与亮度；收起 1/2/3 行切换及无发音歌曲；白背景上的逐字描边；两枚按钮动画；展开／收起／全屏设置切换；vivo 磁贴添加、开关与状态同步；缺权限引导及后台运行。若没有手机连接，如实记录未验证范围；用户并未要求以真机验证作为另一次审批，不应只因为手机不可用就重复询问是否允许发布。实际失败应先修复。

## 上传产物与正式发布

云端已创建 **v1.08 草稿 Release**（Release ID `401509151`），不是正式发布，也不会替换 `releases/latest` 的 1.07。测试 APK 附件上传两次均返回 `HTTP 400: Bad Content-Length`，当前草稿**没有 APK 附件**，本地直接按上述命令构建即可。云端独立 Debug APK 文件名 `Desktop-Lyrics-1.08-test-arm64-v8a.apk`，SHA-256：`f2e3b74713a8bdc7316b4677219f2980d834b32fa82f2fb782506455d8175e94`。以 GitHub 实际资产列表为准；无需等待云端测试附件，也不用把 Debug 包作为正式下载附件。

签名及检查完成后：

1. 更新 README 中“开发版本／待签名”状态为当前正式版 1.08，并将中英文版本信息、`docs/releases/1.08.md` 的验证记录改为最终事实。保留所有用户确认的功能，不把临时测试情况写成已验证。
2. 将这些收尾提交推到 `feature/1.08`，把 PR #5 从草稿改为 ready，再合并到 `main`。如果本地发现其他修改／主分支新增提交，按实际内容解决冲突并进行相关检查。
3. 将签名 APK命名为 `Desktop-Lyrics-1.08-arm64-v8a.apk`，上传到已有 `v1.08` 草稿。正式发布前删除草稿中的 `.dev` 测试 APK，让正式下载明确指向生产包。
4. 将草稿的目标提交改为合并后的 `main` 提交，标题改为“桌面歌词 1.08”，说明使用整理后的 `docs/releases/1.08.md`。发布 `v1.08` 为非预发布且 latest。若草稿不存在，则创建同名正式 Release；若正式 Release 已存在，先核对状态，避免重复发布。
5. 核对 `releases/latest` 指向 `v1.08`、正确签名生产 APK可下载、资源名无 `test`、下载后 SHA-256 与本地正式包一致。报告 Release 链接、签名一致性和实际验证范围。

用户已授权下次本地 Codex 完成上述本版本发布；不要仅重新提出计划，或因为这份交接文档而再要求泛泛的发布确认。使用本地现有账户权限；实际缺失签名文件、密码配置或发布权限时才说明阻塞。
