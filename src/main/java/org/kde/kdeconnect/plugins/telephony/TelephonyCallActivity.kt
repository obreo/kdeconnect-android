/*
 * SPDX-FileCopyrightText: 2026 KDE Connect contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.telephony

import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.base.BaseActivity
import org.kde.kdeconnect_tp.R
import org.kde.kdeconnect_tp.databinding.ActivityTelephonyCallBinding

/**
 * Full-screen UI shown when a call is mirrored from a paired device.
 *
 * Display-only: answering or rejecting is not possible here, it must be done on the device
 * that actually received the call. The activity follows the plugin's state and finishes
 * itself as soon as the mirrored call stops ringing (picked up, ended or dismissed).
 */
class TelephonyCallActivity : BaseActivity<ActivityTelephonyCallBinding>() {

    override val binding: ActivityTelephonyCallBinding by lazy {
        ActivityTelephonyCallBinding.inflate(layoutInflater)
    }

    private var deviceId: String? = null
    private var plugin: TelephonyPlugin? = null

    private val callStateListener = TelephonyPlugin.CallStateListener { ringing, call ->
        if (!ringing) {
            finish()
        } else if (call != null) {
            showCall(call)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
        )

        if (!intent.hasExtra(EXTRA_DEVICE_ID)) {
            Log.e("TelephonyCallActivity", "You must include the deviceId for which this activity is started as an intent EXTRA")
            finish()
            return
        }
        deviceId = intent.getStringExtra(EXTRA_DEVICE_ID)

        binding.ivCallerThumbnail.outlineProvider = ViewOutlineProvider.BACKGROUND
        binding.ivCallerThumbnail.clipToOutline = true

        binding.bStopRinging.setOnClickListener {
            // Local-only: stops the ringtone and hides the notification, the real call
            // keeps ringing on the device which received it.
            plugin?.stopCallMirror(recordMissed = true)
        }

        showCall(
            TelephonyPlugin.MirroredCall(
                intent.getStringExtra(EXTRA_CALLER_NAME),
                intent.getStringExtra(EXTRA_CALLER_NUMBER),
                intent.getStringExtra(EXTRA_CALLER_THUMBNAIL),
            ),
        )
        KdeConnect.getInstance().getDevice(deviceId)?.let { device ->
            binding.tvViaDevice.text = getString(R.string.telephony_call_via, device.name)
        }
    }

    override fun onStart() {
        super.onStart()
        val deviceId = deviceId ?: return
        val plugin = KdeConnect.getInstance().getDevicePlugin(deviceId, TelephonyPlugin::class.java)
        if (plugin == null || !plugin.isMirroringIncomingCall()) {
            finish()
            return
        }
        this.plugin = plugin
        plugin.currentMirroredCall()?.let { showCall(it) }
        plugin.setCallStateListener(callStateListener)
    }

    override fun onStop() {
        plugin?.setCallStateListener(null)
        plugin = null
        super.onStop()
    }

    private fun showCall(call: TelephonyPlugin.MirroredCall) {
        val unknown = getString(R.string.telephony_call_no_answer)
        binding.tvCallerName.text = call.displayName.ifEmpty { unknown }
        // Without contact access the sender puts the number in both fields
        if (call.number == null || call.number == call.name) {
            binding.tvCallerNumber.visibility = View.GONE
        } else {
            binding.tvCallerNumber.visibility = View.VISIBLE
            binding.tvCallerNumber.text = call.number
        }
        showThumbnail(call.thumbnail)
    }

    private fun showThumbnail(thumbnail: String?) {
        val bitmap = thumbnail?.let {
            try {
                val bytes = Base64.decode(it, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (e: Exception) {
                Log.e("TelephonyCallActivity", "Failed to decode the contact photo")
                null
            }
        }
        if (bitmap != null) {
            binding.ivCallerThumbnail.setImageBitmap(bitmap)
            binding.ivCallerThumbnail.setPadding(0, 0, 0, 0)
        } else {
            binding.ivCallerThumbnail.setImageResource(R.drawable.ic_device_phone_32dp)
            val padding = (24 * resources.displayMetrics.density).toInt() // Matches the XML padding
            binding.ivCallerThumbnail.setPadding(padding, padding, padding, padding)
        }
    }

    companion object {
        const val EXTRA_DEVICE_ID: String = "deviceId"
        const val EXTRA_CALLER_NAME: String = "callerName"
        const val EXTRA_CALLER_NUMBER: String = "callerNumber"
        const val EXTRA_CALLER_THUMBNAIL: String = "callerThumbnail"
    }
}
