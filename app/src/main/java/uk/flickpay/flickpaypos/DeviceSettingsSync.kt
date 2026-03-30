package uk.flickpay.flickpaypos

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object DeviceSettingsSync {

    private const val DEFAULT_ACCOUNT_HOST = "app.flickpay.co.uk"
    private const val KEY_ACCOUNT_HOST = "account_host"
    private const val KEY_DATABASE = "database"
    private const val KEY_POS_ID = "pos_id"
    private const val KEY_DEVICE_TOKEN = "device_token"
    private const val KEY_SCREEN1_MODE = "screen1_mode"
    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val KEY_DEVICE_AUTH_ACCESS_TOKEN = "device_auth_access_token"

    private val ENDPOINTS = listOf(
        "/odoo/flickpay_pos/device/settings/sync",
        "/flickpay_pos/device/settings/sync",
        "/odoo/flickpay/device/settings/sync",
        "/flickpay/device/settings/sync",
    )

    private data class SyncContext(
        val accountHost: String,
        val database: String,
        val posId: String,
        val deviceId: String,
        val deviceToken: String,
        val screen1Mode: String,
        val accessToken: String,
        val deviceAuthAccessToken: String,
    )

    private fun normalizeAccountHost(raw: String?): String {
        val text = raw?.trim().orEmpty()
        if (text.isBlank()) return DEFAULT_ACCOUNT_HOST

        val withoutScheme = text
            .replace(Regex("^https?://", RegexOption.IGNORE_CASE), "")
            .trim()
            .trimEnd('/')
        val hostOnly = withoutScheme.substringBefore('/').substringBefore('?').trim().lowercase()
        if (hostOnly.isBlank()) return DEFAULT_ACCOUNT_HOST

        val legacyMapped = if (hostOnly == "devtests.flickpay.co.uk") DEFAULT_ACCOUNT_HOST else hostOnly
        if (!legacyMapped.contains(".")) return "$legacyMapped.flickpay.co.uk"
        return legacyMapped
    }

    private fun resolveContext(context: Context): SyncContext? {
        val prefs = context.getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE)
        val accountHost = normalizeAccountHost(
            prefs.getString(KEY_ACCOUNT_HOST, null) ?: DEFAULT_ACCOUNT_HOST
        )
        val database = prefs.getString(KEY_DATABASE, null)?.trim().orEmpty()
        val posId = prefs.getString(KEY_POS_ID, null)?.trim().orEmpty()
        val deviceToken = prefs.getString(KEY_DEVICE_TOKEN, null)?.trim().orEmpty()
        val screen1Mode = prefs.getString(KEY_SCREEN1_MODE, "pos")?.trim().orEmpty()
        val accessToken = prefs.getString(KEY_ACCESS_TOKEN, null)?.trim().orEmpty()
        val deviceAuthAccessToken = prefs.getString(KEY_DEVICE_AUTH_ACCESS_TOKEN, null)?.trim().orEmpty()
        val deviceId = DeviceIdentity.getOrCreate(context)

        if (database.isBlank() || posId.isBlank() || deviceId.isBlank()) {
            return null
        }
        return SyncContext(
            accountHost = accountHost,
            database = database,
            posId = posId,
            deviceId = deviceId,
            deviceToken = deviceToken,
            screen1Mode = screen1Mode,
            accessToken = accessToken,
            deviceAuthAccessToken = deviceAuthAccessToken,
        )
    }

    private fun postPayload(
        accountHost: String,
        endpoint: String,
        database: String,
        payload: JSONObject,
        deviceAuthAccessToken: String,
    ): JSONObject? {
        val baseUrl = "https://$accountHost"
        val encodedDb = java.net.URLEncoder.encode(database, Charsets.UTF_8.name())
        val url = URL("$baseUrl$endpoint?db=$encodedDb")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 3000
            readTimeout = 5000
            instanceFollowRedirects = false
            doOutput = true
            useCaches = false
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-Odoo-Database", database)
            if (deviceAuthAccessToken.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer $deviceAuthAccessToken")
            }
        }

        return try {
            connection.outputStream.use { out ->
                out.write(payload.toString().toByteArray(Charsets.UTF_8))
                out.flush()
            }
            val status = runCatching { connection.responseCode }.getOrDefault(0)
            val raw = runCatching {
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                stream?.bufferedReader()?.use { it.readText() } ?: ""
            }.getOrDefault("")
            if (raw.isBlank()) return null
            runCatching { JSONObject(raw) }.getOrNull()
        } catch (_: Exception) {
            null
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    fun pullAndApply(context: Context): Boolean {
        val syncContext = resolveContext(context) ?: return false
        val payload = JSONObject().apply {
            put("action", "pull")
            put("client", "android")
            put("db", syncContext.database)
            put("pos_id", syncContext.posId)
            put("device_id", syncContext.deviceId)
            put("device_token", syncContext.deviceToken)
            put("mode", syncContext.screen1Mode)
            put("access_token", syncContext.accessToken)
            put("reason", "app_boot_pull")
            put("app_version", BuildConfig.VERSION_NAME)
        }

        for (endpoint in ENDPOINTS) {
            val response = postPayload(
                accountHost = syncContext.accountHost,
                endpoint = endpoint,
                database = syncContext.database,
                payload = payload,
                deviceAuthAccessToken = syncContext.deviceAuthAccessToken,
            ) ?: continue
            if (!response.optBoolean("success", false)) {
                continue
            }
            val settingsPayload = response.optJSONObject("settings") ?: JSONObject()
            val appSettings = AppSettings(context)
            appSettings.applySyncPayload(settingsPayload)
            return true
        }
        return false
    }

    fun pushCurrentSettings(context: Context, reason: String = "settings_update"): Boolean {
        val syncContext = resolveContext(context) ?: return false
        val settingsPayload = AppSettings(context).exportSyncPayload()
        val payload = JSONObject().apply {
            put("action", "push")
            put("client", "android")
            put("db", syncContext.database)
            put("pos_id", syncContext.posId)
            put("device_id", syncContext.deviceId)
            put("device_token", syncContext.deviceToken)
            put("mode", syncContext.screen1Mode)
            put("access_token", syncContext.accessToken)
            put("reason", reason)
            put("app_version", BuildConfig.VERSION_NAME)
            put("settings_schema", settingsPayload.optInt("schema", 1))
            put("settings", settingsPayload)
        }

        for (endpoint in ENDPOINTS) {
            val response = postPayload(
                accountHost = syncContext.accountHost,
                endpoint = endpoint,
                database = syncContext.database,
                payload = payload,
                deviceAuthAccessToken = syncContext.deviceAuthAccessToken,
            ) ?: continue
            if (response.optBoolean("success", false)) {
                return true
            }
        }
        return false
    }
}
