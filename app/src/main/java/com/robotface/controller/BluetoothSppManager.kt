package com.robotface.controller

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Handles a classic Bluetooth SPP (Serial Port Profile) connection to the HC-05 module.
 * This talks to the same UART the Arduino sketch uses for both Serial and "BT"
 * (since the sketch does #define BT Serial).
 */
class BluetoothSppManager(
    private val onLine: (String) -> Unit,
    private val onConnected: () -> Unit,
    private val onDisconnected: (String?) -> Unit
) {
    companion object {
        private const val TAG = "BluetoothSppManager"
        // Standard SPP UUID, used by virtually every HC-05/HC-06 module.
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    private var socket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var readThread: Thread? = null
    @Volatile private var running = false

    val isConnected: Boolean
        get() = socket?.isConnected == true

    fun getBondedDevices(adapter: BluetoothAdapter): List<BluetoothDevice> {
        return try {
            adapter.bondedDevices?.toList() ?: emptyList()
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    fun connect(device: BluetoothDevice, adapter: BluetoothAdapter? = null) {
        disconnect()
        Thread {
            try {
                // Many Bluetooth stacks (especially on older Android versions)
                // fail or hang an RFCOMM connect attempt if discovery is active.
                try { adapter?.cancelDiscovery() } catch (e: SecurityException) { }

                var s: BluetoothSocket? = null
                val attempts: List<() -> BluetoothSocket> = listOf(
                    { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
                    { device.createRfcommSocketToServiceRecord(SPP_UUID) },
                    {
                        @Suppress("UNCHECKED_CAST")
                        val method = device.javaClass.getMethod(
                            "createRfcommSocket",
                            Int::class.javaPrimitiveType
                        )
                        method.invoke(device, 1) as BluetoothSocket
                    }
                )

                var lastError: Exception? = null
                for (attempt in attempts) {
                    try {
                        val candidate = attempt()
                        candidate.connect()
                        // Many Bluetooth stacks (and HC-05 clones especially) need
                        // a brief moment to fully settle the RFCOMM channel before
                        // the first read/write, otherwise you get an immediate
                        // "read failed, socket might closed" error.
                        Thread.sleep(500)
                        s = candidate
                        break
                    } catch (e: Exception) {
                        lastError = e
                        Log.e(TAG, "Connect attempt failed, trying next method", e)
                        try { (e as? IOException)?.let { } } catch (ignored: Exception) { }
                    }
                }

                if (s == null) {
                    throw lastError ?: IOException("All connection attempts failed")
                }

                socket = s
                inputStream = s.inputStream
                outputStream = s.outputStream
                running = true
                onConnected()
                startReadLoop()
            } catch (e: Exception) {
                Log.e(TAG, "Connect failed", e)
                onDisconnected(e.message)
                closeQuietly()
            }
        }.start()
    }

    private fun startReadLoop() {
        readThread = Thread {
            val buffer = StringBuilder()
            val byteBuffer = ByteArray(1024)
            while (running) {
                try {
                    val ins = inputStream ?: break
                    val count = ins.read(byteBuffer)
                    if (count > 0) {
                        for (i in 0 until count) {
                            val c = byteBuffer[i].toInt().toChar()
                            if (c == '\n') {
                                val line = buffer.toString().trim()
                                buffer.clear()
                                if (line.isNotEmpty()) onLine(line)
                            } else if (c != '\r') {
                                buffer.append(c)
                            }
                        }
                    }
                } catch (e: IOException) {
                    if (running) {
                        running = false
                        onDisconnected(e.message)
                    }
                    break
                }
            }
        }
        readThread?.start()
    }

    fun sendChar(c: Char) {
        send(c.toString())
    }

    fun sendLine(text: String) {
        // The Arduino chat handler reads characters until it sees '\n'
        send(text + "\n")
    }

    private fun send(text: String) {
        try {
            outputStream?.write(text.toByteArray())
            outputStream?.flush()
        } catch (e: IOException) {
            Log.e(TAG, "Send failed", e)
        }
    }

    fun disconnect() {
        running = false
        closeQuietly()
    }

    private fun closeQuietly() {
        try { inputStream?.close() } catch (e: IOException) { }
        try { outputStream?.close() } catch (e: IOException) { }
        try { socket?.close() } catch (e: IOException) { }
        inputStream = null
        outputStream = null
        socket = null
    }
}
