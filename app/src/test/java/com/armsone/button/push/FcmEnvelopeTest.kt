package com.armsone.button.push

import com.armsone.button.model.CallEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class FcmEnvelopeTest {
    @Test
    fun parsesAuthenticatedFetchRoutingFields() {
        val eventID = UUID.randomUUID()
        val spaceID = UUID.randomUUID()
        val value = FcmEnvelope.parse(
            mapOf(
                "eventID" to eventID.toString(),
                "spaceID" to spaceID.toString(),
                "kind" to CallEvent.Kind.DingDong.rawValue,
            ),
        )
        assertEquals(eventID, value?.eventID)
        assertEquals(spaceID, value?.spaceID)
        assertEquals(CallEvent.Kind.DingDong, value?.kind)
    }

    @Test
    fun rejectsMissingMalformedAndPresenceMessages() {
        val valid = mapOf(
            "eventID" to UUID.randomUUID().toString(),
            "spaceID" to UUID.randomUUID().toString(),
            "kind" to CallEvent.Kind.QuietAlert.rawValue,
        )
        assertNull(FcmEnvelope.parse(valid - "eventID"))
        assertNull(FcmEnvelope.parse(valid + ("spaceID" to "not-a-uuid")))
        assertNull(FcmEnvelope.parse(valid + ("kind" to CallEvent.Kind.Presence.rawValue)))
    }

    @Test
    fun acceptsSirenRoutingMessage() {
        val value = FcmEnvelope.parse(
            mapOf(
                "eventID" to UUID.randomUUID().toString(),
                "spaceID" to UUID.randomUUID().toString(),
                "kind" to CallEvent.Kind.Siren.rawValue,
            ),
        )
        assertEquals(CallEvent.Kind.Siren, value?.kind)
    }
}
