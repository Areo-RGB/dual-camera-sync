package com.example.myapplication

import android.util.Size
import android.view.View

/**
 * Recording options shared by the camera screen and the tablet remote, so the tablet offers
 * exactly the choices the phones accept.
 */
enum class Mode { DUAL, SLOW_MO }

/** A rear camera; [label] is shown, [fileName] goes into file names and status. */
enum class Lens(val label: String) {
    WIDE("Ultrawide"), MAIN("Main 1x"), TELE("Tele 5x");

    val fileName get() = name.lowercase()
}

/** DUAL records the two slots (or slot 1 alone with slot 2 off). */
val modeOptions = listOf("Video" to Mode.DUAL, "Slow-mo: main only" to Mode.SLOW_MO)

val dynamicRangeOptions = listOf("SDR" to false, "HDR (HLG 10-bit)" to true)
val previewOptions = listOf("On" to true, "Off + dim screen" to false)
val onOffOptions = listOf("Off" to false, "On" to true)

fun Size.serialize() = "${width}x${height}"

fun parseSize(s: String?): Size? = try {
    s?.split("x")?.let { Size(it[0].toInt(), it[1].toInt()) }?.takeIf { it.width > 0 && it.height > 0 }
} catch (e: Exception) {
    null
}

/** [bitrateMbps] of 0 means automatic, scaled from resolution and frame rate. */
data class RecordSettings(val size: Size, val fps: Int, val bitrateMbps: Int, val hdr: Boolean) {
    val bitrate: Int get() =
        if (bitrateMbps > 0) bitrateMbps * 1_000_000
        else (size.width.toLong() * size.height * fps / 8).coerceIn(8_000_000, 80_000_000).toInt()

    override fun toString(): String {
        val res = if (size.height >= 2160) "4K" else "${size.height}p"
        return "$res$fps ${bitrate / 1_000_000}M${if (hdr) " HLG" else ""}"
    }
}

/** The camera / resolution / frame rate / bitrate dropdowns of one recording slot; no camera is off. */
class CameraConfig(root: View, onFpsChange: () -> Unit, onSave: () -> Unit = {}, onCameraChange: () -> Unit = {}) {
    val camera = Dropdown<Lens?>(root.findViewById(R.id.cameraLayout)) { onCameraChange(); onSave() }
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

    fun settings(hdr: Boolean) = RecordSettings(resolution.value, fps.value, bitrate.value, hdr)

    /** The selections as `<prefix>_cam` ([Lens] name or OFF), `<prefix>_res`, `<prefix>_fps` and `<prefix>_bitrate` values. */
    fun values(prefix: String) = mapOf(
        "${prefix}_cam" to (camera.value?.name ?: OFF),
        "${prefix}_res" to resolution.value.serialize(),
        "${prefix}_fps" to fps.value.toString(),
        "${prefix}_bitrate" to bitrate.value.toString(),
    )

    /** Selects the resolution, frame rate and bitrate stored under [prefix] (see [values]) that are offered. */
    fun select(prefix: String, values: Map<String, String>) {
        values["${prefix}_res"]?.let { parseSize(it) }?.let { resolution.select(it, notify = false) }
        values["${prefix}_fps"]?.toIntOrNull()?.let { fps.select(it, notify = false) }
        values["${prefix}_bitrate"]?.toIntOrNull()?.let { bitrate.select(it, notify = false) }
    }

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

    /** Enables the camera choice and, if [enabled] and a camera is chosen, its recording options. */
    fun setEnabled(enabled: Boolean, cameraChoice: Boolean = enabled) {
        camera.enabled = cameraChoice
        @Suppress("NAME_SHADOWING") val enabled = enabled && camera.value != null
        resolution.enabled = enabled
        fps.enabled = enabled
        bitrate.enabled = enabled
    }
}

private const val OFF = "OFF"

/**
 * The two recording slots. Slot 1 records one of the phone's [lenses], slot 2 another one or
 * nothing, as the same sensor can't feed two streams; picking slot 2's camera for slot 1 swaps
 * them. Slow-mo records the main camera alone, and the slots return when leaving it.
 */
class Slots(val slot1: CameraConfig, val slot2: CameraConfig) {
    var lenses: List<Lens> = emptyList()
        private set
    private var slowMo = false
    private var last1: Lens? = null
    private var saved1: Lens? = null
    private var saved2: Lens? = null

    /** Sets the phone's cameras (main if unknown) and selects main plus, if present, tele or else ultrawide. */
    fun setLenses(lenses: Collection<Lens>) {
        this.lenses = lenses.sorted().ifEmpty { listOf(Lens.MAIN) }
        slowMo = false
        slot1.camera.setOptions(lensOptions(this.lenses), keep = Lens.MAIN)
        slot2.camera.setOptions(lensOptions(this.lenses.filter { it != slot1.camera.value }) + ("Off" to null),
            keep = listOf(Lens.TELE, Lens.WIDE).firstOrNull { it in this.lenses && it != slot1.camera.value })
        last1 = slot1.camera.value
    }

    /** Re-offers the cameras and the recording options of [mode] and dynamic range [hdr]. */
    fun configure(mode: Mode, hdr: Boolean) {
        if (mode == Mode.SLOW_MO) {
            if (!slowMo) { saved1 = slot1.camera.value; saved2 = slot2.camera.value }
            slowMo = true
            slot1.camera.setOptions(listOf(Lens.MAIN.label to Lens.MAIN))
            slot2.camera.setOptions(listOf("Off" to null))
        } else {
            val keep1 = if (slowMo) saved1 else slot1.camera.value
            var keep2 = if (slowMo) saved2 else slot2.camera.value
            slowMo = false
            slot1.camera.setOptions(lensOptions(lenses), keep1)
            val lens1 = slot1.camera.value
            if (keep2 == lens1) keep2 = last1
            slot2.camera.setOptions(lensOptions(lenses.filter { it != lens1 }) + ("Off" to null), keep2)
            last1 = lens1
        }
        slot1.configure(mode, hdr)
        slot2.configure(Mode.DUAL, hdr)
    }

    /** All selections, as `slot1_*` and `slot2_*` values. */
    fun values() = slot1.values("slot1") + slot2.values("slot2")

    /** Selects the [values] (see [values]) that are offered, then re-offers for [mode] and [hdr]. */
    fun select(values: Map<String, String>, mode: Mode, hdr: Boolean) {
        values["slot1_cam"]?.let { lensOf(it) }?.let { slot1.camera.select(it, notify = false) }
        configure(mode, hdr)
        values["slot2_cam"]?.let { slot2.camera.select(if (it == OFF) null else lensOf(it) ?: return@let, notify = false) }
        configure(mode, hdr)
        slot1.select("slot1", values)
        slot2.select("slot2", values)
    }

    private fun lensOf(name: String) = runCatching { Lens.valueOf(name) }.getOrNull()

    private fun lensOptions(lenses: List<Lens>): List<Pair<String, Lens?>> = lenses.map { it.label to it }
}
