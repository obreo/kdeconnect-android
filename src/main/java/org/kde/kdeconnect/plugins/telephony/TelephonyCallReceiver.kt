/*
 * SPDX-FileCopyrightText: 2026 KDE Connect contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.telephony

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.kde.kdeconnect.KdeConnect

/**
 * Stops a mirrored incoming call, on the user's request (notification action or swipe-away).
 */
class TelephonyCallReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_STOP_CALL_MIRROR: String = "org.kde.kdeconnect.plugins.telephony.stopCallMirror"
        const val EXTRA_DEVICE_ID: String = "deviceId"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_STOP_CALL_MIRROR -> stopCallMirror(intent)
            else -> Log.d("TelephonyCallReceiver", "Unhandled Action received: ${intent.action}")
        }
    }

    private fun stopCallMirror(intent: Intent) {
        if (!intent.hasExtra(EXTRA_DEVICE_ID)) {
            Log.e("TelephonyCallReceiver", "stopCallMirror() - deviceId extra is not present, ignoring")
            return
        }
        val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID)
        val plugin = KdeConnect.getInstance().getDevicePlugin(deviceId, TelephonyPlugin::class.java) ?: return
        // The user stopped the local mirror of a still-ringing call: if that call is never
        // answered, the source device will report it as missed and we should show it.
        plugin.stopCallMirror(recordMissed = true)
    }
}
