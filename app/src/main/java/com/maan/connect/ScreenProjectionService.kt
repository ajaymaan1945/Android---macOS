package com.maan.connect

import android.app.*
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream

class ScreenProjectionService : Service() {
    companion object { const val EXTRA_RESULT_CODE = "result_code"; const val EXTRA_DATA = "result_data"; private const val CHANNEL = "maan_projection"; private const val ID = 2202 }
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var socketService: MacConnectionService? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastFrame = 0L
    private var projectionCallback: MediaProjection.Callback? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel(); val notification = Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_view).setContentTitle("MAAN").setContentText("Screen sharing is active").setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) else startForeground(ID, notification)
        val code = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: return START_NOT_STICKY
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA) ?: return START_NOT_STICKY
        val pm = getSystemService(MediaProjectionManager::class.java)
        runCatching { projection?.stop() }
        projection = null
        runCatching { projection = pm.getMediaProjection(code, data) }
        if (projection == null) {
            MacConnectionService.sendToConnected(JSONObject().put("type", "screen_error").put("message", "Android did not grant screen capture permission").toString())
            stopSelf()
            return START_NOT_STICKY
        }

        // Android 14+ requires a MediaProjection callback to be registered
        // before starting the virtual display. It also lets us cleanly stop
        // the capture if Android/Samsung terminates the projection.
        projectionCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                display?.release()
                display = null
                reader?.close()
                reader = null
                projection = null
                MacConnectionService.sendToConnected(JSONObject().put("type", "screen_stopped").toString())
                stopSelf()
            }
        }
        projection?.registerCallback(projectionCallback!!, handler)

        val dm = resources.displayMetrics
        val width = minOf(dm.widthPixels, 1280); val height = (dm.heightPixels * width.toFloat() / dm.widthPixels).toInt().coerceAtLeast(1)
        reader = ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 2)
        reader?.setOnImageAvailableListener({ r -> encodeFrame(r, width, height) }, handler)
        display = runCatching {
            projection?.createVirtualDisplay("MAAN", width, height, dm.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader?.surface, null, handler)
        }.getOrElse {
            MacConnectionService.sendToConnected(JSONObject().put("type", "screen_error").put("message", it.message ?: "Unable to start screen capture").toString())
            runCatching { projection?.stop() }
            stopSelf()
            null
        }
        if (display == null) return START_NOT_STICKY
        return START_STICKY
    }

    private fun encodeFrame(r: ImageReader, width: Int, height: Int) {
        val now = System.currentTimeMillis(); if (now - lastFrame < 140) { r.acquireLatestImage()?.close(); return }; lastFrame = now
        val image = r.acquireLatestImage() ?: return
        try {
            val plane = image.planes[0]; val buffer = plane.buffer; val pixelStride = plane.pixelStride; val rowStride = plane.rowStride; val rowPadding = rowStride - pixelStride * width
            val bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer); val cropped = if (bitmap.width != width) Bitmap.createBitmap(bitmap, 0, 0, width, height) else bitmap
            val out = ByteArrayOutputStream(); cropped.compress(Bitmap.CompressFormat.JPEG, 55, out); if (cropped !== bitmap) bitmap.recycle(); cropped.recycle()
            val payload = JSONObject().put("type", "screen_frame").put("width", width).put("height", height).put("data", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
            send(payload.toString())
        } finally { image.close() }
    }

    private fun send(text: String) {
        MacConnectionService.sendToConnected(text)
    }
    private fun createChannel() { if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "MAAN Screen Sharing", NotificationManager.IMPORTANCE_LOW)) }
    override fun onDestroy() {
        display?.release(); display = null
        reader?.close(); reader = null
        projection?.let { p -> projectionCallback?.let { cb -> runCatching { p.unregisterCallback(cb) } }; runCatching { p.stop() } }
        projection = null
        projectionCallback = null
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
