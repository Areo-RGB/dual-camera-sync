package com.example.myapplication

import android.Manifest
import android.content.Context
import android.os.Build
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Remote start/stop between a controlling tablet and camera phones over Google Nearby
 * Connections: phones advertise, the tablet discovers and connects to all of them (star
 * topology), and connections are accepted without a PIN. Only short text messages are sent:
 *
 * - tablet -> phone: [START] or [STOP]
 * - phone -> tablet: `STATUS<TAB>recording(0/1)<TAB>warning(0/1)<TAB>text`
 */
object RemoteLink {
    const val SERVICE_ID = "com.example.myapplication.dualcam"
    val STRATEGY: Strategy = Strategy.P2P_STAR
    const val START = "START"
    const val STOP = "STOP"
    const val STATUS = "STATUS"
    const val TAG = "REMOTE"

    /** Runtime permissions needed to advertise or discover. */
    fun permissions(): List<String> = buildList {
        add(Manifest.permission.BLUETOOTH_ADVERTISE)
        add(Manifest.permission.BLUETOOTH_CONNECT)
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.NEARBY_WIFI_DEVICES)
        if (Build.VERSION.SDK_INT >= 37) add("android.permission.ACCESS_LOCAL_NETWORK")
    }

    data class Status(val recording: Boolean, val warning: Boolean, val text: String)

    fun encodeStatus(status: Status) =
        listOf(STATUS, if (status.recording) "1" else "0", if (status.warning) "1" else "0", status.text).joinToString("\t")

    fun decodeStatus(message: String): Status? {
        val parts = message.split("\t", limit = 4)
        if (parts.size != 4 || parts[0] != STATUS) return null
        return Status(parts[1] == "1", parts[2] == "1", parts[3])
    }

    fun bytes(message: String): Payload = Payload.fromBytes(message.toByteArray(Charsets.UTF_8))
}

/** Phone side: advertises this device and passes received commands to [onCommand] on the main thread. */
class RemoteAdvertiser(context: Context, private val onCommand: (String) -> Unit, private val onConnected: () -> Unit) {
    private val client = Nearby.getConnectionsClient(context)
    private val endpoints = CopyOnWriteArraySet<String>()
    private var advertising = false

    val connectedCount get() = endpoints.size

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            payload.asBytes()?.let { onCommand(String(it, Charsets.UTF_8)) }
        }
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            client.acceptConnection(endpointId, payloadCallback)
        }
        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            if (resolution.status.isSuccess) {
                endpoints += endpointId
                Log.i(RemoteLink.TAG, "controller connected: $endpointId")
                onConnected()
            }
        }
        override fun onDisconnected(endpointId: String) {
            endpoints -= endpointId
            Log.i(RemoteLink.TAG, "controller disconnected: $endpointId")
        }
    }

    fun start() {
        if (advertising) return
        advertising = true
        val options = AdvertisingOptions.Builder().setStrategy(RemoteLink.STRATEGY).build()
        client.startAdvertising(Build.MODEL, RemoteLink.SERVICE_ID, lifecycle, options)
            .addOnSuccessListener { Log.i(RemoteLink.TAG, "advertising as ${Build.MODEL}") }
            .addOnFailureListener { advertising = false; Log.w(RemoteLink.TAG, "advertising failed", it) }
    }

    fun stop() {
        advertising = false
        client.stopAdvertising()
        client.stopAllEndpoints()
        endpoints.clear()
    }

    fun send(status: RemoteLink.Status) {
        if (endpoints.isEmpty()) return
        client.sendPayload(endpoints.toList(), RemoteLink.bytes(RemoteLink.encodeStatus(status)))
    }
}
