package com.miptv

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
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first

/**
 * Reloads the guide of every source that has one and last loaded it [maxAgeMs] ago or more (or never), then purges
 * finished programmes. Returns the number of guides reloaded; a failure stays on its source (`epgError`).
 */
suspend fun ChannelRepository.refreshStaleGuides(nowMs: Long, maxAgeMs: Long): Int {
    var reloaded = 0
    for (source in sources.first()) {
        if (!source.hasGuide) continue
        val at = source.epgRefreshedAt
        if (at != null && nowMs - at < maxAgeMs) continue
        if (refreshEpg(source.id) is ImportResult.Success) reloaded++
    }
    purgeProgrammes()
    return reloaded
}

/**
 * Keeps the guides filled: once a day, and once at launch for guides older than a day, on an unmetered network. The
 * work reads the sources from the database, so no address or identifier ever goes into its data.
 */
class GuideRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repository = (applicationContext as MiptvApplication).repository
        repository.refreshStaleGuides(System.currentTimeMillis(), inputData.getLong(MAX_AGE, EpgWindow.REFRESH_AFTER_MS))
        return Result.success()
    }

    companion object {
        private const val MAX_AGE = "maxAgeMs"
        const val DAILY = "guide-daily"
        const val AT_LAUNCH = "guide-launch"

        fun schedule(context: Context) {
            val daily = Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresBatteryNotLow(true).build()
            val atLaunch = Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()
            val manager = WorkManager.getInstance(context)
            manager.enqueueUniquePeriodicWork(
                DAILY, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<GuideRefreshWorker>(1, TimeUnit.DAYS).setConstraints(daily).build()
            )
            manager.enqueueUniqueWork(
                AT_LAUNCH, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<GuideRefreshWorker>().setConstraints(atLaunch)
                    .setInputData(workDataOf(MAX_AGE to EpgWindow.STALE_AT_LAUNCH_MS)).build()
            )
        }
    }
}
