package org.openwrench.frontend.android.transport

import android.content.Context
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.SystemClock
import com.hoho.android.usbserial.driver.SerialTimeoutException
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import org.openwrench.nativecore.ErrorKind
import org.openwrench.nativecore.NativeDiagnosticException
import org.openwrench.nativecore.NativeFailureStage
import org.openwrench.nativecore.NativeTransport
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

/** Android USB host ELM-compatible serial adapters, including OHP/FTDI, with bounded baud discovery. */
class UsbElmTransport(
    context: Context,
    private val deviceId: Int,
    private val baudRate: Int = UsbConnectionSettings.AUTO_BAUD,
    private val controlLines: UsbControlLines = UsbControlLines.ASSERTED,
    private val diagnostics: UsbDiagnostics = UsbDiagnostics(),
) : NativeTransport {
    private val manager = context.applicationContext.getSystemService(UsbManager::class.java)
    private val operations = Any()
    private val lifecycle = Any()
    private var generation = 0L
    private val connection = AtomicReference<Connection?>(null)
    private val releases = PendingReleases<Connection> { it.close() }
    private val latestAttempt = AtomicReference<UsbDiagnosticAttempt?>(null)

    init { require(baudRate == UsbConnectionSettings.AUTO_BAUD || baudRate in SUPPORTED_BAUD_RATES) { "Unsupported USB baud rate" } }

    override fun open() = open { }

    override fun open(checkActive: () -> Unit) = synchronized(operations) {
        val trace = diagnostics.begin(deviceId, baudRate, controlLines)
        latestAttempt.set(trace)
        trace.event("command=OPEN stage=ownership")
        try {
            openConnection(checkActive, trace)
        } catch (error: Exception) {
            trace.event("command=OPEN result=failed kind=${(error as? NativeDiagnosticException)?.kind ?: "TRANSPORT"} error=${error.javaClass.simpleName}")
            throw error
        }
    }

    override fun reportSessionFailure(stage: NativeFailureStage, kind: ErrorKind) {
        // A transport error can release the connection before the session reports its outcome.
        latestAttempt.get()?.sessionFailure(stage, kind)
    }

    private fun openConnection(checkActive: () -> Unit, trace: UsbDiagnosticAttempt) {
        checkActive()
        releases.requireReleased()
        val openingGeneration = synchronized(lifecycle) {
            if (connection.get() != null) throw NativeDiagnosticException(ErrorKind.TRANSPORT, "USB adapter is already open.")
            generation
        }
        val usb = manager ?: throw NativeDiagnosticException(ErrorKind.TRANSPORT, "USB host is unavailable on this device.")
        val device = usb.deviceList.values.firstOrNull { it.deviceId == deviceId }
            ?: throw NativeDiagnosticException(ErrorKind.TRANSPORT, "The selected USB adapter was unplugged.")
        trace.event("command=OPEN stage=permission")
        if (!usb.hasPermission(device)) {
            throw NativeDiagnosticException(ErrorKind.TRANSPORT, "Grant Android USB permission for the selected adapter before connecting.")
        }
        checkActive()
        trace.event("command=OPEN stage=probe")
        val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
            ?: throw NativeDiagnosticException(ErrorKind.TRANSPORT, "This USB serial adapter is not supported.")
        val port = driver.ports.firstOrNull()
            ?: throw NativeDiagnosticException(ErrorKind.TRANSPORT, "This USB adapter has no serial port.")
        trace.device(driver.javaClass.simpleName, device.vendorId, device.productId)
        val candidate = Connection(port, trace, checkActive)
        synchronized(lifecycle) {
            if (generation != openingGeneration) throw NativeDiagnosticException(ErrorKind.TRANSPORT, "USB connection was cancelled.")
            connection.set(candidate)
        }
        val budget = if (baudRate == UsbConnectionSettings.AUTO_BAUD) 15_000L else 10_000L
        val endsAt = SystemClock.elapsedRealtime() + budget
        val deadline = IoDeadline(budget, "USB connection") { closeConnection(candidate) }
        try {
            ensureActive(candidate)
            trace.event("command=OPEN stage=open-device budgetMs=$budget")
            val opened = usb.openDevice(device) ?: throw IOException("Android could not open the USB device")
            candidate.deviceConnection.set(opened)
            if (connection.get() !== candidate) {
                opened.close()
                throw IOException("Connection was cancelled")
            }
            ensureActive(candidate)
            trace.event("command=OPEN stage=open-port")
            port.open(opened)
            ensureActive(candidate)
            trace.event("command=OPEN stage=flow-control")
            port.setFlowControl(UsbSerialPort.FlowControl.NONE)
            ensureActive(candidate)
            trace.event("command=OPEN stage=DTR selected=${controlLines.dtr}")
            try { port.dtr = controlLines.dtr } catch (_: UnsupportedOperationException) { trace.event("DTR=unsupported") }
            ensureActive(candidate)
            trace.event("command=OPEN stage=RTS selected=${controlLines.rts}")
            try { port.rts = controlLines.rts } catch (_: UnsupportedOperationException) { trace.event("RTS=unsupported") }
            ensureActive(candidate)
            val io = object : UsbSerialIo {
                override fun checkActive() = ensureActive(candidate)
                override fun setBaudRate(rate: Int) {
                    port.setParameters(rate, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                }
                override fun purgeInput(): Boolean = try {
                    port.purgeHwBuffers(false, true)
                    true
                } catch (_: UnsupportedOperationException) { false }
                override fun write(bytes: ByteArray, timeoutMillis: Int) = port.write(bytes, timeoutMillis)
                override fun read(bytes: ByteArray, timeoutMillis: Int): Int = port.read(bytes, timeoutMillis)
            }
            val session = UsbSerialSession(io, baudRate, trace, SystemClock::elapsedRealtime, Thread::sleep,
                port.readEndpoint.maxPacketSize.coerceIn(64, MAX_ELM_RESPONSE_BYTES))
            candidate.session = session
            session.initialize(endsAt)
            ensureActive(candidate)
            deadline.finish()
            trace.event("command=OPEN result=ready")
        } catch (error: Exception) {
            closeConnection(candidate)
            deadline.checkTimeout(error)
            if (error is NativeDiagnosticException) throw error
            if (error is java.util.concurrent.CancellationException) throw error
            if (error is InterruptedException) Thread.currentThread().interrupt()
            throw NativeDiagnosticException(ErrorKind.TRANSPORT, "Could not initialize the USB adapter; check USB power, permission and connection settings.", error)
        } finally {
            deadline.finish()
        }
    }

    override fun exchange(command: String, timeoutMillis: Long): String = synchronized(operations) {
        frameElmCommand(command)
        require(timeoutMillis in 1..60_000)
        val active = connection.get() ?: throw NativeDiagnosticException(ErrorKind.TRANSPORT, "USB adapter is not connected.")
        val deadline = IoDeadline(timeoutMillis, "USB command") { closeConnection(active) }
        val endsAt = SystemClock.elapsedRealtime() + timeoutMillis
        try {
            ensureActive(active)
            val response = checkNotNull(active.session).exchange(command, endsAt)
            ensureActive(active)
            deadline.finish()
            active.trace.event("command=$command result=complete")
            response
        } catch (error: Exception) {
            active.trace.event("command=$command result=failed kind=${(error as? NativeDiagnosticException)?.kind ?: "TRANSPORT"} error=${error.javaClass.simpleName}")
            closeConnection(active)
            deadline.checkTimeout(error)
            if (error is InterruptedException) Thread.currentThread().interrupt()
            if (error is NativeDiagnosticException) throw error
            if (error is java.util.concurrent.CancellationException) throw error
            if (error is SerialTimeoutException) {
                throw NativeDiagnosticException(ErrorKind.TIMEOUT, "USB command write timed out; reconnect the adapter.", error)
            }
            throw NativeDiagnosticException(ErrorKind.TRANSPORT, "USB communication failed; reconnect the adapter and check USB/OTG power and connection.", error)
        } finally {
            deadline.finish()
        }
    }

    /** Closing the native connection first releases blocked reads without taking the exchange lock. */
    override fun close() {
        val active = synchronized(lifecycle) {
            generation++
            connection.getAndSet(null)
        }
        active?.let { it.trace.event("CLOSE requested"); releases.attempt(it) }
        releases.retry()
    }

    private fun closeConnection(active: Connection) {
        active.trace.event("RELEASE requested")
        connection.compareAndSet(active, null)
        releases.attempt(active)
    }

    private fun ensureActive(active: Connection) {
        active.checkOwnerActive()
        if (connection.get() !== active) throw IOException("USB connection was cancelled")
    }

    private class Connection(val port: UsbSerialPort, val trace: UsbDiagnosticAttempt, val checkOwnerActive: () -> Unit) {
        val deviceConnection = AtomicReference<UsbDeviceConnection?>(null)
        var session: UsbSerialSession? = null
        fun close() {
            val device = deviceConnection.get()
            if (device != null) {
                device.close()
                deviceConnection.compareAndSet(device, null)
            }
            // A successfully closed UsbDeviceConnection has released the OS resource.
            // Closing an already-closed serial driver is harmless bookkeeping.
            runCatching { port.close() }
            trace.event("CLOSE complete")
        }
    }

    companion object {
        val SUPPORTED_BAUD_RATES = UsbConnectionSettings.SUPPORTED_BAUD_RATES
    }
}
