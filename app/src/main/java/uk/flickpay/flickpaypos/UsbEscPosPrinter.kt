package uk.flickpay.flickpaypos

import android.annotation.SuppressLint
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
import java.nio.charset.Charset
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
    private var warmupInFlight: Boolean = false
    @Volatile
    private var lastWarmupAttemptMs: Long = 0L
    @Volatile
    private var runtimeEscposPrimed: Boolean = false

    @SuppressLint("MissingPermission")
    fun warmupActivePrinterAsync() {
        val now = SystemClock.elapsedRealtime()
        if (warmupInFlight) return
        if (now - lastWarmupAttemptMs < WARMUP_MIN_INTERVAL_MS) return
        lastWarmupAttemptMs = now
        warmupInFlight = true
        Thread {
            try {
                synchronized(lock) lockSync@{
                    synchronized(GLOBAL_TRANSPORT_LOCK) transportSync@{
                        val print = appSettings.getPrintSettings(profileKey)
                        when (print.mode) {
                            PrinterMode.BLUETOOTH -> {
                                val adapter = PrinterDiscovery.getBluetoothAdapter(appContext) ?: return@transportSync
                                if (!PrinterDiscovery.hasBluetoothConnectPermission(appContext)) return@transportSync
                                val device = findBluetoothTarget(print.selectedBluetoothAddress) ?: return@transportSync
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

                            PrinterMode.USB -> {
                                val target = findTarget(
                                    requirePermission = true,
                                    preferredPrinterId = print.selectedUsbPrinterId
                                ) ?: return@transportSync
                                val connection = usbManager.openDevice(target.device) ?: return@transportSync
                                try {
                                    if (connection.claimInterface(target.iface, true)) {
                                        runCatching { connection.releaseInterface(target.iface) }
                                    }
                                } finally {
                                    runCatching { connection.close() }
                                }
                            }

                            PrinterMode.NETWORK -> {
                                val endpoint = if (print.networkHost.isNotBlank()) {
                                    Pair(print.networkHost.trim(), print.networkPort)
                                } else {
                                    parseEndpoint(print.selectedNetworkEndpoint)
                                } ?: return@transportSync
                                val host = endpoint.first.trim()
                                if (host.isBlank()) return@transportSync
                                val safePort = endpoint.second.coerceIn(1, 65535)
                                runCatching {
                                    Socket().use { socket ->
                                        socket.connect(
                                            InetSocketAddress(host, safePort),
                                            NETWORK_WARMUP_CONNECT_TIMEOUT_MS
                                        )
                                        socket.soTimeout = NETWORK_WARMUP_SO_TIMEOUT_MS
                                    }
                                }
                            }
                        }
                    }
                }
            } finally {
                warmupInFlight = false
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
            write(byteArrayOf(0x1B, 0x32)) // default line spacing
            write(byteArrayOf(0x1B, 0x4D, 0x00)) // font A
            write(bytes)
            write(byteArrayOf(0x1B, 0x64, 0x01)) // feed 1 line
            write(CUT_WITH_FEED_COMMAND) // cut (function B + small feed)
        }.toByteArray()

        if (isDuplicatePrintPayload(payload)) {
            return@synchronized true
        }
        writeToPrinter(payload)
    }

    fun printEscposText(text: String): Boolean = synchronized(lock) {
        if (TextUtils.isEmpty(text)) return@synchronized false

        // Some firmware can drop the first printable line after cold start/reload.
        // Prime the active transport once per runtime with non-printing ESC/POS init bytes.
        if (!runtimeEscposPrimed) {
            val primed = writeToPrinter(PRIME_ESC_POS_SEQUENCE)
            if (primed) {
                runtimeEscposPrimed = true
                SystemClock.sleep(20)
            }
        }

        // CP437 is the most broadly supported ESC/POS table on generic printers and maps '£' reliably.
        val bytes = encodeEscposPayload(text)
        val payload = ByteArrayOutputStream().apply {
            write(byteArrayOf(0x1B, 0x40)) // init
            write(byteArrayOf(0x1B, 0x74, ESC_POS_CODE_TABLE_CP437)) // select CP437 code table
            write(byteArrayOf(0x1B, 0x4D, 0x00)) // force Font A (larger/more readable)
            write(byteArrayOf(0x1B, 0x32)) // default line spacing
            write(bytes)
            write(byteArrayOf(0x1B, 0x64, 0x01)) // feed 1 line
            write(CUT_WITH_FEED_COMMAND) // cut (function B + small feed)
        }.toByteArray()

        if (isDuplicatePrintPayload(payload)) {
            return@synchronized true
        }
        writeToPrinter(payload)
    }

    // Keep text output compatible with CP437 while allowing raw ESC/POS binary blocks
    // to pass through byte-perfect:
    // - GS v 0 (raster image)
    // - GS ( k (native QR commands)
    private fun encodeEscposPayload(text: String): ByteArray {
        if (text.isEmpty()) return ByteArray(0)
        val out = ByteArrayOutputStream(text.length * 2)
        var i = 0
        while (i < text.length) {
            if (
                i + 8 <= text.length &&
                text[i].code == 0x1D &&
                text[i + 1].code == 0x76 &&
                text[i + 2].code == 0x30
            ) {
                val bytesPerRow = (text[i + 4].code and 0xFF) or ((text[i + 5].code and 0xFF) shl 8)
                val rows = (text[i + 6].code and 0xFF) or ((text[i + 7].code and 0xFF) shl 8)
                val dataLen = bytesPerRow * rows
                val end = i + 8 + dataLen
                if (bytesPerRow > 0 && rows > 0 && end <= text.length) {
                    for (k in i until end) {
                        out.write(text[k].code and 0xFF)
                    }
                    i = end
                    continue
                }
            }

            // Native QR command block:
            // GS ( k pL pH ...[pL + 256*pH bytes]
            if (
                i + 5 <= text.length &&
                text[i].code == 0x1D &&
                text[i + 1].code == 0x28 &&
                text[i + 2].code == 0x6B
            ) {
                val dataLen = (text[i + 3].code and 0xFF) or ((text[i + 4].code and 0xFF) shl 8)
                val end = i + 5 + dataLen
                if (dataLen > 0 && end <= text.length) {
                    for (k in i until end) {
                        out.write(text[k].code and 0xFF)
                    }
                    i = end
                    continue
                }
            }

            // ESC * bit-image block:
            // ESC * m nL nH [data]
            // m=0,1 => 8-dot image (1 byte per column)
            // m=32,33 => 24-dot image (3 bytes per column)
            if (
                i + 5 <= text.length &&
                text[i].code == 0x1B &&
                text[i + 1].code == 0x2A
            ) {
                val mode = text[i + 2].code and 0xFF
                val cols = (text[i + 3].code and 0xFF) or ((text[i + 4].code and 0xFF) shl 8)
                val bytesPerCol = when (mode) {
                    0, 1 -> 1
                    32, 33 -> 3
                    else -> 0
                }
                if (cols > 0 && bytesPerCol > 0) {
                    val dataLen = cols * bytesPerCol
                    val end = i + 5 + dataLen
                    if (end <= text.length) {
                        for (k in i until end) {
                            out.write(text[k].code and 0xFF)
                        }
                        i = end
                        continue
                    }
                }
            }

            val ch = text[i]
            // Preserve ESC/POS control bytes exactly.
            if (ch.code in 0x00..0x1F || ch.code == 0x7F) {
                out.write(ch.code and 0xFF)
                i += 1
                continue
            }
            when (ch) {
                '£' -> out.write(0x9C) // CP437 pound sign
                else -> {
                    val encoded = ch.toString().toByteArray(ESC_POS_TEXT_CHARSET)
                    if (encoded.isNotEmpty()) {
                        out.write(encoded[0].toInt() and 0xFF)
                    } else {
                        out.write('?'.code)
                    }
                }
            }
            i += 1
        }
        return out.toByteArray()
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
        val (widthDots, imageMode) = resolveImageProfile(print, paperWidthMm)
        val scaled = scaleBitmap(sourceBitmap, widthDots)
        val bandRows = if (printMode == PrinterMode.BLUETOOTH) {
            BLUETOOTH_RASTER_BAND_ROWS
        } else {
            DEFAULT_RASTER_BAND_ROWS
        }

        val imagePayload = when (imageMode) {
            ImageCommandMode.RASTER -> bitmapToEscPosRaster(
                scaled,
                threshold = 185,
                maxBandRows = bandRows
            )
            ImageCommandMode.BIT_IMAGE_COMPAT -> bitmapToEscPosBitImage24(
                scaled,
                threshold = 185
            )
        }
        val payload = ByteArrayOutputStream().apply {
            write(byteArrayOf(0x1B, 0x40)) // init
            if (imageMode == ImageCommandMode.RASTER) {
                write(escPosSetPrintArea(widthDots))
            }
            // Keep alignment deterministic; bitmap is centered in-pixel before rasterizing.
            write(byteArrayOf(0x1B, 0x61, 0x00)) // align left
            write(imagePayload)
            write(byteArrayOf(0x1B, 0x64, 0x01)) // feed 1 line
            write(CUT_WITH_FEED_COMMAND) // cut (function B + small feed)
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

    private fun resolveImageProfile(print: PrintSettings, paperWidthMm: Float): Pair<Int, ImageCommandMode> {
        val widthDots = if (paperWidthMm <= 58f) 384 else 576
        if (print.mode != PrinterMode.USB) {
            return Pair(widthDots, ImageCommandMode.RASTER)
        }

        val vendorId = preferredUsbVendorId(print.selectedUsbPrinterId)
        return if (vendorId == EPSON_VENDOR_ID) {
            Pair(widthDots, ImageCommandMode.RASTER)
        } else {
            // Clone/generic ESC/POS firmware is often more stable with ESC * compatibility mode.
            Pair(widthDots, ImageCommandMode.BIT_IMAGE_COMPAT)
        }
    }

    private fun preferredUsbVendorId(preferredPrinterId: String): Int? {
        val target = findTarget(
            requirePermission = false,
            preferredPrinterId = preferredPrinterId
        ) ?: return null
        return target.device.vendorId
    }

    private fun escPosSetPrintArea(widthDots: Int): ByteArray {
        val safeWidth = widthDots.coerceIn(1, 65535)
        val wL = (safeWidth and 0xFF).toByte()
        val wH = ((safeWidth shr 8) and 0xFF).toByte()
        return byteArrayOf(
            0x1D, 0x4C, 0x00, 0x00, // GS L nL nH: left margin = 0
            0x1D, 0x57, wL, wH      // GS W nL nH: print area width
        )
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

    private fun bitmapToEscPosBitImage24(bitmap: Bitmap, threshold: Int): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return ByteArray(0)

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        return ByteArrayOutputStream().apply {
            // ESC * 33 => 24-dot double-density bit image (widely supported by generic ESC/POS).
            // Split horizontally to <=255 columns for compatibility with clone firmware.
            write(byteArrayOf(0x1B, 0x33, 0x18)) // line spacing 24
            var y = 0
            while (y < height) {
                var xOffset = 0
                while (xOffset < width) {
                    val cols = min(255, width - xOffset)
                    val nL = (cols and 0xFF).toByte()
                    val nH = ((cols shr 8) and 0xFF).toByte()
                    write(byteArrayOf(0x1B, 0x2A, 0x21, nL, nH))

                    for (x in 0 until cols) {
                        val xx = xOffset + x
                        for (slice in 0 until 3) {
                            var b = 0
                            for (bit in 0 until 8) {
                                val yy = y + (slice * 8) + bit
                                if (yy >= height) continue
                                val pixel = pixels[yy * width + xx]
                                val luminance =
                                    (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000
                                if (luminance < threshold) {
                                    b = b or (0x80 shr bit)
                                }
                            }
                            write(b)
                        }
                    }
                    xOffset += cols
                }
                write(0x0A) // LF
                y += 24
            }
            // Restore default line spacing.
            write(byteArrayOf(0x1B, 0x32))
        }.toByteArray()
    }

    private fun writeToPrinter(payload: ByteArray): Boolean {
        synchronized(GLOBAL_TRANSPORT_LOCK) {
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
    }

    private fun writeToUsb(payload: ByteArray, preferredPrinterId: String): Boolean {
        val preferred = preferredPrinterId.trim()
        repeat(USB_WRITE_ROUNDS) { round ->
            val candidates = findTargets(
                requirePermission = true,
                preferredPrinterId = preferred
            )
            if (candidates.isNotEmpty()) {
                for (target in candidates) {
                    val ok = writeToUsbTarget(target, payload)
                    if (ok) {
                        val winnerId = PrinterDiscovery.usbId(target.device)
                        if (winnerId.isNotBlank() && winnerId != preferred) {
                            // Re-pin the active USB printer after hot-plug swaps.
                            appSettings.setSelectedUsbPrinter(winnerId, profileKey)
                        }
                        return true
                    }
                }
            }
            if (round < USB_WRITE_ROUNDS - 1) {
                SystemClock.sleep(USB_RETRY_SLEEP_MS)
            }
        }
        return false
    }

    private fun writeToUsbTarget(target: PrinterTarget, payload: ByteArray): Boolean {
        repeat(USB_TARGET_RETRIES) { attempt ->
            val connection = usbManager.openDevice(target.device)
            if (connection == null) {
                if (attempt < USB_TARGET_RETRIES - 1) {
                    SystemClock.sleep(USB_RETRY_SLEEP_MS)
                }
                return@repeat
            }

            val ok = try {
                if (!connection.claimInterface(target.iface, true)) {
                    false
                } else {
                    // Fast path: write immediately to avoid pre-print hesitation.
                    // Fallback: if direct write fails, send a tiny wake/init prime and retry once.
                    val directWriteOk = bulkWrite(connection, target.outEndpoint, payload)
                    if (directWriteOk) {
                        true
                    } else {
                        primeUsbConnection(connection, target.outEndpoint)
                        SystemClock.sleep(USB_AFTER_PRIME_DELAY_MS)
                        bulkWrite(connection, target.outEndpoint, payload)
                    }
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

            if (ok) return true
            if (attempt < USB_TARGET_RETRIES - 1) {
                SystemClock.sleep(USB_RETRY_SLEEP_MS)
            }
        }
        return false
    }

    @SuppressLint("MissingPermission")
    private fun writeToBluetooth(payload: ByteArray, preferredAddress: String): Boolean {
        val adapter = PrinterDiscovery.getBluetoothAdapter(appContext) ?: return false
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

    @SuppressLint("MissingPermission")
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
        if (data.isEmpty()) return true
        var offset = 0
        while (offset < data.size) {
            val chunk = min(4096, data.size - offset)
            var sent = -1
            var attempts = 0
            while (attempts < USB_BULK_TRANSFER_RETRIES && sent <= 0) {
                sent = connection.bulkTransfer(endpoint, data, offset, chunk, USB_BULK_TRANSFER_TIMEOUT_MS)
                if (sent > 0) break
                attempts++
                if (attempts < USB_BULK_TRANSFER_RETRIES) {
                    SystemClock.sleep(USB_RETRY_SLEEP_MS)
                }
            }
            if (sent <= 0) return false
            offset += sent
        }
        return true
    }

    private fun primeUsbConnection(connection: UsbDeviceConnection, endpoint: UsbEndpoint) {
        runCatching {
            val sent = connection.bulkTransfer(
                endpoint,
                USB_PRIME_SEQUENCE,
                0,
                USB_PRIME_SEQUENCE.size,
                1000
            )
            if (sent > 0) {
                SystemClock.sleep(USB_PRIME_DELAY_MS)
            }
        }
    }

    private data class PrinterTarget(
        val device: UsbDevice,
        val iface: UsbInterface,
        val outEndpoint: UsbEndpoint
    )

    private enum class ImageCommandMode {
        RASTER,
        BIT_IMAGE_COMPAT
    }

    private fun findTarget(requirePermission: Boolean, preferredPrinterId: String): PrinterTarget? {
        return findTargets(requirePermission, preferredPrinterId).firstOrNull()
    }

    private fun findTargets(requirePermission: Boolean, preferredPrinterId: String): List<PrinterTarget> {
        val devices = usbManager.deviceList.values.sortedBy { it.deviceName }
        val preferred = preferredPrinterId.trim()
        val preferredVendorProduct = parsePreferredVendorProduct(preferred)
        val scoredTargets = mutableListOf<Pair<Int, PrinterTarget>>()

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
                    preferredPrinterId = preferred,
                    preferredVendorProduct = preferredVendorProduct
                )
                scoredTargets += score to PrinterTarget(device, iface, outEndpoint)
            }
        }

        return scoredTargets
            .sortedByDescending { it.first }
            .map { it.second }
    }

    private fun parsePreferredVendorProduct(preferredPrinterId: String): Pair<Int, Int>? {
        if (preferredPrinterId.isBlank()) return null
        val tokens = preferredPrinterId.split(':')
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

    private fun scoreCandidate(
        device: UsbDevice,
        iface: UsbInterface,
        preferredPrinterId: String,
        preferredVendorProduct: Pair<Int, Int>?
    ): Int {
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

        if (
            preferredVendorProduct != null &&
            preferredVendorProduct.first == device.vendorId &&
            preferredVendorProduct.second == device.productId
        ) {
            score += 350
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

    @SuppressLint("MissingPermission")
    private fun findBluetoothTarget(preferredAddress: String): BluetoothDevice? {
        val adapter = PrinterDiscovery.getBluetoothAdapter(appContext) ?: return null
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

    @SuppressLint("MissingPermission")
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
        private const val EPSON_VENDOR_ID = 0x04B8
        private const val DEFAULT_RASTER_BAND_ROWS = 256
        private const val BLUETOOTH_RASTER_BAND_ROWS = 192
        private const val BLUETOOTH_WRITE_CHUNK_BYTES = 3072
        private const val BLUETOOTH_WRITE_PAUSE_MS = 1L
        private const val BLUETOOTH_PACE_EVERY_N_CHUNKS = 16
        private const val BLUETOOTH_FLUSH_EVERY_N_CHUNKS = 32
        private const val BLUETOOTH_FINAL_SETTLE_MS = 140L
        private const val USB_PRIME_DELAY_MS = 35L
        private const val USB_AFTER_PRIME_DELAY_MS = 20L
        private const val USB_RETRY_SLEEP_MS = 120L
        private const val USB_WRITE_ROUNDS = 2
        private const val USB_TARGET_RETRIES = 2
        private const val USB_BULK_TRANSFER_RETRIES = 3
        private const val USB_BULK_TRANSFER_TIMEOUT_MS = 5000
        private val USB_PRIME_SEQUENCE = byteArrayOf(
            0x1B, 0x40,             // init
            0x1B, 0x61, 0x00        // left align
        )
        private const val BLUETOOTH_IDLE_CLOSE_MS = 180_000L
        private const val WARMUP_MIN_INTERVAL_MS = 1200L
        private const val NETWORK_WARMUP_CONNECT_TIMEOUT_MS = 700
        private const val NETWORK_WARMUP_SO_TIMEOUT_MS = 800
        // Epson guidance: after cut, ~1 mm feed before next print gives best results.
        // GS V Function B with n=8 on 203 dpi models is ~1 mm feed.
        private val CUT_WITH_FEED_COMMAND = byteArrayOf(0x1D, 0x56, 0x42, 0x08)
        private val PRIME_ESC_POS_SEQUENCE = byteArrayOf(
            0x1B, 0x40,       // initialize
            0x1B, 0x61, 0x00  // align left
        )
        private const val ESC_POS_CODE_TABLE_CP437: Byte = 0x00
        private val ESC_POS_TEXT_CHARSET: Charset =
            runCatching { Charset.forName("CP437") }.getOrElse { Charsets.ISO_8859_1 }
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private val KNOWN_PRINTER_VENDORS = setOf(
            0x04B8, // Epson
            0x0519, // Star Micronics
            0x1504, // Bixolon
            0x0416, // Citizen
            0x28E9, // Xprinter (common)
            0x0483, // Xprinter/STM based devices seen in field
        )
        // Service + settings can create separate printer instances; serialize transport access globally.
        private val GLOBAL_TRANSPORT_LOCK = Any()
    }
}
