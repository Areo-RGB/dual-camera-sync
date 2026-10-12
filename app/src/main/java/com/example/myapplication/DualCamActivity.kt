package com.example.myapplication

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaCodec
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor

/**
 * Records the main and 5x tele cameras simultaneously into two separate HEVC videos, using
 * physical camera streams of the logical rear camera. Resolution, frame rate and bitrate are
 * configured per camera.
 *
 * Slow-mo mode records the main camera alone at 120/240 fps through a constrained high-speed
 * session; the camera offers high-speed video only at 1080p/720p and not on the tele. The file
 * plays back in real time at the high frame rate, ready to be conformed to slow motion.
 *
 * HDR records 10-bit HLG. The camera only allows HLG10 streams to be combined with other HLG10
 * streams, so HDR is a single switch covering both cameras and the previews. HLG at 60 fps is
 * rejected by the HAL (the session fails to reconfigure), so HDR is limited to 30 fps.
 *
 * Audio records the microphone as AAC into the main camera's video file only. The microphone
 * permission is requested when audio is first switched on, and recording continues silent without it.
 *
 * "Preview off" records without the preview streams and dims the screen while recording, to
 * reduce camera processing and display load.
 *
 * Both sensors share one capture request stream, so they run at the higher of the two frame
 * rates; a 30 fps camera paired with a 60 fps one only receives every other frame.
 *
 * Phones without a tele record the main camera alone in the regular mode.
 *
 * The screen follows the device orientation without being recreated, so rotating keeps the camera
 * session, a running recording and the tablet link alive. Each recording is tagged with the
 * orientation the phone was held in when it started.
 *
 * With Remote switched on (off by default), the phone advertises itself over Nearby Connections
 * while this screen is open, so the tablet's [RemoteControlActivity] can start and stop recording
 * and see the phone's status. The Nearby permissions are only requested once Remote is switched on.
 *
 * Auto-test via adb: am start -n com.example.myapplication/.DualCamActivity --ei seconds 10
 *     [--es mode slowmo]
 *     [--es res 4k|1080|720] [--ei fps 60] [--ei bitrate 100]  (both cameras; bitrate in Mbps)
 *     [--es main_res ..] [--ei tele_fps ..] [--ei tele_bitrate ..]  (per camera)
 *     [--ez hdr true] [--ez preview false] [--ez audio true]
 * Results are logged under tag DUALCAM.
 */
class DualCamActivity : AppCompatActivity() {
    private val tag = "DUALCAM"

    private companion object {
        const val AUDIO_PERMISSION_REQUEST = 2
        const val REMOTE_PERMISSION_REQUEST = 3
    }
    private val previewSize = Size(1280, 720)

    private enum class Mode { DUAL, SLOW_MO }

    private lateinit var previewMain: TextureView
    private lateinit var previewTele: TextureView
    private lateinit var status: TextView
    private lateinit var live: TextView
    private lateinit var recordButton: Button
    private lateinit var configMain: CameraConfig
    private lateinit var configTele: CameraConfig
    private lateinit var mode: Dropdown<Mode>
    private lateinit var dynamicRange: Dropdown<Boolean>
    private lateinit var previewWhileRecording: Dropdown<Boolean>
    private lateinit var audio: Dropdown<Boolean>
    private lateinit var remoteEnabled: Dropdown<Boolean>

    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private lateinit var executor: Executor

    private var logicalId = ""
    private var mainId = ""
    private var teleId: String? = null
    private val hasTele get() = teleId != null
    private lateinit var remote: RemoteAdvertiser
    @Volatile private var liveLine = ""
    private var sensorOrientation = 90
    @Volatile private var displayRotation = Surface.ROTATION_0
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var recording: Recording? = null
    private var autoTest = false

    /** [bitrateMbps] of 0 means automatic, scaled from resolution and frame rate. */
    private data class Settings(val size: Size, val fps: Int, val bitrateMbps: Int, val hdr: Boolean) {
        val bitrate: Int get() =
            if (bitrateMbps > 0) bitrateMbps * 1_000_000
            else (size.width.toLong() * size.height * fps / 8).coerceIn(8_000_000, 80_000_000).toInt()

        override fun toString(): String {
            val res = if (size.height >= 2160) "4K" else "${size.height}p"
            return "$res$fps ${bitrate / 1_000_000}M${if (hdr) " HLG" else ""}"
        }
    }

    /** The resolution / frame rate / bitrate dropdowns of one camera. */
    private class CameraConfig(root: View, onFpsChange: () -> Unit, onSave: () -> Unit = {}) {
        val resolution = Dropdown<Size>(root.findViewById(R.id.resolutionLayout)) { onSave() }
        val fps = Dropdown<Int>(root.findViewById(R.id.fpsLayout)) { onFpsChange(); onSave() }
        val bitrate = Dropdown<Int>(root.findViewById(R.id.bitrateLayout)) { onSave() }

        init {
            bitrate.setOptions(listOf("Auto" to 0, "50 Mbps" to 50, "100 Mbps" to 100, "150 Mbps" to 150))
        }

        fun configure(mode: Mode, hdr: Boolean) {
            when (mode) {
                Mode.DUAL -> {
                    resolution.setOptions(listOf("1080p" to Size(1920, 1080), "4K" to Size(3840, 2160)))
                    fps.setOptions(if (hdr) listOf("30 fps" to 30) else listOf("30 fps" to 30, "60 fps" to 60))
                }
                Mode.SLOW_MO -> {
                    resolution.setOptions(listOf("720p" to Size(1280, 720), "1080p" to Size(1920, 1080)))
                    fps.setOptions(listOf("120 fps" to 120, "240 fps" to 240))
                }
            }
        }

        fun settings(hdr: Boolean) = Settings(resolution.value, fps.value, bitrate.value, hdr)

        fun apply(res: String?, fpsValue: Int, bitrateMbps: Int) {
            when (res) {
                "4k" -> Size(3840, 2160)
                "1080" -> Size(1920, 1080)
                "720" -> Size(1280, 720)
                else -> null
            }?.let { resolution.select(it, notify = false) }
            fps.select(fpsValue, notify = false)
            bitrate.select(bitrateMbps, notify = false)
        }

        fun setEnabled(enabled: Boolean) {
            resolution.enabled = enabled
            fps.enabled = enabled
            bitrate.enabled = enabled
        }
    }

    private class Track(val name: String, val settings: Settings, val physicalId: String,
                        val recorder: MediaRecorder, val surface: Surface,
                        val uri: Uri, val pfd: ParcelFileDescriptor) {
        var frames = 0
        var lost = 0
        var firstTs = 0L
        var lastTs = 0L
        private var tickDelivered = 0

        val delivered get() = frames - lost

        /** Frames delivered since the previous call. */
        fun deliveredSinceTick() = (delivered - tickDelivered).also { tickDelivered = delivered }

        fun onFrame(ts: Long) {
            if (frames == 0) firstTs = ts
            lastTs = ts
            frames++
        }

        fun measuredFps(): Double {
            val seconds = (lastTs - firstTs) / 1e9
            return if (seconds > 0) (delivered - 1).coerceAtLeast(0) / seconds else 0.0
        }
    }

    /**
     * [previews] are the preview surfaces targeted while recording; empty with preview off.
     * [offscreen] backs an undisplayed preview stream that slow-mo needs even with preview off.
     */
    private class Recording(val tracks: List<Track>, val previews: List<Surface>, val offscreen: SurfaceTexture? = null) {
        val sensorFps = tracks.maxOf { it.settings.fps }
        val withPreview get() = previews.isNotEmpty() && offscreen == null
        var failures = 0
        var lastResult: TotalCaptureResult? = null
    }

    private val log = ArrayDeque<String>()

    private fun out(msg: String) {
        Log.i(tag, msg)
        runOnUiThread {
            log.addLast(msg)
            while (log.size > 14) log.removeFirst()
            status.text = log.joinToString("\n")
        }
    }

    private val prefs by lazy { getSharedPreferences("dualcam_settings", MODE_PRIVATE) }

    /**
     * While true, [saveSettings] is a no-op. Set during [onCreate] so the initial
     * [applyMode] calls don't overwrite the saved values before they are restored.
     */
    private var suppressSave = true

    private fun Size.serialize() = "${width}x${height}"

    private fun parseSize(s: String?): Size? = try {
        s?.split("x")?.let { Size(it[0].toInt(), it[1].toInt()) }?.takeIf { it.width > 0 && it.height > 0 }
    } catch (e: Exception) {
        null
    }

    /** Persists the current UI selections so the next launch restores them. */
    private fun saveSettings() {
        if (suppressSave) return
        if (!::mode.isInitialized || !::configMain.isInitialized || !::configTele.isInitialized ||
            !::previewWhileRecording.isInitialized || !::dynamicRange.isInitialized || !::audio.isInitialized ||
            !::remoteEnabled.isInitialized
        ) return
        prefs.edit()
            .putString("mode", mode.value.name)
            .putBoolean("hdr", dynamicRange.value)
            .putBoolean("preview", previewWhileRecording.value)
            .putBoolean("audio", audio.value)
            .putBoolean("remote", remoteEnabled.value)
            .putString("main_res", configMain.resolution.value.serialize())
            .putInt("main_fps", configMain.fps.value)
            .putInt("main_bitrate", configMain.bitrate.value)
            .putString("tele_res", configTele.resolution.value.serialize())
            .putInt("tele_fps", configTele.fps.value)
            .putInt("tele_bitrate", configTele.bitrate.value)
            .apply()
    }

    /** Restores one camera's selections silently; falls back to [defaultRes] if nothing was saved. */
    private fun CameraConfig.restore(prefix: String, defaultRes: Size? = null) {
        prefs.getString("${prefix}_res", null)?.let { parseSize(it) }?.let { resolution.select(it, notify = false) }
            ?: defaultRes?.let { resolution.select(it, notify = false) }
        prefs.getInt("${prefix}_fps", -1).takeIf { it > 0 }?.let { fps.select(it, notify = false) }
        prefs.getInt("${prefix}_bitrate", -1).takeIf { it >= 0 }?.let { bitrate.select(it, notify = false) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dual_cam)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        previewMain = findViewById(R.id.previewMain)
        previewTele = findViewById(R.id.previewTele)
        applyOrientation(resources.configuration)
        status = findViewById(R.id.status)
        live = findViewById(R.id.live)
        recordButton = findViewById(R.id.record)

        thread = HandlerThread("camera").apply { start() }
        handler = Handler(thread.looper)
        executor = Executor { handler.post(it) }

        findCameras(getSystemService(CameraManager::class.java))
        remote = RemoteAdvertiser(this, onCommand = { handler.post { onRemoteCommand(it) } }, onConnected = { handler.post { sendRemoteStatus() } })

        val restartPreview = { handler.post { startPreview() }; Unit }
        val persist = { saveSettings(); Unit }
        configMain = CameraConfig(findViewById(R.id.configMain), restartPreview, persist)
        configTele = CameraConfig(findViewById(R.id.configTele), restartPreview, persist)
        mode = Dropdown(findViewById(R.id.modeLayout)) { applyMode() }
        mode.setOptions(listOf((if (hasTele) "Dual: main + tele" else "Main only") to Mode.DUAL,
            "Slow-mo: main only" to Mode.SLOW_MO))
        dynamicRange = Dropdown(findViewById(R.id.dynamicRangeLayout)) { applyMode() }
        dynamicRange.setOptions(listOf("SDR" to false, "HDR (HLG 10-bit)" to true))
        previewWhileRecording = Dropdown(findViewById(R.id.previewLayout)) { persist() }
        previewWhileRecording.setOptions(listOf("On" to true, "Off + dim screen" to false))
        audio = Dropdown(findViewById(R.id.audioLayout)) { on ->
            if (on && !hasAudioPermission()) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), AUDIO_PERMISSION_REQUEST)
            persist()
        }
        audio.setOptions(listOf("Off" to false, "On" to true))
        remoteEnabled = Dropdown(findViewById(R.id.remoteLayout)) { on ->
            if (on && !hasRemotePermissions()) requestPermissions(RemoteLink.permissions().toTypedArray(), REMOTE_PERMISSION_REQUEST)
            else applyRemote()
            persist()
        }
        remoteEnabled.setOptions(listOf("Off" to false, "On" to true))
        // Restore last-used settings; unsaved keys keep the SDR / On / 4K defaults.
        prefs.getString("mode", null)?.let { runCatching { Mode.valueOf(it) }.getOrNull() }
            ?.let { mode.select(it, notify = false) }
        dynamicRange.select(prefs.getBoolean("hdr", false), notify = false)
        if (prefs.contains("preview")) previewWhileRecording.select(prefs.getBoolean("preview", true), notify = false)
        audio.select(prefs.getBoolean("audio", false) && hasAudioPermission(), notify = false)
        remoteEnabled.select(prefs.getBoolean("remote", false), notify = false)
        applyMode()
        configMain.restore("main", Size(3840, 2160))
        configTele.restore("tele", Size(3840, 2160))
        applyMode()

        if (intent.hasExtra("seconds")) {
            autoTest = true
            mode.select(if (intent.getStringExtra("mode") == "slowmo") Mode.SLOW_MO else Mode.DUAL, notify = false)
            dynamicRange.select(intent.getBooleanExtra("hdr", false), notify = false)
            applyMode()
            for ((prefix, config) in listOf("main" to configMain, "tele" to configTele)) {
                config.apply(
                    intent.getStringExtra("${prefix}_res") ?: intent.getStringExtra("res"),
                    intent.getIntExtra("${prefix}_fps", intent.getIntExtra("fps", 0)),
                    intent.getIntExtra("${prefix}_bitrate", intent.getIntExtra("bitrate", 0)),
                )
            }
            previewWhileRecording.select(intent.getBooleanExtra("preview", true), notify = false)
            audio.select(intent.getBooleanExtra("audio", false), notify = false)
        }
        suppressSave = false
        saveSettings()

        recordButton.setOnClickListener {
            recordButton.isEnabled = false
            handler.post { if (recording == null) startRecording() else stopRecording() }
        }

        val listener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                updatePreviewTransforms()
                handler.post { startPreview() }
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = updatePreviewTransforms()
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        previewMain.surfaceTextureListener = listener
        previewTele.surfaceTextureListener = listener
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, null)
    }

    /** Catches 180° turns between the two landscape orientations, which change no configuration. */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayChanged(displayId: Int) = updatePreviewTransforms()
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientation(newConfig)
    }

    /** Stacks the previews above the controls in portrait and places them side by side in landscape. */
    private fun applyOrientation(config: Configuration) {
        val landscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE
        val gap = (8 * resources.displayMetrics.density).toInt()
        findViewById<LinearLayout>(R.id.root).orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        findViewById<View>(R.id.cameraPane).layoutParams =
            if (landscape) LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 3f)
            else LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        findViewById<View>(R.id.controlsPane).layoutParams =
            if (landscape) LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 2f).apply { marginStart = gap }
            else LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        previewMain.requestLayout()
        previewTele.requestLayout()
    }

    /**
     * The camera orients preview buffers for the portrait natural orientation, so when the display
     * is turned, rotate them back upright and undo the stretch to the view's turned aspect ratio.
     */
    private fun updatePreviewTransforms() {
        val rotation = display?.rotation ?: Surface.ROTATION_0
        displayRotation = rotation
        for (view in listOf(previewMain, previewTele)) {
            val w = view.width.toFloat()
            val h = view.height.toFloat()
            if (w == 0f || h == 0f) continue
            view.setTransform(Matrix().apply {
                when (rotation) {
                    Surface.ROTATION_90, Surface.ROTATION_270 -> {
                        postScale(h / w, w / h, w / 2, h / 2)
                        postRotate(if (rotation == Surface.ROTATION_90) -90f else 90f, w / 2, h / 2)
                    }
                    Surface.ROTATION_180 -> postRotate(180f, w / 2, h / 2)
                }
            })
        }
    }

    /** Updates the available options for the selected mode and dynamic range. */
    private fun applyMode() {
        val m = mode.value
        if (m == Mode.SLOW_MO) dynamicRange.select(false, notify = false)
        val hdr = dynamicRange.value
        configMain.configure(m, hdr)
        configTele.configure(Mode.DUAL, hdr)
        setControlsEnabled(true)
        previewTele.visibility = if (m == Mode.DUAL && hasTele) View.VISIBLE else View.INVISIBLE
        handler.post { startPreview() }
        saveSettings()
    }

    private fun setControlsEnabled(enabled: Boolean) {
        val dual = mode.value == Mode.DUAL
        configMain.setEnabled(enabled)
        configTele.setEnabled(enabled && dual && hasTele)
        mode.enabled = enabled
        dynamicRange.enabled = enabled && dual
        previewWhileRecording.enabled = enabled
        audio.enabled = enabled
    }

    override fun onResume() {
        super.onResume()
        val missing = (listOf(Manifest.permission.CAMERA) + if (remoteEnabled.value) RemoteLink.permissions() else emptyList())
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
        onPermissionsChanged()
        handler.post(remoteHeartbeat)
        UpdateChecker.check(this, ::out)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == AUDIO_PERMISSION_REQUEST) {
            if (!hasAudioPermission()) { audio.select(false); out("Microphone permission needed for audio") }
            return
        }
        if (requestCode == REMOTE_PERMISSION_REQUEST) {
            if (!hasRemotePermissions()) { remoteEnabled.select(false); out("Nearby permissions needed for tablet remote") }
            else applyRemote()
            return
        }
        onPermissionsChanged()
    }

    private fun hasAudioPermission() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun hasRemotePermissions() = RemoteLink.permissions().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun onPermissionsChanged() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) handler.post { openCamera() }
        else out("Camera permission needed")
        applyRemote()
    }

    /** Advertises to the tablet while Remote is on and its permissions are granted; stops otherwise. */
    private fun applyRemote() {
        if (!remoteEnabled.value) { remote.stop(); return }
        if (hasRemotePermissions()) remote.start()
        else out("Nearby permissions needed for tablet remote")
    }

    override fun onPause() {
        saveSettings()
        handler.removeCallbacks(remoteHeartbeat)
        remote.stop()
        handler.post {
            if (recording != null) stopRecording(restartPreview = false)
            session?.close(); session = null
            device?.close(); device = null
        }
        super.onPause()
    }

    override fun onDestroy() {
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        thread.quitSafely()
        super.onDestroy()
    }

    private fun findCameras(cm: CameraManager): Boolean {
        for (id in cm.cameraIdList) {
            val c = cm.getCameraCharacteristics(id)
            if (c.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) continue
            var main: String? = null
            var tele: String? = null
            for (p in c.physicalCameraIds) {
                val f = cm.getCameraCharacteristics(p).get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)!!.first()
                if (f in 6f..8f && main == null) main = p
                if (f > 15f && tele == null) tele = p
            }
            if (main != null) {
                logicalId = id; mainId = main; teleId = tele
                sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
                return true
            }
        }
        return false
    }

    private fun openCamera() {
        if (device != null) return
        val cm = getSystemService(CameraManager::class.java)
        if (logicalId.isEmpty()) { out("No rear camera with a main lens found"); return }
        out("Logical $logicalId: main=$mainId tele=$teleId")
        cm.openCamera(logicalId, executor, object : CameraDevice.StateCallback() {
            override fun onOpened(d: CameraDevice) { device = d; startPreview() }
            override fun onDisconnected(d: CameraDevice) { d.close(); device = null; out("Camera disconnected") }
            override fun onError(d: CameraDevice, error: Int) { d.close(); device = null; out("Camera error $error") }
        })
    }

    private fun previewSurface(view: TextureView, size: Size = previewSize): Surface? {
        val texture = view.surfaceTexture ?: return null
        texture.setDefaultBufferSize(size.width, size.height)
        return Surface(texture)
    }

    private fun physical(surface: Surface, id: String, hdr: Boolean) = OutputConfiguration(surface).apply {
        setPhysicalCameraId(id)
        if (hdr) dynamicRangeProfile = DynamicRangeProfiles.HLG10
    }

    private fun startPreview() {
        val d = device ?: return
        if (recording != null) return
        val hdr = dynamicRange.value
        // Slow-mo previews in a regular session; the high-speed session only exists while recording.
        // The tele view is hidden in slow-mo, so it may not have a surface.
        val dual = mode.value == Mode.DUAL
        val pMain = previewSurface(previewMain) ?: return
        val pTele = if (dual && hasTele) previewSurface(previewTele) ?: return else null
        val fps = if (dual) maxOf(configMain.settings(hdr).fps, if (hasTele) configTele.settings(hdr).fps else 0) else 30
        val targets = listOfNotNull(pMain to mainId, pTele?.let { it to teleId!! })
        session?.close(); session = null
        try {
        d.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
            targets.map { (surface, id) -> physical(surface, id, hdr) }, executor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    val req = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        targets.forEach { addTarget(it.first) }
                        set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps, fps))
                    }.build()
                    s.setRepeatingRequest(req, null, handler)
                    runOnUiThread { recordButton.isEnabled = true }
                    if (autoTest) { autoTest = false; handler.postDelayed({ startRecording() }, 1500) }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) { out("Preview session failed") }
            }))
        } catch (e: CameraAccessException) {
            Log.w(tag, "preview session failed, retrying", e)
            handler.postDelayed({ startPreview() }, 300)
        }
    }

    private fun createTrack(name: String, settings: Settings, physicalId: String, stamp: String, withAudio: Boolean = false): Track {
        val label = settings.toString().replace(" ", "_")
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "DualCam_${stamp}_${name}_$label.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/DualCam")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)!!
        val pfd = contentResolver.openFileDescriptor(uri, "w")!!
        val surface = MediaCodec.createPersistentInputSurface()
        val size = settings.size
        val mic = withAudio && hasAudioPermission()
        val recorder = MediaRecorder(this).apply {
            if (mic) setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(pfd.fileDescriptor)
            setVideoEncoder(MediaRecorder.VideoEncoder.HEVC)
            if (mic) {
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(48_000)
                setAudioChannels(2)
                setAudioEncodingBitRate(256_000)
            }
            setVideoSize(size.width, size.height)
            setVideoFrameRate(settings.fps)
            setCaptureRate(settings.fps.toDouble())
            setVideoEncodingBitRate(settings.bitrate)
            if (settings.hdr) {
                setVideoEncodingProfileLevel(CodecProfileLevel.HEVCProfileMain10, CodecProfileLevel.HEVCMainTierLevel51)
            }
            // Upright for how the phone is held: the rear sensor turned back by the display rotation.
            setOrientationHint((sensorOrientation - displayRotation * 90 + 360) % 360)
            setInputSurface(surface)
            prepare()
        }
        return Track(name, settings, physicalId, recorder, surface, uri, pfd)
    }

    private fun startRecording() {
        val d = device ?: return
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val tracks = mutableListOf<Track>()
        if (audio.value && !hasAudioPermission()) out("No microphone permission, recording without audio")
        try {
            session?.close(); session = null
            if (mode.value == Mode.SLOW_MO) startSlowMo(d, stamp, tracks) else startDual(d, stamp, tracks)
        } catch (e: Exception) {
            Log.e(tag, "start failed", e)
            out("RESULT: FAILED start: $e")
            tracks.forEach { discardTrack(it) }
            recording = null
            runOnUiThread { recordButton.isEnabled = true }
            startPreview()
        }
    }

    private fun startDual(d: CameraDevice, stamp: String, tracks: MutableList<Track>) {
        val hdr = dynamicRange.value
        tracks += createTrack("main", configMain.settings(hdr), mainId, stamp, withAudio = audio.value)
        teleId?.let { tracks += createTrack("tele", configTele.settings(hdr), it, stamp) }
        val recordOutputs = tracks.map { physical(it.surface, it.physicalId, hdr) }

        // Prefer previews + recordings; fall back to recording-only if the HAL rejects them.
        val previewViews = listOfNotNull(previewMain to mainId, teleId?.let { previewTele to it })
        var previewTargets = emptyList<Pair<Surface, String>>()
        if (previewWhileRecording.value) {
            val candidates = previewViews.mapNotNull { (view, id) -> previewSurface(view)?.let { it to id } }
            if (candidates.size == previewViews.size) {
                val probe = SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                    recordOutputs + candidates.map { (surface, id) -> physical(surface, id, hdr) }, executor,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) {}
                        override fun onConfigureFailed(s: CameraCaptureSession) {}
                    })
                if (d.isSessionConfigurationSupported(probe)) previewTargets = candidates
            }
        }
        val rec = Recording(tracks, previewTargets.map { it.first })
        recording = rec
        if (!rec.withPreview) out("Recording without preview")
        val previewOutputs = previewTargets.map { (surface, id) -> physical(surface, id, hdr) }
        out("Configuring ${tracks.joinToString(" ") { "${it.name}=${it.settings}" }} sensor=${rec.sensorFps}fps")
        d.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
            recordOutputs + previewOutputs, executor, recordingCallback(d, rec)))
    }

    private fun startSlowMo(d: CameraDevice, stamp: String, tracks: MutableList<Track>) {
        val settings = configMain.settings(hdr = false)
        tracks += createTrack("main", settings, mainId, stamp, withAudio = audio.value)
        // High-speed sessions take at most two outputs, both at a high-speed video size, and no
        // physical camera routing; the logical camera at its default 1x zoom streams the main sensor.
        // With the recorder as the only output the high-speed request list holds a single request,
        // which runs at 30 fps, so preview off still streams to an undisplayed surface.
        var offscreen: SurfaceTexture? = null
        val preview = if (previewWhileRecording.value) {
            previewSurface(previewMain, settings.size)
        } else {
            offscreen = SurfaceTexture(false).apply { setDefaultBufferSize(settings.size.width, settings.size.height) }
            Surface(offscreen)
        }
        val previews = listOfNotNull(preview)
        val rec = Recording(tracks, previews, offscreen)
        recording = rec
        if (!rec.withPreview) out("Recording without preview")
        out("Configuring slow-mo main=$settings")
        val outputs = (tracks.map { it.surface } + previews).map { OutputConfiguration(it) }
        d.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_HIGH_SPEED, outputs, executor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    val highSpeed = s as CameraConstrainedHighSpeedCaptureSession
                    val req = d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        tracks.forEach { addTarget(it.surface) }
                        previews.forEach { addTarget(it) }
                        setTag(tracks)
                        set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(settings.fps, settings.fps))
                    }.build()
                    highSpeed.setRepeatingBurst(highSpeed.createHighSpeedRequestList(req), captureCallback(rec), handler)
                    onRecordingStarted(rec)
                }
                override fun onConfigureFailed(s: CameraCaptureSession) = onRecordingConfigureFailed(rec)
            }))
    }

    private fun captureCallback(rec: Recording) = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) {
            val ts = res.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
            rec.lastResult = res
            (r.tag as? List<*>)?.forEach { (it as Track).onFrame(ts) }
        }
        override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, f: CaptureFailure) {
            rec.failures++
        }
        override fun onCaptureBufferLost(s: CameraCaptureSession, r: CaptureRequest, target: Surface, frame: Long) {
            rec.tracks.find { it.surface == target }?.let { it.lost++ }
        }
    }

    private fun recordingCallback(d: CameraDevice, rec: Recording) = object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(s: CameraCaptureSession) {
            session = s
            fun request(tracks: List<Track>) = d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                tracks.forEach { addTarget(it.surface) }
                setTag(tracks)
                rec.previews.forEach { addTarget(it) }
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(rec.sensorFps, rec.sensorFps))
                set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            }.build()

            // Alternate a request for all tracks with one for only the full-rate tracks, so
            // half-rate tracks receive every other frame.
            val burst = mutableListOf(request(rec.tracks))
            val fullRate = rec.tracks.filter { it.settings.fps == rec.sensorFps }
            if (fullRate.size < rec.tracks.size) burst += request(fullRate)
            s.setRepeatingBurst(burst, captureCallback(rec), handler)
            onRecordingStarted(rec)
        }

        override fun onConfigureFailed(s: CameraCaptureSession) = onRecordingConfigureFailed(rec)
    }

    private fun onRecordingStarted(rec: Recording) {
        rec.tracks.forEach { it.recorder.start() }
        out("Recording...")
        runOnUiThread {
            recordButton.text = "Stop"
            recordButton.isEnabled = true
            setControlsEnabled(false)
            setRecordingDisplay(dim = !rec.withPreview)
        }
        val seconds = intent.getIntExtra("seconds", 0)
        if (seconds > 0) handler.postDelayed({ if (recording === rec) stopRecording() }, seconds * 1000L)
        startLiveStats(rec)
    }

    private fun onRecordingConfigureFailed(rec: Recording) {
        rec.offscreen?.release()
        out("RESULT: FAILED session configure ${rec.tracks.joinToString { "${it.name}=${it.settings}" }}")
        rec.tracks.forEach { discardTrack(it) }
        recording = null
        runOnUiThread { recordButton.isEnabled = true }
        startPreview()
    }

    /** Hides the previews and drops the screen to minimum brightness, or restores both. */
    private fun setRecordingDisplay(dim: Boolean) {
        previewMain.visibility = if (dim) View.INVISIBLE else View.VISIBLE
        previewTele.visibility = if (dim || mode.value != Mode.DUAL || !hasTele) View.INVISIBLE else View.VISIBLE
        window.attributes = window.attributes.apply {
            screenBrightness = if (dim) 0.01f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
    }

    /** Shows the frames each camera actually delivered per second while recording. */
    private fun startLiveStats(rec: Recording) {
        val started = System.nanoTime()
        rec.tracks.forEach { it.deliveredSinceTick() }
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (recording !== rec) { liveLine = ""; runOnUiThread { live.text = "" }; return }
                val elapsed = ((System.nanoTime() - started) / 1e9).toInt()
                val parts = rec.tracks.map { t ->
                    val fps = t.deliveredSinceTick()
                    "${t.name} ${fps}fps" + if (fps == 0) " NO FRAMES" else ""
                }
                val line = "REC %d:%02d  %s".format(elapsed / 60, elapsed % 60, parts.joinToString("  "))
                if (elapsed % 10 == 0) Log.i(tag, "live $line")
                liveLine = line
                runOnUiThread { live.text = line }
                handler.postDelayed(this, 1000)
            }
        }, 1000)
    }

    private fun onRemoteCommand(command: String) {
        Log.i(tag, "remote command $command")
        when (command) {
            RemoteLink.START -> if (recording == null && device != null) startRecording()
            RemoteLink.STOP -> if (recording != null) stopRecording()
        }
        sendRemoteStatus()
    }

    /** Sends the phone's status to the tablet every second while the screen is open. */
    private val remoteHeartbeat = object : Runnable {
        override fun run() {
            sendRemoteStatus()
            handler.postDelayed(this, 1000)
        }
    }

    private fun sendRemoteStatus() {
        if (remote.connectedCount == 0) return
        val thermal = getSystemService(PowerManager::class.java).currentThermalStatus
        val thermalText = if (thermal > 0) "  thermal $thermal" else ""
        val rec = recording
        val text = when {
            rec != null -> liveLine.ifEmpty { "Starting…" } + thermalText
            device == null -> "Camera not ready"
            else -> {
                val hdr = dynamicRange.value
                val settings = if (mode.value == Mode.SLOW_MO) "slow-mo main=${configMain.settings(false)}"
                else listOfNotNull("main=${configMain.settings(hdr)}", if (hasTele) "tele=${configTele.settings(hdr)}" else null).joinToString("  ")
                "Ready  $settings$thermalText"
            }
        }
        val warning = text.contains("NO FRAMES") || thermal >= PowerManager.THERMAL_STATUS_MODERATE
        remote.send(RemoteLink.Status(rec != null, warning, text))
    }

    private fun stopRecording(restartPreview: Boolean = true) {
        val rec = recording ?: return
        session?.stopRepeating()
        session?.close(); session = null
        for (t in rec.tracks) {
            runCatching { t.recorder.stop() }.onFailure { out("${t.name} stop failed: $it") }
            t.recorder.release()
            t.surface.release()
            t.pfd.close()
            contentResolver.update(t.uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        }
        rec.offscreen?.release()
        recording = null
        rec.lastResult?.let { logFocus(it) }
        for (t in rec.tracks) {
            out("${t.name} ${t.settings}: ${t.delivered} frames, ${t.lost} lost, %.1f fps".format(t.measuredFps()))
        }
        out("Saved to Movies/DualCam (${rec.failures} capture failures)")
        out("RESULT: DONE " + rec.tracks.joinToString(", ") {
            "${it.name}=${it.settings} %.1f fps (${it.lost} lost)".format(it.measuredFps())
        } + ", preview=${rec.withPreview}")
        runOnUiThread {
            recordButton.text = "Record"
            setControlsEnabled(true)
            setRecordingDisplay(dim = false)
        }
        if (restartPreview) startPreview()
    }

    private fun logFocus(result: TotalCaptureResult) {
        val afModes = mapOf(0 to "OFF", 1 to "AUTO", 2 to "MACRO", 3 to "CONTINUOUS_VIDEO", 4 to "CONTINUOUS_PICTURE", 5 to "EDOF")
        val afStates = mapOf(0 to "INACTIVE", 1 to "PASSIVE_SCAN", 2 to "PASSIVE_FOCUSED", 3 to "ACTIVE_SCAN",
            4 to "FOCUSED_LOCKED", 5 to "NOT_FOCUSED_LOCKED", 6 to "PASSIVE_UNFOCUSED")
        fun describe(r: CaptureResult): String {
            val mode = afModes[r.get(CaptureResult.CONTROL_AF_MODE)]
            val state = afStates[r.get(CaptureResult.CONTROL_AF_STATE)]
            val diopters = r.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: 0f
            val distance = if (diopters > 0f) "%.2fm".format(1f / diopters) else "inf"
            return "af=$mode state=$state focus=%.2f dpt ($distance)".format(diopters)
        }
        val aeStates = mapOf(0 to "INACTIVE", 1 to "SEARCHING", 2 to "CONVERGED", 3 to "LOCKED",
            4 to "FLASH_REQUIRED", 5 to "PRECAPTURE")
        val awbStates = mapOf(0 to "INACTIVE", 1 to "SEARCHING", 2 to "CONVERGED", 3 to "LOCKED")
        fun exposure(r: CaptureResult): String {
            val ns = r.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
            val shutter = if (ns > 0) "1/${(1e9 / ns).toInt()}s" else "?"
            return "ae=${aeStates[r.get(CaptureResult.CONTROL_AE_STATE)]} awb=${awbStates[r.get(CaptureResult.CONTROL_AWB_STATE)]} " +
                "shutter=$shutter iso=${r.get(CaptureResult.SENSOR_SENSITIVITY)}"
        }
        out("Focus logical: ${describe(result)}")
        out("Exposure logical: ${exposure(result)}")
        for ((id, r) in result.physicalCameraTotalResults) {
            val name = if (id == mainId) "main" else if (id == teleId) "tele" else id
            out("Focus $name: ${describe(r)}")
            out("Exposure $name: ${exposure(r)}")
        }
    }

    private fun discardTrack(t: Track) {
        t.recorder.release()
        t.surface.release()
        t.pfd.close()
        contentResolver.delete(t.uri, null, null)
    }
}
