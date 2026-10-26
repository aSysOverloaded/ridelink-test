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
 * Minimal test harness for talking to the owner's own Apache RTR 200 4V (2025 TFT)
 * cluster. It scans, connects, performs the unlock the cluster asks for, and lets
 * the owner send a few sample navigation instructions to confirm the display reacts.
 * Every step is printed to an on-screen log that can be shared back for analysis.
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
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            needed += Manifest.permission.BLUETOOTH_SCAN
            needed += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            needed += Manifest.permission.ACCESS_FINE_LOCATION
        }
        val missing = needed.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    // ---- scanning ------------------------------------------------------------

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device
            val name = dev.name ?: return
            if (seen.put(dev.address, dev) == null) {
                logln("Found: $name  [${dev.address}]")
                ui.post { addDeviceButton(dev, name) }
            }
        }
        override fun onScanFailed(errorCode: Int) = logln("Scan failed: $errorCode")
    }

    private fun startScan() {
        val a = adapter
        if (a == null || !a.isEnabled) { logln("Turn Bluetooth ON first."); return }
        seen.clear()
        deviceBox.removeAllViews()
        logln("Scanning… pick your bike from the list when it appears.")
        a.bluetoothLeScanner?.startScan(scanCb)
        ui.postDelayed({
            a.bluetoothLeScanner?.stopScan(scanCb)
            logln("Scan stopped.")
        }, 12_000)
    }

    // ---- connect + GATT ------------------------------------------------------

    private fun connect(dev: BluetoothDevice) {
        adapter?.bluetoothLeScanner?.stopScan(scanCb)
        setStatus("Connecting to ${dev.name}…")
        gatt = dev.connectGatt(this, false, gattCb, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                logln("Connected. Asking for services…")
                g.requestMtu(185)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                logln("Disconnected (status $st).")
                setStatus("Disconnected")
                writeChar = null
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
            val notify = svc.getCharacteristic(ClusterProtocol.NOTIFY_UUID)
            if (notify != null) {
                g.setCharacteristicNotification(notify, true)
                notify.getDescriptor(ClusterProtocol.CCCD_UUID)?.let {
                    @Suppress("DEPRECATION")
                    it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION") g.writeDescriptor(it)
                }
            }
            setStatus("Connected to ${g.device.name}")
            logln("Ready. The cluster may now send an unlock request on its own.")
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray
        ) = handleIncoming(value)

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) =
            handleIncoming(c.value ?: ByteArray(0))
    }

    /**
     * The Apache cluster isn't expected to send an unlock challenge (that only
     * applies to the U388 scooter in the official app). We just log whatever the
     * cluster sends — both as received and unscrambled — for diagnostics.
     */
    private fun handleIncoming(raw: ByteArray) {
        if (raw.isEmpty()) return
        logln("<= raw   ${ClusterProtocol.hex(raw)}")
        logln("<= clear ${ClusterProtocol.hex(ClusterProtocol.unscramble(raw))}")
    }

    // ---- sending -------------------------------------------------------------

    private fun writeRaw(wire: ByteArray, label: String) {
        val c = writeChar
        val g = gatt
        if (c == null || g == null) { logln("Not connected."); return }
        logln("=> $label: ${ClusterProtocol.hex(wire)}")
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

    private fun sendNav(label: String, plain: ByteArray) =
        writeRaw(ClusterProtocol.navWire(plain), label)

    // ---- UI building ---------------------------------------------------------

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

        root.addView(label("2. Send a test instruction to the TFT"))
        root.addView(button("Turn LEFT in 200 m") {
            sendNav("left 200m", ClusterProtocol.navFrame(
                200, 5, 2300, ClusterProtocol.Picto.LEFT, ClusterProtocol.Prefix.TURN_AHEAD))
        })
        root.addView(button("Turn RIGHT in 200 m") {
            sendNav("right 200m", ClusterProtocol.navFrame(
                200, 5, 2300, ClusterProtocol.Picto.RIGHT, ClusterProtocol.Prefix.TURN_AHEAD))
        })
        root.addView(button("Go STRAIGHT 1.2 km") {
            sendNav("straight 1200m", ClusterProtocol.navFrame(
                1200, 8, 4000, ClusterProtocol.Picto.STRAIGHT, ClusterProtocol.Prefix.STRAIGHT_LONG))
        })
        root.addView(button("You have ARRIVED") {
            sendNav("arrived", ClusterProtocol.navFrame(
                0, 0, 0, ClusterProtocol.Picto.ARRIVED, ClusterProtocol.Prefix.NONE, navStatus = 0))
        })

        root.addView(label("3. Try any arrow code (0–120)"))
        val arrow = EditText(this).apply {
            hint = "arrow code, e.g. 25"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        root.addView(arrow)
        root.addView(button("Send that arrow at 150 m") {
            val code = arrow.text.toString().toIntOrNull()
            if (code == null) logln("Enter a number first.")
            else sendNav("arrow $code", ClusterProtocol.navFrame(
                150, 3, 1500, code, ClusterProtocol.Prefix.TURN_AHEAD))
        })

        root.addView(label("Log (share this back)"))
        logText = TextView(this).apply { textSize = 12f; setTextIsSelectable(true) }
        val scroll = ScrollView(this).apply {
            addView(logText)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 600)
        }
        root.addView(scroll)

        val outer = ScrollView(this)
        outer.addView(root)
        return outer
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

    private fun addDeviceButton(dev: BluetoothDevice, name: String) {
        deviceBox.addView(button("Connect to $name") { connect(dev) })
    }

    // ---- logging -------------------------------------------------------------

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
        gatt?.close()
    }
}
