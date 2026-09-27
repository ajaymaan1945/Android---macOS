package com.maan.connect

import org.json.JSONObject

object MaanProtocol {
    fun hello(device: String) = JSONObject().put("type", "hello").put("app", "MAAN").put("version", 2).put("device", device)
    fun registerControl() = JSONObject().put("type", "register_control")
    fun callState(id: String?, state: String) = JSONObject().put("type", "call_state").put("call_id", id ?: "").put("state", state)
    fun incomingCall(id: String, name: String, number: String) = JSONObject().put("type", "incoming_call").put("call_id", id).put("name", name).put("number", number)
}
