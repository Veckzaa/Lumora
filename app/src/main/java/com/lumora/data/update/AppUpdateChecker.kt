package com.lumora.data.update

import android.content.Context
import android.util.Log
import com.lumora.BaseApplication
import okhttp3.Request
import java.io.File

/**
 * Checks for app updates against one Dropbox file.
 *
 * Deployment is "replace the APK behind [UPDATE_URL]" (same name, Replace; the share link
 * follows the file id): no release tags, no version bumps. A HEAD on the direct link returns
 * an `etag` that changes on every upload, the file name, and the size - but only with
 * `Accept-Encoding: identity`; with OkHttp's default gzip Dropbox drops the etag and length.
 *
 * The etag of the build this install came from is remembered; a different one on Dropbox
 * means a newer upload. Size alone is not enough (every incremental debug build came out the
 * same size: AGP's packager rewrites entries in place and leaves slack), but a size that differs
 * from the installed APK - Android keeps it byte for byte at `sourceDir` - is proof of a
 * different build, which covers a fresh sideload with no remembered etag: it is offered the
 * update if Dropbox already holds something else, and otherwise just records what's there.
 */
class AppUpdateChecker(private val context: Context) {

    private val TAG = "AppUpdate"

    data class UpdateInfo(
        /** Dropbox's etag for the upload; shown nowhere, stored by [markInstalled]. */
        val build: String,
        /** The file's current name on Dropbox, e.g. "lumoraV2.apk", for the prompt. */
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
                val build = response.header("ETag")?.trim('"') ?: return@use null
                val size = response.header("Content-Length")?.toLongOrNull() ?: 0L
                val name = response.header("Content-Disposition")
                    ?.let { Regex("filename=\"?([^\";]+)").find(it)?.groupValues?.get(1) } ?: "Lumora.apk"
                val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val installed = prefs.getString(KEY_BUILD, null)
                val installedSize = File(context.applicationInfo.sourceDir).length()
                val newer = when {
                    size > 0 && size != installedSize -> true
                    installed == null -> false
                    else -> installed != build
                }
                Log.i(TAG, "Dropbox: $name $size bytes build $build; installed $installedSize bytes build $installed -> ${if (newer) "update" else "current"}")
                if (installed == null && !newer) prefs.edit().putString(KEY_BUILD, build).apply()
                val currentVersion = try {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
                } catch (e: Exception) { "1.0" }
                UpdateInfo(
                    build = build,
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
         *  (same name, Replace) so the link keeps pointing at it; a new upload is a new link.
         *  Renaming it afterwards is fine, the name only shows in the prompt. */
        const val UPDATE_URL =
            "https://www.dropbox.com/scl/fi/f1sewaklpbpjc1dra235g/lumoraV1.apk?rlkey=1iua0e0ueohisostvqdm3rvvr&st=25bsoozl&dl=1"
        private const val PREFS = "app_update"
        private const val KEY_BUILD = "installed_build"

        /** Called once the update's download is handed to the installer: the next launch is
         *  that build, and without this the same upload would be offered again every start. */
        fun markInstalled(context: Context, build: String) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_BUILD, build).apply()
        }
    }
}
