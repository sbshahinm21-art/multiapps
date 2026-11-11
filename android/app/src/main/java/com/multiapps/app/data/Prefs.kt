package com.multiapps.app.data

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Small key-value settings stored on the phone. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("multi_apps", Context.MODE_PRIVATE)

    /** Random anonymous ID for this install (shown in Settings → About; used for beta testing and support). */
    val deviceId: String
        get() {
            sp.getString(KEY_DEVICE_ID, null)?.let { return it }
            val id = UUID.randomUUID().toString().replace("-", "")
            sp.edit { putString(KEY_DEVICE_ID, id) }
            return id
        }

    var betaChannel: Boolean
        get() = sp.getBoolean("beta_channel", false)
        set(v) = sp.edit { putBoolean("beta_channel", v) }

    var configJson: String?
        get() = sp.getString("config_json", null)
        set(v) = sp.edit { putString("config_json", v) }

    var configEtag: String?
        get() = sp.getString("config_etag", null)
        set(v) = sp.edit { putString("config_etag", v) }

    var launchCount: Int
        get() = sp.getInt("launch_count", 0)
        set(v) = sp.edit { putInt("launch_count", v) }

    /** Update that was handed to the installer; checked on next start to report success. */
    var pendingInstallReleaseId: Long
        get() = sp.getLong("pending_release_id", 0L)
        set(v) = sp.edit { putLong("pending_release_id", v) }

    var pendingInstallVersionCode: Long
        get() = sp.getLong("pending_version_code", 0L)
        set(v) = sp.edit { putLong("pending_version_code", v) }

    /** Set after the copy of Multi Apps inside the work profile answered a test command. */
    var engineReady: Boolean
        get() = sp.getBoolean("engine_ready", false)
        set(v) = sp.edit { putBoolean("engine_ready", v) }

    var dismissedUpdateVersion: Long
        get() = sp.getLong("dismissed_update", 0L)
        set(v) = sp.edit { putLong("dismissed_update", v) }

    // ---- Support tickets created from this phone (id + private access token) ----
    data class TicketRef(val id: Int, val token: String, val subject: String, val createdAt: Long)

    fun tickets(): List<TicketRef> {
        val arr = JSONArray(sp.getString("tickets", "[]"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            TicketRef(o.getInt("id"), o.getString("token"), o.optString("subject"), o.optLong("createdAt"))
        }.sortedByDescending { it.createdAt }
    }

    fun addTicket(ref: TicketRef) {
        val arr = JSONArray(sp.getString("tickets", "[]"))
        arr.put(JSONObject().put("id", ref.id).put("token", ref.token).put("subject", ref.subject).put("createdAt", ref.createdAt))
        sp.edit { putString("tickets", arr.toString()) }
    }

    fun exportSettings(): JSONObject = JSONObject().put("betaChannel", betaChannel)
    fun importSettings(o: JSONObject) { betaChannel = o.optBoolean("betaChannel", false) }

    private companion object { const val KEY_DEVICE_ID = "device_id" }
}
