package com.lumora.data.update

import android.content.Context
import android.util.Log
import com.lumora.BaseApplication
import okhttp3.Request

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
 */
class AppUpdateChecker(private val context: Context) {

    private val TAG = "AppUpdate"

    data class UpdateInfo(
        /** The file's current name on Dropbox, e.g. "lumoraV1.1.apk"; the version as far as the app knows. */
        val name: String,
        val currentVersion: String,
        val downloadUrl: String,
        val isUpdateAvailable: Boolean
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
                val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
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
                    isUpdateAvailable = newer
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Update check failed: ${e.message}")
            null
        }
    }

    companion object {
        /** Direct (`dl=1`) Dropbox link to the current build. Upload the new APK over this file
         *  (Replace) so the link keeps pointing at it, then rename it: the name change is what
         *  every install sees as the update. A fresh upload would be a new link. */
        const val UPDATE_URL =
            "https://www.dropbox.com/scl/fi/f1sewaklpbpjc1dra235g/lumoraV1.apk?rlkey=1iua0e0ueohisostvqdm3rvvr&st=25bsoozl&dl=1"
        private const val PREFS = "app_update"
        private const val KEY_NAME = "installed_name"

        /** Called once the update's download is handed to the installer: the next launch is
         *  that build, and without this the same file would be offered again every start. */
        fun markInstalled(context: Context, name: String) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_NAME, name).apply()
        }
    }
}
