package uk.flickpay.flickpaypos

import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build

data class KioskState(
    val enabledInSettings: Boolean,
    val deviceOwner: Boolean,
    val lockTaskPermitted: Boolean,
    val inLockTask: Boolean,
)

object KioskModeManager {

    fun currentState(context: Context, enabledInSettings: Boolean): KioskState {
        val appContext = context.applicationContext
        val dpm = appContext.getSystemService(DevicePolicyManager::class.java)
        val packageName = appContext.packageName
        val isOwner = runCatching { dpm?.isDeviceOwnerApp(packageName) == true }.getOrDefault(false)
        val lockTaskPermitted = runCatching { dpm?.isLockTaskPermitted(packageName) == true }.getOrDefault(false)
        val inLockTask = isInLockTaskMode(appContext)
        return KioskState(
            enabledInSettings = enabledInSettings,
            deviceOwner = isOwner,
            lockTaskPermitted = lockTaskPermitted,
            inLockTask = inLockTask,
        )
    }

    fun enforce(activity: Activity, enabledInSettings: Boolean, reason: String = "unknown"): KioskState {
        val appContext = activity.applicationContext
        val packageName = appContext.packageName
        val dpm = appContext.getSystemService(DevicePolicyManager::class.java)
        val admin = ComponentName(appContext, FlickpayDeviceAdminReceiver::class.java)

        val isOwner = runCatching { dpm?.isDeviceOwnerApp(packageName) == true }.getOrDefault(false)
        if (!isOwner || dpm == null) {
            if (enabledInSettings) {
                AppRuntimeLog.w(
                    appContext,
                    "KioskMode",
                    "Kiosk toggle is on but device is not Device Owner (reason=$reason)"
                )
            }
            return currentState(activity, enabledInSettings)
        }

        if (enabledInSettings) {
            runCatching { dpm.setLockTaskPackages(admin, arrayOf(packageName)) }
                .onFailure { AppRuntimeLog.e(appContext, "KioskMode", "Failed to set lock task packages", it) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                runCatching { dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE) }
                    .onFailure { AppRuntimeLog.e(appContext, "KioskMode", "Failed to set lock task features", it) }
            }
            runCatching { dpm.setStatusBarDisabled(admin, true) }
                .onFailure { AppRuntimeLog.e(appContext, "KioskMode", "Failed to disable status bar", it) }
            runCatching { dpm.setKeyguardDisabled(admin, true) }
                .onFailure { AppRuntimeLog.e(appContext, "KioskMode", "Failed to disable keyguard", it) }
            if (!isInLockTaskMode(appContext)) {
                runCatching { activity.startLockTask() }
                    .onFailure { AppRuntimeLog.e(appContext, "KioskMode", "Failed to start lock task", it) }
            }
        } else {
            runCatching { dpm.setStatusBarDisabled(admin, false) }
                .onFailure { AppRuntimeLog.e(appContext, "KioskMode", "Failed to re-enable status bar", it) }
            runCatching { dpm.setKeyguardDisabled(admin, false) }
                .onFailure { AppRuntimeLog.e(appContext, "KioskMode", "Failed to re-enable keyguard", it) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                runCatching { dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS) }
                    .onFailure { AppRuntimeLog.e(appContext, "KioskMode", "Failed to reset lock task features", it) }
            }
            runCatching { dpm.setLockTaskPackages(admin, emptyArray()) }
                .onFailure { AppRuntimeLog.e(appContext, "KioskMode", "Failed to clear lock task packages", it) }
            if (isInLockTaskMode(appContext)) {
                runCatching { activity.stopLockTask() }
                    .onFailure { AppRuntimeLog.e(appContext, "KioskMode", "Failed to stop lock task", it) }
            }
        }

        val state = currentState(activity, enabledInSettings)
        AppRuntimeLog.i(
            appContext,
            "KioskMode",
            "Enforced kiosk enabled=${state.enabledInSettings} owner=${state.deviceOwner} " +
                "permitted=${state.lockTaskPermitted} locked=${state.inLockTask} reason=$reason"
        )
        return state
    }

    private fun isInLockTaskMode(context: Context): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val mode = activityManager?.lockTaskModeState ?: ActivityManager.LOCK_TASK_MODE_NONE
        return mode != ActivityManager.LOCK_TASK_MODE_NONE
    }
}
