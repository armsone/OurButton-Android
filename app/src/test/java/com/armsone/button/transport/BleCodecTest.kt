package com.armsone.button.transport

import com.armsone.button.model.CallEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID

class BleCodecTest {
    private val secret = "0123456789abcdef0123456789abcdef"

    @Test
    fun encryptedRoundTrip() {
        val event = CallEvent(CallEvent.Kind.DingDong, spaceID = UUID.randomUUID(), senderName = "엄마")
        val encrypted = BleCodec.seal(event, secret)
        val decoded = BleCodec.open(encrypted, secret)
        assertEquals(event.id, decoded.id)
        assertEquals(CallEvent.Kind.DingDong, decoded.kind)
        assertEquals(event.spaceID, decoded.spaceID)
    }

    @Test
    fun wrongFamilySecretCannotOpenEvent() {
        val event = CallEvent(CallEvent.Kind.QuietAlert, spaceID = UUID.randomUUID(), senderName = "아빠")
        val encrypted = BleCodec.seal(event, secret)
        assertThrows(Exception::class.java) {
            BleCodec.open(encrypted, "fedcba9876543210fedcba9876543210")
        }
    }

    @Test
    fun fragmentsReassembleOutOfOrder() {
        val event = CallEvent(CallEvent.Kind.DingDong, spaceID = UUID.randomUUID(), senderName = "가족")
        val fragments = BleCodec.fragments(event, secret, maximumPayloadLength = 40)
        assertTrue(fragments.size > 1)
        assertTrue(fragments.all { it.size <= 40 })

        val reassembler = BleReassembler()
        var combined: ByteArray? = null
        for (fragment in fragments.reversed()) {
            combined = reassembler.append(fragment, peerID = "peer") ?: combined
        }
        assertEquals(event.id, BleCodec.open(requireNotNull(combined), secret).id)
    }

    @Test
    fun incompleteAndExpiredFragmentsDoNotProduceEvent() {
        val event = CallEvent(CallEvent.Kind.DingDong, spaceID = UUID.randomUUID(), senderName = "가족")
        val fragments = BleCodec.fragments(event, secret, maximumPayloadLength = 40)
        val reassembler = BleReassembler()
        val start = Instant.parse("2026-01-01T00:00:00Z")
        fragments.dropLast(1).forEach { assertNull(reassembler.append(it, "peer", start)) }
        assertNull(reassembler.append(fragments.last(), "peer", start.plusSeconds(30)))
    }

    @Test
    fun rejectsWrongFrameVersionAndConflictingCount() {
        val event = CallEvent(CallEvent.Kind.DingDong, spaceID = UUID.randomUUID(), senderName = "가족")
        val fragments = BleCodec.fragments(event, secret, maximumPayloadLength = 40)
        val reassembler = BleReassembler()
        assertNull(reassembler.append(fragments.first().clone().also { it[0] = 2 }, "peer"))
        assertNull(reassembler.append(fragments.first(), "peer"))
        val conflicting = fragments[1].clone().also { it[8] = (it[8].toInt() + 1).toByte() }
        assertNull(reassembler.append(conflicting, "peer"))
    }

    @Test
    fun boundedVoiceFallbackPreservesPayloadAndTargets() {
        val target = UUID.randomUUID()
        val voice = ByteArray(32 * 1024) { (it % 251).toByte() }
        val event = CallEvent(
            CallEvent.Kind.VoiceMessage,
            spaceID = UUID.randomUUID(),
            senderName = "김부장",
            targetID = target,
            voiceData = voice,
        )
        assertTrue(BleCodec.supports(event))
        val fragments = BleCodec.fragments(event, secret, maximumPayloadLength = 160)
        val reassembler = BleReassembler()
        var combined: ByteArray? = null
        fragments.forEach { fragment ->
            combined = reassembler.append(fragment, peerID = "android-peer") ?: combined
        }
        val decoded = BleCodec.open(requireNotNull(combined), secret)
        assertEquals(target, decoded.targetID)
        assertTrue(decoded.voiceData!!.contentEquals(voice))

        val tooLarge = event.copyForVoice(ByteArray(BleCodec.MAX_BLE_VOICE_BYTES + 1))
        assertTrue(!BleCodec.supports(tooLarge))
    }

    @Test
    fun peerRoutesCoverEitherConnectionDirectionWithoutDuplicates() {
        assertEquals(
            BlePeerRoutes(notifyAddresses = setOf("peer-a"), writeAddresses = setOf("peer-b")),
            blePeerRoutes(
                subscribedAddresses = setOf("peer-a"),
                connectedAddresses = setOf("peer-a", "peer-b"),
            ),
        )
        assertEquals(
            BlePeerRoutes(notifyAddresses = emptySet(), writeAddresses = setOf("peer-a")),
            blePeerRoutes(emptySet(), setOf("peer-a")),
        )
    }

    private fun CallEvent.copyForVoice(data: ByteArray) = CallEvent(
        kind = kind,
        spaceID = spaceID,
        senderName = senderName,
        senderID = senderID,
        senderRole = senderRole,
        targetID = targetID,
        targetIDs = targetIDs,
        voiceData = data,
    )
}
