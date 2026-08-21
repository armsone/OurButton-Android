package com.armsone.button.push

import com.armsone.button.model.CallEvent
import java.util.UUID

data class FcmEnvelope(
    val eventID: UUID,
    val spaceID: UUID,
    val kind: CallEvent.Kind,
) {
    companion object {
        fun parse(data: Map<String, String>): FcmEnvelope? {
            val eventID = data["eventID"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: return null
            val spaceID = data["spaceID"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: return null
            val kind = data["kind"]?.let(CallEvent.Kind::fromRawValue) ?: return null
            if (kind == CallEvent.Kind.Presence) return null
            return FcmEnvelope(eventID, spaceID, kind)
        }
    }
}
