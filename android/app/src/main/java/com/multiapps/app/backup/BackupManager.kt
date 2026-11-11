package com.multiapps.app.backup

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.multiapps.app.data.AppDatabase
import com.multiapps.app.data.CloneEntity
import com.multiapps.app.data.KeystoreCrypto
import com.multiapps.app.data.Prefs
import com.multiapps.app.data.ProxyEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Exports / restores the clone list, proxies (including passwords) and settings
 * as one password-encrypted file. App data inside the clones is not included.
 */
class BackupManager(private val context: Context, private val db: AppDatabase, private val prefs: Prefs) {

    data class Summary(val clones: Int, val proxies: Int)

    suspend fun export(uri: Uri, password: CharArray): Summary = withContext(Dispatchers.IO) {
        val clones = db.clones().all()
        val proxies = db.proxies().all()
        val json = JSONObject()
            .put("format", "multi-apps-backup")
            .put("version", 1)
            .put("createdAt", System.currentTimeMillis())
            .put("settings", prefs.exportSettings())
            .put("proxies", JSONArray().apply {
                proxies.forEach { p ->
                    put(JSONObject().put("id", p.id).put("name", p.name).put("type", p.type).put("host", p.host)
                        .put("port", p.port).put("username", p.username).put("password", KeystoreCrypto.decrypt(p.passwordEnc)))
                }
            })
            .put("clones", JSONArray().apply {
                clones.forEach { c ->
                    put(JSONObject().put("packageName", c.packageName).put("appLabel", c.appLabel).put("name", c.name)
                        .put("colorIndex", c.colorIndex).put("proxyId", c.proxyId ?: JSONObject.NULL)
                        .put("askProxyOnLaunch", c.askProxyOnLaunch).put("notificationsEnabled", c.notificationsEnabled)
                        .put("notificationsMuted", c.notificationsMuted).put("createdAt", c.createdAt).put("engine", c.engine))
                }
            })
        val bytes = BackupCrypto.encrypt(json.toString().toByteArray(Charsets.UTF_8), password)
        val out = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Could not create the backup file")
        out.use { it.write(bytes) }
        Summary(clones.size, proxies.size)
    }

    /** Replaces the current clone list and proxies with the backup's contents. */
    suspend fun restore(uri: Uri, password: CharArray): Summary = withContext(Dispatchers.IO) {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Could not open the file")
        val data = input.use { it.readBytes() }
        if (data.size > 20 * 1024 * 1024) throw BackupCrypto.NotABackupException()
        val json = JSONObject(String(BackupCrypto.decrypt(data, password), Charsets.UTF_8))
        if (json.optString("format") != "multi-apps-backup") throw BackupCrypto.NotABackupException()
        val proxies = json.optJSONArray("proxies") ?: JSONArray()
        val clones = json.optJSONArray("clones") ?: JSONArray()
        db.withTransaction {
            db.clones().deleteAll()
            db.proxies().deleteAll()
            val idMap = HashMap<Long, Long>()
            for (i in 0 until proxies.length()) {
                val p = proxies.getJSONObject(i)
                val newId = db.proxies().insert(
                    ProxyEntity(
                        name = p.getString("name"), type = p.getString("type"), host = p.getString("host"),
                        port = p.getInt("port"), username = p.optString("username"),
                        passwordEnc = KeystoreCrypto.encrypt(p.optString("password")),
                    )
                )
                idMap[p.getLong("id")] = newId
            }
            for (i in 0 until clones.length()) {
                val c = clones.getJSONObject(i)
                val oldProxy = if (c.isNull("proxyId")) null else c.getLong("proxyId")
                db.clones().insert(
                    CloneEntity(
                        packageName = c.getString("packageName"), appLabel = c.optString("appLabel"), name = c.getString("name"),
                        colorIndex = c.optInt("colorIndex"), proxyId = oldProxy?.let { idMap[it] },
                        askProxyOnLaunch = c.optBoolean("askProxyOnLaunch", true),
                        notificationsEnabled = c.optBoolean("notificationsEnabled", true),
                        notificationsMuted = c.optBoolean("notificationsMuted", false),
                        createdAt = c.optLong("createdAt", System.currentTimeMillis()),
                        // After a restore the clone must be installed again in this phone's work profile.
                        engine = CloneEntity.ENGINE_NONE,
                    )
                )
            }
        }
        json.optJSONObject("settings")?.let { prefs.importSettings(it) }
        Summary(clones.length(), proxies.length())
    }
}
