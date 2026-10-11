package com.example.myapplication

import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate

/**
 * Tablet controller: discovers phones running the Dual Cam screen, connects to all of them
 * automatically and starts/stops their recordings. Each phone records with its own settings.
 */
class RemoteControlActivity : AppCompatActivity() {
    private val client by lazy { Nearby.getConnectionsClient(this) }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val phones = linkedMapOf<String, Phone>()

    private lateinit var searchStatus: TextView
    private lateinit var phoneList: LinearLayout

    private inner class Phone(val endpointId: String, val name: String) {
        val view: View = LayoutInflater.from(this@RemoteControlActivity).inflate(R.layout.item_phone, phoneList, false)
        private val nameView: TextView = view.findViewById(R.id.phoneName)
        private val statusView: TextView = view.findViewById(R.id.phoneStatus)
        private val button: Button = view.findViewById(R.id.phoneButton)
        var connected = false
        var status: RemoteLink.Status? = null

        init {
            nameView.text = name
            button.setOnClickListener { send(listOf(this), if (status?.recording == true) RemoteLink.STOP else RemoteLink.START) }
            render()
        }

        fun render() {
            val s = status
            statusView.text = when {
                !connected -> "Connecting…"
                s == null -> "Connected"
                else -> s.text
            }
            statusView.setTextColor(if (s?.warning == true) Color.parseColor("#FF5252") else Color.parseColor("#CCCCCC"))
            nameView.setTextColor(if (s?.recording == true) Color.parseColor("#FF5252") else Color.WHITE)
            nameView.text = if (s?.recording == true) "● $name" else name
            button.isEnabled = connected
            button.text = if (s?.recording == true) "Stop" else "Start"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_remote_control)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        searchStatus = findViewById(R.id.searchStatus)
        phoneList = findViewById(R.id.phoneList)
        findViewById<Button>(R.id.startAll).setOnClickListener { send(phones.values.toList(), RemoteLink.START) }
        findViewById<Button>(R.id.stopAll).setOnClickListener { send(phones.values.toList(), RemoteLink.STOP) }
    }

    override fun onResume() {
        super.onResume()
        val missing = RemoteLink.permissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startDiscovery() else requestPermissions(missing.toTypedArray(), 1)
        UpdateChecker.check(this) { android.util.Log.i("DUALCAM", it) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) startDiscovery()
        else searchStatus.text = "Nearby permissions are required to find the phones"
    }

    override fun onPause() {
        client.stopDiscovery()
        client.stopAllEndpoints()
        phones.clear()
        phoneList.removeAllViews()
        super.onPause()
    }

    private fun startDiscovery() {
        val options = DiscoveryOptions.Builder().setStrategy(RemoteLink.STRATEGY).build()
        client.startDiscovery(RemoteLink.SERVICE_ID, discovery, options)
            .addOnSuccessListener { updateSearchStatus() }
            .addOnFailureListener {
                Log.w(RemoteLink.TAG, "discovery failed", it)
                searchStatus.text = "Discovery failed: ${it.message}"
            }
    }

    private fun updateSearchStatus() {
        val connected = phones.values.count { it.connected }
        searchStatus.text = if (connected == 0) "Searching for phones… open Dual Cam on each phone"
        else "$connected phone${if (connected == 1) "" else "s"} connected · still searching"
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (info.serviceId != RemoteLink.SERVICE_ID || phones.containsKey(endpointId)) return
            val phone = Phone(endpointId, info.endpointName)
            phones[endpointId] = phone
            phoneList.addView(phone.view)
            connect(phone)
        }

        override fun onEndpointLost(endpointId: String) {
            val phone = phones[endpointId] ?: return
            if (!phone.connected) remove(phone)
        }
    }

    private fun connect(phone: Phone) {
        client.requestConnection("Tablet", phone.endpointId, connectionLifecycle).addOnFailureListener {
            Log.w(RemoteLink.TAG, "connection request to ${phone.name} failed", it)
            // Retry while the phone is still discovered.
            mainHandler.postDelayed({ if (phones[phone.endpointId] === phone && !phone.connected) connect(phone) }, 2000)
        }
    }

    private fun remove(phone: Phone) {
        phones.remove(phone.endpointId)
        phoneList.removeView(phone.view)
        updateSearchStatus()
    }

    private val connectionLifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            client.acceptConnection(endpointId, payloads)
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            val phone = phones[endpointId] ?: return
            phone.connected = resolution.status.isSuccess
            phone.render()
            updateSearchStatus()
        }

        override fun onDisconnected(endpointId: String) {
            phones[endpointId]?.let { remove(it) }
        }
    }

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val message = payload.asBytes()?.let { String(it, Charsets.UTF_8) } ?: return
            val phone = phones[endpointId] ?: return
            phone.status = RemoteLink.decodeStatus(message) ?: return
            phone.render()
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    private fun send(targets: List<Phone>, command: String) {
        val ids = targets.filter { it.connected }.map { it.endpointId }
        if (ids.isNotEmpty()) client.sendPayload(ids, RemoteLink.bytes(command))
    }
}
