package com.armsone.ourbutton.model

import org.json.JSONObject
import org.json.JSONArray
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID

class CallEvent(
    val kind: Kind,
    val spaceID: UUID,
    val senderName: String,
    var version: Int = CURRENT_VERSION,
    val id: UUID = UUID.randomUUID(),
    var senderID: UUID? = null,
    var senderRole: FamilyRole? = null,
    var targetID: UUID? = null,
    var targetIDs: List<UUID>? = null,
    val sentAt: Instant = Instant.now(),
    var voiceData: ByteArray? = null,
    var ackFor: UUID? = null,
) {
    enum class Kind(val rawValue: String, val title: String) {
        QuietAlert("quietAlert", "톡톡"),
        Siren("siren", "사이렌 호출"),
        DingDong("dingDong", "띵동"),
        VoiceMessage("voiceMessage", "음성"),
        Acknowledge("acknowledge", "확인"),
        Presence("presence", "연결 확인");

        val arrivalTitle: String
            get() = "${title}이"

        companion object {
            fun fromRawValue(value: String): Kind? = entries.firstOrNull { it.rawValue == value }
        }
    }

    override fun equals(other: Any?): Boolean = other is CallEvent &&
        version == other.version && id == other.id && kind == other.kind &&
        spaceID == other.spaceID && senderName == other.senderName && senderID == other.senderID &&
        senderRole == other.senderRole && targetID == other.targetID && targetIDs == other.targetIDs && sentAt == other.sentAt &&
        ((voiceData == null && other.voiceData == null) ||
            (voiceData != null && other.voiceData != null && voiceData!!.contentEquals(other.voiceData!!))) &&
        ackFor == other.ackFor

    override fun hashCode(): Int {
        var result = version
        result = 31 * result + id.hashCode()
        result = 31 * result + kind.hashCode()
        result = 31 * result + spaceID.hashCode()
        result = 31 * result + senderName.hashCode()
        result = 31 * result + (senderID?.hashCode() ?: 0)
        result = 31 * result + (senderRole?.hashCode() ?: 0)
        result = 31 * result + (targetID?.hashCode() ?: 0)
        result = 31 * result + (targetIDs?.hashCode() ?: 0)
        result = 31 * result + sentAt.hashCode()
        result = 31 * result + (voiceData?.contentHashCode() ?: 0)
        return 31 * result + (ackFor?.hashCode() ?: 0)
    }

    fun isAddressedTo(deviceID: UUID): Boolean = when {
        !targetIDs.isNullOrEmpty() -> deviceID in targetIDs.orEmpty()
        targetID != null -> targetID == deviceID
        else -> true
    }

    companion object {
        const val CURRENT_VERSION = 1
        const val MAX_VOICE_BYTES = 2 * 1024 * 1024
        val MULTI_TARGET_SENTINEL: UUID = UUID.fromString("00000000-0000-4000-8000-000000000000")
    }
}

sealed class CallEventCodingError(message: String) : IllegalArgumentException(message) {
    class UnsupportedVersion(val version: Int) : CallEventCodingError("Unsupported version: $version")
    data object MissingVoiceData : CallEventCodingError("Voice message data is missing")
    class VoiceDataTooLarge(val size: Int) : CallEventCodingError("Voice message is too large: $size")
    data object EmptySenderName : CallEventCodingError("Sender name is empty")
    data object Malformed : CallEventCodingError("Malformed call event")
}

object CallEventCoder {
    fun encode(event: CallEvent): ByteArray {
        validate(event)
        val json = JSONObject()
            .put("version", event.version)
            .put("id", event.id.toString().uppercase())
            .put("kind", event.kind.rawValue)
            .put("spaceID", event.spaceID.toString().uppercase())
            .put("senderName", event.senderName)
            .put("sentAt", event.sentAt.truncatedTo(ChronoUnit.SECONDS).toString())
        event.senderID?.let { json.put("senderID", it.toString().uppercase()) }
        event.senderRole?.let { json.put("senderRole", it.rawValue) }
        event.targetID?.let { json.put("targetID", it.toString().uppercase()) }
        event.targetIDs?.let { ids ->
            json.put("targetIDs", JSONArray().apply { ids.forEach { put(it.toString().uppercase()) } })
        }
        event.voiceData?.let { json.put("voiceData", Base64.getEncoder().encodeToString(it)) }
        event.ackFor?.let { json.put("ackFor", it.toString().uppercase()) }
        return json.toString().toByteArray(StandardCharsets.UTF_8)
    }

    fun decode(data: ByteArray): CallEvent {
        val event = try {
            val text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(data))
                .toString()
            val json = JSONObject(text)
            CallEvent(
                kind = CallEvent.Kind.fromRawValue(json.requiredString("kind")) ?: throw IllegalArgumentException(),
                spaceID = UUID.fromString(json.requiredString("spaceID")),
                senderName = json.requiredString("senderName"),
                version = json.requiredInt("version"),
                id = UUID.fromString(json.requiredString("id")),
                senderID = json.optionalString("senderID")?.let(UUID::fromString),
                senderRole = json.optionalString("senderRole")?.let {
                    FamilyRole.fromRawValue(it) ?: throw IllegalArgumentException()
                },
                targetID = json.optionalString("targetID")?.let(UUID::fromString),
                targetIDs = json.optionalUUIDArray("targetIDs"),
                sentAt = Instant.parse(json.requiredString("sentAt")),
                voiceData = json.optionalString("voiceData")?.let(Base64.getDecoder()::decode),
                ackFor = json.optionalString("ackFor")?.let(UUID::fromString),
            )
        } catch (error: CallEventCodingError) {
            throw error
        } catch (_: Exception) {
            throw CallEventCodingError.Malformed
        }
        validate(event)
        return event
    }

    fun validate(event: CallEvent) {
        if (event.version != CallEvent.CURRENT_VERSION) {
            throw CallEventCodingError.UnsupportedVersion(event.version)
        }
        if (event.senderName.trim().isEmpty()) throw CallEventCodingError.EmptySenderName
        if (event.kind == CallEvent.Kind.VoiceMessage) {
            val voiceData = event.voiceData
            if (voiceData == null || voiceData.isEmpty()) throw CallEventCodingError.MissingVoiceData
            if (voiceData.size > CallEvent.MAX_VOICE_BYTES) {
                throw CallEventCodingError.VoiceDataTooLarge(voiceData.size)
            }
        }
    }

    private fun JSONObject.requiredString(name: String): String =
        (opt(name) as? String) ?: throw IllegalArgumentException()

    private fun JSONObject.optionalString(name: String): String? {
        if (!has(name) || isNull(name)) return null
        return (opt(name) as? String) ?: throw IllegalArgumentException()
    }

    private fun JSONObject.requiredInt(name: String): Int {
        val value = opt(name)
        return when (value) {
            is Int -> value
            is Long -> value.toInt().takeIf { it.toLong() == value } ?: throw IllegalArgumentException()
            else -> throw IllegalArgumentException()
        }
    }

    private fun JSONObject.optionalUUIDArray(name: String): List<UUID>? {
        if (!has(name) || isNull(name)) return null
        val array = optJSONArray(name) ?: throw IllegalArgumentException()
        return List(array.length()) { index -> UUID.fromString(array.getString(index)) }
    }
}
