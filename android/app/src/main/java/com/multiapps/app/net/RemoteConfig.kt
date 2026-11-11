package com.multiapps.app.net

import com.multiapps.app.data.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/** Settings controlled from the Admin Dashboard (Remote Settings & Ads). Cached on the phone. */
data class AppConfig(val raw: JSONObject) {
    val adsEnabled: Boolean get() = raw.optJSONObject("ads")?.optBoolean("enabled", false) ?: false
    val announcement: String get() = raw.optJSONObject("messages")?.optString("announcement", "").orEmpty()
    val checkIntervalHours: Long get() = (raw.optJSONObject("update")?.optLong("checkIntervalHours", 6) ?: 6L).coerceIn(1, 72)
    val proxyEnabled: Boolean get() = raw.optJSONObject("features")?.optBoolean("proxyEnabled", true) ?: true
    val backupEnabled: Boolean get() = raw.optJSONObject("features")?.optBoolean("backupEnabled", true) ?: true
    val supportEmail: String get() = raw.optJSONObject("support")?.optString("contactEmail", "").orEmpty()
    val faqUrl: String get() = raw.optJSONObject("support")?.optString("faqUrl", "").orEmpty()
}

class RemoteConfigRepo(private val api: Api, private val prefs: Prefs) {
    private val _config = MutableStateFlow(load())
    val config: StateFlow<AppConfig> = _config

    private fun load(): AppConfig {
        val cached = prefs.configJson ?: return AppConfig(JSONObject())
        return try { AppConfig(JSONObject(cached).optJSONObject("config") ?: JSONObject()) } catch (e: Exception) { AppConfig(JSONObject()) }
    }

    /** Fetches the latest settings. Keeps the cached copy when offline. */
    suspend fun refresh(): Result<Unit> = runCatching {
        val res = api.fetchConfig(prefs.deviceId, prefs.betaChannel, prefs.configEtag)
        if (res != null) {
            prefs.configJson = res.first
            prefs.configEtag = res.second
            _config.value = load()
        }
    }
}
