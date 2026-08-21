package com.armsone.button.push

import android.content.Context
import android.content.pm.PackageManager
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.armsone.button.data.BackendClient
import com.armsone.button.data.PushTokenProvider
import com.armsone.button.model.FamilyRole
import com.armsone.button.model.FamilySpace
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.resume

data class FirebaseConfiguration(
    val applicationId: String,
    val projectId: String,
    val apiKey: String,
    val senderId: String,
) {
    val isConfigured: Boolean
        get() = applicationId.isNotBlank() && projectId.isNotBlank() &&
            apiKey.isNotBlank() && senderId.isNotBlank()

    fun ensureApp(context: Context): FirebaseApp? {
        FirebaseApp.getApps(context).firstOrNull()?.let { return it }
        if (!isConfigured) return null
        return FirebaseApp.initializeApp(
            context,
            FirebaseOptions.Builder()
                .setApplicationId(applicationId)
                .setProjectId(projectId)
                .setApiKey(apiKey)
                .setGcmSenderId(senderId)
                .build(),
        )
    }

    companion object {
        fun load(context: Context): FirebaseConfiguration {
            val metadata = runCatching {
                context.packageManager.getApplicationInfo(
                    context.packageName,
                    PackageManager.GET_META_DATA,
                ).metaData
            }.getOrNull()
            return FirebaseConfiguration(
                metadata?.getString("ButtonFirebaseApplicationId").orEmpty(),
                metadata?.getString("ButtonFirebaseProjectId").orEmpty(),
                metadata?.getString("ButtonFirebaseApiKey").orEmpty(),
                metadata?.getString("ButtonFirebaseSenderId").orEmpty(),
            )
        }
    }
}

class FirebasePushTokenProvider(private val context: Context) : PushTokenProvider {
    private val configuration = FirebaseConfiguration.load(context)
    override val statusDescription: String
        get() = if (configuration.isConfigured) "FCM 사용 가능" else "Firebase 설정 필요"

    override suspend fun requestRegistration(): Result<Unit> {
        configuration.ensureApp(context)
            ?: return Result.failure(IllegalStateException(statusDescription))
        return suspendCancellableCoroutine { continuation ->
            FirebaseMessaging.getInstance().register().addOnCompleteListener { task ->
                if (!continuation.isActive) return@addOnCompleteListener
                if (task.isSuccessful) {
                    continuation.resume(Result.success(Unit))
                } else {
                    continuation.resume(
                        Result.failure(task.exception ?: IllegalStateException("FCM 등록을 시작하지 못했어요.")),
                    )
                }
            }
        }
    }
}

data class PushMembership(
    val space: FamilySpace,
    val deviceID: UUID,
    val name: String,
    val role: FamilyRole,
)

class PushRegistrationManager(
    context: Context,
    private val backend: BackendClient,
    private val tokens: PushTokenProvider,
) {
    private val appContext = context.applicationContext
    private val store = PushStore(appContext)

    fun updateMembership(membership: PushMembership) = store.saveMembership(membership)
    fun clearMembership() = store.clearMembership()
    fun statusDescription(): String = when {
        tokens.statusDescription == "Firebase 설정 필요" -> tokens.statusDescription
        store.registeredFingerprint != null -> "FCM 등록됨"
        else -> "요청하지 않음"
    }

    suspend fun requestTokenAndRegister(): Result<Unit> = tokens.requestRegistration().fold(
        onSuccess = { registerCurrentTokenIfAvailable() ?: Result.success(Unit) },
        onFailure = { Result.failure(it) },
    )

    suspend fun registerCurrentTokenIfAvailable(): Result<Unit>? {
        val token = store.token ?: return null
        return register(token)
    }

    private suspend fun register(token: String): Result<Unit> {
        val membership = store.membership ?: return Result.failure(
            IllegalStateException("먼저 가족 공간에 참여해 주세요."),
        )
        val fingerprint = registrationFingerprint(token, membership)
        if (store.registeredFingerprint == fingerprint) return Result.success(Unit)
        return runCatching {
            backend.registerDevice(
                token,
                membership.space,
                membership.deviceID,
                membership.name,
                membership.role,
                "production",
            )
            store.registeredFingerprint = fingerprint
        }
    }

    private fun registrationFingerprint(token: String, membership: PushMembership): String {
        val bytes = listOf(
            token,
            membership.space.id,
            membership.space.secret,
            membership.deviceID,
            membership.name,
            membership.role.rawValue,
        ).joinToString("\u0000").toByteArray()
        return MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }

    companion object {
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<PushRegistrationWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "button-push-registration",
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}

internal class PushStore(context: Context) {
    private val prefs = context.getSharedPreferences("button_push", Context.MODE_PRIVATE)
    var token: String?
        get() = prefs.getString("token", null)
        private set(value) { prefs.edit().putString("token", value).apply() }
    var registeredFingerprint: String?
        get() = prefs.getString("registeredFingerprint", null)
        set(value) { prefs.edit().putString("registeredFingerprint", value).apply() }

    val membership: PushMembership?
        get() = runCatching {
            val json = JSONObject(prefs.getString("membership", null) ?: return null)
            PushMembership(
                FamilySpace(
                    UUID.fromString(json.getString("spaceID")),
                    json.getString("spaceName"),
                    json.getString("secret"),
                ),
                UUID.fromString(json.getString("deviceID")),
                json.getString("name"),
                FamilyRole.fromRawValue(json.getString("role")) ?: return null,
            )
        }.getOrNull()

    fun saveToken(value: String) { token = value }

    fun clearToken() {
        prefs.edit().remove("token").remove("registeredFingerprint").apply()
    }

    fun saveMembership(value: PushMembership) {
        val json = JSONObject()
            .put("spaceID", value.space.id.toString())
            .put("spaceName", value.space.name)
            .put("secret", value.space.secret)
            .put("deviceID", value.deviceID.toString())
            .put("name", value.name)
            .put("role", value.role.rawValue)
        val changed = prefs.getString("membership", null) != json.toString()
        prefs.edit().putString("membership", json.toString()).apply()
        if (changed) registeredFingerprint = null
    }

    fun clearMembership() {
        prefs.edit().remove("membership").remove("registeredFingerprint").apply()
    }
}

object DeviceIdentity {
    fun loadOrCreate(context: Context): UUID {
        val prefs = context.getSharedPreferences("button_hardware", Context.MODE_PRIVATE)
        prefs.getString("deviceID", null)?.let {
            runCatching { UUID.fromString(it) }.getOrNull()?.let { id -> return id }
        }
        return UUID.randomUUID().also { prefs.edit().putString("deviceID", it.toString()).apply() }
    }
}

internal fun enqueueDelivery(context: Context, envelope: FcmEnvelope) {
    val request = OneTimeWorkRequestBuilder<PushDeliveryWorker>()
        .setInputData(
            workDataOf(
                "eventID" to envelope.eventID.toString(),
                "spaceID" to envelope.spaceID.toString(),
                "kind" to envelope.kind.rawValue,
            ),
        )
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .build()
    WorkManager.getInstance(context).enqueueUniqueWork(
        "button-push-${envelope.eventID}",
        ExistingWorkPolicy.KEEP,
        request,
    )
}
