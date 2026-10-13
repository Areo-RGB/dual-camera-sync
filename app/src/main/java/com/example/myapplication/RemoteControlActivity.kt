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
 * automatically and starts/stops their recordings. Each phone reports its settings and cameras,
 * and its row offers those settings with that phone's own options; a change made here
 * is sent to that phone straight away.
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
        private val settingsToggle: Button = view.findViewById(R.id.phoneSettingsToggle)
        private val settings = PhoneSettings(view) { send(listOf(this), RemoteLink.encodeSettings(it)) }
        var connected = false
        var status: RemoteLink.Status? = null

        init {
            nameView.text = name
            button.setOnClickListener { send(listOf(this), if (status?.recording == true) RemoteLink.STOP else RemoteLink.START) }
            settingsToggle.setOnClickListener { settings.visible = !settings.visible; render() }
            render()
        }

        fun showSettings(values: Map<String, String>) {
            settings.show(values)
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
            // Phones ignore settings while recording.
            settings.enabled = connected && s?.recording != true
            settingsToggle.isEnabled = settings.known
            settingsToggle.alpha = if (settings.known) 1f else 0.4f
            settingsToggle.text = if (settings.visible) "Hide settings" else "Settings"
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
        }
        // Retry while the phone is still discovered. Covers refused requests and ones that never
        // complete, e.g. when the phone restarts advertising mid-handshake; those block new
        // requests to the endpoint until cancelled.
        mainHandler.postDelayed({
            if (phones[phone.endpointId] === phone && !phone.connected) {
                client.disconnectFromEndpoint(phone.endpointId)
                connect(phone)
            }
        }, 10_000)
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
            RemoteLink.decodeSettings(message)?.let { phone.showSettings(it); return }
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

/**
 * One phone's recording settings in its row on the tablet. Filled from what the phone reports
 * (including which cameras it has) and calls [onChange] with all values when the user picks one.
 */
private class PhoneSettings(root: View, private val onChange: (Map<String, String>) -> Unit) {
    private val panel: View = root.findViewById(R.id.phoneSettings)
    private val slot2Column: View = root.findViewById(R.id.slot2Column)
    private val config1 = CameraConfig(root.findViewById(R.id.config1), {}, ::changed) { applyMode() }
    private val config2 = CameraConfig(root.findViewById(R.id.config2), {}, ::changed) { applyMode() }
    private val slots = Slots(config1, config2)
    private val mode = Dropdown<Mode>(root.findViewById(R.id.modeLayout)) { applyMode(); changed() }
    private val dynamicRange = Dropdown<Boolean>(root.findViewById(R.id.dynamicRangeLayout)) { applyMode(); changed() }
    private val preview = Dropdown<Boolean>(root.findViewById(R.id.previewLayout)) { changed() }
    private val audio = Dropdown<Boolean>(root.findViewById(R.id.audioLayout)) { changed() }

    /** Whether the phone has reported its settings yet. */
    var known = false
        private set

    var visible: Boolean
        get() = panel.visibility == View.VISIBLE
        set(visible) { panel.visibility = if (visible && known) View.VISIBLE else View.GONE }

    var enabled = true
        set(enabled) { field = enabled; updateEnabled() }

    init {
        mode.setOptions(modeOptions)
        dynamicRange.setOptions(dynamicRangeOptions)
        preview.setOptions(previewOptions)
        audio.setOptions(onOffOptions)
    }

    /** Shows the settings the phone reported, in the keys of the [RemoteLink.SETTINGS] message. */
    fun show(values: Map<String, String>) {
        known = true
        val lenses = values["lenses"].orEmpty().split(",").mapNotNull { runCatching { Lens.valueOf(it) }.getOrNull() }
        if (lenses.sorted() != slots.lenses) slots.setLenses(lenses)
        // A single camera leaves nothing for slot 2.
        slot2Column.visibility = if (lenses.size > 1) View.VISIBLE else View.GONE
        values["mode"]?.let { runCatching { Mode.valueOf(it) }.getOrNull() }?.let { mode.select(it, notify = false) }
        values["hdr"]?.let { dynamicRange.select(it.toBoolean(), notify = false) }
        values["preview"]?.let { preview.select(it.toBoolean(), notify = false) }
        values["audio"]?.let { audio.select(it.toBoolean(), notify = false) }
        slots.select(values, mode.value, dynamicRange.value)
        applyMode()
    }

    /** Offers the options valid for the mode and slot cameras, as on the phone: slow-mo is SDR on the main camera. */
    private fun applyMode() {
        if (mode.value == Mode.SLOW_MO) dynamicRange.select(false, notify = false)
        slots.configure(mode.value, dynamicRange.value)
        updateEnabled()
    }

    private fun updateEnabled() {
        if (!known) return
        val dual = mode.value == Mode.DUAL
        mode.enabled = enabled
        dynamicRange.enabled = enabled && dual
        preview.enabled = enabled
        audio.enabled = enabled
        config1.setEnabled(enabled, cameraChoice = enabled && dual && slots.lenses.size > 1)
        config2.setEnabled(enabled, cameraChoice = enabled && dual && slots.lenses.size > 1)
    }

    private fun changed() = onChange(values())

    private fun values(): Map<String, String> = mapOf(
        "mode" to mode.value.name,
        "hdr" to dynamicRange.value.toString(),
        "preview" to preview.value.toString(),
        "audio" to audio.value.toString(),
    ) + slots.values()
}
