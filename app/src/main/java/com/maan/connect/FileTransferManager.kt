package com.maan.connect

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID

object FileTransferManager {
    private data class Receive(val file: RandomAccessFile, val target: File, val name: String)
    private val receiving = ConcurrentHashMap<String, Receive>()

    fun receiveChunk(context: Context, data: JSONObject) {
        val id = data.optString("transfer_id")
        if (id.isBlank()) return
        val name = safeName(data.optString("name", "file.bin"))
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "MAAN-incoming")
        dir.mkdirs()
        val target = File(dir, "${id}_${name}")
        val r = receiving[id] ?: Receive(RandomAccessFile(target, "rw"), target, name).also { receiving[id] = it }
        val offset = data.optLong("offset", r.file.length())
        r.file.seek(offset)
        r.file.write(Base64.decode(data.optString("data"), Base64.DEFAULT))
    }

    fun finishReceive(context: Context, data: JSONObject) {
        val id = data.optString("transfer_id")
        val r = receiving.remove(id) ?: return
        runCatching { r.file.close() }
        val finalPath = publishToDownloads(context, r.target, r.name)
        r.target.delete()
        MacConnectionService.sendToConnected(
            JSONObject()
                .put("type", "file_received_ack")
                .put("transfer_id", id)
                .put("name", r.name)
                .put("path", finalPath)
                .toString()
        )
    }

    fun sendUriToMac(context: Context, uri: Uri, requestedName: String? = null, transferId: String? = null) {
        Thread {
            val name = safeName(requestedName ?: queryName(context, uri) ?: "file-${UUID.randomUUID()}.bin")
            val id = transferId?.ifBlank { null } ?: "tx-${UUID.randomUUID()}"
            try {
                val input = context.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("MAAN could not open the selected phone file")
                input.use {
                    var offset = 0L
                    val buf = ByteArray(48 * 1024)
                    while (true) {
                        val n = it.read(buf)
                        if (n <= 0) break
                        val payload = JSONObject()
                            .put("type", if (transferId.isNullOrBlank()) "file_chunk" else "file_drag_chunk")
                            .put("transfer_id", id)
                            .put("name", name)
                            .put("offset", offset)
                            .put("data", Base64.encodeToString(buf, 0, n, Base64.NO_WRAP))
                        while (!MacConnectionService.sendToConnected(payload.toString())) Thread.sleep(250)
                        offset += n
                    }
                    val endType = if (transferId.isNullOrBlank()) "file_end" else "file_drag_end"
                    while (!MacConnectionService.sendToConnected(JSONObject().put("type", endType).put("transfer_id", id).put("name", name).put("success", true).toString())) Thread.sleep(250)
                }
            } catch (t: Throwable) {
                if (!transferId.isNullOrBlank()) {
                    MacConnectionService.sendToConnected(JSONObject()
                        .put("type", "file_drag_end")
                        .put("transfer_id", id)
                        .put("name", name)
                        .put("success", false)
                        .put("message", t.message ?: "Unable to read file")
                        .toString())
                }
            }
        }.start()
    }

    private fun queryName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && idx >= 0) c.getString(idx) else null
        }
    }.getOrNull()

    private fun publishToDownloads(context: Context, source: File, name: String): String {
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, guessMime(name))
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/MAAN")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return source.absolutePath
            try {
                resolver.openOutputStream(uri)?.use { out -> source.inputStream().use { it.copyTo(out) } }
                resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                return "Downloads/MAAN/$name"
            } catch (t: Throwable) {
                resolver.delete(uri, null, null)
                return source.absolutePath
            }
        }
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "MAAN")
        dir.mkdirs()
        val target = uniqueFile(dir, name)
        source.copyTo(target, overwrite = false)
        return target.absolutePath
    }

    private fun uniqueFile(dir: File, name: String): File {
        val base = File(dir, name)
        if (!base.exists()) return base
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (true) {
            val candidate = File(dir, "$stem ($i)$ext")
            if (!candidate.exists()) return candidate
            i++
        }
    }

    private fun safeName(name: String): String = name.replace("/", "_").replace("\\", "_").trim().ifBlank { "file.bin" }
    private fun guessMime(name: String): String = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
}
