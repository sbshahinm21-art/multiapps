package com.multiapps.app.engine

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build

/**
 * Route 3: clone management that runs inside the work profile itself.
 * Works without any cross-profile channel, so it also works on phones where routes 1 and 2 are blocked.
 * Only usable from the copy of Multi Apps that owns the work profile.
 */
object ProfileCloner {
    data class AppItem(val packageName: String, val label: String)

    private fun isUserApp(ai: ApplicationInfo) = (ai.flags and ApplicationInfo.FLAG_SYSTEM) == 0

    /** Apps installed on the phone (personal side) that are not yet in this profile. */
    fun cloneCandidates(context: Context): List<AppItem> {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val all = pm.queryIntentActivities(launcher, PackageManager.MATCH_UNINSTALLED_PACKAGES)
        return all.asSequence()
            .map { it.activityInfo.applicationInfo }
            .filter { it.packageName != context.packageName && isUserApp(it) && (it.flags and ApplicationInfo.FLAG_INSTALLED) == 0 }
            .distinctBy { it.packageName }
            .map { AppItem(it.packageName, it.loadLabel(pm).toString()) }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    /** User apps already copied into this profile. */
    fun clones(context: Context): List<AppItem> {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launcher, 0).asSequence()
            .map { it.activityInfo.applicationInfo }
            .filter { it.packageName != context.packageName && isUserApp(it) }
            .distinctBy { it.packageName }
            .map { AppItem(it.packageName, it.loadLabel(pm).toString()) }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    /** Copies an app that is installed on the personal side into this profile. Returns an error or null. */
    fun install(context: Context, packageName: String): String? {
        if (Build.VERSION.SDK_INT < 28) return "Android 9 or newer is needed."
        val dpm = WorkProfile.dpm(context)
        if (!dpm.isProfileOwnerApp(context.packageName)) return "Multi Apps does not own this profile."
        return try {
            val admin = WorkProfile.admin(context)
            if (dpm.installExistingPackage(admin, packageName)) {
                runCatching { dpm.setApplicationHidden(admin, packageName, false) }
                null
            } else "Android could not copy this app into the work profile."
        } catch (e: Exception) {
            "Could not copy the app: ${e.message}"
        }
    }

    /** Removes a clone and its data from this profile only. */
    fun uninstall(context: Context, packageName: String): String? = try {
        val pi = PendingIntent.getBroadcast(
            context, packageName.hashCode(), Intent(context, UninstallResultReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        context.packageManager.packageInstaller.uninstall(packageName, pi.intentSender)
        null
    } catch (e: Exception) {
        "Could not remove the clone: ${e.message}"
    }

    fun launch(context: Context, packageName: String): String? {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return "This clone cannot be opened."
        return try { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); null } catch (e: Exception) { e.message }
    }
}
