package com.multiapps.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.multiapps.app.data.KeystoreCrypto
import com.multiapps.app.data.ProxyEntity
import com.multiapps.app.proxy.ProxyTester
import com.multiapps.app.ui.BackTopBar
import com.multiapps.app.ui.SectionCard
import com.multiapps.app.ui.appContainer
import com.multiapps.app.ui.theme.Bad
import com.multiapps.app.ui.theme.Ok
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private suspend fun runTest(p: ProxyEntity, password: String): ProxyTester.Result =
    withContext(Dispatchers.IO) { ProxyTester.test(p.type, p.host, p.port, p.username, password) }

@Composable
private fun TestResultText(r: ProxyTester.Result) {
    if (r.ok) Text("Working · exit IP ${r.exitIp}${r.country?.let { " ($it)" } ?: ""} · ${r.millis} ms", color = Ok,
        style = MaterialTheme.typography.bodySmall)
    else Text("Not working: ${r.error}", color = Bad, style = MaterialTheme.typography.bodySmall)
}

@Composable
fun ProxyListScreen(onBack: () -> Unit, onEdit: (Long) -> Unit) {
    val c = appContainer()
    val scope = rememberCoroutineScope()
    val proxies by c.db.proxies().observeAll().collectAsState(initial = emptyList())
    val results = remember { mutableStateMapOf<Long, ProxyTester.Result?>() }

    Scaffold(
        topBar = { BackTopBar("Proxies", onBack) },
        floatingActionButton = {
            FloatingActionButton(onClick = { onEdit(0L) }, containerColor = MaterialTheme.colorScheme.primary) {
                Icon(Icons.Filled.Add, contentDescription = "Add proxy")
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("Proxies are assigned to clones in each clone's settings. Routing clone traffic through them arrives with the clone engine.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (proxies.isEmpty()) item {
                Text("No proxies yet. Tap + to add a SOCKS5 or HTTP proxy.", Modifier.padding(top = 40.dp))
            }
            items(proxies, key = { it.id }) { p ->
                SectionCard(modifier = Modifier.clickable { onEdit(p.id) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, style = MaterialTheme.typography.titleMedium)
                            Text("${p.type} · ${p.host}:${p.port}${if (p.username.isNotEmpty()) " · with login" else ""}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        val testing = results.containsKey(p.id) && results[p.id] == null
                        if (testing) CircularProgressIndicator(Modifier.padding(8.dp))
                        else TextButton(onClick = {
                            results[p.id] = null
                            scope.launch { results[p.id] = runTest(p, KeystoreCrypto.decrypt(p.passwordEnc)) }
                        }) { Text("Test") }
                    }
                    results[p.id]?.let { TestResultText(it) }
                }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProxyEditScreen(proxyId: Long, onDone: () -> Unit) {
    val c = appContainer()
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf(proxyId == 0L) }
    var existing by remember { mutableStateOf<ProxyEntity?>(null) }
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("SOCKS5") }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ProxyTester.Result?>(null) }

    LaunchedEffect(proxyId) {
        if (proxyId != 0L) {
            c.db.proxies().get(proxyId)?.let { p ->
                existing = p; name = p.name; type = p.type; host = p.host; port = p.port.toString()
                username = p.username; password = withContext(Dispatchers.IO) { KeystoreCrypto.decrypt(p.passwordEnc) }
            }
            loaded = true
        }
    }

    fun build(): ProxyEntity? {
        val h = host.trim()
        val pt = port.trim().toIntOrNull()
        error = when {
            name.isBlank() -> "Enter a name"
            h.isEmpty() || h.contains(' ') -> "Enter the proxy host (IP address or domain)"
            pt == null || pt !in 1..65535 -> "Port must be a number from 1 to 65535"
            username.length > 255 || password.length > 255 -> "Username or password is too long"
            else -> null
        }
        if (error != null || pt == null) return null
        return ProxyEntity(id = existing?.id ?: 0L, name = name.trim(), type = type, host = h, port = pt,
            username = username.trim(), passwordEnc = "", createdAt = existing?.createdAt ?: System.currentTimeMillis())
    }

    Scaffold(topBar = { BackTopBar(if (proxyId == 0L) "Add proxy" else "Edit proxy", onDone) }) { padding ->
        if (!loaded) return@Scaffold
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard {
                OutlinedTextField(name, { name = it.take(40) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("SOCKS5", "HTTP").forEachIndexed { i, t ->
                        SegmentedButton(selected = type == t, onClick = { type = t }, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(t) }
                    }
                }
                OutlinedTextField(host, { host = it.take(253) }, label = { Text("Host") }, placeholder = { Text("e.g. 192.168.1.10") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("Port") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(username, { username = it }, label = { Text("Username (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(password, { password = it }, label = { Text("Password (optional)") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Text("The password is stored encrypted on this phone.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            result?.let { TestResultText(it) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(enabled = !testing, onClick = {
                    val p = build() ?: return@OutlinedButton
                    testing = true; result = null
                    scope.launch { result = runTest(p, password); testing = false }
                }) { Text(if (testing) "Testing…" else "Test connection") }
                Button(onClick = {
                    val p = build() ?: return@Button
                    scope.launch {
                        val enc = withContext(Dispatchers.IO) { KeystoreCrypto.encrypt(password) }
                        val toSave = p.copy(passwordEnc = enc)
                        if (toSave.id == 0L) c.db.proxies().insert(toSave) else c.db.proxies().update(toSave)
                        onDone()
                    }
                }) { Text("Save") }
            }
            existing?.let { ex ->
                TextButton(
                    onClick = { scope.launch { c.db.clones().clearProxy(ex.id); c.db.proxies().delete(ex); onDone() } },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete proxy") }
            }
        }
    }
}
