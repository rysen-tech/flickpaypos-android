package uk.flickpay.flickpaypos

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.SystemClock
import android.text.TextUtils
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class UsbEscPosPrinter(
    context: Context,
    private val profileKey: String = AppSettings.DEFAULT_ROUTE_KEY
) {

    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val appSettings = AppSettings(appContext)
    private val lock = Any()
    private var lastPrintPayloadHash: String? = null
    private var lastPrintAtMs: Long = 0L
    private var bluetoothSocket: BluetoothSocket? = null
    private var bluetoothOutput: OutputStream? = null
    private var bluetoothAddress: String = ""
    private var bluetoothLastUsedMs: Long = 0L
    private var bluetoothPreferInsecure: Boolean? = null
    @Volatile
    private var bluetoothWarmupInFlight: Boolean = false
    @Volatile
    private var bluetoothLastWarmupAttemptMs: Long = 0L

    fun warmupActivePrinterAsync() {
        val now = SystemClock.elapsedRealtime()
        if (bluetoothWarmupInFlight) return
        if (now - bluetoothLastWarmupAttemptMs < BLUETOOTH_WARMUP_MIN_INTERVAL_MS) return
        bluetoothLastWarmupAttemptMs = now
        bluetoothWarmupInFlight = true
        Thread {
            try {
                synchronized(lock) {
                    val print = appSettings.getPrintSettings(profileKey)
                    if (print.mode != PrinterMode.BLUETOOTH) return@synchronized
                    val adapter = BluetoothAdapter.getDefaultAdapter() ?: return@synchronized
                    if (!PrinterDiscovery.hasBluetoothConnectPermission(appContext)) return@synchronized
                    val device = findBluetoothTarget(print.selectedBluetoothAddress) ?: return@synchronized
                    runCatching { adapter.cancelDiscovery() }
                    closeBluetoothConnectionIfStale(SystemClock.elapsedRealtime())
                    val attemptOrder = when (bluetoothPreferInsecure) {
                        true -> listOf(true, false)
                        false -> listOf(false, true)
                        null -> listOf(true, false)
                    }
                    for (useInsecure in attemptOrder) {
                        if (ensureBluetoothConnection(device, insecure = useInsecure)) {
                            bluetoothPreferInsecure = useInsecure
                            bluetoothLastUsedMs = SystemClock.elapsedRealtime()
                            break
                        }
                    }
                }
            } finally {
                bluetoothWarmupInFlight = false
            }
        }.start()
    }

    fun hasAnyPrinter(): Boolean {
        val print = appSettings.getPrintSettings(profileKey)
        return when (print.mode) {
            PrinterMode.USB -> findTarget(
                requirePermission = false,
                preferredPrinterId = print.selectedUsbPrinterId
            ) != null

            PrinterMode.BLUETOOTH -> findBluetoothTarget(print.selectedBluetoothAddress) != null
            PrinterMode.NETWORK -> print.networkHost.isNotBlank() || print.selectedNetworkEndpoint.isNotBlank()
        }
    }

    fun hasPermissionForCurrentPrinter(): Boolean {
        val print = appSettings.getPrintSettings(profileKey)
        return when (print.mode) {
            PrinterMode.USB -> {
                val target = findTarget(
                    requirePermission = false,
                    preferredPrinterId = print.selectedUsbPrinterId
                ) ?: return false
                usbManager.hasPermission(target.device)
            }

            PrinterMode.BLUETOOTH -> {
                PrinterDiscovery.hasBluetoothConnectPermission(appContext) &&
                    findBluetoothTarget(print.selectedBluetoothAddress) != null
            }

            PrinterMode.NETWORK -> print.networkHost.isNotBlank() || print.selectedNetworkEndpoint.isNotBlank()
        }
    }

    fun currentPrinterName(): String {
        val print = appSettings.getPrintSettings(profileKey)
        return when (print.mode) {
            PrinterMode.USB -> {
                val target = findTarget(
                    requirePermission = false,
                    preferredPrinterId = print.selectedUsbPrinterId
                )
                target?.device?.deviceName.orEmpty()
            }

            PrinterMode.BLUETOOTH -> {
                val device = findBluetoothTarget(print.selectedBluetoothAddress)
                safeBluetoothName(device)
            }

            PrinterMode.NETWORK -> {
                val endpoint = print.networkEndpoint()
                if (endpoint.isNotBlank()) endpoint else print.selectedNetworkEndpoint.trim()
            }
        }
    }

    fun printPlainText(text: String): Boolean = synchronized(lock) {
        if (TextUtils.isEmpty(text)) return@synchronized false

        val bytes = text.toByteArray(Charsets.UTF_8)
        val payload = ByteArrayOutputStream().apply {
            write(byteArrayOf(0x1B, 0x40)) // init
            write(byteArrayOf(0x1B, 0x61, 0x00)) // align left
            write(bytes)
            write("\n\n\n".toByteArray(Charsets.UTF_8))
            write(byteArrayOf(0x1D, 0x56, 0x41, 0x10)) // cut
        }.toByteArray()

        if (isDuplicatePrintPayload(payload)) {
            return@synchronized true
        }
        writeToPrinter(payload)
    }

    fun printBase64Image(base64: String, paperWidthMm: Float = 80f): Boolean = synchronized(lock) {
        val clean = base64.replace("\\s".toRegex(), "")
        if (clean.isBlank()) return@synchronized false

        val decoded = runCatching { Base64.decode(clean, Base64.DEFAULT) }.getOrNull()
            ?: return@synchronized false
        val bmp = BitmapFactory.decodeByteArray(decoded, 0, decoded.size) ?: return@synchronized false

        val effectivePaperWidth = resolvePaperWidthMm(fallbackMm = paperWidthMm)
        printBitmap(bmp, effectivePaperWidth)
    }

    fun openCashDrawer(): Boolean = synchronized(lock) {
        val payload = byteArrayOf(
            0x1B, 0x40,             // init
            0x1B, 0x70, 0x00, 0x3C, 0x7F // pulse drawer pin 2
        )
        writeToPrinter(payload)
    }

    private fun printBitmap(bitmap: Bitmap, paperWidthMm: Float): Boolean {
        val print = appSettings.getPrintSettings(profileKey)
        val printMode = print.mode
        val sourceBitmap = if (print.reversePrint) rotateBitmap180(bitmap) else bitmap
        val widthDots = if (paperWidthMm <= 58f) 384 else 576
        val scaled = scaleBitmap(sourceBitmap, widthDots)
        val bandRows = if (printMode == PrinterMode.BLUETOOTH) {
            BLUETOOTH_RASTER_BAND_ROWS
        } else {
            DEFAULT_RASTER_BAND_ROWS
        }

        val raster = bitmapToEscPosRaster(scaled, threshold = 185, maxBandRows = bandRows)
        val payload = ByteArrayOutputStream().apply {
            write(byteArrayOf(0x1B, 0x40)) // init
            write(byteArrayOf(0x1B, 0x61, 0x01)) // align center
            write(raster)
            write(byteArrayOf(0x1B, 0x64, 0x03)) // feed n lines
            write(byteArrayOf(0x1D, 0x56, 0x41, 0x10)) // cut
        }.toByteArray()

        if (isDuplicatePrintPayload(payload)) {
            return true
        }
        return writeToPrinter(payload)
    }

    private fun rotateBitmap180(bitmap: Bitmap): Bitmap {
        return try {
            val matrix = Matrix().apply { postRotate(180f) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } catch (_: Throwable) {
            bitmap
        }
    }

    private fun resolvePaperWidthMm(fallbackMm: Float): Float {
        val configured = appSettings.getPrintSettings(profileKey).paperSize.widthMm
        return if (configured > 0f) configured else fallbackMm
    }

    private fun isDuplicatePrintPayload(payload: ByteArray): Boolean {
        val nowMs = SystemClock.elapsedRealtime()
        val payloadHash = sha256Hex(payload)
        val duplicate = (lastPrintPayloadHash == payloadHash) &&
            (nowMs - lastPrintAtMs <= DUPLICATE_PRINT_WINDOW_MS)
        lastPrintPayloadHash = payloadHash
        lastPrintAtMs = nowMs
        return duplicate
    }

    private fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        val out = StringBuilder(digest.size * 2)
        for (b in digest) {
            out.append(String.format("%02x", b.toInt() and 0xFF))
        }
        return out.toString()
    }

    private fun scaleBitmap(bitmap: Bitmap, widthDots: Int): Bitmap {
        val ratio = widthDots.toFloat() / max(1, bitmap.width)
        val height = max(1, (bitmap.height * ratio).roundToInt())
        return Bitmap.createScaledBitmap(bitmap, widthDots, height, true)
    }

    private fun bitmapToEscPosRaster(bitmap: Bitmap, threshold: Int, maxBandRows: Int): ByteArray {
        val width = bitmap.width - (bitmap.width % 8)
        val height = bitmap.height
        if (width <= 0 || height <= 0) return ByteArray(0)
        val sourceWidth = bitmap.width
        val bytesPerRow = width / 8
        val safeBandRows = max(8, maxBandRows.coerceAtMost(512))
        val pixels = IntArray(sourceWidth * height)
        bitmap.getPixels(pixels, 0, sourceWidth, 0, 0, sourceWidth, height)
        val xL = (bytesPerRow and 0xFF).toByte()
        val xH = ((bytesPerRow shr 8) and 0xFF).toByte()

        return ByteArrayOutputStream().apply {
            var yOffset = 0
            while (yOffset < height) {
                val bandRows = min(safeBandRows, height - yOffset)
                val bandData = ByteArray(bytesPerRow * bandRows)
                var idx = 0

                for (row in 0 until bandRows) {
                    val y = yOffset + row
                    val rowBase = y * sourceWidth
                    for (xByte in 0 until bytesPerRow) {
                        var b = 0
                        for (bit in 0..7) {
                            val x = xByte * 8 + bit
                            val pixel = pixels[rowBase + x]
                            val luminance =
                                (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000
                            if (luminance < threshold) {
                                b = b or (0x80 shr bit)
                            }
                        }
                        bandData[idx++] = b.toByte()
                    }
                }

                val yL = (bandRows and 0xFF).toByte()
                val yH = ((bandRows shr 8) and 0xFF).toByte()
                write(byteArrayOf(0x1D, 0x76, 0x30, 0x00, xL, xH, yL, yH))
                write(bandData)
                yOffset += bandRows
            }
        }.toByteArray()
    }

    private fun writeToPrinter(payload: ByteArray): Boolean {
        val print = appSettings.getPrintSettings(profileKey)
        return when (print.mode) {
            PrinterMode.USB -> writeToUsb(payload, print.selectedUsbPrinterId)
            PrinterMode.BLUETOOTH -> writeToBluetooth(payload, print.selectedBluetoothAddress)
            PrinterMode.NETWORK -> {
                val host = print.networkHost.trim()
                if (host.isNotBlank()) {
                    writeToNetwork(payload, host, print.networkPort)
                } else {
                    val parsed = parseEndpoint(print.selectedNetworkEndpoint)
                    if (parsed == null) false else writeToNetwork(payload, parsed.first, parsed.second)
                }
            }
        }
    }

    private fun writeToUsb(payload: ByteArray, preferredPrinterId: String): Boolean {
        val target = findTarget(requirePermission = true, preferredPrinterId = preferredPrinterId) ?: return false
        val connection = usbManager.openDevice(target.device) ?: return false

        return try {
            if (!connection.claimInterface(target.iface, true)) {
                false
            } else {
                bulkWrite(connection, target.outEndpoint, payload)
            }
        } finally {
            try {
                connection.releaseInterface(target.iface)
            } catch (_: Throwable) {
            }
            try {
                connection.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun writeToBluetooth(payload: ByteArray, preferredAddress: String): Boolean {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return false
        if (!PrinterDiscovery.hasBluetoothConnectPermission(appContext)) return false
        val device = findBluetoothTarget(preferredAddress) ?: return false
        runCatching { adapter.cancelDiscovery() }
        closeBluetoothConnectionIfStale(SystemClock.elapsedRealtime())

        val attemptOrder = when (bluetoothPreferInsecure) {
            true -> listOf(true, false)
            false -> listOf(false, true)
            null -> listOf(true, false)
        }
        for (useInsecure in attemptOrder) {
            if (!ensureBluetoothConnection(device, insecure = useInsecure)) {
                continue
            }
            val output = bluetoothOutput ?: continue
            val ok = writeBluetoothPayload(output, payload)
            if (ok) {
                bluetoothPreferInsecure = useInsecure
                bluetoothLastUsedMs = SystemClock.elapsedRealtime()
                return true
            }
            closeBluetoothConnection()
        }
        return false
    }

    private fun ensureBluetoothConnection(device: BluetoothDevice, insecure: Boolean): Boolean {
        val deviceAddress = runCatching { device.address }.getOrNull()?.trim().orEmpty()
        val existingSocket = bluetoothSocket
        val existingOutput = bluetoothOutput
        if (
            existingSocket != null &&
            existingOutput != null &&
            deviceAddress.isNotBlank() &&
            bluetoothAddress.equals(deviceAddress, ignoreCase = true) &&
            runCatching { existingSocket.isConnected }.getOrDefault(false)
        ) {
            return true
        }

        closeBluetoothConnection()
        return try {
            val socket = if (insecure) {
                runCatching { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) }
                    .getOrElse { device.createRfcommSocketToServiceRecord(SPP_UUID) }
            } else {
                device.createRfcommSocketToServiceRecord(SPP_UUID)
            }
            socket.connect()
            val output = socket.outputStream ?: return false
            bluetoothSocket = socket
            bluetoothOutput = output
            bluetoothAddress = deviceAddress
            bluetoothLastUsedMs = SystemClock.elapsedRealtime()
            true
        } catch (_: Throwable) {
            closeBluetoothConnection()
            false
        }
    }

    private fun closeBluetoothConnectionIfStale(nowMs: Long) {
        if (bluetoothSocket == null) return
        if (nowMs - bluetoothLastUsedMs > BLUETOOTH_IDLE_CLOSE_MS) {
            closeBluetoothConnection()
        }
    }

    private fun closeBluetoothConnection() {
        val output = bluetoothOutput
        val socket = bluetoothSocket
        bluetoothOutput = null
        bluetoothSocket = null
        bluetoothAddress = ""
        bluetoothLastUsedMs = 0L
        runCatching { output?.flush() }
        runCatching { output?.close() }
        runCatching { socket?.close() }
    }

    private fun writeBluetoothPayload(output: OutputStream, payload: ByteArray): Boolean {
        return try {
            var offset = 0
            var flushCounter = 0
            var paceCounter = 0
            while (offset < payload.size) {
                val chunk = min(BLUETOOTH_WRITE_CHUNK_BYTES, payload.size - offset)
                output.write(payload, offset, chunk)
                offset += chunk
                flushCounter++
                paceCounter++
                if (flushCounter >= BLUETOOTH_FLUSH_EVERY_N_CHUNKS || offset >= payload.size) {
                    output.flush()
                    flushCounter = 0
                }
                if (offset < payload.size && paceCounter >= BLUETOOTH_PACE_EVERY_N_CHUNKS) {
                    SystemClock.sleep(BLUETOOTH_WRITE_PAUSE_MS)
                    paceCounter = 0
                }
            }
            // Give slower BT printers a brief settle window before socket close.
            SystemClock.sleep(BLUETOOTH_FINAL_SETTLE_MS)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun writeToNetwork(payload: ByteArray, host: String, port: Int): Boolean {
        if (host.isBlank()) return false
        val safePort = if (port in 1..65535) port else 9100
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, safePort), 1500)
                socket.soTimeout = 2500
                socket.getOutputStream().use { out ->
                    out.write(payload)
                    out.flush()
                }
            }
            true
        }.getOrDefault(false)
    }

    private fun bulkWrite(
        connection: UsbDeviceConnection,
        endpoint: UsbEndpoint,
        data: ByteArray
    ): Boolean {
        var offset = 0
        while (offset < data.size) {
            val chunk = min(4096, data.size - offset)
            val sent = connection.bulkTransfer(endpoint, data, offset, chunk, 5000)
            if (sent <= 0) return false
            offset += sent
        }
        return true
    }

    private data class PrinterTarget(
        val device: UsbDevice,
        val iface: UsbInterface,
        val outEndpoint: UsbEndpoint
    )

    private fun findTarget(requirePermission: Boolean, preferredPrinterId: String): PrinterTarget? {
        val devices = usbManager.deviceList.values.sortedBy { it.deviceName }
        var bestScore = Int.MIN_VALUE
        var bestTarget: PrinterTarget? = null

        for (device in devices) {
            if (requirePermission && !usbManager.hasPermission(device)) {
                continue
            }

            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                val outEndpoint = findBulkOutEndpoint(iface) ?: continue

                val score = scoreCandidate(
                    device = device,
                    iface = iface,
                    preferredPrinterId = preferredPrinterId
                )
                if (score > bestScore) {
                    bestScore = score
                    bestTarget = PrinterTarget(device, iface, outEndpoint)
                }
            }
        }

        return bestTarget
    }

    private fun scoreCandidate(device: UsbDevice, iface: UsbInterface, preferredPrinterId: String): Int {
        var score = 0

        score += when (iface.interfaceClass) {
            UsbConstants.USB_CLASS_PRINTER -> 100
            UsbConstants.USB_CLASS_VENDOR_SPEC -> 40
            else -> 10
        }

        val meta = buildString {
            append(device.deviceName)
            append(" ")
            append(device.productName ?: "")
            append(" ")
            append(device.manufacturerName ?: "")
        }.lowercase()

        if (
            meta.contains("printer") ||
            meta.contains("thermal") ||
            meta.contains("epson") ||
            meta.contains("xprinter") ||
            meta.contains("pos")
        ) {
            score += 40
        }

        if (KNOWN_PRINTER_VENDORS.contains(device.vendorId)) {
            score += 30
        }

        if (preferredPrinterId.isNotBlank() && preferredPrinterId == PrinterDiscovery.usbId(device)) {
            score += 1000
        }

        return score
    }

    private fun findBulkOutEndpoint(iface: UsbInterface): UsbEndpoint? {
        for (e in 0 until iface.endpointCount) {
            val endpoint = iface.getEndpoint(e)
            if (endpoint.direction == UsbConstants.USB_DIR_OUT && endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                return endpoint
            }
        }
        return null
    }

    private fun findBluetoothTarget(preferredAddress: String): BluetoothDevice? {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return null
        if (!PrinterDiscovery.hasBluetoothConnectPermission(appContext)) return null
        val bonded = runCatching { adapter.bondedDevices?.toList().orEmpty() }.getOrDefault(emptyList())
        if (bonded.isEmpty()) return null

        val preferred = bonded.firstOrNull {
            val address = runCatching { it.address }.getOrNull()?.trim().orEmpty()
            preferredAddress.isNotBlank() && address.equals(preferredAddress.trim(), ignoreCase = true)
        }
        if (preferred != null) return preferred

        return bonded.sortedBy { safeBluetoothName(it).lowercase() }.firstOrNull()
    }

    private fun safeBluetoothName(device: BluetoothDevice?): String {
        if (device == null) return ""
        val name = runCatching { device.name }.getOrNull()?.trim().orEmpty()
        if (name.isNotBlank()) return name
        return runCatching { device.address }.getOrNull()?.trim().orEmpty()
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

    companion object {
        // Ignore immediate duplicate payload replays caused by transport retries.
        private const val DUPLICATE_PRINT_WINDOW_MS = 3000L
        private const val DEFAULT_RASTER_BAND_ROWS = 256
        private const val BLUETOOTH_RASTER_BAND_ROWS = 192
        private const val BLUETOOTH_WRITE_CHUNK_BYTES = 3072
        private const val BLUETOOTH_WRITE_PAUSE_MS = 1L
        private const val BLUETOOTH_PACE_EVERY_N_CHUNKS = 16
        private const val BLUETOOTH_FLUSH_EVERY_N_CHUNKS = 32
        private const val BLUETOOTH_FINAL_SETTLE_MS = 140L
        private const val BLUETOOTH_IDLE_CLOSE_MS = 180_000L
        private const val BLUETOOTH_WARMUP_MIN_INTERVAL_MS = 1200L
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private val KNOWN_PRINTER_VENDORS = setOf(
            0x04B8, // Epson
            0x0519, // Star Micronics
            0x1504, // Bixolon
            0x0416, // Citizen
            0x28E9, // Xprinter (common)
            0x0483, // Xprinter/STM based devices seen in field
        )
    }
}
