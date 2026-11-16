package com.ivac.assistant.core.service

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ivac.assistant.IvacApplication
import com.ivac.assistant.core.data.repository.Resource
import java.util.concurrent.TimeUnit

/**
 * Background WorkManager worker for handling recoverable OTP push retry attempts
 * during transient network dropouts, within the short OTP validity period.
 */
class OtpPushWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val attempt = runAttemptCount
        Log.i(TAG, "OtpPushWorker doWork started (Attempt $attempt)")

        val app = applicationContext as? IvacApplication
        if (app == null) {
            Log.e(TAG, "OtpPushWorker failed: ApplicationContext is not IvacApplication")
            return Result.failure()
        }
        val repository = app.repository

        val otp = inputData.getString(KEY_OTP)
        if (otp.isNullOrBlank()) {
            Log.e(TAG, "OtpPushWorker failed: Missing or blank OTP in inputData")
            return Result.failure()
        }

        val sender = inputData.getString(KEY_SENDER) ?: "IVAC_BD"
        val timestamp = inputData.getLong(KEY_TIMESTAMP, System.currentTimeMillis())

        // Give up if the OTP is older than 2 minutes (it's expired or discarded by user)
        if (System.currentTimeMillis() - timestamp > 2 * 60 * 1000L) {
            Log.w(TAG, "OtpPushWorker cancelled: OTP expired (> 2 minutes old)")
            return Result.failure()
        }

        Log.i(TAG, "OtpPushWorker executing authenticated forwardOtp [sender: $sender, digits: ${otp.length}]")

        return when (val result = repository.forwardOtp(otp, sender, timestamp)) {
            is Resource.Success -> {
                Log.i(TAG, "OtpPushWorker SUCCESS: ${result.data}")
                Result.success()
            }
            is Resource.Error -> {
                Log.e(TAG, "OtpPushWorker ERROR: ${result.message} (Code: ${result.statusCode})")
                // Retry only on recoverable errors (transient network issues or 5xx server errors)
                if (attempt < 2 && (result.statusCode == null || result.statusCode in 500..599)) {
                    Log.i(TAG, "OtpPushWorker scheduling retry...")
                    Result.retry()
                } else {
                    Log.w(TAG, "OtpPushWorker exhausted retries or encountered permanent client error.")
                    Result.failure()
                }
            }
            Resource.Loading -> Result.retry()
        }
    }

    companion object {
        private const val TAG = "OtpPushWorker"
        const val KEY_OTP = "work_otp"
        const val KEY_SENDER = "work_sender"
        const val KEY_TIMESTAMP = "work_timestamp"
        const val UNIQUE_WORK_NAME = "ivac_otp_push_work"

        fun enqueue(context: Context, otp: String, sender: String, timestamp: Long) {
            try {
                val inputData = Data.Builder()
                    .putString(KEY_OTP, otp)
                    .putString(KEY_SENDER, sender)
                    .putLong(KEY_TIMESTAMP, timestamp)
                    .build()

                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()

                val workRequest = OneTimeWorkRequestBuilder<OtpPushWorker>()
                    .setInputData(inputData)
                    .setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.SECONDS)
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .addTag(TAG)
                    .build()

                WorkManager.getInstance(context).enqueueUniqueWork(
                    "${UNIQUE_WORK_NAME}_${timestamp}",
                    ExistingWorkPolicy.REPLACE,
                    workRequest
                )
                Log.i(TAG, "WorkManager enqueued: OtpPushWorker [Work ID: ${workRequest.id}] for reliable background delivery")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to enqueue WorkManager OtpPushWorker: ${e.message}", e)
            }
        }
    }
}
