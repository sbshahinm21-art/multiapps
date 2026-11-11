package com.multiapps.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.multiapps.app.BuildConfig
import com.multiapps.app.data.Prefs
import com.multiapps.app.net.TicketThread
import com.multiapps.app.ui.BackTopBar
import com.multiapps.app.ui.SectionCard
import com.multiapps.app.ui.appContainer
import com.multiapps.app.ui.formatDate
import kotlinx.coroutines.launch

private val FAQ = listOf(
    "How do I create a clone?" to "On the home screen tap “Add clone”, choose an app and give the clone a name. Long-press a clone to change its name, color, proxy and notifications.",
    "Why can't I open my clones yet?" to "This version sets up clones, proxies, backup and updates. The clone engine that actually runs the cloned apps arrives in the next update. Everything you set up now is kept.",
    "How do proxies work?" to "Add a SOCKS5 or HTTP proxy under Proxies (lock icon) and tap Test to check it. Then assign it to a clone in the clone's settings. When you open a clone you choose “No proxy” or “Its own proxy”.",
    "How do I back up my setup?" to "Settings → Backup. Choose a password of at least 8 characters. The backup file contains your clones, proxies (with passwords) and settings, encrypted. Without the password it cannot be restored.",
    "How do updates work?" to "Multi Apps checks for updates by itself and asks before installing. The first time, Android asks you to allow installs from Multi Apps.",
    "Where is my Device ID?" to "Settings → About → Device ID. Tap it to copy. Support may ask for it.",
)

private val CATEGORIES = listOf(
    "bug" to "Something is not working", "clone_problem" to "Problem with a clone", "proxy_problem" to "Problem with a proxy",
    "account" to "Account or backup", "feature_request" to "Feature request", "other" to "Other",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupportScreen(onBack: () -> Unit, onOpenTicket: (Int) -> Unit) {
    val c = appContainer()
    val context = LocalContext.current
    val config by c.remoteConfig.config.collectAsState()
    val scope = rememberCoroutineScope()
    var refreshKey by remember { mutableIntStateOf(0) }
    val tickets = remember(refreshKey) { c.prefs.tickets() }

    var category by remember { mutableStateOf("bug") }
    var subject by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var attachInfo by remember { mutableStateOf(true) }
    var sending by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    Scaffold(topBar = { BackTopBar("Help & Support", onBack) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SectionCard("Common questions") {
                FAQ.forEach { (q, a) -> FaqItem(q, a) }
                if (config.faqUrl.startsWith("https://")) {
                    TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(config.faqUrl))) }) { Text("More help online") }
                }
            }

            if (tickets.isNotEmpty()) SectionCard("My requests") {
                tickets.forEach { t ->
                    Row(Modifier.fillMaxWidth().clickable { onOpenTicket(t.id) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("#${t.id} ${t.subject}")
                            Text(formatDate(t.createdAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("Open", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            SectionCard("Contact support") {
                var open by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
                    OutlinedTextField(
                        value = CATEGORIES.first { it.first == category }.second, onValueChange = {}, readOnly = true,
                        label = { Text("Topic") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        CATEGORIES.forEach { (key, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { category = key; open = false }) }
                    }
                }
                OutlinedTextField(subject, { subject = it.take(150) }, label = { Text("Subject") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(message, { message = it.take(5000) }, label = { Text("Describe the problem") }, minLines = 4, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(email, { email = it.trim().take(200) }, label = { Text("Email (optional, for replies by email)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = attachInfo, onCheckedChange = { attachInfo = it })
                    Text("Include phone model and Android version", style = MaterialTheme.typography.bodySmall)
                }
                status?.let { Text(it, color = if (it.startsWith("Sent")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
                Button(
                    enabled = !sending,
                    onClick = {
                        status = when {
                            subject.trim().length < 3 -> "Please enter a subject."
                            message.trim().length < 5 -> "Please describe the problem."
                            email.isNotEmpty() && !Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").matches(email) -> "Email address is not valid."
                            else -> null
                        }
                        if (status != null) return@Button
                        sending = true
                        scope.launch {
                            try {
                                val info = if (attachInfo) "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})" else ""
                                val (id, token) = c.api.createTicket(c.prefs.deviceId, email, category, subject.trim(), message.trim(), BuildConfig.VERSION_NAME, info)
                                c.prefs.addTicket(Prefs.TicketRef(id, token, subject.trim(), System.currentTimeMillis()))
                                subject = ""; message = ""
                                status = "Sent. Request #$id — replies appear under “My requests”."
                                refreshKey++
                            } catch (e: Exception) {
                                status = e.message ?: "Could not send. Check your internet connection."
                            } finally { sending = false }
                        }
                    },
                ) { Text(if (sending) "Sending…" else "Send") }
            }
        }
    }
}

@Composable
private fun FaqItem(q: String, a: String) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clickable { open = !open }.animateContentSize().padding(vertical = 6.dp)) {
        Text(q, fontWeight = FontWeight.Medium)
        if (open) Text(a, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
fun TicketScreen(ticketId: Int, onBack: () -> Unit) {
    val c = appContainer()
    val scope = rememberCoroutineScope()
    val ref = remember(ticketId) { c.prefs.tickets().firstOrNull { it.id == ticketId } }
    var thread by remember { mutableStateOf<TicketThread?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var reply by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }

    LaunchedEffect(reload) {
        if (ref == null) { error = "This request was not created on this phone."; return@LaunchedEffect }
        error = null
        try { thread = c.api.getTicket(ref.id, ref.token) } catch (e: Exception) { error = e.message ?: "Could not load" }
    }

    Scaffold(topBar = {
        BackTopBar("Request #$ticketId", onBack) {
            IconButton(onClick = { reload++ }) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp)) }
            val t = thread
            if (t == null && error == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                return@Scaffold
            }
            if (t != null) {
                Text(t.subject, style = MaterialTheme.typography.titleMedium)
                Text("Status: ${t.status}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(Modifier.weight(1f).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(t.messages) { m ->
                        val mine = m.sender == "user"
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = if (mine) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                                ),
                                modifier = Modifier.widthIn(max = 300.dp),
                            ) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(if (mine) "You" else "Support", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(m.body)
                                    Text(formatDate(m.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
                if (t.status != "closed" && ref != null) {
                    Row(Modifier.padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(reply, { reply = it.take(5000) }, placeholder = { Text("Write a reply") }, modifier = Modifier.weight(1f))
                        TextButton(enabled = reply.isNotBlank() && !sending, onClick = {
                            sending = true
                            scope.launch {
                                try { c.api.replyTicket(ref.id, ref.token, reply.trim()); reply = ""; reload++ }
                                catch (e: Exception) { error = e.message } finally { sending = false }
                            }
                        }) { Text("Send") }
                    }
                } else if (t.status == "closed") {
                    Text("This request is closed. Open a new one if you need more help.", Modifier.padding(bottom = 12.dp),
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
