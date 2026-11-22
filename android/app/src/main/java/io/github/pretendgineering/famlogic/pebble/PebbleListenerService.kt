package io.github.pretendgineering.famlogic.pebble

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import io.rebble.pebblekit2.common.SendDataCallback
import io.rebble.pebblekit2.common.UniversalRequestResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Receives messages from our watchapp and watchface through the Pebble phone
 * app (PebbleKit 2). Only the installed Pebble app may call it.
 */
class PebbleListenerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val binder = object : UniversalRequestResponse.Stub() {
        override fun request(data: Bundle, callback: SendDataCallback) {
            val caller = packageManager.getNameForUid(getCallingUid())
            scope.launch {
                val reply = if (caller != null && caller in PebbleKit.pebbleApps(this@PebbleListenerService)) {
                    runCatching { handle(data) }.onFailure { Log.w(TAG, "Failed to handle a watch message", it) }.getOrNull()
                } else {
                    Log.w(TAG, "Ignoring PebbleKit call from $caller")
                    null
                }
                runCatching { callback.onResult(reply ?: Bundle()) }
            }
        }
    }

    private suspend fun handle(data: Bundle): Bundle {
        val uuid = data.getString(PebbleKit.KEY_WATCHAPP_UUID)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return Bundle()
        return when (data.getString(PebbleKit.KEY_ACTION)) {
            PebbleKit.ACTION_RECEIVE_DATA_FROM_WATCH -> {
                val dict = PebbleKit.fromBundle(data.getBundle(PebbleKit.KEY_DATA_DICTIONARY) ?: Bundle())
                val ok = PebbleBridge.onWatchMessage(applicationContext, uuid, dict)
                Bundle().apply { putBundle(PebbleKit.KEY_TRANSMISSION_RESULTS, Bundle().apply { putString("TYPE", if (ok) "ACK" else "NACK") }) }
            }
            PebbleKit.ACTION_APP_OPENED -> { PebbleBridge.onAppOpened(applicationContext, uuid); Bundle() }
            PebbleKit.ACTION_APP_CLOSED -> { PebbleBridge.onAppClosed(uuid); Bundle() }
            else -> Bundle() // data logging etc.: not used
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "PebbleListener"
    }
}
