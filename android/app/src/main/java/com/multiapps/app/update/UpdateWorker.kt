package com.multiapps.app.update

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.multiapps.app.MainActivity
import com.multiapps.app.MultiAppsApp
import com.multiapps.app.R
import java.util.concurrent.TimeUnit

/** Periodic background check. Shows a notification when an update is available. */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as MultiAppsApp).container
        c.remoteConfig.refresh()
        val check = c.updates.check() ?: return Result.retry()
        val r = check.release
        if (check.updateAvailable && r != null && (check.mandatory || r.versionCode != c.prefs.dismissedUpdateVersion)) {
            notify(applicationContext, r.versionName, check.mandatory)
        }
        return Result.success()
    }

    companion object {
        const val CHANNEL_ID = "updates"
        private const val WORK_NAME = "update-check"

        fun createChannel(context: Context) {
            val ch = NotificationChannel(CHANNEL_ID, "App updates", NotificationManager.IMPORTANCE_DEFAULT)
            ch.description = "Tells you when a new version of Multi Apps is available"
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }

        fun schedule(context: Context, hours: Long) {
            val req = PeriodicWorkRequestBuilder<UpdateWorker>(hours.coerceAtLeast(1), TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        @SuppressLint("MissingPermission") // checked at the start of the function
        private fun notify(context: Context, versionName: String, mandatory: Boolean) {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(if (mandatory) "Required update: Multi Apps $versionName" else "Multi Apps $versionName is available")
                .setContentText("Tap to update.")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(1001, n)
        }
    }
}
