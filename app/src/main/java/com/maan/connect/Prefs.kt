package com.maan.connect

import android.content.Context

object Prefs {
    private const val NAME = "MAANPrefs"
    private const val KEY_MAC_IP = "mac_ip"
    private const val KEY_TRUSTED = "trusted_mac"
    private const val KEY_AUTO_NETWORK = "auto_network_reconnect"
    private const val KEY_CALL_NOTIF = "call_notifications"
    private const val KEY_ALL_NOTIF = "all_notifications"
    private const val KEY_MULTI = "multiple_devices"
    private const val KEY_CLIPBOARD = "clipboard_sync"
    private const val KEY_FILES = "file_transfer"
    private const val KEY_SCREEN = "screen_sharing"
    private const val KEY_MAC_NAME = "mac_name"
    private const val KEY_TOKEN = "pair_token"

    private fun p(c: Context) = c.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    fun macIp(c: Context) = p(c).getString(KEY_MAC_IP, null)
    fun saveMacIp(c: Context, ip: String) = p(c).edit().putString(KEY_MAC_IP, ip).apply()
    fun trusted(c: Context) = p(c).getBoolean(KEY_TRUSTED, false)
    fun setTrusted(c: Context, value: Boolean) = p(c).edit().putBoolean(KEY_TRUSTED, value).apply()
    fun autoNetwork(c: Context) = p(c).getBoolean(KEY_AUTO_NETWORK, false)
    fun callNotifications(c: Context) = p(c).getBoolean(KEY_CALL_NOTIF, true)
    fun allNotifications(c: Context) = p(c).getBoolean(KEY_ALL_NOTIF, true)
    fun multipleDevices(c: Context) = p(c).getBoolean(KEY_MULTI, false)
    fun clipboard(c: Context) = p(c).getBoolean(KEY_CLIPBOARD, true)
    fun files(c: Context) = p(c).getBoolean(KEY_FILES, true)
    fun screen(c: Context) = p(c).getBoolean(KEY_SCREEN, true)
    fun macName(c: Context) = p(c).getString(KEY_MAC_NAME, "Mac") ?: "Mac"
    fun token(c: Context) = p(c).getString(KEY_TOKEN, null)
    fun saveToken(c: Context, token: String) = p(c).edit().putString(KEY_TOKEN, token).apply()
    fun saveCallId(c: Context, id: String?) = p(c).edit().putString("active_call_id", id).apply()
    fun callId(c: Context) = p(c).getString("active_call_id", null)
}
