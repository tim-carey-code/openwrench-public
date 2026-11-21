package org.openwrench.frontend.android.transport

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Build
import com.hoho.android.usbserial.driver.UsbSerialProber
import org.openwrench.nativecore.ErrorKind
import org.openwrench.nativecore.NativeDiagnosticException

data class BluetoothAdapterChoice(val address: String, val name: String)

data class UsbAdapterChoice(
    val deviceId: Int,
    val name: String,
    val vendorId: Int,
    val productId: Int,
    val hasPermission: Boolean,
)

internal fun requireBluetoothPermission(context: Context) {
    if (Build.VERSION.SDK_INT >= 31 &&
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
    ) {
        throw NativeDiagnosticException(ErrorKind.TRANSPORT, "Allow Nearby devices permission to use a paired Bluetooth adapter.")
    }
}

/** Lists already paired devices only. Never starts a scan, pairs, or opens a socket. */
@SuppressLint("MissingPermission")
fun listPairedBluetooth(context: Context): List<BluetoothAdapterChoice> {
    requireBluetoothPermission(context)
    try {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: throw NativeDiagnosticException(ErrorKind.TRANSPORT, "This device has no Bluetooth adapter.")
        if (!adapter.isEnabled) {
            throw NativeDiagnosticException(ErrorKind.TRANSPORT, "Turn on Bluetooth and pair the ELM327 adapter in Android Settings.")
        }
        return adapter.bondedDevices.map { BluetoothAdapterChoice(it.address, it.name ?: "Paired Bluetooth device") }
            .sortedWith(compareBy({ it.name }, { it.address }))
    } catch (error: SecurityException) {
        throw NativeDiagnosticException(ErrorKind.TRANSPORT, "Bluetooth permission was denied or revoked.", error)
    }
}

/** Enumerates supported USB serial drivers only. Does not request permission or open devices. */
fun listUsbDevices(context: Context): List<UsbAdapterChoice> {
    val manager = context.getSystemService(UsbManager::class.java) ?: return emptyList()
    return UsbSerialProber.getDefaultProber().findAllDrivers(manager).filter { it.ports.isNotEmpty() }.map { driver ->
        val device = driver.device
        UsbAdapterChoice(
            deviceId = device.deviceId,
            name = device.productName?.takeIf { it.isNotBlank() } ?: driver.javaClass.simpleName.removeSuffix("SerialDriver"),
            vendorId = device.vendorId,
            productId = device.productId,
            hasPermission = manager.hasPermission(device),
        )
    }.sortedWith(compareBy({ it.name }, { it.deviceId }))
}
