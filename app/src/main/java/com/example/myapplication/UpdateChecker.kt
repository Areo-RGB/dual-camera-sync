package com.example.myapplication

import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.provider.Settings
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Checks the newest GitHub release of this repo (published by CI on every main
 * push) and installs it if its versionCode is higher than the installed one.
 *
 * Throttled to one check per day. Fully silent when there is nothing new or
 * the network fails; progress is reported through [log].
 */
object UpdateChecker {
    private const val META_URL =
        "https://github.com/Areo-RGB/dual-camera-sync/releases/latest/download/update.json"
    private const val PREFS = "dualcam_settings"
    private const val KEY_LAST_CHECK = "lastUpdateCheck"
    private const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
    private const val APK_MIME = "application/vnd.android.package-archive"

    private data class Update(val versionCode: Long, val versionName: String, val apkUrl: String)

    @Volatile private var inFlight = false

    @Synchronized
    private fun claimInFlight(): Boolean {
        if (inFlight) return false
        inFlight = true
        return true
    }

    fun check(activity: Activity, log: (String) -> Unit) {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (System.currentTimeMillis() - prefs.getLong(KEY_LAST_CHECK, 0) < CHECK_INTERVAL_MS) return
        if (!claimInFlight()) return
        thread(name = "update-check", isDaemon = true) {
            try {
                val update = fetchMeta() ?: run {
                    // No release published yet (or network down): back off for a day.
                    prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
                    return@thread
                }
                val current = activity.packageManager.getPackageInfo(activity.packageName, 0).longVersionCode
                if (update.versionCode <= current) {
                    prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
                    return@thread
                }
                activity.runOnUiThread { log("Update v${update.versionName} available, downloading…") }
                if (!activity.packageManager.canRequestPackageInstalls()) {
                    // Don't stamp the check: retry on next launch after the user grants it.
                    activity.runOnUiThread {
                        log("Allow “Install unknown apps” for Dual Cam, then reopen to update")
                        activity.startActivity(
                            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:${activity.packageName}"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                    return@thread
                }
                prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
                downloadAndInstall(activity.applicationContext, activity, update, log)
            } catch (e: Exception) {
                Log.w("DUALCAM", "update check failed", e)
            } finally {
                inFlight = false
            }
        }
    }

    private fun fetchMeta(): Update? {
        val conn = (URL(META_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("User-Agent", "DualCam-Updater")
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            return Update(
                versionCode = json.getLong("versionCode"),
                versionName = json.getString("versionName"),
                apkUrl = json.getString("apkUrl"),
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun downloadAndInstall(appContext: Context, activity: Activity, update: Update, log: (String) -> Unit) {
        val dm = appContext.getSystemService(DownloadManager::class.java)
        val id = dm.enqueue(
            DownloadManager.Request(Uri.parse(update.apkUrl))
                .setTitle("Dual Cam v${update.versionName}")
                .setDescription("Downloading update")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationInExternalPublicDir(
                    android.os.Environment.DIRECTORY_DOWNLOADS, "dualcam-update.apk")
                .setMimeType(APK_MIME)
        )
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return
                appContext.unregisterReceiver(this)
                val query = DownloadManager.Query().setFilterById(id)
                dm.query(query).use { cursor ->
                    if (!cursor.moveToFirst()) return
                    val status = cursor.getInt(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    if (status != DownloadManager.STATUS_SUCCESSFUL) {
                        activity.runOnUiThread { log("Update download failed") }
                        return
                    }
                }
                val uri = dm.getUriForDownloadedFile(id) ?: return
                activity.runOnUiThread { log("Update downloaded, opening installer…") }
                activity.startActivity(
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, APK_MIME)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                )
            }
        }
        // DownloadManager broadcasts from the system uid, so the receiver must be exported.
        appContext.registerReceiver(
            receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            Context.RECEIVER_EXPORTED)
    }
}
