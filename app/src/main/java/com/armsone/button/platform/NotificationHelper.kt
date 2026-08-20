package com.armsone.button.platform

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.armsone.button.model.CallEvent
import com.armsone.button.R

/** Posts the two app-owned call notifications with intentionally separate channels. */
class NotificationHelper(private val context: Context, dingDongSound: Uri? = null) {
    companion object {
        const val QUIET_CHANNEL_ID = "family_calls_quiet_v1"
        const val DING_DONG_CHANNEL_ID = "family_calls_ding_dong_v1"
        private const val CHANNEL_GROUP_ID = "family_calls"
        private const val NOTIFICATION_TAG = "family-call"
    }

    init {
        createChannels(dingDongSound)
    }

    fun createChannels(dingDongSound: Uri? = null) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannelGroup(
            android.app.NotificationChannelGroup(CHANNEL_GROUP_ID, "가족 호출")
        )

        val quiet = NotificationChannel(
            QUIET_CHANNEL_ID,
            "조용히 알림",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            group = CHANNEL_GROUP_ID
            description = "소리 없이 가족 호출을 알려요."
            setSound(null, null)
            enableVibration(false)
        }
        val dingDong = NotificationChannel(
            DING_DONG_CHANNEL_ID,
            "띵동 알림",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            group = CHANNEL_GROUP_ID
            description = "띵동 소리와 함께 가족 호출을 알려요."
            if (dingDongSound != null) {
                setSound(
                    dingDongSound,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
            }
            enableVibration(false)
        }
        // Android channel sound/importance is immutable after first creation. New behavior
        // therefore requires a new versioned channel ID, never mutation of these channels.
        manager.createNotificationChannels(listOf(quiet, dingDong))
    }

    fun notify(event: CallEvent): Boolean {
        if (event.kind != CallEvent.Kind.QuietAlert && event.kind != CallEvent.Kind.DingDong) {
            return false
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return false

        val dingDong = event.kind == CallEvent.Kind.DingDong
        val notification = NotificationCompat.Builder(
            context,
            if (dingDong) DING_DONG_CHANNEL_ID else QUIET_CHANNEL_ID,
        )
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("버튼")
            .setSubText(if (dingDong) "띵동 알림" else "조용히 알림")
            .setContentText("${event.senderName}님이 불렀어요.")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setSilent(!dingDong)
            .setGroup("family-call-${event.spaceID}")
            .build()

        NotificationManagerCompat.from(context).notify(
            NOTIFICATION_TAG,
            event.id.hashCode(),
            notification,
        )
        return true
    }

    fun clearDeliveredCalls() {
        if (Build.VERSION.SDK_INT >= 23) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.activeNotifications
                .filter { it.tag == NOTIFICATION_TAG }
                .forEach { manager.cancel(it.tag, it.id) }
        }
    }
}
