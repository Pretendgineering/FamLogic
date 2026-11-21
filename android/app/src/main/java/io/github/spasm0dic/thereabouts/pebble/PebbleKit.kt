package io.github.spasm0dic.thereabouts.pebble

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import io.rebble.pebblekit2.common.SendDataCallback
import io.rebble.pebblekit2.common.UniversalRequestResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * A small client for the Pebble phone app's PebbleKit 2 interface: send
 * AppMessages to a watchapp, start it, and add timeline pins.
 *
 * This follows PebbleKit Android 2 (https://github.com/pebble-dev/PebbleKitAndroid2,
 * Apache License 2.0), keeping only what Thereabouts needs, so the app doesn't
 * need that library's much newer Android toolchain. The bundle keys and formats
 * are the library's wire contract with the Pebble app.
 */
object PebbleKit {
    private const val TAG = "PebbleKit"
    const val SEND_DATA = "io.rebble.pebblekit2.SEND_DATA_TO_WATCH"
    const val RECEIVE_DATA = "io.rebble.pebblekit2.RECEIVE_DATA_FROM_WATCH"

    // Bundle keys and actions (PebbleKitBundleKeys)
    const val KEY_ACTION = "ACTION"
    const val KEY_WATCHAPP_UUID = "WATCHAPP_UUID"
    const val KEY_WATCHES_ID = "WATCHES_ID"
    const val KEY_WATCH_ID = "WATCH_ID"
    const val KEY_DATA_DICTIONARY = "DATA_DICTIONARY"
    const val KEY_TRANSMISSION_RESULTS = "TRANSMISSION_RESULTS"
    const val KEY_TIMELINE_PIN = "TIMELINE_PIN"
    const val KEY_TIMELINE_RESULT = "TIMELINE_RESULT"
    const val ACTION_SEND_DATA_TO_WATCH = "SEND_DATA_TO_WATCH"
    const val ACTION_RECEIVE_DATA_FROM_WATCH = "ACTION_RECEIVE_DATA_FROM_WATCH"
    const val ACTION_APP_OPENED = "APP_OPENED"
    const val ACTION_APP_CLOSED = "APP_CLOSED"
    const val ACTION_START_APP = "START_APP"
    const val ACTION_SHOW_TIMELINE_PIN = "SHOW_TIMELINE_PIN"

    /** The installed Pebble phone apps that offer PebbleKit 2. */
    fun pebbleApps(context: Context): List<String> =
        context.packageManager.queryIntentServices(Intent(SEND_DATA), 0).map { it.serviceInfo.packageName }.distinct()

    // ---- AppMessage dictionaries: Int key -> String, Int or ByteArray ----

    fun toBundle(dict: Map<Int, Any>): Bundle = Bundle().apply {
        for ((k, v) in dict) {
            when (v) {
                is String -> { putString("${k}_TYPE", "String"); putString("${k}_VALUE", v) }
                is Int -> { putString("${k}_TYPE", "Int32"); putLong("${k}_VALUE", v.toLong()) }
                is Boolean -> { putString("${k}_TYPE", "UInt8"); putLong("${k}_VALUE", if (v) 1 else 0) }
                is ByteArray -> { putString("${k}_TYPE", "ByteArray"); putByteArray("${k}_VALUE", v) }
                else -> error("unsupported AppMessage value ${v::class}")
            }
        }
    }

    /** Numbers arrive as Long, text as String, bytes as ByteArray. */
    fun fromBundle(b: Bundle): Map<Int, Any> = b.keySet().filter { it.endsWith("_TYPE") }.mapNotNull { typeKey ->
        val key = typeKey.substringBefore("_TYPE").toLongOrNull()?.toInt() ?: return@mapNotNull null
        val valueKey = "${key}_VALUE"
        when (b.getString(typeKey)) {
            "String" -> b.getString(valueKey)?.let { key to it }
            "ByteArray" -> b.getByteArray(valueKey)?.let { key to it }
            "Int8", "Int16", "Int32", "UInt8", "UInt16", "UInt32" -> key to b.getLong(valueKey)
            else -> null
        }
    }.toMap()

    // ---- talking to the Pebble app ----

    /** Sends one request bundle to the Pebble app; null if it isn't there or didn't answer. */
    suspend fun request(context: Context, bundle: Bundle): Bundle? {
        val pkg = pebbleApps(context).firstOrNull() ?: return null
        val connected = CompletableDeferred<UniversalRequestResponse?>()
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
                connected.complete(UniversalRequestResponse.Stub.asInterface(binder))
            }
            override fun onServiceDisconnected(name: ComponentName?) {}
            override fun onNullBinding(name: ComponentName?) { connected.complete(null) }
            override fun onBindingDied(name: ComponentName?) { connected.complete(null) }
        }
        val bound = try {
            context.bindService(Intent(SEND_DATA).setPackage(pkg), conn, Context.BIND_AUTO_CREATE)
        } catch (e: SecurityException) {
            Log.w(TAG, "Can't bind to $pkg", e)
            false
        }
        if (!bound) return null
        try {
            val service = withTimeoutOrNull(10_000) { connected.await() } ?: return null
            val result = CompletableDeferred<Bundle?>()
            val callback = object : SendDataCallback.Stub() {
                override fun onResult(bundle: Bundle) {
                    result.complete(bundle)
                }
            }
            return try {
                service.request(bundle, callback)
                withTimeoutOrNull(20_000) { result.await() }
            } catch (e: android.os.RemoteException) {
                null
            }
        } finally {
            runCatching { context.unbindService(conn) }
        }
    }

    /** Sends an AppMessage to a watchapp on all connected watches. True if at least one watch took it. */
    suspend fun send(context: Context, app: UUID, dict: Map<Int, Any>): Boolean {
        val reply = request(
            context,
            Bundle().apply {
                putString(KEY_ACTION, ACTION_SEND_DATA_TO_WATCH)
                putString(KEY_WATCHAPP_UUID, app.toString())
                putBundle(KEY_DATA_DICTIONARY, toBundle(dict))
            },
        ) ?: return false
        val results = reply.getBundle(KEY_TRANSMISSION_RESULTS) ?: return false
        return results.keySet().any { results.getBundle(it)?.getString("TYPE") == "SUCCESS" }
    }

    /** Opens a watchapp on the connected watches (no special permission needed). */
    suspend fun startApp(context: Context, app: UUID): Boolean =
        request(
            context,
            Bundle().apply {
                putString(KEY_ACTION, ACTION_START_APP)
                putString(KEY_WATCHAPP_UUID, app.toString())
            },
        ) != null

    /**
     * Adds a timeline pin for our watchapp. [icon] is a system resource such as
     * "system://images/NOTIFICATION_FLAG".
     */
    suspend fun insertPin(
        context: Context,
        app: UUID,
        id: String,
        time: java.time.Instant,
        title: String,
        body: String,
        icon: String,
        color: String? = null,
    ): Boolean {
        val pin = Bundle().apply {
            putString("ID", id)
            putString("START_TIME", time.toString())
            putString("LAYOUT_TYPE", "genericPin")
            putString("LAYOUT_TITLE", title)
            if (body.isNotEmpty()) putString("LAYOUT_BODY", body)
            putString("LAYOUT_TINY_ICON", icon)
            color?.let { putString("LAYOUT_BACKGROUND_COLOR", it) }
            putInt("REMINDERS_COUNT", 0)
            putInt("ACTIONS_COUNT", 1)
            putBundle(
                "ACTION_0",
                Bundle().apply {
                    putString("ACTION_TITLE", "Open Thereabouts")
                    putString("ACTION_TYPE", "openWatchApp")
                },
            )
        }
        val reply = request(
            context,
            Bundle().apply {
                putString(KEY_ACTION, ACTION_SHOW_TIMELINE_PIN)
                putString(KEY_WATCHAPP_UUID, app.toString())
                putBundle(KEY_TIMELINE_PIN, pin)
            },
        )
        return reply?.getBundle(KEY_TIMELINE_RESULT)?.getString("TYPE") == "SUCCESS"
    }
}
