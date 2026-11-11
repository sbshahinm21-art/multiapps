package com.multiapps.app.engine

import android.app.Activity
import android.app.PendingIntent
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.content.IntentCompat
import com.multiapps.app.MainActivity

/** Device admin component. Android requires it for the app that owns the work profile. */
class ProfileAdminReceiver : DeviceAdminReceiver() {
    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        ProfileSetup.finish(context)
    }
}

/**
 * Runs inside the Multi Apps work profile. Safe to call any number of times: it runs after setup,
 * every time the profile copy of the app starts, after app updates and after the phone restarts,
 * so a step that failed once is retried automatically.
 */
object ProfileSetup {
    fun finish(context: Context) {
        val dpm = WorkProfile.dpm(context)
        if (!dpm.isProfileOwnerApp(context.packageName)) return
        val admin = WorkProfile.admin(context)
        val pm = context.packageManager
        // Each step is independent: one failing must not stop the others.
        runCatching { dpm.setProfileName(admin, "Multi Apps") }
        runCatching {
            // Route 1: the main app's command intents are forwarded into this profile.
            val filter = IntentFilter(WorkProfile.ACTION_COMMAND).apply { addCategory(Intent.CATEGORY_DEFAULT) }
            dpm.addCrossProfileIntentFilter(admin, filter, DevicePolicyManager.FLAG_PARENT_CAN_ACCESS_MANAGED)
        }
        runCatching {
            // Route 2 (Android 11+): "Connected work & personal apps". Lets the main app start the
            // command screen here directly, after the user allows it once.
            if (Build.VERSION.SDK_INT >= 30) dpm.setCrossProfilePackages(admin, setOf(context.packageName))
        }
        runCatching {
            pm.setComponentEnabledSetting(
                ComponentName(context, ProfileCommandActivity::class.java),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP,
            )
        }
        runCatching {
            // Keep the work-tab "Multi Apps" icon: it shows the engine status and lets the main app
            // restart the engine (CrossProfileApps.startMainActivity needs a launcher screen).
            pm.setComponentEnabledSetting(
                ComponentName(context, MainActivity::class.java),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP,
            )
        }
        runCatching { dpm.setProfileEnabled(admin) }
    }
}

/** Re-runs the profile setup after the app is updated or the phone restarts (inside the profile only). */
class ProfileRepairReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ProfileSetup.finish(context)
    }
}

/** Android 10+ asks the admin app which kind of provisioning it wants: a work profile. */
class GetProvisioningModeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val result = Intent()
        if (Build.VERSION.SDK_INT >= 29) {
            result.putExtra(DevicePolicyManager.EXTRA_PROVISIONING_MODE, DevicePolicyManager.PROVISIONING_MODE_MANAGED_PROFILE)
        }
        setResult(RESULT_OK, result)
        finish()
    }
}

/** Android 10+ asks the admin app to confirm its policies at the end of setup. Nothing to configure. */
class PolicyComplianceActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ProfileSetup.finish(this)
        setResult(RESULT_OK)
        finish()
    }
}

/** Android 8+ starts this inside the profile when setup succeeded. */
class ProvisioningSuccessActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ProfileSetup.finish(this)
        finish()
    }
}

/**
 * Runs inside the work profile and carries out commands from the main Multi Apps app.
 * Disabled by default; only the profile copy enables it, so the main app's own copy never answers.
 * Only accepts commands from Multi Apps itself.
 */
class ProfileCommandActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dpm = WorkProfile.dpm(this)
        val admin = WorkProfile.admin(this)
        val (ok, message) = try {
            when {
                !dpm.isProfileOwnerApp(packageName) -> false to "Not running in the Multi Apps work profile"
                callingPackage != null && callingPackage != packageName -> false to "Command rejected"
                else -> run(dpm, admin)
            }
        } catch (e: Exception) {
            false to (e.message ?: "Command failed")
        }
        setResult(if (ok) RESULT_OK else RESULT_FIRST_USER, Intent().putExtra(WorkProfile.EXTRA_MESSAGE, message))
        finish()
    }

    private fun run(dpm: DevicePolicyManager, admin: ComponentName): Pair<Boolean, String> {
        val pkg = intent.getStringExtra(WorkProfile.EXTRA_PACKAGE)
        return when (intent.getStringExtra(WorkProfile.EXTRA_CMD)) {
            WorkProfile.CMD_PING -> true to "ready"
            WorkProfile.CMD_INSTALL -> {
                if (pkg.isNullOrBlank()) return false to "No app given"
                if (Build.VERSION.SDK_INT < 28) return false to "Android 9 or newer is needed"
                val installed = dpm.installExistingPackage(admin, pkg)
                if (installed) {
                    dpm.setApplicationHidden(admin, pkg, false)
                    true to "installed"
                } else {
                    false to "Android could not copy this app into the work profile. Some system apps cannot be cloned."
                }
            }
            WorkProfile.CMD_UNINSTALL -> {
                if (pkg.isNullOrBlank()) return false to "No app given"
                // Removes the app and all its data from the work profile only. The normal app is not touched.
                val pi = PendingIntent.getBroadcast(
                    this, pkg.hashCode(), Intent(this, UninstallResultReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                packageManager.packageInstaller.uninstall(pkg, pi.intentSender)
                true to "removed"
            }
            else -> false to "Unknown command"
        }
    }
}

/**
 * Result of removing a clone inside the profile. As profile owner the removal is normally silent;
 * if Android still wants the user to confirm, show its confirmation screen.
 */
class UninstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java) ?: return
            try { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { /* nothing else to do */ }
        }
    }
}
