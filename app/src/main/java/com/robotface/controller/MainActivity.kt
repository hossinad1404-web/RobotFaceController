package com.robotface.controller

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var tvConnection: TextView
    private lateinit var tvState: TextView
    private lateinit var tvLog: TextView
    private lateinit var btnConnect: Button
    private lateinit var etChat: EditText
    private lateinit var radarView: RadarView
    private lateinit var cbAutoAvoid: CheckBox
    private lateinit var etThreshold: EditText

    private lateinit var bt: BluetoothSppManager
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        BluetoothAdapter.getDefaultAdapter()
    }

    private var lastKnownState: String = "NORMAL"
    private var lastAutoTriggerTime = 0L
    private val autoTriggerCooldownMs = 4000L

    private val requiredPermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvConnection = findViewById(R.id.tvConnection)
        tvState = findViewById(R.id.tvState)
        tvLog = findViewById(R.id.tvLog)
        btnConnect = findViewById(R.id.btnConnect)
        etChat = findViewById(R.id.etChat)
        radarView = findViewById(R.id.radarView)
        cbAutoAvoid = findViewById(R.id.cbAutoAvoid)
        etThreshold = findViewById(R.id.etThreshold)

        bt = BluetoothSppManager(
            onLine = { line -> runOnUiThread { handleIncomingLine(line) } },
            onConnected = {
                runOnUiThread {
                    tvConnection.text = "وصل شد ✓"
                    appendLog("اتصال برقرار شد")
                }
            },
            onDisconnected = { reason ->
                runOnUiThread {
                    tvConnection.text = "وصل نیست"
                    appendLog("اتصال قطع شد${if (reason != null) " ($reason)" else ""}")
                }
            }
        )

        btnConnect.setOnClickListener { onConnectClicked() }

        findViewById<Button>(R.id.btnNormal).setOnClickListener { sendCmd('n') }
        findViewById<Button>(R.id.btnHappy).setOnClickListener { sendCmd('h') }
        findViewById<Button>(R.id.btnSad).setOnClickListener { sendCmd('s') }
        findViewById<Button>(R.id.btnAngry).setOnClickListener { sendCmd('a') }
        findViewById<Button>(R.id.btnGuard).setOnClickListener { sendCmd('g') }
        findViewById<Button>(R.id.btnRescue).setOnClickListener { sendCmd('r') }
        findViewById<Button>(R.id.btnDanger).setOnClickListener { sendCmd('d') }
        findViewById<Button>(R.id.btnClearAlarm).setOnClickListener { sendCmd('k') }
        findViewById<Button>(R.id.btnLowBat).setOnClickListener { sendCmd('l') }
        findViewById<Button>(R.id.btnStuck).setOnClickListener { sendCmd('m') }

        findViewById<Button>(R.id.btnSendChat).setOnClickListener {
            val msg = etChat.text.toString()
            if (msg.isNotBlank()) {
                if (!bt.isConnected) {
                    appendLog("اول باید وصل شوید")
                    return@setOnClickListener
                }
                bt.sendChar('c')
                bt.sendLine(msg)
                appendLog("پیام ارسال شد: $msg")
            }
        }
    }

    private fun sendCmd(c: Char) {
        if (!bt.isConnected) {
            appendLog("اول باید وصل شوید")
            return
        }
        bt.sendChar(c)
    }

    private fun onConnectClicked() {
        if (!hasPermissions()) {
            ActivityCompat.requestPermissions(this, requiredPermissions, 1001)
            return
        }
        val adapter = bluetoothAdapter
        if (adapter == null) {
            appendLog("این دستگاه بلوتوث ندارد")
            return
        }
        if (!adapter.isEnabled) {
            appendLog("لطفاً اول بلوتوث تبلت را روشن کنید")
            return
        }

        val devices = bt.getBondedDevices(adapter)
        if (devices.isEmpty()) {
            appendLog("هیچ دستگاه جفت‌شده‌ای پیدا نشد. اول از تنظیمات بلوتوث تبلت با HC-05 جفت (Pair) شوید.")
            return
        }

        val names = devices.map { d ->
            try { "${d.name} (${d.address})" } catch (e: SecurityException) { d.address }
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("یک دستگاه را انتخاب کنید")
            .setItems(names) { _, which ->
                connectTo(devices[which])
            }
            .show()
    }

    private fun connectTo(device: BluetoothDevice) {
        tvConnection.text = "در حال اتصال..."
        bt.connect(device)
    }

    private fun hasPermissions(): Boolean {
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001 && hasPermissions()) {
            onConnectClicked()
        }
    }

    private fun handleIncomingLine(line: String) {
        when {
            line.startsWith("STATE:") -> {
                val state = line.removePrefix("STATE:")
                lastKnownState = state
                tvState.text = "حالت فعلی: ${stateLabel(state)}"
            }
            line.startsWith("RADAR:") -> {
                val parts = line.removePrefix("RADAR:").split(",")
                if (parts.size == 2) {
                    val angle = parts[0].toIntOrNull()
                    val dist = parts[1].toIntOrNull()
                    if (angle != null && dist != null) {
                        radarView.pushReading(angle, dist)
                        checkAutoAvoid(dist)
                    }
                }
            }
            line.startsWith("FIRE!") -> {
                appendLog("🔥 $line")
            }
            line.startsWith("ALARM CLEARED") -> {
                appendLog("✅ آلارم پاک شد")
            }
            else -> {
                appendLog(line)
            }
        }
    }

    private fun checkAutoAvoid(distanceCm: Int) {
        if (!cbAutoAvoid.isChecked) return
        if (lastKnownState == "DANGER") return
        val threshold = etThreshold.text.toString().toIntOrNull() ?: return
        if (distanceCm <= 0) return // 0 means "no echo" / out of range, not "very close"
        if (distanceCm > threshold) return

        val now = System.currentTimeMillis()
        if (now - lastAutoTriggerTime < autoTriggerCooldownMs) return
        lastAutoTriggerTime = now

        bt.sendChar('x')
        appendLog("⚠️ مانع نزدیک ($distanceCm cm) - دستور خطر ارسال شد")
    }

    private fun stateLabel(state: String): String = when (state) {
        "NORMAL" -> "عادی"
        "HAPPY" -> "خوشحال"
        "SAD" -> "ناراحت"
        "ANGRY" -> "عصبانی"
        "GUARD" -> "نگهبان"
        "RESCUE" -> "نجات"
        "DANGER" -> "خطر"
        "CHAT" -> "چت"
        "SLEEP" -> "خواب"
        "EDGE_WARNING" -> "هشدار لبه/سقوط"
        "STUCK" -> "گیر کردن موتور"
        "LOWBAT" -> "باتری کم"
        else -> state
    }

    private fun appendLog(text: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        tvLog.append("[$time] $text\n")
    }

    override fun onDestroy() {
        super.onDestroy()
        bt.disconnect()
    }
}
