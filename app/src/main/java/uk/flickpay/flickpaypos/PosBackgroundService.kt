package uk.flickpay.flickpaypos

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import fi.iki.elonen.NanoHTTPD

class PosBackgroundService : Service() {

    private val servers = mutableListOf<HwProxyServer>()
    private val printers = mutableMapOf<String, UsbEscPosPrinter>()
    private lateinit var appSettings: AppSettings

    override fun onCreate() {
        super.onCreate()
        appSettings = AppSettings(applicationContext)
        for (route in appSettings.getPrinterRoutes()) {
            val printer = UsbEscPosPrinter(applicationContext, route.key)
            printers[route.key] = printer
            printer.warmupActivePrinterAsync()
        }
        scheduleBluetoothWarmupRetries()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        startServer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        servers.forEach { runCatching { it.stop() } }
        servers.clear()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startServer() {
        servers.clear()
        for (route in appSettings.getPrinterRoutes()) {
            val printer = printers[route.key] ?: continue
            val port = route.localPort
            // Try HTTPS first to satisfy POS pages served over HTTPS.
            val server = runCatching {
                HwProxyServer(this, printer, port = port, useTls = true).also {
                    it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                }
            }.getOrElse {
                HwProxyServer(this, printer, port = port, useTls = false).also {
                    it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                }
            }
            servers += server
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = getSystemService(NotificationManager::class.java)

        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.proxy_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.proxy_channel_description)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }

        manager.createNotificationChannel(channel)
    }

    private fun scheduleBluetoothWarmupRetries() {
        Thread {
            repeat(4) {
                SystemClock.sleep(1500)
                for (printer in printers.values) {
                    runCatching { printer.warmupActivePrinterAsync() }
                }
            }
        }.start()
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_print)
            .setContentTitle(getString(R.string.proxy_notification_title))
            .setContentText(getString(R.string.proxy_notification_text))
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "flickpaypos_service"
        private const val NOTIFICATION_ID = 8070
    }
}
