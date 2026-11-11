package com.multiapps.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.multiapps.app.data.CloneEntity
import com.multiapps.app.ui.BackTopBar
import com.multiapps.app.ui.CloneIcon
import com.multiapps.app.ui.appContainer
import com.multiapps.app.ui.theme.CloneColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class InstalledApp(val packageName: String, val label: String)

@Composable
fun AddCloneScreen(onBack: () -> Unit, onCreated: () -> Unit) {
    val context = LocalContext.current
    val c = appContainer()
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<InstalledApp?>(null) }

    val apps = produceState<List<InstalledApp>?>(null) {
        value = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(intent, 0)
                .map { InstalledApp(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
                .filter { it.packageName != context.packageName }
                .distinctBy { it.packageName }
                .sortedBy { it.label.lowercase() }
        }
    }.value

    Scaffold(topBar = { BackTopBar("Choose an app", onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                placeholder = { Text("Search apps") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
            if (apps == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                val shown = apps.filter { query.isBlank() || it.label.contains(query, ignoreCase = true) }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(shown, key = { it.packageName }) { app ->
                        Row(
                            Modifier.fillMaxWidth().clickable { picked = app }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            CloneIcon(app.packageName, app.label, colorIndex = 0, size = 44.dp)
                            Column {
                                Text(app.label, style = MaterialTheme.typography.bodyLarge)
                                Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }

    picked?.let { app ->
        var name by remember(app) { mutableStateOf("") }
        var saving by remember(app) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { picked = null },
            title = { Text("New ${app.label} clone") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it.take(30) }, label = { Text("Clone name") },
                    placeholder = { Text("e.g. ${app.label} Work") }, singleLine = true)
            },
            confirmButton = {
                Button(enabled = !saving, onClick = {
                    saving = true
                    scope.launch {
                        val count = c.db.clones().countFor(app.packageName)
                        val total = c.db.clones().all().size
                        c.db.clones().insert(
                            CloneEntity(
                                packageName = app.packageName, appLabel = app.label,
                                name = name.trim().ifBlank { "${app.label} ${count + 1}" },
                                colorIndex = total % CloneColors.size,
                            )
                        )
                        picked = null
                        onCreated()
                    }
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { picked = null }) { Text("Cancel") } },
        )
    }
}
