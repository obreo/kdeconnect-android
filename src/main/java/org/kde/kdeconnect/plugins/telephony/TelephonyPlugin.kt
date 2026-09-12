/*
 * SPDX-FileCopyrightText: 2014 Albert Vaca Cintora <albertvaka@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.telephony

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.preference.PreferenceManager
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import org.kde.kdeconnect.DeviceType
import org.kde.kdeconnect.helpers.ContactsHelper
import org.kde.kdeconnect.NetworkPacket
import org.kde.kdeconnect.plugins.Plugin
import org.kde.kdeconnect.plugins.PluginFactory.LoadablePlugin
import org.kde.kdeconnect.ui.PluginSettingsFragment
import org.kde.kdeconnect.ui.PluginSettingsFragment.Companion.newInstance
import org.kde.kdeconnect_tp.BuildConfig
import org.kde.kdeconnect_tp.R
import java.util.Timer
import java.util.TimerTask

@LoadablePlugin
class TelephonyPlugin : Plugin() {
    private var lastState = TelephonyManager.CALL_STATE_IDLE
    private var lastPacket: NetworkPacket? = null
    private var isMuted = false

    // --- Call mirroring (phone-to-phone) ---
    // onPacketReceived runs on the link's read thread, while plugin (un)loading can run on a
    // worker thread. The mirroring state itself lives in MirroredCallSession on the main
    // thread, so it survives this instance being destroyed and replaced on a reconnect.
    private val mainHandler = Handler(Looper.getMainLooper())

    private val receiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            //Log.e("TelephonyPlugin", "Telephony event: $action")
            if (TelephonyManager.ACTION_PHONE_STATE_CHANGED == intent.action) {
                val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
                val intState = when (state) {
                    TelephonyManager.EXTRA_STATE_RINGING -> TelephonyManager.CALL_STATE_RINGING
                    TelephonyManager.EXTRA_STATE_OFFHOOK -> TelephonyManager.CALL_STATE_OFFHOOK
                    else -> TelephonyManager.CALL_STATE_IDLE
                }

                // We will get a second broadcast with the phone number https://developer.android.com/reference/android/telephony/TelephonyManager#ACTION_PHONE_STATE_CHANGED
                // The IDLE broadcast has no number at all, and we don't need one to end a call.
                if (intState != TelephonyManager.CALL_STATE_IDLE && !intent.hasExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)) return
                val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

                if (intState != lastState) {
                    lastState = intState
                    callBroadcastReceived(intState, number)
                }
            }
        }
    }

    // Debug-only way to feed fake call events into the mirroring receiver, so it can be
    // tested on a single device/emulator:
    // adb shell am broadcast -a org.kde.kdeconnect_tp.DEBUG_CALL_MIRROR --es event ringing --es phoneNumber +15551234567 --es contactName Alice
    private val debugReceiver: BroadcastReceiver? = if (BuildConfig.DEBUG) {
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val isCancel = intent.getStringExtra("isCancel") == "true"
                mainHandler.post {
                    MirroredCallSession.handleCallEvent(
                        context,
                        device.deviceId,
                        device.name,
                        intent.getStringExtra("event") ?: "",
                        isCancel,
                        intent.getStringExtra("contactName"),
                        intent.getStringExtra("phoneNumber"),
                        null,
                    )
                }
            }
        }
    } else {
        null
    }

    override val displayName: String
        get() = context.resources.getString(R.string.pref_plugin_telephony)

    override val description: String
        get() = context.resources.getString(R.string.pref_plugin_telephony_desc)

    private fun callBroadcastReceived(state: Int, phoneNumber: String?) {
        if (isNumberBlocked(phoneNumber)) return

        // Phones only exchange call events when call mirroring is enabled for this pair;
        // desktops always receive them. Still unmute on ringing, so a ringer muted through
        // KDE Connect doesn't silence a real incoming call when we don't send anything.
        if (device.deviceType == DeviceType.PHONE && !isCallMirroringEnabled()) {
            if (state == TelephonyManager.CALL_STATE_RINGING) unmuteRinger()
            return
        }

        val np = NetworkPacket(PACKET_TYPE_TELEPHONY)

        val permissionCheck = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)

        if (permissionCheck == PackageManager.PERMISSION_GRANTED && phoneNumber != null) {
            val result = ContactsHelper.phoneNumberLookup(context, phoneNumber)

            val name = result.name
            if (name != null) {
                np["contactName"] = name
            }

            val photoUri = result.photoId
            if (photoUri != null) {
                try {
                    val base64photo = ContactsHelper.photoId64Encoded(context, photoUri)
                    if (!base64photo.isNullOrEmpty()) {
                        np["phoneThumbnail"] = base64photo
                    }
                } catch (e: Exception) {
                    Log.e("TelephonyPlugin", "Failed to get contact photo")
                }
            }
        } else if (phoneNumber != null) {
            np["contactName"] = phoneNumber
        }

        if (phoneNumber != null) {
            np["phoneNumber"] = phoneNumber
        }

        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                unmuteRinger()
                np["event"] = "ringing"
                device.sendPacket(np)
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                np["event"] = "talking"
                device.sendPacket(np)
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                val lastPacket = lastPacket ?: return
                // Resend a cancel of the last event (can either be "ringing" or "talking")
                lastPacket["isCancel"] = "true"
                device.sendPacket(lastPacket)

                if (isMuted) {
                    val timer = Timer()
                    timer.schedule(object : TimerTask() {
                        override fun run() {
                            unmuteRinger()
                        }
                    }, 500)
                }

                // Emit a missed call notification if needed
                if ("ringing" == lastPacket.getString("event")) {
                    np["event"] = "missedCall"
                    val phoneNumber = lastPacket.getStringOrNull("phoneNumber")
                    if (phoneNumber != null) {
                        np["phoneNumber"] = phoneNumber
                    }
                    val contactName = lastPacket.getStringOrNull("contactName")
                    if (contactName != null) {
                        np["contactName"] = contactName
                    }
                    device.sendPacket(np)
                }
            }
        }

        lastPacket = np
    }

    private fun unmuteRinger() {
        if (isMuted) {
            val am = ContextCompat.getSystemService(context, AudioManager::class.java) ?: return
            am.setStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_UNMUTE, 0)
            isMuted = false
        }
    }

    private fun muteRinger() {
        if (isMuted) return

        val am = ContextCompat.getSystemService(context, AudioManager::class.java) ?: return
        if (!am.isStreamMute(AudioManager.STREAM_RING)) {
            am.setStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_MUTE, 0)
            isMuted = true
        }
    }

    // --- Call mirroring receiver side ---
    // The state machine lives in MirroredCallSession (process-wide), not in this instance:
    // a mirrored call outlives its plugin instance if the link drops and comes back.

    /**
     * A call incoming on the paired device, to be mirrored on this one.
     */
    data class MirroredCall(val name: String?, val number: String?, val thumbnail: String?) {
        val displayName: String
            get() = name ?: number ?: ""
    }

    fun interface CallStateListener {
        fun onMirroredCallStateChanged(ringing: Boolean, call: MirroredCall?)
    }

    /**
     * Set by [TelephonyCallActivity] to be told when a mirrored call starts or stops.
     * Only one listener at a time; it is called on the main thread.
     */
    fun setCallStateListener(listener: CallStateListener?) {
        MirroredCallSession.setCallStateListener(listener)
    }

    fun isMirroringIncomingCall(): Boolean = MirroredCallSession.isRinging(device.deviceId)

    fun currentMirroredCall(): MirroredCall? = MirroredCallSession.currentCall()

    fun stopCallMirror(recordMissed: Boolean = false) {
        MirroredCallSession.stop(context, device.deviceId, recordMissed)
    }

    override val permissionExplanation: Int = R.string.telephony_permission_explanation

    override val optionalPermissionExplanation: Int = R.string.telephony_optional_permission_explanation

    override fun onCreate(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Declare the self-managed phone account whose ConnectionService draws the system
            // incoming-call UI for mirrored calls. Registering it repeatedly is harmless.
            MirroredCallTelecom.registerAccount(context)
        }

        val filter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        filter.priority = 500
        context.registerReceiver(receiver, filter)

        val debugReceiver = debugReceiver
        if (debugReceiver != null) {
            val debugFilter = IntentFilter(ACTION_DEBUG_CALL_MIRROR)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Exported so "adb shell am broadcast" can reach it; only ever registered in
                // debug builds (debugReceiver is null in release), so release builds stay shut.
                context.registerReceiver(debugReceiver, debugFilter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(debugReceiver, debugFilter)
            }
        }
        return true
    }

    override fun onDestroy() {
        context.unregisterReceiver(receiver)
        debugReceiver?.let { context.unregisterReceiver(it) }
        // A mirror which is mid-ring is deliberately left alone here: plugin (un)loading is
        // driven by link connectivity, and a call must keep mirroring across a reconnect.
        // MirroredCallSession owns it and stops it on the cancel event, a user action or
        // its own timeout.
    }

    override fun onDeviceUnpaired(context: Context, deviceId: String) {
        // This may be called on a freshly created instance or off the main thread, so just
        // hand it to the session (which is safe for devices with no active mirror).
        mainHandler.post { MirroredCallSession.deviceUnpaired(context, deviceId) }
    }

    override fun onPacketReceived(np: NetworkPacket): Boolean {
        when (np.type) {
            PACKET_TYPE_TELEPHONY_REQUEST_MUTE -> muteRinger()
            PACKET_TYPE_TELEPHONY -> {
                if (isCallMirroringEnabled()) {
                    // The sender writes isCancel as a string, but some senders (e.g. the
                    // desktop) may use a boolean; optString stringifies both to "true".
                    val isCancel = np.getString("isCancel") == "true"
                    val event = np.getString("event")
                    val contactName = np.getStringOrNull("contactName")
                    val phoneNumber = np.getStringOrNull("phoneNumber")
                    val thumbnail = np.getStringOrNull("phoneThumbnail")
                    mainHandler.post {
                        MirroredCallSession.handleCallEvent(
                            context,
                            device.deviceId,
                            device.name,
                            event,
                            isCancel,
                            contactName,
                            phoneNumber,
                            thumbnail,
                        )
                    }
                }
            }
        }
        return true
    }

    private fun isNumberBlocked(number: String?): Boolean {
        return getBlockedNumbers().any { s -> PhoneNumberUtils.compare(number, s) }
    }

    private fun getBlockedNumbers(): List<String> {
        // Settings are device-specific now, but older versions stored them globally
        var blockedNumbers = preferences?.getString(KEY_PREF_BLOCKED_NUMBERS, "").orEmpty()
        if (blockedNumbers.isEmpty()) {
            blockedNumbers = PreferenceManager.getDefaultSharedPreferences(context).getString(KEY_PREF_BLOCKED_NUMBERS, "").orEmpty()
        }
        return blockedNumbers.split("\n").dropLastWhile { it.isEmpty() }
    }

    private fun isCallMirroringEnabled(): Boolean {
        return preferences?.getBoolean(context.getString(R.string.telephony_preference_key_mirror_calls), false) ?: false
    }

    override val supportedPacketTypes: Array<String> = arrayOf(PACKET_TYPE_TELEPHONY_REQUEST_MUTE, PACKET_TYPE_TELEPHONY)

    override val outgoingPacketTypes: Array<String> = arrayOf(PACKET_TYPE_TELEPHONY)

    override val requiredPermissions: Array<String> = arrayOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG)

    override val optionalPermissions: Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.POST_NOTIFICATIONS)
    } else {
        arrayOf(Manifest.permission.READ_CONTACTS)
    }

    override fun hasSettings(): Boolean = true

    override fun supportsDeviceSpecificSettings(): Boolean = true

    override fun getSettingsFragment(activity: Activity): PluginSettingsFragment = newInstance(pluginKey, R.xml.telephonyplugin_preferences)

    companion object {
        /**
         * Packet used for simple call events
         *
         * It contains the key "event" which maps to a string indicating the type of event:
         * - "ringing" - A phone call is incoming
         * - "missedCall" - An incoming call was not answered
         * - "sms" - An incoming SMS message
         * - Note: As of this writing (15 May 2018) the SMS interface is being improved and this type of event
         * is no longer the preferred way of handling SMS. Use the packets defined by the SMS plugin instead.
         *
         * Depending on the event, other fields may be defined.
         *
         * When the "isCancel" key is set (to the string "true"), this packet repeats a previous
         * event and announces that the call it refers to is over.
         *
         * Android phones also receive this packet type, to mirror incoming calls of the paired
         * device (see "Mirror incoming calls" in the plugin settings): a "ringing" event starts
         * the mirror, and either a "talking" or a cancelled event ends it.
         */
        const val PACKET_TYPE_TELEPHONY: String = "kdeconnect.telephony"

        /**
         * Packet sent to indicate the user has requested the device mute its ringer
         *
         * The body should be empty
         */
        private const val PACKET_TYPE_TELEPHONY_REQUEST_MUTE = "kdeconnect.telephony.request_mute"

        private const val KEY_PREF_BLOCKED_NUMBERS = "telephony_blocked_numbers"

        private const val ACTION_DEBUG_CALL_MIRROR = "org.kde.kdeconnect_tp.DEBUG_CALL_MIRROR"
    }
}
