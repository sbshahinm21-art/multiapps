package com.multiapps.app.engine

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.CrossProfileApps
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.multiapps.app.MultiAppsApp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Current state of the clone engine as seen from the main app. */
enum class EngineStatus { UNSUPPORTED, NOT_SET_UP, WAITING, READY, BLOCKED }

fun engineStatus(context: Context): EngineStatus {
    val prefs = (context.applicationContext as MultiAppsApp).container.prefs
    if (!WorkProfile.isSupportedVersion) return EngineStatus.UNSUPPORTED
    val hasProfile = WorkProfile.profileUser(context) != null
    return when {
        // The profile copy of Multi Apps exists. Even if no command route works on this phone,
        // clones can be added in the engine screen inside the profile (route 3) and opened from here.
        hasProfile -> EngineStatus.READY
        WorkProfile.canCreateProfile(context) -> EngineStatus.NOT_SET_UP
        else -> EngineStatus.BLOCKED
    }
}

fun engineStatusText(s: EngineStatus): String = when (s) {
    EngineStatus.UNSUPPORTED -> "Clones need Android 9 or newer."
    EngineStatus.NOT_SET_UP -> "Not set up yet. Android will create a separate, private space (a “work profile”) where your clones run with their own data."
    EngineStatus.WAITING -> "The Multi Apps work profile was created. Tap “Check engine” to finish."
    EngineStatus.READY -> "Ready. Each app can have one clone in the Multi Apps work profile. If adding a clone from here does not work on your phone, tap “Open engine” and add it there."
    EngineStatus.BLOCKED -> "This phone does not allow a new work profile right now. Usually another app (for example Island, Shelter or a work app) already owns the work profile. Remove it in that app, then come back."
}

/** Shown when the phone blocks both command routes; the user can still use the engine screen. */
const val NO_ROUTE = "This phone does not let Multi Apps send commands to the work profile. Tap “Open engine” and do it there — it takes one tap."

/** Result of a command sent to the copy of Multi Apps inside the work profile. */
data class CommandResult(val ok: Boolean, val message: String)

/**
 * Returns a function that sends a command to the work profile and calls [onResult] with the answer.
 *
 * Route 1: a cross-profile intent, answered with an activity result.
 * Route 2 (Android 11+, used when route 1 is not available on the phone): Android's
 * "Connected work & personal apps". The user allows it once; the main app then starts the
 * command screen in the profile directly and checks the outcome itself (e.g. is the clone installed).
 */
@Composable
fun rememberProfileCommand(onResult: (CommandResult) -> Unit): (Intent) -> Unit {
    val context = LocalContext.current
    val activity = context.findActivity()
    val scope = rememberCoroutineScope()
    val callback by rememberUpdatedState(onResult)
    val prefs = (context.applicationContext as MultiAppsApp).container.prefs
    val pending = remember { arrayOfNulls<Intent>(1) }

    fun viaConnectedApps(intent: Intent) {
        if (Build.VERSION.SDK_INT < 30 || activity == null || !WorkProfile.connectedAppsAllowed(context)) {
            callback(CommandResult(false, NO_ROUTE))
            return
        }
        val user = WorkProfile.profileUser(context)
            ?: return callback(CommandResult(false, "The Multi Apps work profile was not found. Set up the clone engine in Settings."))
        val cpa = context.getSystemService(CrossProfileApps::class.java)
        val explicit = Intent(intent).setComponent(ComponentName(context, ProfileCommandActivity::class.java))
        try {
            cpa.startActivity(explicit, user, activity)
        } catch (e: Exception) {
            callback(CommandResult(false, "Could not reach the work profile (${e.javaClass.simpleName}). Tap “Restart engine”, then try again."))
            return
        }
        val cmd = intent.getStringExtra(WorkProfile.EXTRA_CMD)
        val pkg = intent.getStringExtra(WorkProfile.EXTRA_PACKAGE)
        scope.launch {
            when (cmd) {
                WorkProfile.CMD_PING -> { prefs.engineReady = true; callback(CommandResult(true, "ready")) }
                WorkProfile.CMD_INSTALL -> {
                    var ok = false
                    repeat(30) { if (!ok) { delay(500); ok = pkg != null && WorkProfile.isInstalledInProfile(context, pkg) } }
                    callback(if (ok) CommandResult(true, "installed")
                    else CommandResult(false, "The app did not appear in the work profile. Some apps cannot be cloned."))
                }
                WorkProfile.CMD_UNINSTALL -> {
                    var gone = false
                    repeat(30) { if (!gone) { delay(500); gone = pkg == null || !WorkProfile.isInstalledInProfile(context, pkg) } }
                    callback(if (gone) CommandResult(true, "removed") else CommandResult(false, "The clone could not be removed. Try again."))
                }
                else -> callback(CommandResult(false, "Unknown command"))
            }
        }
    }

    // Opens Android's "Connected work & personal apps" screen; continues with the waiting command afterwards.
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val waiting = pending[0]
        pending[0] = null
        if (waiting != null) {
            if (WorkProfile.connectedAppsAllowed(context)) viaConnectedApps(waiting)
            else callback(CommandResult(false, "Multi Apps needs “Connect these apps” turned on to reach the work profile."))
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val msg = r.data?.getStringExtra(WorkProfile.EXTRA_MESSAGE)
        val ok = r.resultCode == Activity.RESULT_OK
        if (ok && msg == "ready") prefs.engineReady = true
        callback(CommandResult(ok, msg ?: if (ok) "Done" else "The work profile did not answer. Check that work apps are turned on."))
    }

    return remember(launcher, consent) {
        { intent: Intent ->
            when {
                WorkProfile.isPaused(context) ->
                    callback(CommandResult(false, "Work apps are paused. Turn on “Work apps” in quick settings, then try again."))
                WorkProfile.intentRouteAvailable(context) -> try { launcher.launch(intent) } catch (e: ActivityNotFoundException) { viaConnectedApps(intent) }
                WorkProfile.connectedAppsAllowed(context) -> viaConnectedApps(intent)
                WorkProfile.connectedAppsCanRequest(context) && Build.VERSION.SDK_INT >= 30 -> {
                    pending[0] = intent
                    try { consent.launch(context.getSystemService(CrossProfileApps::class.java).createRequestInteractAcrossProfilesIntent()) }
                    catch (e: Exception) { pending[0] = null; callback(CommandResult(false, "Could not open the permission screen: ${e.message}")) }
                }
                else -> callback(CommandResult(false, NO_ROUTE))
            }
        }
    }
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

/** One line per check, shown under "Engine details" to help support find problems. */
fun engineDetails(context: Context): String {
    val yes = "yes"; val no = "no"
    val profile = WorkProfile.profileUser(context) != null
    val route1 = runCatching { WorkProfile.intentRouteAvailable(context) }.getOrDefault(false)
    val route2 = when {
        Build.VERSION.SDK_INT < 30 -> "not supported"
        WorkProfile.connectedAppsAllowed(context) -> "allowed"
        WorkProfile.connectedAppsCanRequest(context) -> "needs your permission"
        else -> "not available yet"
    }
    return "Work profile found: ${if (profile) yes else no}\n" +
        "Work apps paused: ${if (WorkProfile.isPaused(context)) yes else no}\n" +
        "Route 1 (intent): ${if (route1) "available" else "not available"}\n" +
        "Route 2 (connected apps): $route2\n" +
        "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}"
}

/** Returns a function that starts Android's work profile setup. [onDone] gets true if Android created it. */
@Composable
fun rememberProfileSetup(onDone: (Boolean) -> Unit): () -> Unit {
    val context = LocalContext.current
    val callback by rememberUpdatedState(onDone)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        callback(r.resultCode == Activity.RESULT_OK)
    }
    return remember(launcher) {
        {
            try { launcher.launch(WorkProfile.provisioningIntent(context)) }
            catch (e: ActivityNotFoundException) { callback(false) }
        }
    }
}
