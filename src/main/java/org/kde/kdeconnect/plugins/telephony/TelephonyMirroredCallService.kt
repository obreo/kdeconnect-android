/*
 * SPDX-FileCopyrightText: 2026 KDE Connect contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.telephony

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.content.ContextCompat
import org.kde.kdeconnect_tp.R

/**
 * Presents mirrored incoming calls through Android's telecom framework, the same way
 * WhatsApp or Messenger do. The incoming-call screen is drawn by the system dialer, so
 * it appears over the lock screen and lights the display without needing the
 * "full screen notifications" permission, which Android 14+ restricts to calling apps.
 *
 * The connection is display-only (mirror-only): the real call must be answered on the
 * device which received it. Tapping "Answer" here therefore just closes the mirror and
 * points the user at the other device.
 */
class TelephonyMirroredCallService : ConnectionService() {

    override fun onCreateIncomingConnection(phoneAccountHandle: PhoneAccountHandle, request: ConnectionRequest): Connection {
        // Prefer the caller info stashed by MirroredCallTelecom.start(): some OEM
        // implementations do not deliver our custom extras inside the request.
        val pending = MirroredCallTelecom.takePendingCall()
        val name = pending?.callerName
        val number = pending?.callerNumber
        val sourceDevice = pending?.sourceDeviceName ?: ""
        val answerDescription = getString(R.string.telephony_call_control_on_other, sourceDevice)

        return try {
            val connection = MirroredCallConnection(answerDescription)
            connection.setAddress(Uri.fromParts("tel", number ?: UNKNOWN_ADDRESS, null), TelecomManager.PRESENTATION_ALLOWED)
            connection.setCallerDisplayName(name ?: number ?: getString(R.string.telephony_call_no_answer), TelecomManager.PRESENTATION_ALLOWED)
            connection.setRinging()
            MirroredCallTelecom.connectionCreated(connection)
            connection
        } catch (e: Exception) {
            // A throwing callback would take down the whole process mid-ring (telecom dispatches
            // it in our process), so end just the mirror instead.
            Log.e(TAG, "Failed to hand the mirrored call to the system UI", e)
            val connection = MirroredCallConnection(answerDescription)
            connection.setDisconnected(DisconnectCause(DisconnectCause.ERROR))
            connection.destroy()
            connection
        }
    }

    override fun onCreateOutgoingConnection(phoneAccountHandle: PhoneAccountHandle, request: ConnectionRequest): Connection {
        // Call mirroring is incoming-only; we never place calls through this account.
        val connection = MirroredCallConnection("")
        connection.setDisconnected(DisconnectCause(DisconnectCause.ERROR))
        connection.destroy()
        return connection
    }

    internal class MirroredCallConnection(private val answerDescription: String) : Connection() {

        /** The source call ended (answered elsewhere or hung up) - close the system UI. */
        fun endBySource() {
            endWith(DisconnectCause.REMOTE)
        }

        override fun onAnswer() {
            endWith(DisconnectCause.ANSWERED_ELSEWHERE, answerDescription)
        }

        override fun onDisconnect() {
            endWith(DisconnectCause.REJECTED)
        }

        override fun onAbort() {
            endWith(DisconnectCause.REJECTED)
        }

        override fun onHold() {
            // Mirrored calls cannot be put on hold
        }

        override fun onUnhold() {
            // Mirrored calls cannot be put on hold
        }

        override fun onStateChanged(newState: Int) {
            if (newState == STATE_DISCONNECTED) {
                MirroredCallTelecom.connectionClosed(this)
            }
        }

        private fun endWith(cause: Int, description: String? = null) {
            try {
                if (state != STATE_DISCONNECTED) {
                    setDisconnected(if (description != null) DisconnectCause(cause, description) else DisconnectCause(cause))
                    destroy()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to disconnect the mirrored call connection", e)
            }
        }
    }

    companion object {
        private const val UNKNOWN_ADDRESS = "unknown"
        private const val TAG = "TelephonyMirrorCall"
    }
}

/**
 * Bridges between [TelephonyPlugin] (which owns the ringing/notification state) and the
 * telecom connection which draws the system incoming-call UI. Everything here runs on
 * the main thread: the plugin confines its state changes to it, and telecom dispatches
 * callbacks for a default-constructed connection on the main looper as well.
 */
object MirroredCallTelecom {

    private const val ACCOUNT_ID = "kdeconnect_telephony_call_mirror"
    private const val TAG = "TelephonyMirrorCall"

    /** Caller info for the call we asked telecom to present, until the service picks it up. */
    data class PendingCall(val callerName: String?, val callerNumber: String?, val sourceDeviceName: String)

    private var pendingCall: PendingCall? = null
    private var currentConnection: TelephonyMirroredCallService.MirroredCallConnection? = null

    /**
     * Called when the system UI closed itself (the user tapped answer or decline, or the
     * dialer dismissed it) so the plugin can stop the ringtone and the notification.
     */
    var onUiDismissed: (() -> Unit)? = null

    /**
     * Declares our self-managed phone account to telecom. Required before
     * [TelecomManager.addNewIncomingCall] will route a call to our service; it needs no
     * user enablement in the dialer because self-managed accounts are app-owned.
     */
    @Suppress("DEPRECATION")
    fun registerAccount(context: Context) {
        val telecom = ContextCompat.getSystemService(context, TelecomManager::class.java) ?: return
        try {
            // Some OEM telecom implementations (at least Samsung, see b/343674176) only honour a
            // self-managed account whose extras bundle is non-empty, and an empty bundle is
            // nulled out on reboot (b/352526256) - so carry one placeholder value.
            val accountExtras = Bundle().apply { putBoolean("isKdeConnectCallMirrorAccount", true) }
            val account = PhoneAccount.builder(phoneAccountHandle(context), context.getString(R.string.kde_connect))
                .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
                .setExtras(accountExtras)
                .build()
            telecom.registerPhoneAccount(account)
        } catch (e: Exception) {
            // SecurityException if MANAGE_OWN_CALLS were not declared; IllegalArgumentException
            // if the service were missing from the manifest. Neither should happen.
            Log.e(TAG, "Failed to register the call mirror phone account", e)
        }
    }

    fun start(context: Context, call: TelephonyPlugin.MirroredCall, sourceDeviceName: String): Boolean {
        val telecom = ContextCompat.getSystemService(context, TelecomManager::class.java) ?: return false
        pendingCall = PendingCall(call.name, call.number, sourceDeviceName)
        return try {
            // No standard incoming-number extra: it is a hidden constant, and custom extras
            // are not reliably delivered on all OEMs - the stash above carries the caller info.
            telecom.addNewIncomingCall(phoneAccountHandle(context), Bundle())
            true
        } catch (e: Exception) {
            // E.g. IllegalArgumentException if the account registration did not take effect.
            // The caller just gets the plugin's plain notification instead of the system one.
            Log.e(TAG, "Failed to present the mirrored call through telecom", e)
            pendingCall = null
            false
        }
    }

    /** Ends the system incoming-call UI, if one is up. */
    fun stop() {
        pendingCall = null
        currentConnection?.endBySource()
    }

    internal fun takePendingCall(): PendingCall? = pendingCall.also { pendingCall = null }

    internal fun connectionCreated(connection: TelephonyMirroredCallService.MirroredCallConnection) {
        currentConnection = connection
    }

    internal fun connectionClosed(connection: TelephonyMirroredCallService.MirroredCallConnection) {
        if (currentConnection === connection) {
            currentConnection = null
            onUiDismissed?.invoke()
        }
    }

    private fun phoneAccountHandle(context: Context): PhoneAccountHandle =
        PhoneAccountHandle(ComponentName(context, TelephonyMirroredCallService::class.java), ACCOUNT_ID)
}
