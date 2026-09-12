package com.lumora.data.update

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import com.lumora.BaseApplication
import okhttp3.Request
import java.io.File

/**
 * What an update hand-off looks like on a later check: true when that build is what's installed,
 * false when a different build was installed since (a sideload over it), null when nothing was -
 * the install was cancelled or failed.
 *
 * The installer reports nothing back (ACTION_VIEW) and a successful install kills this process, so
 * the hand-off is recorded before the installer opens and only settled here. versionCode can't
 * tell: builds between releases share one, and the Dropbox file name is the version. The install
 * time says whether anything was installed since; the size says whether it was the file that was
 * downloaded, since Android installs an APK's bytes as they are.
 */
internal fun pendingInstallResult(updateTimeAtHandoff: Long, updateTimeNow: Long, downloadedSize: Long, installedSize: Long): Boolean? = when {
    updateTimeNow == updateTimeAtHandoff -> null
    installedSize == downloadedSize -> true
    else -> false
}

/**
 * Checks for app updates against one Dropbox file.
 *
 * Deployment is "replace the APK behind [UPDATE_URL] and rename it" (Replace keeps the share
 * link, which follows the file id): the file name is the version - lumoraV1.apk, lumoraV1.1.apk.
 * Upload the armeabi-v7a APK: every Fire TV runs it, at half the universal's size.
 * A HEAD on the direct link returns that name in Content-Disposition, but only with
 * `Accept-Encoding: identity`; with OkHttp's default gzip Dropbox drops most headers.
 *
 * The name of the build this install came from is remembered, and a different name on Dropbox
 * is the update. Nothing else on the link is usable: the etag turned out to follow the file,
 * not its content (a Replace kept it), and a size that merely differs from the installed APK
 * says "different build", not "newer" - it nagged every launch on any build that wasn't the
 * exact bytes on Dropbox, including ones built after them. A fresh sideload just records the
 * name it finds, so keep the initial-install APK the same build as the update link's.
 *
 * An update's name is remembered only once the install is confirmed ([pendingInstallResult]).
 * It used to be written as the download was handed to the installer, so a cancelled or failed
 * install read as updated and that update was never offered again.
 */
class AppUpdateChecker(private val context: Context) {

    private val TAG = "AppUpdate"

    data class UpdateInfo(
        /** The file's current name on Dropbox, e.g. "lumoraV1.1.apk"; the version as far as the app knows. */
        val name: String,
        val currentVersion: String,
        val downloadUrl: String,
        val isUpdateAvailable: Boolean,
        /** This update was handed to the installer before and never installed (cancelled or failed). */
        val lastAttemptFailed: Boolean = false,
        /** The published build can't run on this device, so it is never offered here. */
        val incompatible: Boolean = false
    )

    suspend fun checkForUpdate(): UpdateInfo? {
        return try {
            val request = Request.Builder().url(UPDATE_URL).head().header("Accept-Encoding", "identity").build()
            // use{}: a non-2xx early return used to leak the response and its pooled connection.
            BaseApplication.instance.okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Update check: HTTP ${response.code}")
                    return@use null
                }
                val name = response.header("Content-Disposition")
                    ?.let { Regex("filename=\"?([^\";]+)").find(it)?.groupValues?.get(1) }
                    ?: return@use null
                // The Dropbox file is the armeabi-v7a build. A device that can't run it - the x86
                // FireTV_API25 AVD, a 64-bit-only phone - gets "app isn't compatible" from the
                // installer, and with the install check below it would be offered the same download
                // again on every launch. Nothing is recorded for it either: it isn't that build.
                if (UPDATE_ABI !in Build.SUPPORTED_ABIS) {
                    Log.i(TAG, "Dropbox: $name is $UPDATE_ABI; this device runs ${Build.SUPPORTED_ABIS.joinToString()} - not offered")
                    return@use UpdateInfo(name, "", UPDATE_URL, isUpdateAvailable = false, incompatible = true)
                }
                val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val attemptFailed = settlePendingInstall(prefs)
                val installed = prefs.getString(KEY_NAME, null)
                val newer = installed != null && installed != name
                Log.i(TAG, "Dropbox: $name; installed: $installed -> ${if (newer) "update" else "current"}")
                if (installed == null) prefs.edit().putString(KEY_NAME, name).apply()
                val currentVersion = try {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
                } catch (e: Exception) { "1.0" }
                UpdateInfo(
                    name = name,
                    currentVersion = currentVersion,
                    downloadUrl = UPDATE_URL,
                    isUpdateAvailable = newer,
                    lastAttemptFailed = attemptFailed && newer
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Update check failed: ${e.message}")
            null
        }
    }

    /** Settles the last hand-off ([pendingInstallResult]); true while it has not installed. */
    private fun settlePendingInstall(prefs: SharedPreferences): Boolean {
        val pending = prefs.getString(KEY_PENDING_NAME, null) ?: return false
        val updateTime = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        val installedSize = File(context.applicationInfo.sourceDir).length()
        val result = pendingInstallResult(
            prefs.getLong(KEY_PENDING_UPDATE_TIME, 0L), updateTime, prefs.getLong(KEY_PENDING_SIZE, -1L), installedSize
        )
        Log.i(TAG, "Update hand-off to $pending: ${when (result) { true -> "installed"; false -> "another build installed over it"; null -> "not installed" }}")
        if (result == null) return true
        val edit = prefs.edit().remove(KEY_PENDING_NAME).remove(KEY_PENDING_SIZE).remove(KEY_PENDING_UPDATE_TIME)
        if (result) edit.putString(KEY_NAME, pending)
        edit.apply()
        // Installed (or overtaken by a sideload): the download has done its job. The rollback copy stays.
        AppUpdateInstaller(context).deleteDownloads()
        return false
    }

    companion object {
        /** Direct (`dl=1`) Dropbox link to the current build. Upload the new APK over this file
         *  (Replace) so the link keeps pointing at it, then rename it: the name change is what
         *  every install sees as the update. A fresh upload would be a new link. */
        const val UPDATE_URL =
            "https://www.dropbox.com/scl/fi/f1sewaklpbpjc1dra235g/lumoraV1.apk?rlkey=1iua0e0ueohisostvqdm3rvvr&st=25bsoozl&dl=1"
        /** What the file behind [UPDATE_URL] is built for - see the class comment. */
        private const val UPDATE_ABI = "armeabi-v7a"
        private const val PREFS = "app_update"
        private const val KEY_NAME = "installed_name"
        private const val KEY_PENDING_NAME = "pending_name"
        private const val KEY_PENDING_SIZE = "pending_size"
        private const val KEY_PENDING_UPDATE_TIME = "pending_update_time"
        private const val KEY_PREVIOUS_NAME = "previous_name"

        private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        /** The build this install is, as far as the checks know; null before the first one. */
        fun installedName(context: Context): String? = prefs(context).getString(KEY_NAME, null)

        /** The build [AppUpdateInstaller.previousApk] holds. */
        fun previousName(context: Context): String? = prefs(context).getString(KEY_PREVIOUS_NAME, null)

        fun setPreviousName(context: Context, name: String) {
            prefs(context).edit().putString(KEY_PREVIOUS_NAME, name).apply()
        }

        /** Records the update about to go to the installer; the next check confirms it or offers it
         *  again ([pendingInstallResult]). commit(), not apply(): a successful install kills this
         *  process, and an apply() still queued would lose the record and re-offer an installed update. */
        fun markPending(context: Context, name: String, apk: File) {
            val updateTime = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_PENDING_NAME, name)
                .putLong(KEY_PENDING_SIZE, apk.length())
                .putLong(KEY_PENDING_UPDATE_TIME, updateTime)
                .commit()
        }
    }
}
