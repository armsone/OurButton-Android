package com.armsone.button.push

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.armsone.button.R
import com.armsone.button.data.BackendConfiguration
import com.armsone.button.data.BackendException
import com.armsone.button.data.CallHistoryStore
import com.armsone.button.data.HttpBackendClient
import com.armsone.button.data.PendingVoiceStore
import com.armsone.button.model.CallEvent
import com.armsone.button.platform.NotificationHelper
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import android.net.Uri
import java.time.Instant
import java.util.UUID

private fun workerFailure(error: Throwable): androidx.work.ListenableWorker.Result = when (error) {
    is BackendException.HttpError -> if (error.code == 429 || error.code >= 500) {
        androidx.work.ListenableWorker.Result.retry()
    } else {
        androidx.work.ListenableWorker.Result.failure()
    }
    BackendException.NotConfigured -> androidx.work.ListenableWorker.Result.failure()
    else -> androidx.work.ListenableWorker.Result.retry()
}

class ButtonFirebaseMessagingService : FirebaseMessagingService() {
    override fun onRegistered(token: String) {
        PushStore(applicationContext).saveToken(token)
        PushRegistrationManager.enqueue(applicationContext)
    }

    override fun onUnregistered(token: String) {
        PushStore(applicationContext).clearToken()
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val envelope = FcmEnvelope.parse(message.data) ?: return
        enqueueDelivery(applicationContext, envelope)
    }
}

class PushRegistrationWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val configuration = BackendConfiguration.load(applicationContext)
        if (!configuration.isConfigured) return Result.failure()
        val manager = PushRegistrationManager(
            applicationContext,
            HttpBackendClient(configuration),
            FirebasePushTokenProvider(applicationContext),
        )
        val outcome = manager.registerCurrentTokenIfAvailable() ?: return Result.success()
        return outcome.fold(
            onSuccess = { Result.success() },
            onFailure = ::workerFailure,
        )
    }
}

class PushDeliveryWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val envelope = FcmEnvelope.parse(
            mapOf(
                "eventID" to inputData.getString("eventID").orEmpty(),
                "spaceID" to inputData.getString("spaceID").orEmpty(),
                "kind" to inputData.getString("kind").orEmpty(),
            ),
        ) ?: return Result.failure()
        val store = PushStore(applicationContext)
        val membership = store.memberships.firstOrNull { it.space.id == envelope.spaceID }
            ?: return Result.success()
        val configuration = BackendConfiguration.load(applicationContext)
        if (!configuration.isConfigured) return Result.failure()
        val event = runCatching {
            HttpBackendClient(configuration).fetchEvent(envelope.eventID, membership.space)
        }.getOrElse { return workerFailure(it) }

        // The signed space fetch is authoritative. Validate every routing field before any
        // notification, audio, flash, or foreground callback becomes visible to the user.
        if (event.id != envelope.eventID || event.spaceID != envelope.spaceID || event.kind != envelope.kind) {
            return Result.failure()
        }
        if (event.senderID == membership.deviceID) return Result.success()
        if (!event.isAddressedTo(membership.deviceID)) return Result.success()
        if (!DeliveryDeduplicator(applicationContext).markIfNew(event.id)) return Result.success()
        val history = CallHistoryStore(applicationContext)
        if (event.kind == CallEvent.Kind.Acknowledge) {
            event.ackFor?.let { history.markAcknowledged(it, event.senderName, event.senderID) }
        } else {
            history.recordReceived(event)
        }

        if (!RemoteEventRouter.route(event)) {
            if (event.kind == CallEvent.Kind.VoiceMessage) {
                PendingVoiceStore(applicationContext).record(event.id)
            }
            NotificationHelper(
                applicationContext,
                Uri.parse("android.resource://${applicationContext.packageName}/${R.raw.dingdong3}"),
                Uri.parse("android.resource://${applicationContext.packageName}/${R.raw.siren}"),
            ).notify(event)
        }
        return Result.success()
    }
}

private class DeliveryDeduplicator(context: Context) {
    private val prefs = context.getSharedPreferences("button_push_delivery", Context.MODE_PRIVATE)

    fun markIfNew(id: UUID): Boolean = synchronized(lock) {
        val now = Instant.now().epochSecond
        val cutoff = now - 86_400
        val entries = prefs.all.mapNotNull { (key, value) ->
            (value as? Long)?.let { key to it }
        }.filter { it.second >= cutoff }.toMap()
        if (entries.containsKey(id.toString())) return false
        val editor = prefs.edit().clear()
        entries.forEach { (key, value) -> editor.putLong(key, value) }
        editor.putLong(id.toString(), now).apply()
        true
    }

    companion object {
        private val lock = Any()
    }
}

object RemoteEventRouter {
    private var owner: Any? = null
    private var listener: ((CallEvent) -> Boolean)? = null

    @Synchronized
    fun attach(newOwner: Any, newListener: (CallEvent) -> Boolean) {
        owner = newOwner
        listener = newListener
    }

    @Synchronized
    fun detach(currentOwner: Any) {
        if (owner === currentOwner) {
            owner = null
            listener = null
        }
    }

    @Synchronized
    fun route(event: CallEvent): Boolean {
        val callback = listener ?: return false
        return callback(event)
    }
}
