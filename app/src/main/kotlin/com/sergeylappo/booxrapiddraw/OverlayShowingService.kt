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
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
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
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.api.device.epd.UpdateMode
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

private const val CHANNEL_ID = "rapid_draw_channel_overlay_01"
private const val STROKE_WIDTH = 4.0f
private const val WATCHDOG_INTERVAL_MS = 2000L

// Smoothing: the higher, the smoother (but more lag). 0 = no smoothing.
private const val SMOOTHING_FACTOR = 0.35f

class OverlayShowingService : Service() {
    private val strokePaint = Paint()

    private lateinit var touchHelper: TouchHelper
    private lateinit var wm: WindowManager
    private lateinit var overlayPaintingView: SurfaceView
    private lateinit var overlayParams: WindowManager.LayoutParams

    private var touchHelperInitialized = false

    // Stroke tracking for custom Canvas rendering
    private var lastPoint: TouchPoint? = null
    private var midPointX = 0f
    private var midPointY = 0f
    private var hasMidPoint = false

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
            return START_NOT_STICKY
        }

        Toast.makeText(this, "Starting Rapid Draw Service", Toast.LENGTH_SHORT).show()
        return START_STICKY
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

        @Suppress("InlinedApi")
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
        overlayParams.alpha = 1.0f
        overlayParams.gravity = Gravity.START or Gravity.TOP
        overlayParams.x = 0
        overlayParams.y = 0

        wm.addView(overlayPaintingView, overlayParams)
    }

    private fun initPaint() {
        strokePaint.isAntiAlias = true
        strokePaint.style = Paint.Style.STROKE
        strokePaint.color = Color.BLACK
        strokePaint.strokeWidth = STROKE_WIDTH
        strokePaint.strokeCap = Paint.Cap.ROUND
        strokePaint.strokeJoin = Paint.Join.ROUND
    }

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
                val bounds = Rect(0, 0, right - left, bottom - top)

                if (!touchHelperInitialized) {
                    touchHelper.setStrokeColor(Color.BLACK)
                    touchHelper.setStrokeStyle(TouchHelper.STROKE_STYLE_MARKER)
                    touchHelper.openRawDrawing()
                    touchHelper.setStrokeWidth(STROKE_WIDTH)
                    touchHelper.setFilterRepeatMovePoint(true)
                    touchHelper.setRawInputReaderEnable(!touchHelper.isRawDrawingInputEnabled)
                    touchHelper.enableFingerTouch(false)
                    // Disable SDK's SF rendering — we render on Canvas ourselves
                    // for full anti-aliasing and curve smoothing.
                    touchHelper.isRawDrawingRenderEnabled = false
                    touchHelperInitialized = true
                }

                touchHelper.setLimitRect(bounds, listOf())
            }
        })

        overlayPaintingView.setOnTouchListener { _: View?, _: MotionEvent? -> true }
    }

    /**
     * Draw a smoothed line segment from the last point to the current point
     * on the SurfaceView's Canvas using quadratic bezier interpolation.
     */
    private fun drawSegment(current: TouchPoint) {
        val prev = lastPoint ?: run {
            // First point of a stroke — nothing to draw yet.
            lastPoint = current
            midPointX = current.x
            midPointY = current.y
            hasMidPoint = true
            return
        }

        val holder: SurfaceHolder = overlayPaintingView.holder
        val canvas = holder.lockCanvas() ?: return

        val path = Path()

        // Use midpoint between prev and current as the bezier endpoint,
        // and prev as the control point. This produces smooth curves.
        val newMidX = (prev.x + current.x) / 2f
        val newMidY = (prev.y + current.y) / 2f

        if (hasMidPoint) {
            path.moveTo(midPointX, midPointY)
            path.quadTo(prev.x, prev.y, newMidX, newMidY)
        } else {
            path.moveTo(prev.x, prev.y)
            path.lineTo(current.x, current.y)
        }

        canvas.drawPath(path, strokePaint)
        holder.unlockCanvasAndPost(canvas)

        midPointX = newMidX
        midPointY = newMidY
        hasMidPoint = true
        lastPoint = current
    }

    /**
     * Trigger an e-ink screen refresh for the given region so the
     * Canvas-rendered strokes become visible on the physical display.
     */
    private fun refreshEink(rect: RectF?) {
        if (rect != null) {
            EpdController.refreshScreenRegion(
                overlayPaintingView,
                rect.left.toInt(),
                rect.top.toInt(),
                (rect.right - rect.left).toInt(),
                (rect.bottom - rect.top).toInt(),
                UpdateMode.GU
            )
        } else {
            EpdController.invalidate(overlayPaintingView, UpdateMode.GU)
        }
    }

    /**
     * Periodic check to ensure the overlay window stays attached.
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
        override fun onBeginRawDrawing(b: Boolean, touchPoint: TouchPoint?) {
            // Start a new stroke
            lastPoint = touchPoint
            hasMidPoint = false
            if (touchPoint != null) {
                midPointX = touchPoint.x
                midPointY = touchPoint.y
            }
        }

        override fun onRawDrawingTouchPointMoveReceived(touchPoint: TouchPoint?) {
            // Draw incrementally as the pen moves
            if (touchPoint != null) {
                drawSegment(touchPoint)
            }
        }

        override fun onEndRawDrawing(b: Boolean, touchPoint: TouchPoint?) {
            // Draw the final segment to the end point
            if (touchPoint != null && lastPoint != null) {
                val holder: SurfaceHolder = overlayPaintingView.holder
                val canvas = holder.lockCanvas() ?: return
                val path = Path()
                path.moveTo(midPointX, midPointY)
                path.lineTo(touchPoint.x, touchPoint.y)
                canvas.drawPath(path, strokePaint)
                holder.unlockCanvasAndPost(canvas)
            }
            lastPoint = null
            hasMidPoint = false
        }

        override fun onPenActive(point: TouchPoint?) {
            touchHelper.setRawDrawingEnabled(true)
            // Keep SF rendering disabled — we render on Canvas ourselves.
        }

        override fun onRawDrawingTouchPointListReceived(touchPointList: TouchPointList) {}

        override fun onBeginRawErasing(b: Boolean, touchPoint: TouchPoint?) {}

        override fun onEndRawErasing(b: Boolean, touchPoint: TouchPoint?) {}

        override fun onRawErasingTouchPointMoveReceived(touchPoint: TouchPoint?) {}

        override fun onRawErasingTouchPointListReceived(touchPointList: TouchPointList?) {}

        override fun onPenUpRefresh(refreshRect: RectF?) {
            // Trigger a clean e-ink refresh to show the Canvas-rendered stroke
            refreshEink(refreshRect)
            super.onPenUpRefresh(refreshRect)
        }
    }
}
