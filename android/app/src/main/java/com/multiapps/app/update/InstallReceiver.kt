package com.multiapps.app.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat
import com.multiapps.app.BuildConfig
import com.multiapps.app.MultiAppsApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Receives the result of an update install from Android's PackageInstaller. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as MultiAppsApp
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val releaseId = intent.getLongExtra(EXTRA_RELEASE_ID, 0L)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Android needs the user to confirm the update: show its confirmation screen.
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try { context.startActivity(confirm) } catch (e: Exception) {
                        app.container.updates.onInstallFailed("Could not open the install screen. Open Multi Apps and try again.")
                    }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                // Normally the app is restarted by the update; success is reported on next start.
            }
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    ?: if (status == PackageInstaller.STATUS_FAILURE_ABORTED) "Update was cancelled" else "Update failed"
                val friendly = when (status) {
                    PackageInstaller.STATUS_FAILURE_ABORTED -> "Update was cancelled."
                    PackageInstaller.STATUS_FAILURE_CONFLICT -> "Update failed: the new version is signed with a different key."
                    PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough storage to install the update."
                    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "This update is not compatible with your phone."
                    else -> "Update failed: $msg"
                }
                app.container.prefs.pendingInstallVersionCode = 0L
                app.container.prefs.pendingInstallReleaseId = 0L
                app.container.updates.onInstallFailed(friendly)
                val pending = goAsync()
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    try {
                        app.container.api.sendEvent(
                            app.container.prefs.deviceId, BuildConfig.VERSION_CODE.toLong(),
                            releaseId.takeIf { it > 0 }, "install_failed", "$status: $msg",
                        )
                    } finally { pending.finish() }
                }
            }
        }
    }

    companion object { const val EXTRA_RELEASE_ID = "release_id" }
}
