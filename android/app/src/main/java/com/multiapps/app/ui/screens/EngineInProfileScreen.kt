package com.multiapps.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.multiapps.app.engine.ProfileCloner
import com.multiapps.app.engine.ProfileSetup
import com.multiapps.app.ui.CloneIcon
import com.multiapps.app.ui.SectionCard

/**
 * Shown by the Multi Apps copy inside the work profile ("Multi Apps" in the Work tab).
 * Lets the user add and remove clones directly here (route 3), and shows the setup report.
 */
@Composable
fun EngineInProfileScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { ProfileSetup.finish(context); tick++ }
    val clones = remember(tick) { ProfileCloner.clones(context) }
    val candidates = remember(tick) { ProfileCloner.cloneCandidates(context) }
    var message by remember { mutableStateOf<String?>(null) }
    var showReport by remember { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(Modifier.padding(top = 48.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Clone engine (work profile)", style = MaterialTheme.typography.headlineSmall)
                Text("Add or remove clones here. Your clones also appear in the normal Multi Apps app and in the Work tab.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            }
        }
        item {
            SectionCard("Clones (${clones.size})") {
                if (clones.isEmpty()) Text("No clones yet. Choose an app below.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                clones.forEach { app ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CloneIcon(app.packageName, app.label, 0, size = 40.dp)
                        Text(app.label, Modifier.weight(1f))
                        TextButton(onClick = { message = ProfileCloner.launch(context, app.packageName) }) { Text("Open") }
                        TextButton(onClick = {
                            message = ProfileCloner.uninstall(context, app.packageName) ?: "Removing ${app.label}…"
                            tick++
                        }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
        item { Text("Apps you can clone", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp)) }
        if (candidates.isEmpty()) item {
            Text("All your apps are already cloned, or none could be found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(candidates, key = { it.packageName }) { app ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CloneIcon(app.packageName, app.label, 0, size = 40.dp)
                Column(Modifier.weight(1f)) {
                    Text(app.label)
                    Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(onClick = {
                    message = ProfileCloner.install(context, app.packageName) ?: "${app.label} was cloned. Open it from the Work tab or from Multi Apps."
                    tick++
                }) { Text("Clone") }
            }
        }
        item {
            SectionCard {
                TextButton(onClick = { showReport = !showReport }) { Text(if (showReport) "Hide setup report" else "Setup report") }
                if (showReport) Text(ProfileSetup.lastReport.joinToString("\n"), fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item { OutlinedButton(onClick = onClose, modifier = Modifier.padding(bottom = 32.dp)) { Text("Close") } }
    }
}
