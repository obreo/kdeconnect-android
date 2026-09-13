/*
 * SPDX-FileCopyrightText: 2026 KDE Connect contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.telephony

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import com.google.android.material.color.MaterialColors
import org.kde.kdeconnect.helpers.NotificationHelper
import org.kde.kdeconnect_tp.R

/**
 * Owns a mirrored incoming call for the whole process.
 *
 * This deliberately lives outside [TelephonyPlugin]: when the link to a device drops briefly
 * (Wi-Fi flap, the other device restarting KDE Connect, ...) the plugin instance is destroyed
 * and a fresh one created on reconnect. A mirror which is mid-ring must survive that - both
 * the ringing and the system call UI - and later events for it ("talking", the cancel packet)
 * may arrive on a different plugin instance than the one that started it. Every component
 * involved (plugin, call activity, notification receiver, telecom UI callbacks) routes the
 * mirroring state through this single object instead.
 *
 * All mutable state lives here and is confined to the main thread; entry points document where
 * the caller must already be.
 */
object MirroredCallSession {

    private const val TAG = "TelephonyPlugin"

    private const val MIRROR_IDLE = 0
    private const val MIRROR_RINGING = 1
    private const val MIRROR_RINGING_TIMEOUT_MS = 120000L
    private const val CALL_STYLE_RETRY_DELAY_MS = 1500L
    private const val FALLBACK_ACTION_COLOR = 0xFF1B1B1F.toInt() // Material 3 onSurface, light scheme

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Device the mirror was started for; also keys the notification id. */
    private var deviceId: String? = null
    private var deviceName: String = ""
    private var state = MIRROR_IDLE
    private var call: TelephonyPlugin.MirroredCall? = null
    private var pendingMissedCall: TelephonyPlugin.MirroredCall? = null
    private var ringtonePlayer: MediaPlayer? = null
    private var ringingTimeout: Runnable? = null
    private var callStateListener: TelephonyPlugin.CallStateListener? = null

    /**
     * Handles one telephony packet from [sourceDeviceId]. Call on the main thread.
     */
    fun handleCallEvent(context: Context, sourceDeviceId: String, sourceDeviceName: String,
                        event: String, isCancel: Boolean,
                        contactName: String?, phoneNumber: String?, thumbnail: String?) {
        // A new incoming call takes over the mirror (and names it with its own device);
        // stop events only ever act on the device that owns the current mirror, so a late
        // or unrelated packet from another device cannot tear down an active call.
        if (event == "ringing" && !isCancel) {
            deviceId = sourceDeviceId
            deviceName = sourceDeviceName
            start(context, TelephonyPlugin.MirroredCall(contactName, phoneNumber, thumbnail))
            return
        }

        if (sourceDeviceId != deviceId) {
            return
        }

        // Any event other than a new incoming call must stop the mirroring immediately, so
        // that the notification on this device automatically goes away when the call is
        // picked up or ends on the phone with the SIM card.
        if (isCancel) {
            stopOnMain(context, recordMissed = true)
            return
        }

        when (event) {
            "talking" -> stopOnMain(context, recordMissed = false) // Picked up on the other device
            "missedCall" -> postMissedCallNotification(context)
        }
    }

    /** Whether a mirrored call from [sourceDeviceId] is currently ringing. Any thread. */
    fun isRinging(sourceDeviceId: String): Boolean = state == MIRROR_RINGING && deviceId == sourceDeviceId

    /** The call currently being mirrored, if any. Any thread (best-effort snapshot). */
    fun currentCall(): TelephonyPlugin.MirroredCall? = call

    /** Registers the [TelephonyCallActivity] listener. Any thread. */
    fun setCallStateListener(listener: TelephonyPlugin.CallStateListener?) {
        mainHandler.post { callStateListener = listener }
    }

    /**
     * Stops the mirror for [sourceDeviceId] (user action: notification action, swipe, or the
     * in-app stop button). Safe from any thread.
     */
    fun stop(context: Context, sourceDeviceId: String, recordMissed: Boolean) {
        mainHandler.post {
            if (deviceId == sourceDeviceId) {
                stopOnMain(context, recordMissed)
            }
        }
    }

    /**
     * The device was unpaired while it might have a mirror up. Safe on a freshly created plugin
     * instance: it only touches this device's own notification. Call on the main thread.
     */
    fun deviceUnpaired(context: Context, sourceDeviceId: String) {
        if (deviceId == sourceDeviceId) {
            stopOnMain(context, recordMissed = false)
        } else {
            NotificationManagerCompat.from(context).cancel(callNotificationId(sourceDeviceId))
        }
    }

    private fun start(context: Context, incoming: TelephonyPlugin.MirroredCall) {
        // A previous mirrored call (e.g. a second incoming call) must not keep ringing
        if (state == MIRROR_RINGING) {
            stopOnMain(context, recordMissed = false)
        }

        call = incoming
        state = MIRROR_RINGING
        pendingMissedCall = null

        startRingtone(context)
        startVibration(context)
        var telecomCallAdded = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Add the call to telecom (the way WhatsApp does): the system then treats the
            // mirror as a real call for audio, display and the call-style notification.
            MirroredCallTelecom.onUiDismissed = { stopOnMain(context, recordMissed = true) }
            telecomCallAdded = MirroredCallTelecom.start(context, incoming, deviceName)
        }
        postCallNotification(context, incoming, telecomCallAdded)

        // Safety net: the cancel packet should always arrive, but if it was lost on a flaky
        // link stop the mirror on our own (carrier ringing always ends sooner anyway).
        val timeout = Runnable {
            Log.i(TAG, "Mirrored call timed out, stopping the mirror")
            stopOnMain(context, recordMissed = true)
        }
        ringingTimeout = timeout
        mainHandler.postDelayed(timeout, MIRROR_RINGING_TIMEOUT_MS)
        dispatchCallState()
    }

    private fun stopOnMain(context: Context, recordMissed: Boolean) {
        if (recordMissed && state == MIRROR_RINGING) {
            pendingMissedCall = call
        }
        ringingTimeout?.let { mainHandler.removeCallbacks(it) }
        ringingTimeout = null

        // Tear the ringing down unconditionally, even if the session already considers itself
        // idle: a stop request must never leave a looping vibration, a live telecom call or a
        // stale notification behind, and every step here is idempotent.
        stopRingtone()
        stopVibration(context)
        MirroredCallTelecom.stop()
        deviceId?.let { NotificationManagerCompat.from(context).cancel(callNotificationId(it)) }

        if (state != MIRROR_RINGING) {
            return
        }
        state = MIRROR_IDLE
        call = null
        dispatchCallState()
    }

    private fun dispatchCallState() {
        callStateListener?.onMirroredCallStateChanged(state == MIRROR_RINGING, call)
    }

    private fun postCallNotification(context: Context, call: TelephonyPlugin.MirroredCall, viaTelecomCall: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(TAG, "No permission to post notifications, mirrored call will only be audible")
            return
        }

        val sourceDeviceId = deviceId ?: return

        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, TelephonyCallActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(TelephonyCallActivity.EXTRA_DEVICE_ID, sourceDeviceId)
                putExtra(TelephonyCallActivity.EXTRA_CALLER_NAME, call.name)
                putExtra(TelephonyCallActivity.EXTRA_CALLER_NUMBER, call.number)
                putExtra(TelephonyCallActivity.EXTRA_CALLER_THUMBNAIL, call.thumbnail)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        fun stopMirrorIntent(requestCode: Int): PendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, TelephonyCallReceiver::class.java).apply {
                action = TelephonyCallReceiver.ACTION_STOP_CALL_MIRROR
                putExtra(TelephonyCallReceiver.EXTRA_DEVICE_ID, sourceDeviceId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = stopMirrorIntent(1)

        val plainStyle = NotificationCompat.BigTextStyle()
            .bigText(listOfNotNull(call.displayName, context.getString(R.string.telephony_call_via, deviceName)).joinToString("\n"))

        val builder = NotificationCompat.Builder(context, NotificationHelper.Channels.INCOMING_CALL)
            .setSmallIcon(R.drawable.ic_telephony_call_24dp)
            .setContentTitle(context.getString(R.string.telephony_call_incoming))
            .setContentText(call.displayName)
            .setSubText(deviceName)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            // The caller must be visible on the lock screen, like a real incoming call.
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(false)
            // The channel is silent: the ringtone and vibration are driven by this session.
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .setDeleteIntent(stopIntent)
            .addAction(
                NotificationCompat.Action.Builder(
                    R.drawable.ic_call_end_24dp,
                    context.getString(R.string.telephony_call_stop_ringing),
                    stopIntent,
                ).build(),
            )
            // Color the action explicitly: some OEM skins otherwise render its text white on
            // a white pill. onSurface is the near-black body text color in the light theme and
            // flips to a light tone in dark mode, so the button stays readable either way.
            // ("colorOnSurface" is not exported in every Material library release's R class,
            // hence the by-name attribute lookup.)
            .setColor(actionColor(context))

        call.thumbnail?.let { thumbnail ->
            try {
                val bytes = Base64.decode(thumbnail, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { builder.setLargeIcon(it) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decode the contact photo of a mirrored call")
            }
        }

        val notificationId = callNotificationId(sourceDeviceId)
        if (viaTelecomCall && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // This notification is the CallStyle counterpart of the telecom call: the system
            // treats a valid call notification as the call UI itself and renders it as the
            // top card (Android requires one within 5 s of addNewIncomingCall), ongoing so
            // the user dismisses it through its controls rather than by swiping blindly.
            // forOngoingCall is the form with the fewest controls (a single hangup); the
            // incoming form adds inert answer/decline buttons which OEMs route to the
            // telecom connection instead of our PendingIntents. The hangup is wired to the
            // same stop intent as the action below - Android has no call card without any
            // buttons at all (android.app.Notification.CallStyle offers only incoming,
            // ongoing and screening). TODO: real answer/decline needs an own InCallService.
            builder.setStyle(
                NotificationCompat.CallStyle.forOngoingCall(
                    Person.Builder().setName(call.displayName).build(),
                    stopMirrorIntent(2),
                ),
            )
            builder.setOngoing(true)
            // The platform only accepts a CallStyle notification from a foreground service,
            // a user-initiated job, or one carrying a full-screen intent (otherwise notify()
            // throws and telecom later tears the call down for want of a UI). The intent
            // doubles as the lock-screen call UI - our activity sets showWhenLocked.
            builder.setFullScreenIntent(contentIntent, true)
            if (!tryNotify(context, notificationId, builder.build())) {
                // The telecom call may still be settling in (our connection service is bound
                // asynchronously) and Android 12+ validates call notifications against it -
                // retry once shortly after, then keep the plain notification for good.
                mainHandler.postDelayed({
                    if (state == MIRROR_RINGING && call === this.call) {
                        if (!tryNotify(context, notificationId, builder.build())) {
                            builder.setStyle(plainStyle)
                            builder.setOngoing(false)
                            tryNotify(context, notificationId, builder.build())
                        }
                    }
                }, CALL_STYLE_RETRY_DELAY_MS)
            }
        } else {
            // The user can dismiss it (which stops the ringtone via the delete intent), but
            // tapping it opens the call screen without cancelling the notification.
            builder.setStyle(plainStyle)
            builder.setOngoing(false)
            // The full-screen intent brings our call screen to the front while the screen is
            // off or locked (the activity sets showWhenLocked). Android 14+ only launches it
            // automatically with the per-app "full screen notifications" allowance; without
            // that the notification still rings and tapping it opens the same screen.
            builder.setFullScreenIntent(contentIntent, true)
            tryNotify(context, notificationId, builder.build())
        }
    }

    private fun tryNotify(context: Context, notificationId: Int, notification: Notification): Boolean {
        return try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
            true
        } catch (e: Exception) {
            // Never let a declined notification take down the ring: POST_NOTIFICATIONS may
            // have been revoked meanwhile, or the platform may have rejected the call style.
            Log.e(TAG, "Failed to post the mirrored call notification", e)
            false
        }
    }

    private fun postMissedCallNotification(context: Context) {
        val call = pendingMissedCall ?: return
        pendingMissedCall = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val notification = NotificationCompat.Builder(context, NotificationHelper.Channels.DEFAULT)
            .setSmallIcon(R.drawable.ic_telephony_call_24dp)
            .setContentTitle(context.getString(R.string.telephony_call_missed, call.displayName))
            .setSubText(deviceName)
            .setAutoCancel(true)
            .setSound(null)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(System.currentTimeMillis().toInt(), notification)
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to post the missed call notification", e)
        }
    }

    private fun callNotificationId(deviceId: String): Int = 0x51A00 + (deviceId.hashCode() and 0xFF)

    /**
     * The Material 3 on-surface text color of the current theme (dark in the light theme,
     * light in the dark theme), so the notification action stays readable.
     */
    private fun actionColor(context: Context): Int {
        val attr = context.resources.getIdentifier("colorOnSurface", "attr", context.packageName)
        return if (attr != 0) {
            MaterialColors.getColor(context, attr, FALLBACK_ACTION_COLOR)
        } else {
            FALLBACK_ACTION_COLOR
        }
    }

    private fun startRingtone(context: Context) {
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
            Log.e(TAG, "Failed to play the ringtone for a mirrored call", e)
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

    private fun startVibration(context: Context) {
        val vibrator = getVibrator(context) ?: return
        val pattern = longArrayOf(0, 500, 800)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, 0)
        }
    }

    private fun stopVibration(context: Context) {
        getVibrator(context)?.cancel()
    }

    private fun getVibrator(context: Context): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.getSystemService(context, VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ContextCompat.getSystemService(context, Vibrator::class.java)
        }
    }
}
