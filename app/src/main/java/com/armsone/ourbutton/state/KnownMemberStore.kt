package com.armsone.ourbutton.state

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Space-scoped durable fallback for authenticated same-space senders.
 * Server rows overwrite matching deviceIDs, but a temporarily incomplete server roster cannot
 * erase a sender that this device has already authenticated with the shared space secret.
 */
class KnownMemberStore(context: Context) {
    private val preferences = context.getSharedPreferences("button_known_members", Context.MODE_PRIVATE)

    fun members(spaceID: UUID): List<PresenceUi> = decode(preferences.getString(spaceID.toString(), null))

    fun upsert(spaceID: UUID, member: PresenceUi) {
        if (member.isCurrentDevice || member.id == "current" || runCatching { UUID.fromString(member.id) }.isFailure) return
        val byID = members(spaceID).associateByTo(linkedMapOf()) { it.id }
        byID[member.id] = member.copy(
            name = visibleMemberName(member.name),
            isCurrentDevice = false,
            notificationMuteSyncStatus = com.armsone.ourbutton.push.NotificationMuteSyncStatus.SYNCED,
        )
        save(spaceID, byID.values.toList())
    }

    fun reconcile(spaceID: UUID, authoritative: List<PresenceUi>): List<PresenceUi> {
        val result = reconcileKnownMembers(members(spaceID), authoritative)
        save(spaceID, result)
        return result
    }

    fun clear(spaceID: UUID) = preferences.edit().remove(spaceID.toString()).apply()

    private fun save(spaceID: UUID, members: List<PresenceUi>) {
        val array = JSONArray().apply {
            members.forEach { member -> put(JSONObject()
                .put("deviceID", member.id)
                .put("name", visibleMemberName(member.name))
                .put("role", member.role?.name ?: JSONObject.NULL)
                .put("notificationsMuted", member.notificationsMuted))
            }
        }
        preferences.edit().putString(spaceID.toString(), array.toString()).apply()
    }

    private fun decode(raw: String?): List<PresenceUi> = runCatching {
        if (raw.isNullOrBlank()) return emptyList()
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) {
                val json = array.optJSONObject(index) ?: continue
                val id = json.optString("deviceID")
                if (runCatching { UUID.fromString(id) }.isFailure) continue
                add(PresenceUi(
                    id = id,
                    name = visibleMemberName(json.optString("name")),
                    role = json.optString("role").takeIf(String::isNotBlank)
                        ?.let { runCatching { AppRole.valueOf(it) }.getOrNull() },
                    isCurrentDevice = false,
                    notificationsMuted = json.optBoolean("notificationsMuted", false),
                ))
            }
        }
    }.getOrDefault(emptyList())
}
