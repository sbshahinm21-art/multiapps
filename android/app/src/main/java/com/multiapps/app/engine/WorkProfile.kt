package com.multiapps.app.engine

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.CrossProfileApps
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.os.UserManager

/**
 * Clone engine based on Android's official Work Profile.
 *
 * How it works:
 *  - Multi Apps creates a work profile (Android shows its own setup screens) and becomes its "profile owner".
 *  - A copy of Multi Apps runs inside the profile. The main app sends it commands through a
 *    cross-profile intent (ACTION_COMMAND). That copy installs an app that is already installed on
 *    the phone into the profile (no download), or removes it.
 *  - The cloned app has completely separate data, accounts and notifications.
 *  - The main app opens the clone with LauncherApps.startMainActivity(…, profileUser).
 *
 * Limit: one copy of each app per work profile, and Android allows one work profile per phone.
 */
object WorkProfile {
    const val ACTION_COMMAND = "com.multiapps.app.action.PROFILE_COMMAND"
    const val EXTRA_CMD = "cmd"
    const val EXTRA_PACKAGE = "pkg"
    const val EXTRA_MESSAGE = "message"
    const val CMD_PING = "ping"
    const val CMD_INSTALL = "install"
    const val CMD_UNINSTALL = "uninstall"

    /** Android 9+ is needed to copy an installed app into the profile. */
    val isSupportedVersion: Boolean get() = Build.VERSION.SDK_INT >= 28

    fun admin(context: Context) = ComponentName(context, ProfileAdminReceiver::class.java)

    fun dpm(context: Context): DevicePolicyManager = context.getSystemService(DevicePolicyManager::class.java)

    /** True when this copy of the app is running inside the work profile it manages. */
    fun isRunningInManagedProfile(context: Context): Boolean = dpm(context).isProfileOwnerApp(context.packageName)

    /** True if Android lets this phone create a work profile now. */
    fun canCreateProfile(context: Context): Boolean =
        isSupportedVersion && dpm(context).isProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE)

    fun provisioningIntent(context: Context): Intent =
        Intent(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE)
            .putExtra(DevicePolicyManager.EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME, admin(context))
            .putExtra(DevicePolicyManager.EXTRA_PROVISIONING_SKIP_ENCRYPTION, true)

    /**
     * The work profile that Multi Apps manages, if it exists.
     * Uses CrossProfileApps, which only lists profiles where Multi Apps itself is installed. This skips
     * other kinds of profiles that Android 14/15 phones have (app cloning / "Dual apps", Private Space),
     * which UserManager.userProfiles also returns.
     */
    fun profileUser(context: Context): UserHandle? {
        if (Build.VERSION.SDK_INT < 28) return null
        return try {
            context.getSystemService(CrossProfileApps::class.java).targetUserProfiles
                .firstOrNull { it != Process.myUserHandle() }
        } catch (e: Exception) { null }
    }

    /** True when work apps are paused by the user (quick settings "Work apps" toggle). */
    fun isPaused(context: Context): Boolean {
        val user = profileUser(context) ?: return false
        return context.getSystemService(UserManager::class.java).isQuietModeEnabled(user)
    }

    fun isInstalledInProfile(context: Context, packageName: String): Boolean {
        val user = profileUser(context) ?: return false
        return try {
            context.getSystemService(LauncherApps::class.java).getActivityList(packageName, user).isNotEmpty()
        } catch (e: Exception) { false }
    }

    /** Opens the cloned app. Returns an error message, or null on success. */
    fun launch(context: Context, packageName: String): String? {
        val user = profileUser(context) ?: return "The work profile was not found. Set up the clone engine again in Settings."
        if (isPaused(context)) return "Work apps are paused. Turn on “Work apps” in quick settings, then try again."
        val la = context.getSystemService(LauncherApps::class.java)
        val activity = try { la.getActivityList(packageName, user).firstOrNull() } catch (e: Exception) { null }
            ?: return "This clone is not installed in the work profile. Open the clone's settings and tap “Install clone”."
        return try {
            la.startMainActivity(activity.componentName, user, null, null)
            null
        } catch (e: Exception) {
            "Could not open the clone: ${e.message}"
        }
    }

    /** Route 1: is the command intent forwarded into the work profile? */
    fun intentRouteAvailable(context: Context): Boolean =
        context.packageManager.queryIntentActivities(commandIntent(CMD_PING), PackageManager.MATCH_DEFAULT_ONLY).isNotEmpty()

    /** Route 2 (Android 11+): "Connected work & personal apps" allowed for Multi Apps. */
    fun connectedAppsAllowed(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 30 && runCatching { context.getSystemService(CrossProfileApps::class.java).canInteractAcrossProfiles() }.getOrDefault(false)

    /** Route 2 can be turned on by the user (the profile copy has allowed it). */
    fun connectedAppsCanRequest(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 30 && runCatching { context.getSystemService(CrossProfileApps::class.java).canRequestInteractAcrossProfiles() }.getOrDefault(false)

    /** Opens the Multi Apps copy inside the profile, which re-runs its setup. Returns an error or null. */
    fun restartEngine(context: Context): String? {
        val user = profileUser(context) ?: return "The work profile was not found."
        if (isPaused(context)) return "Work apps are paused. Turn on “Work apps” in quick settings, then try again."
        return try {
            context.getSystemService(CrossProfileApps::class.java)
                .startMainActivity(ComponentName(context, com.multiapps.app.MainActivity::class.java), user)
            null
        } catch (e: Exception) {
            "Could not start the engine in the work profile (${e.javaClass.simpleName}). Open “Multi Apps” from the Work tab once, then try again."
        }
    }

    fun commandIntent(cmd: String, packageName: String? = null): Intent =
        Intent(ACTION_COMMAND).addCategory(Intent.CATEGORY_DEFAULT).putExtra(EXTRA_CMD, cmd).apply {
            if (packageName != null) putExtra(EXTRA_PACKAGE, packageName)
        }
}
