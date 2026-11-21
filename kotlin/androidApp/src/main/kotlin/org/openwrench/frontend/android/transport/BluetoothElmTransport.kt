package org.openwrench.frontend.android.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.SystemClock
import org.openwrench.nativecore.ErrorKind
import org.openwrench.nativecore.NativeDiagnosticException
import org.openwrench.nativecore.NativeTransport
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Paired Bluetooth Classic ELM327 SPP connection. Call open/exchange off the main thread. */
class BluetoothElmTransport(context: Context, private val address: String) : NativeTransport {
    private val context = context.applicationContext
    private val operations = Any()
    private val socket = AtomicReference<BluetoothSocket?>(null)
    private val lifecycle = Any()
    private var generation = 0L
    private val pacer = ExchangePacer(SystemClock::elapsedRealtime)
    private val releases = PendingReleases<BluetoothSocket>(retryable = false) { it.close() }

    @SuppressLint("MissingPermission")
    override fun open() = synchronized(operations) {
        releases.requireReleased()
        requireBluetoothPermission(context)
        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            throw NativeDiagnosticException(ErrorKind.TRANSPORT, "Choose a valid paired Bluetooth address.")
        }
        val openingGeneration = synchronized(lifecycle) {
            if (socket.get() != null) throw NativeDiagnosticException(ErrorKind.TRANSPORT, "Bluetooth adapter is already open.")
            generation
        }
        var candidate: BluetoothSocket? = null
        var deadline: IoDeadline? = null
        try {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
                ?: throw NativeDiagnosticException(ErrorKind.TRANSPORT, "This device has no Bluetooth adapter.")
            if (!adapter.isEnabled) throw NativeDiagnosticException(ErrorKind.TRANSPORT, "Bluetooth is turned off.")
            val device = adapter.getRemoteDevice(address)
            if (device.bondState != BluetoothDevice.BOND_BONDED) {
                throw NativeDiagnosticException(ErrorKind.TRANSPORT, "Pair this adapter in Android Settings before connecting.")
            }
            // Deliberately no discovery: Android 12+ paired-device access requires CONNECT only.
            val connecting = device.createRfcommSocketToServiceRecord(SPP_UUID)
            candidate = connecting
            synchronized(lifecycle) {
                if (generation != openingGeneration) throw IOException("Connection was cancelled")
                socket.set(connecting)
            }
            deadline = IoDeadline(12_000, "Bluetooth connection") { closeSocket(connecting) }
            connecting.connect()
            if (socket.get() !== connecting) throw IOException("Connection was cancelled")
            deadline.finish()
        } catch (error: Exception) {
            candidate?.let(::closeSocket)
            deadline?.checkTimeout(error)
            if (error is NativeDiagnosticException) throw error
            throw NativeDiagnosticException(ErrorKind.TRANSPORT, "Could not connect to the paired Bluetooth ELM327 adapter: ${error.message ?: "connection closed"}", error)
        } finally {
            deadline?.finish()
        }
    }

    override fun exchange(command: String, timeoutMillis: Long): String = synchronized(operations) {
        val bytes = frameElmCommand(command)
        require(timeoutMillis in 1..60_000)
        val active = socket.get() ?: throw NativeDiagnosticException(ErrorKind.TRANSPORT, "Bluetooth adapter is not connected.")
        val deadline = IoDeadline(timeoutMillis, "Bluetooth command") { closeSocket(active) }
        var prompt = false
        try {
            pacer.beforeWrite(command)
            if (socket.get() !== active) throw IOException("Connection was closed")
            val input = active.inputStream
            val buffer = ByteArray(512)
            var discarded = 0
            while (input.available() > 0) {
                val count = input.read(buffer, 0, minOf(buffer.size, input.available()))
                if (count < 0) throw IOException("Adapter disconnected")
                discarded += count
                if (discarded > MAX_ELM_RESPONSE_BYTES) {
                    throw NativeDiagnosticException(ErrorKind.MALFORMED_RESPONSE, "Adapter input did not become idle before sending a command.")
                }
            }
            active.outputStream.write(bytes)
            active.outputStream.flush()
            val frame = ElmFrameAccumulator()
            while (true) {
                val count = input.read(buffer)
                if (count < 0) throw IOException("Adapter disconnected before its prompt")
                val response = frame.append(buffer, count)
                if (response != null) {
                    deadline.finish()
                    prompt = true
                    return@synchronized response
                }
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        } catch (error: Exception) {
            closeSocket(active)
            deadline.checkTimeout(error)
            if (error is InterruptedException) Thread.currentThread().interrupt()
            if (error is NativeDiagnosticException) throw error
            throw NativeDiagnosticException(ErrorKind.TRANSPORT, "Bluetooth communication failed: ${error.message ?: "connection closed"}", error)
        } finally {
            pacer.completed(command, prompt)
            deadline.finish()
        }
    }

    /** Never waits for the exchange lock: close aborts blocked connect/read/write calls. */
    override fun close() {
        val active = synchronized(lifecycle) {
            generation++
            socket.getAndSet(null)
        }
        active?.let(releases::attempt)
        releases.retry()
    }

    private fun closeSocket(active: BluetoothSocket) {
        socket.compareAndSet(active, null)
        releases.attempt(active)
    }

    companion object {
        private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
    }
}
