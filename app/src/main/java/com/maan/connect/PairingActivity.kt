package com.maan.connect

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import org.json.JSONObject

class PairingActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val code=intent.getStringExtra("code") ?: "------"
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(28,40,28,28) }
        root.addView(TextView(this).apply { text="Pair with MAAN"; textSize=30f; setTypeface(typeface, android.graphics.Typeface.BOLD) })
        root.addView(TextView(this).apply { text="Pairing code:\n\n$code\n\nApprove this request on your Mac, or confirm below."; textSize=18f; setPadding(0,24,0,24) })
        root.addView(MaterialButton(this).apply { text="Confirm & Trust this Mac"; setOnClickListener { MacConnectionService.sendToConnected(JSONObject().put("type","pair_confirm").put("code",code).put("device",android.os.Build.MODEL).toString()); finish() } })
        root.addView(MaterialButton(this).apply { text="Cancel"; setOnClickListener { finish() } })
        setContentView(root)
    }
}
