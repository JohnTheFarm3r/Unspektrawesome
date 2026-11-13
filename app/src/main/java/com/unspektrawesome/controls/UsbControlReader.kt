package com.unspektrawesome.controls

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class DirectUsbStatus { CONNECTED, WAITING_FOR_PERMISSION, FAILED }

/** A USB input accessory the app can read directly. */
data class DirectUsbDevice(val key: String, val name: String)

/**
 * USB accessories the app reads directly instead of through Android. Android leaves out some controls, such as which
 * way a dial turns; reading the accessory directly gives the app every control while it is open.
 */
object DirectUsbControls {
    private const val FILE = "control_bindings"
    private const val KEY = "direct_usb"
    private val mutableEnabled = MutableStateFlow<Set<String>>(emptySet())
    val enabled: StateFlow<Set<String>> = mutableEnabled.asStateFlow()
    internal val mutableStatus = MutableStateFlow<Map<String, DirectUsbStatus>>(emptyMap())
    val status: StateFlow<Map<String, DirectUsbStatus>> = mutableStatus.asStateFlow()
    internal val mutableDevices = MutableStateFlow<List<DirectUsbDevice>>(emptyList())
    /** Connected USB accessories that have buttons or dials. */
    val devices: StateFlow<List<DirectUsbDevice>> = mutableDevices.asStateFlow()
    private var context: Context? = null

    fun initialize(context: Context) = synchronized(this) {
        if (this.context != null) return@synchronized
        this.context = context.applicationContext
        mutableEnabled.value = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getStringSet(KEY, emptySet()).orEmpty().toSet()
    }

    fun setEnabled(key: String, enabled: Boolean) {
        val next = if (enabled) mutableEnabled.value + key else mutableEnabled.value - key
        mutableEnabled.value = next
        context?.getSharedPreferences(FILE, Context.MODE_PRIVATE)?.edit()?.putStringSet(KEY, next)?.apply()
    }

    fun key(device: UsbDevice) = "usb:${device.vendorId}:${device.productId}"

    fun name(device: UsbDevice): String = device.productName?.takeIf { it.isNotBlank() } ?: device.deviceName
}

/**
 * Reads the input reports of enabled USB accessories while [start]ed. Claiming an accessory takes it away from
 * Android, so its buttons reach only this app; [stop] hands it back.
 */
class UsbControlReader(
    private val context: Context,
    private val onButton: (key: String, name: String, usage: Int, pressed: Boolean) -> Unit,
    private val onWheel: (key: String, name: String, usage: Int, steps: Int) -> Unit,
) {
    private val manager = context.getSystemService(UsbManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val sessions = mutableMapOf<String, Session>()
    private var running = false
    private val permissionAction = "${context.packageName}.USB_CONTROL_PERMISSION"
    /** Accessories already asked about, so a refusal does not bring the request back each time the camera opens. */
    private val requested = mutableSetOf<String>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                device?.let { sessions.remove(DirectUsbControls.key(it))?.close() }
            }
            refresh()
        }
    }

    fun start() {
        if (running || manager == null) return
        running = true
        ContextCompat.registerReceiver(context, receiver, IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }, ContextCompat.RECEIVER_EXPORTED)
        ContextCompat.registerReceiver(context, receiver, IntentFilter(permissionAction), ContextCompat.RECEIVER_NOT_EXPORTED)
        refresh()
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { context.unregisterReceiver(receiver) }
        sessions.values.forEach(Session::close)
        sessions.clear()
        DirectUsbControls.mutableStatus.value = emptyMap()
    }

    /** Connects enabled accessories and lets go of ones that were turned off or unplugged. */
    fun refresh() {
        val usb = manager ?: return
        val attached = usb.deviceList.values.filter { device -> hidInterfaces(device).isNotEmpty() }
        DirectUsbControls.mutableDevices.value = attached.map { DirectUsbDevice(DirectUsbControls.key(it), DirectUsbControls.name(it)) }
            .distinctBy { it.key }
        if (!running) return
        val wanted = attached.filter { DirectUsbControls.key(it) in DirectUsbControls.enabled.value }.associateBy(DirectUsbControls::key)
        (sessions.keys - wanted.keys).forEach { sessions.remove(it)?.close() }
        // Ask once per connection; turning the setting off and on again, or reconnecting, asks again.
        requested.retainAll(wanted.keys)
        val status = mutableMapOf<String, DirectUsbStatus>()
        for ((key, device) in wanted) {
            sessions[key]?.takeIf { it.alive }?.let { status[key] = DirectUsbStatus.CONNECTED; continue }
            sessions.remove(key)?.close()
            if (!usb.hasPermission(device)) {
                status[key] = DirectUsbStatus.WAITING_FOR_PERMISSION
                if (requested.add(key)) usb.requestPermission(device, PendingIntent.getBroadcast(context, 0,
                    Intent(permissionAction).setPackage(context.packageName), PendingIntent.FLAG_MUTABLE))
                continue
            }
            val session = Session.open(usb, device, key, DirectUsbControls.name(device), main, onButton, onWheel) { refresh() }
            if (session != null) {
                sessions[key] = session
                status[key] = DirectUsbStatus.CONNECTED
            } else status[key] = DirectUsbStatus.FAILED
        }
        DirectUsbControls.mutableStatus.value = status
    }

    private class Session(
        private val connection: UsbDeviceConnection,
        private val readers: List<Pair<UsbInterface, Thread>>,
    ) {
        @Volatile var alive = true

        fun close() {
            alive = false
            readers.forEach { (_, thread) -> thread.interrupt() }
            readers.forEach { (_, thread) -> runCatching { thread.join(300) } }
            // Releasing an interface lets Android take the accessory back.
            readers.forEach { (intf, _) -> runCatching { connection.releaseInterface(intf) } }
            runCatching { connection.close() }
        }

        companion object {
            fun open(manager: UsbManager, device: UsbDevice, key: String, name: String, main: Handler,
                onButton: (String, String, Int, Boolean) -> Unit, onWheel: (String, String, Int, Int) -> Unit,
                onLost: () -> Unit): Session? {
                val connection = manager.openDevice(device) ?: return null
                val claimed = mutableListOf<Pair<UsbInterface, UsbEndpoint>>()
                val layouts = mutableListOf<HidReportLayout>()
                for ((intf, endpoint) in hidInterfaces(device)) {
                    val claimedInterface = connection.claimInterface(intf, true)
                    debug("claim interface=${intf.id} result=$claimedInterface")
                    if (!claimedInterface) continue
                    val descriptor = ByteArray(4096)
                    // GET_DESCRIPTOR for the interface's report descriptor.
                    val length = connection.controlTransfer(0x81, 0x06, 0x2200, intf.id, descriptor, descriptor.size, 1000)
                    val layout = if (length > 0) HidReportLayout.parse(descriptor.copyOf(length)) else null
                    debug("descriptor length=$length controls=${layout?.hasControls} bytes=${hex(descriptor, maxOf(length, 0))}")
                    if (layout?.hasControls != true) {
                        connection.releaseInterface(intf)
                        continue
                    }
                    claimed += intf to endpoint
                    layouts += layout
                }
                if (claimed.isEmpty()) {
                    connection.close()
                    return null
                }
                lateinit var session: Session
                val threads = claimed.mapIndexed { index, (intf, endpoint) ->
                    val layout = layouts[index]
                    intf to Thread({
                        val buffer = ByteArray(maxOf(endpoint.maxPacketSize, 64))
                        while (!Thread.currentThread().isInterrupted && session.alive) {
                            val read = connection.bulkTransfer(endpoint, buffer, buffer.size, 250)
                            if (read > 0) debug("report=${hex(buffer, read)}")
                            if (read <= 0) {
                                if (read < 0 && !Thread.currentThread().isInterrupted && manager.deviceList.values.none { it.deviceId == device.deviceId }) break
                                continue
                            }
                            val events = layout.decode(buffer, read)
                            debug("events=$events")
                            if (events.isNotEmpty()) main.post { dispatch(events, key, name, onButton, onWheel) }
                        }
                        val released = layout.releaseAll()
                        main.post {
                            dispatch(released, key, name, onButton, onWheel)
                            if (session.alive) onLost()
                        }
                    }, "UsbControls").apply { isDaemon = true }
                }
                session = Session(connection, threads)
                threads.forEach { it.second.start() }
                return session
            }

            private fun debug(message: String) {
                if (com.unspektrawesome.BuildConfig.DEBUG) android.util.Log.d("ControlInput", message)
            }

            private fun hex(data: ByteArray, length: Int) = data.take(length).joinToString("") { "%02x".format(it) }

            private fun dispatch(events: List<HidReportLayout.Event>, key: String, name: String,
                onButton: (String, String, Int, Boolean) -> Unit, onWheel: (String, String, Int, Int) -> Unit) {
                for (event in events) when (event) {
                    is HidReportLayout.Event.Button -> onButton(key, name, event.usage, event.pressed)
                    is HidReportLayout.Event.Wheel -> onWheel(key, name, event.usage, event.steps)
                }
            }
        }
    }

    private companion object {
        /** Input interfaces of [device] with the endpoint that delivers their reports. */
        fun hidInterfaces(device: UsbDevice): List<Pair<UsbInterface, UsbEndpoint>> =
            (0 until device.interfaceCount).map(device::getInterface).filter { it.interfaceClass == UsbConstants.USB_CLASS_HID }
                .mapNotNull { intf ->
                    (0 until intf.endpointCount).map(intf::getEndpoint).firstOrNull {
                        it.direction == UsbConstants.USB_DIR_IN && it.type == UsbConstants.USB_ENDPOINT_XFER_INT
                    }?.let { intf to it }
                }
    }
}
