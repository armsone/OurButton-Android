package com.armsone.ourbutton.data

import android.content.Context
import android.content.pm.PackageManager
import com.armsone.ourbutton.model.CallEvent
import com.armsone.ourbutton.model.CallEventCoder
import com.armsone.ourbutton.model.FamilyRole
import com.armsone.ourbutton.model.FamilySpace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

data class BackendConfiguration(val baseUrl: String?) {
    val isConfigured: Boolean get() = !baseUrl.isNullOrBlank()

    companion object {
        fun load(context: Context): BackendConfiguration {
            val stored = context.getSharedPreferences("button", Context.MODE_PRIVATE)
                .getString("backendURL", null)
            if (!stored.isNullOrBlank()) return BackendConfiguration(stored)
            val metadata = runCatching {
                context.packageManager.getApplicationInfo(
                    context.packageName,
                    PackageManager.GET_META_DATA,
                ).metaData
            }.getOrNull()
            return BackendConfiguration(
                metadata?.getString("OurButtonBackendURL")
                    ?: metadata?.getString("ButtonBackendURL")
            )
        }
    }
}

sealed class BackendException(message: String) : Exception(message) {
    data object NotConfigured : BackendException("서버가 아직 구성되지 않았어요.")
    class HttpError(val code: Int) : BackendException("서버 오류 (HTTP $code)")
    data object NoRecipients : BackendException("현재 전달할 수 있는 가족 기기가 없어요. 상대 기기에서 앱을 열거나 원격 알림을 켜 주세요.")
}

data class BackendSendReceipt(
    val delivered: Int,
    val attempted: Int,
    val muted: Int,
    val queued: Boolean,
    val acknowledged: Boolean = false,
)

data class BackendInboxPage(
    val events: List<CallEvent>,
    val cursor: String?,
    val hasMore: Boolean,
)

interface BackendClient {
    suspend fun registerDevice(
        token: String?,
        space: FamilySpace,
        deviceId: UUID,
        name: String,
        role: FamilyRole,
        environment: String,
    )
    suspend fun send(event: CallEvent, spaceSecret: String): BackendSendReceipt
    suspend fun fetchEvent(id: UUID, space: FamilySpace): CallEvent
    suspend fun fetchMembers(space: FamilySpace): List<RemoteFamilyMember>
    suspend fun fetchInbox(space: FamilySpace, deviceId: UUID, cursor: String?): BackendInboxPage
    suspend fun acknowledgeInbox(space: FamilySpace, deviceId: UUID, eventId: UUID)
    suspend fun setNotificationsMuted(space: FamilySpace, deviceId: UUID, muted: Boolean)
}

data class RemoteFamilyMember(
    val deviceID: UUID,
    val name: String,
    val role: FamilyRole,
    val notificationsMuted: Boolean,
)

/** Boundary kept separate from Firebase so registration and backend behavior remain testable. */
interface PushTokenProvider {
    val statusDescription: String
    suspend fun requestRegistration(): Result<Unit>
}

class NoPushTokenProvider : PushTokenProvider {
    override val statusDescription = "원격 알림 제공자가 연결되지 않음"
    override suspend fun requestRegistration() = Result.failure<Unit>(
        IllegalStateException(statusDescription),
    )
}

class HttpBackendClient(private val configuration: BackendConfiguration) : BackendClient {
    override suspend fun registerDevice(
        token: String?,
        space: FamilySpace,
        deviceId: UUID,
        name: String,
        role: FamilyRole,
        environment: String,
    ) {
        val body = JSONObject()
            .put("spaceID", space.id.toString())
            .put("secret", space.secret)
            .put("deviceID", deviceId.toString())
            .put("name", name)
            .put("role", role.rawValue)
            .put("environment", environment)
            .put("platform", "android")
        token?.takeIf { it.isNotBlank() }?.let { body.put("token", it) }
        request("v1/devices", "POST", body = body.toString().toByteArray())
    }

    override suspend fun send(event: CallEvent, spaceSecret: String): BackendSendReceipt {
        val response = JSONObject(String(request(
            "v1/spaces/${event.spaceID}/calls",
            "POST",
            spaceSecret,
            CallEventCoder.encode(event),
        )))
        val receipt = BackendSendReceipt(
            delivered = response.optInt("delivered", 0).coerceAtLeast(0),
            attempted = response.optInt("attempted", response.optInt("delivered", 0)).coerceAtLeast(0),
            muted = response.optInt("muted", 0).coerceAtLeast(0),
            queued = response.optBoolean("queued", false),
            acknowledged = response.optBoolean("acknowledged", false),
        )
        if (receipt.delivered == 0 && !receipt.queued && !receipt.acknowledged) {
            throw BackendException.NoRecipients
        }
        return receipt
    }

    override suspend fun fetchEvent(id: UUID, space: FamilySpace): CallEvent =
        CallEventCoder.decode(request("v1/spaces/${space.id}/calls/$id", "GET", space.secret))

    override suspend fun fetchMembers(space: FamilySpace): List<RemoteFamilyMember> {
        val response = JSONObject(String(request(
            "v1/spaces/${space.id}/members", "GET", space.secret,
        )))
        return response.getJSONArray("members").mapObjects { member ->
            RemoteFamilyMember(
                deviceID = UUID.fromString(member.getString("deviceID")),
                name = member.getString("name"),
                role = FamilyRole.fromRawValue(member.getString("role"))
                    ?: throw IllegalArgumentException("invalid family role"),
                notificationsMuted = member.optBoolean("notificationsMuted", false),
            )
        }
    }

    override suspend fun fetchInbox(
        space: FamilySpace,
        deviceId: UUID,
        cursor: String?,
    ): BackendInboxPage {
        val query = buildString {
            append("?deviceID=")
            append(URLEncoder.encode(deviceId.toString(), StandardCharsets.UTF_8.name()))
            cursor?.takeIf(String::isNotBlank)?.let {
                append("&cursor=")
                append(URLEncoder.encode(it, StandardCharsets.UTF_8.name()))
            }
        }
        val response = JSONObject(String(request(
            "v1/spaces/${space.id}/events$query", "GET", space.secret,
        )))
        val events = response.getJSONArray("events").mapObjects { event ->
            CallEventCoder.decode(event.toString().toByteArray(StandardCharsets.UTF_8))
        }
        return BackendInboxPage(
            events = events,
            cursor = response.optString("cursor").takeIf { !response.isNull("cursor") && it.isNotBlank() },
            hasMore = response.optBoolean("hasMore", false),
        )
    }

    override suspend fun acknowledgeInbox(space: FamilySpace, deviceId: UUID, eventId: UUID) {
        val body = JSONObject().put("deviceID", deviceId.toString()).toString().toByteArray()
        request("v1/spaces/${space.id}/events/$eventId/ack", "POST", space.secret, body)
    }

    override suspend fun setNotificationsMuted(space: FamilySpace, deviceId: UUID, muted: Boolean) {
        val body = JSONObject()
            .put("spaceID", space.id.toString())
            .put("secret", space.secret)
            .put("deviceID", deviceId.toString())
            .put("platform", "android")
            .put("notificationsMuted", muted)
        request("v1/devices", "POST", body = body.toString().toByteArray())
    }

    private suspend fun request(
        path: String,
        method: String,
        spaceSecret: String? = null,
        body: ByteArray? = null,
    ): ByteArray = withContext(Dispatchers.IO) {
        val base = configuration.baseUrl?.takeIf { it.isNotBlank() }
            ?: throw BackendException.NotConfigured
        val connection = URL(base.trimEnd('/') + "/" + path.trimStart('/'))
            .openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/json")
            spaceSecret?.let { connection.setRequestProperty("X-Space-Secret", it) }
            body?.let {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { output -> output.write(it) }
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                val errorCode = runCatching {
                    val errorBody = connection.errorStream?.use { it.readBytes() } ?: ByteArray(0)
                    JSONObject(String(errorBody))
                        .optString("code")
                }.getOrNull()
                if (code == 503 && errorCode == "delivery_unavailable") {
                    throw BackendException.NoRecipients
                }
                throw BackendException.HttpError(code)
            }
            connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }
}

private fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> =
    List(length()) { index -> transform(getJSONObject(index)) }
