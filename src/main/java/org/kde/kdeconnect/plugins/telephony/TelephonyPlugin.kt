/*
 * SPDX-FileCopyrightText: 2014 Albert Vaca Cintora <albertvaka@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.telephony

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.preference.PreferenceManager
import android.provider.Settings
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.kde.kdeconnect.DeviceType
import org.kde.kdeconnect.helpers.ContactsHelper
import org.kde.kdeconnect.helpers.NotificationHelper
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

    // --- Call mirroring (phone-to-phone) state ---
    // onPacketReceived runs on the link's read thread, while plugin (un)loading can run on
    // a worker thread, so every state change below is confined to the main thread.
    private val mainHandler = Handler(Looper.getMainLooper())
    private var mirroredState = MIRROR_IDLE
    private var mirroredCall: MirroredCall? = null
    private var pendingMissedCall: MirroredCall? = null
    private var ringtonePlayer: MediaPlayer? = null
    private var callStateListener: CallStateListener? = null

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
                    handleMirroredCallEvent(
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
        mainHandler.post { callStateListener = listener }
    }

    fun isMirroringIncomingCall(): Boolean = mirroredState == MIRROR_RINGING

    fun currentMirroredCall(): MirroredCall? = mirroredCall

    fun stopCallMirror(recordMissed: Boolean = false) {
        mainHandler.post { stopCallMirrorOnMain(recordMissed) }
    }

    private fun handleMirroredCallEvent(event: String, isCancel: Boolean, contactName: String?, phoneNumber: String?, thumbnail: String?) {
        // Any event other than a new incoming call must stop the mirroring immediately, so
        // that the notification on this device automatically goes away when the call is
        // picked up or ends on the phone with the SIM card.
        if (isCancel) {
            stopCallMirrorOnMain(recordMissed = true)
            return
        }

        when (event) {
            "ringing" -> startCallMirror(MirroredCall(contactName, phoneNumber, thumbnail))
            "talking" -> stopCallMirrorOnMain(recordMissed = false) // Picked up on the other device
            "missedCall" -> postMissedCallNotification()
        }
    }

    private fun startCallMirror(call: MirroredCall) {
        // A previous mirrored call (e.g. a second incoming call) must not keep ringing
        if (mirroredState == MIRROR_RINGING) {
            stopCallMirrorOnMain(recordMissed = false)
        }

        mirroredCall = call
        mirroredState = MIRROR_RINGING
        pendingMissedCall = null

        startRingtone()
        startVibration()
        postCallNotification(call)
        mainHandler.postDelayed(ringingTimeout, MIRROR_RINGING_TIMEOUT_MS)
        dispatchCallState()
    }

    private fun stopCallMirrorOnMain(recordMissed: Boolean) {
        if (recordMissed && mirroredState == MIRROR_RINGING) {
            pendingMissedCall = mirroredCall
        }
        mainHandler.removeCallbacks(ringingTimeout)
        if (mirroredState != MIRROR_RINGING) {
            return
        }
        mirroredState = MIRROR_IDLE
        mirroredCall = null

        stopRingtone()
        stopVibration()
        NotificationManagerCompat.from(context).cancel(callNotificationId(device.deviceId))
        dispatchCallState()
    }

    private fun dispatchCallState() {
        callStateListener?.onMirroredCallStateChanged(mirroredState == MIRROR_RINGING, mirroredCall)
    }

    private val ringingTimeout = Runnable {
        // Safety net: the cancel packet should always arrive, but if it was lost on a flaky
        // link stop the mirror on our own (carrier ringing always ends sooner anyway).
        Log.i("TelephonyPlugin", "Mirrored call timed out, stopping the mirror")
        stopCallMirrorOnMain(recordMissed = true)
    }

    private fun postCallNotification(call: MirroredCall) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !isPermissionGranted(Manifest.permission.POST_NOTIFICATIONS)) {
            Log.i("TelephonyPlugin", "No permission to post notifications, mirrored call will only be audible")
            return
        }

        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, TelephonyCallActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(TelephonyCallActivity.EXTRA_DEVICE_ID, device.deviceId)
                putExtra(TelephonyCallActivity.EXTRA_CALLER_NAME, call.name)
                putExtra(TelephonyCallActivity.EXTRA_CALLER_NUMBER, call.number)
                putExtra(TelephonyCallActivity.EXTRA_CALLER_THUMBNAIL, call.thumbnail)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val stopIntent = PendingIntent.getBroadcast(
            context,
            1,
            Intent(context, TelephonyCallReceiver::class.java).apply {
                action = TelephonyCallReceiver.ACTION_STOP_CALL_MIRROR
                putExtra(TelephonyCallReceiver.EXTRA_DEVICE_ID, device.deviceId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(context, NotificationHelper.Channels.INCOMING_CALL)
            .setSmallIcon(R.drawable.ic_telephony_call_24dp)
            .setContentTitle(context.getString(R.string.telephony_call_incoming))
            .setContentText(call.displayName)
            .setSubText(device.name)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(listOfNotNull(call.displayName, context.getString(R.string.telephony_call_via, device.name)).joinToString("\n")),
            )
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            // The user can dismiss it (which stops the ringtone via the delete intent), but
            // tapping it opens the call screen without cancelling the notification.
            .setOngoing(false)
            .setAutoCancel(false)
            // The channel is silent: the ringtone and vibration are driven by this plugin.
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .setDeleteIntent(stopIntent)
            .addAction(0, context.getString(R.string.telephony_call_stop_ringing), stopIntent)

        call.thumbnail?.let { thumbnail ->
            try {
                val bytes = Base64.decode(thumbnail, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { builder.setLargeIcon(it) }
            } catch (e: Exception) {
                Log.e("TelephonyPlugin", "Failed to decode the contact photo of a mirrored call")
            }
        }

        if (canUseFullScreenIntent()) {
            builder.setFullScreenIntent(contentIntent, true)
        }

        try {
            NotificationManagerCompat.from(context).notify(callNotificationId(device.deviceId), builder.build())
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS was revoked meanwhile: keep the audible ringing anyway
            Log.e("TelephonyPlugin", "Failed to post the mirrored call notification", e)
        }
    }

    private fun canUseFullScreenIntent(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return true
        }
        return ContextCompat.getSystemService(context, NotificationManager::class.java)?.canUseFullScreenIntent() ?: false
    }

    private fun postMissedCallNotification() {
        val call = pendingMissedCall ?: return
        pendingMissedCall = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !isPermissionGranted(Manifest.permission.POST_NOTIFICATIONS)) {
            return
        }

        val notification = NotificationCompat.Builder(context, NotificationHelper.Channels.DEFAULT)
            .setSmallIcon(R.drawable.ic_telephony_call_24dp)
            .setContentTitle(context.getString(R.string.telephony_call_missed, call.displayName))
            .setSubText(device.name)
            .setAutoCancel(true)
            .setSound(null)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(System.currentTimeMillis().toInt(), notification)
        } catch (e: SecurityException) {
            Log.e("TelephonyPlugin", "Failed to post the missed call notification", e)
        }
    }

    private fun callNotificationId(deviceId: String): Int = 0x51A00 + (deviceId.hashCode() and 0xFF)

    private fun startRingtone() {
        stopRingtone()
        try {
            val player = MediaPlayer()
            player.setDataSource(context, Settings.System.DEFAULT_RINGTONE_URI)
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    // Like a real incoming call: audible even when the phone is silenced
                    .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
                    .build(),
            )
            player.setWakeMode(context, PowerManager.SCREEN_DIM_WAKE_LOCK)
            player.setLooping(true)
            player.prepare()
            player.start()
            ringtonePlayer = player
        } catch (e: Exception) {
            Log.e("TelephonyPlugin", "Failed to play the ringtone for a mirrored call", e)
        }
    }

    private fun stopRingtone() {
        ringtonePlayer?.let { player ->
            try {
                if (player.isPlaying) player.stop()
            } catch (e: Exception) {
                // Already stopped or released
            }
            player.release()
        }
        ringtonePlayer = null
    }

    private fun startVibration() {
        val vibrator = getVibrator() ?: return
        val pattern = longArrayOf(0, 500, 800)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, 0)
        }
    }

    private fun stopVibration() {
        getVibrator()?.cancel()
    }

    private fun getVibrator(): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.getSystemService(context, VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ContextCompat.getSystemService(context, Vibrator::class.java)
        }
    }

    override val permissionExplanation: Int = R.string.telephony_permission_explanation

    override val optionalPermissionExplanation: Int = R.string.telephony_optional_permission_explanation

    override fun onCreate(): Boolean {
        val filter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        filter.priority = 500
        context.registerReceiver(receiver, filter)

        val debugReceiver = debugReceiver
        if (debugReceiver != null) {
            val debugFilter = IntentFilter(ACTION_DEBUG_CALL_MIRROR)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(debugReceiver, debugFilter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(debugReceiver, debugFilter)
            }
        }
        return true
    }

    override fun onDestroy() {
        context.unregisterReceiver(receiver)
        debugReceiver?.let { context.unregisterReceiver(it) }
        mainHandler.post {
            mainHandler.removeCallbacks(ringingTimeout)
            stopRingtone()
            stopVibration()
            callStateListener = null
        }
    }

    override fun onDeviceUnpaired(context: Context, deviceId: String) {
        // This may be called on a freshly created instance which never ran onCreate(), so only
        // do things that are safe on it: cancel the (possibly lingering) notification by id.
        NotificationManagerCompat.from(context).cancel(callNotificationId(deviceId))
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
                    mainHandler.post { handleMirroredCallEvent(event, isCancel, contactName, phoneNumber, thumbnail) }
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

        private const val MIRROR_IDLE = 0
        private const val MIRROR_RINGING = 1
        private const val MIRROR_RINGING_TIMEOUT_MS = 120000L
    }
}
