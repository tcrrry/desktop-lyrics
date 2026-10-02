package com.tcrrry.desktoplyrics

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.util.Base64
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A user-started overlay that consumes MediaSession callbacks directly on-device.
 * Network requests are only used to resolve lyrics/cover art; playback synchronization
 * never waits for the website status polling path.
 */
@SuppressLint("ForegroundServiceType")
class LyricsOverlayService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    private val windowManager by lazy { getSystemService(Context.WINDOW_SERVICE) as WindowManager }
    private val sessionManager by lazy { getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager }
    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private val listenerComponent by lazy { ComponentName(this, MediaListenerService::class.java) }
    private val lyricsRepository = DirectLyricsRepository()
    private val lyricsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var overlayRoot: FrameLayout? = null
    private var overlayContent: FrameLayout? = null
    private var chromeBar: LinearLayout? = null
    private var dragTouchArea: View? = null
    private var rotateButton: TextView? = null
    private var scaleButton: TextView? = null
    private var closeButton: TextView? = null
    private var webView: WebView? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var webReady = false
    private var fullscreenHost: FrameLayout? = null
    private var overlayWebHost: ViewGroup? = null
    private var singleTap: Runnable? = null
    private var compact = false
    private val hideCompactControls = Runnable {
        if (compact) chromeBar?.animate()?.alpha(0f)?.setDuration(350)?.withEndAction {
            if (compact && chromeBar?.alpha == 0f) chromeBar?.visibility = View.INVISIBLE
        }?.start()
    }

    private fun revealCompactControls(scheduleHide: Boolean = true) {
        mainHandler.removeCallbacks(hideCompactControls)
        chromeBar?.animate()?.cancel()
        chromeBar?.visibility = View.VISIBLE
        chromeBar?.animate()?.alpha(1f)?.setDuration(180)?.start()
        if (compact && scheduleHide) mainHandler.postDelayed(hideCompactControls, 2500L)
    }
    private var overlayRotated = false
    private var backgroundMode = BACKGROUND_DEFAULT
    private var lyricStrokeEnabled = false
    private var fontScalePercent = FONT_SCALE_DEFAULT_PERCENT
    private var lyricColor = LYRIC_COLOR_DEFAULT
    private var expandedBackgroundMode = BACKGROUND_DEFAULT
    private var compactBackgroundMode = BACKGROUND_DEFAULT
    private var expandedFontScalePercent = FONT_SCALE_DEFAULT_PERCENT
    private var compactFontScalePercent = FONT_SCALE_DEFAULT_PERCENT
    private var expandedLyricColor = LYRIC_COLOR_DEFAULT
    private var compactLyricColor = LYRIC_COLOR_DEFAULT
    private var lyricOffsetMs = 0
    private var currentLyricIdentity = ""
    private var currentLyricSource = ""
    private var currentLyricTitle = ""
    private var currentLyricArtist = ""
    private var translationMode = TRANSLATION_BILINGUAL
    private var monitorStarted = false
    private var lastDisplayWidth = 0
    private var lastDisplayHeight = 0
    private var currentController: MediaController? = null
    private var pendingSnapshot: JSONObject? = null
    private var cachedArtworkKey = ""
    private var cachedArtworkDataUrl = ""
    private var snapshotScheduled = false
    private var closeBlockedUntilElapsedMs = 0L
    @Volatile private var latestLyricsRequestId = 0
    private var supplementJob: kotlinx.coroutines.Job? = null
    private var supplementGeneration = 0
    private val supplements by lazy { SupplementTranslation(this) }

    private val dispatchRunnable = Runnable {
        snapshotScheduled = false
        dispatchSnapshot()
    }
    private val sessionRefreshRunnable = object : Runnable {
        override fun run() {
            if (!monitorStarted) return
            refreshActiveSessions()
            mainHandler.postDelayed(this, 2_000L)
        }
    }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = scheduleSnapshot()
        override fun onPlaybackStateChanged(state: PlaybackState?) = scheduleSnapshot()
        override fun onSessionDestroyed() = refreshActiveSessions()
    }

    private val activeSessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            selectController(controllers.orEmpty())
        }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        mainHandler.post { adaptOverlayToDisplay() }
        mainHandler.postDelayed({ adaptOverlayToDisplay() }, 220L)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        isRunning = true
        announceOverlayState()
        expandedBackgroundMode = normalizedBackgroundMode(
            prefs.getString(PREF_BACKGROUND_MODE, BACKGROUND_DEFAULT)
        )
        compactBackgroundMode = normalizedBackgroundMode(
            prefs.getString(PREF_BACKGROUND_MODE_COMPACT, expandedBackgroundMode)
        )
        expandedFontScalePercent = normalizedFontScale(
            prefs.getInt(PREF_FONT_SCALE_PERCENT, FONT_SCALE_DEFAULT_PERCENT)
        )
        compactFontScalePercent = normalizedFontScale(
            prefs.getInt(PREF_FONT_SCALE_COMPACT_PERCENT, expandedFontScalePercent)
        )
        expandedLyricColor = normalizedLyricColor(prefs.getString(PREF_LYRIC_COLOR, LYRIC_COLOR_DEFAULT))
        compactLyricColor = normalizedLyricColor(
            prefs.getString(PREF_LYRIC_COLOR_COMPACT, expandedLyricColor)
        )
        syncActiveVisualPreferences(false)
        lyricOffsetMs = prefs.getInt(PREF_LYRIC_OFFSET_MS, 0)
            .coerceIn(LYRIC_OFFSET_MIN_MS, LYRIC_OFFSET_MAX_MS)
        translationMode = normalizedTranslationMode(
            prefs.getString(PREF_TRANSLATION_MODE, TRANSLATION_BILINGUAL)
        )
        overlayRotated = prefs.getBoolean(PREF_OVERLAY_ROTATED, false)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "com.tcrrry.desktoplyrics.REFRESH_MATCHES") {
            webView?.evaluateJavascript("window.LobstaOverlay?.refreshMatchManagement();", null)
            return START_STICKY
        }
        if (intent?.action == ACTION_SET_READING_OPTIONS) {
            if (overlayRoot == null) {
                stopSelf()
                return START_NOT_STICKY
            }
            syncActiveVisualPreferences(displayedVisualTargetIsCompact())
            applyReadingOptions()
            return START_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_SET_BACKGROUND) {
            val targetCompact = intent.getBooleanExtra(EXTRA_TARGET_COMPACT, false)
            val value = normalizedBackgroundMode(
                intent.getStringExtra(EXTRA_BACKGROUND_MODE)
            )
            if (targetCompact) compactBackgroundMode = value else expandedBackgroundMode = value
            prefs.edit().putString(
                if (targetCompact) PREF_BACKGROUND_MODE_COMPACT else PREF_BACKGROUND_MODE,
                value
            ).apply()
            if (displayedVisualTargetIsCompact() == targetCompact) {
                backgroundMode = value
                applyBackgroundMode()
            }
            if (overlayRoot != null) return START_STICKY
        }

        if (intent?.action == ACTION_SET_FONT_SCALE) {
            val targetCompact = intent.getBooleanExtra(EXTRA_TARGET_COMPACT, false)
            val previousPercent = fontScalePercent
            val value = normalizedFontScale(
                intent.getIntExtra(EXTRA_FONT_SCALE_PERCENT, FONT_SCALE_DEFAULT_PERCENT)
            )
            if (targetCompact) compactFontScalePercent = value else expandedFontScalePercent = value
            prefs.edit().putInt(
                if (targetCompact) PREF_FONT_SCALE_COMPACT_PERCENT else PREF_FONT_SCALE_PERCENT,
                value
            ).apply()
            if (displayedVisualTargetIsCompact() == targetCompact) {
                fontScalePercent = value
                applyFontScale(
                    previousPercent,
                    adjustCompactHeight = targetCompact && fullscreenHost == null
                )
            }
            if (overlayRoot != null) return START_STICKY
        }

        if (intent?.action == ACTION_SET_LYRIC_COLOR) {
            val targetCompact = intent.getBooleanExtra(EXTRA_TARGET_COMPACT, false)
            val value = normalizedLyricColor(intent.getStringExtra(EXTRA_LYRIC_COLOR))
            if (targetCompact) compactLyricColor = value else expandedLyricColor = value
            prefs.edit().putString(
                if (targetCompact) PREF_LYRIC_COLOR_COMPACT else PREF_LYRIC_COLOR,
                value
            ).apply()
            if (displayedVisualTargetIsCompact() == targetCompact) {
                lyricColor = value
                applyLyricColor()
            }
            if (overlayRoot != null) return START_STICKY
        }

        if (intent?.action == ACTION_SET_LYRIC_OFFSET) {
            lyricOffsetMs = intent.getIntExtra(EXTRA_LYRIC_OFFSET_MS, 0)
                .coerceIn(LYRIC_OFFSET_MIN_MS, LYRIC_OFFSET_MAX_MS)
            val editor = prefs.edit().putInt(PREF_LYRIC_OFFSET_MS, lyricOffsetMs)
            activeLyricOffsetPreferenceKey()?.let { key ->
                if (lyricOffsetMs == 0) editor.remove(key) else editor.putInt(key, lyricOffsetMs)
                updateLyricOffsetIndex(
                    editor, key, currentLyricIdentity, currentLyricSource,
                    currentLyricTitle, currentLyricArtist, lyricOffsetMs
                )
            }
            editor.apply()
            applyLyricOffset()
            if (overlayRoot != null) return START_STICKY
        }

        if (intent?.action == ACTION_DELETE_LYRIC_OFFSET_MEMORY) {
            val key = intent.getStringExtra(EXTRA_LYRIC_OFFSET_MEMORY_KEY).orEmpty()
            if (key.startsWith(PREF_LYRIC_OFFSET_ENTRY_PREFIX)) {
                val editor = prefs.edit().remove(key)
                updateLyricOffsetIndex(editor, key, "", "", "", "", 0)
                if (activeLyricOffsetPreferenceKey() == key) {
                    lyricOffsetMs = 0
                    editor.putInt(PREF_LYRIC_OFFSET_MS, 0)
                    applyLyricOffset()
                }
                editor.apply()
                announceOverlayState()
            }
            if (overlayRoot != null) return START_STICKY
        }

        if (intent?.action == ACTION_CLEAR_LYRIC_OFFSET_MEMORIES) {
            val editor = prefs.edit()
            prefs.all.keys.filter { it.startsWith(PREF_LYRIC_OFFSET_ENTRY_PREFIX) }.forEach(editor::remove)
            lyricOffsetMs = 0
            editor.putInt(PREF_LYRIC_OFFSET_MS, 0).remove(PREF_LYRIC_OFFSET_INDEX).apply()
            applyLyricOffset()
            announceOverlayState()
            if (overlayRoot != null) return START_STICKY
        }

        if (intent?.action == ACTION_SET_TRANSLATION_MODE) {
            translationMode = normalizedTranslationMode(
                intent.getStringExtra(EXTRA_TRANSLATION_MODE)
            )
            prefs.edit().putString(PREF_TRANSLATION_MODE, translationMode).apply()
            applyTranslationMode()
            if (overlayRoot != null) return START_STICKY
        }

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        startAsForeground()
        if (overlayRoot == null) createOverlay()
        startMediaMonitor()
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        mainHandler.removeCallbacksAndMessages(null)
        lyricsScope.cancel()
        lyricsRepository.close()
        stopMediaMonitor()
        val player = webView
        (player?.parent as? ViewGroup)?.removeView(player)
        overlayRoot?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        player?.apply {
            stopLoading()
            loadUrl("about:blank")
            destroy()
        }
        webView = null
        overlayRoot = null
        overlayContent = null
        chromeBar = null
        dragTouchArea = null
        rotateButton = null
        scaleButton = null
        closeButton = null
        isRunning = false
        announceOverlayState()
        super.onDestroy()
    }

    private fun announceOverlayState() {
        android.service.quicksettings.TileService.requestListeningState(
            this, android.content.ComponentName(this, LyricsTileService::class.java)
        )
        sendBroadcast(
            Intent(ACTION_STATE_CHANGED)
                .setPackage(packageName)
                .putExtra(EXTRA_RUNNING, isRunning)
        )
    }

    fun refreshSupplementTranslation() {
        mainHandler.post {
            webView?.evaluateJavascript("window.LobstaOverlay.refreshSupplement();", null)
        }
    }

    private inner class LyricsJavascriptBridge {
        @JavascriptInterface
        fun lyricSourceOffset(identity: String, source: String, title: String, artist: String): Int {
            val safeIdentity = identity.trim().take(600)
            val safeSource = source.trim().take(120)
            val safeTitle = title.trim().take(300)
            val safeArtist = artist.trim().take(300)
            if (safeIdentity.isBlank() || safeSource.isBlank()) return 0
            val preferenceKey = lyricOffsetPreferenceKey(safeIdentity, safeSource)
            val remembered = prefs.getInt(preferenceKey, 0)
                .coerceIn(LYRIC_OFFSET_MIN_MS, LYRIC_OFFSET_MAX_MS)
            mainHandler.post {
                currentLyricIdentity = safeIdentity
                currentLyricSource = safeSource
                currentLyricTitle = safeTitle
                currentLyricArtist = safeArtist
                lyricOffsetMs = remembered
                prefs.edit().putInt(PREF_LYRIC_OFFSET_MS, remembered).apply()
                announceOverlayState()
            }
            return remembered
        }

        @JavascriptInterface
        fun supplementSettings() {
            mainHandler.post { startActivity(Intent(this@LyricsOverlayService, TranslationSettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }

        @JavascriptInterface
        fun supplementStrategy(): String {
            val translationPrefs = getSharedPreferences("supplement_translation", Context.MODE_PRIVATE)
            val profile = TranslationApiProfiles.find(
                this@LyricsOverlayService,
                translationPrefs.getString("active_api_profile", null)
            )
            val endpoint = translationPrefs.getString(
                TranslationApiProfiles.endpointKey(profile.id), profile.defaultEndpoint
            ).orEmpty().lowercase()
            val model = translationPrefs.getString(
                TranslationApiProfiles.modelKey(profile.id), profile.defaultModel
            ).orEmpty().lowercase()
            val provider = when {
                profile.id == "gemini" || "generativelanguage.googleapis.com" in endpoint || "gemini" in model -> "gemini"
                profile.id == "glm" || "bigmodel.cn" in endpoint || model.startsWith("glm-") -> "glm"
                profile.id == "deepseek" || "deepseek.com" in endpoint || model.startsWith("deepseek-") -> "deepseek"
                else -> "default"
            }
            return when (provider) {
                "gemini" -> JSONObject().put("behind", 2).put("ahead", 34).put("prefetch", 10).toString()
                "glm" -> JSONObject().put("behind", 2).put("ahead", 22).put("prefetch", 7).toString()
                else -> JSONObject().put("behind", 2).put("ahead", 12).put("prefetch", 4).toString()
            }
        }

        @JavascriptInterface
        fun cancelSupplement() {
            mainHandler.post { supplementGeneration++; supplementJob?.cancel() }
        }

        @JavascriptInterface
        fun translateMissing(requestId: Int, payload: String) {
            if (payload.length > 250000) return
            mainHandler.post {
                supplementJob?.cancel()
                val generation = ++supplementGeneration
                fun report(data: JSONObject) {
                    mainHandler.post {
                        if (generation == supplementGeneration && webReady) webView?.evaluateJavascript(
                            "window.LobstaOverlay.receiveSupplement($requestId,$data);", null)
                    }
                }
                supplementJob = lyricsScope.launch {
                    try {
                        val mode = getSharedPreferences("supplement_translation", Context.MODE_PRIVATE).getString("mode", "off")
                        if (mode == "off") { report(JSONObject().put("status", "off")); return@launch }
                        report(JSONObject().put("status", "working"))
                        supplements.translate(payload) { report(it) }
                        report(JSONObject().put("status", "done"))
                    } catch (cancel: kotlinx.coroutines.CancellationException) {
                        throw cancel
                    } catch (error: Exception) {
                        val message = when (error) {
                            is IllegalArgumentException -> error.message.orEmpty().take(180)
                            is java.net.SocketTimeoutException -> "翻译服务连接超时，请检查手机网络或 API 服务"
                            is java.io.IOException -> "翻译服务网络连接失败：${error.message.orEmpty().take(120)}"
                            else -> "补充翻译暂不可用：${error.message.orEmpty().take(120)}"
                        }
                        report(JSONObject().put("status", "error").put("message", message))
                    }
                }
            }
        }
        @JavascriptInterface
        fun mediaCommand(command: String) {
            mainHandler.post {
                val controller = currentController ?: return@post
                runCatching {
                    when (command) {
                        "play" -> controller.transportControls.play()
                        "pause" -> controller.transportControls.pause()
                        "previous" -> controller.transportControls.skipToPrevious()
                        "next" -> controller.transportControls.skipToNext()
                        else -> return@runCatching
                    }
                }.onFailure {
                    Log.w(LOG_TAG, "Media command failed: $command", it)
                }
                mainHandler.postDelayed({ scheduleSnapshot() }, 180L)
            }
        }

        @JavascriptInterface
        fun mediaSeek(positionMs: Double) {
            if (!positionMs.isFinite()) return
            mainHandler.post {
                val controller = currentController ?: return@post
                val duration = controller.metadata
                    ?.getLong(MediaMetadata.METADATA_KEY_DURATION)
                    ?.coerceAtLeast(0L)
                    ?: 0L
                val requested = positionMs.toLong().coerceAtLeast(0L)
                val target = if (duration > 0L) requested.coerceAtMost(duration) else requested
                runCatching {
                    controller.transportControls.seekTo(target)
                }.onFailure {
                    Log.w(LOG_TAG, "Media seek failed: $target", it)
                }
                mainHandler.postDelayed({ scheduleSnapshot() }, 180L)
            }
        }

        @JavascriptInterface
        fun rematchLyrics(track: String, artist: String, album: String, durationMs: Double,
                          source: String, excludedJson: String, requestId: Int, operationId: Int) {
            if (requestId != latestLyricsRequestId || track.isBlank()) return
            lyricsScope.launch {
                val payload = try {
                    val array = org.json.JSONArray(excludedJson)
                    val excluded = (0 until array.length()).map { array.optString(it) }.toSet()
                    val result = lyricsRepository.rematch(source, track, artist, album,
                        durationMs.takeIf { it.isFinite() && it > 0 }?.toLong() ?: 0L, excluded)
                    result?.toJson() ?: JSONObject().put("error", "没有找到更合适的版本，已保留当前歌词")
                } catch (error: Exception) {
                    val cooling = error.message.orEmpty().contains("cool", ignoreCase = true)
                    JSONObject().put("error", if (cooling) "此歌词源暂时限流或不可用，请稍后重试" else "重新匹配失败，已保留当前歌词")
                }
                mainHandler.post {
                    if (requestId == latestLyricsRequestId && webReady) webView?.evaluateJavascript(
                        "window.LobstaOverlay && window.LobstaOverlay.receiveRematch($requestId,$operationId,$payload);", null)
                }
            }
        }

        @JavascriptInterface
        fun readMatchMemory(): String = prefs.getString("match_memory_v2", "").orEmpty()

        @JavascriptInterface
        fun writeMatchMemory(value: String) {
            if (runCatching { org.json.JSONArray(value); true }.getOrDefault(false)) {
                prefs.edit().putString("match_memory_v2", value).apply()
            }
        }

        @JavascriptInterface
        fun requestLyrics(
            track: String,
            artist: String,
            album: String,
            durationMs: Double,
            requestId: Int,
            needsRemoteCover: Boolean
        ) {
            if (track.isBlank() || requestId <= 0) return
            latestLyricsRequestId = requestId
            lyricsScope.launch {
                val startedAt = SystemClock.elapsedRealtime()
                val coverLookup = if (needsRemoteCover) {
                    async { lyricsRepository.resolveCover(track, artist) }
                } else null
                val result = lyricsRepository.resolveLyrics(
                    track,
                    artist,
                    album,
                    durationMs.takeIf { it.isFinite() && it > 0 }?.toLong() ?: 0L,
                    onPartial = { partial ->
                        if (requestId == latestLyricsRequestId) deliverLyricsResult(requestId, partial)
                    }
                )
                if (requestId != latestLyricsRequestId) {
                    coverLookup?.cancel()
                    return@launch
                }
                Log.i(
                    LOG_TAG,
                    "Direct lyrics source=${result.source.ifBlank { "none" }} " +
                        "found=${result.lyrics.isNotBlank()} elapsedMs=${SystemClock.elapsedRealtime() - startedAt}"
                )
                deliverLyricsResult(requestId, result)

                if (needsRemoteCover && result.cover.isBlank()) {
                    val cover = runCatching { coverLookup?.await().orEmpty() }.getOrDefault("")
                    if (cover.isNotBlank() && requestId == latestLyricsRequestId) {
                        deliverRemoteCover(requestId, cover)
                    }
                } else {
                    coverLookup?.cancel()
                }
            }
        }
    }

    private fun deliverLyricsResult(requestId: Int, result: DirectLyricsRepository.Result) {
        val payload = result.toJson().toString()
        mainHandler.post {
            if (requestId != latestLyricsRequestId || !webReady) return@post
            webView?.evaluateJavascript(
                "window.LobstaOverlay && window.LobstaOverlay.receiveLyrics($requestId,$payload);",
                null
            )
        }
    }

    private fun deliverRemoteCover(requestId: Int, cover: String) {
        val encodedCover = JSONObject.quote(cover)
        mainHandler.post {
            if (requestId != latestLyricsRequestId || !webReady) return@post
            webView?.evaluateJavascript(
                "window.LobstaOverlay && window.LobstaOverlay.receiveRemoteCover($requestId,$encodedCover);",
                null
            )
        }
    }

    private fun startAsForeground() {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(this, LyricsOverlayService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("${getString(R.string.app_name)} 正在监听")
            .setContentText("本地实时同步当前媒体会话")
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "关闭悬浮窗", stopPendingIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "歌词悬浮窗",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持本地歌词悬浮窗与 MediaSession 实时同步"
                setShowBadge(false)
            }
        )
    }

    @Suppress("SetJavaScriptEnabled")
    private fun createOverlay() {
        val (screenWidth, screenHeight) = currentDisplaySize()
        val safeBounds = currentSafeDisplayBounds()
        lastDisplayWidth = screenWidth
        lastDisplayHeight = screenHeight
        val storedExpandedWidth = prefs.getInt(
            "width",
            min(dp(360), max(1, safeBounds.width() - dp(24)))
        )
        val storedCompactWidth = prefs.getInt(PREF_COMPACT_WIDTH, storedExpandedWidth)
        val wasCompact = prefs.getBoolean("compact", false)
        val storedNormalHeight = prefs.getInt("height", dp(520))
        syncActiveVisualPreferences(wasCompact)
        val compactMinimumHeight = dp(compactMinimumHeightDp(compactFontScalePercent))
        val storedCompactHeight = prefs.getInt("compact_height_v3", max(dp(48), compactMinimumHeight))
        val activeStoredHeight = if (wasCompact) storedCompactHeight else storedNormalHeight
        compact = activeStoredHeight <= dp(COMPACT_MAX_HEIGHT_DP)
        syncActiveVisualPreferences(compact)
        val normalHeightSource = if (!compact && wasCompact) activeStoredHeight else storedNormalHeight
        val compactHeightSource = if (compact && !wasCompact) activeStoredHeight else storedCompactHeight
        val normalSize = fittedLogicalOverlaySize(
            storedExpandedWidth,
            normalHeightSource,
            isCompact = false,
            rotated = overlayRotated,
            safeBounds = safeBounds
        )
        val compactSize = fittedLogicalOverlaySize(
            storedCompactWidth,
            compactHeightSource,
            isCompact = true,
            rotated = overlayRotated,
            safeBounds = safeBounds
        )
        val normalHeight = normalSize.second
        val compactHeight = compactSize.second
        val activeLogicalSize = if (compact) compactSize else normalSize
        val activeWindowSize = logicalToWindowSize(activeLogicalSize, overlayRotated)
        if (compact != wasCompact) {
            val migration = prefs.edit().putBoolean("compact", compact)
            if (compact) migration.putInt("compact_height_v3", compactHeight)
            else migration.putInt("height", normalHeight)
            migration.apply()
        }

        val params = WindowManager.LayoutParams(
            activeWindowSize.first,
            activeWindowSize.second,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setFitInsetsTypes(0)
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            x = prefs.getInt(
                positionXPreferenceKey(compact),
                prefs.getInt(
                    "x",
                    max(safeBounds.left, safeBounds.right - activeWindowSize.first - dp(12))
                )
            ).coerceIn(
                safeBounds.left,
                max(safeBounds.left, safeBounds.right - activeWindowSize.first)
            )
            y = prefs.getInt(
                positionYPreferenceKey(compact),
                prefs.getInt("y", dp(96))
            ).coerceIn(
                safeBounds.top,
                max(
                    safeBounds.top,
                    safeBounds.bottom - activeWindowSize.second
                )
            )
        }
        windowParams = params

        val root = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            setBackgroundColor(Color.TRANSPARENT)
        }
        overlayRoot = root
        root.setOnApplyWindowInsetsListener { _, insets ->
            mainHandler.post {
                if (fullscreenHost == null) {
                    val safe = currentSafeDisplayBounds()
                    windowParams?.let { lp ->
                        val x = lp.x.coerceIn(safe.left, max(safe.left, safe.right - lp.width))
                        val y = lp.y.coerceIn(safe.top, max(safe.top, safe.bottom - lp.height))
                        if (x != lp.x || y != lp.y) {
                            lp.x = x; lp.y = y
                            overlayRoot?.let { windowManager.updateViewLayout(it, lp) }
                        }
                    }
                }
            }
            insets
        }

        val content = FrameLayout(this).apply {
            clipToOutline = true
            elevation = dp(14).toFloat()
            background = overlayBackground(compact)
        }
        overlayContent = content
        root.addView(
            content,
            rotatedContentLayoutParams(params.width, params.height, overlayRotated)
        )
        content.rotation = if (overlayRotated) 90f else 0f

        val chrome = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 0)
            setBackgroundColor(Color.TRANSPARENT)
        }
        chromeBar = chrome

        val dragArea = View(this)
        dragTouchArea = dragArea

        val closeControl = chromeButton("") {
            if (SystemClock.elapsedRealtime() >= closeBlockedUntilElapsedMs) {
                stopSelf()
            }
        }.apply {
            contentDescription = "关闭悬浮窗"
            setChromeIcon(this, R.drawable.ic_overlay_close)
        }
        closeButton = closeControl

        val resizeButton = chromeButton("") {
            val pending = singleTap
            if (pending != null) {
                mainHandler.removeCallbacks(pending)
                singleTap = null
                startActivity(Intent(this, FullscreenLyricsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } else {
                singleTap = Runnable { singleTap = null; toggleCompact() }.also {
                    mainHandler.postDelayed(it, ViewConfiguration.getDoubleTapTimeout().toLong())
                }
            }
        }.apply {
            contentDescription = "切换收起或展开，长按拖动可调整大小"
            setChromeIcon(this, R.drawable.ic_overlay_resize_up_left)
        }
        scaleButton = resizeButton

        val rotateControl = chromeButton("") {
            toggleOverlayOrientation()
        }.apply {
            contentDescription = "将整个悬浮窗旋转90度"
            setChromeIcon(this, R.drawable.ic_screen_rotation)
        }
        rotateButton = rotateControl

        val dragTouch = object : View.OnTouchListener {
            var downRawX = 0f
            var downRawY = 0f
            var downX = 0
            var downY = 0

            override fun onTouch(view: View, event: MotionEvent): Boolean {
                val lp = windowParams ?: return false
                if (compact) {
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) revealCompactControls(false)
                    if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) revealCompactControls()
                }
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downRawX = event.rawX
                        downRawY = event.rawY
                        downX = lp.x
                        downY = lp.y
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val safe = currentSafeDisplayBounds()
                        val maxX = max(safe.left, safe.right - lp.width)
                        val maxY = max(safe.top, safe.bottom - lp.height)
                        lp.x = (downX + event.rawX - downRawX).toInt()
                            .coerceIn(safe.left, maxX)
                        lp.y = (downY + event.rawY - downRawY).toInt()
                            .coerceIn(safe.top, maxY)
                        overlayRoot?.let { windowManager.updateViewLayout(it, lp) }
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        saveOverlayGeometry(compact, lp, saveSize = false)
                        return true
                    }
                }
                return false
            }
        }
        dragArea.setOnTouchListener(dragTouch)

        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        resizeButton.setOnTouchListener(object : View.OnTouchListener {
            var downRawX = 0f
            var downRawY = 0f
            var downX = 0
            var downY = 0
            var downWidth = 0
            var downHeight = 0
            var resizing = false
            var movedBeforeLongPress = false
            var pendingLongPress: Runnable? = null

            fun cancelLongPress() {
                pendingLongPress?.let(mainHandler::removeCallbacks)
                pendingLongPress = null
            }

            fun saveSize(lp: WindowManager.LayoutParams) {
                saveOverlayGeometry(compact, lp, saveSize = true)
            }

            override fun onTouch(view: View, event: MotionEvent): Boolean {
                val lp = windowParams ?: return false
                if (compact) revealCompactControls(event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL)
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downRawX = event.rawX
                        downRawY = event.rawY
                        downX = lp.x
                        downY = lp.y
                        downWidth = lp.width
                        downHeight = lp.height
                        resizing = false
                        movedBeforeLongPress = false
                        pendingLongPress = Runnable {
                            singleTap?.let(mainHandler::removeCallbacks)
                            singleTap = null
                            resizing = true
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        }.also {
                            mainHandler.postDelayed(it, ViewConfiguration.getLongPressTimeout().toLong())
                        }
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val deltaX = event.rawX - downRawX
                        val deltaY = event.rawY - downRawY
                        if (!resizing && max(kotlin.math.abs(deltaX), kotlin.math.abs(deltaY)) > touchSlop.toFloat()) {
                            movedBeforeLongPress = true
                            cancelLongPress()
                        }
                        if (!resizing) return true
                        val safe = currentSafeDisplayBounds()
                        if (overlayRotated) {
                            // The logical top/right resize corner becomes the physical
                            // bottom/right corner after the whole canvas turns clockwise.
                            val minimumPhysicalWidth = dp(compactMinimumHeightDp(fontScalePercent))
                            val minimumPhysicalHeight = minimumOverlayWidth(safe.height())
                            val maximumPhysicalWidth = max(minimumPhysicalWidth, safe.right - downX)
                            val maximumPhysicalHeight = max(minimumPhysicalHeight, safe.bottom - downY)
                            lp.width = (downWidth + deltaX).toInt()
                                .coerceIn(minimumPhysicalWidth, maximumPhysicalWidth)
                            lp.height = (downHeight + deltaY).toInt()
                                .coerceIn(minimumPhysicalHeight, maximumPhysicalHeight)
                            lp.x = downX
                            lp.y = downY
                        } else {
                            val minimumWidth = minimumOverlayWidth(safe.width())
                            val availableWidth = max(minimumWidth, safe.right - downX)
                            lp.width = (downWidth + deltaX).toInt()
                                .coerceIn(minimumWidth, availableWidth)
                            val bottomEdge = downY + downHeight
                            val minimumHeight = dp(compactMinimumHeightDp(fontScalePercent))
                            val maximumHeight = max(minimumHeight, bottomEdge - safe.top)
                            lp.height = (downHeight - deltaY).toInt()
                                .coerceIn(minimumHeight, maximumHeight)
                            lp.y = bottomEdge - lp.height
                        }
                        applyOverlayRotationLayout()
                        overlayRoot?.let { windowManager.updateViewLayout(it, lp) }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        cancelLongPress()
                        if (resizing) {
                            val logicalSize = windowToLogicalSize(lp.width, lp.height, overlayRotated)
                            val compactForSize = logicalSize.second <= dp(COMPACT_MAX_HEIGHT_DP)
                            if (compactForSize != compact) {
                                setCompactUi(compactForSize)
                            }
                            saveSize(lp)
                        } else if (!movedBeforeLongPress) {
                            view.performClick()
                        }
                        resizing = false
                        return true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        cancelLongPress()
                        if (resizing) {
                            val logicalSize = windowToLogicalSize(lp.width, lp.height, overlayRotated)
                            val compactForSize = logicalSize.second <= dp(COMPACT_MAX_HEIGHT_DP)
                            if (compactForSize != compact) {
                                setCompactUi(compactForSize)
                            }
                            saveSize(lp)
                        }
                        resizing = false
                        return true
                    }
                }
                return false
            }
        })

        val webContainer = FrameLayout(this)
        content.addView(webContainer, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        val player = WebView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = false
            settings.mediaPlaybackRequiresUserGesture = false
            addJavascriptInterface(LyricsJavascriptBridge(), "LobstaNativeLyrics")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean = true

                override fun onPageFinished(view: WebView?, url: String?) {
                    webReady = true
                    applyFontScale(fontScalePercent, adjustCompactHeight = false)
                    applyLyricColor()
                    applyLyricOffset()
                    applyTranslationMode()
                    evaluateJavascript(
                        "window.LobstaOverlay && window.LobstaOverlay.setCompact($compact);",
                        null
                    )
                    applyHorizontalWebLayout()
                    applyBackgroundMode()
                    pendingSnapshot?.let { deliverToWeb(it) } ?: scheduleSnapshot()
                }
            }
            loadUrl("file:///android_asset/lyrics_overlay.html")
        }
        webView = player
        webContainer.addView(player, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        content.addView(dragArea, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        content.addView(chrome, FrameLayout.LayoutParams(
            if (compact) dp(26) else ViewGroup.LayoutParams.WRAP_CONTENT,
            if (compact) ViewGroup.LayoutParams.MATCH_PARENT else dp(28),
            if (compact) Gravity.END or Gravity.CENTER_VERTICAL else Gravity.END or Gravity.BOTTOM
        ))
        updateControlLayout(compact)

        windowManager.addView(root, params)
    }

    fun attachFullscreen(host: FrameLayout): Boolean {
        if (!webReady) return false
        val player = webView ?: return false
        if (fullscreenHost != null) return false
        overlayWebHost = player.parent as? ViewGroup
        overlayWebHost?.removeView(player)
        fullscreenHost = host
        overlayRoot?.visibility = View.GONE
        host.addView(player, FrameLayout.LayoutParams(-1, -1))
        syncActiveVisualPreferences(false)
        player.evaluateJavascript("window.LobstaOverlay.setCompact(false);", null)
        applyFontScale(fontScalePercent, adjustCompactHeight = false)
        applyLyricColor()
        applyBackgroundMode()
        updateFullscreenLayout()
        return true
    }

    fun updateFullscreenLayout() {
        val host = fullscreenHost ?: return
        host.post {
            webView?.evaluateJavascript("window.LobstaOverlay.setHorizontalLayout(${host.width > host.height});", null)
        }
    }

    fun detachFullscreen(host: FrameLayout) {
        if (fullscreenHost !== host) return
        webView?.let { player ->
            host.removeView(player)
            overlayWebHost?.addView(player, FrameLayout.LayoutParams(-1, -1))
            syncActiveVisualPreferences(compact)
            player.evaluateJavascript("window.LobstaOverlay.setCompact($compact);", null)
            applyFontScale(fontScalePercent, adjustCompactHeight = false)
            applyLyricColor()
            applyBackgroundMode()
        }
        fullscreenHost = null
        overlayWebHost = null
        applyHorizontalWebLayout()
        overlayRoot?.visibility = View.VISIBLE
        val size = currentDisplaySize()
        if (size.first != lastDisplayWidth || size.second != lastDisplayHeight) adaptOverlayToDisplay()
    }

    private fun currentDisplaySize(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            if (bounds.width() > 0 && bounds.height() > 0) {
                return bounds.width() to bounds.height()
            }
        }
        return max(1, resources.displayMetrics.widthPixels) to
            max(1, resources.displayMetrics.heightPixels)
    }

    private fun currentSafeDisplayBounds(): Rect {
        val (screenWidth, screenHeight) = currentDisplaySize()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return Rect(0, 0, screenWidth, screenHeight)
        }
        val metrics = windowManager.currentWindowMetrics
        val insets = (overlayRoot?.rootWindowInsets ?: metrics.windowInsets)
            .getInsets(WindowInsets.Type.systemBars())
        val bounds = metrics.bounds
        val safe = Rect(
            bounds.left + insets.left,
            bounds.top + insets.top,
            bounds.right - insets.right,
            bounds.bottom - insets.bottom
        )
        return if (safe.width() > 0 && safe.height() > 0) {
            safe
        } else {
            Rect(0, 0, screenWidth, screenHeight)
        }
    }

    private fun logicalToWindowSize(logicalSize: Pair<Int, Int>, rotated: Boolean): Pair<Int, Int> =
        if (rotated) logicalSize.second to logicalSize.first else logicalSize

    private fun windowToLogicalSize(width: Int, height: Int, rotated: Boolean): Pair<Int, Int> =
        if (rotated) height to width else width to height

    private fun rotatedContentLayoutParams(
        windowWidth: Int,
        windowHeight: Int,
        rotated: Boolean
    ): FrameLayout.LayoutParams = FrameLayout.LayoutParams(
        if (rotated) windowHeight else windowWidth,
        if (rotated) windowWidth else windowHeight,
        Gravity.CENTER
    )

    private fun applyOverlayRotationLayout() {
        val content = overlayContent ?: return
        val lp = windowParams ?: return
        content.layoutParams = rotatedContentLayoutParams(lp.width, lp.height, overlayRotated)
        content.rotation = if (overlayRotated) 90f else 0f
        content.requestLayout()
    }

    private fun fittedLogicalOverlaySize(
        desiredWidth: Int,
        desiredHeight: Int,
        isCompact: Boolean,
        rotated: Boolean,
        safeBounds: Rect
    ): Pair<Int, Int> {
        val logicalScreenWidth = if (rotated) safeBounds.height() else safeBounds.width()
        val logicalScreenHeight = if (rotated) safeBounds.width() else safeBounds.height()
        return fittedOverlaySize(
            desiredWidth,
            desiredHeight,
            isCompact,
            logicalScreenWidth,
            logicalScreenHeight
        )
    }

    private fun minimumOverlayWidth(screenWidth: Int = currentDisplaySize().first): Int {
        val oneThirdScreen = screenWidth / 3
        val requestedMinimum = oneThirdScreen.coerceIn(dp(112), dp(140))
        return min(requestedMinimum, max(1, screenWidth))
    }

    private fun fittedOverlaySize(
        desiredWidth: Int,
        desiredHeight: Int,
        isCompact: Boolean,
        screenWidth: Int,
        screenHeight: Int
    ): Pair<Int, Int> {
        // Safe display bounds already exclude system bars and cutouts. Allow users to
        // align an overlay exactly to both horizontal edges without losing 8dp again
        // when the size is re-fitted during a compact/expanded transition.
        val maxWidth = max(1, screenWidth)
        val minWidth = minimumOverlayWidth(screenWidth).coerceAtMost(maxWidth)
        val maxHeight = max(1, screenHeight - dp(DISPLAY_EDGE_MARGIN_DP))
        val requestedMinHeight = if (isCompact) {
            dp(compactMinimumHeightDp(fontScalePercent))
        } else {
            dp(COMPACT_MAX_HEIGHT_DP + 1)
        }
        val minHeight = min(requestedMinHeight, maxHeight)

        var width = max(desiredWidth, minWidth)
        var height = max(desiredHeight, minHeight)
        if (!isCompact) {
            val scale = minOf(
                1f,
                maxWidth / width.toFloat(),
                maxHeight / height.toFloat()
            )
            width = (width * scale).roundToInt()
            height = (height * scale).roundToInt()
        }

        width = width.coerceIn(minWidth, maxWidth)
        val compactMaxHeight = min(dp(COMPACT_MAX_HEIGHT_DP), maxHeight)
        height = if (isCompact) {
            height.coerceIn(min(minHeight, compactMaxHeight), compactMaxHeight)
        } else {
            height.coerceIn(minHeight, maxHeight)
        }
        return width to height
    }

    private fun adaptOverlayToDisplay() {
        if (fullscreenHost != null) return
        val root = overlayRoot ?: return
        val lp = windowParams ?: return
        val (screenWidth, screenHeight) = currentDisplaySize()
        val safe = currentSafeDisplayBounds()
        val previousWidth = max(1, lastDisplayWidth)
        val previousHeight = max(1, lastDisplayHeight)
        val centerX = (lp.x + lp.width / 2f) / previousWidth
        val centerY = (lp.y + lp.height / 2f) / previousHeight
        val currentLogicalSize = windowToLogicalSize(lp.width, lp.height, overlayRotated)

        val desiredSize = if (compact) {
            prefs.getInt(PREF_COMPACT_WIDTH, currentLogicalSize.first) to
                prefs.getInt("compact_height_v3", currentLogicalSize.second)
        } else {
            prefs.getInt("width", currentLogicalSize.first) to
                prefs.getInt("height", currentLogicalSize.second)
        }
        val fittedLogicalSize = fittedLogicalOverlaySize(
            desiredSize.first,
            desiredSize.second,
            isCompact = compact,
            rotated = overlayRotated,
            safeBounds = safe
        )
        val fittedWindowSize = logicalToWindowSize(fittedLogicalSize, overlayRotated)
        lp.width = fittedWindowSize.first
        lp.height = fittedWindowSize.second
        lp.x = (centerX * screenWidth - lp.width / 2f).roundToInt()
            .coerceIn(safe.left, max(safe.left, safe.right - lp.width))
        lp.y = (centerY * screenHeight - lp.height / 2f).roundToInt()
            .coerceIn(safe.top, max(safe.top, safe.bottom - lp.height))
        lastDisplayWidth = screenWidth
        lastDisplayHeight = screenHeight
        saveOverlayGeometry(compact, lp, saveSize = true)
        applyOverlayRotationLayout()
        applyHorizontalWebLayout()
        windowManager.updateViewLayout(root, lp)
    }

    private fun applyHorizontalWebLayout() {
        if (fullscreenHost != null) return
        webView?.evaluateJavascript(
            "window.LobstaOverlay && window.LobstaOverlay.setHorizontalLayout($overlayRotated);",
            null
        )
    }

    private fun chromeButton(label: String, action: () -> Unit): TextView = TextView(this).apply {
        text = label
        setTextColor(Color.argb(225, 255, 255, 255))
        textSize = 11f
        includeFontPadding = false
        gravity = Gravity.CENTER
        setShadowLayer(dp(2).toFloat(), 0f, dp(1).toFloat(), Color.argb(190, 0, 0, 0))
        setOnClickListener { action() }
    }

    private fun setChromeIcon(button: TextView, drawableRes: Int) {
        val icon = AppCompatResources.getDrawable(this, drawableRes)?.mutate()?.apply {
            setTint(Color.argb(225, 255, 255, 255))
        }
        button.text = ""
        button.setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null)
    }

    private fun overlayBackground(isCompact: Boolean): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(22).toFloat()
        setColor(Color.TRANSPARENT)
        if (!isCompact) {
            setStroke(dp(1), Color.argb(45, 255, 255, 255))
        }
    }

    private fun toggleOverlayOrientation() {
        val root = overlayRoot ?: return
        val lp = windowParams ?: return
        if (compact) return
        val safe = currentSafeDisplayBounds()
        val centerX = lp.x + lp.width / 2f
        val centerY = lp.y + lp.height / 2f
        val nextRotated = !overlayRotated
        lp.x = (centerX - lp.width / 2f).roundToInt()
            .coerceIn(safe.left, max(safe.left, safe.right - lp.width))
        lp.y = (centerY - lp.height / 2f).roundToInt()
            .coerceIn(safe.top, max(safe.top, safe.bottom - lp.height))
        overlayRotated = nextRotated
        val currentLogicalSize = windowToLogicalSize(lp.width, lp.height, overlayRotated)
        prefs.edit()
            .putBoolean(PREF_OVERLAY_ROTATED, overlayRotated)
            .putInt("width", currentLogicalSize.first)
            .putInt("height", currentLogicalSize.second)
            .putInt("x", lp.x)
            .putInt("y", lp.y)
            .putInt(positionXPreferenceKey(false), lp.x)
            .putInt(positionYPreferenceKey(false), lp.y)
            .apply()
        applyOverlayRotationLayout()
        applyHorizontalWebLayout()
        windowManager.updateViewLayout(root, lp)
    }

    private fun toggleCompact() {
        val nextCompact = !compact
        val previousFontScalePercent = fontScalePercent
        val lp = windowParams ?: return
        val safe = currentSafeDisplayBounds()
        val centerX = lp.x + lp.width / 2f
        val centerY = lp.y + lp.height / 2f
        val currentLogicalSize = windowToLogicalSize(lp.width, lp.height, overlayRotated)
        saveOverlayGeometry(compact, lp, saveSize = true)
        val desiredSize = if (nextCompact) {
            prefs.getInt(PREF_COMPACT_WIDTH, currentLogicalSize.first) to
                prefs.getInt("compact_height_v3", dp(48))
        } else {
            prefs.getInt("width", currentLogicalSize.first) to prefs.getInt("height", dp(520))
        }
        syncActiveVisualPreferences(nextCompact)
        val fittedLogicalSize = fittedLogicalOverlaySize(
            desiredSize.first,
            desiredSize.second,
            isCompact = nextCompact,
            rotated = overlayRotated,
            safeBounds = safe
        )
        val fittedWindowSize = logicalToWindowSize(fittedLogicalSize, overlayRotated)
        lp.width = fittedWindowSize.first
        lp.height = fittedWindowSize.second
        val targetXKey = positionXPreferenceKey(nextCompact)
        val targetYKey = positionYPreferenceKey(nextCompact)
        lp.x = (if (prefs.contains(targetXKey)) prefs.getInt(targetXKey, lp.x)
            else (centerX - lp.width / 2f).roundToInt())
            .coerceIn(safe.left, max(safe.left, safe.right - lp.width))
        lp.y = (if (prefs.contains(targetYKey)) prefs.getInt(targetYKey, lp.y)
            else (centerY - lp.height / 2f).roundToInt())
            .coerceIn(safe.top, max(safe.top, safe.bottom - lp.height))
        fontScalePercent = previousFontScalePercent
        setCompactUi(nextCompact)
        applyOverlayRotationLayout()
        overlayRoot?.let { windowManager.updateViewLayout(it, lp) }
        saveOverlayGeometry(nextCompact, lp, saveSize = true)
    }

    private fun positionXPreferenceKey(targetCompact: Boolean): String =
        if (targetCompact) PREF_COMPACT_X else PREF_EXPANDED_X

    private fun positionYPreferenceKey(targetCompact: Boolean): String =
        if (targetCompact) PREF_COMPACT_Y else PREF_EXPANDED_Y

    private fun saveOverlayGeometry(
        targetCompact: Boolean,
        lp: WindowManager.LayoutParams,
        saveSize: Boolean
    ) {
        val editor = prefs.edit()
            .putInt("x", lp.x)
            .putInt("y", lp.y)
            .putInt(positionXPreferenceKey(targetCompact), lp.x)
            .putInt(positionYPreferenceKey(targetCompact), lp.y)
            .putBoolean("compact", targetCompact)
        if (saveSize) {
            val logicalSize = windowToLogicalSize(lp.width, lp.height, overlayRotated)
            if (targetCompact) {
                editor.putInt(PREF_COMPACT_WIDTH, logicalSize.first)
                    .putInt("compact_height_v3", logicalSize.second)
            } else {
                editor.putInt("width", logicalSize.first)
                    .putInt("height", logicalSize.second)
            }
        }
        editor.apply()
    }

    private fun setCompactUi(value: Boolean) {
        val previousFontScalePercent = fontScalePercent
        if (compact != value) {
            closeBlockedUntilElapsedMs = SystemClock.elapsedRealtime() + CLOSE_GUARD_AFTER_TOGGLE_MS
        }
        compact = value
        syncActiveVisualPreferences(compact)
        prefs.edit().putBoolean("compact", compact).apply()
        overlayContent?.background = overlayBackground(compact)
        updateControlLayout(compact)
        webView?.evaluateJavascript(
            "window.LobstaOverlay && window.LobstaOverlay.setCompact($compact);",
            null
        )
        // The target geometry has already been restored before this UI switch.
        // Re-basing compact height from the other mode's font scale can shave a
        // few pixels off a two-line compact layout and incorrectly collapse it
        // to one line. Only an explicit compact-font setting change may resize it.
        applyFontScale(previousFontScalePercent, adjustCompactHeight = false)
        applyLyricColor()
        applyBackgroundMode()
    }

    private fun applyReadingOptions() {
        val stroke = lyricStrokeEnabled
        val pronunciation = prefs.getBoolean(PREF_PRONUNCIATION_ENABLED, false)
        webView?.evaluateJavascript(
            "window.LobstaOverlay && (window.LobstaOverlay.setLyricStroke($stroke), " +
                "window.LobstaOverlay.setPronunciationEnabled($pronunciation));", null
        )
    }

    private fun syncActiveVisualPreferences(targetCompact: Boolean) {
        lyricStrokeEnabled = prefs.getBoolean(
            if (targetCompact) PREF_LYRIC_STROKE_COMPACT else PREF_LYRIC_STROKE, false
        )
        backgroundMode = if (targetCompact) compactBackgroundMode else expandedBackgroundMode
        fontScalePercent = if (targetCompact) compactFontScalePercent else expandedFontScalePercent
        lyricColor = if (targetCompact) compactLyricColor else expandedLyricColor
    }

    private fun displayedVisualTargetIsCompact(): Boolean = fullscreenHost == null && compact

    private fun applyBackgroundMode() {
        val encodedMode = JSONObject.quote(backgroundMode)
        webView?.evaluateJavascript(
            "window.LobstaOverlay && window.LobstaOverlay.setBackgroundMode($encodedMode);",
            null
        )
    }

    private fun applyFontScale(previousPercent: Int, adjustCompactHeight: Boolean) {
        webView?.evaluateJavascript(
            "window.LobstaOverlay && window.LobstaOverlay.setFontScale($fontScalePercent);",
            null
        )
        if (!adjustCompactHeight || !compact) return

        val lp = windowParams ?: return
        val logicalSize = windowToLogicalSize(lp.width, lp.height, overlayRotated)
        val previousMinimum = dp(compactMinimumHeightDp(previousPercent))
        val nextMinimum = dp(compactMinimumHeightDp(fontScalePercent))
        val wasAtMinimum = logicalSize.second <= previousMinimum + dp(2)
        val nextLogicalHeight = if (wasAtMinimum) {
            nextMinimum
        } else {
            max(logicalSize.second, nextMinimum)
        }
        if (nextLogicalHeight == logicalSize.second) return

        val safe = currentSafeDisplayBounds()
        val fittedLogicalSize = fittedLogicalOverlaySize(
            logicalSize.first,
            nextLogicalHeight.coerceAtMost(dp(COMPACT_MAX_HEIGHT_DP)),
            isCompact = true,
            rotated = overlayRotated,
            safeBounds = safe
        )
        val fittedWindowSize = logicalToWindowSize(fittedLogicalSize, overlayRotated)
        lp.width = fittedWindowSize.first
        lp.height = fittedWindowSize.second
        lp.x = lp.x.coerceIn(safe.left, max(safe.left, safe.right - lp.width))
        lp.y = lp.y.coerceIn(safe.top, max(safe.top, safe.bottom - lp.height))
        applyOverlayRotationLayout()
        overlayRoot?.let { windowManager.updateViewLayout(it, lp) }
        prefs.edit()
            .putInt(PREF_COMPACT_WIDTH, fittedLogicalSize.first)
            .putInt("compact_height_v3", fittedLogicalSize.second)
            .apply()
    }

    private fun applyLyricColor() {
        applyReadingOptions()
        val encoded = JSONObject.quote(lyricColor)
        webView?.evaluateJavascript(
            "window.LobstaOverlay && window.LobstaOverlay.setLyricColor($encoded);",
            null
        )
    }

    private fun applyLyricOffset() {
        webView?.evaluateJavascript(
            "window.LobstaOverlay && window.LobstaOverlay.setLyricOffset($lyricOffsetMs);",
            null
        )
    }

    private fun lyricOffsetPreferenceKey(identity: String, source: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$identity\u0000$source".toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString("") { "%02x".format(it) }
        return PREF_LYRIC_OFFSET_ENTRY_PREFIX + digest
    }

    private fun activeLyricOffsetPreferenceKey(): String? =
        if (currentLyricIdentity.isBlank() || currentLyricSource.isBlank()) null
        else lyricOffsetPreferenceKey(currentLyricIdentity, currentLyricSource)

    private fun updateLyricOffsetIndex(
        editor: android.content.SharedPreferences.Editor,
        preferenceKey: String,
        identity: String,
        source: String,
        title: String,
        artist: String,
        offsetMs: Int
    ) {
        val entryId = preferenceKey.removePrefix(PREF_LYRIC_OFFSET_ENTRY_PREFIX)
        val index = runCatching {
            JSONObject(prefs.getString(PREF_LYRIC_OFFSET_INDEX, "{}").orEmpty().ifBlank { "{}" })
        }
            .getOrDefault(JSONObject())
        if (offsetMs == 0) {
            index.remove(entryId)
        } else {
            index.put(entryId, JSONObject()
                .put("identity", identity)
                .put("source", source)
                .put("title", title)
                .put("artist", artist)
                .put("offsetMs", offsetMs)
                .put("updatedAt", System.currentTimeMillis()))
        }
        editor.putString(PREF_LYRIC_OFFSET_INDEX, index.toString())
    }

    fun currentLyricOffsetMs(): Int = lyricOffsetMs

    private fun applyTranslationMode() {
        val encoded = JSONObject.quote(translationMode)
        webView?.evaluateJavascript(
            "window.LobstaOverlay && window.LobstaOverlay.setTranslationMode($encoded);",
            null
        )
    }

    private fun normalizedBackgroundMode(value: String?): String = when (value) {
        BACKGROUND_TRANSPARENT -> BACKGROUND_TRANSPARENT
        BACKGROUND_LOW -> BACKGROUND_LOW
        BACKGROUND_MEDIUM -> BACKGROUND_MEDIUM
        BACKGROUND_HIGH -> BACKGROUND_HIGH
        else -> BACKGROUND_DEFAULT
    }

    private fun normalizedLyricColor(value: String?): String {
        val normalized = value.orEmpty().uppercase(Locale.ROOT)
        return if (Regex("^#[0-9A-F]{6}$").matches(normalized)) normalized else LYRIC_COLOR_DEFAULT
    }

    private fun normalizedTranslationMode(value: String?): String = when (value) {
        TRANSLATION_ORIGINAL -> TRANSLATION_ORIGINAL
        TRANSLATION_TRANSLATED -> TRANSLATION_TRANSLATED
        else -> TRANSLATION_BILINGUAL
    }

    private fun normalizedFontScale(value: Int): Int =
        value.coerceIn(FONT_SCALE_MIN_PERCENT, FONT_SCALE_MAX_PERCENT)

    private fun updateControlLayout(isCompact: Boolean) {
        val chrome = chromeBar ?: return
        revealCompactControls()
        chrome.orientation = if (isCompact) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        chrome.gravity = Gravity.CENTER
        scaleButton?.let {
            setChromeIcon(
                it,
                if (isCompact) {
                    R.drawable.ic_overlay_resize_down_left
                } else {
                    R.drawable.ic_overlay_resize_up_left
                }
            )
        }

        chrome.removeAllViews()
        if (isCompact) {
            scaleButton?.let(chrome::addView)
            closeButton?.let(chrome::addView)
        } else {
            rotateButton?.let(chrome::addView)
            chrome.addView(
                View(this),
                LinearLayout.LayoutParams(dp(6), ViewGroup.LayoutParams.MATCH_PARENT)
            )
            scaleButton?.let(chrome::addView)
            chrome.addView(
                View(this),
                LinearLayout.LayoutParams(dp(6), ViewGroup.LayoutParams.MATCH_PARENT)
            )
            closeButton?.let(chrome::addView)
        }

        val params = (chrome.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(28))
        params.width = if (isCompact) dp(26) else ViewGroup.LayoutParams.WRAP_CONTENT
        params.height = if (isCompact) ViewGroup.LayoutParams.MATCH_PARENT else dp(32)
        params.gravity = if (isCompact) {
            Gravity.END or Gravity.CENTER_VERTICAL
        } else {
            Gravity.END or Gravity.TOP
        }
        params.setMargins(0, if (isCompact) 0 else dp(12), if (isCompact) 0 else dp(17), 0)
        chrome.layoutParams = params

        if (isCompact) {
            closeButton?.translationX = dp(3).toFloat()
            scaleButton?.translationX = dp(3).toFloat()
            closeButton?.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            scaleButton?.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        } else {
            rotateButton?.translationX = 0f
            closeButton?.translationX = 0f
            scaleButton?.translationX = 0f
            rotateButton?.layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
            closeButton?.layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
            scaleButton?.layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
        }
        chrome.requestLayout()

        dragTouchArea?.let { area ->
            val areaParams = (area.layoutParams as? FrameLayout.LayoutParams)
                ?: FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            areaParams.width = ViewGroup.LayoutParams.MATCH_PARENT
            // Keep the metadata header draggable, but stop before the chips row so
            // the WebView can receive taps on the interactive lyrics-source chip.
            areaParams.height = if (isCompact) ViewGroup.LayoutParams.MATCH_PARENT else dp(78)
            areaParams.gravity = Gravity.TOP
            area.layoutParams = areaParams
            area.requestLayout()
        }
    }

    private fun startMediaMonitor() {
        if (monitorStarted) {
            refreshActiveSessions()
            return
        }
        try {
            sessionManager.addOnActiveSessionsChangedListener(activeSessionsListener, listenerComponent)
            monitorStarted = true
            refreshActiveSessions()
            mainHandler.removeCallbacks(sessionRefreshRunnable)
            mainHandler.postDelayed(sessionRefreshRunnable, 750L)
        } catch (_: SecurityException) {
            pendingSnapshot = JSONObject()
                .put("hasSession", false)
                .put("permissionRequired", true)
            pendingSnapshot?.let { deliverToWeb(it) }
            mainHandler.postDelayed({
                if (!monitorStarted) startMediaMonitor()
            }, 1_500L)
        }
    }

    private fun stopMediaMonitor() {
        mainHandler.removeCallbacks(sessionRefreshRunnable)
        if (monitorStarted) {
            try {
                sessionManager.removeOnActiveSessionsChangedListener(activeSessionsListener)
            } catch (_: Exception) {
            }
        }
        monitorStarted = false
        currentController?.unregisterCallback(controllerCallback)
        currentController = null
    }

    private fun refreshActiveSessions() {
        try {
            val controllers = sessionManager.getActiveSessions(listenerComponent)
            selectController(controllers)
        } catch (error: SecurityException) {
            pendingSnapshot = JSONObject()
                .put("hasSession", false)
                .put("permissionRequired", true)
            pendingSnapshot?.let { deliverToWeb(it) }
        }
    }

    private fun selectController(controllers: List<MediaController>) {
        val best = controllers
            .asSequence()
            .filter { it.packageName != packageName }
            .filter { isSupportedMusicPackage(it.packageName) }
            .maxByOrNull { controllerScore(it) }

        if (best?.sessionToken == currentController?.sessionToken) {
            scheduleSnapshot()
            return
        }

        currentController?.unregisterCallback(controllerCallback)
        currentController = best
        cachedArtworkKey = ""
        cachedArtworkDataUrl = ""
        best?.registerCallback(controllerCallback, mainHandler)
        scheduleSnapshot()
    }

    private fun controllerScore(controller: MediaController): Int {
        val stateScore = when (controller.playbackState?.state) {
            PlaybackState.STATE_PLAYING -> 1000
            PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING -> 800
            PlaybackState.STATE_PAUSED -> 600
            else -> 100
        }
        val metadataScore = if (!mediaTitle(controller.metadata).isNullOrBlank()) 100 else 0
        return stateScore + metadataScore
    }

    private fun isSupportedMusicPackage(packageName: String): Boolean {
        val p = packageName.lowercase(Locale.ROOT)
        val exactOrPrefix = listOf(
            "com.apple.android.music",
            "com.spotify.music",
            "com.netease.cloudmusic",
            "com.tencent.qqmusic",
            "com.kugou.android",
            "cn.kuwo.player",
            "com.kuwo.player",
            "com.google.android.apps.youtube.music",
            "com.amazon.mp3",
            "com.soundcloud.android",
            "deezer.android.app",
            "com.aspiro.tidal",
            "com.miui.player",
            "com.sec.android.app.music",
            "com.maxmpz.audioplayer",
            "in.krosbits.musicolet",
            "com.aimp.player",
            "com.fiio.music",
            "com.plexamp.android",
            "org.videolan.vlc"
        )
        return exactOrPrefix.any { p == it || p.startsWith("$it.") } ||
            (p.contains("music") && !p.contains("bilibili"))
    }

    private fun scheduleSnapshot() {
        if (snapshotScheduled) return
        snapshotScheduled = true
        mainHandler.postDelayed(dispatchRunnable, 35)
    }

    private fun dispatchSnapshot() {
        val controller = currentController
        val snapshot = if (controller == null) {
            JSONObject().put("hasSession", false).put("permissionRequired", false)
        } else {
            buildSnapshot(controller)
        }
        pendingSnapshot = snapshot
        deliverToWeb(snapshot)
    }

    private fun buildSnapshot(controller: MediaController): JSONObject {
        val metadata = controller.metadata
        val playback = controller.playbackState
        val title = mediaTitle(metadata).orEmpty()
        val artist = firstMetadataString(
            metadata,
            MediaMetadata.METADATA_KEY_ARTIST,
            MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
            MediaMetadata.METADATA_KEY_AUTHOR,
            MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE
        ).orEmpty()
        val album = firstMetadataString(metadata, MediaMetadata.METADATA_KEY_ALBUM).orEmpty()
        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val state = when (playback?.state) {
            PlaybackState.STATE_PLAYING -> "playing"
            PlaybackState.STATE_PAUSED -> "paused"
            PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING -> "buffering"
            PlaybackState.STATE_STOPPED, PlaybackState.STATE_NONE -> "stopped"
            else -> "paused"
        }
        val speed = playback?.playbackSpeed?.toDouble() ?: 0.0
        val actions = playback?.actions ?: 0L
        val position = currentPosition(playback, duration)
        val artwork = artworkDataUrl(metadata, "$title\u0000$artist\u0000$album")

        return JSONObject()
            .put("hasSession", title.isNotBlank() || playback != null)
            .put("permissionRequired", false)
            .put("track", title)
            .put("artist", artist)
            .put("album", album)
            .put("packageName", controller.packageName)
            .put("state", state)
            .put("positionMs", position)
            .put("durationMs", max(0L, duration))
            .put("speed", if (speed.isFinite()) speed else 1.0)
            .put(
                "canPlay",
                actions and (PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PLAY_PAUSE) != 0L
            )
            .put(
                "canPause",
                actions and (PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE) != 0L
            )
            .put("canPrevious", actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L)
            .put("canNext", actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L)
            .put("canSeek", actions and PlaybackState.ACTION_SEEK_TO != 0L)
            .put("capturedAtMs", System.currentTimeMillis())
            .put("cover", artwork)
            .put("volumePct", mediaVolumePercent())
    }

    private fun mediaVolumePercent(): Int {
        return try {
            val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (maxVolume > 0) (current * 100f / maxVolume).toInt().coerceIn(0, 100) else 0
        } catch (_: Exception) {
            0
        }
    }

    private fun currentPosition(state: PlaybackState?, duration: Long): Long {
        if (state == null) return 0L
        var position = max(0L, state.position)
        if (state.state == PlaybackState.STATE_PLAYING && state.playbackSpeed > 0f) {
            val elapsed = max(0L, SystemClock.elapsedRealtime() - state.lastPositionUpdateTime)
            position += (elapsed * state.playbackSpeed).toLong()
        }
        return if (duration > 0) min(position, duration) else position
    }

    private fun mediaTitle(metadata: MediaMetadata?): String? = firstMetadataString(
        metadata,
        MediaMetadata.METADATA_KEY_TITLE,
        MediaMetadata.METADATA_KEY_DISPLAY_TITLE
    )

    private fun firstMetadataString(metadata: MediaMetadata?, vararg keys: String): String? {
        if (metadata == null) return null
        for (key in keys) {
            metadata.getString(key)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return null
    }

    private fun artworkDataUrl(metadata: MediaMetadata?, key: String): String {
        // Media apps often publish title/artist first and artwork in a later callback.
        // Do not permanently cache an empty first result for the lifetime of the track.
        if (key == cachedArtworkKey && cachedArtworkDataUrl.isNotEmpty()) {
            return cachedArtworkDataUrl
        }
        cachedArtworkKey = key
        val bitmap = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        cachedArtworkDataUrl = bitmap?.let { bitmapDataUrl(it) }.orEmpty()
        return cachedArtworkDataUrl
    }

    private fun bitmapDataUrl(source: Bitmap): String {
        return try {
            val maxSide = max(source.width, source.height)
            val scaled = if (maxSide > 640) {
                val ratio = 640f / maxSide.toFloat()
                Bitmap.createScaledBitmap(
                    source,
                    max(1, (source.width * ratio).toInt()),
                    max(1, (source.height * ratio).toInt()),
                    true
                )
            } else {
                source
            }
            val bytes = ByteArrayOutputStream().use { output ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 88, output)
                output.toByteArray()
            }
            if (scaled !== source) scaled.recycle()
            "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (_: Exception) {
            ""
        }
    }

    private fun deliverToWeb(snapshot: JSONObject) {
        pendingSnapshot = snapshot
        if (!webReady) return
        webView?.post {
            webView?.evaluateJavascript(
                "window.LobstaOverlay && window.LobstaOverlay.updatePlayback($snapshot);",
                null
            )
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        var instance: LyricsOverlayService? = null
            private set
        const val ACTION_START = "com.tcrrry.desktoplyrics.action.START_LYRICS_OVERLAY"
        const val ACTION_STOP = "com.tcrrry.desktoplyrics.action.STOP_LYRICS_OVERLAY"
        const val ACTION_STATE_CHANGED = "com.tcrrry.desktoplyrics.action.LYRICS_OVERLAY_STATE_CHANGED"
        const val ACTION_SET_BACKGROUND = "com.tcrrry.desktoplyrics.action.SET_LYRICS_BACKGROUND"
        const val ACTION_SET_FONT_SCALE = "com.tcrrry.desktoplyrics.action.SET_LYRICS_FONT_SCALE"
        const val ACTION_SET_LYRIC_COLOR = "com.tcrrry.desktoplyrics.action.SET_LYRIC_COLOR"
        const val ACTION_SET_LYRIC_OFFSET = "com.tcrrry.desktoplyrics.action.SET_LYRIC_OFFSET"
        const val ACTION_CLEAR_LYRIC_OFFSET_MEMORIES = "com.tcrrry.desktoplyrics.action.CLEAR_LYRIC_OFFSET_MEMORIES"
        const val ACTION_DELETE_LYRIC_OFFSET_MEMORY = "com.tcrrry.desktoplyrics.action.DELETE_LYRIC_OFFSET_MEMORY"
        const val ACTION_SET_TRANSLATION_MODE = "com.tcrrry.desktoplyrics.action.SET_TRANSLATION_MODE"
        const val EXTRA_BACKGROUND_MODE = "background_mode"
        const val EXTRA_FONT_SCALE_PERCENT = "font_scale_percent"
        const val EXTRA_LYRIC_COLOR = "lyric_color"
        const val EXTRA_TARGET_COMPACT = "target_compact"
        const val EXTRA_LYRIC_OFFSET_MS = "lyric_offset_ms"
        const val EXTRA_LYRIC_OFFSET_MEMORY_KEY = "lyric_offset_memory_key"
        const val EXTRA_TRANSLATION_MODE = "translation_mode"
        const val EXTRA_RUNNING = "running"
        const val PREFS_NAME = "lyrics_overlay_prefs"
        const val ACTION_SET_READING_OPTIONS = "com.tcrrry.desktoplyrics.SET_READING_OPTIONS"
        const val PREF_PRONUNCIATION_ENABLED = "pronunciation_enabled_v1"
        const val PREF_LYRIC_STROKE = "lyric_stroke_v1"
        const val PREF_LYRIC_STROKE_COMPACT = "lyric_stroke_compact_v1"
        const val PREF_BACKGROUND_MODE = "background_mode"
        const val PREF_BACKGROUND_MODE_COMPACT = "background_mode_compact_v1"
        const val PREF_FONT_SCALE_PERCENT = "font_scale_percent"
        const val PREF_FONT_SCALE_COMPACT_PERCENT = "font_scale_compact_percent_v1"
        const val PREF_LYRIC_COLOR = "lyric_color_v1"
        const val PREF_LYRIC_COLOR_COMPACT = "lyric_color_compact_v1"
        const val PREF_LYRIC_OFFSET_MS = "lyric_offset_ms_v1"
        const val PREF_LYRIC_OFFSET_ENTRY_PREFIX = "lyric_offset_entry_v2:"
        const val PREF_LYRIC_OFFSET_INDEX = "lyric_offset_index_v2"
        const val PREF_TRANSLATION_MODE = "translation_mode_v1"
        private const val PREF_COMPACT_WIDTH = "compact_width_v1"
        private const val PREF_EXPANDED_X = "expanded_x_v1"
        private const val PREF_EXPANDED_Y = "expanded_y_v1"
        private const val PREF_COMPACT_X = "compact_x_v1"
        private const val PREF_COMPACT_Y = "compact_y_v1"
        private const val PREF_OVERLAY_ROTATED = "overlay_rotated_v1"
        const val BACKGROUND_TRANSPARENT = "transparent"
        const val BACKGROUND_LOW = "low"
        const val BACKGROUND_MEDIUM = "medium"
        const val BACKGROUND_HIGH = "high"
        const val BACKGROUND_DEFAULT = BACKGROUND_HIGH
        const val FONT_SCALE_MIN_PERCENT = 35
        const val FONT_SCALE_MAX_PERCENT = 150
        const val FONT_SCALE_DEFAULT_PERCENT = 100
        const val LYRIC_COLOR_DEFAULT = "#FFFFFF"
        const val LYRIC_OFFSET_MIN_MS = -5_000
        const val LYRIC_OFFSET_MAX_MS = 5_000
        const val TRANSLATION_ORIGINAL = "original"
        const val TRANSLATION_BILINGUAL = "bilingual"
        const val TRANSLATION_TRANSLATED = "translated"

        fun compactMinimumHeightDp(percent: Int): Int {
            val scale = percent.coerceIn(FONT_SCALE_MIN_PERCENT, FONT_SCALE_MAX_PERCENT) / 100f
            return (9.5f + 34.5f * scale).roundToInt().coerceIn(32, 64)
        }
        private const val LOG_TAG = "DesktopLyrics"
        private const val CHANNEL_ID = "lobsta_lyrics_overlay"
        private const val COMPACT_MAX_HEIGHT_DP = 130
        private const val DISPLAY_EDGE_MARGIN_DP = 8
        private const val CLOSE_GUARD_AFTER_TOGGLE_MS = 650L
        private const val NOTIFICATION_ID = 4202

        @Volatile
        var isRunning: Boolean = false
            private set
    }
}
