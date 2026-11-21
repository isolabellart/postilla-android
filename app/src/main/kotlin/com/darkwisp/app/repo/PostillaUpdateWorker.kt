package com.darkwisp.app.repo

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.darkwisp.app.BuildConfig
import java.util.concurrent.TimeUnit

class PostillaUpdateWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        if (BuildConfig.APPLICATION_ID != "com.isolabellart.darkwispenhanced" ||
            BuildConfig.BUILD_TYPE != "release"
        ) {
            return Result.success()
        }

        return try {
            if (PostillaUpdateManager.checkIfDue(applicationContext, forceCheck = true)) {
                Result.success()
            } else {
                Result.retry()
            }
        } catch (_: Exception) {
            Result.retry()
        }
    }
}

object PostillaUpdateScheduler {
    private const val UNIQUE_WORK_NAME = "postilla-daily-release-check"

    fun schedule(context: Context) {
        if (BuildConfig.APPLICATION_ID != "com.isolabellart.darkwispenhanced" ||
            BuildConfig.BUILD_TYPE != "release"
        ) {
            return
        }

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<PostillaUpdateWorker>(
            24, TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }
}
