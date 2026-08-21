package com.armsone.button.data

import android.content.Context
import android.content.pm.PackageManager
import com.armsone.button.model.CallEvent
import com.armsone.button.model.CallEventCoder
import com.armsone.button.model.FamilyRole
import com.armsone.button.model.FamilySpace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
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
            return BackendConfiguration(metadata?.getString("ButtonBackendURL"))
        }
    }
}

sealed class BackendException(message: String) : Exception(message) {
    data object NotConfigured : BackendException("서버가 아직 구성되지 않았어요.")
    class HttpError(val code: Int) : BackendException("서버 오류 (HTTP $code)")
}

interface BackendClient {
    suspend fun registerDevice(
        token: String,
        space: FamilySpace,
        deviceId: UUID,
        name: String,
        role: FamilyRole,
        environment: String,
    )
    suspend fun send(event: CallEvent, spaceSecret: String)
    suspend fun fetchEvent(id: UUID, space: FamilySpace): CallEvent
}

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
        token: String,
        space: FamilySpace,
        deviceId: UUID,
        name: String,
        role: FamilyRole,
        environment: String,
    ) {
        val body = JSONObject()
            .put("token", token)
            .put("spaceID", space.id.toString())
            .put("secret", space.secret)
            .put("deviceID", deviceId.toString())
            .put("name", name)
            .put("role", role.rawValue)
            .put("environment", environment)
            .put("platform", "android")
            .toString().toByteArray()
        request("v1/devices", "POST", body = body)
    }

    override suspend fun send(event: CallEvent, spaceSecret: String) {
        request(
            "v1/spaces/${event.spaceID}/calls",
            "POST",
            spaceSecret,
            CallEventCoder.encode(event),
        )
    }

    override suspend fun fetchEvent(id: UUID, space: FamilySpace): CallEvent =
        CallEventCoder.decode(request("v1/spaces/${space.id}/calls/$id", "GET", space.secret))

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
            if (code !in 200..299) throw BackendException.HttpError(code)
            connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }
}
