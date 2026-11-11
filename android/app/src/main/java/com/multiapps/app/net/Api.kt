package com.multiapps.app.net

import com.multiapps.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class ApiException(message: String, val status: Int = 0) : IOException(message)

data class ReleaseInfo(
    val id: Long,
    val versionCode: Long,
    val versionName: String,
    val changelog: String,
    val fileSize: Long,
    val sha256: String,
    val downloadUrl: String,
)

data class UpdateCheck(
    val updateAvailable: Boolean,
    val mandatory: Boolean,
    val tester: Boolean,
    val currentWithdrawn: Boolean,
    val release: ReleaseInfo?,
) {
    companion object {
        fun parse(o: JSONObject): UpdateCheck {
            val r = o.optJSONObject("release")
            return UpdateCheck(
                updateAvailable = o.optBoolean("updateAvailable"),
                mandatory = o.optBoolean("mandatory"),
                tester = o.optBoolean("tester"),
                currentWithdrawn = o.optBoolean("currentWithdrawn"),
                release = r?.let {
                    ReleaseInfo(
                        id = it.getLong("id"),
                        versionCode = it.getLong("versionCode"),
                        versionName = it.getString("versionName"),
                        changelog = it.optString("changelog"),
                        fileSize = it.optLong("fileSize"),
                        sha256 = it.getString("sha256"),
                        downloadUrl = it.getString("downloadUrl"),
                    )
                },
            )
        }
    }
}

data class TicketMessage(val sender: String, val body: String, val createdAt: Long)
data class TicketThread(val id: Int, val subject: String, val status: String, val messages: List<TicketMessage>)

/** Talks to the Multi Apps server (Cloudflare Worker). All calls run on the IO dispatcher. */
class Api(private val base: String = BuildConfig.API_BASE) {

    private data class Resp(val code: Int, val body: String, val etag: String?)

    private fun call(method: String, path: String, body: JSONObject? = null, headers: Map<String, String> = emptyMap()): Resp {
        val conn = URL(base + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "MultiApps/${BuildConfig.VERSION_NAME} Android")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            if (code == 304) return Resp(code, "", conn.getHeaderField("ETag"))
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code >= 400) {
                val msg = try { JSONObject(text).optString("error") } catch (e: Exception) { "" }
                throw ApiException(msg.ifBlank { "Server error ($code)" }, code)
            }
            return Resp(code, text, conn.getHeaderField("ETag"))
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    suspend fun checkUpdate(deviceId: String, versionCode: Long, beta: Boolean, model: String, sdk: Int): UpdateCheck =
        withContext(Dispatchers.IO) {
            val q = "deviceId=${enc(deviceId)}&versionCode=$versionCode&channel=${if (beta) "beta" else "stable"}&model=${enc(model)}&sdk=$sdk"
            UpdateCheck.parse(JSONObject(call("GET", "/api/v1/update/check?$q").body))
        }

    /** Never throws: statistics must not break the app. */
    suspend fun sendEvent(deviceId: String, versionCode: Long, releaseId: Long?, event: String, detail: String? = null) {
        withContext(Dispatchers.IO) {
            try {
                val b = JSONObject().put("deviceId", deviceId).put("versionCode", versionCode).put("event", event)
                if (releaseId != null) b.put("releaseId", releaseId)
                if (!detail.isNullOrBlank()) b.put("detail", detail.take(500))
                call("POST", "/api/v1/events", b)
            } catch (e: Exception) { /* ignored */ }
        }
    }

    /** Returns (json, etag), or null when the server says nothing changed (HTTP 304). */
    suspend fun fetchConfig(deviceId: String, beta: Boolean, etag: String?): Pair<String, String?>? =
        withContext(Dispatchers.IO) {
            val headers = if (etag != null) mapOf("If-None-Match" to etag) else emptyMap()
            val r = call("GET", "/api/v1/config?deviceId=${enc(deviceId)}&channel=${if (beta) "beta" else "stable"}", headers = headers)
            if (r.code == 304) null else r.body to r.etag
        }

    suspend fun joinBeta(deviceId: String, code: String) = withContext(Dispatchers.IO) {
        call("POST", "/api/v1/beta/join", JSONObject().put("deviceId", deviceId).put("code", code)); Unit
    }

    suspend fun leaveBeta(deviceId: String) = withContext(Dispatchers.IO) {
        call("POST", "/api/v1/beta/leave", JSONObject().put("deviceId", deviceId)); Unit
    }

    /** Returns (ticketId, accessToken). */
    suspend fun createTicket(
        deviceId: String, email: String, category: String, subject: String, message: String,
        appVersion: String, deviceInfo: String,
    ): Pair<Int, String> = withContext(Dispatchers.IO) {
        val b = JSONObject().put("deviceId", deviceId).put("email", email).put("category", category)
            .put("subject", subject).put("message", message).put("appVersion", appVersion).put("deviceInfo", deviceInfo)
        val o = JSONObject(call("POST", "/api/v1/support/tickets", b).body)
        o.getInt("id") to o.getString("accessToken")
    }

    suspend fun getTicket(id: Int, token: String): TicketThread = withContext(Dispatchers.IO) {
        val o = JSONObject(call("GET", "/api/v1/support/tickets/$id", headers = mapOf("X-Ticket-Token" to token)).body)
        val arr: JSONArray = o.getJSONArray("messages")
        TicketThread(
            id = o.getInt("id"), subject = o.getString("subject"), status = o.getString("status"),
            messages = (0 until arr.length()).map {
                val m = arr.getJSONObject(it)
                TicketMessage(m.getString("sender"), m.getString("body"), m.getLong("createdAt"))
            },
        )
    }

    suspend fun replyTicket(id: Int, token: String, message: String) = withContext(Dispatchers.IO) {
        call("POST", "/api/v1/support/tickets/$id/messages", JSONObject().put("message", message), mapOf("X-Ticket-Token" to token)); Unit
    }
}
