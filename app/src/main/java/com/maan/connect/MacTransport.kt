package com.maan.connect

import android.content.Context
import android.util.Log
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.net.URI

object MacTransport {
    private const val TAG = "MAANTransport"
    private const val PORT = 8765

    fun saveIp(context: Context, ip: String) = Prefs.saveMacIp(context, ip)
    fun getSavedIp(context: Context): String? = Prefs.macIp(context)

    fun sendJson(context: Context, payload: JSONObject, onDone: (() -> Unit)? = null) {
        val ip = Prefs.macIp(context)
        if (ip.isNullOrBlank()) { onDone?.invoke(); return }
        Thread {
            var socket: WebSocketClient? = null
            try {
                socket = object : WebSocketClient(URI("ws://$ip:$PORT")) {
                    override fun onOpen(handshake: ServerHandshake) { send(payload.toString()); close() }
                    override fun onMessage(message: String) {}
                    override fun onClose(code: Int, reason: String, remote: Boolean) {}
                    override fun onError(ex: Exception) { Log.w(TAG, "sendJson failed", ex) }
                }
                socket.connect()
                Thread.sleep(1500)
            } catch (e: Exception) { Log.w(TAG, "sendJson exception", e) }
            finally { runCatching { socket?.close() }; onDone?.invoke() }
        }.start()
    }
}
