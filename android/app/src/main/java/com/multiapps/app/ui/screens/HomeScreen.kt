package com.multiapps.app.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.multiapps.app.data.CloneEntity
import com.multiapps.app.data.ProxyEntity
import com.multiapps.app.engine.EngineStatus
import com.multiapps.app.engine.WorkProfile
import com.multiapps.app.engine.NO_ROUTE
import com.multiapps.app.engine.engineStatus
import com.multiapps.app.engine.rememberProfileCommand
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import com.multiapps.app.ui.CloneIcon
import com.multiapps.app.ui.appContainer
import com.multiapps.app.ui.theme.Warn

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    onAddClone: () -> Unit,
    onOpenClone: (Long) -> Unit,
    onProxies: () -> Unit,
    onSupport: () -> Unit,
    onSettings: () -> Unit,
) {
    val c = appContainer()
    val clones by c.db.clones().observeAll().collectAsState(initial = null)
    val proxies by c.db.proxies().observeAll().collectAsState(initial = emptyList())
    val config by c.remoteConfig.config.collectAsState()
    val withdrawn by c.updates.currentWithdrawn.collectAsState()
    var launching by remember { mutableStateOf<CloneEntity?>(null) }
    val context = LocalContext.current
    var resumeTick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumeTick++ }
    val engine = remember(resumeTick) { engineStatus(context) }
    // Clones added inside the engine screen (route 3) are picked up here; removed ones disappear.
    LaunchedEffect(resumeTick) {
        if (engine != EngineStatus.READY || WorkProfile.isPaused(context)) return@LaunchedEffect
        val inProfile = withContext(Dispatchers.IO) { WorkProfile.profileApps(context) } ?: return@LaunchedEffect
        val dao = c.db.clones()
        val known = dao.all()
        inProfile.forEach { (pkg, label) ->
            if (known.none { it.packageName == pkg && it.engine == CloneEntity.ENGINE_WORK_PROFILE }) {
                val old = known.firstOrNull { it.packageName == pkg && it.engine == CloneEntity.ENGINE_NONE }
                if (old != null) dao.update(old.copy(engine = CloneEntity.ENGINE_WORK_PROFILE))
                else dao.insert(CloneEntity(packageName = pkg, appLabel = label, name = label,
                    colorIndex = known.size % com.multiapps.app.ui.theme.CloneColors.size, engine = CloneEntity.ENGINE_WORK_PROFILE))
            }
        }
        known.filter { it.engine == CloneEntity.ENGINE_WORK_PROFILE && it.packageName !in inProfile.keys }.forEach { dao.delete(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Multi Apps", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onProxies) { Icon(Icons.Filled.Lock, contentDescription = "Proxies") }
                    IconButton(onClick = onSupport) { Icon(Icons.Filled.Email, contentDescription = "Support") }
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddClone,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Add clone") },
                containerColor = MaterialTheme.colorScheme.primary,
            )
        },
    ) { padding ->
        val list = clones
        LazyVerticalGrid(
            columns = GridCells.Adaptive(96.dp),
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (withdrawn) item(span = { GridItemSpan(maxLineSpan) }) {
                Banner("This version was withdrawn. A fixed version is coming — you will be asked to update.", Warn)
            }
            if (engine == EngineStatus.NOT_SET_UP || engine == EngineStatus.WAITING) item(span = { GridItemSpan(maxLineSpan) }) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text("Set up the clone engine to open your clones.", style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = onSettings) { Text("Open Settings") }
                    }
                }
            }
            if (config.announcement.isNotBlank()) item(span = { GridItemSpan(maxLineSpan) }) {
                Banner(config.announcement, MaterialTheme.colorScheme.primary)
            }
            if (list != null && list.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.fillMaxWidth().padding(top = 80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No clones yet", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    Text("Tap “Add clone” and choose an app.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(list.orEmpty(), key = { it.id }) { clone ->
                Column(
                    Modifier.combinedClickable(onClick = { launching = clone }, onLongClick = { onOpenClone(clone.id) }),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CloneIcon(clone.packageName, clone.appLabel, clone.colorIndex, size = 64.dp)
                    Spacer(Modifier.height(6.dp))
                    Text(clone.name, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    launching?.let { clone ->
        LaunchDialog(clone, proxies, onDismiss = { launching = null }, onSettings = { launching = null; onOpenClone(clone.id) })
    }
}

@Composable
private fun Banner(text: String, color: androidx.compose.ui.graphics.Color) {
    Card(colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.12f)), modifier = Modifier.fillMaxWidth()) {
        Text(text, Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Asks "No proxy" or "Its own proxy" (if the clone is set to ask), then opens the clone in the work profile.
 * Proxy routing arrives with the VPN engine; until then the clone opens without proxy and the dialog says so.
 */
@Composable
private fun LaunchDialog(clone: CloneEntity, proxies: List<ProxyEntity>, onDismiss: () -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    val c = appContainer()
    val scope = rememberCoroutineScope()
    val own = proxies.firstOrNull { it.id == clone.proxyId }
    var useProxy by remember { mutableStateOf(own != null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val status = remember { engineStatus(context) }
    val installed = remember { WorkProfile.isInstalledInProfile(context, clone.packageName) }

    val command = rememberProfileCommand { r ->
        busy = false
        if (r.ok) {
            scope.launch { c.db.clones().update(clone.copy(engine = CloneEntity.ENGINE_WORK_PROFILE)) }
            message = WorkProfile.launch(context, clone.packageName)
            if (message == null) onDismiss()
        } else message = r.message
    }

    fun open() {
        if (status != EngineStatus.READY && !installed) {
            message = "Set up the clone engine first: Settings → Clone engine."
            return
        }
        if (useProxy && own != null) {
            message = "Proxy routing for clones arrives in the next update. For now the clone opens without a proxy."
            useProxy = false
            return
        }
        if (installed) {
            if (clone.engine != CloneEntity.ENGINE_WORK_PROFILE) scope.launch { c.db.clones().update(clone.copy(engine = CloneEntity.ENGINE_WORK_PROFILE)) }
            message = WorkProfile.launch(context, clone.packageName)
            if (message == null) onDismiss()
        } else {
            busy = true
            command(WorkProfile.commandIntent(WorkProfile.CMD_INSTALL, clone.packageName))
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(clone.name) },
        text = {
            Column {
                if (clone.askProxyOnLaunch) {
                    Text("Open with:", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth().selectable(selected = !useProxy, onClick = { useProxy = false }).padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = !useProxy, onClick = { useProxy = false }); Text("No proxy")
                    }
                    Row(Modifier.fillMaxWidth().selectable(selected = useProxy, enabled = own != null, onClick = { useProxy = true }).padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = useProxy, enabled = own != null, onClick = { useProxy = true })
                        Text(if (own != null) "Its own proxy (${own.name})" else "Its own proxy — none assigned yet")
                    }
                }
                if (!installed && status == EngineStatus.READY) {
                    Text("This clone will be installed in the work profile first.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            if (message == NO_ROUTE) Button(onClick = { message = WorkProfile.restartEngine(context); if (message == null) onDismiss() }) { Text("Open engine") }
            else Button(enabled = !busy, onClick = { open() }) { Text(if (busy) "Installing…" else "Open") }
        },
        dismissButton = { TextButton(onClick = onSettings) { Text("Clone settings") } },
    )
}
