package com.multiapps.app

import android.app.Application
import android.content.Context
import com.multiapps.app.backup.BackupManager
import com.multiapps.app.data.AppDatabase
import com.multiapps.app.data.Prefs
import com.multiapps.app.net.Api
import com.multiapps.app.net.RemoteConfigRepo
import com.multiapps.app.update.UpdateManager
import com.multiapps.app.update.UpdateWorker

/** Holds the app's single instances (simple manual dependency injection). */
class AppContainer(context: Context) {
    val prefs = Prefs(context)
    val db = AppDatabase.create(context)
    val api = Api()
    val remoteConfig = RemoteConfigRepo(api, prefs)
    val updates = UpdateManager(context, api, prefs)
    val backup = BackupManager(context, db, prefs)
}

class MultiAppsApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.prefs.launchCount = container.prefs.launchCount + 1
        UpdateWorker.createChannel(this)
        UpdateWorker.schedule(this, container.remoteConfig.config.value.checkIntervalHours)
    }
}
