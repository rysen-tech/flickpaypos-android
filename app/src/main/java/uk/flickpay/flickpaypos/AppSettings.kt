package uk.flickpay.flickpaypos

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import org.json.JSONArray
import org.json.JSONObject

enum class PrinterMode(val storageValue: String) {
    USB("usb"),
    BLUETOOTH("bluetooth"),
    NETWORK("network");

    companion object {
        fun fromStorage(value: String?): PrinterMode {
            return entries.firstOrNull { it.storageValue == value?.trim()?.lowercase() } ?: USB
        }
    }
}

enum class PaperSize(val storageValue: String, val widthMm: Float, val label: String) {
    MM80("80", 80f, "80 mm"),
    MM58("58", 58f, "58 mm");

    companion object {
        fun fromStorage(value: String?): PaperSize {
            return entries.firstOrNull { it.storageValue == value?.trim() } ?: MM80
        }
    }
}

enum class RotationLockMode(val storageValue: String) {
    LANDSCAPE("landscape"),
    PORTRAIT("portrait");

    companion object {
        fun fromStorage(value: String?): RotationLockMode? {
            return entries.firstOrNull { it.storageValue == value?.trim()?.lowercase() }
        }
    }
}

data class PrintSettings(
    val mode: PrinterMode = PrinterMode.USB,
    val selectedUsbPrinterId: String = "",
    val selectedBluetoothAddress: String = "",
    val selectedNetworkEndpoint: String = "",
    val networkHost: String = "",
    val networkPort: Int = 9100,
    val paperSize: PaperSize = PaperSize.MM80,
    val reversePrint: Boolean = false,
) {
    fun networkEndpoint(): String {
        val host = networkHost.trim()
        if (host.isBlank()) return ""
        return "$host:$networkPort"
    }
}

data class PrintRoute(
    val key: String,
    val localPort: Int,
    val label: String,
    val builtIn: Boolean = false
)

class AppSettings(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isRotationLocked(): Boolean {
        // Treat rotation lock as opt-in only. Older installs may have inherited
        // a previously hardcoded default; ignore that until user explicitly chooses.
        if (!prefs.getBoolean(KEY_ROTATION_LOCK_USER_SET, false)) {
            return false
        }
        return prefs.getBoolean(KEY_ROTATION_LOCK, false)
    }

    fun getRotationLockMode(): RotationLockMode {
        val stored = RotationLockMode.fromStorage(
            prefs.getString(KEY_ROTATION_LOCK_MODE, null)
        )
        if (stored != null) return stored
        // Backward compatibility for installs that had lock=true without explicit mode.
        return RotationLockMode.LANDSCAPE
    }

    fun setRotationLocked(locked: Boolean, mode: RotationLockMode? = null) {
        val editor = prefs.edit()
            .putBoolean(KEY_ROTATION_LOCK, locked)
            .putBoolean(KEY_ROTATION_LOCK_USER_SET, true)
        if (locked) {
            val lockMode = mode ?: getRotationLockMode()
            editor.putString(KEY_ROTATION_LOCK_MODE, lockMode.storageValue)
        } else {
            editor.remove(KEY_ROTATION_LOCK_MODE)
        }
        editor.apply()
    }

    fun getLockedRequestedOrientation(): Int {
        return when (getRotationLockMode()) {
            RotationLockMode.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            RotationLockMode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    fun isStartOnBootEnabled(): Boolean {
        return prefs.getBoolean(KEY_START_ON_BOOT, false)
    }

    fun setStartOnBootEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_START_ON_BOOT, enabled).apply()
        setComponentEnabled(
            componentClassName = "${appContext.packageName}.BootReceiver",
            enabled = enabled
        )
    }

    fun isHomeLauncherEnabled(): Boolean {
        return prefs.getBoolean(KEY_HOME_LAUNCHER, false)
    }

    fun setHomeLauncherEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_HOME_LAUNCHER, enabled).apply()
        setComponentEnabled(
            componentClassName = "${appContext.packageName}.HomeLauncherAlias",
            enabled = enabled
        )
    }

    fun syncComponentStates() {
        setComponentEnabled(
            componentClassName = "${appContext.packageName}.BootReceiver",
            enabled = isStartOnBootEnabled()
        )
        setComponentEnabled(
            componentClassName = "${appContext.packageName}.HomeLauncherAlias",
            enabled = isHomeLauncherEnabled()
        )
    }

    fun getCustomerDisplayZoomPercent(): Int {
        val raw = prefs.all[KEY_CUSTOMER_DISPLAY_ZOOM_PERCENT]
        val value = when (raw) {
            is Int -> raw
            is Long -> raw.toInt()
            is String -> raw.toIntOrNull() ?: 85
            else -> 85
        }
        return value.coerceIn(50, 200)
    }

    fun setCustomerDisplayZoomPercent(value: Int) {
        prefs.edit()
            .putInt(KEY_CUSTOMER_DISPLAY_ZOOM_PERCENT, value.coerceIn(50, 200))
            .apply()
    }

    private fun normalizeRouteKey(routeKey: String?): String {
        val normalized = routeKey?.trim()?.lowercase().orEmpty()
        return if (normalized.isBlank()) DEFAULT_ROUTE_KEY else normalized
    }

    private fun isMainRoute(routeKey: String): Boolean {
        return normalizeRouteKey(routeKey) == DEFAULT_ROUTE_KEY
    }

    private fun scopedKey(baseKey: String, routeKey: String): String {
        return "${baseKey}_${normalizeRouteKey(routeKey)}"
    }

    private fun getStringSetting(baseKey: String, routeKey: String, legacyFallback: String = ""): String {
        val key = normalizeRouteKey(routeKey)
        val scoped = prefs.getString(scopedKey(baseKey, key), null)?.trim()
        if (!scoped.isNullOrEmpty()) return scoped
        if (isMainRoute(key)) {
            return prefs.getString(baseKey, legacyFallback)?.trim().orEmpty()
        }
        return legacyFallback
    }

    private fun getIntSetting(baseKey: String, routeKey: String, defaultValue: Int): Int {
        val key = normalizeRouteKey(routeKey)
        val routeScopedKey = scopedKey(baseKey, key)
        if (prefs.contains(routeScopedKey)) {
            return prefs.getInt(routeScopedKey, defaultValue)
        }
        if (isMainRoute(key) && prefs.contains(baseKey)) {
            return prefs.getInt(baseKey, defaultValue)
        }
        return defaultValue
    }

    private fun getBooleanSetting(baseKey: String, routeKey: String, defaultValue: Boolean): Boolean {
        val key = normalizeRouteKey(routeKey)
        val routeScopedKey = scopedKey(baseKey, key)
        if (prefs.contains(routeScopedKey)) {
            return prefs.getBoolean(routeScopedKey, defaultValue)
        }
        if (isMainRoute(key) && prefs.contains(baseKey)) {
            return prefs.getBoolean(baseKey, defaultValue)
        }
        return defaultValue
    }

    private fun parseRoutesJson(raw: String?): MutableList<PrintRoute> {
        if (raw.isNullOrBlank()) return mutableListOf()
        val out = mutableListOf<PrintRoute>()
        val seenKeys = mutableSetOf<String>()
        val seenPorts = mutableSetOf<Int>(DEFAULT_ROUTE_PORT)
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return mutableListOf()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val key = normalizeRouteKey(obj.optString("key", ""))
            val label = obj.optString("label", "").trim()
            val port = obj.optInt("port", 0)
            if (key.isBlank() || key == DEFAULT_ROUTE_KEY || seenKeys.contains(key)) continue
            if (label.isBlank()) continue
            if (port !in 1..65535 || seenPorts.contains(port)) continue
            seenKeys.add(key)
            seenPorts.add(port)
            out += PrintRoute(key = key, localPort = port, label = label, builtIn = false)
        }
        return out
    }

    private fun saveCustomRoutes(routes: List<PrintRoute>) {
        val arr = JSONArray()
        for (route in routes) {
            if (route.builtIn || route.key == DEFAULT_ROUTE_KEY) continue
            arr.put(
                JSONObject().apply {
                    put("key", normalizeRouteKey(route.key))
                    put("label", route.label.trim())
                    put("port", route.localPort)
                }
            )
        }
        prefs.edit().putString(KEY_PRINT_ROUTES_JSON, arr.toString()).apply()
    }

    private fun loadCustomRoutes(): MutableList<PrintRoute> {
        if (!prefs.contains(KEY_PRINT_ROUTES_JSON)) {
            val seeded = mutableListOf(
                PrintRoute(LEGACY_ROUTE_KEY_1, LEGACY_ROUTE_PORT_1, LEGACY_ROUTE_LABEL_1),
                PrintRoute(LEGACY_ROUTE_KEY_2, LEGACY_ROUTE_PORT_2, LEGACY_ROUTE_LABEL_2),
            )
            saveCustomRoutes(seeded)
            return seeded
        }
        return parseRoutesJson(prefs.getString(KEY_PRINT_ROUTES_JSON, null))
    }

    fun getPrinterRoutes(): List<PrintRoute> {
        val custom = loadCustomRoutes().sortedBy { it.localPort }
        return listOf(
            PrintRoute(
                key = DEFAULT_ROUTE_KEY,
                localPort = DEFAULT_ROUTE_PORT,
                label = getMainRouteLabel(),
                builtIn = true
            )
        ) + custom
    }

    fun getPrinterRouteLabel(routeKey: String): String {
        val key = normalizeRouteKey(routeKey)
        if (isMainRoute(key)) return getMainRouteLabel()
        return getPrinterRoutes()
            .firstOrNull { normalizeRouteKey(it.key) == key }
            ?.label
            ?.trim()
            .orEmpty()
            .ifBlank { DEFAULT_ROUTE_LABEL }
    }

    fun setPrinterRouteLabel(routeKey: String, label: String): Boolean {
        val key = normalizeRouteKey(routeKey)
        val trimmedLabel = label.trim()
        if (trimmedLabel.isBlank()) return false

        if (isMainRoute(key)) {
            prefs.edit().putString(KEY_MAIN_ROUTE_LABEL, trimmedLabel).apply()
            return true
        }

        val routes = loadCustomRoutes()
        var changed = false
        val updated = routes.map { route ->
            if (normalizeRouteKey(route.key) == key) {
                changed = true
                route.copy(label = trimmedLabel)
            } else {
                route
            }
        }
        if (!changed) return false
        saveCustomRoutes(updated)
        return true
    }

    fun getNextAvailableRoutePort(startFrom: Int = LEGACY_ROUTE_PORT_1): Int {
        val usedPorts = getPrinterRoutes().map { it.localPort }.toSet()
        for (port in startFrom..65535) {
            if (!usedPorts.contains(port)) {
                return port
            }
        }
        return -1
    }

    fun addPrinterRoute(label: String, port: Int): PrintRoute? {
        val trimmedLabel = label.trim()
        if (trimmedLabel.isBlank()) return null
        if (port !in 1..65535) return null

        val routes = loadCustomRoutes()
        val usedPorts = getPrinterRoutes().map { it.localPort }.toSet()
        if (usedPorts.contains(port)) return null

        val usedKeys = getPrinterRoutes().map { normalizeRouteKey(it.key) }.toMutableSet()
        var sequence = System.currentTimeMillis()
        var key = "route_$sequence"
        while (usedKeys.contains(key)) {
            sequence += 1
            key = "route_$sequence"
        }

        val route = PrintRoute(key = key, localPort = port, label = trimmedLabel, builtIn = false)
        routes += route
        saveCustomRoutes(routes)
        return route
    }

    fun removePrinterRoute(routeKey: String): Boolean {
        val key = normalizeRouteKey(routeKey)
        if (isMainRoute(key)) return false
        val routes = loadCustomRoutes()
        val updated = routes.filterNot { normalizeRouteKey(it.key) == key }
        if (updated.size == routes.size) return false
        saveCustomRoutes(updated)
        return true
    }

    fun getPrintSettings(routeKey: String = DEFAULT_ROUTE_KEY): PrintSettings {
        val key = normalizeRouteKey(routeKey)
        val rawPort = getIntSetting(KEY_NETWORK_PORT, key, 9100)
        val safePort = if (rawPort in 1..65535) rawPort else 9100
        return PrintSettings(
            mode = PrinterMode.fromStorage(getStringSetting(KEY_PRINTER_MODE, key, PrinterMode.USB.storageValue)),
            selectedUsbPrinterId = getStringSetting(KEY_SELECTED_USB_PRINTER, key),
            selectedBluetoothAddress = getStringSetting(KEY_SELECTED_BLUETOOTH_PRINTER, key),
            selectedNetworkEndpoint = getStringSetting(KEY_SELECTED_NETWORK_ENDPOINT, key),
            networkHost = getStringSetting(KEY_NETWORK_HOST, key),
            networkPort = safePort,
            paperSize = PaperSize.fromStorage(getStringSetting(KEY_PAPER_SIZE, key, PaperSize.MM80.storageValue)),
            reversePrint = getBooleanSetting(KEY_REVERSE_PRINT, key, false),
        )
    }

    fun setPrinterMode(mode: PrinterMode, routeKey: String = DEFAULT_ROUTE_KEY) {
        prefs.edit().putString(scopedKey(KEY_PRINTER_MODE, routeKey), mode.storageValue).apply()
    }

    fun setPaperSize(paperSize: PaperSize, routeKey: String = DEFAULT_ROUTE_KEY) {
        prefs.edit().putString(scopedKey(KEY_PAPER_SIZE, routeKey), paperSize.storageValue).apply()
    }

    fun setReversePrint(enabled: Boolean, routeKey: String = DEFAULT_ROUTE_KEY) {
        prefs.edit().putBoolean(scopedKey(KEY_REVERSE_PRINT, routeKey), enabled).apply()
    }

    fun setSelectedUsbPrinter(printerId: String, routeKey: String = DEFAULT_ROUTE_KEY) {
        prefs.edit().putString(scopedKey(KEY_SELECTED_USB_PRINTER, routeKey), printerId.trim()).apply()
    }

    fun setSelectedBluetoothPrinter(address: String, routeKey: String = DEFAULT_ROUTE_KEY) {
        prefs.edit().putString(scopedKey(KEY_SELECTED_BLUETOOTH_PRINTER, routeKey), address.trim()).apply()
    }

    fun setNetworkHostPort(host: String, port: Int, routeKey: String = DEFAULT_ROUTE_KEY) {
        val safePort = if (port in 1..65535) port else 9100
        prefs.edit()
            .putString(scopedKey(KEY_NETWORK_HOST, routeKey), host.trim())
            .putInt(scopedKey(KEY_NETWORK_PORT, routeKey), safePort)
            .apply()
    }

    fun setSelectedNetworkEndpoint(endpoint: String, routeKey: String = DEFAULT_ROUTE_KEY) {
        prefs.edit().putString(scopedKey(KEY_SELECTED_NETWORK_ENDPOINT, routeKey), endpoint.trim()).apply()
    }

    fun resetPrinterSelection(routeKey: String = DEFAULT_ROUTE_KEY, forceUsbMode: Boolean = true) {
        val key = normalizeRouteKey(routeKey)
        val editor = prefs.edit()

        editor.remove(scopedKey(KEY_SELECTED_USB_PRINTER, key))
        editor.remove(scopedKey(KEY_SELECTED_BLUETOOTH_PRINTER, key))
        editor.remove(scopedKey(KEY_SELECTED_NETWORK_ENDPOINT, key))
        editor.remove(scopedKey(KEY_NETWORK_HOST, key))
        editor.remove(scopedKey(KEY_NETWORK_PORT, key))

        if (isMainRoute(key)) {
            // Clear legacy unscoped values for old installs.
            editor.remove(KEY_SELECTED_USB_PRINTER)
            editor.remove(KEY_SELECTED_BLUETOOTH_PRINTER)
            editor.remove(KEY_SELECTED_NETWORK_ENDPOINT)
            editor.remove(KEY_NETWORK_HOST)
            editor.remove(KEY_NETWORK_PORT)
        }

        if (forceUsbMode) {
            editor.putString(scopedKey(KEY_PRINTER_MODE, key), PrinterMode.USB.storageValue)
            if (isMainRoute(key)) {
                editor.putString(KEY_PRINTER_MODE, PrinterMode.USB.storageValue)
            }
        }

        editor.apply()
    }

    fun getRecentNetworkEndpoints(routeKey: String = DEFAULT_ROUTE_KEY): List<String> {
        val key = normalizeRouteKey(routeKey)
        val scoped = prefs.getStringSet(scopedKey(KEY_NETWORK_RECENT_ENDPOINTS, key), null)
        val raw = if (scoped != null) {
            scoped
        } else if (isMainRoute(key)) {
            prefs.getStringSet(KEY_NETWORK_RECENT_ENDPOINTS, emptySet()).orEmpty()
        } else {
            emptySet()
        }
        return raw
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .sorted()
    }

    fun addRecentNetworkEndpoint(endpoint: String, routeKey: String = DEFAULT_ROUTE_KEY) {
        val value = endpoint.trim()
        if (value.isBlank()) return
        val updated = getRecentNetworkEndpoints(routeKey).toMutableSet()
        updated.add(value)
        prefs.edit().putStringSet(scopedKey(KEY_NETWORK_RECENT_ENDPOINTS, routeKey), updated).apply()
    }

    private fun isSyncManagedKey(key: String): Boolean {
        if (key in CORE_SYNC_KEYS) return true
        for (prefix in ROUTE_SCOPED_SYNC_PREFIXES) {
            if (key == prefix || key.startsWith("${prefix}_")) {
                return true
            }
        }
        return false
    }

    private fun isRouteScopedSyncKey(key: String): Boolean {
        for (prefix in ROUTE_SCOPED_SYNC_PREFIXES) {
            if (key == prefix || key.startsWith("${prefix}_")) {
                return true
            }
        }
        return false
    }

    private fun isEffectivelyEmptySyncValue(value: Any?): Boolean {
        return when (value) {
            null -> true
            is String -> value.trim().isEmpty()
            is JSONArray -> value.length() == 0
            is JSONObject -> value.length() == 0
            is Set<*> -> value.isEmpty()
            is Collection<*> -> value.isEmpty()
            else -> false
        }
    }

    fun exportSyncPayload(): JSONObject {
        val prefValues = prefs.all
        val keys = prefValues.keys.filter { isSyncManagedKey(it) }.sorted()
        val prefsJson = JSONObject()
        for (key in keys) {
            val value = prefValues[key] ?: continue
            when (value) {
                is Boolean -> prefsJson.put(key, value)
                is Int -> prefsJson.put(key, value)
                is Long -> prefsJson.put(key, value)
                is Float -> prefsJson.put(key, value.toDouble())
                is String -> prefsJson.put(key, value)
                is Set<*> -> {
                    val arr = JSONArray()
                    value.mapNotNull { it?.toString()?.trim() }
                        .filter { it.isNotBlank() }
                        .sorted()
                        .forEach { arr.put(it) }
                    prefsJson.put(key, arr)
                }
            }
        }
        return JSONObject().apply {
            put("schema", 1)
            put("prefs", prefsJson)
        }
    }

    fun applySyncPayload(payload: JSONObject): Boolean {
        val prefsJson = payload.optJSONObject("prefs") ?: return false
        val editor = prefs.edit()

        // Reset non-route managed keys first. Route-scoped printer settings are merged
        // to prevent empty/stale server payloads from wiping working local selections.
        prefs.all.keys
            .filter { isSyncManagedKey(it) && !isRouteScopedSyncKey(it) }
            .forEach { editor.remove(it) }

        val keysIter = prefsJson.keys()
        while (keysIter.hasNext()) {
            val key = keysIter.next()
            if (!isSyncManagedKey(key)) continue
            val raw = prefsJson.opt(key)

            if (isRouteScopedSyncKey(key) && isEffectivelyEmptySyncValue(raw)) {
                continue
            }

            when (raw) {
                is Boolean -> editor.putBoolean(key, raw)
                is Int -> editor.putInt(key, raw)
                is Long -> {
                    if (raw in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                        editor.putInt(key, raw.toInt())
                    } else {
                        editor.putString(key, raw.toString())
                    }
                }
                is Double -> {
                    if (raw % 1.0 == 0.0 && raw >= Int.MIN_VALUE.toDouble() && raw <= Int.MAX_VALUE.toDouble()) {
                        editor.putInt(key, raw.toInt())
                    } else {
                        editor.putString(key, raw.toString())
                    }
                }
                is String -> editor.putString(key, raw)
                is JSONArray -> {
                    val values = linkedSetOf<String>()
                    for (idx in 0 until raw.length()) {
                        val item = raw.optString(idx, "").trim()
                        if (item.isNotBlank()) values.add(item)
                    }
                    editor.putStringSet(key, values)
                }
            }
        }
        editor.apply()
        syncComponentStates()
        return true
    }

    private fun setComponentEnabled(componentClassName: String, enabled: Boolean) {
        val packageManager = appContext.packageManager
        val componentName = android.content.ComponentName(appContext, componentClassName)
        val targetState = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        val currentState = packageManager.getComponentEnabledSetting(componentName)
        if (currentState == targetState) {
            return
        }
        packageManager.setComponentEnabledSetting(
            componentName,
            targetState,
            PackageManager.DONT_KILL_APP
        )
    }

    companion object {
        const val PREFS_NAME = "flickpaypos_app"
        const val DEFAULT_ROUTE_KEY = "main"
        const val DEFAULT_ROUTE_PORT = 8070
        const val DEFAULT_ROUTE_LABEL = "Receipt"

        private const val KEY_ROTATION_LOCK = "rotation_lock"
        private const val KEY_ROTATION_LOCK_USER_SET = "rotation_lock_user_set"
        private const val KEY_ROTATION_LOCK_MODE = "rotation_lock_mode"
        private const val KEY_START_ON_BOOT = "start_on_boot"
        private const val KEY_HOME_LAUNCHER = "home_launcher_enabled"
        private const val KEY_CUSTOMER_DISPLAY_ZOOM_PERCENT = "customer_display_zoom_percent"
        private const val KEY_PRINT_ROUTES_JSON = "print_routes_json"
        private const val KEY_MAIN_ROUTE_LABEL = "print_main_route_label"
        private const val KEY_PRINTER_MODE = "print_printer_mode"
        private const val KEY_SELECTED_USB_PRINTER = "print_selected_usb_printer"
        private const val KEY_SELECTED_BLUETOOTH_PRINTER = "print_selected_bluetooth_printer"
        private const val KEY_SELECTED_NETWORK_ENDPOINT = "print_selected_network_endpoint"
        private const val KEY_NETWORK_HOST = "print_network_host"
        private const val KEY_NETWORK_PORT = "print_network_port"
        private const val KEY_NETWORK_RECENT_ENDPOINTS = "print_network_recent_endpoints"
        private const val KEY_PAPER_SIZE = "print_paper_size"
        private const val KEY_REVERSE_PRINT = "print_reverse_orientation"
        private const val LEGACY_ROUTE_KEY_1 = "kitchen1"
        private const val LEGACY_ROUTE_PORT_1 = 8071
        private const val LEGACY_ROUTE_LABEL_1 = "Kitchen 1"
        private const val LEGACY_ROUTE_KEY_2 = "kitchen2"
        private const val LEGACY_ROUTE_PORT_2 = 8072
        private const val LEGACY_ROUTE_LABEL_2 = "Kitchen 2"

        private val CORE_SYNC_KEYS = setOf(
            KEY_ROTATION_LOCK,
            KEY_ROTATION_LOCK_USER_SET,
            KEY_ROTATION_LOCK_MODE,
            KEY_START_ON_BOOT,
            KEY_HOME_LAUNCHER,
            KEY_CUSTOMER_DISPLAY_ZOOM_PERCENT,
            KEY_PRINT_ROUTES_JSON,
            KEY_MAIN_ROUTE_LABEL,
        )

        private val ROUTE_SCOPED_SYNC_PREFIXES = setOf(
            KEY_PRINTER_MODE,
            KEY_SELECTED_USB_PRINTER,
            KEY_SELECTED_BLUETOOTH_PRINTER,
            KEY_SELECTED_NETWORK_ENDPOINT,
            KEY_NETWORK_HOST,
            KEY_NETWORK_PORT,
            KEY_NETWORK_RECENT_ENDPOINTS,
            KEY_PAPER_SIZE,
            KEY_REVERSE_PRINT,
        )

        private fun normalizeMainRouteLabel(raw: String?): String {
            val value = raw?.trim().orEmpty()
            return if (value.isBlank()) DEFAULT_ROUTE_LABEL else value
        }
    }

    private fun getMainRouteLabel(): String {
        val raw = prefs.getString(KEY_MAIN_ROUTE_LABEL, DEFAULT_ROUTE_LABEL)
        return Companion.normalizeMainRouteLabel(raw)
    }
}
