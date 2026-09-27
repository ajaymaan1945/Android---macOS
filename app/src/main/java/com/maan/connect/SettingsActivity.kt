package com.maan.connect

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Switch
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {
    private fun row(title: String, checked: Boolean, on: (Boolean)->Unit): Switch = Switch(this).apply { text=title; isChecked=checked; textSize=16f; setPadding(8,18,8,18); setOnCheckedChangeListener { _, v -> on(v) } }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(22,28,22,22) }
        root.addView(TextView(this).apply { text="MAAN Settings"; textSize=30f; setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(0,0,0,24) })
        root.addView(TextView(this).apply { text="Connection & services"; textSize=18f; setPadding(0,0,0,10) })
        root.addView(row("Automatic network reconnect", Prefs.autoNetwork(this)) { getSharedPreferences("MAANPrefs",0).edit().putBoolean("auto_network_reconnect",it).apply() })
        root.addView(row("Call notifications", Prefs.callNotifications(this)) { getSharedPreferences("MAANPrefs",0).edit().putBoolean("call_notifications",it).apply() })
        root.addView(row("All MAAN notifications", Prefs.allNotifications(this)) { getSharedPreferences("MAANPrefs",0).edit().putBoolean("all_notifications",it).apply() })
        root.addView(row("Clipboard sync", Prefs.clipboard(this)) { getSharedPreferences("MAANPrefs",0).edit().putBoolean("clipboard_sync",it).apply() })
        root.addView(row("File transfer", Prefs.files(this)) { getSharedPreferences("MAANPrefs",0).edit().putBoolean("file_transfer",it).apply() })
        root.addView(row("Screen sharing", Prefs.screen(this)) { getSharedPreferences("MAANPrefs",0).edit().putBoolean("screen_sharing",it).apply() })
        root.addView(row("Multiple devices", Prefs.multipleDevices(this)) { getSharedPreferences("MAANPrefs",0).edit().putBoolean("multiple_devices",it).apply() })
        root.addView(TextView(this).apply { text="Remote control uses Android Accessibility permission. Open Android Settings → Accessibility → MAAN to enable it. Screen sharing always requires Android's system confirmation."; textSize=14f; alpha=.7f; setPadding(8,20,8,20) })
        setContentView(root)
    }
}
