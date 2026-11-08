package com.ivac.assistant.core.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ivac.assistant.IvacApplication
import com.ivac.assistant.core.data.repository.Resource

/**
 * Background WorkManager worker for handling recoverable OTP push retry attempts
 * during transient network dropouts, within the short OTP validity period.
 */
class OtpPushWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? IvacApplication ?: return Result.failure()
        val repository = app.repository

        val otp = inputData.getString(KEY_OTP) ?: return Result.failure()
        val timestamp = inputData.getLong(KEY_TIMESTAMP, 0L)

        // Give up if the OTP is older than 2 minutes (it's expired or discarded by user)
        if (System.currentTimeMillis() - timestamp > 2 * 60 * 1000L) {
            return Result.failure()
        }

        return when (repository.forwardOtp(otp)) {
            is Resource.Success -> Result.success()
            is Resource.Error -> {
                if (runAttemptCount < 2) {
                    Result.retry()
                } else {
                    Result.failure()
                }
            }
            Resource.Loading -> Result.retry()
        }
    }

    companion object {
        const val KEY_OTP = "work_otp"
        const val KEY_TIMESTAMP = "work_timestamp"
        const val UNIQUE_WORK_NAME = "ivac_otp_push_work"
    }
}

