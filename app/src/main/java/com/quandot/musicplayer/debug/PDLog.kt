/**
 * PDLog — Lightweight structured logging facade for PlayDepth.
 *
 * @remarks
 * Standardizes all Kotlin log sites under the `PD:<Domain>` namespace with strict level
 * semantics and grep-friendly `EVENT | key=value` payloads. `PDLog.d` is `inline` and gated
 * behind `BuildConfig.DEBUG` so R8 strips both the call site and lambda allocations completely
 * in Release builds. `ERROR`, `WARN`, and `INFO` events mirror into [AppLogManager] so user
 * diagnostic exports preserve full hardware handshakes, format negotiations, and error history.
 *
 * @see AppLogManager
 */
package com.quandot.musicplayer.debug

import android.util.Log
import com.quandot.musicplayer.BuildConfig

object PDLog {
    // ═══ Unified PD:<Domain> Tag Namespace ═══
    const val UI = "PD:UI"
    const val PLAYBACK = "PD:Playback"
    const val SCANNER = "PD:Scanner"
    const val ART_CACHE = "PD:ArtCache"
    const val USB_DAC = "PD:UsbDac"
    const val JNI_BRIDGE = "PD:JniBridge"
    const val NATIVE_AUDIO = "PD:NativeAudio"

    @JvmStatic
    fun e(tag: String, event: String, details: String = "", throwable: Throwable? = null) {
        val msg = format(event, details)
        if (throwable != null) {
            Log.e(tag, msg, throwable)
        } else {
            Log.e(tag, msg)
        }
        AppLogManager.recordStructured("E", tag, msg, throwable)
    }

    @JvmStatic
    fun w(tag: String, event: String, details: String = "", throwable: Throwable? = null) {
        val msg = format(event, details)
        if (throwable != null) {
            Log.w(tag, msg, throwable)
        } else {
            Log.w(tag, msg)
        }
        AppLogManager.recordStructured("W", tag, msg, throwable)
    }

    @JvmStatic
    fun i(tag: String, event: String, details: String = "") {
        val msg = format(event, details)
        Log.i(tag, msg)
        AppLogManager.recordStructured("I", tag, msg, null)
    }

    /**
     * Debug-only diagnostic log. Completely dead-code eliminated by R8 in Release builds.
     */
    inline fun d(tag: String, event: String, details: () -> String = { "" }) {
        if (BuildConfig.DEBUG) {
            val d = details()
            val msg = if (d.isEmpty()) event else "$event | $d"
            Log.d(tag, msg)
            AppLogManager.recordStructured("D", tag, msg, null)
        }
    }

    @PublishedApi
    internal fun format(event: String, details: String): String =
        if (details.isEmpty()) event else "$event | $details"
}
