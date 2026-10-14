package com.armsone.ourbutton.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.armsone.ourbutton.MainActivity
import com.armsone.ourbutton.R
import com.armsone.ourbutton.state.SavedRoomUi
import com.armsone.ourbutton.state.roleLabel

class WidgetConfigurationActivity : ComponentActivity() {
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Set the result to CANCELED. If the user backs out before finishing, the widget is not added.
        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        setResult(
            Activity.RESULT_CANCELED,
            Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
        )

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        if (!isAppWidgetIdBoundToOwnProvider(appWidgetId)) {
            finish()
            return
        }

        val rooms = WidgetRoomStore.getSavedRooms(this)
        val currentSelectedId = WidgetRoomStore.getRoomId(this, appWidgetId)

        setContent {
            WidgetConfigurationScreen(
                rooms = rooms,
                currentSelectedId = currentSelectedId,
                onRoomSelected = { room ->
                    selectRoomAndFinish(room)
                },
                onOpenApp = {
                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                },
            )
        }
    }

    private fun isAppWidgetIdBoundToOwnProvider(appWidgetId: Int): Boolean {
        val appWidgetManager = AppWidgetManager.getInstance(this) ?: return false
        val providerInfo = appWidgetManager.getAppWidgetInfo(appWidgetId) ?: return false
        val provider = providerInfo.provider ?: return false
        val ownProviders = listOf(
            ComponentName(this, ParentButtonWidgetProvider::class.java),
            ComponentName(this, ChildButtonWidgetProvider::class.java),
        )
        return provider in ownProviders
    }

    private fun selectRoomAndFinish(room: SavedRoomUi) {
        WidgetRoomStore.saveRoom(
            context = this,
            appWidgetId = appWidgetId,
            spaceId = room.invite.spaceId,
            spaceName = room.invite.spaceName,
        )

        val appWidgetManager = AppWidgetManager.getInstance(this)
        if (appWidgetManager != null) {
            updateWidgetById(this, appWidgetManager, appWidgetId)
        }

        val resultValue = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        setResult(Activity.RESULT_OK, resultValue)
        finish()
    }
}

private val GroupedBackground = Color(0xFFF7F1E8)
private val CardBackground = Color(0xFFFFFCF7)
private val Charcoal = Color(0xFF302E2B)
private val Secondary = Color(0xFF6E665E)
private val Accent = Color(0xFF526D80)
private val BrandRed = Color(0xFFE41E25)

@Composable
private fun WidgetConfigurationScreen(
    rooms: List<SavedRoomUi>,
    currentSelectedId: String?,
    onRoomSelected: (SavedRoomUi) -> Unit,
    onOpenApp: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = GroupedBackground,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 24.dp),
        ) {
            Text(
                text = "위젯 공간 설정",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Charcoal,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "이 위젯을 탭했을 때 호출할 가족 공간을 선택하세요.",
                fontSize = 14.sp,
                color = Secondary,
            )
            Spacer(Modifier.height(24.dp))

            if (rooms.isEmpty()) {
                EmptyRoomsView(onOpenApp = onOpenApp)
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(rooms, key = { it.invite.spaceId }) { room ->
                        val isSelected = room.invite.spaceId == currentSelectedId
                        RoomItemCard(
                            room = room,
                            isSelected = isSelected,
                            onClick = { onRoomSelected(room) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RoomItemCard(
    room: SavedRoomUi,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 6.dp,
                shape = shape,
                ambientColor = Charcoal.copy(alpha = 0.08f),
                spotColor = Charcoal.copy(alpha = 0.10f),
            )
            .background(
                brush = Brush.verticalGradient(listOf(Color.White, CardBackground)),
                shape = shape,
            )
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) BrandRed else Accent.copy(alpha = 0.35f),
                shape = shape,
            )
            .clickable(onClick = onClick)
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.width(40.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.Groups,
                contentDescription = null,
                tint = if (isSelected) BrandRed else Accent,
                modifier = Modifier.size(28.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = room.invite.spaceName,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = Charcoal,
            )
            val subtitle = buildString {
                if (room.displayName.isNotBlank()) {
                    append(room.displayName)
                    append(" · ")
                }
                append(roleLabel(room.role))
            }
            Text(
                text = subtitle,
                fontSize = 13.sp,
                color = Secondary,
            )
        }
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = Secondary.copy(alpha = 0.45f),
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun EmptyRoomsView(onOpenApp: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 4.dp,
                shape = shape,
                ambientColor = Charcoal.copy(alpha = 0.06f),
                spotColor = Charcoal.copy(alpha = 0.08f),
            )
            .background(Color.White, shape)
            .border(1.dp, Accent.copy(alpha = 0.25f), shape)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "저장된 가족 공간이 없어요.",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = Charcoal,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "OurButton 앱에서 먼저 가족 공간을 만들거나 참여해 주세요.",
            fontSize = 13.sp,
            color = Secondary,
        )
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onOpenApp,
            colors = ButtonDefaults.buttonColors(containerColor = BrandRed),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(
                text = "OurButton 앱 열기",
                color = Color.White,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}
