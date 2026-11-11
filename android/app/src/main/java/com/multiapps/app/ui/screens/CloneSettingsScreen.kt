package com.multiapps.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.multiapps.app.data.CloneEntity
import com.multiapps.app.engine.WorkProfile
import com.multiapps.app.engine.NO_ROUTE
import com.multiapps.app.engine.rememberProfileCommand
import androidx.compose.ui.platform.LocalContext
import com.multiapps.app.ui.BackTopBar
import com.multiapps.app.ui.CloneIcon
import com.multiapps.app.ui.SectionCard
import com.multiapps.app.ui.appContainer
import com.multiapps.app.ui.theme.CloneColors
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloneSettingsScreen(cloneId: Long, onBack: () -> Unit, onManageProxies: () -> Unit) {
    val c = appContainer()
    val scope = rememberCoroutineScope()
    val stored by c.db.clones().observe(cloneId).collectAsState(initial = null)
    val proxies by c.db.proxies().observeAll().collectAsState(initial = emptyList())
    var edit by remember { mutableStateOf<CloneEntity?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(stored) { if (edit == null && stored != null) edit = stored }
    val context = LocalContext.current
    var deleteError by remember { mutableStateOf<String?>(null) }
    // Removing a work-profile clone deletes the app copy and all its data inside the profile.
    val command = rememberProfileCommand { r ->
        val e = edit
        if (r.ok && e != null) scope.launch { c.db.clones().delete(e); onBack() } else deleteError = r.message
    }

    fun save(next: CloneEntity) { edit = next; scope.launch { c.db.clones().update(next) } }

    Scaffold(topBar = { BackTopBar("Clone settings", onBack) }) { padding ->
        val e = edit ?: return@Scaffold
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                CloneIcon(e.packageName, e.appLabel, e.colorIndex, size = 64.dp)
                Column {
                    Text(e.name, style = MaterialTheme.typography.titleLarge)
                    Text(e.appLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            SectionCard("Appearance") {
                OutlinedTextField(
                    value = e.name, onValueChange = { save(e.copy(name = it.take(30))) },
                    label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Text("Color", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CloneColors.forEachIndexed { i, col ->
                        Box(
                            Modifier.size(30.dp).clip(CircleShape).background(col)
                                .border(3.dp, if (i == e.colorIndex) Color.White else Color.Transparent, CircleShape)
                                .clickable { save(e.copy(colorIndex = i)) }
                        )
                    }
                }
            }

            SectionCard("Proxy") {
                var open by remember { mutableStateOf(false) }
                val current = proxies.firstOrNull { it.id == e.proxyId }
                ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
                    OutlinedTextField(
                        value = current?.let { "${it.name} (${it.type})" } ?: "No proxy assigned",
                        onValueChange = {}, readOnly = true, label = { Text("This clone's own proxy") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        DropdownMenuItem(text = { Text("No proxy assigned") }, onClick = { open = false; save(e.copy(proxyId = null)) })
                        proxies.forEach { p ->
                            DropdownMenuItem(text = { Text("${p.name} (${p.type})") }, onClick = { open = false; save(e.copy(proxyId = p.id)) })
                        }
                    }
                }
                SwitchRow("Ask every time I open it", "Choose “No proxy” or “Its own proxy” at launch", e.askProxyOnLaunch) {
                    save(e.copy(askProxyOnLaunch = it))
                }
                TextButton(onClick = onManageProxies) { Text("Manage proxies") }
            }

            SectionCard("Notifications") {
                SwitchRow("Show notifications", null, e.notificationsEnabled) { save(e.copy(notificationsEnabled = it)) }
                SwitchRow("Silent (no sound or vibration)", null, e.notificationsMuted) { save(e.copy(notificationsMuted = it)) }
                Text("Notification controls take effect with the next update. Until then the clone shows its notifications with a work badge.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            deleteError?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                if (it == NO_ROUTE) OutlinedButton(onClick = { deleteError = WorkProfile.restartEngine(context) }) { Text("Open engine") }
            }
            OutlinedButton(
                onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("Delete clone") }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this clone?") },
            text = { Text("The clone, its settings and all its app data (accounts, messages, files) will be removed. The normal app on your phone is not affected.") },
            confirmButton = {
                Button(
                    onClick = {
                        val e = edit; confirmDelete = false
                        if (e != null) {
                            if (WorkProfile.isInstalledInProfile(context, e.packageName)) {
                                command(WorkProfile.commandIntent(WorkProfile.CMD_UNINSTALL, e.packageName))
                            } else scope.launch { c.db.clones().delete(e); onBack() }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
