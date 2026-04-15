package uk.flickpay.flickpaypos

import android.app.Activity
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display

class CustomerDisplayController(
    private val activity: Activity,
) : DisplayManager.DisplayListener {
    companion object {
        private const val TAG = "CustomerDisplay"
        private val RETRY_DELAYS_MS = longArrayOf(300L, 1000L, 2500L, 5000L)
    }

    private val displayManager =
        activity.getSystemService(Activity.DISPLAY_SERVICE) as? DisplayManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val retryRefreshRunnables = mutableListOf<Runnable>()

    private var customerUrl: String = ""
    private var presentation: CustomerDisplayPresentation? = null
    private var started = false

    fun start() {
        runOnMain {
            if (!started) {
                started = true
                displayManager?.registerDisplayListener(this, mainHandler)
            }
            refreshPresentation(scheduleRetries = true)
        }
    }

    fun stop() {
        if (!started) return
        started = false
        runOnMain {
            runCatching { displayManager?.unregisterDisplayListener(this) }
            clearRetryRefreshes()
            dismissPresentation()
        }
    }

    fun setCustomerUrl(url: String) {
        customerUrl = url.trim()
        runOnMain { refreshPresentation(scheduleRetries = true) }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.getMainLooper().thread === Thread.currentThread()) {
            block()
        } else {
            mainHandler.post { block() }
        }
    }

    private fun currentActivityDisplayId(): Int {
        @Suppress("DEPRECATION")
        return runCatching { activity.windowManager.defaultDisplay.displayId }
            .getOrDefault(Display.DEFAULT_DISPLAY)
    }

    private fun findCustomerDisplay(): Display? {
        val dm = displayManager ?: return null
        val mainDisplayId = currentActivityDisplayId()
        val presentationDisplays = dm
            .getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .filter { it.displayId != mainDisplayId }
        if (presentationDisplays.isNotEmpty()) {
            return presentationDisplays.first()
        }
        // Fallback for vendor ROMs that do not correctly classify external displays
        // under DISPLAY_CATEGORY_PRESENTATION.
        return dm.getDisplays().firstOrNull { it.displayId != mainDisplayId }
    }

    private fun dismissPresentation() {
        val existing = presentation ?: return
        runCatching { existing.shutdown() }
        runCatching { existing.dismiss() }
        presentation = null
    }

    private fun clearRetryRefreshes() {
        if (retryRefreshRunnables.isEmpty()) return
        retryRefreshRunnables.forEach { mainHandler.removeCallbacks(it) }
        retryRefreshRunnables.clear()
    }

    private fun scheduleRetryRefreshes() {
        if (customerUrl.isBlank()) return
        clearRetryRefreshes()
        RETRY_DELAYS_MS.forEach { delayMs ->
            val runnable = Runnable {
                if (!started || customerUrl.isBlank()) return@Runnable
                refreshPresentation(scheduleRetries = false)
            }
            retryRefreshRunnables += runnable
            mainHandler.postDelayed(runnable, delayMs)
        }
    }

    private fun refreshPresentation(scheduleRetries: Boolean = false) {
        val nextUrl = customerUrl.trim()
        if (nextUrl.isBlank()) {
            clearRetryRefreshes()
            dismissPresentation()
            return
        }

        val targetDisplay = findCustomerDisplay()
        if (targetDisplay == null) {
            AppRuntimeLog.w(activity.applicationContext, TAG, "No external display available yet")
            dismissPresentation()
            if (scheduleRetries) {
                scheduleRetryRefreshes()
            }
            return
        }

        val existing = presentation
        val existingDisplayId = runCatching { existing?.display?.displayId }.getOrNull()
        val existingShowing = runCatching { existing?.isShowing == true }.getOrDefault(false)
        val needsRecreate = !existingShowing || existingDisplayId != targetDisplay.displayId
        if (needsRecreate) {
            dismissPresentation()
            val created = runCatching {
                CustomerDisplayPresentation(activity, targetDisplay).also { presentation ->
                    presentation.setOnDismissListener {
                        if (this.presentation === presentation) {
                            this.presentation = null
                        }
                    }
                    presentation.show()
                }
            }
                .onFailure { err ->
                    AppRuntimeLog.w(
                        activity.applicationContext,
                        TAG,
                        "Failed to create customer presentation",
                        err
                    )
                }
                .getOrNull()
            presentation = created
            if (created == null) {
                if (scheduleRetries) {
                    scheduleRetryRefreshes()
                }
                return
            }
        }

        presentation?.showUrl(nextUrl)
        clearRetryRefreshes()
    }

    override fun onDisplayAdded(displayId: Int) {
        refreshPresentation(scheduleRetries = false)
    }

    override fun onDisplayRemoved(displayId: Int) {
        refreshPresentation(scheduleRetries = false)
    }

    override fun onDisplayChanged(displayId: Int) {
        refreshPresentation(scheduleRetries = false)
    }
}
