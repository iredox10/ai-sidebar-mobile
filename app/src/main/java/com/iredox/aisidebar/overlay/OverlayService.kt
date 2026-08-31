package com.iredox.aisidebar.overlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.iredox.aisidebar.MainActivity
import com.iredox.aisidebar.R

class OverlayService : Service() {
    private var bubble: TextView? = null
    private lateinit var windowManager: WindowManager

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, createNotification())
        if (bubble == null) showBubble()
        return START_NOT_STICKY
    }

    private fun showBubble() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        bubble = TextView(this).apply {
            text = "AI"
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(73, 69, 191))
            elevation = 16f
            setOnClickListener {
                startActivity(Intent(this@OverlayService, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        val params = WindowManager.LayoutParams(
            64.dp, 64.dp,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; x = 16.dp; y = 160.dp }
        windowManager.addView(bubble, params)
    }

    private fun createNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle(getString(R.string.overlay_notification_title))
        .setContentText(getString(R.string.overlay_notification_body))
        .setOngoing(true)
        .build()

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, getString(R.string.overlay_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.overlay_channel_description)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        bubble?.let { windowManager.removeView(it) }
        bubble = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private val Int.dp get() = (this * resources.displayMetrics.density).toInt()

    private companion object {
        const val CHANNEL_ID = "overlay"
        const val NOTIFICATION_ID = 101
    }
}
