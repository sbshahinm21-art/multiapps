package com.multiapps.app.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.multiapps.app.BuildConfig
import com.multiapps.app.data.Prefs
import com.multiapps.app.net.Api
import com.multiapps.app.net.ReleaseInfo
import com.multiapps.app.net.UpdateCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val check: UpdateCheck) : UpdateState
    data class Downloading(val check: UpdateCheck, val progress: Float) : UpdateState
    data class ReadyToInstall(val check: UpdateCheck, val file: File) : UpdateState
    data class Installing(val check: UpdateCheck) : UpdateState
    data class Failed(val message: String, val check: UpdateCheck?) : UpdateState
}

/**
 * Checks the server for updates, downloads the APK (resumable), verifies its SHA-256,
 * and hands it to Android's PackageInstaller. Android shows its own confirmation screen.
 */
class UpdateManager(private val context: Context, private val api: Api, private val prefs: Prefs) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    /** True when the current version was withdrawn (rolled back) by the admin. */
    val currentWithdrawn = MutableStateFlow(false)

    private val currentVersion: Long get() = BuildConfig.VERSION_CODE.toLong()
    private val dir: File get() = File(context.filesDir, "updates").apply { mkdirs() }

    /** Set when the user taps "Check for updates", so a previously dismissed update is shown again. */
    @Volatile var manualCheck = false

    suspend fun check(manual: Boolean = false): UpdateCheck? {
        manualCheck = manual
        _state.value = UpdateState.Checking
        return try {
            val c = api.checkUpdate(prefs.deviceId, currentVersion, prefs.betaChannel, "${Build.MANUFACTURER} ${Build.MODEL}", Build.VERSION.SDK_INT)
            currentWithdrawn.value = c.currentWithdrawn
            _state.value = if (c.updateAvailable && c.release != null) UpdateState.Available(c) else UpdateState.UpToDate
            c
        } catch (e: Exception) {
            _state.value = UpdateState.Failed(e.message ?: "Could not check for updates", null)
            null
        }
    }

    fun dismiss(check: UpdateCheck) {
        check.release?.let { prefs.dismissedUpdateVersion = it.versionCode }
        _state.value = UpdateState.Idle
    }

    suspend fun download(check: UpdateCheck) {
        val r = check.release ?: return
        val file = File(dir, "update-${r.versionCode}.apk")
        _state.value = UpdateState.Downloading(check, 0f)
        api.sendEvent(prefs.deviceId, currentVersion, r.id, "download_started")
        try {
            withContext(Dispatchers.IO) { downloadFile(r, file) { p -> _state.value = UpdateState.Downloading(check, p) } }
            val actual = withContext(Dispatchers.IO) { sha256(file) }
            if (!actual.equals(r.sha256, ignoreCase = true)) {
                file.delete()
                throw IOException("The downloaded file is damaged (checksum mismatch). Please try again.")
            }
            api.sendEvent(prefs.deviceId, currentVersion, r.id, "download_completed")
            _state.value = UpdateState.ReadyToInstall(check, file)
        } catch (e: Exception) {
            api.sendEvent(prefs.deviceId, currentVersion, r.id, "download_failed", e.message)
            _state.value = UpdateState.Failed(e.message ?: "Download failed", check)
        }
    }

    private fun downloadFile(r: ReleaseInfo, file: File, onProgress: (Float) -> Unit) {
        // Remove leftovers from other versions.
        dir.listFiles()?.filter { it.name != file.name }?.forEach { it.delete() }
        var existing = if (file.exists()) file.length() else 0L
        if (r.fileSize > 0 && existing == r.fileSize) { onProgress(1f); return } // already complete; checksum decides
        if (r.fileSize > 0 && existing > r.fileSize) { file.delete(); existing = 0L }
        val conn = URL(r.downloadUrl).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            if (existing > 0) conn.setRequestProperty("Range", "bytes=$existing-")
            val code = conn.responseCode
            val append = code == 206
            if (code != 200 && code != 206) throw IOException("Download failed (HTTP $code)")
            if (!append) existing = 0L
            val total = if (r.fileSize > 0) r.fileSize else existing + conn.contentLengthLong
            conn.inputStream.use { input ->
                FileOutputStream(file, append).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = existing
                    var lastReport = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0 && done - lastReport > 256 * 1024) {
                            lastReport = done
                            onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            }
            onProgress(1f)
        } finally {
            conn.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** True if Android allows this app to install APKs (user setting "Install unknown apps"). */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    suspend fun install(check: UpdateCheck, file: File) {
        val r = check.release ?: return
        _state.value = UpdateState.Installing(check)
        try {
            withContext(Dispatchers.IO) {
                val installer = context.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                params.setAppPackageName(context.packageName)
                if (Build.VERSION.SDK_INT >= 31) {
                    params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
                val sessionId = installer.createSession(params)
                installer.openSession(sessionId).use { session ->
                    session.openWrite("base.apk", 0, file.length()).use { out ->
                        file.inputStream().use { it.copyTo(out) }
                        session.fsync(out)
                    }
                    prefs.pendingInstallReleaseId = r.id
                    prefs.pendingInstallVersionCode = r.versionCode
                    val intent = Intent(context, InstallReceiver::class.java)
                        .putExtra(InstallReceiver.EXTRA_RELEASE_ID, r.id)
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                    val pi = PendingIntent.getBroadcast(context, sessionId, intent, flags)
                    session.commit(pi.intentSender)
                }
            }
        } catch (e: Exception) {
            api.sendEvent(prefs.deviceId, currentVersion, r.id, "install_failed", e.message)
            _state.value = UpdateState.Failed(e.message ?: "Install failed", check)
        }
    }

    /** Called by InstallReceiver when Android reports a failure or cancellation. */
    fun onInstallFailed(message: String) {
        val s = _state.value
        val check = (s as? UpdateState.Installing)?.check
        _state.value = UpdateState.Failed(message, check)
    }

    /** On app start: if we were updated to the version we installed, report success once. */
    suspend fun reportPendingInstall() {
        val target = prefs.pendingInstallVersionCode
        if (target == 0L) return
        val releaseId = prefs.pendingInstallReleaseId
        if (currentVersion >= target) {
            api.sendEvent(prefs.deviceId, currentVersion, releaseId, "install_success")
            dir.listFiles()?.forEach { it.delete() }
        }
        prefs.pendingInstallVersionCode = 0L
        prefs.pendingInstallReleaseId = 0L
    }
}
