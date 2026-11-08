package com.ivac.assistant.core.data.network

import com.ivac.assistant.BuildConfig
import com.ivac.assistant.core.data.api.IvacApiService
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Factory for OkHttpClient and Retrofit API Service.
 *
 * Includes:
 * 1. Automatic exponential backoff retry for transient network dropouts.
 * 2. Strict sanitization interceptor to ensure OTP digits are NEVER logged.
 * 3. Dynamic Base URL reconfiguration support.
 */
object NetworkClient {

    private const val CONNECT_TIMEOUT_SEC = 15L
    private const val READ_TIMEOUT_SEC = 20L
    private const val WRITE_TIMEOUT_SEC = 20L
    private const val MAX_RETRIES = 3

    /**
     * Interceptor that safely retries transient network failures with exponential backoff.
     * Never retries 4xx client errors (400, 401, 403, 404) to avoid useless delays and rate limiting.
     */
    private val retryInterceptor = Interceptor { chain ->
        val request = chain.request()
        var response: Response? = null
        var exception: IOException? = null
        var tryCount = 0

        while (tryCount < MAX_RETRIES) {
            try {
                if (tryCount > 0) {
                    val backoffMs = (1000L * (1 shl (tryCount - 1))).coerceAtMost(4000L)
                    try {
                        Thread.sleep(backoffMs)
                    } catch (_: InterruptedException) {}
                }
                response?.close()
                response = chain.proceed(request)
                // Stop retrying if successful or if it's a client error (4xx) that retrying won't fix
                if (response.isSuccessful || response.code in 400..499) {
                    return@Interceptor response
                }
            } catch (e: IOException) {
                exception = e
            }
            tryCount++
        }

        response ?: throw (exception ?: IOException("Network request failed after $MAX_RETRIES retries"))
    }

    /**
     * Safe logging interceptor: Never prints request body containing OTP.
     */
    private val safeLoggingInterceptor = HttpLoggingInterceptor().apply {
        level = if (BuildConfig.DEBUG) {
            // Log basic headers and URLs only - never BODY to prevent OTP leakage in logcat
            HttpLoggingInterceptor.Level.BASIC
        } else {
            HttpLoggingInterceptor.Level.NONE
        }
    }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT_SEC, TimeUnit.SECONDS)
        .addInterceptor(retryInterceptor)
        .addInterceptor(safeLoggingInterceptor)
        .retryOnConnectionFailure(true)
        .build()

    fun getOkHttpClient(): OkHttpClient = okHttpClient

    fun formatBaseUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim().removeSuffix("/")
        val withV1 = if (trimmed.endsWith("/v1")) trimmed else "$trimmed/v1"
        return "$withV1/"
    }

    fun createApiService(baseUrl: String = BuildConfig.DEFAULT_BASE_URL): IvacApiService {
        val formattedUrl = formatBaseUrl(baseUrl)
        return Retrofit.Builder()
            .baseUrl(formattedUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(IvacApiService::class.java)
    }
}

