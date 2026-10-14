package com.armsone.ourbutton.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.RemoteViews
import android.widget.Toast
import com.armsone.ourbutton.MainActivity
import com.armsone.ourbutton.R
import com.armsone.ourbutton.data.BackendConfiguration
import com.armsone.ourbutton.data.BackendException
import com.armsone.ourbutton.data.CallHistoryStore
import com.armsone.ourbutton.data.HttpBackendClient
import com.armsone.ourbutton.data.OutboundEventStore
import com.armsone.ourbutton.model.CallEvent
import com.armsone.ourbutton.model.FamilyRole
import com.armsone.ourbutton.model.FamilySpace
import com.armsone.ourbutton.platform.DingDongPlayer
import com.armsone.ourbutton.push.DeviceIdentity
import com.armsone.ourbutton.state.AppRole
import com.armsone.ourbutton.state.AppStateCoder
import com.armsone.ourbutton.state.SavedRoomUi
import com.armsone.ourbutton.state.SendCooldown
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

const val ACTION_DING_DONG = "com.armsone.ourbutton.action.WIDGET_DING_DONG"
const val EXTRA_SPACE_ID = "button_widget_space_id"
const val EXTRA_IS_CHILD = "button_widget_is_child"

private fun widgetActivityIntent(
    context: Context,
    action: String,
    requestCode: Int,
    spaceId: String?,
): PendingIntent =
    PendingIntent.getActivity(
        context,
        requestCode,
        Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_WIDGET_ACTION, action)
            if (!spaceId.isNullOrBlank()) {
                putExtra(MainActivity.EXTRA_WIDGET_SPACE_ID, spaceId)
            }
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

private fun bindActions(
    context: Context,
    views: RemoteViews,
    appWidgetId: Int,
    room: SavedRoomUi,
    isChild: Boolean,
) {
    val spaceId = room.invite.spaceId
    val providerClass = if (isChild) ChildButtonWidgetProvider::class.java else ParentButtonWidgetProvider::class.java

    // widget_quiet: retains existing meaning (opens MainActivity to send quiet tap)
    views.setOnClickPendingIntent(
        R.id.widget_quiet,
        widgetActivityIntent(context, "quiet", appWidgetId * 10 + 1, spaceId),
    )

    // widget_ding: sends DingDong event directly without presenting app as primary action
    val dingIntent = Intent(context, providerClass).apply {
        action = ACTION_DING_DONG
        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        putExtra(EXTRA_SPACE_ID, spaceId)
        putExtra(EXTRA_IS_CHILD, isChild)
    }
    val dingPendingIntent = PendingIntent.getBroadcast(
        context,
        appWidgetId * 10 + 2,
        dingIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    views.setOnClickPendingIntent(R.id.widget_ding, dingPendingIntent)

    // widget_voice: retains existing meaning (opens voice modal in MainActivity)
    views.setOnClickPendingIntent(
        R.id.widget_voice,
        widgetActivityIntent(context, "voice", appWidgetId * 10 + 3, spaceId),
    )

    // widget_open: retains existing meaning (opens MainActivity)
    views.setOnClickPendingIntent(
        R.id.widget_open,
        widgetActivityIntent(context, "open", appWidgetId * 10 + 4, spaceId),
    )
}

fun updateWidget(
    context: Context,
    manager: AppWidgetManager,
    appWidgetId: Int,
    isChild: Boolean,
) {
    val layoutId = if (isChild) R.layout.widget_child else R.layout.widget_parent
    val views = RemoteViews(context.packageName, layoutId)
    val savedRooms = WidgetRoomStore.getSavedRooms(context)
    val room = WidgetRoomStore.resolveRoom(context, appWidgetId)
        ?: if (savedRooms.size == 1) {
            val single = savedRooms.first()
            WidgetRoomStore.saveRoom(context, appWidgetId, single.invite.spaceId, single.invite.spaceName)
            single
        } else null

    if (room != null) {
        // Configured state: show room title, buttons, and bind actions
        views.setViewVisibility(R.id.widget_room_name, View.VISIBLE)
        views.setTextViewText(R.id.widget_room_name, room.invite.spaceName)
        views.setViewVisibility(R.id.widget_button_container, View.VISIBLE)
        views.setViewVisibility(R.id.widget_unconfigured_container, View.GONE)

        bindActions(context, views, appWidgetId, room, isChild)
    } else {
        // Unconfigured or no-rooms state: safe prompt explaining how to choose a room
        views.setViewVisibility(R.id.widget_room_name, View.GONE)
        views.setViewVisibility(R.id.widget_button_container, View.GONE)
        views.setViewVisibility(R.id.widget_unconfigured_container, View.VISIBLE)

        if (savedRooms.isEmpty()) {
            views.setTextViewText(R.id.widget_unconfigured_title, context.getString(R.string.widget_no_rooms_title))
            views.setTextViewText(R.id.widget_unconfigured_message, context.getString(R.string.widget_no_rooms_message))
            val openAppIntent = PendingIntent.getActivity(
                context,
                appWidgetId * 10 + 9,
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_unconfigured_container, openAppIntent)
        } else {
            views.setTextViewText(R.id.widget_unconfigured_title, context.getString(R.string.widget_unconfigured_title))
            views.setTextViewText(R.id.widget_unconfigured_message, context.getString(R.string.widget_unconfigured_message))
            val configIntent = PendingIntent.getActivity(
                context,
                appWidgetId * 10 + 8,
                Intent(context, WidgetConfigurationActivity::class.java).apply {
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_unconfigured_container, configIntent)
        }
    }

    manager.updateAppWidget(appWidgetId, views)
}

fun updateWidgetById(context: Context, manager: AppWidgetManager, appWidgetId: Int) {
    val info = runCatching { manager.getAppWidgetInfo(appWidgetId) }.getOrNull()
    val isChild = info?.provider?.className == ChildButtonWidgetProvider::class.java.name
    updateWidget(context, manager, appWidgetId, isChild)
}

fun updateAllWidgets(context: Context) {
    val manager = AppWidgetManager.getInstance(context) ?: return
    val parentComponent = ComponentName(context, ParentButtonWidgetProvider::class.java)
    val parentIds = runCatching { manager.getAppWidgetIds(parentComponent) }.getOrNull() ?: IntArray(0)
    for (id in parentIds) {
        updateWidget(context, manager, id, isChild = false)
    }
    val childComponent = ComponentName(context, ChildButtonWidgetProvider::class.java)
    val childIds = runCatching { manager.getAppWidgetIds(childComponent) }.getOrNull() ?: IntArray(0)
    for (id in childIds) {
        updateWidget(context, manager, id, isChild = true)
    }
}

private fun dispatchDingDong(
    context: Context,
    appWidgetId: Int,
    targetSpaceId: String?,
    isChild: Boolean,
    pendingResult: BroadcastReceiver.PendingResult,
) {
    val state = AppStateCoder.decode(
        context.getSharedPreferences("button_state", Context.MODE_PRIVATE).getString("state", null),
    )
    val room = (if (!targetSpaceId.isNullOrBlank()) {
        state.rooms.firstOrNull { it.invite.spaceId == targetSpaceId }
    } else null) ?: WidgetRoomStore.resolveRoom(context, appWidgetId)
    ?: (if (state.rooms.size == 1) state.rooms.first() else null)

    if (room == null) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, "호출할 공간을 선택해 주세요.", Toast.LENGTH_SHORT).show()
        }
        val manager = AppWidgetManager.getInstance(context)
        if (manager != null && appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            updateWidget(context, manager, appWidgetId, isChild)
        }
        pendingResult.finish()
        return
    }

    val childRole = isChild || room.role == AppRole.CHILD
    if (childRole) {
        if (!SendCooldown.shared.beginIfAvailable()) {
            val remaining = SendCooldown.shared.remainingSeconds()
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "잠시만요. ${remaining}초 뒤에 다시 보낼 수 있어요.", Toast.LENGTH_SHORT).show()
            }
            pendingResult.finish()
            return
        }
    }

    val space = runCatching {
        FamilySpace(
            UUID.fromString(room.invite.spaceId),
            room.invite.spaceName,
            room.invite.secret,
        )
    }.getOrNull()
    if (space == null) {
        pendingResult.finish()
        return
    }

    val role = when (room.role) {
        AppRole.PARENT -> FamilyRole.Parent
        AppRole.CHILD -> FamilyRole.Child
        AppRole.GENERAL -> FamilyRole.General
        null -> if (isChild) FamilyRole.Child else FamilyRole.Parent
    }
    val deviceID = DeviceIdentity.loadOrCreate(context)
    val event = CallEvent(
        kind = CallEvent.Kind.DingDong,
        spaceID = space.id,
        senderName = room.displayName.ifBlank { "가족" },
        senderID = deviceID,
        senderRole = role,
    )

    // Local chime sound enhancement
    runCatching {
        DingDongPlayer().play()
    }

    // Direct toast feedback to the user without presenting MainActivity
    Handler(Looper.getMainLooper()).post {
        Toast.makeText(context, "‘${space.name}’에 띵동을 보냈어요.", Toast.LENGTH_SHORT).show()
    }

    // Persist to outbound queue and history store
    val outboundStore = OutboundEventStore(context)
    outboundStore.put(event)
    val historyStore = CallHistoryStore(context)
    historyStore.recordSent(event, targetName = null)

    val configuration = BackendConfiguration.load(context)
    if (!configuration.isConfigured) {
        pendingResult.finish()
        return
    }

    CoroutineScope(Dispatchers.IO).launch {
        try {
            val backend = HttpBackendClient(configuration)
            backend.send(event, space.secret)
            outboundStore.remove(event.id)
        } catch (e: Exception) {
            if (e is BackendException.NoRecipients) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, "현재 전달할 수 있는 가족 기기가 없어요.", Toast.LENGTH_SHORT).show()
                }
            }
        } finally {
            pendingResult.finish()
        }
    }
}

abstract class BaseButtonWidgetProvider(protected val isChildProvider: Boolean) : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { id ->
            updateWidget(context, manager, id, isChild = isChildProvider)
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { id ->
            WidgetRoomStore.removeWidget(context, id)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_DING_DONG) {
            val appWidgetId = intent.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID,
            )
            val spaceId = intent.getStringExtra(EXTRA_SPACE_ID)
            val isChild = intent.getBooleanExtra(EXTRA_IS_CHILD, isChildProvider)
            val pendingResult = goAsync()
            dispatchDingDong(context, appWidgetId, spaceId, isChild, pendingResult)
        } else {
            super.onReceive(context, intent)
        }
    }
}

class ParentButtonWidgetProvider : BaseButtonWidgetProvider(isChildProvider = false)

class ChildButtonWidgetProvider : BaseButtonWidgetProvider(isChildProvider = true)
