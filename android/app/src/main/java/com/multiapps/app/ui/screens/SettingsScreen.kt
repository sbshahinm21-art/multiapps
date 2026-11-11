package com.multiapps.app.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.multiapps.app.BuildConfig
import com.multiapps.app.backup.BackupCrypto
import com.multiapps.app.engine.EngineStatus
import com.multiapps.app.engine.WorkProfile
import com.multiapps.app.engine.engineStatus
import com.multiapps.app.engine.engineDetails
import com.multiapps.app.engine.engineStatusText
import com.multiapps.app.engine.rememberProfileCommand
import com.multiapps.app.engine.rememberProfileSetup
import androidx.compose.runtime.mutableIntStateOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.multiapps.app.ui.BackTopBar
import com.multiapps.app.ui.SectionCard
import com.multiapps.app.ui.appContainer
import com.multiapps.app.update.UpdateState
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class BackupMode { EXPORT, RESTORE }

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val c = appContainer()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val config by c.remoteConfig.config.collectAsState()
    val updateState by c.updates.state.collectAsState()

    var beta by remember { mutableStateOf(c.prefs.betaChannel) }
    var betaDialog by remember { mutableStateOf(false) }
    var betaCode by remember { mutableStateOf("") }
    var betaMsg by remember { mutableStateOf<String?>(null) }

    var backupMode by remember { mutableStateOf<BackupMode?>(null) }
    var backupUri by remember { mutableStateOf<Uri?>(null) }
    var backupPassword by remember { mutableStateOf("") }
    var backupPassword2 by remember { mutableStateOf("") }
    var backupBusy by remember { mutableStateOf(false) }
    var backupMsg by remember { mutableStateOf<String?>(null) }

    val createDoc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) { backupUri = uri; backupMode = BackupMode.EXPORT }
    }
    val openDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { backupUri = uri; backupMode = BackupMode.RESTORE }
    }

    Scaffold(topBar = { BackTopBar("Settings", onBack) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            EngineCard()

            SectionCard("Updates") {
                val text = when (val s = updateState) {
                    is UpdateState.Checking -> "Checking…"
                    is UpdateState.UpToDate -> "You have the latest version."
                    is UpdateState.Available -> "Version ${s.check.release?.versionName} is available."
                    is UpdateState.Failed -> s.message
                    else -> "Version ${BuildConfig.VERSION_NAME}"
                }
                Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = { scope.launch { c.updates.check(manual = true) } }) { Text("Check for updates") }
            }

            SectionCard("Beta program") {
                Text(
                    if (beta) "You get test versions before everyone else. They may have bugs."
                    else "Try new versions early. You need a beta code from the developer.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                betaMsg?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                if (beta) {
                    OutlinedButton(onClick = {
                        scope.launch {
                            try {
                                c.api.leaveBeta(c.prefs.deviceId); c.prefs.betaChannel = false; beta = false
                                c.remoteConfig.refresh(); betaMsg = "You left the beta program."
                            } catch (e: Exception) { betaMsg = e.message }
                        }
                    }) { Text("Leave beta") }
                } else {
                    Button(onClick = { betaCode = ""; betaDialog = true }) { Text("Join beta") }
                }
            }

            if (config.backupEnabled) SectionCard("Backup") {
                Text("Save your clones, proxies and settings to an encrypted file, or restore them.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                backupMsg?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = {
                        val stamp = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                        createDoc.launch("multi-apps-backup-$stamp.mabk")
                    }) { Text("Create backup") }
                    OutlinedButton(onClick = { openDoc.launch(arrayOf("*/*")) }) { Text("Restore") }
                }
            }

            SectionCard("About") {
                Text("Multi Apps ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                Text("Device ID (tap to copy)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    c.prefs.deviceId, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth().clickable {
                        clipboard.setText(AnnotatedString(c.prefs.deviceId))
                        Toast.makeText(context, "Device ID copied", Toast.LENGTH_SHORT).show()
                    },
                )
                Text("Share it only with Multi Apps support or to join beta testing.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    if (betaDialog) {
        var busy by remember { mutableStateOf(false) }
        var err by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { betaDialog = false },
            title = { Text("Join beta") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(betaCode, { betaCode = it.trim().take(40) }, label = { Text("Beta code") }, singleLine = true)
                    err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                Button(enabled = !busy && betaCode.length >= 6, onClick = {
                    busy = true
                    scope.launch {
                        try {
                            c.api.joinBeta(c.prefs.deviceId, betaCode)
                            c.prefs.betaChannel = true; beta = true; betaDialog = false
                            c.remoteConfig.refresh()
                            betaMsg = "Welcome to the beta. Checking for test versions…"
                            c.updates.check(manual = true)
                        } catch (e: Exception) { err = e.message } finally { busy = false }
                    }
                }) { Text("Join") }
            },
            dismissButton = { TextButton(onClick = { betaDialog = false }) { Text("Cancel") } },
        )
    }

    val mode = backupMode
    val uri = backupUri
    if (mode != null && uri != null) {
        var err by remember(mode, uri) { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { if (!backupBusy) { backupMode = null; backupPassword = ""; backupPassword2 = "" } },
            title = { Text(if (mode == BackupMode.EXPORT) "Backup password" else "Restore backup") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (mode == BackupMode.EXPORT) Text("You need this password to restore. It cannot be recovered.", style = MaterialTheme.typography.bodySmall)
                    else Text("This replaces your current clones and proxies.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(backupPassword, { backupPassword = it }, label = { Text("Password") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation())
                    if (mode == BackupMode.EXPORT) OutlinedTextField(backupPassword2, { backupPassword2 = it }, label = { Text("Repeat password") },
                        singleLine = true, visualTransformation = PasswordVisualTransformation())
                    err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (backupBusy) Text("Working…")
                }
            },
            confirmButton = {
                Button(enabled = !backupBusy, onClick = {
                    if (backupPassword.length < 8) { err = "Password must be at least 8 characters."; return@Button }
                    if (mode == BackupMode.EXPORT && backupPassword != backupPassword2) { err = "Passwords do not match."; return@Button }
                    backupBusy = true
                    scope.launch {
                        try {
                            val pw = backupPassword.toCharArray()
                            val sum = if (mode == BackupMode.EXPORT) c.backup.export(uri, pw) else c.backup.restore(uri, pw)
                            backupMsg = if (mode == BackupMode.EXPORT) "Backup saved: ${sum.clones} clones, ${sum.proxies} proxies."
                            else "Restored ${sum.clones} clones and ${sum.proxies} proxies."
                            beta = c.prefs.betaChannel
                            backupMode = null; backupPassword = ""; backupPassword2 = ""
                        } catch (e: BackupCrypto.WrongPasswordException) { err = e.message }
                        catch (e: BackupCrypto.NotABackupException) { err = e.message }
                        catch (e: Exception) { err = e.message ?: "Backup failed" }
                        finally { backupBusy = false }
                    }
                }) { Text(if (mode == BackupMode.EXPORT) "Save" else "Restore") }
            },
            dismissButton = { TextButton(enabled = !backupBusy, onClick = { backupMode = null; backupPassword = ""; backupPassword2 = "" }) { Text("Cancel") } },
        )
    }
}

/** Shows the clone engine status and lets the user set it up or test it. */
@Composable
fun EngineCard() {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val status = remember(tick) { engineStatus(context) }
    var msg by remember { mutableStateOf<String?>(null) }
    val command = rememberProfileCommand { r ->
        msg = if (r.ok) "Clone engine is ready." else r.message
        tick++
    }
    val setup = rememberProfileSetup { created ->
        msg = if (created) "Work profile created. Wait a few seconds, then tap “Check engine”." else "Setup was cancelled or not allowed on this phone."
        tick++
    }
    var showDetails by remember { mutableStateOf(false) }
    SectionCard("Clone engine") {
        Text(engineStatusText(status), color = MaterialTheme.colorScheme.onSurfaceVariant)
        msg?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        when (status) {
            EngineStatus.NOT_SET_UP -> Button(onClick = { msg = null; setup() }) { Text("Set up clone engine") }
            EngineStatus.WAITING, EngineStatus.READY -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { msg = null; command(WorkProfile.commandIntent(WorkProfile.CMD_PING)) }) { Text("Check engine") }
                OutlinedButton(onClick = {
                    msg = WorkProfile.restartEngine(context) ?: "Engine restarted in the work profile. Come back here and tap “Check engine”."
                }) { Text("Restart engine") }
            }
            else -> {}
        }
        TextButton(onClick = { showDetails = !showDetails; tick++ }) { Text(if (showDetails) "Hide engine details" else "Engine details") }
        if (showDetails) {
            Text(remember(tick) { engineDetails(context) }, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
