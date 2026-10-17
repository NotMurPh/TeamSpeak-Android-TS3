package com.tsrewrite.client

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner

/** System notifications for pokes and private messages while the app is in the background. */
object Notifier {
    private const val CHANNEL = "messages"
    private var nextId = 100

    private fun inForeground() = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    private fun post(context: Context, title: String, text: String) {
        val app = context.applicationContext as App
        if (!app.store.settings.value.systemNotifications || inForeground()) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Messages and pokes", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(nextId++, Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build())
    }

    fun poke(context: Context, from: String, message: String) = post(context, "$from poked you", message)
    fun message(context: Context, from: String, message: String) = post(context, from, message)
}
