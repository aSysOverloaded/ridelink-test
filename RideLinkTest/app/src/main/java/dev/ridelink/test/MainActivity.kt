package dev.ridelink.test

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Test harness for the owner's own Apache RTR 200 4V (2025 TFT) cluster.
 *
 * It scans, connects, and then STREAMS the chosen navigation instruction to the
 * dash about once a second. Streaming matters for two reasons the first test
 * revealed: (1) the cluster drops an idle phone after a few seconds, so constant
 * writes keep the link alive, and (2) the dash is designed to be fed continuously
 * while navigating. The routine speedometer frames the cluster sends back are
 * counted quietly so the log stays readable.
 */
@SuppressLint("MissingPermission")
class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? by lazy {
        (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private val seen = LinkedHashMap<String, BluetoothDevice>()
    private var lastDevice: BluetoothDevice? = null
    private var reconnectTries = 0

    // streaming state
    private var streamPlain: ByteArray? = null
    private var streamLabel = ""
    private var sendTicks = 0
    private var inCount = 0
    private var lastInSummary = 0L
    private var firstWriteOk = false
    private var writeFailsLogged = 0

    private val streamer = object : Runnable {
        override fun run() {
            val p = streamPlain
            if (p != null && writeChar != null) {
                writeRaw(ClusterProtocol.navWire(p), streamLabel, log = false)
                sendTicks++
                if (sendTicks % 5 == 0)
                    logln("… still sending \"$streamLabel\" (x$sendTicks) · cluster frames in: $inCount")
            }
            ui.postDelayed(this, 1000)
        }
    }

    private lateinit var statusText: TextView
    private lateinit var deviceBox: LinearLayout
    private lateinit var logText: TextView
    private val log = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        requestPerms()
    }

    // ---- permissions ---------------------------------------------------------

    private fun requestPerms() {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    // ---- scanning ------------------------------------------------------------

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device
            val name = dev.name ?: return
            if (seen.put(dev.address, dev) == null) {
                logln("Found: $name  [${dev.address}]")
                ui.post { deviceBox.addView(button("Connect to $name") { connect(dev) }) }
            }
        }
        override fun onScanFailed(errorCode: Int) = logln("Scan failed: $errorCode")
    }

    private fun startScan() {
        val a = adapter
        if (a == null || !a.isEnabled) { logln("Turn Bluetooth ON first."); return }
        seen.clear()
        deviceBox.removeAllViews()
        logln("Scanning… pick your bike when it appears.")
        a.bluetoothLeScanner?.startScan(scanCb)
        ui.postDelayed({ a.bluetoothLeScanner?.stopScan(scanCb); logln("Scan stopped.") }, 10_000)
    }

    // ---- connect + GATT ------------------------------------------------------

    private fun connect(dev: BluetoothDevice) {
        adapter?.bluetoothLeScanner?.stopScan(scanCb)
        lastDevice = dev
        reconnectTries = 0
        setStatus("Connecting to ${dev.name}…")
        gatt = dev.connectGatt(this, false, gattCb, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                logln("Connected. Negotiating…")
                g.requestMtu(185)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                logln("Disconnected (status $st).")
                setStatus("Disconnected")
                writeChar = null
                stopStream()
                g.close()
                // The cluster sometimes drops the link; try to come back automatically.
                if (lastDevice != null && reconnectTries < 5) {
                    reconnectTries++
                    logln("Reconnecting (try $reconnectTries)…")
                    ui.postDelayed({ lastDevice?.let {
                        gatt = it.connectGatt(this@MainActivity, false, gattCb, BluetoothDevice.TRANSPORT_LE)
                    } }, 1200)
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, st: Int) {
            logln("MTU = $mtu. Discovering services…")
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
            logln("--- Services on the cluster ---")
            for (svc in g.services) {
                logln("service ${svc.uuid}")
                for (c in svc.characteristics) logln("   char ${c.uuid}")
            }
            logln("-------------------------------")
            val svc = g.getService(ClusterProtocol.SERVICE_UUID)
            if (svc == null) { logln("Expected service not found — share the list above."); return }
            writeChar = svc.getCharacteristic(ClusterProtocol.WRITE_UUID)
            svc.getCharacteristic(ClusterProtocol.NOTIFY_UUID)?.let { n ->
                g.setCharacteristicNotification(n, true)
                n.getDescriptor(ClusterProtocol.CCCD_UUID)?.let {
                    @Suppress("DEPRECATION") run {
                        it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        g.writeDescriptor(it)
                    }
                }
            }
            reconnectTries = 0
            firstWriteOk = false
            writeFailsLogged = 0
            setStatus("Connected to ${g.device.name}")
            logln("Ready. Auto-sending \"Turn LEFT 200 m\" every second — this keeps the")
            logln("link alive and drives the dash. Use the buttons to change it, or Stop.")
            logln("TIP: cycle the dash's mode/info button to its navigation screen.")
            startStream("left 200m", leftFrame())
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                if (writeFailsLogged < 3) { logln("⚠ write rejected (status $status)"); writeFailsLogged++ }
            } else if (!firstWriteOk) {
                firstWriteOk = true
                logln("✓ first instruction accepted by the cluster")
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray
        ) = handleIncoming(value)

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) =
            handleIncoming(c.value ?: ByteArray(0))
    }

    /** Quietly count the routine speedometer frames; shout about anything unusual. */
    private fun handleIncoming(raw: ByteArray) {
        if (raw.isEmpty()) return
        inCount++
        val clear = ClusterProtocol.unscramble(raw)
        val id = clear.getOrNull(1)?.toInt()?.and(0xFF) ?: -1
        val routine = id == 0x10 || id == 0x12 || id == 0x18
        if (!routine) {
            logln("<= NEW ${ClusterProtocol.hex(raw)}  (clear ${ClusterProtocol.hex(clear)})")
        } else {
            val now = System.currentTimeMillis()
            if (now - lastInSummary > 5000) {
                lastInSummary = now
                logln("cluster alive · $inCount frames received so far")
            }
        }
    }

    // ---- sending -------------------------------------------------------------

    private fun writeRaw(wire: ByteArray, label: String, log: Boolean = true) {
        val c = writeChar
        val g = gatt
        if (c == null || g == null) { if (log) logln("Not connected."); return }
        if (log) logln("=> $label: ${ClusterProtocol.hex(wire)}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(c, wire, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            @Suppress("DEPRECATION") run {
                c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                c.value = wire
                g.writeCharacteristic(c)
            }
        }
    }

    private fun startStream(label: String, plain: ByteArray) {
        streamPlain = plain
        streamLabel = label
        sendTicks = 0
        logln("▶ now sending \"$label\": ${ClusterProtocol.hex(ClusterProtocol.navWire(plain))}")
        ui.removeCallbacks(streamer)
        ui.post(streamer)
    }

    private fun stopStream() {
        streamPlain = null
        ui.removeCallbacks(streamer)
    }

    // frame presets
    private fun leftFrame() =
        ClusterProtocol.navFrame(200, 5, 2300, ClusterProtocol.Picto.LEFT, ClusterProtocol.Prefix.TURN_AHEAD)
    private fun rightFrame() =
        ClusterProtocol.navFrame(200, 5, 2300, ClusterProtocol.Picto.RIGHT, ClusterProtocol.Prefix.TURN_AHEAD)
    private fun straightFrame() =
        ClusterProtocol.navFrame(1200, 8, 4000, ClusterProtocol.Picto.STRAIGHT, ClusterProtocol.Prefix.STRAIGHT_LONG)
    private fun arrivedFrame() =
        ClusterProtocol.navFrame(0, 0, 0, ClusterProtocol.Picto.ARRIVED, ClusterProtocol.Prefix.NONE, navStatus = 0)

    // ---- UI ------------------------------------------------------------------

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }
        statusText = TextView(this).apply { text = "Not connected"; textSize = 16f }
        root.addView(statusText)

        root.addView(button("1. Scan for my bike") { startScan() })
        deviceBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(deviceBox)

        root.addView(label("2. Choose what to stream to the dash"))
        root.addView(button("Turn LEFT in 200 m") { startStream("left 200m", leftFrame()) })
        root.addView(button("Turn RIGHT in 200 m") { startStream("right 200m", rightFrame()) })
        root.addView(button("Go STRAIGHT 1.2 km") { startStream("straight 1200m", straightFrame()) })
        root.addView(button("You have ARRIVED") { startStream("arrived", arrivedFrame()) })
        root.addView(button("■ STOP sending") { stopStream(); logln("■ stopped sending.") })

        root.addView(label("3. Try any arrow code (0–120)"))
        val arrow = EditText(this).apply {
            hint = "arrow code, e.g. 25"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        root.addView(arrow)
        root.addView(button("Stream that arrow at 150 m") {
            val code = arrow.text.toString().toIntOrNull()
            if (code == null) logln("Enter a number first.")
            else startStream("arrow $code",
                ClusterProtocol.navFrame(150, 3, 1500, code, ClusterProtocol.Prefix.TURN_AHEAD))
        })

        root.addView(label("Log (share this back)"))
        logText = TextView(this).apply { textSize = 12f; setTextIsSelectable(true) }
        val scroll = ScrollView(this).apply {
            addView(logText)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 600)
        }
        root.addView(scroll)

        return ScrollView(this).apply { addView(root) }
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        setOnClickListener { onClick() }
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        setPadding(0, 32, 0, 8)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun setStatus(s: String) = ui.post { statusText.text = s }

    private fun logln(s: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        ui.post {
            log.append("[$stamp] ").append(s).append('\n')
            logText.text = log.toString()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopStream()
        gatt?.close()
    }
}
