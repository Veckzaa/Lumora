package com.lumora.nativelib

import android.util.Log
import java.io.InputStream

/**
 * The Rust library under `native/` (one `.so`, one coarse call per job - see the native policy
 * in CLAUDE.md). Every entry point has a Kotlin twin that callers use when [available] is
 * false: old sticks have failed to load native libraries before (see `useLegacyPackaging` in
 * app/build.gradle.kts), and a build made without cargo ships no library at all.
 */
object LumoraNative {
    private const val TAG = "LumoraNative"

    val available: Boolean by lazy {
        try {
            System.loadLibrary("lumora_native")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "Native library unavailable, using the Kotlin paths: ${e.message}")
            false
        }
    }

    /**
     * Parses an XMLTV feed into the flat record `XmltvParser.decodeNative` reads (the layout is
     * documented in native/src/xmltv.rs). Reads [input] to its end. Throws `IOException` for a
     * malformed or cut-off feed, or whatever the stream itself threw.
     */
    @JvmStatic
    external fun parseXmltv(input: InputStream): ByteArray?
}
