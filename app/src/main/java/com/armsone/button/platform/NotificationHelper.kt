package com.armsone.button.platform

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.armsone.button.model.CallEvent
import com.armsone.button.R
import com.armsone.button.MainActivity

/** Posts app-owned call notifications with intentionally separate sound-policy channels. */
class NotificationHelper(
    private val context: Context,
    dingDongSound: Uri? = null,
    sirenSound: Uri? = null,
) {
    companion object {
        const val QUIET_CHANNEL_ID = "family_calls_quiet_v1"
        const val DING_DONG_CHANNEL_ID = "family_calls_ding_dong_v2"
        const val SIREN_CHANNEL_ID = "family_calls_siren_v1"
        const val VOICE_CHANNEL_ID = "family_calls_voice_v1"
        private const val CHANNEL_GROUP_ID = "family_calls"
        private const val NOTIFICATION_TAG = "family-call"
    }

    init {
        createChannels(dingDongSound, sirenSound)
    }

    fun createChannels(dingDongSound: Uri? = null, sirenSound: Uri? = null) {
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
        val voice = NotificationChannel(
            VOICE_CHANNEL_ID,
            "음성 메시지",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            group = CHANNEL_GROUP_ID
            description = "가족의 음성 메시지가 도착하면 알려요."
            enableVibration(false)
        }
        val siren = NotificationChannel(
            SIREN_CHANNEL_ID,
            "사이렌 알림",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            group = CHANNEL_GROUP_ID
            description = "긴급 사이렌 소리와 함께 가족 호출을 알려요."
            if (sirenSound != null) {
                setSound(
                    sirenSound,
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
        manager.createNotificationChannels(listOf(quiet, dingDong, voice, siren))
    }

    fun notify(event: CallEvent): Boolean {
        if (event.kind != CallEvent.Kind.QuietAlert &&
            event.kind != CallEvent.Kind.Siren &&
            event.kind != CallEvent.Kind.DingDong &&
            event.kind != CallEvent.Kind.VoiceMessage
        ) {
            return false
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return false

        val dingDong = event.kind == CallEvent.Kind.DingDong
        val siren = event.kind == CallEvent.Kind.Siren
        val voice = event.kind == CallEvent.Kind.VoiceMessage
        val contentIntent = PendingIntent.getActivity(
            context,
            event.id.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(
            context,
            when {
                siren -> SIREN_CHANNEL_ID
                dingDong -> DING_DONG_CHANNEL_ID
                voice -> VOICE_CHANNEL_ID
                else -> QUIET_CHANNEL_ID
            },
        )
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("버튼")
            .setSubText(when {
                siren -> "사이렌 알림"
                dingDong -> "띵동 알림"
                voice -> "음성 메시지"
                else -> "조용히 알림"
            })
            .setContentText(when {
                voice -> "${event.senderName}님의 음성 메시지가 도착했어요."
                siren -> "${event.senderName}님의 긴급 사이렌 호출이 왔어요."
                else -> "${event.senderName}님이 불렀어요."
            })
            .setContentIntent(contentIntent)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setSilent(!siren && !dingDong && !voice)
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
