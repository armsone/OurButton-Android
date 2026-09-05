package com.armsone.ourbutton.push

import android.content.Context
import android.content.pm.PackageManager
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.armsone.ourbutton.data.BackendClient
import com.armsone.ourbutton.data.PushTokenProvider
import com.armsone.ourbutton.model.FamilyRole
import com.armsone.ourbutton.model.FamilySpace
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import org.json.JSONArray
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
        if (!PushStore(context).token.isNullOrBlank()) return Result.success(Unit)
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

class PushRegistrationManager internal constructor(
    private val store: PushStore,
    private val backend: BackendClient,
    private val tokens: PushTokenProvider,
) {
    constructor(
        context: Context,
        backend: BackendClient,
        tokens: PushTokenProvider,
    ) : this(PushStore(context.applicationContext), backend, tokens)

    fun updateMembership(membership: PushMembership) = updateMemberships(listOf(membership))
    fun updateMemberships(memberships: List<PushMembership>) = store.saveMemberships(memberships)
    fun clearMembership() = store.clearMemberships()
    fun statusDescription(): String = when {
        tokens.statusDescription == "Firebase 설정 필요" -> tokens.statusDescription
        !store.token.isNullOrBlank() && store.registeredFingerprint != null -> "FCM 등록됨"
        else -> "요청하지 않음"
    }

    suspend fun requestTokenAndRegister(): Result<Unit> = tokens.requestRegistration().fold(
        onSuccess = { syncMemberships(store.token) },
        onFailure = { Result.failure(it) },
    )

    suspend fun registerCurrentTokenIfAvailable(): Result<Unit> = syncMemberships(store.token)

    suspend fun syncMemberships(token: String? = store.token): Result<Unit> {
        val memberships = store.memberships
        if (memberships.isEmpty()) return Result.failure(
            IllegalStateException("먼저 가족 공간에 참여해 주세요."),
        )
        val fingerprint = registrationFingerprint(token, memberships)
        if (store.registeredFingerprint == fingerprint) return Result.success(Unit)
        return runCatching {
            memberships.forEach { membership ->
                backend.registerDevice(
                    token,
                    membership.space,
                    membership.deviceID,
                    membership.name,
                    membership.role,
                    "production",
                )
            }
            store.registeredFingerprint = fingerprint
        }
    }

    companion object {
        fun registrationFingerprint(token: String?, memberships: List<PushMembership>): String {
            val parts = memberships.sortedBy { it.space.id.toString() }.flatMap { membership -> listOf(
                membership.space.id, membership.space.name, membership.space.secret, membership.deviceID,
                membership.name, membership.role.rawValue,
            ) }
            val bytes = (listOf(token.orEmpty()) + parts).joinToString("\u0000").toByteArray()
            return MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
        }

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

internal class PushStore(private val prefs: android.content.SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences("button_push", Context.MODE_PRIVATE))
    var token: String?
        get() = prefs.getString("token", null)
        set(value) { prefs.edit().putString("token", value).apply() }
    var registeredFingerprint: String?
        get() = prefs.getString("registeredFingerprint", null)
        set(value) { prefs.edit().putString("registeredFingerprint", value).apply() }

    val memberships: List<PushMembership>
        get() = runCatching {
            val stored = prefs.getString("memberships", null)
            if (stored != null) {
                val array = JSONArray(stored)
                return buildList {
                    for (index in 0 until array.length()) {
                        array.optJSONObject(index)?.toMembership()?.let(::add)
                    }
                }
            }
            val legacy = prefs.getString("membership", null) ?: return emptyList()
            listOfNotNull(JSONObject(legacy).toMembership())
        }.getOrDefault(emptyList())

    private fun JSONObject.toMembership(): PushMembership? = runCatching {
            PushMembership(
                FamilySpace(
                    UUID.fromString(getString("spaceID")),
                    getString("spaceName"),
                    getString("secret"),
                ),
                UUID.fromString(getString("deviceID")),
                getString("name"),
                FamilyRole.fromRawValue(getString("role")) ?: return null,
            )
        }.getOrNull()

    fun saveToken(value: String) { token = value }

    fun clearToken() {
        prefs.edit().remove("token").remove("registeredFingerprint").apply()
    }

    fun saveMemberships(values: List<PushMembership>) {
        val json = JSONArray()
        values.sortedBy { it.space.id.toString() }.forEach { value -> json.put(JSONObject()
            .put("spaceID", value.space.id.toString())
            .put("spaceName", value.space.name)
            .put("secret", value.space.secret)
            .put("deviceID", value.deviceID.toString())
            .put("name", value.name)
            .put("role", value.role.rawValue))
        }
        val changed = prefs.getString("memberships", null) != json.toString()
        prefs.edit().putString("memberships", json.toString()).remove("membership").apply()
        if (changed) registeredFingerprint = null
    }

    fun clearMemberships() {
        prefs.edit().remove("membership").remove("memberships").remove("registeredFingerprint").apply()
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
