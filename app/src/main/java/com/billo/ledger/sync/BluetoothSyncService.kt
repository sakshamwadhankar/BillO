package com.billo.ledger.sync

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import com.billo.ledger.core.Clock
import com.billo.ledger.data.db.LedgerOpEntity
import com.billo.ledger.data.db.OpType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.UUID

sealed class SyncEvent {
    data class Status(val message: String) : SyncEvent()
    data class Progress(val message: String) : SyncEvent()
    data class Done(val sentOps: Int, val receivedOps: Int) : SyncEvent()
    data class Failed(val message: String) : SyncEvent()
}

/**
 * Real Android Bluetooth RFCOMM peer-to-peer sync. No INTERNET permission
 * anywhere — devices find each other by listening/connecting on [SYNC_UUID]
 * and exchange CRDT op-logs as length-prefixed UTF-8 JSON frames:
 *
 *   writeInt(opCount) then per op: writeInt(jsonBytes.size) + jsonBytes
 *
 * The initiator calls [connectAndSync] and pushes its ops while reading the
 * peer's; the listener created by [startServer] answers with its own op-log
 * (a full exchange therefore happens when both sides initiate, or the UI can
 * fall back to the clipboard transport). All callbacks fire on the IO thread;
 * the caller is responsible for hopping to the main thread if needed.
 */
class BluetoothSyncService(
    private val context: Context,
    private val clock: Clock,
    private val onEvent: (SyncEvent) -> Unit,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var running: Boolean = false

    private var serverSocket: BluetoothServerSocket? = null

    private fun adapterOrNull(): BluetoothAdapter? = try {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    } catch (_: SecurityException) {
        null
    } catch (_: Exception) {
        null
    }

    private fun deviceName(device: BluetoothDevice): String = try {
        device.name ?: "unknown device"
    } catch (_: SecurityException) {
        "unknown device"
    }

    /** Listens for peers forever (until [stopServer]) and exchanges ops with each one. */
    fun startServer() {
        if (running) return
        running = true
        scope.launch {
            val adapter = adapterOrNull()
            if (adapter == null) {
                running = false
                onEvent(SyncEvent.Failed("No bluetooth adapter"))
                return@launch
            }
            val server = try {
                adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SYNC_UUID)
            } catch (_: SecurityException) {
                running = false
                onEvent(SyncEvent.Failed("Bluetooth permission missing"))
                return@launch
            } catch (e: IOException) {
                running = false
                onEvent(SyncEvent.Failed("Could not open server socket: ${e.message ?: "IO error"}"))
                return@launch
            }
            serverSocket = server
            onEvent(SyncEvent.Status("Listening for BillO peers on RFCOMM ${SYNC_UUID.toString().take(8)}…"))
            try {
                while (running) {
                    val socket = try {
                        server.accept()
                    } catch (_: SecurityException) {
                        onEvent(SyncEvent.Failed("Bluetooth permission missing"))
                        break
                    } catch (_: IOException) {
                        break // server socket closed by stopServer(), or adapter turned off
                    }
                    if (!running) {
                        runCatching { socket.close() }
                        break
                    }
                    onEvent(SyncEvent.Status("Peer connected: ${deviceName(socket.remoteDevice)}"))
                    val received = try {
                        exchange(socket, emptyList())
                    } catch (_: SecurityException) {
                        onEvent(SyncEvent.Failed("Bluetooth permission missing"))
                        runCatching { socket.close() }
                        continue
                    } catch (e: IOException) {
                        onEvent(SyncEvent.Failed("Exchange failed: ${e.message ?: "IO error"}"))
                        runCatching { socket.close() }
                        continue
                    }
                    onEvent(SyncEvent.Done(sentOps = 0, receivedOps = received.size))
                    runCatching { socket.close() }
                }
            } finally {
                running = false
                runCatching { server.close() }
                if (serverSocket === server) serverSocket = null
            }
        }
    }

    /** Closes the server socket; the accept() loop unwinds on the next poll. */
    fun stopServer() {
        running = false
        val server = serverSocket
        serverSocket = null
        try {
            server?.close()
        } catch (_: IOException) {
            // already closed — nothing to do
        }
    }

    /**
     * Connects to [device], sends [localOps], reads the peer's ops and hands
     * the parsed list to [onRemoteOps] so the caller can merge them.
     */
    fun connectAndSync(
        device: BluetoothDevice,
        localOps: List<LedgerOpEntity>,
        onRemoteOps: (List<LedgerOpEntity>) -> Unit,
    ) {
        scope.launch {
            val adapter = adapterOrNull()
            if (adapter == null) {
                onEvent(SyncEvent.Failed("No bluetooth adapter"))
                return@launch
            }
            var socket: BluetoothSocket? = null
            try {
                try {
                    adapter.cancelDiscovery()
                } catch (_: SecurityException) {
                    // discovery cancel is best-effort; connect can proceed anyway
                } catch (_: IOException) {
                    // ditto
                }
                socket = device.createRfcommSocketToServiceRecord(SYNC_UUID)
                onEvent(SyncEvent.Status("Connecting to ${deviceName(device)}…"))
                socket.connect()
                onEvent(SyncEvent.Status("Connected — sending ${localOps.size} ops"))
                val received = exchange(socket, localOps)
                onEvent(SyncEvent.Done(sentOps = localOps.size, receivedOps = received.size))
                onRemoteOps(received)
            } catch (_: SecurityException) {
                onEvent(SyncEvent.Failed("Bluetooth permission missing"))
            } catch (e: IOException) {
                onEvent(SyncEvent.Failed("Sync failed: ${e.message ?: "IO error"}"))
            } finally {
                try {
                    socket?.close()
                } catch (_: IOException) {
                    // already closed
                }
            }
        }
    }

    /**
     * Full-duplex op exchange over an open socket: writes [localOps], then
     * reads the peer's ops. Returns the received ops (count = received size).
     */
    private fun exchange(socket: BluetoothSocket, localOps: List<LedgerOpEntity>): List<LedgerOpEntity> {
        val input = DataInputStream(BufferedInputStream(socket.inputStream))
        val output = DataOutputStream(BufferedOutputStream(socket.outputStream))

        output.writeInt(localOps.size)
        for (op in localOps) {
            val bytes = opToJson(op).toByteArray(Charsets.UTF_8)
            output.writeInt(bytes.size)
            output.write(bytes)
        }
        output.flush()

        val count = input.readInt()
        if (count < 0 || count > MAX_FRAME_OPS) {
            throw IOException("Peer sent implausible op count: $count")
        }
        val received = ArrayList<LedgerOpEntity>(count)
        for (n in 0 until count) {
            val len = input.readInt()
            if (len < 0 || len > MAX_FRAME_BYTES) {
                throw IOException("Peer sent implausible frame size: $len")
            }
            val buffer = ByteArray(len)
            input.readFully(buffer)
            received.add(opFromJson(String(buffer, Charsets.UTF_8)))
            if (count > 1) {
                onEvent(SyncEvent.Progress("Received op ${n + 1}/$count"))
            }
        }
        return received
    }

    private fun opToJson(op: LedgerOpEntity): String = JSONObject()
        .put("opId", op.opId)
        .put("hlcWall", op.hlcWall)
        .put("hlcCounter", op.hlcCounter)
        .put("nodeId", op.nodeId)
        .put("type", op.type.name)
        .put("payloadJson", op.payloadJson)
        .put("appliedAtWall", op.appliedAtWall)
        .toString()

    private fun opFromJson(json: String): LedgerOpEntity {
        val o = JSONObject(json)
        return LedgerOpEntity(
            opId = o.getString("opId"),
            hlcWall = o.getLong("hlcWall"),
            hlcCounter = o.getLong("hlcCounter"),
            nodeId = o.getString("nodeId"),
            type = OpType.valueOf(o.getString("type")),
            payloadJson = o.getString("payloadJson"),
            appliedAtWall = o.getLong("appliedAtWall"),
        )
    }

    companion object {
        val SYNC_UUID: UUID = UUID.fromString("5f1c3b2a-9d4e-4f8a-b7c1-2e3f4a5b6c7d")
        private const val SERVICE_NAME = "BillO"
        private const val MAX_FRAME_OPS = 100_000
        private const val MAX_FRAME_BYTES = 4 * 1024 * 1024
    }
}
