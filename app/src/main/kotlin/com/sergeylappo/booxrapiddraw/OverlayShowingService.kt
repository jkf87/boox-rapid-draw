package com.sergeylappo.booxrapiddraw

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.view.View.OnLayoutChangeListener
import android.view.WindowManager
import android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
import android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
import android.view.WindowManager.LayoutParams.MATCH_PARENT
import android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList

private const val CHANNEL_ID = "rapid_draw_channel_overlay_01"
private const val STROKE_WIDTH = 5.0f
private const val WATCHDOG_INTERVAL_MS = 2000L

class OverlayShowingService : Service() {
    private val paint = Paint()

    private lateinit var touchHelper: TouchHelper
    private lateinit var wm: WindowManager
    private lateinit var overlayPaintingView: SurfaceView
    private lateinit var overlayParams: WindowManager.LayoutParams
    private var touchHelperInitialized = false
    private val watchdogHandler = Handler(Looper.getMainLooper())
    private val watchdogRunnable = object : Runnable {
        override fun run() {
            ensureOverlayActive()
            watchdogHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent) = null

    override fun onCreate() {
        super.onCreate()

        createForegroundNotification()

        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        createOverlayPaintingView()

        initPaint()
        initSurfaceView()
        watchdogHandler.postDelayed(watchdogRunnable, WATCHDOG_INTERVAL_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            Toast.makeText(this, "Terminating Rapid Draw Service...", Toast.LENGTH_SHORT).show()
            stopSelf()
            return START_NOT_STICKY // Prevents service from being recreated
        }

        Toast.makeText(this, "Starting Rapid Draw Service", Toast.LENGTH_SHORT).show()
        return START_STICKY // Service will be recreated if killed
    }

    private fun createForegroundNotification() {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Boox Rapid draw overlay service",
                NotificationManager.IMPORTANCE_HIGH
            )
        )

        // add notification intent to finish the service
        val pendingIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, OverlayShowingService::class.java).apply { action = "STOP" },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.overlay_service_notification_content_title))
            .setContentText(getString(R.string.overlay_service_notification_content))
            .setSmallIcon(R.drawable.rapid_draw)
            .addAction(NotificationCompat.Action.Builder(null, "Stop", pendingIntent).build())
            .build()

        //noinspection InlinedApi (Seems to work, IDK why, maybe older Android versions might not support this)
        ServiceCompat.startForeground(this, 1, notification, FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }

    private fun createOverlayPaintingView() {
        overlayPaintingView = SurfaceView(this)
        overlayPaintingView.setZOrderOnTop(true)
        overlayPaintingView.holder.setFormat(PixelFormat.TRANSPARENT)
        overlayPaintingView.alpha = 1.0f

        overlayParams = WindowManager.LayoutParams(
            MATCH_PARENT,
            MATCH_PARENT,
            TYPE_APPLICATION_OVERLAY,
            FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSPARENT
        )
        // alpha 1.0: transparency comes from PixelFormat.TRANSPARENT, not window alpha.
        // The old 0.2f made strokes nearly invisible on e-ink.
        overlayParams.alpha = 1.0f
        overlayParams.gravity = Gravity.START or Gravity.TOP
        overlayParams.x = 0
        overlayParams.y = 0

        wm.addView(overlayPaintingView, overlayParams)
    }

    private fun initPaint() {
        paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.color = Color.BLACK
        paint.strokeWidth = STROKE_WIDTH
    }

    //    TODO fix suppress
    @SuppressLint("ClickableViewAccessibility")
    private fun initSurfaceView() {
        touchHelper = TouchHelper.create(overlayPaintingView, 2, callback)
        touchHelper.setPenUpRefreshTimeMs(1000)
        overlayPaintingView.addOnLayoutChangeListener(object : OnLayoutChangeListener {
            override fun onLayoutChange(
                v: View,
                left: Int,
                top: Int,
                right: Int,
                bottom: Int,
                oldLeft: Int,
                oldTop: Int,
                oldRight: Int,
                oldBottom: Int
            ) {
                if (touchHelperInitialized) return

                // Use raw view dimensions — do NOT call getLocalVisibleRect().
                // It clips the rect to the visible area (excluding status bar),
                // which offsets EMR pen coordinates and misaligns strokes.
                val bounds = Rect(0, 0, right - left, bottom - top)

                touchHelper.setStrokeColor(Color.BLACK)
                touchHelper.setStrokeStyle(TouchHelper.STROKE_STYLE_FOUNTAIN)
                touchHelper.openRawDrawing()
                touchHelper.setStrokeWidth(STROKE_WIDTH).setLimitRect(bounds, listOf())
                touchHelper.setRawInputReaderEnable(!touchHelper.isRawDrawingInputEnabled)
                touchHelperInitialized = true
            }
        })

        overlayPaintingView.setOnTouchListener { _: View?, _: MotionEvent? -> true }
    }

    /**
     * Periodic check to ensure the overlay window stays attached.
     * If the system or a fullscreen app detached it, re-attach.
     */
    private fun ensureOverlayActive() {
        if (!::overlayPaintingView.isInitialized || !::overlayParams.isInitialized) return
        if (overlayPaintingView.windowToken == null) {
            try {
                wm.addView(overlayPaintingView, overlayParams)
                touchHelperInitialized = false
            } catch (_: Exception) {
                // Display not ready or view already attached
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        watchdogHandler.removeCallbacks(watchdogRunnable)
        wm.removeViewImmediate(overlayPaintingView)
        touchHelper.closeRawDrawing()
    }

    private val callback: RawInputCallback = object : RawInputCallback() {
        override fun onBeginRawDrawing(b: Boolean, touchPoint: TouchPoint?) {}

        override fun onEndRawDrawing(b: Boolean, touchPoint: TouchPoint?) {}

        override fun onRawDrawingTouchPointMoveReceived(touchPoint: TouchPoint?) {}

        override fun onPenActive(point: TouchPoint?) {
            touchHelper.setRawDrawingEnabled(true)
        }

        override fun onRawDrawingTouchPointListReceived(touchPointList: TouchPointList) {}

        override fun onBeginRawErasing(b: Boolean, touchPoint: TouchPoint?) {}

        override fun onEndRawErasing(b: Boolean, touchPoint: TouchPoint?) {}

        override fun onRawErasingTouchPointMoveReceived(touchPoint: TouchPoint?) {}

        override fun onRawErasingTouchPointListReceived(touchPointList: TouchPointList?) {}

        override fun onPenUpRefresh(refreshRect: RectF?) {
            super.onPenUpRefresh(refreshRect)
        }
    }
}
