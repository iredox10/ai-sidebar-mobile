package com.iredox.aisidebar.overlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.iredox.aisidebar.MainActivity
import com.iredox.aisidebar.R

/** A user-controlled foreground overlay. It never captures screen content by itself. */
class OverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private var bubble: TextView? = null
    private var panel: LinearLayout? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, createNotification())
        if (bubble == null && panel == null) showBubble()
        return START_NOT_STICKY
    }

    private fun showBubble() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val params = overlayParams(width = 64.dp, height = 64.dp).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 16.dp
            y = 160.dp
        }
        bubble = TextView(this).apply {
            text = "AI"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = roundedBackground(Color.rgb(79, 70, 229), 32.dp)
            elevation = 16f
            setOnClickListener { showPanel() }
            installDragBehavior(this, params)
        }
        windowManager.addView(bubble, params)
    }

    private fun showPanel() {
        bubble?.let { windowManager.removeView(it) }
        bubble = null
        val params = overlayParams(width = 300.dp, height = WindowManager.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = 16.dp
            y = 96.dp
        }
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18.dp, 16.dp, 18.dp, 16.dp)
            background = roundedBackground(Color.rgb(30, 30, 44), 22.dp)
            elevation = 18f
            addView(label("AI Sidebar", 18f, Color.WHITE, true))
            addView(label("Ready for chat. Screen context will always require your explicit action.", 14f, Color.rgb(220, 220, 230), false).apply {
                setPadding(0, 8.dp, 0, 14.dp)
            })
            addView(action("Open full assistant") { openMainApp() })
            addView(action("Collapse") { closePanel() }.apply { setPadding(0, 12.dp, 0, 0) })
        }
        windowManager.addView(panel, params)
    }

    private fun closePanel() {
        panel?.let { windowManager.removeView(it) }
        panel = null
        showBubble()
    }

    private fun openMainApp() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun overlayParams(width: Int, height: Int) = WindowManager.LayoutParams(
        width, height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
    )

    private fun installDragBehavior(view: View, params: WindowManager.LayoutParams) {
        view.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0
            private var startY = 0
            private var touchX = 0f
            private var touchY = 0f
            private var moved = false

            override fun onTouch(target: View, event: MotionEvent): Boolean = when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y; touchX = event.rawX; touchY = event.rawY; moved = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val offsetX = (event.rawX - touchX).toInt()
                    val offsetY = (event.rawY - touchY).toInt()
                    moved = moved || kotlin.math.abs(offsetX) > 8.dp || kotlin.math.abs(offsetY) > 8.dp
                    params.x = startX - offsetX
                    params.y = startY + offsetY
                    windowManager.updateViewLayout(target, params)
                    true
                }
                MotionEvent.ACTION_UP -> { if (!moved) target.performClick(); true }
                else -> false
            }
        })
    }

    private fun label(text: String, size: Float, color: Int, bold: Boolean) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    private fun action(text: String, click: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 15f
        setTextColor(Color.rgb(199, 196, 255))
        setPadding(0, 10.dp, 0, 10.dp)
        setOnClickListener { click() }
    }

    private fun roundedBackground(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
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
        panel?.let { windowManager.removeView(it) }
        bubble = null
        panel = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private val Int.dp get() = (this * resources.displayMetrics.density).toInt()

    private companion object {
        const val CHANNEL_ID = "overlay"
        const val NOTIFICATION_ID = 101
    }
}
