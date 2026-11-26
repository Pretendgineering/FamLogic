package io.github.pretendgineering.famlogic

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * FamLogic TV's remote helper, an accessibility service (like Projectivy's and
 * Button Mapper's). With it switched on in the TV's Accessibility settings, FamLogic can:
 *
 * - **Override the current launcher:** when the TV's own home screen opens (the Home
 *   button, or after an app closes), FamLogic opens instead. Useful on Google TV, which
 *   doesn't let you change or uninstall the stock home screen.
 * - **Start when the TV turns on.**
 * - **Remap remote buttons:** a button (the Netflix or YouTube button, say) opens an app
 *   of your choice, FamLogic, or Steward.
 *
 * It reads no screen content: only which app's window came to the front, and key presses.
 */
class TvRemoteService : AccessibilityService() {
    private var lastOpen = 0L

    override fun onServiceConnected() {
        running = true
        // Ask for the remote's buttons (set here as well as in the service's XML: some
        // Android versions only honour it when set in code).
        serviceInfo = serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS }
        // Shortly after the TV starts, open FamLogic if asked to.
        if (TvRemoteSettings.boot(this) && SystemClock.elapsedRealtime() < 5 * 60_000) open(null)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || !TvRemoteSettings.override(this)) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg in stockLaunchers(this)) open(null)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        // Learning a button for a remap: the next press is the one.
        TvNav.capture?.let { take ->
            if (code in NAVIGATION_KEYS) return false
            if (event.action == KeyEvent.ACTION_UP) { TvNav.capture = null; take(code) }
            return true
        }
        val action = when {
            code == KeyEvent.KEYCODE_HOME && TvRemoteSettings.override(this) -> "home"
            else -> TvRemoteSettings.keys(this)[code]
        } ?: return false
        if (event.action == KeyEvent.ACTION_UP && event.repeatCount == 0) perform(action)
        return true
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    private fun perform(action: String) {
        when {
            action.startsWith("app:") -> {
                val pkg = action.removePrefix("app:")
                val pm = packageManager
                val i = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg) ?: return
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            action == "steward" -> open("steward")
            else -> open("home")
        }
    }

    /** Brings FamLogic to the front (at most once a second, so it never loops). */
    private fun open(view: String?) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastOpen < 1000) return
        lastOpen = now
        startActivity(Intent(this, TvActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("open", view ?: "home"))
    }

    companion object {
        @Volatile var running = false

        /** Keys a remap never takes, so the remote can always move around and go back. */
        val NAVIGATION_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK,
        )

        /** The TV's other home screens (Google TV's, Android TV's...), not FamLogic or the setup screens. */
        fun stockLaunchers(context: Context): Set<String> {
            val pm = context.packageManager
            return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
                .map { it.activityInfo.packageName }
                .filter { it != context.packageName && it != "com.android.settings" && it != "com.android.tv.settings" }
                .toSet()
        }

        /** Whether the service is switched on in the TV's Accessibility settings. */
        fun enabled(context: Context): Boolean {
            val me = ComponentName(context, TvRemoteService::class.java)
            val on = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            return on.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}

/** The remote helper's choices, kept on the TV (with the rest of its settings). */
object TvRemoteSettings {
    private fun sp(c: Context) = c.getSharedPreferences("tv", Context.MODE_PRIVATE)
    fun override(c: Context) = sp(c).getBoolean("override", false)
    fun setOverride(c: Context, on: Boolean) = sp(c).edit().putBoolean("override", on).apply()
    fun boot(c: Context) = sp(c).getBoolean("boot", false)
    fun setBoot(c: Context, on: Boolean) = sp(c).edit().putBoolean("boot", on).apply()

    /** Remapped buttons: key code to action ("app:<package>", "home" or "steward"). */
    fun keys(c: Context): Map<Int, String> = sp(c).getString("keys", "").orEmpty().split(';').mapNotNull { e ->
        val (k, v) = e.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
        k.toIntOrNull()?.let { it to v }
    }.toMap()
    fun setKeys(c: Context, keys: Map<Int, String>) = sp(c).edit().putString("keys", keys.entries.joinToString(";") { "${it.key}=${it.value}" }).apply()

    /** "Netflix button", "Button 1", from a key code. */
    fun keyName(code: Int): String {
        val raw = KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_").lowercase().replace('_', ' ')
        return raw.replaceFirstChar { it.uppercase() }.let { if (it.contains("button")) it else "$it button" }
    }
}

/** Between the remote helper and the screen: what to open, and a button being learned. */
object TvNav {
    /** What the helper asked for ("home" closes whatever is open; "steward" asks Steward). */
    var open by mutableStateOf<String?>(null)
    /** Bumped on each request, so the same one twice still counts. */
    var seq by mutableIntStateOf(0)
    /** Waiting for a remote button to remap. */
    @Volatile var capture: ((Int) -> Unit)? = null

    fun request(view: String?) { open = view; seq++ }
}
