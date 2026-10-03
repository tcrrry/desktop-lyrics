package com.tcrrry.desktoplyrics

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** User-initiated toggle; permission setup remains in the settings activity. */
class LyricsTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun { toggleOverlay() } else toggleOverlay()
    }

    private fun toggleOverlay() {
        if (LyricsOverlayService.isRunning) {
            stopService(Intent(this, LyricsOverlayService::class.java))
        } else if (!Settings.canDrawOverlays(this) ||
            !NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        ) {
            openSettings()
        } else {
            try {
                ContextCompat.startForegroundService(this,
                    Intent(this, LyricsOverlayService::class.java).apply {
                        action = LyricsOverlayService.ACTION_START
                    })
            } catch (_: IllegalStateException) {
                openSettings()
            } catch (_: SecurityException) {
                openSettings()
            }
        }
        updateTile()
    }

    private fun updateTile() {
        qsTile?.apply {
            label = "桌面歌词"
            state = if (LyricsOverlayService.isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            if (Build.VERSION.SDK_INT >= 29) subtitle = if (LyricsOverlayService.isRunning) "已开启" else "已关闭"
            updateTile()
        }
    }

    // The PendingIntent overload is only available on API 34+. Older Android
    // versions require the Intent overload, which remains supported there.
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    private fun openSettings() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 108, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        } else {
            startActivityAndCollapse(intent)
        }
    }
}
