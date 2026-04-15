package uk.flickpay.flickpaypos

import android.annotation.SuppressLint
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import kotlin.math.max

data class PrinterOption(
    val id: String,
    val label: String,
)

object PrinterDiscovery {

    fun listUsbPrinters(context: Context): List<PrinterOption> {
        val usbManager = context.applicationContext.getSystemService(Context.USB_SERVICE) as UsbManager
        val options = mutableListOf<PrinterOption>()
        for (device in usbManager.deviceList.values.sortedBy { it.deviceName }) {
            val hasCandidateInterface = (0 until device.interfaceCount).any { i ->
                findBulkOutEndpoint(device.getInterface(i))
            }
            if (!hasCandidateInterface) continue
            val id = usbId(device)
            val label = buildUsbLabel(device, usbManager.hasPermission(device))
            options.add(PrinterOption(id = id, label = label))
        }
        return options
    }

    @SuppressLint("MissingPermission")
    fun listBluetoothPrinters(context: Context): List<PrinterOption> {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return emptyList()
        if (!hasBluetoothConnectPermission(context)) return emptyList()
        val bonded = runCatching { adapter.bondedDevices?.toList().orEmpty() }.getOrDefault(emptyList())
        return bonded
            .filter { looksLikePrinter(it) }
            .sortedBy { safeBluetoothName(it).lowercase() }
            .map { device ->
                val name = safeBluetoothName(device)
                val address = device.address.orEmpty()
                PrinterOption(
                    id = address,
                    label = if (address.isBlank()) name else "$name ($address)"
                )
            }
    }

    fun usbId(device: UsbDevice): String {
        val serial = safeUsbSerial(device)
        return if (serial.isNotBlank()) {
            "${device.vendorId}:${device.productId}:$serial"
        } else {
            "${device.vendorId}:${device.productId}"
        }
    }

    fun hasBluetoothConnectPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    private fun buildUsbLabel(device: UsbDevice, hasPermission: Boolean): String {
        val manufacturer = device.manufacturerName?.trim().orEmpty()
        val product = device.productName?.trim().orEmpty()
        val base = listOf(manufacturer, product)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { "USB Printer" }
        val permissionHint = if (hasPermission) "" else " (Allow USB access)"
        return "$base${permissionHint}"
    }

    @SuppressLint("MissingPermission")
    private fun safeUsbSerial(device: UsbDevice): String {
        val serial = runCatching { device.serialNumber }.getOrNull()?.trim().orEmpty()
        if (serial.isBlank()) return ""
        val normalized = serial.replace(":", "-")
        if (
            normalized.equals("null", ignoreCase = true) ||
            normalized.equals("unknown", ignoreCase = true)
        ) {
            return ""
        }
        return normalized
    }

    @SuppressLint("MissingPermission")
    private fun looksLikePrinter(device: BluetoothDevice): Boolean {
        val majorClass = runCatching { device.bluetoothClass?.majorDeviceClass }.getOrNull()
        if (majorClass == BluetoothClass.Device.Major.IMAGING) return true

        val text = buildString {
            append(safeBluetoothName(device))
            append(" ")
            append(device.address.orEmpty())
        }.lowercase()

        return text.contains("printer") ||
            text.contains("thermal") ||
            text.contains("epson") ||
            text.contains("xprinter") ||
            text.contains("pos")
    }

    @SuppressLint("MissingPermission")
    private fun safeBluetoothName(device: BluetoothDevice): String {
        val name = runCatching { device.name }.getOrNull()?.trim().orEmpty()
        if (name.isNotBlank()) return name
        return "Bluetooth Printer"
    }

    private fun findBulkOutEndpoint(iface: UsbInterface): Boolean {
        val endpointCount = max(0, iface.endpointCount)
        for (i in 0 until endpointCount) {
            val endpoint = iface.getEndpoint(i)
            if (
                endpoint.direction == UsbConstants.USB_DIR_OUT &&
                endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK
            ) {
                return true
            }
        }
        return false
    }
}
