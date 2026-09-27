package com.maan.connect

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import org.json.JSONObject

object ClipboardBridge {
    fun setText(context: Context, text: String) {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return
        cm.setPrimaryClip(ClipData.newPlainText("MAAN", text))
    }
    fun currentText(context: Context): String? {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return null
        return runCatching { cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() }.getOrNull()
    }
}
