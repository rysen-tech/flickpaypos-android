package uk.flickpay.flickpaypos

import android.app.Activity
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display

class CustomerDisplayController(
    private val activity: Activity,
) : DisplayManager.DisplayListener {

    private val displayManager =
        activity.getSystemService(Activity.DISPLAY_SERVICE) as? DisplayManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var customerUrl: String = ""
    private var presentation: CustomerDisplayPresentation? = null
    private var started = false

    fun start() {
        if (started) return
        started = true
        runOnMain {
            displayManager?.registerDisplayListener(this, mainHandler)
            refreshPresentation()
        }
    }

    fun stop() {
        if (!started) return
        started = false
        runOnMain {
            runCatching { displayManager?.unregisterDisplayListener(this) }
            dismissPresentation()
        }
    }

    fun setCustomerUrl(url: String) {
        customerUrl = url.trim()
        runOnMain { refreshPresentation() }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.getMainLooper().thread === Thread.currentThread()) {
            block()
        } else {
            mainHandler.post { block() }
        }
    }

    private fun currentActivityDisplayId(): Int {
        return runCatching { activity.display?.displayId ?: Display.DEFAULT_DISPLAY }
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

    private fun refreshPresentation() {
        val nextUrl = customerUrl.trim()
        if (nextUrl.isBlank()) {
            dismissPresentation()
            return
        }

        val targetDisplay = findCustomerDisplay()
        if (targetDisplay == null) {
            dismissPresentation()
            return
        }

        val existing = presentation
        val needsRecreate = existing == null || existing.display.displayId != targetDisplay.displayId
        if (needsRecreate) {
            dismissPresentation()
            val created = runCatching {
                CustomerDisplayPresentation(activity, targetDisplay).also { it.show() }
            }.getOrNull()
            presentation = created
        }

        presentation?.showUrl(nextUrl)
    }

    override fun onDisplayAdded(displayId: Int) {
        refreshPresentation()
    }

    override fun onDisplayRemoved(displayId: Int) {
        refreshPresentation()
    }

    override fun onDisplayChanged(displayId: Int) {
        refreshPresentation()
    }
}
