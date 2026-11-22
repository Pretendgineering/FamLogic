package io.github.spasm0dic.famlogic

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * New chat messages as notifications: one per conversation, in Android's messaging
 * style (several messages stack up), with a Reply button. Nothing is shown for the
 * chat that's open on screen.
 */
object ChatNotify {
    const val CHANNEL = "chat"
    const val EXTRA_OPEN_CHAT = "open_chat"
    private const val KEY_REPLY = "reply"

    // Recent messages per conversation, so a new one adds to the notification. Lost if the app restarts; that's fine.
    private val history = mutableMapOf<String, MutableList<NotificationCompat.MessagingStyle.Message>>()
    private val titles = mutableMapOf<String, String>()

    fun ensureChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "New messages in your family's chats"
            },
        )
    }

    fun id(conv: String) = 3_000_000 + (conv.hashCode() and 0xffff)

    @Synchronized
    fun show(context: Context, e: AppEvent) {
        if (e.conv.isEmpty() || ChatState.openConversation == e.conv) return
        val sender = Person.Builder().setName(e.name).setKey(e.memberId).build()
        val list = history.getOrPut(e.conv) { mutableListOf() }
        list += NotificationCompat.MessagingStyle.Message(e.note, if (e.time > 0) e.time else System.currentTimeMillis(), sender)
        while (list.size > 8) list.removeAt(0)
        titles[e.conv] = e.title
        post(context, e.conv)
    }

    /** Adds your own reply (sent from the notification) and refreshes it. */
    @Synchronized
    fun addReply(context: Context, conv: String, text: String) {
        val list = history.getOrPut(conv) { mutableListOf() }
        list += NotificationCompat.MessagingStyle.Message(text, System.currentTimeMillis(), null as Person?)
        post(context, conv, silent = true)
    }

    @Synchronized
    fun clear(context: Context, conv: String) {
        history.remove(conv)
        NotificationManagerCompat.from(context).cancel(id(conv))
    }

    private fun post(context: Context, conv: String, silent: Boolean = false) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannel(context)
        val title = titles[conv].orEmpty()
        val style = NotificationCompat.MessagingStyle(Person.Builder().setName("You").build())
            .setConversationTitle(title.ifEmpty { null })
            .setGroupConversation(title.isNotEmpty())
        history[conv].orEmpty().forEach { style.addMessage(it) }
        val open = PendingIntent.getActivity(
            context, id(conv),
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_OPEN_CHAT, conv),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // The reply box fills in the intent, so this one must be mutable.
        val reply = PendingIntent.getBroadcast(
            context, id(conv),
            Intent(context, ChatReplyReceiver::class.java).putExtra(EXTRA_OPEN_CHAT, conv),
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0),
        )
        val replyAction = NotificationCompat.Action.Builder(0, "Reply", reply)
            .addRemoteInput(RemoteInput.Builder(KEY_REPLY).setLabel("Message").build())
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .build()
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(open)
            .addAction(replyAction)
            .setAutoCancel(true)
            .setOnlyAlertOnce(silent)
            .setSilent(silent)
            .build()
        NotificationManagerCompat.from(context).notify(id(conv), n)
    }

    fun replyText(intent: Intent): String? = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)?.toString()
}

/** The Reply button on a chat notification. */
class ChatReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val conv = intent.getStringExtra(ChatNotify.EXTRA_OPEN_CHAT) ?: return
        val text = ChatNotify.replyText(intent)?.trim().orEmpty()
        if (text.isEmpty()) return
        val prefs = Prefs(context)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Api(prefs.serverUrl, prefs.token).chatSend(conv, text)
                ChatNotify.addReply(context, conv, text)
            } catch (e: Exception) {
                ChatNotify.addReply(context, conv, "⚠ Not sent: $text")
            } finally {
                pending.finish()
            }
        }
    }
}
