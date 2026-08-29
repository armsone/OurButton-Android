package com.armsone.ourbutton.push

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.armsone.ourbutton.model.CallEvent
import java.util.UUID
import java.util.concurrent.TimeUnit

enum class NotificationMuteSyncStatus { SYNCED, SYNCING, ERROR }

data class SpaceNotificationMuteState(
    val muted: Boolean,
    val syncStatus: NotificationMuteSyncStatus,
    val errorMessage: String? = null,
)

fun shouldSuppressAppOwnedAlert(muted: Boolean, kind: CallEvent.Kind): Boolean = muted && kind in setOf(
    CallEvent.Kind.QuietAlert,
    CallEvent.Kind.Siren,
    CallEvent.Kind.DingDong,
    CallEvent.Kind.VoiceMessage,
)

/** Device-local protection is authoritative immediately; remote confirmation is tracked separately. */
class SpaceNotificationMuteStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "button_notification_mute",
        Context.MODE_PRIVATE,
    )

    fun state(spaceID: UUID): SpaceNotificationMuteState {
        val key = spaceID.toString()
        val status = runCatching {
            NotificationMuteSyncStatus.valueOf(
                prefs.getString("status_$key", NotificationMuteSyncStatus.SYNCED.name)!!,
            )
        }.getOrDefault(NotificationMuteSyncStatus.SYNCED)
        return SpaceNotificationMuteState(
            muted = prefs.getBoolean("muted_$key", false),
            syncStatus = status,
            errorMessage = prefs.getString("error_$key", null),
        )
    }

    fun isMuted(spaceID: UUID): Boolean = state(spaceID).muted

    fun setDesired(spaceID: UUID, muted: Boolean): SpaceNotificationMuteState {
        val key = spaceID.toString()
        prefs.edit()
            .putBoolean("muted_$key", muted)
            .putString("status_$key", NotificationMuteSyncStatus.SYNCING.name)
            .remove("error_$key")
            .apply()
        return state(spaceID)
    }

    fun markSynced(spaceID: UUID, requestedMuted: Boolean): SpaceNotificationMuteState {
        val key = spaceID.toString()
        if (state(spaceID).muted != requestedMuted) return state(spaceID)
        prefs.edit()
            .putString("status_$key", NotificationMuteSyncStatus.SYNCED.name)
            .remove("error_$key")
            .apply()
        return state(spaceID)
    }

    fun markError(
        spaceID: UUID,
        requestedMuted: Boolean,
        message: String,
    ): SpaceNotificationMuteState {
        val key = spaceID.toString()
        if (state(spaceID).muted != requestedMuted) return state(spaceID)
        prefs.edit()
            .putString("status_$key", NotificationMuteSyncStatus.ERROR.name)
            .putString("error_$key", message)
            .apply()
        return state(spaceID)
    }

    fun clear(spaceID: UUID) {
        val key = spaceID.toString()
        prefs.edit().remove("muted_$key").remove("status_$key").remove("error_$key").apply()
    }
}

object SpaceNotificationMuteSync {
    fun enqueue(context: Context, spaceID: UUID, requestedMuted: Boolean): UUID {
        val request = OneTimeWorkRequestBuilder<NotificationMuteWorker>()
            .setInputData(workDataOf(
                "spaceID" to spaceID.toString(),
                "notificationsMuted" to requestedMuted,
            ))
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            "button-notification-mute-${spaceID}",
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
        return request.id
    }
}
