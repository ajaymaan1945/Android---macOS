package com.maan.connect

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telecom.TelecomManager
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentLinkedQueue
import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

class MacConnectionService : Service() {

    companion object {
        @Volatile
        private var instance: MacConnectionService? = null

        // A call can arrive before Android finishes creating the foreground
        // connection service. Keep the event until the service/socket exists.
        private val pendingBeforeInstance = ConcurrentLinkedQueue<String>()

        fun sendToConnected(payload: String): Boolean {
            val service = instance
            if (service != null) {
                return service.send(payload)
            }
            pendingBeforeInstance.offer(payload)
            return false
        }

        const val ACTION_STATUS = "com.maan.connect.STATUS"
        const val EXTRA_STATUS = "status"

        private const val CHANNEL = "maan_connection"
        private const val ID = 2201
        private const val PORT = 8765
        private const val TAG = "MAANConnection"
    }

    private val handler = Handler(Looper.getMainLooper())

    private val connecting = AtomicBoolean(false)
    private val pendingControl = ConcurrentLinkedQueue<String>()

    @Volatile
    private var socket: WebSocketClient? = null

    @Volatile
    private var discovery: MacDiscovery? = null

    private var suppressClipboardEcho = false

    private var clipboard: ClipboardManager? = null

    override fun onCreate() {
        super.onCreate()

        instance = this

        // Move events received while the service was starting into the
        // instance queue; they will be flushed after the WebSocket connects.
        while (true) {
            val queued = pendingBeforeInstance.poll() ?: break
            pendingControl.offer(queued)
        }

        createChannel()

        clipboard =
            getSystemService(ClipboardManager::class.java)

        clipboard?.addPrimaryClipChangedListener {
            if (!suppressClipboardEcho && Prefs.clipboard(this)) {
                sendClipboardToMac()
            }
        }

        startForegroundCompat("Connecting automatically…")

        startDiscovery()
        connect()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        startDiscovery()
        connect()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    // ---------------------------------------------------------
    // DISCOVERY
    // ---------------------------------------------------------

    private fun startDiscovery() {

        if (discovery != null) {
            return
        }

        discovery = MacDiscovery(
            this,

            { ip, _ ->
                Prefs.saveMacIp(this, ip)

                startForegroundCompat(
                    "● Mac found — connecting"
                )

                connect()
            },

            { state ->
                if (socket?.isOpen != true) {
                    startForegroundCompat(state)
                }
            }
        )

        discovery?.start()
    }

    // ---------------------------------------------------------
    // WEBSOCKET CONNECTION
    // ---------------------------------------------------------

    private fun connect() {

        if (!connecting.compareAndSet(false, true)) {
            return
        }

        val ip = Prefs.macIp(this)

        if (ip.isNullOrBlank()) {
            connecting.set(false)
            startForegroundCompat("● Looking for your Mac")
            return
        }

        val oldSocket = socket

        if (oldSocket?.isOpen == true) {
            connecting.set(false)
            return
        }

        val ws = object : WebSocketClient(
            URI("ws://$ip:$PORT")
        ) {

            override fun onOpen(handshake: ServerHandshake?) {

                connecting.set(false)

                this@MacConnectionService.socket = this

                handler.post {
                    startForegroundCompat(
                        "● Connected to ${Prefs.macName(this@MacConnectionService)}"
                    )
                }

                val hello =
                    MaanProtocol
                        .hello(Build.MODEL)
                        .put(
                            "token",
                            Prefs.token(this@MacConnectionService)
                                ?: JSONObject.NULL
                        )
                        .toString()

                send(hello, queueIfDisconnected = false)

                send(
                    MaanProtocol
                        .registerControl()
                        .toString(),
                    queueIfDisconnected = false
                )
                flushPendingControl()

                broadcast("connected")

                Log.d(TAG, "Mac connected: $ip")
            }

            override fun onMessage(message: String?) {

                if (!message.isNullOrBlank()) {
                    try {
                        handle(JSONObject(message))
                    } catch (e: Exception) {
                        Log.e(
                            TAG,
                            "Invalid JSON message",
                            e
                        )
                    }
                }
            }

            override fun onClose(
                code: Int,
                reason: String?,
                remote: Boolean
            ) {

                connecting.set(false)

                this@MacConnectionService.socket = null

                broadcast("disconnected")

                startForegroundCompat(
                    "● Mac disconnected — reconnecting"
                )

                Log.d(
                    TAG,
                    "Mac disconnected: $reason"
                )

                scheduleReconnect()
            }

            override fun onError(ex: Exception?) {

                connecting.set(false)

                this@MacConnectionService.socket = null

                Log.w(
                    TAG,
                    "WebSocket error",
                    ex
                )

                scheduleReconnect()
            }
        }

        socket = ws

        Thread {
            try {
                ws.connect()
            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "WebSocket connection failed",
                    e
                )

                connecting.set(false)

                handler.post {
                    startForegroundCompat(
                        "● Connection failed — retrying"
                    )
                }

                scheduleReconnect()
            }
        }.start()
    }

    private fun scheduleReconnect() {

        handler.removeCallbacksAndMessages(null)

        handler.postDelayed(
            {
                startDiscovery()
                connect()
            },
            2500
        )
    }

    // ---------------------------------------------------------
    // MESSAGE HANDLER
    // ---------------------------------------------------------

    private fun handle(data: JSONObject) {

        when (data.optString("type")) {

            // Calls
            "answer_call" -> {
                controlCall(
                    true,
                    data.optString("call_id")
                )
            }

            "reject_call" -> {
                controlCall(
                    false,
                    data.optString("call_id")
                )
            }

            // Pairing
            "pair_request" -> {
                showPairingNotification(
                    data.optString("code")
                )
            }

            "pair_complete" -> {

                val token =
                    data.optString("token")

                if (token.isNotBlank()) {
                    Prefs.saveToken(
                        this,
                        token
                    )
                }
            }

            // Remote control
            "input_tap",
            "input_swipe",
            "file_drag_probe",
            "file_drag_commit" -> {

                AccessibilityControlService
                    .handleRemoteCommand(
                        this,
                        data
                    )
            }

            // File transfer
            "file_chunk" -> {

                if (!Prefs.files(this)) return
                FileTransferManager.receiveChunk(
                    this,
                    data
                )
            }

            "file_end" -> {

                if (!Prefs.files(this)) return
                FileTransferManager.finishReceive(
                    this,
                    data
                )
            }

            "file_drag_pull" -> {

                if (!Prefs.files(this)) return
                val uriString =
                    data.optString("uri")

                if (uriString.isNotBlank()) {

                    FileTransferManager.sendUriToMac(
                        this,
                        Uri.parse(uriString),
                        data.optString("name")
                            .ifBlank { null },
                        data.optString("transfer_id")
                    )
                }
            }

            // Clipboard text
            "clipboard_text" -> {

                if (!Prefs.clipboard(this)) return
                suppressClipboardEcho = true

                ClipboardBridge.setText(
                    this,
                    data.optString("text")
                )

                handler.postDelayed(
                    {
                        suppressClipboardEcho = false
                    },
                    500
                )
            }

            // Clipboard image
            "clipboard_image" -> {

                if (!Prefs.clipboard(this)) return
                suppressClipboardEcho = true
                setImageClipboard(
                    data.optString("data")
                )
                handler.postDelayed({ suppressClipboardEcho = false }, 1000)
            }

            // Open MAAN Files
            "open_file_bridge" -> {

                val intent = Intent(
                    this,
                    FileBridgeActivity::class.java
                ).apply {

                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK
                    )

                    addFlags(
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                }

                startActivity(intent)
            }

            // Screen sharing command
            "screen_start" -> {
                // ScreenProjectionService handles
                // the MediaProjection flow.
            }
        }
    }

    // ---------------------------------------------------------
    // CALL CONTROL
    // ---------------------------------------------------------

    private fun controlCall(
        answer: Boolean,
        callId: String
    ) {

        if (
            checkSelfPermission(
                "android.permission.ANSWER_PHONE_CALLS"
            ) != PackageManager.PERMISSION_GRANTED
        ) {

            sendResult(
                callId,
                if (answer) {
                    "answer_call"
                } else {
                    "reject_call"
                },
                false,
                "ANSWER_PHONE_CALLS permission is missing"
            )

            return
        }

        val telecom =
            getSystemService(
                TelecomManager::class.java
            )

        val ok =
            runCatching {

                if (answer) {
                    telecom?.acceptRingingCall()
                } else {
                    telecom?.endCall()
                }

                true

            }.getOrDefault(false)

        Prefs.saveCallId(
            this,
            if (ok) null else Prefs.callId(this)
        )

        sendResult(
            callId,
            if (answer) {
                "answer_call"
            } else {
                "reject_call"
            },
            ok,
            if (ok) {
                "done"
            } else {
                "Telecom command failed"
            }
        )

        send(
            MaanProtocol
                .callState(
                    callId,
                    if (answer) {
                        "ANSWERED"
                    } else {
                        "REJECTED"
                    }
                )
                .toString()
        )
    }

    // ---------------------------------------------------------
    // SEND
    // ---------------------------------------------------------

    fun send(payload: String, queueIfDisconnected: Boolean = true): Boolean {
        val s = socket?.takeIf { it.isOpen }
        if (s == null) {
            if (queueIfDisconnected && isControlPayload(payload)) pendingControl.offer(payload)
            return false
        }
        return runCatching { s.send(payload); true }.getOrDefault(false)
    }

    private fun isControlPayload(payload: String): Boolean = runCatching {
        when (JSONObject(payload).optString("type")) {
            "incoming_call", "call_state", "call_control_result", "clipboard_text", "clipboard_image" -> true
            else -> false
        }
    }.getOrDefault(false)

    private fun flushPendingControl() {
        while (true) {
            val item = pendingControl.poll() ?: break
            if (!send(item, queueIfDisconnected = false)) {
                pendingControl.offer(item)
                break
            }
        }
    }

    // ---------------------------------------------------------
    // CLIPBOARD PHONE -> MAC
    // ---------------------------------------------------------

    private fun sendClipboardToMac() {

        val cm =
            clipboard ?: return

        val clip =
            runCatching {
                cm.primaryClip
            }.getOrNull()
                ?: return

        if (clip.itemCount <= 0) {
            return
        }

        val item =
            clip.getItemAt(0)

        item.text
            ?.toString()
            ?.let { text ->

                send(
                    JSONObject()
                        .put(
                            "type",
                            "clipboard_text"
                        )
                        .put(
                            "text",
                            text
                        )
                        .toString()
                )

                return
            }

        val uri = item.uri ?: return

        Thread {
            runCatching {
                contentResolver.openInputStream(uri)?.use { input ->
                    val bytes = input.readBytes()
                    send(JSONObject().put("type", "clipboard_image").put("data", Base64.encodeToString(bytes, Base64.NO_WRAP)).toString())
                }
            }.onFailure { Log.w(TAG, "Unable to read clipboard image URI", it) }
        }.start()
    }

    // ---------------------------------------------------------
    // CLIPBOARD MAC -> PHONE
    // ---------------------------------------------------------

    private fun setImageClipboard(
        base64: String
    ) {

        runCatching {

            val bytes =
                Base64.decode(
                    base64,
                    Base64.DEFAULT
                )

            val dir =
                File(
                    cacheDir,
                    "clipboard"
                ).apply {
                    mkdirs()
                }

            val file =
                File(
                    dir,
                    "maan-${System.currentTimeMillis()}.png"
                )

            file.writeBytes(bytes)

            val uri =
                FileProvider.getUriForFile(
                    this,
                    "com.maan.connect.files",
                    file
                )

            val clip =
                ClipData.newUri(
                    contentResolver,
                    "MAAN image",
                    uri
                )

            clipboard?.setPrimaryClip(clip)

        }.onFailure {
            Log.e(
                TAG,
                "Unable to set image clipboard",
                it
            )
        }
    }

    // ---------------------------------------------------------
    // CALL RESULT
    // ---------------------------------------------------------

    private fun sendResult(
        id: String,
        action: String,
        success: Boolean,
        message: String
    ) {

        send(
            JSONObject()
                .put(
                    "type",
                    "call_control_result"
                )
                .put(
                    "call_id",
                    id
                )
                .put(
                    "action",
                    action
                )
                .put(
                    "success",
                    success
                )
                .put(
                    "message",
                    message
                )
                .toString()
        )
    }

    // ---------------------------------------------------------
    // STATUS
    // ---------------------------------------------------------

    private fun broadcast(
        status: String
    ) {

        sendBroadcast(
            Intent(ACTION_STATUS).apply {
                putExtra(
                    EXTRA_STATUS,
                    status
                )
            }
        )
    }

    // ---------------------------------------------------------
    // PAIRING NOTIFICATION
    // ---------------------------------------------------------

    private fun showPairingNotification(
        code: String
    ) {

        val intent =
            Intent(
                this,
                PairingActivity::class.java
            ).apply {

                putExtra(
                    "code",
                    code
                )

                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                )
            }

        val flags =
            PendingIntent.FLAG_UPDATE_CURRENT or
                    if (
                        Build.VERSION.SDK_INT >= 23
                    ) {
                        PendingIntent.FLAG_IMMUTABLE
                    } else {
                        0
                    }

        val pi =
            PendingIntent.getActivity(
                this,
                3301,
                intent,
                flags
            )

        val notification =
            NotificationCompat
                .Builder(
                    this,
                    CHANNEL
                )
                .setSmallIcon(
                    android.R.drawable.stat_sys_warning
                )
                .setContentTitle(
                    "MAAN pairing request"
                )
                .setContentText(
                    "Code: $code — tap to confirm"
                )
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(
                    NotificationCompat.PRIORITY_HIGH
                )
                .build()

        getSystemService(
            NotificationManager::class.java
        ).notify(
            3301,
            notification
        )
    }

    // ---------------------------------------------------------
    // NOTIFICATION CHANNEL
    // ---------------------------------------------------------

    private fun createChannel() {

        if (Build.VERSION.SDK_INT >= 26) {

            val channel =
                NotificationChannel(
                    CHANNEL,
                    getString(
                        R.string.connection_channel
                    ),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {

                    description =
                        getString(
                            R.string.connection_channel_description
                        )
                }

            getSystemService(
                NotificationManager::class.java
            ).createNotificationChannel(
                channel
            )
        }
    }

    // ---------------------------------------------------------
    // FOREGROUND SERVICE
    // ---------------------------------------------------------

    private fun startForegroundCompat(
        text: String
    ) {

        val notification =
            NotificationCompat
                .Builder(
                    this,
                    CHANNEL
                )
                .setSmallIcon(
                    android.R.drawable.stat_sys_data_bluetooth
                )
                .setContentTitle(
                    "MAAN"
                )
                .setContentText(
                    text
                )
                .setOngoing(true)
                .setSilent(true)
                .build()

        if (Build.VERSION.SDK_INT >= 29) {

            startForeground(
                ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )

        } else {

            startForeground(
                ID,
                notification
            )
        }
    }

    // ---------------------------------------------------------
    // DESTROY
    // ---------------------------------------------------------

    override fun onDestroy() {

        handler.removeCallbacksAndMessages(null)

        if (instance === this) {
            instance = null
        }

        discovery?.stop()
        discovery = null

        socket?.close()
        socket = null

        super.onDestroy()
    }
}