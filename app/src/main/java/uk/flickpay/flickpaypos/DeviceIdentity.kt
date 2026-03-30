package uk.flickpay.flickpaypos

import android.content.Context
import android.provider.Settings
import java.util.UUID

object DeviceIdentity {

    private const val PREFS_NAME = "flickpaypos_app"
    private const val KEY_DEVICE_ID = "device_id"

    fun getOrCreate(context: Context): String {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_DEVICE_ID, null)?.trim().orEmpty()
        if (existing.isNotBlank()) {
            return existing
        }

        val androidId = runCatching {
            Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrDefault("").trim().lowercase()

        val stable = if (androidId.isNotBlank() && androidId != "9774d56d682e549c") {
            "android-$androidId"
        } else {
            UUID.randomUUID().toString()
        }
        prefs.edit().putString(KEY_DEVICE_ID, stable).apply()
        return stable
    }
}
