package uk.flickpay.flickpaypos

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

object AppUpdateManager {

    data class AvailableUpdate(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val sha256: String,
        val notes: String,
    )

    sealed class CheckResult {
        data class UpdateAvailable(val update: AvailableUpdate) : CheckResult()
        data object UpToDate : CheckResult()
        data class Failure(val message: String) : CheckResult()
    }

    private sealed class DownloadResult {
        data class Success(val apkFile: File) : DownloadResult()
        data class Failure(val message: String) : DownloadResult()
    }

    private sealed class InstallLaunchResult {
        data object Started : InstallLaunchResult()
        data class NeedsUnknownSourcesPermission(val apkFile: File) : InstallLaunchResult()
        data class Failure(val message: String) : InstallLaunchResult()
    }

    private const val UPDATE_MANIFEST_URL =
        "https://github.com/rysen-tech/flickpaypos-android/releases/latest/download/latest.json"
    private const val PREFS_NAME = AppSettings.PREFS_NAME
    private const val KEY_PENDING_APK_PATH = "update_pending_apk_path"

    fun checkForUpdateAsync(callback: (CheckResult) -> Unit) {
        Thread {
            val result = checkForUpdate()
            Handler(Looper.getMainLooper()).post { callback(result) }
        }.start()
    }

    fun showUpdatePrompt(activity: AppCompatActivity, update: AvailableUpdate) {
        if (activity.isFinishing || activity.isDestroyed) return

        val view = activity.layoutInflater.inflate(R.layout.dialog_app_update, null, false)
        val title = view.findViewById<TextView>(R.id.updateDialogTitle)
        val message = view.findViewById<TextView>(R.id.updateDialogMessage)
        val details = view.findViewById<TextView>(R.id.updateDialogDetails)
        val laterButton = view.findViewById<MaterialButton>(R.id.updateLaterButton)
        val nowButton = view.findViewById<MaterialButton>(R.id.updateNowButton)

        title.text = activity.getString(R.string.update_dialog_title)
        message.text = activity.getString(
            R.string.update_dialog_message,
            update.versionName.ifBlank { update.versionCode.toString() }
        )
        details.text = if (update.notes.isBlank()) {
            activity.getString(R.string.update_dialog_details_default)
        } else {
            update.notes
        }

        val dialog = AlertDialog.Builder(activity)
            .setView(view)
            .setCancelable(false)
            .create()

        laterButton.setOnClickListener { dialog.dismiss() }
        nowButton.setOnClickListener {
            laterButton.isEnabled = false
            nowButton.isEnabled = false
            dialog.dismiss()
            startUpdateNow(activity, update)
        }

        dialog.show()
    }

    fun resumePendingInstallIfPossible(activity: Activity) {
        val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val path = prefs.getString(KEY_PENDING_APK_PATH, null)?.trim().orEmpty()
        if (path.isBlank()) return
        if (!canInstallUnknownApps(activity)) return

        val apkFile = File(path)
        if (!apkFile.exists()) {
            clearPendingInstall(activity)
            return
        }

        when (launchInstaller(activity, apkFile)) {
            is InstallLaunchResult.Started -> clearPendingInstall(activity)
            is InstallLaunchResult.NeedsUnknownSourcesPermission -> {
                // Permission was revoked again; keep pending record.
            }
            is InstallLaunchResult.Failure -> {
                clearPendingInstall(activity)
            }
        }
    }

    private fun checkForUpdate(): CheckResult {
        val manifestUrl = resolveManifestUrl()
        val response = fetchJson(manifestUrl) ?: return CheckResult.Failure("Update check failed.")
        val manifest = response.first
        val statusCode = response.second
        if (statusCode !in 200..299) {
            return CheckResult.Failure("Update check failed ($statusCode).")
        }

        val versionCode = extractInt(manifest, "versionCode", "version_code", "androidVersionCode")
            ?: return CheckResult.Failure("Invalid update payload: versionCode missing.")
        if (versionCode <= BuildConfig.VERSION_CODE) {
            return CheckResult.UpToDate
        }

        val versionName = extractString(manifest, "versionName", "version_name", "androidVersionName")
            .ifBlank { versionCode.toString() }
        val notes = extractString(manifest, "notes", "releaseNotes", "release_notes")
        val sha256 = extractString(manifest, "sha256", "sha_256")
        val rawApkUrl = extractString(manifest, "apkUrl", "apk_url", "downloadUrl", "download_url", "url", "path")
        if (rawApkUrl.isBlank()) {
            return CheckResult.Failure("Invalid update payload: apk URL missing.")
        }
        val apkUrl = resolveApkUrl(manifestUrl, rawApkUrl)
            ?: return CheckResult.Failure("Invalid update payload: apk URL invalid.")

        return CheckResult.UpdateAvailable(
            AvailableUpdate(
                versionCode = versionCode,
                versionName = versionName,
                apkUrl = apkUrl,
                sha256 = sha256,
                notes = notes,
            )
        )
    }

    private fun startUpdateNow(activity: AppCompatActivity, update: AvailableUpdate) {
        if (activity.isFinishing || activity.isDestroyed) return

        val progressView = activity.layoutInflater.inflate(R.layout.dialog_app_update_progress, null, false)
        val progressDialog = AlertDialog.Builder(activity)
            .setView(progressView)
            .setCancelable(false)
            .create()
        progressDialog.show()

        Thread {
            val downloadResult = downloadUpdate(activity.applicationContext, update)
            Handler(Looper.getMainLooper()).post {
                runCatching { progressDialog.dismiss() }
                when (downloadResult) {
                    is DownloadResult.Success -> {
                        when (val launch = launchInstaller(activity, downloadResult.apkFile)) {
                            is InstallLaunchResult.Started -> {
                                clearPendingInstall(activity)
                            }
                            is InstallLaunchResult.NeedsUnknownSourcesPermission -> {
                                markPendingInstall(activity, launch.apkFile)
                                openUnknownSourcesSettings(activity)
                                Toast.makeText(
                                    activity,
                                    activity.getString(R.string.update_unknown_sources_hint),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            is InstallLaunchResult.Failure -> {
                                Toast.makeText(
                                    activity,
                                    launch.message,
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                    is DownloadResult.Failure -> {
                        Toast.makeText(
                            activity,
                            downloadResult.message,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }.start()
    }

    private fun downloadUpdate(context: Context, update: AvailableUpdate): DownloadResult {
        return try {
            val updatesDir = File(context.cacheDir, "app_updates").apply { mkdirs() }
            val apkFile = File(updatesDir, "FlickpayPOS-${update.versionCode}.apk")
            val connection = (URL(update.apkUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 30000
                requestMethod = "GET"
                instanceFollowRedirects = true
                useCaches = false
            }
            connection.connect()
            if (connection.responseCode !in 200..299) {
                val code = connection.responseCode
                connection.disconnect()
                return DownloadResult.Failure("Update download failed ($code).")
            }
            connection.inputStream.use { input ->
                FileOutputStream(apkFile).use { out ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                    }
                    out.flush()
                }
            }
            connection.disconnect()

            if (update.sha256.isNotBlank()) {
                val actual = sha256Hex(apkFile)
                if (!actual.equals(update.sha256.trim(), ignoreCase = true)) {
                    runCatching { apkFile.delete() }
                    return DownloadResult.Failure("Update verification failed (checksum mismatch).")
                }
            }

            DownloadResult.Success(apkFile)
        } catch (_: Exception) {
            DownloadResult.Failure("Update download failed. Please try again.")
        }
    }

    private fun launchInstaller(activity: Activity, apkFile: File): InstallLaunchResult {
        if (!apkFile.exists()) {
            return InstallLaunchResult.Failure("Update file is missing.")
        }
        if (!canInstallUnknownApps(activity)) {
            return InstallLaunchResult.NeedsUnknownSourcesPermission(apkFile)
        }
        return try {
            val uri = FileProvider.getUriForFile(
                activity,
                "${BuildConfig.APPLICATION_ID}.fileprovider",
                apkFile
            )
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(installIntent)
            InstallLaunchResult.Started
        } catch (_: Exception) {
            InstallLaunchResult.Failure("Could not open Android installer.")
        }
    }

    private fun canInstallUnknownApps(activity: Activity): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            activity.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    private fun openUnknownSourcesSettings(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${activity.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { activity.startActivity(intent) }
    }

    private fun markPendingInstall(context: Context, apkFile: File) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PENDING_APK_PATH, apkFile.absolutePath)
            .apply()
    }

    private fun clearPendingInstall(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_PENDING_APK_PATH)
            .apply()
    }

    private fun resolveManifestUrl(): String {
        return UPDATE_MANIFEST_URL
    }

    private fun fetchJson(url: String): Pair<JSONObject, Int>? {
        return try {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 10000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                useCaches = false
            }
            connection.connect()
            val code = connection.responseCode
            val text = runCatching {
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                stream?.bufferedReader()?.use { it.readText() } ?: ""
            }.getOrDefault("")
            connection.disconnect()
            if (text.isBlank()) return null
            val json = JSONObject(text)
            Pair(json, code)
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveApkUrl(manifestUrl: String, rawApkUrl: String): String? {
        val candidate = rawApkUrl.trim()
        if (candidate.isBlank()) return null
        return runCatching {
            URL(URL(manifestUrl), candidate).toString()
        }.getOrNull()
    }

    private fun extractString(json: JSONObject, vararg keys: String): String {
        for (key in keys) {
            val value = json.optString(key, "").trim()
            if (value.isNotBlank()) return value
        }
        return ""
    }

    private fun extractInt(json: JSONObject, vararg keys: String): Int? {
        for (key in keys) {
            val raw = json.opt(key) ?: continue
            when (raw) {
                is Int -> if (raw > 0) return raw
                is Long -> if (raw > 0L && raw <= Int.MAX_VALUE) return raw.toInt()
                is Double -> {
                    if (raw > 0.0) {
                        val asInt = raw.toInt()
                        if (asInt > 0) return asInt
                    }
                }
                is String -> {
                    val parsed = raw.trim().toIntOrNull()
                    if (parsed != null && parsed > 0) return parsed
                }
            }
        }
        return null
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
