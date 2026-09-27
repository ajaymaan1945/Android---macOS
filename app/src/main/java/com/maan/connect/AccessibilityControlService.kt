package com.maan.connect

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.content.Context
import android.net.Uri
import org.json.JSONObject

class AccessibilityControlService : AccessibilityService() {
    companion object {
        private const val TAG = "MAANRemoteControl"
        @Volatile private var instance: AccessibilityControlService? = null

        fun isEnabled(): Boolean = instance != null

        fun handleRemoteCommand(context: Context, data: JSONObject) {
            val service = instance ?: run {
                Log.w(TAG, "Remote command ignored: Accessibility service is not enabled")
                MacConnectionService.sendToConnected(JSONObject()
                    .put("type", "input_result")
                    .put("success", false)
                    .put("message", "MAAN Accessibility Service is not enabled")
                    .toString())
                return
            }

            val metrics = service.resources.displayMetrics
            val screenWidth = metrics.widthPixels.toFloat().coerceAtLeast(1f)
            val screenHeight = metrics.heightPixels.toFloat().coerceAtLeast(1f)
            val remoteWidth = data.optDouble("screen_width", screenWidth.toDouble()).toFloat().coerceAtLeast(1f)
            val remoteHeight = data.optDouble("screen_height", screenHeight.toDouble()).toFloat().coerceAtLeast(1f)

            fun clamp(v: Float, max: Float) = v.coerceIn(0f, max - 1f)
            fun x(v: Double) = clamp((v.toFloat() / remoteWidth) * screenWidth, screenWidth)
            fun y(v: Double) = clamp((v.toFloat() / remoteHeight) * screenHeight, screenHeight)

            when (data.optString("type")) {
                "input_tap" -> service.tap(x(data.optDouble("x")), y(data.optDouble("y")))
                "input_swipe" -> service.swipe(
                    x(data.optDouble("x1")), y(data.optDouble("y1")),
                    x(data.optDouble("x2")), y(data.optDouble("y2")),
                    data.optLong("duration", 350).coerceIn(100L, 2500L)
                )
                "file_drag_probe" -> service.probeFileAt(
                    x(data.optDouble("x")), y(data.optDouble("y"))
                )
                "file_drag_commit" -> {
                    val uri = data.optString("uri")
                    if (uri.isBlank()) {
                        MacConnectionService.sendToConnected(JSONObject().put("type", "file_drag_result").put("success", false).put("message", "No file was selected").toString())
                    } else {
                        FileTransferManager.sendUriToMac(context, Uri.parse(uri), data.optString("name").ifBlank { null })
                        MacConnectionService.sendToConnected(JSONObject().put("type", "file_drag_result").put("success", true).put("message", "Sending file to Mac").put("name", data.optString("name")).toString())
                    }
                }
                else -> Log.w(TAG, "Unknown input command: ${data.optString("type")}")
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "MAAN remote control enabled")
        MacConnectionService.sendToConnected(JSONObject()
            .put("type", "input_ready")
            .put("enabled", true)
            .toString())
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        Log.i(TAG, "MAAN remote control disabled")
        MacConnectionService.sendToConnected(JSONObject()
            .put("type", "input_ready")
            .put("enabled", false)
            .toString())
        super.onDestroy()
    }

    private fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 70))
            .build()
        dispatchGesture(gesture, callback, null)
    }

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long) {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        dispatchGesture(gesture, callback, null)
    }

    private fun probeFileAt(x: Float, y: Float) {
        val root = rootInActiveWindow
        if (root == null) {
            sendFileCandidate(false, "No active window")
            return
        }
        if (root.packageName?.toString() != packageName) {
            sendFileCandidate(false, "Open MAAN Files on the phone to drag a file to Mac")
            return
        }
        val node = findDeepestNode(root, x, y)
        val description = node?.contentDescription?.toString().orEmpty()
        if (description.startsWith("MAAN_FILE|")) {
            val parts = description.split('|', limit = 3)
            val uri = parts.getOrNull(1).orEmpty()
            val name = parts.getOrNull(2).orEmpty().ifBlank { node?.text?.toString().orEmpty() }
            if (uri.isNotBlank()) {
                sendFileCandidate(true, "", uri, name)
                    return
            }
        }
        sendFileCandidate(false, "No MAAN file at this point")
    }

    private fun findDeepestNode(node: AccessibilityNodeInfo, x: Float, y: Float): AccessibilityNodeInfo? {
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        if (!bounds.contains(x.toInt(), y.toInt())) return null
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val hit = findDeepestNode(child, x, y)
            if (hit != null) return hit
        }
        return node
    }

    private fun sendFileCandidate(success: Boolean, message: String, uri: String = "", name: String = "") {
        MacConnectionService.sendToConnected(JSONObject()
            .put("type", "file_drag_candidate")
            .put("success", success)
            .put("message", message)
            .put("uri", uri)
            .put("name", name)
            .toString())
    }

    private val callback = object : GestureResultCallback() {
        override fun onCompleted(gestureDescription: GestureDescription?) {
            Log.d(TAG, "Remote gesture completed")
            MacConnectionService.sendToConnected(JSONObject()
                .put("type", "input_result")
                .put("success", true)
                .put("message", "Gesture completed")
                .toString())
        }

        override fun onCancelled(gestureDescription: GestureDescription?) {
            Log.w(TAG, "Remote gesture cancelled")
            MacConnectionService.sendToConnected(JSONObject()
                .put("type", "input_result")
                .put("success", false)
                .put("message", "Gesture cancelled")
                .toString())
        }
    }
}
