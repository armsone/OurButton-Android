package com.armsone.ourbutton.data

import android.content.Context
import com.armsone.ourbutton.model.CallEvent
import com.armsone.ourbutton.model.CallEventCoder
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Durable idempotent outbox. Events keep their original eventID across relaunch retries. */
class OutboundEventStore(context: Context) {
    private val directory = File(context.noBackupFilesDir, "ButtonOutbox")
    private val index = File(directory, "outbox.json")

    fun put(event: CallEvent) = synchronized(LOCK) {
        val items = load().associateByTo(linkedMapOf()) { it.id }
        items[event.id] = event
        save(items.values.toList())
    }

    fun remove(eventID: UUID) = synchronized(LOCK) {
        save(load().filterNot { it.id == eventID })
    }

    fun events(spaceID: UUID? = null): List<CallEvent> = synchronized(LOCK) {
        load().filter { spaceID == null || it.spaceID == spaceID }
    }

    private fun load(): List<CallEvent> = runCatching {
        if (!index.isFile) return emptyList()
        val array = JSONArray(index.readText())
        buildList {
            for (position in 0 until array.length()) {
                array.optJSONObject(position)?.let { json ->
                    runCatching { CallEventCoder.decode(json.toString().toByteArray()) }.getOrNull()?.let(::add)
                }
            }
        }
    }.getOrDefault(emptyList())

    private fun save(events: List<CallEvent>) {
        directory.mkdirs()
        val array = JSONArray().apply {
            events.forEach { event -> put(JSONObject(String(CallEventCoder.encode(event)))) }
        }
        val temporary = File(directory, "outbox.json.tmp")
        temporary.writeText(array.toString())
        if (!temporary.renameTo(index)) {
            temporary.copyTo(index, overwrite = true)
            temporary.delete()
        }
    }

    private companion object { val LOCK = Any() }
}
