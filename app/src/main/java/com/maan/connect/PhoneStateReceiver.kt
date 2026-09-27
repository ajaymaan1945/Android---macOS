package com.maan.connect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.ContactsContract
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.UUID

class PhoneStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                if (!Prefs.callNotifications(context)) return
                // Create the call record at RINGING time. The old implementation
                // returned here when no call id existed, so the Mac never received
                // an incoming_call event.
                if (Prefs.callId(context) == null) {
                    val id = "call-${UUID.randomUUID()}"
                    val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER).orEmpty()
                    val name = lookupContactName(context, number) ?: number.ifBlank { "Incoming call" }
                    Prefs.saveCallId(context, id)

                    // Make sure the connection service is alive before sending.
                    runCatching {
                        ContextCompat.startForegroundService(
                            context,
                            Intent(context, MacConnectionService::class.java)
                        )
                    }

                    MacConnectionService.sendToConnected(
                        MaanProtocol.incomingCall(id, name, number).toString()
                    )
                }
            }

            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                val id = Prefs.callId(context) ?: return
                MacConnectionService.sendToConnected(
                    MaanProtocol.callState(id, "ANSWERED").toString()
                )
            }

            TelephonyManager.EXTRA_STATE_IDLE -> {
                val id = Prefs.callId(context) ?: return
                MacConnectionService.sendToConnected(
                    MaanProtocol.callState(id, "ENDED").toString()
                )
                Prefs.saveCallId(context, null)
            }
        }
    }

    private fun lookupContactName(context: Context, number: String): String? {
        if (number.isBlank()) return null
        return runCatching {
            context.contentResolver.query(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI.buildUpon()
                    .appendPath(number)
                    .build(),
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
    }
}
