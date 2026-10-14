package com.armsone.ourbutton.widget

import android.content.Context
import android.content.SharedPreferences
import com.armsone.ourbutton.state.AppStateCoder
import com.armsone.ourbutton.state.SavedRoomUi

/**
 * Persists and resolves the target family room for each widget instance (appWidgetId).
 */
object WidgetRoomStore {
    private const val PREFS_NAME = "button_widget_prefs"
    private const val KEY_PREFIX_ROOM_ID = "widget_room_id_"
    private const val KEY_PREFIX_ROOM_NAME = "widget_room_name_"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveRoom(context: Context, appWidgetId: Int, spaceId: String, spaceName: String) {
        prefs(context).edit()
            .putString(KEY_PREFIX_ROOM_ID + appWidgetId, spaceId)
            .putString(KEY_PREFIX_ROOM_NAME + appWidgetId, spaceName)
            .apply()
    }

    fun getRoomId(context: Context, appWidgetId: Int): String? =
        prefs(context).getString(KEY_PREFIX_ROOM_ID + appWidgetId, null)

    fun getRoomName(context: Context, appWidgetId: Int): String? =
        prefs(context).getString(KEY_PREFIX_ROOM_NAME + appWidgetId, null)

    fun removeWidget(context: Context, appWidgetId: Int) {
        prefs(context).edit()
            .remove(KEY_PREFIX_ROOM_ID + appWidgetId)
            .remove(KEY_PREFIX_ROOM_NAME + appWidgetId)
            .apply()
    }

    fun resolveRoom(context: Context, appWidgetId: Int): SavedRoomUi? {
        val state = AppStateCoder.decode(
            context.getSharedPreferences("button_state", Context.MODE_PRIVATE).getString("state", null),
        )
        val targetId = getRoomId(context, appWidgetId)
        if (!targetId.isNullOrBlank()) {
            val found = state.rooms.firstOrNull { it.invite.spaceId == targetId }
            if (found != null) return found
        }
        return null
    }

    fun getSavedRooms(context: Context): List<SavedRoomUi> {
        val state = AppStateCoder.decode(
            context.getSharedPreferences("button_state", Context.MODE_PRIVATE).getString("state", null),
        )
        return state.rooms
    }
}
