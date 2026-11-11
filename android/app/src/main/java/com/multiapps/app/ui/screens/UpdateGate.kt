package com.multiapps.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.multiapps.app.net.UpdateCheck
import com.multiapps.app.ui.appContainer
import com.multiapps.app.ui.formatSize
import com.multiapps.app.update.UpdateState
import kotlinx.coroutines.launch

/**
 * Wraps the whole app. Shows update dialogs; a mandatory update blocks the app until installed.
 */
@Composable
fun UpdateGate(content: @Composable () -> Unit) {
    val c = appContainer()
    val state by c.updates.state.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // Re-check the "install unknown apps" permission when the user comes back from Settings.
    var resumeTick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumeTick++ }
    val canInstall = remember(resumeTick, state) { c.updates.canInstall() }

    Box(Modifier.fillMaxSize()) { content() }

    val check: UpdateCheck? = when (val s = state) {
        is UpdateState.Available -> s.check
        is UpdateState.Downloading -> s.check
        is UpdateState.ReadyToInstall -> s.check
        is UpdateState.Installing -> s.check
        is UpdateState.Failed -> s.check
        else -> null
    }
    val release = check?.release ?: return
    val mandatory = check.mandatory
    if (state is UpdateState.Available && !mandatory && !c.updates.manualCheck && release.versionCode == c.prefs.dismissedUpdateVersion) return

    AlertDialog(
        onDismissRequest = { if (!mandatory && state is UpdateState.Available) c.updates.dismiss(check) },
        properties = DialogProperties(dismissOnBackPress = !mandatory, dismissOnClickOutside = false),
        title = { Text(if (mandatory) "Update required" else "Update available") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Version ${release.versionName}  ·  ${formatSize(release.fileSize)}", style = MaterialTheme.typography.bodyMedium)
                if (mandatory) Text("This version is required to keep using Multi Apps.", color = MaterialTheme.colorScheme.error)
                if (release.changelog.isNotBlank()) {
                    Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                        Text(release.changelog, style = MaterialTheme.typography.bodySmall)
                    }
                }
                when (val s = state) {
                    is UpdateState.Downloading -> {
                        LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                        Text("Downloading… ${(s.progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                    }
                    is UpdateState.ReadyToInstall -> {
                        if (!canInstall) {
                            Text("Allow Multi Apps to install updates: tap “Allow”, turn on “Allow from this source”, then come back.",
                                style = MaterialTheme.typography.bodySmall)
                        } else {
                            Text("Download complete and verified.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    is UpdateState.Installing -> Text("Waiting for Android to install…", style = MaterialTheme.typography.bodySmall)
                    is UpdateState.Failed -> Text(s.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    else -> {}
                }
            }
        },
        confirmButton = {
            when (val s = state) {
                is UpdateState.Available, is UpdateState.Failed ->
                    Button(onClick = { scope.launch { c.updates.download(check) } }) { Text(if (s is UpdateState.Failed) "Try again" else "Update now") }
                is UpdateState.ReadyToInstall ->
                    if (!canInstall) {
                        Button(onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }) { Text("Allow") }
                    } else {
                        Button(onClick = { scope.launch { c.updates.install(s.check, s.file) } }) { Text("Install") }
                    }
                else -> {}
            }
        },
        dismissButton = {
            if (!mandatory && (state is UpdateState.Available || state is UpdateState.Failed)) {
                TextButton(onClick = { c.updates.dismiss(check) }) { Text("Later") }
            }
        },
    )
}
