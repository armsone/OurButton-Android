package com.armsone.button.data

import com.armsone.button.model.CallEvent
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.UUID

class CallHistoryStoreTest {
    private lateinit var directory: File
    private val spaceID = UUID.randomUUID()

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("CallHistoryStoreTest-").toFile()
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun persistsSentAndReceivedVoiceForReplay() {
        val sentID = UUID.randomUUID()
        val receivedID = UUID.randomUUID()
        val sentVoice = ByteArray(128) { 0x11.toByte() }
        val receivedVoice = ByteArray(256) { 0x22.toByte() }
        val store = makeStore()

        store.recordSent(sentID, spaceID, CallEvent.Kind.VoiceMessage, "아들", Instant.parse("2026-08-21T01:00:00Z"), sentVoice)
        store.recordReceived(receivedID, spaceID, CallEvent.Kind.VoiceMessage, "딸", Instant.parse("2026-08-21T01:01:00Z"), receivedVoice)

        val reloaded = makeStore()
        assertEquals(listOf(receivedID, sentID), reloaded.entries.map { it.id })
        assertArrayEquals(receivedVoice, reloaded.voiceData(reloaded.entries[0]))
        assertArrayEquals(sentVoice, reloaded.voiceData(reloaded.entries[1]))
    }

    @Test
    fun duplicateEventIDIsRecordedOnceAcrossTransports() {
        val eventID = UUID.randomUUID()
        val store = makeStore()

        store.recordReceived(eventID, spaceID, CallEvent.Kind.DingDong, "엄마", Instant.now())
        store.recordReceived(eventID, spaceID, CallEvent.Kind.DingDong, "엄마", Instant.now())

        assertEquals(1, store.entries.size)
    }

    @Test
    fun acknowledgementUpdatesOnlySentEntryAndIsIdempotent() {
        val sentID = UUID.randomUUID()
        val receivedID = UUID.randomUUID()
        val store = makeStore()
        store.recordSent(sentID, spaceID, CallEvent.Kind.QuietAlert, null, Instant.now())
        store.recordReceived(receivedID, spaceID, CallEvent.Kind.QuietAlert, "엄마", Instant.now())

        assertTrue(store.markAcknowledged(sentID, "딸"))
        assertTrue(store.markAcknowledged(sentID, "딸"))
        assertFalse(store.markAcknowledged(receivedID, "아들"))
        assertEquals(listOf("딸"), store.entries.first { it.id == sentID }.acknowledgedBy)
        assertTrue(store.entries.first { it.id == receivedID }.acknowledgedBy.isEmpty())
    }

    @Test
    fun entryAndVoiceLimitsRemoveOldestDataButKeepRecentMetadata() {
        val store = makeStore(maxEntries = 3, maxVoiceEntries = 1)
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        store.recordReceived(first, spaceID, CallEvent.Kind.VoiceMessage, "첫째", Instant.parse("2026-08-21T01:00:00Z"), byteArrayOf(1))
        store.recordReceived(second, spaceID, CallEvent.Kind.VoiceMessage, "둘째", Instant.parse("2026-08-21T01:01:00Z"), byteArrayOf(2))
        store.recordReceived(UUID.randomUUID(), spaceID, CallEvent.Kind.DingDong, "셋째", Instant.parse("2026-08-21T01:02:00Z"))
        store.recordReceived(UUID.randomUUID(), spaceID, CallEvent.Kind.QuietAlert, "넷째", Instant.parse("2026-08-21T01:03:00Z"))

        assertEquals(3, store.entries.size)
        assertNull(store.entries.find { it.id == first })
        assertArrayEquals(byteArrayOf(2), store.voiceData(store.entries.first { it.id == second }))
    }

    @Test
    fun corruptIndexLoadsEmptyAndClearRemovesVoice() {
        directory.mkdirs()
        File(directory, "history.json").writeText("not-json")
        val store = makeStore()
        assertTrue(store.entries.isEmpty())

        val voiceID = UUID.randomUUID()
        store.recordReceived(voiceID, spaceID, CallEvent.Kind.VoiceMessage, "아빠", Instant.now(), byteArrayOf(7, 8, 9))
        val entry = store.entries.first()
        assertArrayEquals(byteArrayOf(7, 8, 9), store.voiceData(entry))
        store.clear()
        assertTrue(store.entries.isEmpty())
        assertNull(store.voiceData(entry))
    }

    private fun makeStore(maxEntries: Int = 50, maxVoiceEntries: Int = 10) =
        CallHistoryStore(directory, maxEntries, maxVoiceEntries)
}
