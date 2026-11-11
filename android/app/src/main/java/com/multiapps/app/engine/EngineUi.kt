package com.multiapps.app.engine

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.multiapps.app.MultiAppsApp

/** Current state of the clone engine as seen from the main app. */
enum class EngineStatus { UNSUPPORTED, NOT_SET_UP, WAITING, READY, BLOCKED }

fun engineStatus(context: Context): EngineStatus {
    val prefs = (context.applicationContext as MultiAppsApp).container.prefs
    if (!WorkProfile.isSupportedVersion) return EngineStatus.UNSUPPORTED
    val hasProfile = WorkProfile.profileUser(context) != null
    return when {
        hasProfile && prefs.engineReady -> EngineStatus.READY
        hasProfile -> EngineStatus.WAITING // created, or another app's work profile; a test command decides
        WorkProfile.canCreateProfile(context) -> EngineStatus.NOT_SET_UP
        else -> EngineStatus.BLOCKED
    }
}

fun engineStatusText(s: EngineStatus): String = when (s) {
    EngineStatus.UNSUPPORTED -> "Clones need Android 9 or newer."
    EngineStatus.NOT_SET_UP -> "Not set up yet. Android will create a separate, private space (a “work profile”) where your clones run with their own data."
    EngineStatus.WAITING -> "The Multi Apps work profile was created. Tap “Check engine” to finish."
    EngineStatus.READY -> "Ready. Each app can have one clone in the Multi Apps work profile."
    EngineStatus.BLOCKED -> "This phone does not allow a new work profile right now. Usually another app (for example Island, Shelter or a work app) already owns the work profile. Remove it in that app, then come back."
}

/** Result of a command sent to the copy of Multi Apps inside the work profile. */
data class CommandResult(val ok: Boolean, val message: String)

/**
 * Returns a function that sends a command to the work profile and calls [onResult] with the answer.
 * Commands go through Android's cross-profile intent forwarding and come back as an activity result.
 */
@Composable
fun rememberProfileCommand(onResult: (CommandResult) -> Unit): (Intent) -> Unit {
    val context = LocalContext.current
    val callback by rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val msg = r.data?.getStringExtra(WorkProfile.EXTRA_MESSAGE)
        val ok = r.resultCode == Activity.RESULT_OK
        if (ok && msg == "ready") (context.applicationContext as MultiAppsApp).container.prefs.engineReady = true
        callback(CommandResult(ok, msg ?: if (ok) "Done" else "The work profile did not answer. Check that work apps are turned on."))
    }
    return remember(launcher) {
        { intent: Intent ->
            try { launcher.launch(intent) }
            catch (e: ActivityNotFoundException) {
                callback(CommandResult(false, "The Multi Apps work profile was not found. Set up the clone engine in Settings."))
            }
        }
    }
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
