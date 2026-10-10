package com.litetv.app.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.litetv.app.BuildConfig
import com.litetv.app.sdui.AppUpdateConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Status of an application version check.
 */
sealed class AppUpdateStatus {
    object UpToDate : AppUpdateStatus()
    data class UpdateAvailable(
        val isMandatory: Boolean,
        val config: AppUpdateConfig,
        val currentVersionCode: Int,
        val currentVersionName: String
    ) : AppUpdateStatus()
    data class Error(val message: String) : AppUpdateStatus()
}

/**
 * Manages native application binary updates while respecting Android's security architecture:
 * 1. Distinguishes soft updates (new features) from mandatory updates (security/API compatibility)
 * 2. Never bypasses Android security, package signature verification, or user installation consent
 * 3. Supports direct authorized distribution (GitHub Releases / custom host) and Google Play store fallback
 * 4. Verifies SHA-256 cryptographic hashes for downloaded binaries
 * 5. Respects active playback state so playback is never interrupted by update prompts
 */
class AppUpdateManager(private val context: Context) {

    companion object {
        private const val TAG = "AppUpdateManager"
        private const val BUFFER_SIZE = 16384
        private const val MAX_APK_SIZE = 100 * 1024 * 1024 // 100 MB limit
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 60_000

        /** Flag indicating whether the video player is actively displaying full-screen playback */
        @Volatile
        var isPlaybackActive: Boolean = false
    }

    val currentVersionCode: Int = try {
        BuildConfig.VERSION_CODE
    } catch (_: Throwable) {
        1
    }

    val currentVersionName: String = try {
        BuildConfig.VERSION_NAME
    } catch (_: Throwable) {
        "1.0.0"
    }

    /**
     * Evaluate remote configuration against the currently installed build.
     */
    fun checkUpdate(config: AppUpdateConfig?): AppUpdateStatus {
        if (config == null) return AppUpdateStatus.UpToDate

        val isOutdated = config.latestVersionCode > currentVersionCode
        val isMandatory = config.forceUpdate || (currentVersionCode < config.minRequiredVersionCode)

        return if (isOutdated || isMandatory) {
            AppUpdateStatus.UpdateAvailable(
                isMandatory = isMandatory,
                config = config,
                currentVersionCode = currentVersionCode,
                currentVersionName = currentVersionName
            )
        } else {
            AppUpdateStatus.UpToDate
        }
    }

    /**
     * Direct the user to the authorized update distribution channel (e.g. GitHub Releases or Play Store).
     */
    fun openUpdateChannel(activity: Activity, url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch update channel URL: $url", e)
        }
    }

    /**
     * Download and trigger Android's standard package installation intent for authorized APKs.
     * Enforces HTTPS, bounds size, checks SHA-256 integrity, and requests user consent via system installer.
     */
    suspend fun downloadAndInstallApk(
        activity: Activity,
        config: AppUpdateConfig,
        onProgress: (Int) -> Unit = {}
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val downloadUrl = config.apkDownloadUrl
        if (downloadUrl.isNullOrEmpty() || !downloadUrl.startsWith("https://")) {
            return@withContext Result.failure(IllegalArgumentException("Invalid or non-HTTPS download URL"))
        }

        // Check Unknown App Sources permission on Android 8.0+ (API 26+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!activity.packageManager.canRequestPackageInstalls()) {
                withContext(Dispatchers.Main) {
                    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${activity.packageName}")
                    }
                    activity.startActivity(intent)
                }
                return@withContext Result.failure(
                    SecurityException("Install permission required. Please allow installation from Lite TV in Settings.")
                )
            }
        }

        val updatesDir = File(activity.cacheDir, "updates").apply { mkdirs() }
        val apkFile = File(updatesDir, "litetv_update_${config.latestVersionCode}.apk")

        try {
            val url = URL(downloadUrl)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("User-Agent", "LiteTV/$currentVersionName")
            }

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                return@withContext Result.failure(
                    java.io.IOException("Server returned HTTP ${connection.responseCode}")
                )
            }

            val contentLength = connection.contentLengthLong
            if (contentLength > MAX_APK_SIZE) {
                return@withContext Result.failure(
                    SecurityException("APK size exceeds maximum allowable limit")
                )
            }

            var downloadedBytes = 0L
            val inputStream: InputStream = connection.inputStream
            val outputStream = FileOutputStream(apkFile)
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(BUFFER_SIZE)

            outputStream.use { out ->
                while (true) {
                    val bytesRead = inputStream.read(buffer)
                    if (bytesRead == -1) break
                    out.write(buffer, 0, bytesRead)
                    digest.update(buffer, 0, bytesRead)
                    downloadedBytes += bytesRead

                    if (contentLength > 0) {
                        val progress = ((downloadedBytes * 100) / contentLength).toInt()
                        withContext(Dispatchers.Main) { onProgress(progress) }
                    }
                }
                out.flush()
            }

            // Cryptographic checksum verification
            if (!config.apkSha256.isNullOrBlank()) {
                val computedHash = digest.digest().joinToString("") { "%02x".format(it) }
                if (!computedHash.equals(config.apkSha256.trim(), ignoreCase = true)) {
                    apkFile.delete()
                    Log.e(TAG, "SHA-256 mismatch! Expected ${config.apkSha256}, got $computedHash")
                    return@withContext Result.failure(
                        SecurityException("Cryptographic verification failed: APK hash mismatch")
                    )
                }
                Log.i(TAG, "Cryptographic verification successful: SHA-256 match ($computedHash)")
            }

            // Launch standard Android PackageInstaller using FileProvider
            withContext(Dispatchers.Main) {
                val apkUri = FileProvider.getUriForFile(
                    activity,
                    "${activity.packageName}.fileprovider",
                    apkFile
                )

                val installIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(apkUri, "application/vnd.android.package-archive")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                }
                activity.startActivity(installIntent)
            }

            Result.success(Unit)

        } catch (e: Exception) {
            Log.e(TAG, "APK download or install failed", e)
            if (apkFile.exists()) apkFile.delete()
            Result.failure(e)
        }
    }
}
