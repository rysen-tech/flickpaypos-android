package uk.flickpay.flickpaypos

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.net.HttpURLConnection
import java.net.URL

class SettingsActivity : AppCompatActivity() {

    private lateinit var appSettings: AppSettings

    private lateinit var generalSection: ScrollView
    private lateinit var printSection: ScrollView
    private lateinit var logsSection: View
    private lateinit var aboutSection: ScrollView
    private lateinit var navGeneralItem: View
    private lateinit var navPrintItem: View
    private lateinit var navLogsItem: View
    private lateinit var navAboutItem: View
    private lateinit var navGeneralIndicator: View
    private lateinit var navPrintIndicator: View
    private lateinit var navLogsIndicator: View
    private lateinit var navAboutIndicator: View
    private lateinit var pageTitle: TextView
    private lateinit var sidebarVersion: TextView
    private lateinit var closeSettingsButton: ImageButton

    private lateinit var rotationLockSwitch: MaterialSwitch
    private lateinit var rotationLockHint: TextView
    private lateinit var customerDisplayZoomSlider: Slider
    private lateinit var customerDisplayZoomValue: TextView
    private lateinit var customerDisplayZoomHint: TextView
    private lateinit var startOnBootSwitch: MaterialSwitch
    private lateinit var startOnBootHint: TextView
    private lateinit var homeAppSwitch: MaterialSwitch
    private lateinit var homeAppHint: TextView
    private lateinit var checkUpdatesButton: MaterialButton
    private lateinit var checkUpdatesHint: TextView

    private lateinit var addPrinterTargetButton: MaterialButton
    private lateinit var removePrinterTargetButton: MaterialButton
    private lateinit var savePrinterNameButton: MaterialButton
    private lateinit var testPrintButton: MaterialButton
    private lateinit var printerProfileDropdown: MaterialAutoCompleteTextView
    private lateinit var printerModeDropdown: MaterialAutoCompleteTextView
    private lateinit var printerDeviceDropdown: MaterialAutoCompleteTextView
    private lateinit var printerNameInput: TextInputEditText
    private lateinit var paperSizeDropdown: MaterialAutoCompleteTextView
    private lateinit var reversePrintSwitch: MaterialSwitch
    private lateinit var reversePrintHint: TextView
    private lateinit var bluetoothPermissionHint: TextView
    private lateinit var networkHostInput: TextInputEditText
    private lateinit var networkPortInput: TextInputEditText
    private lateinit var refreshPrintersButton: MaterialButton
    private lateinit var resetPrinterSelectionButton: MaterialButton
    private lateinit var saveNetworkEndpointButton: MaterialButton

    private lateinit var refreshLogsButton: MaterialButton
    private lateinit var sendLogsButton: MaterialButton
    private lateinit var clearLogsButton: MaterialButton
    private lateinit var logsScrollWindow: ScrollView
    private lateinit var logsContentText: TextView
    private lateinit var logsUpdatedText: TextView
    private lateinit var aboutVersionValue: TextView

    private lateinit var networkFields: View

    private val modeItems = listOf(
        ModeItem(label = "USB", mode = PrinterMode.USB),
        ModeItem(label = "Bluetooth", mode = PrinterMode.BLUETOOTH),
        ModeItem(label = "Network", mode = PrinterMode.NETWORK),
    )
    private val paperItems = listOf(
        PaperItem(label = PaperSize.MM80.label, paperSize = PaperSize.MM80),
        PaperItem(label = PaperSize.MM58.label, paperSize = PaperSize.MM58),
    )
    private var currentRouteItems: List<ProfileItem> = emptyList()
    private var currentPrinterOptions: List<PrinterOption> = emptyList()
    private var currentPrintProfile: String = AppSettings.DEFAULT_ROUTE_KEY
    private var suppressNetworkFieldChange = false
    private var suppressGeneralToggleCallbacks = false
    private var suppressPrintToggleCallbacks = false
    private var currentSection: Section = Section.GENERAL
    private val settingsSyncHandler = Handler(Looper.getMainLooper())
    private val settingsSyncRunnable = Runnable {
        Thread {
            runCatching {
                DeviceSettingsSync.pushCurrentSettings(
                    applicationContext,
                    reason = "settings_debounced"
                )
            }
        }.start()
    }

    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshPrinterOptions(allowBluetoothPermissionPrompt = false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_settings)

        appSettings = AppSettings(this)
        appSettings.syncComponentStates()

        requestedOrientation = if (appSettings.isRotationLocked()) {
            appSettings.getLockedRequestedOrientation()
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }

        bindViews()
        configureSidebar()
        configureGeneralSection()
        configurePrintSection()
        configureLogsSection()
        configureAboutSection()
        configureBackNavigation()
        loadCurrentSettings()
        enableImmersiveMode()
    }

    override fun onResume() {
        super.onResume()
        enableImmersiveMode()
        AppUpdateManager.resumePendingInstallIfPossible(this)
        updateKioskHint()
    }

    override fun onStop() {
        super.onStop()
        flushSettingsSync(reason = "settings_flush")
    }

    override fun onDestroy() {
        settingsSyncHandler.removeCallbacks(settingsSyncRunnable)
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            enableImmersiveMode()
        }
    }

    private fun bindViews() {
        generalSection = findViewById(R.id.generalSection)
        printSection = findViewById(R.id.printSection)
        logsSection = findViewById(R.id.logsSection)
        aboutSection = findViewById(R.id.aboutSection)
        navGeneralItem = findViewById(R.id.navGeneralItem)
        navPrintItem = findViewById(R.id.navPrintItem)
        navLogsItem = findViewById(R.id.navLogsItem)
        navAboutItem = findViewById(R.id.navAboutItem)
        navGeneralIndicator = findViewById(R.id.navGeneralIndicator)
        navPrintIndicator = findViewById(R.id.navPrintIndicator)
        navLogsIndicator = findViewById(R.id.navLogsIndicator)
        navAboutIndicator = findViewById(R.id.navAboutIndicator)
        pageTitle = findViewById(R.id.settingsPageTitle)
        sidebarVersion = findViewById(R.id.settingsSidebarVersion)
        closeSettingsButton = findViewById(R.id.closeSettingsButton)

        rotationLockSwitch = findViewById(R.id.rotationLockSwitch)
        rotationLockHint = findViewById(R.id.rotationLockHint)
        customerDisplayZoomSlider = findViewById(R.id.customerDisplayZoomSlider)
        customerDisplayZoomValue = findViewById(R.id.customerDisplayZoomValue)
        customerDisplayZoomHint = findViewById(R.id.customerDisplayZoomHint)
        startOnBootSwitch = findViewById(R.id.startOnBootSwitch)
        startOnBootHint = findViewById(R.id.startOnBootHint)
        homeAppSwitch = findViewById(R.id.homeAppSwitch)
        homeAppHint = findViewById(R.id.homeAppHint)
        checkUpdatesButton = findViewById(R.id.checkUpdatesButton)
        checkUpdatesHint = findViewById(R.id.checkUpdatesHint)

        addPrinterTargetButton = findViewById(R.id.addPrinterTargetButton)
        removePrinterTargetButton = findViewById(R.id.removePrinterTargetButton)
        savePrinterNameButton = findViewById(R.id.savePrinterNameButton)
        testPrintButton = findViewById(R.id.testPrintButton)
        printerProfileDropdown = findViewById(R.id.printerProfileDropdown)
        printerModeDropdown = findViewById(R.id.printerModeDropdown)
        printerDeviceDropdown = findViewById(R.id.printerDeviceDropdown)
        printerNameInput = findViewById(R.id.printerNameInput)
        paperSizeDropdown = findViewById(R.id.paperSizeDropdown)
        reversePrintSwitch = findViewById(R.id.reversePrintSwitch)
        reversePrintHint = findViewById(R.id.reversePrintHint)
        bluetoothPermissionHint = findViewById(R.id.bluetoothPermissionHint)
        networkHostInput = findViewById(R.id.networkHostInput)
        networkPortInput = findViewById(R.id.networkPortInput)
        refreshPrintersButton = findViewById(R.id.refreshPrintersButton)
        resetPrinterSelectionButton = findViewById(R.id.resetPrinterSelectionButton)
        saveNetworkEndpointButton = findViewById(R.id.saveNetworkEndpointButton)
        networkFields = findViewById(R.id.networkFields)

        refreshLogsButton = findViewById(R.id.refreshLogsButton)
        sendLogsButton = findViewById(R.id.sendLogsButton)
        clearLogsButton = findViewById(R.id.clearLogsButton)
        logsScrollWindow = findViewById(R.id.logsScrollWindow)
        logsContentText = findViewById(R.id.logsContentText)
        logsUpdatedText = findViewById(R.id.logsUpdatedText)
        aboutVersionValue = findViewById(R.id.aboutVersionValue)
    }

    private fun configureSidebar() {
        val versionLabel = if (BuildConfig.VERSION_NAME.isNullOrBlank()) "" else "v${BuildConfig.VERSION_NAME}"
        sidebarVersion.text = versionLabel

        navGeneralItem.setOnClickListener { showSection(Section.GENERAL) }
        navPrintItem.setOnClickListener { showSection(Section.PRINT) }
        navLogsItem.setOnClickListener { showSection(Section.LOGS) }
        navAboutItem.setOnClickListener { showSection(Section.ABOUT) }
        closeSettingsButton.setOnClickListener { finish() }
    }

    private fun configureBackNavigation() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (currentSection != Section.GENERAL) {
                        showSection(Section.GENERAL)
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        )
    }

    private fun configureGeneralSection() {
        rotationLockSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (suppressGeneralToggleCallbacks) return@setOnCheckedChangeListener
            if (isChecked) {
                val mode = detectCurrentRotationLockMode()
                appSettings.setRotationLocked(true, mode)
            } else {
                appSettings.setRotationLocked(false)
            }
            requestedOrientation = if (isChecked) {
                appSettings.getLockedRequestedOrientation()
            } else {
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
            rotationLockHint.alpha = if (isChecked) 1.0f else 0.9f
            scheduleSettingsSync()
        }

        customerDisplayZoomSlider.addOnChangeListener { _, value, fromUser ->
            val zoom = value.toInt().coerceIn(50, 200)
            customerDisplayZoomValue.text = getString(R.string.customer_display_zoom_value, zoom)
            if (fromUser) {
                appSettings.setCustomerDisplayZoomPercent(zoom)
                customerDisplayZoomHint.alpha = if (zoom == 85) 1.0f else 0.95f
                scheduleSettingsSync()
            }
        }

        startOnBootSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (suppressGeneralToggleCallbacks) return@setOnCheckedChangeListener
            appSettings.setStartOnBootEnabled(isChecked)
            startOnBootHint.alpha = if (isChecked) 1.0f else 0.9f
            scheduleSettingsSync()
        }

        homeAppSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (suppressGeneralToggleCallbacks) return@setOnCheckedChangeListener
            appSettings.setHomeLauncherEnabled(isChecked)
            val kioskState = KioskModeManager.enforce(this, isChecked, "settings_toggle")
            homeAppHint.alpha = if (isChecked) 1.0f else 0.9f
            if (isChecked) {
                Toast.makeText(this, getString(R.string.home_app_enable_toast), Toast.LENGTH_LONG).show()
                openHomeAppSettings()
                if (!kioskState.deviceOwner) {
                    Toast.makeText(
                        this,
                        getString(R.string.kiosk_mode_requires_owner_toast),
                        Toast.LENGTH_LONG
                    ).show()
                }
            } else {
                Toast.makeText(this, getString(R.string.home_app_disable_toast), Toast.LENGTH_SHORT).show()
            }
            updateKioskHint(kioskState)
            scheduleSettingsSync()
        }

        checkUpdatesButton.setOnClickListener {
            runManualUpdateCheck()
        }
    }

    private fun configurePrintSection() {
        val dropdownLayout = android.R.layout.simple_list_item_1
        refreshPrinterRouteDropdown(currentPrintProfile)

        printerProfileDropdown.setOnItemClickListener { _, _, position, _ ->
            val selected = currentRouteItems.getOrNull(position) ?: return@setOnItemClickListener
            currentPrintProfile = selected.routeKey
            loadPrintSettingsForCurrentProfile(allowBluetoothPermissionPrompt = false)
        }

        addPrinterTargetButton.setOnClickListener {
            val nextPort = appSettings.getNextAvailableRoutePort()
            if (nextPort <= 0) {
                Toast.makeText(this, getString(R.string.no_available_target_port), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val routeNumber = appSettings.getPrinterRoutes().count { !it.builtIn } + 1
            val route = appSettings.addPrinterRoute("Kitchen $routeNumber", nextPort)
            if (route == null) {
                Toast.makeText(this, getString(R.string.no_available_target_port), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Toast.makeText(this, getString(R.string.printer_target_added, route.localPort), Toast.LENGTH_SHORT).show()
            currentPrintProfile = route.key
            restartProxyService()
            refreshPrinterRouteDropdown(currentPrintProfile)
            loadPrintSettingsForCurrentProfile(allowBluetoothPermissionPrompt = false)
            scheduleSettingsSync()
        }

        removePrinterTargetButton.setOnClickListener {
            if (currentPrintProfile == AppSettings.DEFAULT_ROUTE_KEY) {
                Toast.makeText(this, getString(R.string.cannot_remove_main_target), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!appSettings.removePrinterRoute(currentPrintProfile)) {
                return@setOnClickListener
            }
            Toast.makeText(this, getString(R.string.printer_target_removed), Toast.LENGTH_SHORT).show()
            currentPrintProfile = AppSettings.DEFAULT_ROUTE_KEY
            restartProxyService()
            refreshPrinterRouteDropdown(currentPrintProfile)
            loadPrintSettingsForCurrentProfile(allowBluetoothPermissionPrompt = false)
            scheduleSettingsSync()
        }

        savePrinterNameButton.setOnClickListener {
            val label = printerNameInput.text?.toString()?.trim().orEmpty()
            if (label.isBlank()) {
                Toast.makeText(this, getString(R.string.printer_name_required), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!appSettings.setPrinterRouteLabel(currentPrintProfile, label)) {
                Toast.makeText(this, getString(R.string.printer_name_required), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Toast.makeText(this, getString(R.string.printer_name_saved), Toast.LENGTH_SHORT).show()
            refreshPrinterRouteDropdown(currentPrintProfile)
            loadPrintSettingsForCurrentProfile(allowBluetoothPermissionPrompt = false)
            restartProxyService()
            scheduleSettingsSync()
        }

        printerModeDropdown.setAdapter(
            ArrayAdapter(
                this,
                dropdownLayout,
                modeItems.map { it.label }
            )
        )
        printerModeDropdown.setOnItemClickListener { _, _, position, _ ->
            val selected = modeItems.getOrNull(position) ?: return@setOnItemClickListener
            appSettings.setPrinterMode(selected.mode, currentPrintProfile)
            syncPrintUiForMode(selected.mode, allowBluetoothPermissionPrompt = true)
            if (selected.mode == PrinterMode.BLUETOOTH) {
                UsbEscPosPrinter(applicationContext, currentPrintProfile).warmupActivePrinterAsync()
            }
            scheduleSettingsSync()
        }

        paperSizeDropdown.setAdapter(
            ArrayAdapter(
                this,
                dropdownLayout,
                paperItems.map { it.label }
            )
        )
        paperSizeDropdown.setOnItemClickListener { _, _, position, _ ->
            val selected = paperItems.getOrNull(position) ?: return@setOnItemClickListener
            appSettings.setPaperSize(selected.paperSize, currentPrintProfile)
            scheduleSettingsSync()
        }

        reversePrintSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (suppressPrintToggleCallbacks) return@setOnCheckedChangeListener
            appSettings.setReversePrint(isChecked, currentPrintProfile)
            reversePrintHint.alpha = if (isChecked) 1.0f else 0.9f
            scheduleSettingsSync()
        }

        printerDeviceDropdown.setOnItemClickListener { _, _, position, _ ->
            val selected = currentPrinterOptions.getOrNull(position) ?: return@setOnItemClickListener
            onPrinterSelected(selected)
        }

        refreshPrintersButton.setOnClickListener {
            refreshPrinterOptions(allowBluetoothPermissionPrompt = true)
        }

        resetPrinterSelectionButton.setOnClickListener {
            appSettings.resetPrinterSelection(currentPrintProfile, forceUsbMode = true)
            restartProxyService()
            loadPrintSettingsForCurrentProfile(allowBluetoothPermissionPrompt = false)
            scheduleSettingsSync()
            Toast.makeText(this, getString(R.string.printer_selection_reset), Toast.LENGTH_SHORT).show()
        }

        testPrintButton.setOnClickListener {
            runTestPrint()
        }

        saveNetworkEndpointButton.setOnClickListener {
            val endpoint = persistNetworkHostPort(addToRecent = true)
            if (endpoint.isBlank()) {
                Toast.makeText(this, "Enter network host and port first.", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Network printer saved.", Toast.LENGTH_SHORT).show()
                refreshPrinterOptions(allowBluetoothPermissionPrompt = false)
            }
            scheduleSettingsSync()
        }

        networkHostInput.doAfterTextChanged {
            if (suppressNetworkFieldChange) return@doAfterTextChanged
            persistNetworkHostPort(addToRecent = false)
        }
        networkPortInput.doAfterTextChanged {
            if (suppressNetworkFieldChange) return@doAfterTextChanged
            persistNetworkHostPort(addToRecent = false)
        }
    }

    private fun configureLogsSection() {
        refreshLogsButton.setOnClickListener {
            loadLogs()
        }
        sendLogsButton.setOnClickListener {
            sendLogsToServer()
        }
        clearLogsButton.setOnClickListener {
            val ok = AppRuntimeLog.clear(applicationContext)
            if (ok) {
                Toast.makeText(this, getString(R.string.logs_cleared), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, getString(R.string.logs_clear_failed), Toast.LENGTH_SHORT).show()
            }
            loadLogs()
        }
    }

    private fun configureAboutSection() {
        aboutVersionValue.text = getString(
            R.string.about_app_version_value,
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE
        )
    }

    private fun loadCurrentSettings() {
        suppressGeneralToggleCallbacks = true
        rotationLockSwitch.isChecked = appSettings.isRotationLocked()
        startOnBootSwitch.isChecked = appSettings.isStartOnBootEnabled()
        homeAppSwitch.isChecked = appSettings.isHomeLauncherEnabled()
        suppressGeneralToggleCallbacks = false
        rotationLockHint.alpha = if (rotationLockSwitch.isChecked) 1.0f else 0.9f
        val zoom = appSettings.getCustomerDisplayZoomPercent()
        customerDisplayZoomSlider.value = zoom.toFloat()
        customerDisplayZoomValue.text = getString(R.string.customer_display_zoom_value, zoom)
        customerDisplayZoomHint.alpha = if (zoom == 85) 1.0f else 0.95f
        startOnBootHint.alpha = if (startOnBootSwitch.isChecked) 1.0f else 0.9f
        homeAppHint.alpha = if (homeAppSwitch.isChecked) 1.0f else 0.9f
        checkUpdatesHint.alpha = 0.95f
        updateKioskHint()

        currentPrintProfile = AppSettings.DEFAULT_ROUTE_KEY
        refreshPrinterRouteDropdown(currentPrintProfile)
        loadPrintSettingsForCurrentProfile(allowBluetoothPermissionPrompt = false)

        showSection(Section.GENERAL)
    }

    private fun loadLogs() {
        setLogsActionsEnabled(false)
        logsUpdatedText.text = getString(R.string.logs_loading)
        Thread {
            val logs = AppRuntimeLog.read(applicationContext).trim()
            val rendered = if (logs.isBlank()) {
                getString(R.string.logs_empty)
            } else {
                reverseLogEntriesNewestFirst(logs)
            }
            val updatedAt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            runOnUiThread {
                logsContentText.text = rendered
                logsUpdatedText.text = getString(R.string.logs_last_updated, updatedAt)
                setLogsActionsEnabled(true)
                pinLogsWindowTop()
            }
        }.start()
    }

    private fun sendLogsToServer() {
        setLogsActionsEnabled(false)
        logsUpdatedText.text = getString(R.string.logs_sending)
        Thread {
            val logs = AppRuntimeLog.read(applicationContext)
            val result = DeviceSettingsSync.sendDiagnostics(
                context = applicationContext,
                logs = logs,
                reason = "manual_send",
            )
            val updatedAt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            runOnUiThread {
                setLogsActionsEnabled(true)
                if (result.success) {
                    val ref = result.reference?.trim().orEmpty()
                    if (ref.isBlank()) {
                        Toast.makeText(this, getString(R.string.logs_sent_ok), Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, getString(R.string.logs_sent_ok_ref, ref), Toast.LENGTH_LONG).show()
                    }
                    logsUpdatedText.text = getString(R.string.logs_last_updated, updatedAt)
                } else {
                    val err = result.error?.trim().orEmpty()
                    val message = if (err.isBlank()) getString(R.string.logs_send_failed) else err
                    logsUpdatedText.text = message
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                }
                pinLogsWindowTop()
            }
        }.start()
    }

    private fun setLogsActionsEnabled(enabled: Boolean) {
        refreshLogsButton.isEnabled = enabled
        sendLogsButton.isEnabled = enabled
        clearLogsButton.isEnabled = enabled
    }

    private fun pinLogsWindowTop() {
        val scrollTop: () -> Unit = {
            logsScrollWindow.scrollTo(0, 0)
            logsContentText.scrollTo(0, 0)
            logsScrollWindow.fullScroll(View.FOCUS_UP)
        }
        logsScrollWindow.post { scrollTop() }
        logsScrollWindow.postDelayed({ scrollTop() }, 24L)
        logsScrollWindow.postDelayed({ scrollTop() }, 120L)
    }

    private fun reverseLogEntriesNewestFirst(raw: String): String {
        if (raw.isBlank()) return raw
        val entryStart = Regex("^\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\s+[DIWE]/")
        val entries = mutableListOf<StringBuilder>()
        for (line in raw.lineSequence()) {
            if (entryStart.containsMatchIn(line) || entries.isEmpty()) {
                entries += StringBuilder(line)
            } else {
                entries.last().append('\n').append(line)
            }
        }
        return entries.asReversed().joinToString("\n")
    }

    private fun detectCurrentRotationLockMode(): RotationLockMode {
        return when (resources.configuration.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> RotationLockMode.LANDSCAPE
            Configuration.ORIENTATION_PORTRAIT -> RotationLockMode.PORTRAIT
            else -> {
                if (resources.displayMetrics.widthPixels >= resources.displayMetrics.heightPixels) {
                    RotationLockMode.LANDSCAPE
                } else {
                    RotationLockMode.PORTRAIT
                }
            }
        }
    }

    private fun loadPrintSettingsForCurrentProfile(allowBluetoothPermissionPrompt: Boolean) {
        val print = appSettings.getPrintSettings(currentPrintProfile)

        val selectedMode = modeItems.firstOrNull { it.mode == print.mode } ?: modeItems.first()
        printerModeDropdown.setText(selectedMode.label, false)

        val selectedPaper = paperItems.firstOrNull { it.paperSize == print.paperSize } ?: paperItems.first()
        paperSizeDropdown.setText(selectedPaper.label, false)

        suppressNetworkFieldChange = true
        networkHostInput.setText(print.networkHost)
        networkPortInput.setText(print.networkPort.toString())
        suppressNetworkFieldChange = false

        suppressPrintToggleCallbacks = true
        reversePrintSwitch.isChecked = print.reversePrint
        suppressPrintToggleCallbacks = false
        reversePrintHint.alpha = if (print.reversePrint) 1.0f else 0.9f
        printerNameInput.setText(appSettings.getPrinterRouteLabel(currentPrintProfile))

        syncPrintUiForMode(print.mode, allowBluetoothPermissionPrompt = allowBluetoothPermissionPrompt)
    }

    private fun refreshPrinterRouteDropdown(selectedRouteKey: String) {
        currentRouteItems = appSettings.getPrinterRoutes().map { route ->
            ProfileItem(
                label = "${route.label} (${route.localPort})",
                routeKey = route.key
            )
        }
        printerProfileDropdown.setAdapter(
            ArrayAdapter(
                this,
                android.R.layout.simple_list_item_1,
                currentRouteItems.map { it.label }
            )
        )
        val selected = currentRouteItems.firstOrNull { it.routeKey == selectedRouteKey }
            ?: currentRouteItems.firstOrNull()
        if (selected != null) {
            currentPrintProfile = selected.routeKey
            printerProfileDropdown.setText(selected.label, false)
        }
    }

    private fun openHomeAppSettings() {
        val homeSettingsIntent = Intent(Settings.ACTION_HOME_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (runCatching { startActivity(homeSettingsIntent); true }.getOrDefault(false)) return
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(homeIntent)
    }

    private fun restartProxyService() {
        val intent = Intent(this, PosBackgroundService::class.java)
        runCatching { stopService(intent) }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun scheduleSettingsSync(delayMs: Long = 900L) {
        settingsSyncHandler.removeCallbacks(settingsSyncRunnable)
        settingsSyncHandler.postDelayed(settingsSyncRunnable, delayMs)
    }

    private fun flushSettingsSync(reason: String) {
        settingsSyncHandler.removeCallbacks(settingsSyncRunnable)
        Thread {
            runCatching {
                DeviceSettingsSync.pushCurrentSettings(
                    applicationContext,
                    reason = reason
                )
            }
        }.start()
    }

    private fun syncPrintUiForMode(mode: PrinterMode, allowBluetoothPermissionPrompt: Boolean) {
        networkFields.visibility = if (mode == PrinterMode.NETWORK) View.VISIBLE else View.GONE
        refreshPrinterOptions(allowBluetoothPermissionPrompt = allowBluetoothPermissionPrompt)
    }

    private fun refreshPrinterOptions(allowBluetoothPermissionPrompt: Boolean) {
        val print = appSettings.getPrintSettings(currentPrintProfile)
        val mode = print.mode

        if (mode == PrinterMode.BLUETOOTH && !PrinterDiscovery.hasBluetoothConnectPermission(this)) {
            bluetoothPermissionHint.visibility = View.VISIBLE
            currentPrinterOptions = emptyList()
            setPrinterDropdownItems(listOf("No Bluetooth permission"), enabled = false)
            if (allowBluetoothPermissionPrompt) {
                requestBluetoothPermission()
            }
            return
        }

        bluetoothPermissionHint.visibility = View.GONE
        currentPrinterOptions = when (mode) {
            PrinterMode.USB -> PrinterDiscovery.listUsbPrinters(this)
            PrinterMode.BLUETOOTH -> PrinterDiscovery.listBluetoothPrinters(this)
            PrinterMode.NETWORK -> {
                val all = linkedSetOf<String>()
                val currentEndpoint = print.networkEndpoint()
                if (currentEndpoint.isNotBlank()) all.add(currentEndpoint)
                val selectedEndpoint = print.selectedNetworkEndpoint.trim()
                if (selectedEndpoint.isNotBlank()) all.add(selectedEndpoint)
                appSettings.getRecentNetworkEndpoints(currentPrintProfile).forEach { all.add(it) }
                all.map { endpoint -> PrinterOption(id = endpoint, label = endpoint) }
            }
        }

        if (currentPrinterOptions.isEmpty()) {
            val message = when (mode) {
                PrinterMode.USB -> "No USB printers detected"
                PrinterMode.BLUETOOTH -> "No paired Bluetooth printers"
                PrinterMode.NETWORK -> "No saved network printers"
            }
            setPrinterDropdownItems(listOf(message), enabled = false)
            return
        }

        setPrinterDropdownItems(currentPrinterOptions.map { it.label }, enabled = true)
        val selectedId = when (mode) {
            PrinterMode.USB -> print.selectedUsbPrinterId
            PrinterMode.BLUETOOTH -> print.selectedBluetoothAddress
            PrinterMode.NETWORK -> print.selectedNetworkEndpoint
        }
        val selectedOption = currentPrinterOptions.firstOrNull { it.id == selectedId }
            ?: if (mode == PrinterMode.USB) {
                currentPrinterOptions.firstOrNull {
                    hasSameUsbVendorProduct(it.id, selectedId)
                }
            } else {
                null
            }
            ?: currentPrinterOptions.firstOrNull()
        if (selectedOption != null) {
            printerDeviceDropdown.setText(selectedOption.label, false)
            onPrinterSelected(selectedOption, updateDropdown = false)
        }
    }

    private fun onPrinterSelected(option: PrinterOption, updateDropdown: Boolean = true) {
        if (option.id.isBlank()) return
        val mode = appSettings.getPrintSettings(currentPrintProfile).mode
        when (mode) {
            PrinterMode.USB -> appSettings.setSelectedUsbPrinter(option.id, currentPrintProfile)
            PrinterMode.BLUETOOTH -> {
                appSettings.setSelectedBluetoothPrinter(option.id, currentPrintProfile)
                UsbEscPosPrinter(applicationContext, currentPrintProfile).warmupActivePrinterAsync()
            }
            PrinterMode.NETWORK -> {
                appSettings.setSelectedNetworkEndpoint(option.id, currentPrintProfile)
                val parsed = parseEndpoint(option.id)
                if (parsed != null) {
                    suppressNetworkFieldChange = true
                    networkHostInput.setText(parsed.first)
                    networkPortInput.setText(parsed.second.toString())
                    suppressNetworkFieldChange = false
                    appSettings.setNetworkHostPort(parsed.first, parsed.second, currentPrintProfile)
                }
            }
        }
        scheduleSettingsSync()
        if (updateDropdown) {
            printerDeviceDropdown.setText(option.label, false)
        }
    }

    private fun persistNetworkHostPort(addToRecent: Boolean): String {
        val host = networkHostInput.text?.toString()?.trim().orEmpty()
        val port = networkPortInput.text?.toString()?.trim()?.toIntOrNull()?.coerceIn(1, 65535) ?: 9100
        appSettings.setNetworkHostPort(host, port, currentPrintProfile)
        if (host.isBlank()) {
            scheduleSettingsSync()
            return ""
        }
        val endpoint = "$host:$port"
        appSettings.setSelectedNetworkEndpoint(endpoint, currentPrintProfile)
        if (addToRecent) {
            appSettings.addRecentNetworkEndpoint(endpoint, currentPrintProfile)
        }
        scheduleSettingsSync()
        return endpoint
    }

    private fun parseEndpoint(endpoint: String): Pair<String, Int>? {
        val text = endpoint.trim()
        if (text.isBlank()) return null
        val separatorIndex = text.lastIndexOf(':')
        if (separatorIndex <= 0 || separatorIndex == text.lastIndex) {
            return Pair(text, 9100)
        }
        val host = text.substring(0, separatorIndex).trim()
        val port = text.substring(separatorIndex + 1).trim().toIntOrNull()?.coerceIn(1, 65535) ?: 9100
        if (host.isBlank()) return null
        return Pair(host, port)
    }

    private fun hasSameUsbVendorProduct(leftId: String, rightId: String): Boolean {
        val left = parseUsbVendorProduct(leftId) ?: return false
        val right = parseUsbVendorProduct(rightId) ?: return false
        return left.first == right.first && left.second == right.second
    }

    private fun parseUsbVendorProduct(rawId: String): Pair<Int, Int>? {
        val tokens = rawId.split(':')
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (tokens.isEmpty()) return null
        val numbers = mutableListOf<Int>()
        for (token in tokens) {
            val value = token.toIntOrNull() ?: continue
            numbers.add(value)
            if (numbers.size == 2) break
        }
        if (numbers.size < 2) return null
        return Pair(numbers[0], numbers[1])
    }

    private fun updateKioskHint(state: KioskState? = null) {
        val enabled = appSettings.isHomeLauncherEnabled()
        val snapshot = state ?: KioskModeManager.currentState(this, enabled)
        val status = when {
            !enabled -> getString(R.string.kiosk_mode_status_off)
            !snapshot.deviceOwner -> getString(R.string.kiosk_mode_status_needs_owner)
            snapshot.inLockTask -> getString(R.string.kiosk_mode_status_enforced)
            snapshot.lockTaskPermitted -> getString(R.string.kiosk_mode_status_ready)
            else -> getString(R.string.kiosk_mode_status_partial)
        }
        homeAppHint.text = getString(
            R.string.home_app_hint_with_status,
            getString(R.string.home_app_hint),
            status
        )
    }

    private fun requestBluetoothPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val required = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            required.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            required.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (required.isNotEmpty()) {
            bluetoothPermissionLauncher.launch(required.toTypedArray())
        }
    }

    private fun runTestPrint() {
        val printer = UsbEscPosPrinter(applicationContext, currentPrintProfile)
        if (!printer.hasAnyPrinter()) {
            Toast.makeText(this, getString(R.string.test_print_no_printer), Toast.LENGTH_SHORT).show()
            return
        }
        if (!printer.hasPermissionForCurrentPrinter()) {
            Toast.makeText(this, getString(R.string.test_print_no_permission), Toast.LENGTH_SHORT).show()
            return
        }

        runCatching {
            ContextCompat.startForegroundService(this, Intent(this, PosBackgroundService::class.java))
        }

        val print = appSettings.getPrintSettings(currentPrintProfile)
        val profileLabel = appSettings.getPrinterRouteLabel(currentPrintProfile)
        val selectedPrinterLabel = printerDeviceDropdown.text?.toString()?.trim().orEmpty()
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val modeLabel = modeItems.firstOrNull { it.mode == print.mode }?.label ?: print.mode.storageValue

        val payload = buildString {
            appendLine("FlickpayPOS")
            appendLine("TEST PRINT")
            appendLine("------------------------------")
            appendLine("Profile: $profileLabel")
            appendLine("Mode: $modeLabel")
            if (selectedPrinterLabel.isNotBlank()) {
                appendLine("Printer: $selectedPrinterLabel")
            }
            appendLine("Paper: ${print.paperSize.label}")
            if (print.reversePrint) {
                appendLine("Orientation: Reversed")
            }
            appendLine("Time: $now")
            appendLine("------------------------------")
            appendLine("If you can read this,")
            appendLine("printer is configured correctly.")
        }

        testPrintButton.isEnabled = false
        Thread {
            // Use the same local hw_proxy path as kiosk/POS so test print reflects real runtime behavior.
            val ok = runCatching {
                SystemClock.sleep(120L)
                sendTestPrintViaLocalProxy(payload)
            }.getOrElse {
                runCatching { printer.printPlainText(payload) }.getOrDefault(false)
            }
            AppRuntimeLog.i(
                applicationContext,
                "Settings",
                "Test print profile=$currentPrintProfile result=$ok"
            )
            runOnUiThread {
                testPrintButton.isEnabled = true
                if (ok) {
                    Toast.makeText(this, getString(R.string.test_print_ok), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, getString(R.string.test_print_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun sendTestPrintViaLocalProxy(payload: String): Boolean {
        val currentKey = normalizeRouteKey(currentPrintProfile)
        val route = appSettings
            .getPrinterRoutes()
            .firstOrNull { normalizeRouteKey(it.key) == currentKey }
        val port = route?.localPort ?: 8070
        val rpcUrl = URL("http://127.0.0.1:$port/hw_proxy/default_printer_action")
        val bodyJson = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", System.currentTimeMillis())
            put("method", "call")
            put(
                "params",
                JSONObject().apply {
                    put(
                        "data",
                        JSONObject().apply {
                            put("action", "print_receipt")
                            put("receipt", payload)
                        }
                    )
                }
            )
        }.toString()
        val bodyBytes = bodyJson.toByteArray(Charsets.UTF_8)
        val connection = (rpcUrl.openConnection() as HttpURLConnection).apply {
            connectTimeout = 1800
            readTimeout = 5000
            requestMethod = "POST"
            instanceFollowRedirects = false
            useCaches = false
            doInput = true
            doOutput = true
            setRequestProperty("Connection", "close")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Origin", "https://app.flickpay.co.uk")
            setFixedLengthStreamingMode(bodyBytes.size)
        }

        return try {
            connection.outputStream.use { out ->
                out.write(bodyBytes)
                out.flush()
            }
            val status = runCatching { connection.responseCode }.getOrDefault(0)
            val responseText = runCatching {
                val stream = if (status in 200..399) connection.inputStream else connection.errorStream
                stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            }.getOrDefault("")
            if (status !in 200..299) {
                false
            } else {
                val root = runCatching { JSONObject(responseText) }.getOrNull() ?: return false
                if (root.has("error")) {
                    return false
                }
                when (val result = root.opt("result")) {
                    is Boolean -> result
                    null, JSONObject.NULL -> false
                    else -> true
                }
            }
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    private fun normalizeRouteKey(value: String?): String {
        return value?.trim()?.lowercase().orEmpty().ifBlank { AppSettings.DEFAULT_ROUTE_KEY }
    }

    private fun setPrinterDropdownItems(labels: List<String>, enabled: Boolean) {
        printerDeviceDropdown.setAdapter(
            ArrayAdapter(
                this,
                android.R.layout.simple_list_item_1,
                labels
            )
        )
        printerDeviceDropdown.isEnabled = enabled
        if (labels.isNotEmpty()) {
            printerDeviceDropdown.setText(labels.first(), false)
        } else {
            printerDeviceDropdown.setText("", false)
        }
    }

    private fun showSection(section: Section) {
        currentSection = section
        generalSection.visibility = View.GONE
        printSection.visibility = View.GONE
        logsSection.visibility = View.GONE
        aboutSection.visibility = View.GONE
        navGeneralItem.isSelected = false
        navPrintItem.isSelected = false
        navLogsItem.isSelected = false
        navAboutItem.isSelected = false
        navGeneralIndicator.visibility = View.GONE
        navPrintIndicator.visibility = View.GONE
        navLogsIndicator.visibility = View.GONE
        navAboutIndicator.visibility = View.GONE

        when (section) {
            Section.GENERAL -> {
                generalSection.visibility = View.VISIBLE
                pageTitle.text = getString(R.string.general_settings_title)
                navGeneralItem.isSelected = true
                navGeneralIndicator.visibility = View.VISIBLE
            }

            Section.PRINT -> {
                printSection.visibility = View.VISIBLE
                pageTitle.text = getString(R.string.print_settings_title)
                navPrintItem.isSelected = true
                navPrintIndicator.visibility = View.VISIBLE
            }

            Section.LOGS -> {
                logsSection.visibility = View.VISIBLE
                pageTitle.text = getString(R.string.logs_settings_title)
                navLogsItem.isSelected = true
                navLogsIndicator.visibility = View.VISIBLE
                loadLogs()
                pinLogsWindowTop()
            }

            Section.ABOUT -> {
                aboutSection.visibility = View.VISIBLE
                pageTitle.text = getString(R.string.about_settings_title)
                navAboutItem.isSelected = true
                navAboutIndicator.visibility = View.VISIBLE
            }
        }
    }

    private fun enableImmersiveMode() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun runManualUpdateCheck() {
        checkUpdatesButton.isEnabled = false
        checkUpdatesButton.text = getString(R.string.check_updates_checking)

        AppUpdateManager.checkForUpdateAsync { result ->
            checkUpdatesButton.isEnabled = true
            checkUpdatesButton.text = getString(R.string.check_updates_now)
            when (result) {
                is AppUpdateManager.CheckResult.UpdateAvailable -> {
                    AppRuntimeLog.i(applicationContext, "Settings", "Update available: ${result.update.versionName}")
                    AppUpdateManager.showUpdatePrompt(this, result.update)
                }
                is AppUpdateManager.CheckResult.UpToDate -> {
                    AppRuntimeLog.i(applicationContext, "Settings", "Update check: up to date")
                    Toast.makeText(this, getString(R.string.check_updates_up_to_date), Toast.LENGTH_SHORT).show()
                }
                is AppUpdateManager.CheckResult.Failure -> {
                    val message = result.message.ifBlank { getString(R.string.check_updates_failed) }
                    AppRuntimeLog.w(applicationContext, "Settings", "Update check failed: $message")
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private data class ModeItem(
        val label: String,
        val mode: PrinterMode,
    )

    private data class ProfileItem(
        val label: String,
        val routeKey: String,
    )

    private data class PaperItem(
        val label: String,
        val paperSize: PaperSize,
    )

    private enum class Section {
        GENERAL,
        PRINT,
        LOGS,
        ABOUT
    }
}
