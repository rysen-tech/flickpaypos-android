package uk.flickpay.flickpaypos

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

class FlickpayDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        AppRuntimeLog.i(context.applicationContext, "DeviceAdmin", "Device admin receiver enabled")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        AppRuntimeLog.w(context.applicationContext, "DeviceAdmin", "Device admin receiver disabled")
    }
}
