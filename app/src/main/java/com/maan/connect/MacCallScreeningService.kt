package com.maan.connect

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import androidx.core.content.ContextCompat
import android.net.Uri
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.CallScreeningService
import android.util.Log
import java.util.UUID

class MacCallScreeningService : CallScreeningService() {
    override fun onScreenCall(callDetails: Call.Details) {
        val number = callDetails.handle?.schemeSpecificPart?.trim().takeUnless { it.isNullOrBlank() } ?: "Unknown Number"
        val name = findContactName(number)
        val callId = "call-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(6)}"
        Prefs.saveCallId(this, callId)
        respondToCall(callDetails, CallResponse.Builder().build())

        // The screening service can be invoked before MAAN's persistent
        // connection service is alive. Start it first; the connection service
        // queues control messages until the WebSocket is connected.
        runCatching {
            ContextCompat.startForegroundService(
                this,
                Intent(this, MacConnectionService::class.java)
            )
        }

        MacConnectionService.sendToConnected(
            MaanProtocol.incomingCall(callId, name, number).toString()
        )
    }

    private fun findContactName(number: String): String {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return "Unknown Caller"
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        return runCatching { contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } ?: "Unknown Caller" else "Unknown Caller" } ?: "Unknown Caller" }.getOrDefault("Unknown Caller")
    }
}
