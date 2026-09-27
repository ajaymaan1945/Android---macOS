package com.maan.connect

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.app.role.RoleManager
import android.view.View
import android.widget.TextView
import android.net.Uri
import java.io.InputStream
import java.util.UUID
import android.util.Base64
import org.json.JSONObject
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var device: TextView
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { startMaan() }
    private val projection = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK && r.data != null) {
            val i = Intent(this, ScreenProjectionService::class.java).putExtra(ScreenProjectionService.EXTRA_RESULT_CODE, r.resultCode).putExtra(ScreenProjectionService.EXTRA_DATA, r.data)
            ContextCompat.startForegroundService(this, i)
        }
    }

    private fun requestCallScreeningRoleIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val roleManager = getSystemService(RoleManager::class.java) ?: return
        if (roleManager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)) return
        if (!roleManager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) return
        runCatching {
            startActivityForResult(
                roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING),
                9001
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); setContentView(R.layout.activity_main)
        status = findViewById(R.id.connectionStatus); device = findViewById(R.id.deviceName)
        findViewById<View>(R.id.screenButton).setOnClickListener { requestScreenShare() }
        findViewById<View>(R.id.filesButton).setOnClickListener { pickFile() }
        findViewById<View>(R.id.fileBridgeButton).setOnClickListener { startActivity(Intent(this, FileBridgeActivity::class.java)) }
        findViewById<View>(R.id.settingsButton).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        requestSetup()
        requestCallScreeningRoleIfNeeded()
    }

    private fun requestSetup() {
        val list = mutableListOf(Manifest.permission.READ_CONTACTS, Manifest.permission.READ_PHONE_STATE, Manifest.permission.ANSWER_PHONE_CALLS, Manifest.permission.READ_CALL_LOG)
        if (Build.VERSION.SDK_INT >= 33) list += Manifest.permission.NEARBY_WIFI_DEVICES else list += Manifest.permission.ACCESS_FINE_LOCATION
        if (Build.VERSION.SDK_INT >= 33) list += Manifest.permission.POST_NOTIFICATIONS
        val missing = list.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permissions.launch(missing.toTypedArray()) else startMaan()
    }

    private fun startMaan() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, MacConnectionService::class.java)
        )
        status.text = if (Prefs.macIp(this) == null) {
            "● Looking for your Mac"
        } else {
            "● Reconnecting automatically"
        }
        device.text = "MAAN will keep the trusted connection in the background."
    }

    private fun requestScreenShare() {
        if (!Prefs.screen(this)) return
        val pm = getSystemService(MediaProjectionManager::class.java)
        projection.launch(pm.createScreenCaptureIntent())
    }

    private fun pickFile() {
        if (!Prefs.files(this)) return
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE) }
        startActivityForResult(i, 701)
    }

    @Deprecated("Android activity result API retained for broad device compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 701 || resultCode != Activity.RESULT_OK || data?.data == null) return
        if (Prefs.files(this)) sendUriToMac(data.data!!)
    }

    private fun sendUriToMac(uri: Uri) {
        FileTransferManager.sendUriToMac(this, uri)
    }
}
