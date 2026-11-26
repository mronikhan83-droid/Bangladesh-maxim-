package io.github.aedev.flow.data.offline

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.github.aedev.flow.data.local.PlayerPreferences
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Tops Offline videos up to the chosen number and leaves the transfer to the normal download queue.
 *
 * It runs two ways. [refillSoon] runs it as soon as there is a connection, right after a watched
 * set was deleted, so the next set downloads by itself. [schedule] keeps a daily run that waits for
 * an unmetered network and a charger, so it never spends mobile data or battery in the background.
 */
class OfflineRefillWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    /** What the worker needs from the app graph; it is created by WorkManager, not by Hilt. */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun offlineShorts(): OfflineShortsRepository
    }

    override suspend fun doWork(): Result {
        val preferences = PlayerPreferences(applicationContext)
        if (!preferences.offlineAutoDownload.first()) return Result.success()
        val offline = EntryPointAccessors.fromApplication(applicationContext, Dependencies::class.java).offlineShorts()
        offline.fill(preferences.offlineVideosTarget.first())
        return Result.success()
    }

    companion object {
        const val WORK_NAME = "offline_shorts_refill"
        private const val REFILL_NOW = "offline_shorts_refill_now"

        fun schedule(context: Context) {
            val request =
                PeriodicWorkRequestBuilder<OfflineRefillWorker>(1, TimeUnit.DAYS)
                    .setConstraints(
                        Constraints
                            .Builder()
                            .setRequiredNetworkType(NetworkType.UNMETERED)
                            .setRequiresCharging(true)
                            .setRequiresBatteryNotLow(true)
                            .build(),
                    ).build()
            WorkManager
                .getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** Fills the next set as soon as the network allows: unmetered when Wi-Fi only is on. */
        fun refillSoon(
            context: Context,
            wifiOnly: Boolean,
        ) {
            val request =
                OneTimeWorkRequestBuilder<OfflineRefillWorker>()
                    .setConstraints(
                        Constraints
                            .Builder()
                            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                            .build(),
                    ).build()
            WorkManager
                .getInstance(context)
                .enqueueUniqueWork(REFILL_NOW, ExistingWorkPolicy.KEEP, request)
            schedule(context)
        }

        fun cancel(context: Context) {
            val work = WorkManager.getInstance(context)
            work.cancelUniqueWork(WORK_NAME)
            work.cancelUniqueWork(REFILL_NOW)
        }
    }
}
