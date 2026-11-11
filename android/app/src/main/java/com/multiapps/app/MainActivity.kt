package com.multiapps.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.multiapps.app.ui.screens.AddCloneScreen
import com.multiapps.app.ui.screens.CloneSettingsScreen
import com.multiapps.app.ui.screens.HomeScreen
import com.multiapps.app.ui.screens.ProxyEditScreen
import com.multiapps.app.ui.screens.ProxyListScreen
import com.multiapps.app.ui.screens.SettingsScreen
import com.multiapps.app.ui.screens.SupportScreen
import com.multiapps.app.ui.screens.TicketScreen
import com.multiapps.app.ui.screens.UpdateGate
import com.multiapps.app.ui.theme.MultiAppsTheme
import com.multiapps.app.update.UpdateWorker
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val c = (application as MultiAppsApp).container

        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)

        lifecycleScope.launch {
            c.updates.reportPendingInstall()
            c.remoteConfig.refresh()
            UpdateWorker.schedule(this@MainActivity, c.remoteConfig.config.value.checkIntervalHours)
            c.updates.check()
        }

        setContent {
            MultiAppsTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val nav = rememberNavController()
                    UpdateGate {
                        NavHost(navController = nav, startDestination = "home") {
                            composable("home") {
                                HomeScreen(
                                    onAddClone = { nav.navigate("add") },
                                    onOpenClone = { id -> nav.navigate("clone/$id") },
                                    onProxies = { nav.navigate("proxies") },
                                    onSupport = { nav.navigate("support") },
                                    onSettings = { nav.navigate("settings") },
                                )
                            }
                            composable("add") {
                                AddCloneScreen(onBack = { nav.popBackStack() }, onCreated = { nav.popBackStack() })
                            }
                            composable("clone/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { e ->
                                CloneSettingsScreen(
                                    cloneId = e.arguments?.getLong("id") ?: 0L,
                                    onBack = { nav.popBackStack() },
                                    onManageProxies = { nav.navigate("proxies") },
                                )
                            }
                            composable("proxies") {
                                ProxyListScreen(onBack = { nav.popBackStack() }, onEdit = { id -> nav.navigate("proxy/$id") })
                            }
                            composable("proxy/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { e ->
                                ProxyEditScreen(proxyId = e.arguments?.getLong("id") ?: 0L, onDone = { nav.popBackStack() })
                            }
                            composable("support") {
                                SupportScreen(onBack = { nav.popBackStack() }, onOpenTicket = { id -> nav.navigate("ticket/$id") })
                            }
                            composable("ticket/{id}", arguments = listOf(navArgument("id") { type = NavType.IntType })) { e ->
                                TicketScreen(ticketId = e.arguments?.getInt("id") ?: 0, onBack = { nav.popBackStack() })
                            }
                            composable("settings") {
                                SettingsScreen(onBack = { nav.popBackStack() })
                            }
                        }
                    }
                }
            }
        }
    }
}
