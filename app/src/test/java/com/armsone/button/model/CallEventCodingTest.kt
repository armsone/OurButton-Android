package com.armsone.button.model

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.util.UUID

class CallEventCodingTest {
    private val spaceID = UUID.randomUUID()

    @Test
    fun dingDongAndQuietAlertRoundTrip() {
        for (kind in listOf(CallEvent.Kind.DingDong, CallEvent.Kind.QuietAlert, CallEvent.Kind.Siren)) {
            val event = CallEvent(kind = kind, spaceID = spaceID, senderName = "엄마")
            val decoded = CallEventCoder.decode(CallEventCoder.encode(event))
            assertEquals(event.id, decoded.id)
            assertEquals(kind, decoded.kind)
            assertEquals(spaceID, decoded.spaceID)
            assertEquals("엄마", decoded.senderName)
            assertNull(decoded.voiceData)
            assertTrue(Duration.between(decoded.sentAt, event.sentAt).abs() < Duration.ofSeconds(1))
        }
        assertFalse(CallEvent.Kind.QuietAlert.title == CallEvent.Kind.DingDong.title)
        assertEquals("siren", CallEvent.Kind.Siren.rawValue)
    }

    @Test
    fun voiceMessageRoundTrip() {
        val voice = ByteArray(1024) { (it % 256).toByte() }
        val event = CallEvent(
            kind = CallEvent.Kind.VoiceMessage,
            spaceID = spaceID,
            senderName = "아빠",
            voiceData = voice,
        )
        val decoded = CallEventCoder.decode(CallEventCoder.encode(event))
        assertEquals(CallEvent.Kind.VoiceMessage, decoded.kind)
        assertArrayEquals(voice, decoded.voiceData)
    }

    @Test
    fun acknowledgeAndPresenceFieldsRoundTrip() {
        val originalID = UUID.randomUUID()
        val ack = CallEvent(
            kind = CallEvent.Kind.Acknowledge,
            spaceID = spaceID,
            senderName = "첫째",
            ackFor = originalID,
        )
        assertEquals(originalID, CallEventCoder.decode(CallEventCoder.encode(ack)).ackFor)

        val senderID = UUID.randomUUID()
        val presence = CallEvent(
            kind = CallEvent.Kind.Presence,
            spaceID = spaceID,
            senderName = "한지온",
            senderID = senderID,
            senderRole = FamilyRole.Child,
        )
        val decoded = CallEventCoder.decode(CallEventCoder.encode(presence))
        assertEquals(senderID, decoded.senderID)
        assertEquals(FamilyRole.Child, decoded.senderRole)
    }

    @Test
    fun targetedCallRoundTripsWithoutChangingProtocolVersion() {
        val targetID = UUID.randomUUID()
        val event = CallEvent(
            kind = CallEvent.Kind.DingDong,
            spaceID = spaceID,
            senderName = "엄마",
            targetID = targetID,
        )
        val encoded = CallEventCoder.encode(event)
        val json = JSONObject(String(encoded, Charsets.UTF_8))
        val decoded = CallEventCoder.decode(encoded)

        assertEquals(1, json.getInt("version"))
        assertEquals(targetID.toString().uppercase(), json.getString("targetID"))
        assertEquals(targetID, decoded.targetID)
    }

    @Test
    fun invalidVoiceDataAndSenderFailToEncode() {
        val missing = CallEvent(CallEvent.Kind.VoiceMessage, spaceID = spaceID, senderName = "아빠")
        assertTrue(assertThrows(CallEventCodingError::class.java) {
            CallEventCoder.encode(missing)
        } is CallEventCodingError.MissingVoiceData)

        val tooLarge = ByteArray(CallEvent.MAX_VOICE_BYTES + 1)
        val oversized = CallEvent(
            kind = CallEvent.Kind.VoiceMessage,
            spaceID = spaceID,
            senderName = "아빠",
            voiceData = tooLarge,
        )
        val sizeError = assertThrows(CallEventCodingError::class.java) { CallEventCoder.encode(oversized) }
        assertEquals(tooLarge.size, (sizeError as CallEventCodingError.VoiceDataTooLarge).size)

        val unnamed = CallEvent(CallEvent.Kind.DingDong, spaceID = spaceID, senderName = "  ")
        assertTrue(assertThrows(CallEventCodingError::class.java) {
            CallEventCoder.encode(unnamed)
        } is CallEventCodingError.EmptySenderName)
    }

    @Test
    fun unsupportedVersionAndGarbageFailToDecode() {
        val event = CallEvent(CallEvent.Kind.DingDong, spaceID = spaceID, senderName = "엄마")
        val json = JSONObject(String(CallEventCoder.encode(event), Charsets.UTF_8)).put("version", 99)
        val error = assertThrows(CallEventCodingError::class.java) {
            CallEventCoder.decode(json.toString().toByteArray())
        }
        assertEquals(99, (error as CallEventCodingError.UnsupportedVersion).version)
        assertTrue(assertThrows(CallEventCodingError::class.java) {
            CallEventCoder.decode("not json".toByteArray())
        } is CallEventCodingError.Malformed)
    }

    @Test
    fun payloadUsesIosJsonFieldNamesAndRawValues() {
        val event = CallEvent(CallEvent.Kind.QuietAlert, spaceID = spaceID, senderName = "엄마")
        val json = JSONObject(String(CallEventCoder.encode(event), Charsets.UTF_8))
        assertEquals("quietAlert", json.getString("kind"))
        assertEquals(spaceID.toString().uppercase(), json.getString("spaceID"))
        assertEquals(1, json.getInt("version"))
        assertFalse(json.has("voiceData"))
        assertFalse(json.has("targetID"))
    }
}
