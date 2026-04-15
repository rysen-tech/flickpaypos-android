package uk.flickpay.flickpaypos

import android.content.Context
import android.os.Build
import org.json.JSONArray
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
    private val DIAGNOSTIC_ENDPOINTS = listOf(
        "/odoo/flickpay_pos/device/diagnostics/upload",
        "/flickpay_pos/device/diagnostics/upload",
        "/odoo/flickpay/device/diagnostics/upload",
        "/flickpay/device/diagnostics/upload",
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

    data class DiagnosticsResult(
        val success: Boolean,
        val reference: String? = null,
        val error: String? = null,
    )

    private fun resolveContext(context: Context): SyncContext? {
        val prefs = context.getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE)
        val accountHost = AccountHostNormalizer.normalize(
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

    fun sendDiagnostics(
        context: Context,
        logs: String,
        reason: String = "manual_send",
    ): DiagnosticsResult {
        val syncContext = resolveContext(context)
            ?: return DiagnosticsResult(success = false, error = "Device context is not ready yet.")

        val sanitizedLogs = sanitizeDiagnosticsLogs(logs)
        val payload = JSONObject().apply {
            put("action", "upload")
            put("client", "android")
            put("db", syncContext.database)
            put("pos_id", syncContext.posId)
            put("device_id", syncContext.deviceId)
            put("device_token", syncContext.deviceToken)
            put("mode", syncContext.screen1Mode)
            put("access_token", syncContext.accessToken)
            put("reason", reason)
            put("app_version", BuildConfig.VERSION_NAME)
            put("logs", sanitizedLogs)
            put("metadata", buildDiagnosticsMetadata(context))
        }

        var lastError = "Diagnostics upload failed."
        for (endpoint in DIAGNOSTIC_ENDPOINTS) {
            val response = postPayload(
                accountHost = syncContext.accountHost,
                endpoint = endpoint,
                database = syncContext.database,
                payload = payload,
                deviceAuthAccessToken = syncContext.deviceAuthAccessToken,
            ) ?: continue
            if (response.optBoolean("success", false)) {
                val reference = response.optString("diagnostic_ref", "").trim().ifBlank {
                    response.optString("reference", "").trim().ifBlank { null }
                }
                return DiagnosticsResult(success = true, reference = reference)
            }
            val error = response.optString("error", "").trim()
            if (error.isNotBlank()) {
                lastError = error
            }
        }

        return DiagnosticsResult(success = false, error = lastError)
    }

    private fun buildDiagnosticsMetadata(context: Context): JSONObject {
        val appSettings = AppSettings(context)
        val activeRoute = appSettings.getPrintSettings(AppSettings.DEFAULT_ROUTE_KEY)
        val routes = JSONArray()
        for (route in appSettings.getPrinterRoutes()) {
            val routePrint = appSettings.getPrintSettings(route.key)
            routes.put(
                JSONObject().apply {
                    put("key", route.key)
                    put("label", route.label)
                    put("local_port", route.localPort)
                    put("built_in", route.builtIn)
                    put("mode", routePrint.mode.storageValue)
                    put("paper_size", routePrint.paperSize.storageValue)
                    put("reverse_print", routePrint.reversePrint)
                    put("network_endpoint", routePrint.selectedNetworkEndpoint)
                }
            )
        }

        return JSONObject().apply {
            put("android_sdk_int", Build.VERSION.SDK_INT)
            put("android_release", Build.VERSION.RELEASE ?: "")
            put("device_model", Build.MODEL ?: "")
            put("device_manufacturer", Build.MANUFACTURER ?: "")
            put("rotation_locked", appSettings.isRotationLocked())
            put("kiosk_mode_enabled", appSettings.isHomeLauncherEnabled())
            put("start_on_boot", appSettings.isStartOnBootEnabled())
            put("active_route", AppSettings.DEFAULT_ROUTE_KEY)
            put("active_printer_mode", activeRoute.mode.storageValue)
            put("active_paper_size", activeRoute.paperSize.storageValue)
            put("active_reverse_print", activeRoute.reversePrint)
            put("routes", routes)
        }
    }

    private fun sanitizeDiagnosticsLogs(raw: String): String {
        val maxChars = 240_000
        var text = raw.trim()
        if (text.isBlank()) {
            text = "(no local runtime logs captured)"
        }

        val redactions = listOf(
            Regex("(?i)(authorization\\s*[:=]\\s*bearer\\s+)[^\\s\\\"]+"),
            Regex("(?i)(device_auth_access_token\\s*[:=]\\s*)[^\\s\\\",]+"),
            Regex("(?i)(access_token\\s*[:=]\\s*)[^\\s\\\",]+"),
            Regex("(?i)(session_id\\s*[:=]\\s*)[^\\s\\\",;]+"),
        )
        for (pattern in redactions) {
            text = pattern.replace(text) { matchResult ->
                "${matchResult.groupValues.getOrNull(1).orEmpty()}***"
            }
        }

        if (text.length > maxChars) {
            text = "...(truncated to last $maxChars chars)...\n" + text.takeLast(maxChars)
        }
        return text
    }
}
