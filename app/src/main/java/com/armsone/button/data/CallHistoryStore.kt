package com.armsone.button.data

import android.content.Context
import com.armsone.button.model.CallEvent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.UUID

class PendingVoiceStore(context: Context) {
    private val preferences = context.getSharedPreferences("button_pending_voice", Context.MODE_PRIVATE)

    fun record(eventID: UUID) {
        preferences.edit().putString("eventID", eventID.toString()).apply()
    }

    fun take(): UUID? {
        val value = preferences.getString("eventID", null)
        preferences.edit().remove("eventID").apply()
        return value?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    }

    fun clear() = preferences.edit().clear().apply()
}

data class CallHistoryEntry(
    val id: UUID,
    val spaceID: UUID,
    val kind: CallEvent.Kind,
    val direction: Direction,
    val counterpartName: String,
    val date: Instant,
    val acknowledgedBy: List<String> = emptyList(),
    val acknowledgementKeys: List<String>? = null,
    val intendedRecipientCount: Int? = null,
    val voiceFileName: String? = null,
) {
    enum class Direction { SENT, RECEIVED }

    val hasReplayableVoice: Boolean
        get() = kind == CallEvent.Kind.VoiceMessage && voiceFileName != null
    val pendingRecipientCount: Int
        get() = if (direction == Direction.SENT && intendedRecipientCount != null) {
            (intendedRecipientCount - (acknowledgementKeys ?: acknowledgedBy).toSet().size).coerceAtLeast(0)
        } else 0
}

/** Keeps call metadata and short voice messages on this device only. */
class CallHistoryStore(
    private val directory: File,
    private val maxEntries: Int = MAX_ENTRIES,
    private val maxVoiceEntries: Int = MAX_VOICE_ENTRIES,
) {
    constructor(context: Context) : this(File(context.noBackupFilesDir, "ButtonCallHistory"))

    private val indexFile = File(directory, "history.json")
    private val mutableEntries = loadEntries().toMutableList()

    val entries: List<CallHistoryEntry>
        get() = synchronized(FILE_LOCK) {
            reloadFromDisk()
            mutableEntries.toList()
        }

    init {
        synchronized(FILE_LOCK) {
            directory.mkdirs()
            reloadFromDisk()
            removeMissingVoiceReferences()
            trimIfNeeded()
            removeOrphanedVoiceFiles()
            persist()
        }
    }

    fun recordSent(
        id: UUID,
        spaceID: UUID,
        kind: CallEvent.Kind,
        targetName: String?,
        date: Instant,
        voiceData: ByteArray? = null,
        intendedRecipientCount: Int = 0,
    ) {
        synchronized(FILE_LOCK) {
            reloadFromDisk()
            record(id, spaceID, kind, CallHistoryEntry.Direction.SENT, targetName ?: "모두", date,
                voiceData, intendedRecipientCount.coerceAtLeast(0))
        }
    }

    fun recordSent(event: CallEvent, targetName: String?) {
        recordSent(event.id, event.spaceID, event.kind, targetName, event.sentAt, event.voiceData)
    }

    fun recordReceived(
        id: UUID,
        spaceID: UUID,
        kind: CallEvent.Kind,
        senderName: String,
        date: Instant,
        voiceData: ByteArray? = null,
    ) {
        synchronized(FILE_LOCK) {
            reloadFromDisk()
            record(id, spaceID, kind, CallHistoryEntry.Direction.RECEIVED, senderName, date, voiceData)
        }
    }

    fun recordReceived(event: CallEvent) {
        recordReceived(event.id, event.spaceID, event.kind, event.senderName, event.sentAt, event.voiceData)
    }

    /** Returns true when the acknowledgement belongs to a sent entry, including duplicates. */
    fun markAcknowledged(eventID: UUID, by: String, recipientID: UUID? = null): Boolean = synchronized(FILE_LOCK) {
        reloadFromDisk()
        val index = mutableEntries.indexOfFirst {
            it.id == eventID && it.direction == CallHistoryEntry.Direction.SENT
        }
        if (index < 0) return@synchronized false
        val name = by.trim()
        val key = recipientID?.toString()?.lowercase() ?: "name:$name"
        if (name.isEmpty() || key in mutableEntries[index].acknowledgementKeys.orEmpty()) return@synchronized true
        mutableEntries[index] = mutableEntries[index].copy(
            acknowledgedBy = mutableEntries[index].acknowledgedBy + name,
            acknowledgementKeys = mutableEntries[index].acknowledgementKeys.orEmpty() + key,
        )
        persist()
        true
    }

    fun voiceData(entry: CallHistoryEntry): ByteArray? = synchronized(FILE_LOCK) {
        entry.voiceFileName?.let { runCatching { File(directory, it).readBytes() }.getOrNull() }
    }

    fun clear() {
        synchronized(FILE_LOCK) {
            directory.listFiles().orEmpty().filter { it.extension == "m4a" }.forEach { it.delete() }
            mutableEntries.clear()
            persist()
        }
    }

    fun clear(spaceID: UUID) {
        synchronized(FILE_LOCK) {
            reloadFromDisk()
            mutableEntries.filter { it.spaceID == spaceID }.forEach(::removeVoiceFile)
            mutableEntries.removeAll { it.spaceID == spaceID }
            persist()
        }
    }

    private fun record(
        id: UUID,
        spaceID: UUID,
        kind: CallEvent.Kind,
        direction: CallHistoryEntry.Direction,
        counterpartName: String,
        date: Instant,
        voiceData: ByteArray?,
        intendedRecipientCount: Int? = null,
    ) {
        if (kind !in RECORDABLE_KINDS || mutableEntries.any { it.id == id }) return
        val playableVoice = voiceData?.takeIf { it.isNotEmpty() }
        val voiceFileName = if (kind == CallEvent.Kind.VoiceMessage && playableVoice != null) {
            "$id.m4a".takeIf { name ->
                runCatching {
                    directory.mkdirs()
                    val destination = File(directory, name)
                    val temporary = File(directory, "$name.tmp")
                    temporary.writeBytes(playableVoice)
                    if (!temporary.renameTo(destination)) {
                        temporary.copyTo(destination, overwrite = true)
                        temporary.delete()
                    }
                }.isSuccess
            }
        } else null
        mutableEntries.add(
            0,
            CallHistoryEntry(id, spaceID, kind, direction, counterpartName, date,
                acknowledgementKeys = emptyList(), intendedRecipientCount = intendedRecipientCount,
                voiceFileName = voiceFileName),
        )
        trimIfNeeded()
        persist()
    }

    private fun trimIfNeeded() {
        while (mutableEntries.size > maxEntries.coerceAtLeast(1)) {
            removeVoiceFile(mutableEntries.removeLast())
        }
        val keepVoiceCount = maxVoiceEntries.coerceAtLeast(0)
        mutableEntries.indices.filter { mutableEntries[it].voiceFileName != null }
            .drop(keepVoiceCount)
            .forEach { index ->
                removeVoiceFile(mutableEntries[index])
                mutableEntries[index] = mutableEntries[index].copy(voiceFileName = null)
            }
    }

    private fun removeMissingVoiceReferences() {
        mutableEntries.indices.forEach { index ->
            val fileName = mutableEntries[index].voiceFileName ?: return@forEach
            if (!File(directory, fileName).isFile) {
                mutableEntries[index] = mutableEntries[index].copy(voiceFileName = null)
            }
        }
    }

    private fun removeVoiceFile(entry: CallHistoryEntry) {
        entry.voiceFileName?.let { File(directory, it).delete() }
    }

    private fun removeOrphanedVoiceFiles() {
        val referenced = mutableEntries.mapNotNullTo(mutableSetOf()) { it.voiceFileName }
        directory.listFiles().orEmpty()
            .filter { it.extension == "m4a" && it.name !in referenced }
            .forEach { it.delete() }
    }

    private fun persist() {
        directory.mkdirs()
        val json = JSONArray().also { array -> mutableEntries.forEach { array.put(it.toJson()) } }.toString()
        val temporary = File(directory, "history.json.tmp")
        runCatching {
            temporary.writeText(json)
            if (!temporary.renameTo(indexFile)) {
                temporary.copyTo(indexFile, overwrite = true)
                temporary.delete()
            }
        }
    }

    private fun loadEntries(): List<CallHistoryEntry> = runCatching {
        if (!indexFile.isFile) return emptyList()
        val array = JSONArray(indexFile.readText())
        buildList {
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.toEntry()?.takeIf { it.kind in RECORDABLE_KINDS }?.let(::add)
            }
        }
    }.getOrDefault(emptyList())

    private fun reloadFromDisk() {
        mutableEntries.clear()
        mutableEntries.addAll(loadEntries())
    }

    private fun CallHistoryEntry.toJson() = JSONObject()
        .put("id", id.toString())
        .put("spaceID", spaceID.toString())
        .put("kind", kind.rawValue)
        .put("direction", direction.name)
        .put("counterpartName", counterpartName)
        .put("date", date.toString())
        .put("acknowledgedBy", JSONArray(acknowledgedBy))
        .put("acknowledgementKeys", acknowledgementKeys?.let(::JSONArray) ?: JSONObject.NULL)
        .put("intendedRecipientCount", intendedRecipientCount ?: JSONObject.NULL)
        .put("voiceFileName", voiceFileName ?: JSONObject.NULL)

    private fun JSONObject.toEntry(): CallHistoryEntry? = runCatching {
        val acknowledgements = optJSONArray("acknowledgedBy")
        val acknowledgementKeys = optJSONArray("acknowledgementKeys")
        CallHistoryEntry(
            id = UUID.fromString(getString("id")),
            spaceID = UUID.fromString(getString("spaceID")),
            kind = CallEvent.Kind.fromRawValue(getString("kind")) ?: error("Unknown call kind"),
            direction = CallHistoryEntry.Direction.valueOf(getString("direction")),
            counterpartName = getString("counterpartName"),
            date = Instant.parse(getString("date")),
            acknowledgedBy = buildList {
                if (acknowledgements != null) for (index in 0 until acknowledgements.length()) {
                    acknowledgements.optString(index).takeIf(String::isNotBlank)?.let(::add)
                }
            },
            acknowledgementKeys = acknowledgementKeys?.let { keys -> buildList {
                for (index in 0 until keys.length()) {
                    keys.optString(index).takeIf(String::isNotBlank)?.let(::add)
                }
            } },
            intendedRecipientCount = if (isNull("intendedRecipientCount")) null
                else optInt("intendedRecipientCount").coerceAtLeast(0),
            voiceFileName = if (isNull("voiceFileName")) null else getString("voiceFileName").takeIf { it.isNotBlank() },
        )
    }.getOrNull()

    companion object {
        const val MAX_ENTRIES = 20
        const val MAX_VOICE_ENTRIES = 10
        private val RECORDABLE_KINDS = setOf(
            CallEvent.Kind.QuietAlert,
            CallEvent.Kind.Siren,
            CallEvent.Kind.DingDong,
            CallEvent.Kind.VoiceMessage,
        )
        private val FILE_LOCK = Any()
    }
}
