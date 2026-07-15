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
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList

private const val CHANNEL_ID = "rapid_draw_channel_overlay_01"
private const val STROKE_WIDTH = 3.0f
private const val WATCHDOG_INTERVAL_MS = 2000L

class OverlayShowingService : Service() {
    private val strokePaint = Paint()

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
            return START_NOT_STICKY
        }
        Toast.makeText(this, "Starting Rapid Draw Service", Toast.LENGTH_SHORT).show()
        return START_STICKY
    }

    private fun createForegroundNotification() {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID, "Boox Rapid draw overlay service", NotificationManager.IMPORTANCE_HIGH
            )
        )
        val pendingIntent = PendingIntent.getService(
            this, 0,
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
            MATCH_PARENT, MATCH_PARENT,
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

    /**
     * Render a complete stroke from a TouchPointList onto the SurfaceView Canvas
     * with anti-aliasing and quadratic bezier smoothing.
     * Called after the SF-rendered real-time stroke is done, to overlay a
     * higher-quality version that becomes visible on the pen-up e-ink refresh.
     */
    private fun renderStrokeOnCanvas(points: TouchPointList) {
        val allPoints = points.points ?: return
        if (allPoints.size < 2) return

        val holder: SurfaceHolder = overlayPaintingView.holder
        val canvas = holder.lockCanvas() ?: return

        val path = Path()
        val first = allPoints[0]
        path.moveTo(first.x, first.y)

        if (allPoints.size == 2) {
            path.lineTo(allPoints[1].x, allPoints[1].y)
        } else {
            // Quadratic bezier smoothing: use midpoints as endpoints,
            // actual points as control points.
            for (i in 1 until allPoints.size - 1) {
                val p = allPoints[i]
                val next = allPoints[i + 1]
                val midX = (p.x + next.x) / 2f
                val midY = (p.y + next.y) / 2f
                path.quadTo(p.x, p.y, midX, midY)
            }
            // Final segment to the last point
            val last = allPoints[allPoints.size - 1]
            path.lineTo(last.x, last.y)
        }

        canvas.drawPath(path, strokePaint)
        holder.unlockCanvasAndPost(canvas)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initSurfaceView() {
        touchHelper = TouchHelper.create(overlayPaintingView, 2, callback)
        touchHelper.setPenUpRefreshTimeMs(1000)
        overlayPaintingView.addOnLayoutChangeListener(object : OnLayoutChangeListener {
            override fun onLayoutChange(
                v: View, left: Int, top: Int, right: Int, bottom: Int,
                oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int
            ) {
                val bounds = Rect(0, 0, right - left, bottom - top)

                if (!touchHelperInitialized) {
                    touchHelper.setStrokeColor(Color.BLACK)
                    // SF rendering handles real-time display (rough but instant).
                    // Canvas overlay (renderStrokeOnCanvas) provides final quality.
                    touchHelper.setStrokeStyle(TouchHelper.STROKE_STYLE_MARKER)
                    touchHelper.openRawDrawing()
                    touchHelper.setStrokeWidth(STROKE_WIDTH)
                    touchHelper.setFilterRepeatMovePoint(true)
                    touchHelper.setRawInputReaderEnable(!touchHelper.isRawDrawingInputEnabled)
                    touchHelper.enableFingerTouch(false)
                    touchHelperInitialized = true
                }

                touchHelper.setLimitRect(bounds, listOf())
            }
        })

        overlayPaintingView.setOnTouchListener { _: View?, _: MotionEvent? -> true }
    }

    private fun ensureOverlayActive() {
        if (!::overlayPaintingView.isInitialized || !::overlayParams.isInitialized) return
        if (overlayPaintingView.windowToken == null) {
            try {
                wm.addView(overlayPaintingView, overlayParams)
                touchHelperInitialized = false
            } catch (_: Exception) {}
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
            // Enable SF rendering — enters scribble mode for input routing
            // and real-time visual feedback.
            touchHelper.setRawDrawingEnabled(true)
            touchHelper.isRawDrawingRenderEnabled = true
        }

        override fun onRawDrawingTouchPointListReceived(touchPointList: TouchPointList) {
            // Full stroke data available — render a high-quality version on
            // Canvas that will become visible on the pen-up e-ink refresh.
            // The SurfaceView (setZOrderOnTop) is composited above the
            // SF-rendered framebuffer, so our anti-aliased stroke overlays
            // the rough SF stroke.
            renderStrokeOnCanvas(touchPointList)
        }

        override fun onBeginRawErasing(b: Boolean, touchPoint: TouchPoint?) {}
        override fun onEndRawErasing(b: Boolean, touchPoint: TouchPoint?) {}
        override fun onRawErasingTouchPointMoveReceived(touchPoint: TouchPoint?) {}
        override fun onRawErasingTouchPointListReceived(touchPointList: TouchPointList?) {}

        override fun onPenUpRefresh(refreshRect: RectF?) {
            // Exit scribble mode — releases input pipeline so finger touches
            // pass through to the underlying app again.
            touchHelper.isRawDrawingRenderEnabled = false
            super.onPenUpRefresh(refreshRect)
        }
    }
}
