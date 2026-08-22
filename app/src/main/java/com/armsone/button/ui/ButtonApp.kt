@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.armsone.button.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.filled.ArrowCircleDown
import androidx.compose.material.icons.filled.ArrowCircleUp
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SupervisorAccount
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Warning
import com.armsone.button.data.CallHistoryEntry
import com.armsone.button.model.CallEvent
import com.armsone.button.state.AppPhase
import com.armsone.button.state.AppRole
import com.armsone.button.state.AppRoute
import com.armsone.button.state.AppUiState
import com.armsone.button.state.AppViewModel
import com.armsone.button.state.CallActivityKind
import com.armsone.button.state.CallActivityUi
import com.armsone.button.state.IncomingKind
import com.armsone.button.state.IncomingUi
import com.armsone.button.state.InviteUi
import com.armsone.button.state.PresenceUi
import com.armsone.button.state.TransportUiStatus
import com.armsone.button.state.VoiceState
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private val Accent = Color(0xFF4B76A3)
private val Grouped = Color(0xFFF2F2F7)
private val Secondary = Color(0xFF6D6D72)
private val Green = Color(0xFF34C759)
private val Orange = Color(0xFFFF9500)
private val Purple = Color(0xFFAF52DE)
private val Red = Color(0xFFFF3B30)

@Composable
fun ButtonApp(model: AppViewModel) {
    val state by model.uiState.collectAsState()
    MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(primary = Accent)) {
        Box(Modifier.fillMaxSize()) {
            CalmBackground()
            AppScaffold(title = titleFor(state), subtitle = homeStatusFor(state), leading = {
                when {
                    state.route != AppRoute.WELCOME -> ToolbarButton(Icons.Default.ArrowBackIosNew, "뒤로", "nav_back") { model.back() }
                    state.phase == AppPhase.HOME && state.role == AppRole.PARENT ->
                        ToolbarButton(Icons.Default.QrCode2, "가족 초대 QR 보기", "open_invite") { model.showInvite(true) }
                }
            }, trailing = {
                if (state.phase == AppPhase.HOME && state.route == AppRoute.WELCOME) {
                    ToolbarButton(Icons.Default.Settings, "설정", "open_settings") { model.navigate(AppRoute.SETTINGS) }
                }
            }) {
                when {
                    state.route == AppRoute.CREATE_SPACE -> CreateSpaceScreen(model)
                    state.route == AppRoute.JOIN_SPACE -> JoinSpaceScreen(state, model)
                    state.route == AppRoute.SETTINGS -> SettingsScreen(state, model)
                    state.phase == AppPhase.SETUP -> WelcomeScreen(model)
                    state.phase == AppPhase.ROLE_SELECTION -> RoleSelectionScreen(model)
                    state.role == AppRole.PARENT -> ParentHomeScreen(state, model)
                    else -> ChildHomeScreen(state, model)
                }
            }
            if (state.showInvite) InviteDialog(state.invite, model)
            if (state.showVoice) VoiceDialog(state.voiceState, state.sendCooldownRemainingSeconds, model)
            if (state.voiceState == VoiceState.READY) VoiceConfirmationDialog(state, model)
            state.incoming?.let { IncomingDialog(it, model) }
            state.errorMessage?.let { GlobalErrorDialog(it, model) }
        }
    }
}

private fun titleFor(state: AppUiState): String = when {
    state.route == AppRoute.CREATE_SPACE -> "새 가족 공간"
    state.route == AppRoute.JOIN_SPACE -> "초대로 참여"
    state.route == AppRoute.SETTINGS -> "설정"
    state.phase == AppPhase.SETUP -> "시작하기"
    state.phase == AppPhase.ROLE_SELECTION -> "역할 선택"
    else -> state.spaceName.ifBlank { "버튼" }
}

private fun homeStatusFor(state: AppUiState): String? {
    if (state.phase != AppPhase.HOME || state.route != AppRoute.WELCOME) return null
    if (state.role == AppRole.PARENT && state.voiceState == VoiceState.RECORDING) return "녹음 중…"
    return state.quietHoldRemainingSeconds.takeIf { it > 0 }?.let { "사이렌까지 ${it}초" }
}

@Composable
private fun CalmBackground() {
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Grouped, Accent.copy(alpha = .08f)))))
}

@Composable
private fun AppScaffold(
    title: String,
    subtitle: String?,
    leading: @Composable () -> Unit,
    trailing: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(64.dp), contentAlignment = Alignment.CenterStart) { leading() }
            Column(
                modifier = Modifier.weight(1f).testTag("screen_title"),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center, maxLines = 1)
                Text(
                    subtitle ?: " ",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Red,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.height(16.dp).testTag("home_status"),
                )
            }
            Box(Modifier.width(64.dp), contentAlignment = Alignment.CenterEnd) { trailing() }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
        val context = LocalContext.current
        val packageInfo = remember {
            runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        }
        Text("버전 ${packageInfo?.versionName ?: "-"} · 빌드 ${packageInfo?.longVersionCode ?: "-"}",
            fontSize = 11.sp, color = Secondary, fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().background(Color.White.copy(alpha = .72f)).padding(vertical = 5.dp)
                .semantics { contentDescription = "버전 ${packageInfo?.versionName ?: "-"}, 빌드 ${packageInfo?.longVersionCode ?: "-"}" })
    }
}

@Composable
private fun ToolbarButton(icon: ImageVector, label: String, tag: String, action: () -> Unit) {
    Icon(icon, contentDescription = label, tint = Accent,
        modifier = Modifier.size(44.dp).clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = label, onClick = action)
            .padding(10.dp).testTag(tag))
}

@Composable
private fun AdaptiveContent(tag: String, content: @Composable ColumnScope.() -> Unit) {
    LazyColumn(Modifier.fillMaxSize().testTag(tag), horizontalAlignment = Alignment.CenterHorizontally) {
        item {
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(24.dp), content = content)
        }
    }
}

@Composable
private fun WelcomeScreen(model: AppViewModel) = AdaptiveContent("setup_welcome") {
    Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BellGlyph(56.dp, Accent, wideWaves = true)
        Text("버튼", fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Text("멀리 있는 가족을 부드럽게 부르는\n우리 집 초인종이에요.", fontSize = 17.sp,
            color = Secondary, textAlign = TextAlign.Center, lineHeight = 22.sp)
    }
    Spacer(Modifier.height(28.dp))
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SetupRow("새 가족 공간 만들기", "첫 기기에서 공간을 만들고 QR로 초대해요", Icons.Default.AddCircle, "setup_create") {
            model.navigate(AppRoute.CREATE_SPACE)
        }
        SetupRow("초대 QR로 참여하기", "가족 기기에 표시된 QR 코드를 스캔해요", Icons.Default.QrCodeScanner, "setup_join") {
            model.navigate(AppRoute.JOIN_SPACE)
        }
    }
}

@Composable
private fun SetupRow(title: String, subtitle: String, icon: ImageVector, tag: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().shadow(8.dp, RoundedCornerShape(20.dp), ambientColor = Color.Black.copy(.05f),
        spotColor = Color.Black.copy(.05f)).background(Color.White, RoundedCornerShape(20.dp))
        .clickable(onClick = onClick).testTag(tag).padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(44.dp), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = Accent, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, fontSize = 13.sp, color = Secondary)
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Secondary.copy(.45f), modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun CreateSpaceScreen(model: AppViewModel) {
    var space by remember { mutableStateOf("우리 가족") }
    var member by remember { mutableStateOf("") }
    AdaptiveContent("setup_create_screen") {
        Spacer(Modifier.height(20.dp))
        FieldGroup("가족 공간 이름", "예: 우리 가족", space, { space = it }, "space_name")
        Spacer(Modifier.height(24.dp))
        FieldGroup("내 이름 (호출할 때 표시돼요)", "예: 엄마", member, { member = it }, "member_name")
        Spacer(Modifier.height(24.dp))
        ProminentButton("공간 만들기", enabled = space.isNotBlank() && member.isNotBlank(), tag = "create_space") {
            model.createSpace(space, member)
        }
        if (member.isBlank()) {
            Spacer(Modifier.height(12.dp))
            Text("ⓘ  내 이름을 입력하면 공간을 만들 수 있어요.", fontSize = 13.sp, color = Secondary)
        }
        Spacer(Modifier.height(24.dp))
        Text("공간을 만든 뒤 홈 화면에서 초대 QR을 보여 줄 수 있어요.", fontSize = 13.sp,
            color = Secondary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun FieldGroup(label: String, hint: String, value: String, onValue: (String) -> Unit, tag: String) {
    Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(value, onValue, placeholder = { Text(hint) }, singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag(tag), shape = RoundedCornerShape(6.dp))
}

@Composable
private fun JoinSpaceScreen(state: AppUiState, model: AppViewModel) {
    val fixtureInvite = remember(state.fixtureId) {
        if (state.fixtureId == "setup_join_confirmed") {
            InviteUi("12345678-1234-1234-1234-123456789abc", "우리 가족", "0123456789abcdef0123456789abcdef")
        } else null
    }
    var manual by remember(state.fixtureId) { mutableStateOf(if (state.fixtureId == "setup_join_invalid") "not-an-invite" else "") }
    var member by remember { mutableStateOf("") }
    var invite by remember(state.fixtureId) { mutableStateOf(fixtureInvite) }
    var error by remember(state.fixtureId) {
        mutableStateOf(if (state.fixtureId == "setup_join_invalid") "버튼 앱의 초대 코드가 아니에요." else null)
    }
    AdaptiveContent("setup_join_screen") {
        Spacer(Modifier.height(12.dp))
        if (invite == null) {
            if (state.fixtureId != null) {
                Box(Modifier.fillMaxWidth().height(300.dp).background(Color.Black, RoundedCornerShape(20.dp))
                    .testTag("qr_scanner_fixture"), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.CameraAlt, contentDescription = null, tint = Color.White, modifier = Modifier.size(40.dp))
                        Text("가족 초대 QR 스캐너", color = Color.White, textAlign = TextAlign.Center)
                    }
                }
            } else {
                QrScannerPreview(onCode = { raw ->
                    model.parseInvite(raw).onSuccess { invite = it; error = null }
                        .onFailure { error = it.message ?: "초대 코드를 읽지 못했어요." }
                }, modifier = Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(20.dp))
                    .testTag("qr_scanner").semantics { contentDescription = "가족 초대 QR 스캐너" })
            }
            Spacer(Modifier.height(24.dp))
            Text("가족 기기의 초대 QR 코드를 화면에 맞춰 주세요.", fontSize = 13.sp, color = Secondary,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(24.dp))
            Text("초대 링크 직접 입력", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(manual, { manual = it }, placeholder = { Text("buttonapp://invite/v1?...") },
                singleLine = true, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None,
                    keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth().testTag("manual_invite"))
            Spacer(Modifier.height(8.dp))
            OutlinedButton(enabled = manual.isNotBlank(), onClick = {
                model.parseInvite(manual).onSuccess { invite = it; error = null }
                    .onFailure { error = it.message ?: "초대 코드를 읽지 못했어요." }
            }, modifier = Modifier.testTag("confirm_invite")) { Text("확인") }
            error?.let { Text("⚠  $it", fontSize = 13.sp, color = Red, modifier = Modifier.padding(top = 18.dp)) }
        } else {
            Column(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(20.dp)).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Green, modifier = Modifier.size(44.dp))
                Text("“${invite!!.spaceName}” 공간 초대를 확인했어요.", fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(24.dp))
            FieldGroup("내 이름 (호출할 때 표시돼요)", "예: 첫째", member, { member = it }, "join_member_name")
            Spacer(Modifier.height(24.dp))
            ProminentButton("참여하기", member.isNotBlank(), "join_space") { model.join(invite!!, member) }
            TextButton(onClick = { invite = null; manual = "" }, modifier = Modifier.align(Alignment.CenterHorizontally)
                .testTag("scan_other_invite")) { Text("다른 초대 스캔하기", fontSize = 13.sp) }
        }
    }
}

@Composable
private fun RoleSelectionScreen(model: AppViewModel) = AdaptiveContent("role_selection") {
    Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("이 기기의 역할을 골라 주세요", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("나중에 설정에서 바꿀 수 있어요.", fontSize = 13.sp, color = Secondary)
    }
    Spacer(Modifier.height(28.dp))
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    if (wide) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        RoleCard(AppRole.PARENT, Accent, model, Modifier.weight(1f))
        RoleCard(AppRole.CHILD, Orange, model, Modifier.weight(1f))
    } else Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        RoleCard(AppRole.PARENT, Accent, model)
        RoleCard(AppRole.CHILD, Orange, model)
    }
}

@Composable
private fun RoleCard(role: AppRole, color: Color, model: AppViewModel, modifier: Modifier = Modifier) {
    val parent = role == AppRole.PARENT
    Column(modifier.fillMaxWidth().heightIn(min = 116.dp).shadow(6.dp, RoundedCornerShape(18.dp),
        ambientColor = Color.Black.copy(.05f), spotColor = Color.Black.copy(.05f))
        .background(Color.White, RoundedCornerShape(18.dp)).border(1.dp, color.copy(.25f), RoundedCornerShape(18.dp))
        .clickable { model.selectRole(role) }.testTag(if (parent) "role_parent" else "role_child").padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(if (parent) Icons.Default.SupervisorAccount else Icons.Default.ChildCare,
            contentDescription = null, tint = color, modifier = Modifier.size(32.dp))
        Spacer(Modifier.height(10.dp))
        Text(if (parent) "부모" else "자녀", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(3.dp))
        Text("음성과 호출을 주고받아요",
            fontSize = 12.sp, color = Secondary, textAlign = TextAlign.Center)
    }
}

@Composable
private fun ParentHomeScreen(state: AppUiState, model: AppViewModel) = AdaptiveContent("parent_home") {
    val voiceTitle = when (state.voiceState) {
        VoiceState.REQUESTING_PERMISSION -> "권한 확인 중…"
        VoiceState.RECORDING -> "녹음 중…"
        VoiceState.DENIED -> "마이크 설정 필요"
        VoiceState.READY -> "전송 확인"
        VoiceState.SENT -> "전송했어요"
        VoiceState.IDLE -> "음성"
    }
    val voiceIcon = when (state.voiceState) {
        VoiceState.RECORDING -> Icons.Default.GraphicEq
        VoiceState.DENIED -> Icons.Default.MicOff
        VoiceState.SENT -> Icons.Default.CheckCircle
        else -> Icons.Default.Mic
    }
    val voiceColor = when (state.voiceState) {
        VoiceState.RECORDING -> Red
        VoiceState.DENIED -> Color.Gray
        VoiceState.SENT -> Green
        else -> Orange
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HomeAction("톡톡", Icons.Default.TouchApp, Secondary, "quiet_alert",
            hint = "탭하면 소리 없이 알리고, 5초간 누르면 상대 기기에 사이렌을 보내요", modifier = Modifier.weight(1f),
            cooldownSeconds = 0,
            onTap = model::sendQuietTap, onPressStart = model::beginQuietHold, onPressEnd = model::endQuietHold)
        HomeAction("띵동", Icons.Default.NotificationsActive, Accent, "dingdong", "띵동 소리와 함께 알림 화면을 상대 기기에 보여요",
            Modifier.weight(1f), 0, model::sendDingDong)
        HomeAction(voiceTitle, voiceIcon, voiceColor, "voice", "누르고 있는 동안 최대 15초 녹음하고 손을 떼면 보낼지 확인해요",
            Modifier.weight(1f), 0,
            onTap = model::recordAccessibleVoice,
            onPressStart = model::beginVoiceHold,
            onPressEnd = model::endVoiceHold,
            tapAfterPress = false)
    }
    Spacer(Modifier.height(18.dp))
    PresenceCard(state, model::toggleRecipient)
    state.callActivity?.let {
        Spacer(Modifier.height(18.dp))
        CallActivityBanner(it, model::clearCallActivity)
    }
    Spacer(Modifier.height(18.dp))
    CallHistoryList(state.callHistory, model::replayVoice)
    if (state.isDemoMode) {
        Spacer(Modifier.height(18.dp))
        Text("데모 모드: 보낸 호출이 이 기기로 다시 전달돼요.", fontSize = 12.sp, color = Purple)
    }
}

@Composable
private fun ChildHomeScreen(state: AppUiState, model: AppViewModel) = AdaptiveContent("child_home") {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HomeAction("톡톡", Icons.Default.TouchApp, Secondary, "quiet_alert",
            "탭하면 소리 없이 알리고, 5초간 누르면 부모와 가족 기기에 사이렌을 보내요", Modifier.weight(1f),
            state.sendCooldownRemainingSeconds, model::sendQuietTap,
            model::beginQuietHold, model::endQuietHold)
        HomeAction("띵동", Icons.Default.NotificationsActive, Accent, "dingdong", "띵동 소리와 함께 부모와 가족에게 호출을 보내요",
            Modifier.weight(1f), state.sendCooldownRemainingSeconds, model::sendDingDong)
        HomeAction("음성", Icons.Default.Mic, Orange, "voice", "녹음한 음성을 보낼지 확인한 뒤 가족에게 보내요",
            Modifier.weight(1f), state.sendCooldownRemainingSeconds, { model.showVoice(true) })
    }
    Spacer(Modifier.height(18.dp))
    PresenceCard(state, model::toggleRecipient)
    state.callActivity?.let {
        Spacer(Modifier.height(18.dp))
        CallActivityBanner(it, model::clearCallActivity)
    }
    Spacer(Modifier.height(18.dp))
    CallHistoryList(state.callHistory, model::replayVoice)
    if (state.isDemoMode) {
        Spacer(Modifier.height(16.dp))
        Text("데모 모드: 보낸 호출이 이 기기로 돌아와요.", fontSize = 12.sp, color = Purple,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
    Spacer(Modifier.height(16.dp))
    OutlinedButton(onClick = model::playDingDong, modifier = Modifier.align(Alignment.CenterHorizontally)
        .testTag("preview_dingdong")) {
        Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text("띵동 소리 미리 듣기")
    }
}

@Composable
private fun HomeAction(
    title: String, icon: ImageVector, color: Color, tag: String, hint: String, modifier: Modifier = Modifier,
    cooldownSeconds: Int = 0,
    onTap: () -> Unit, onPressStart: (() -> Unit)? = null, onPressEnd: (() -> Unit)? = null,
    tapAfterPress: Boolean = true,
) {
    val enabled = cooldownSeconds <= 0
    val gesture = when {
        !enabled -> Modifier
        onPressStart != null -> Modifier.pointerInput(Unit) {
            detectTapGestures(onTap = { if (tapAfterPress) onTap() }, onPress = {
                onPressStart(); tryAwaitRelease(); onPressEnd?.invoke()
            })
        }
        else -> Modifier.clickable(onClick = onTap)
    }
    Column(modifier.background(Color.White, RoundedCornerShape(16.dp))
        .aspectRatio(1f).border(1.dp, color.copy(.25f), RoundedCornerShape(16.dp)).then(gesture).testTag(tag)
        .semantics {
            contentDescription = if (enabled) title else "$title, ${cooldownSeconds}초 뒤에 다시 보낼 수 있어요"
            if (enabled) onClick(label = hint) { onTap(); true } else disabled()
        }.padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, contentDescription = null, tint = if (enabled) color else color.copy(.35f), modifier = Modifier.size(32.dp))
        Spacer(Modifier.height(10.dp))
        Text(title, fontSize = if (title.length > 4) 12.sp else 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            color = if (enabled) Color.Unspecified else Secondary)
        if (!enabled) {
            Spacer(Modifier.height(2.dp))
            Text("${cooldownSeconds}초 뒤 가능", fontSize = 10.sp, color = Secondary, maxLines = 1)
        }
    }
}

@Composable
private fun PresenceCard(
    state: AppUiState,
    onSelect: (PresenceUi) -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(18.dp))
        .border(1.dp, Secondary.copy(.15f), RoundedCornerShape(18.dp)).padding(14.dp).testTag("presence_list"),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Groups, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text("우리 공간", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            CompactTransportStatus(state)
            Spacer(Modifier.width(8.dp))
            Text("${state.members.size}명", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Secondary)
        }
        state.members.chunked(2).forEach { rowMembers ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowMembers.forEach { member ->
                    PresenceTile(member, member.id in state.selectedTargetIDs, onSelect, Modifier.weight(1f))
                }
                if (rowMembers.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        Text(if (state.selectedTargetIDs.isEmpty()) "선택하지 않으면 모두에게 보내요." else "선택한 사람들에게만 보내요.",
            fontSize = 11.sp, color = Secondary)
    }
}

@Composable
private fun PresenceTile(
    member: PresenceUi,
    selected: Boolean,
    onSelect: (PresenceUi) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.heightIn(min = 48.dp).background(Grouped, RoundedCornerShape(12.dp))
        .clickable(enabled = !member.isCurrentDevice) { onSelect(member) }
        .padding(horizontal = 10.dp, vertical = 7.dp)
        .semantics {
            contentDescription = if (member.isCurrentDevice) {
                "${member.name}, 현재 기기"
            } else {
                "${member.name}, 함께 받을 사람으로 선택하거나 해제하세요"
            }
        }, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(
            if (member.role == AppRole.PARENT) Icons.Default.Person else Icons.Default.PhoneAndroid,
            contentDescription = null,
            tint = if (member.isCurrentDevice) Accent else Green,
            modifier = Modifier.size(20.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(member.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            val role = if (member.role == AppRole.PARENT) "부모" else if (member.role == AppRole.CHILD) "자녀" else "가족"
            Text(if (member.isCurrentDevice) "이 기기 · $role" else "전송 가능 · $role",
                fontSize = 11.sp, color = Secondary, maxLines = 1)
        }
        if (selected) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Accent, modifier = Modifier.size(20.dp))
        } else {
            Box(Modifier.size(7.dp).background(if (member.isCurrentDevice) Accent else Green, CircleShape))
        }
    }
}

@Composable
private fun CompactTransportStatus(state: AppUiState) {
    val (text, color) = when (state.transportStatus) {
        TransportUiStatus.IDLE -> "대기" to Secondary
        TransportUiStatus.SEARCHING -> "연결 중" to Orange
        TransportUiStatus.CONNECTED -> "${state.connectedCount}대 연결" to Green
        TransportUiStatus.DEMO -> "데모" to Purple
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier.semantics { contentDescription = transportDescription(state) }) {
        Box(Modifier.size(6.dp).background(color, CircleShape))
        Text(text, fontSize = 11.sp, color = Secondary)
    }
}

private fun transportDescription(state: AppUiState): String = when (state.transportStatus) {
    TransportUiStatus.IDLE -> "꺼짐"
    TransportUiStatus.SEARCHING -> "가족 기기를 찾는 중…"
    TransportUiStatus.CONNECTED -> "근처 기기 ${state.connectedCount}대와 연결됨"
    TransportUiStatus.DEMO -> "데모 모드"
}

@Composable
private fun CallActivityBanner(activity: CallActivityUi, dismiss: () -> Unit) {
    val acknowledged = activity.kind == CallActivityKind.ACKNOWLEDGED
    Row(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(16.dp)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(if (acknowledged) "👍" else "➤", color = if (acknowledged) Green else Accent)
        Text(activity.message, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text("✕", color = Secondary.copy(.45f), modifier = Modifier.clickable(onClick = dismiss)
            .testTag("dismiss_call_activity").semantics { contentDescription = "호출 활동 닫기" })
    }
}

@Composable
private fun CallHistoryList(entries: List<CallHistoryEntry>, onReplayVoice: (CallHistoryEntry) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(18.dp))
        .border(1.dp, Secondary.copy(.15f), RoundedCornerShape(18.dp)).padding(14.dp)
        .testTag("call_history"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(20.dp))
            Text("최근 기록", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
        if (entries.isEmpty()) {
            Text("아직 호출 기록이 없어요.", fontSize = 15.sp, color = Secondary,
                modifier = Modifier.fillMaxWidth())
        } else {
            entries.take(20).forEachIndexed { index, entry ->
                CallHistoryRow(entry, onReplayVoice)
                if (index != entries.take(20).lastIndex) HorizontalDivider(color = Secondary.copy(.18f))
            }
        }
    }
}

@Composable
private fun CallHistoryRow(entry: CallHistoryEntry, onReplayVoice: (CallHistoryEntry) -> Unit) {
    val color = when {
        entry.kind == CallEvent.Kind.Siren -> Red
        entry.kind == CallEvent.Kind.VoiceMessage -> Orange
        entry.kind == CallEvent.Kind.DingDong -> Accent
        else -> Secondary
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(
            when {
                entry.kind == CallEvent.Kind.Siren -> Icons.Default.Warning
                entry.kind == CallEvent.Kind.VoiceMessage -> Icons.Default.GraphicEq
                entry.direction == CallHistoryEntry.Direction.SENT -> Icons.Default.ArrowCircleUp
                else -> Icons.Default.ArrowCircleDown
            },
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(24.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            val destination = if (entry.counterpartName == "모두") "모두에게" else "${entry.counterpartName}에게"
            Text(
                if (entry.direction == CallHistoryEntry.Direction.SENT) {
                    val particle = if (entry.kind == CallEvent.Kind.VoiceMessage) "를" else "을"
                    "$destination ${entry.kind.title}$particle 보냈어요."
                } else {
                    "${entry.counterpartName}의 ${entry.kind.arrivalTitle} 왔어요."
                },
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(CALL_HISTORY_DATE_FORMATTER.format(entry.date), fontSize = 11.sp, color = Secondary)
            if (entry.acknowledgedBy.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Green, modifier = Modifier.size(13.dp))
                    Text("확인: ${entry.acknowledgedBy.joinToString(", ")}", fontSize = 11.sp, color = Green)
                }
            }
        }
        if (entry.hasReplayableVoice) {
            Icon(Icons.Default.PlayCircle,
                contentDescription = "${entry.counterpartName} 음성 다시 듣기",
                tint = Orange,
                modifier = Modifier.size(28.dp).clickable { onReplayVoice(entry) }
                    .testTag("replay_voice_${entry.id}"))
        }
        if (entry.pendingRecipientCount > 0) {
            Text(
                "${entry.pendingRecipientCount}",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.heightIn(min = 24.dp).background(Accent, CircleShape)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .semantics { contentDescription = "확인하지 않은 사람 ${entry.pendingRecipientCount}명" },
            )
        }
    }
}

private val CALL_HISTORY_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter
    .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
    .withLocale(Locale.getDefault())
    .withZone(ZoneId.systemDefault())

@Composable
private fun InviteDialog(invite: InviteUi?, model: AppViewModel) {
    Dialog(onDismissRequest = { model.showInvite(false) }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        SheetFrame("가족 초대", "닫기", { model.showInvite(false) }, "invite_qr") {
            if (invite != null) {
                Text("가족 기기에서 이 QR을 스캔하면\n“${invite.spaceName}” 공간에 참여해요.", fontSize = 17.sp,
                    color = Secondary, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 24.dp))
                Spacer(Modifier.height(24.dp))
                QrPlaceholder(invite.url)
                Spacer(Modifier.height(24.dp))
                Text("카메라를 쓸 수 없는 기기는 링크로 참여할 수 있어요.", fontSize = 12.sp, color = Secondary,
                    textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = model::shareInvite, modifier = Modifier.testTag("share_invite")) {
                    Text("↥  초대 링크 공유")
                }
            }
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun QrPlaceholder(value: String) {
    val matrix = remember(value) {
        MultiFormatWriter().encode(
            value,
            BarcodeFormat.QR_CODE,
            1,
            1,
            mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 0),
        )
    }
    Box(Modifier.widthIn(max = 280.dp).fillMaxWidth().aspectRatio(1f).shadow(12.dp, RoundedCornerShape(24.dp),
        ambientColor = Color.Black.copy(.08f), spotColor = Color.Black.copy(.08f)).background(Color.White, RoundedCornerShape(24.dp))
        .padding(20.dp).testTag("invite_qr_code").semantics { contentDescription = "가족 공간 초대 QR 코드" }) {
        Canvas(Modifier.fillMaxSize()) {
            val unitX = size.width / matrix.width
            val unitY = size.height / matrix.height
            for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
                if (matrix[x, y]) {
                    drawRect(Color.Black, Offset(x * unitX, y * unitY), androidx.compose.ui.geometry.Size(unitX, unitY))
                }
            }
        }
    }
}

@Composable
private fun VoiceDialog(state: VoiceState, cooldownSeconds: Int, model: AppViewModel) {
    Dialog(onDismissRequest = { model.showVoice(false) }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        SheetFrame("음성", "닫기", { model.showVoice(false) }, "voice_sheet") {
            Text("버튼을 누르고 있는 동안 녹음되고,\n손을 떼면 보낼지 확인해요.", fontSize = 17.sp, color = Secondary,
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 24.dp))
            Spacer(Modifier.height(28.dp))
            val coolingDown = cooldownSeconds > 0 && state != VoiceState.RECORDING
            val color = when {
                state == VoiceState.RECORDING -> Red
                state == VoiceState.DENIED || coolingDown -> Color.Gray
                else -> Accent
            }
            Box(Modifier.requiredSize(160.dp).background(color.copy(.15f), CircleShape)
                .pointerInput(state, coolingDown) { if (!coolingDown) detectTapGestures(onPress = {
                    model.beginVoiceHold(); tryAwaitRelease(); model.endVoiceHold()
                }) }.testTag("voice_hold").semantics {
                    if (coolingDown) {
                        contentDescription = "음성 버튼, ${cooldownSeconds}초 뒤에 다시 보낼 수 있어요"
                        disabled()
                    } else {
                        contentDescription = "음성 버튼"
                        onClick(label = "1초 동안 녹음하고 전송 여부 확인") { model.recordAccessibleVoice(); true }
                    }
                }, contentAlignment = Alignment.Center) {
                Box(Modifier.requiredSize(110.dp).background(color, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(
                        if (state == VoiceState.RECORDING) Icons.Default.GraphicEq
                        else if (state == VoiceState.DENIED) Icons.Default.MicOff
                        else Icons.Default.Mic,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(40.dp),
                    )
                }
            }
            Spacer(Modifier.height(28.dp))
            when {
                state == VoiceState.DENIED -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("마이크 접근이 꺼져 있어요.", fontSize = 13.sp, color = Red)
                    Button(onClick = model::openMicrophoneSettings) { Text("마이크 설정 열기") }
                }
                state == VoiceState.REQUESTING_PERMISSION -> Text("마이크 권한을 확인하는 중…", fontSize = 13.sp, color = Secondary)
                state == VoiceState.RECORDING -> Text("녹음 중… 손을 떼면 보낼지 확인해요.", fontSize = 13.sp, color = Red)
                state == VoiceState.READY -> Text("녹음이 끝났어요.", fontSize = 13.sp, color = Secondary)
                state == VoiceState.SENT -> Text("✓  전송했어요", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Green)
                coolingDown -> Text("${cooldownSeconds}초 뒤에 다시 보낼 수 있어요.", fontSize = 13.sp, color = Secondary)
                else -> Text("버튼을 누르고 있으면 녹음이 시작돼요. (최대 15초)", fontSize = 13.sp, color = Secondary)
            }
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun VoiceConfirmationDialog(state: AppUiState, model: AppViewModel) {
    val recipientNames = state.members
        .filter { it.id in state.selectedTargetIDs && !it.isCurrentDevice }
        .joinToString(", ") { it.name }
    AlertDialog(
        onDismissRequest = model::discardVoice,
        title = { Text("음성을 보낼까요?") },
        text = {
            Text(if (recipientNames.isEmpty()) "모두에게 녹음한 음성을 보내요."
                else "${recipientNames}에게 녹음한 음성을 보내요.")
        },
        confirmButton = { TextButton(onClick = model::confirmVoiceSend) { Text("보내기") } },
        dismissButton = { TextButton(onClick = model::discardVoice) { Text("취소") } },
    )
}

@Composable
private fun SheetFrame(title: String, close: String, onClose: () -> Unit, tag: String,
    content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxSize().testTag(tag), color = Color.Transparent) {
        CalmBackground()
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(close, color = Accent, modifier = Modifier.width(72.dp).clickable(onClick = onClose).testTag("close_sheet"))
                Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(72.dp))
            }
            Column(Modifier.weight(1f).widthIn(max = 560.dp).fillMaxWidth().align(Alignment.CenterHorizontally)
                .padding(horizontal = 24.dp).imePadding(), horizontalAlignment = Alignment.CenterHorizontally, content = content)
        }
    }
}

@Composable
private fun IncomingDialog(event: IncomingUi, model: AppViewModel) {
    Dialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false,
        dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxSize().testTag("incoming_${event.kind.name.lowercase()}"), color = Color.Transparent) {
            CalmBackground()
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().fillMaxHeight().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.weight(1f))
                    val color = when (event.kind) {
                        IncomingKind.VOICE_MESSAGE -> Orange
                        IncomingKind.QUIET_ALERT -> Secondary
                        IncomingKind.SIREN -> Red
                        IncomingKind.DING_DONG -> Accent
                    }
                    BellGlyph(88.dp, color)
                    Spacer(Modifier.height(28.dp))
                    val title = when (event.kind) {
                        IncomingKind.QUIET_ALERT -> "톡톡"
                        IncomingKind.SIREN -> "사이렌 호출"
                        IncomingKind.DING_DONG -> "띵동"
                        IncomingKind.VOICE_MESSAGE -> "음성"
                    }
                    Text("${event.senderName}의 $title", fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    Text(event.timeLabel, fontSize = 13.sp, color = Secondary)
                    if (event.hasVoice) {
                        Spacer(Modifier.height(22.dp))
                        OutlinedButton(onClick = model::playVoice, modifier = Modifier.testTag("play_voice")) {
                            Text("▶  음성 듣기", fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    ProminentButton("확인했어요", true, "acknowledge") { model.acknowledgeIncoming() }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(state: AppUiState, model: AppViewModel) {
    var confirmLeave by remember { mutableStateOf(false) }
    AdaptiveContent("settings") {
        SettingsSection("가족 공간") {
            SettingsValue("이름", state.spaceName)
            SettingsValue("역할", if (state.role == AppRole.PARENT) "부모" else if (state.role == AppRole.CHILD) "자녀" else "-")
            SettingsValue("내 이름", state.displayName.ifEmpty { "-" })
            state.rooms.forEach { room ->
                SettingsButton(
                    if (room.invite.spaceId == state.invite?.spaceId) "${room.invite.spaceName} · 사용 중"
                    else "${room.invite.spaceName} · 전환",
                    "switch_room_${room.invite.spaceId}",
                    if (room.invite.spaceId == state.invite?.spaceId) Secondary else Accent,
                ) { model.switchRoom(room.invite.spaceId) }
            }
            SettingsButton("새 가족 공간 만들기", "create_another_room") { model.navigate(AppRoute.CREATE_SPACE) }
            SettingsButton("다른 공간 초대로 참여", "join_another_room") { model.navigate(AppRoute.JOIN_SPACE) }
            SettingsButton("역할 다시 고르기", "choose_role_again") { model.chooseRoleAgain() }
            SettingsButton("공간 나가기", "leave_space", Red) { confirmLeave = true }
        }
        Spacer(Modifier.height(24.dp))
        Text("근거리 연결 (Bluetooth)", fontSize = 13.sp, color = Secondary,
            modifier = Modifier.padding(start = 16.dp, bottom = 6.dp))
        SettingsSection(null) {
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("데모 모드", fontSize = 17.sp, modifier = Modifier.weight(1f))
                Switch(state.isDemoMode, model::toggleDemo, modifier = Modifier.testTag("demo_mode"))
            }
            SettingsValue("근거리 연결", when (state.transportStatus) {
                TransportUiStatus.IDLE -> "꺼짐"; TransportUiStatus.SEARCHING -> "가족 기기를 찾는 중…"
                TransportUiStatus.CONNECTED -> "근처 기기 ${state.connectedCount}대와 연결됨"; TransportUiStatus.DEMO -> "데모 모드"
            })
            SettingsValue("잠금화면 알림", state.notificationStatus, if (state.notificationStatus == "차단됨") Red else Secondary)
            if (state.notificationStatus == "허용 필요") SettingsButton("잠금화면 알림 허용", "allow_notifications") {
                model.requestNotificationPermission()
            }
            if (state.notificationStatus == "차단됨") SettingsButton("Android 알림 설정 열기", "notification_settings") { model.openNotificationSettings() }
        }
        Text("같은 공간에서는 Bluetooth로 가까운 가족 기기와 연결해요. Android가 앱을 중지하거나 기기를 재시작한 뒤에는 앱을 한 번 열어 주세요. 데모 모드에서는 상대 기기 없이 호출이 이 기기로 되돌아와요.",
            fontSize = 12.sp, color = Secondary, lineHeight = 16.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        Spacer(Modifier.height(18.dp))
        Text("원격 호출", fontSize = 13.sp, color = Secondary, modifier = Modifier.padding(start = 16.dp, bottom = 6.dp))
        SettingsSection(null) {
            SettingsValue("알림/FCM", state.pushStatus)
            if (state.pushStatus == "요청하지 않음") SettingsButton("원격 알림 켜기", "enable_remote_notifications") {
                model.enableRemoteNotifications()
            }
            SettingsValue("서버", state.serverStatus, if (state.serverStatus.startsWith("구성되지")) Orange else Secondary)
        }
        Text("FCM을 켜면 같은 가족 공간의 원격 호출을 NAS 서버에서 안전하게 받아요. iPhone은 APNs, Android는 FCM을 사용하며 둘 다 같은 호출 기록과 대상 선택을 공유해요.",
            fontSize = 12.sp, color = Secondary, lineHeight = 16.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
    }
    if (confirmLeave) AlertDialog(onDismissRequest = { confirmLeave = false },
        title = { Text("공간을 나가면 이 기기의 설정이 초기화돼요.") },
        confirmButton = { TextButton(onClick = { confirmLeave = false; model.leaveSpace() }, modifier = Modifier.testTag("confirm_leave")) {
            Text("공간 나가기", color = Red) } },
        dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("취소") } })
}

@Composable
private fun SettingsSection(title: String?, content: @Composable ColumnScope.() -> Unit) {
    title?.let { Text(it, fontSize = 13.sp, color = Secondary, modifier = Modifier.padding(start = 16.dp, bottom = 6.dp)) }
    Column(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(12.dp)), content = content)
}

@Composable
private fun SettingsValue(label: String, value: String, color: Color = Secondary) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 17.sp, modifier = Modifier.weight(1f))
        Text(value, fontSize = 17.sp, color = color, textAlign = TextAlign.End, modifier = Modifier.weight(1.3f))
    }
    HorizontalDivider(Modifier.padding(start = 16.dp), color = Secondary.copy(.18f))
}

@Composable
private fun SettingsButton(label: String, tag: String, color: Color = Accent, onClick: () -> Unit) {
    Text(label, fontSize = 17.sp, color = color, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)
        .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 11.dp).testTag(tag))
    HorizontalDivider(Modifier.padding(start = 16.dp), color = Secondary.copy(.18f))
}

@Composable
private fun GlobalErrorDialog(message: String, model: AppViewModel) {
    AlertDialog(onDismissRequest = model::clearError, title = { Text("전송 안내") }, text = { Text(message) },
        confirmButton = { TextButton(onClick = model::clearError, modifier = Modifier.testTag("dismiss_error")) { Text("확인") } })
}

@Composable
private fun ProminentButton(text: String, enabled: Boolean, tag: String, onClick: () -> Unit) {
    Button(onClick, enabled = enabled, shape = RoundedCornerShape(10.dp), colors = ButtonDefaults.buttonColors(containerColor = Accent),
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag(tag)) {
        Text(text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 5.dp))
    }
}

@Composable
private fun BellGlyph(size: androidx.compose.ui.unit.Dp, color: Color, wideWaves: Boolean = false) {
    val modifier = if (wideWaves) Modifier.width(size * 2).height(size) else Modifier.size(size)
    Canvas(modifier.semantics { contentDescription = "호출 벨" }) {
        val w = this.size.width
        val h = this.size.height
        val unit = if (wideWaves) h else w
        val centerX = w / 2f
        val bodyScale = if (wideWaves) 1.4f else 1f
        val bell = androidx.compose.ui.graphics.Path().apply {
            moveTo(centerX - unit * .22f * bodyScale, h * .68f)
            cubicTo(centerX - unit * .16f * bodyScale, h * .58f, centerX - unit * .15f * bodyScale, h * .49f, centerX - unit * .15f * bodyScale, h * .34f)
            cubicTo(centerX - unit * .15f * bodyScale, h * .20f, centerX - unit * .08f * bodyScale, h * .12f, centerX, h * .12f)
            cubicTo(centerX + unit * .08f * bodyScale, h * .12f, centerX + unit * .15f * bodyScale, h * .20f, centerX + unit * .15f * bodyScale, h * .34f)
            cubicTo(centerX + unit * .15f * bodyScale, h * .49f, centerX + unit * .16f * bodyScale, h * .58f, centerX + unit * .22f * bodyScale, h * .68f)
            quadraticTo(centerX + unit * .25f * bodyScale, h * .73f, centerX + unit * .18f * bodyScale, h * .73f)
            lineTo(centerX - unit * .18f * bodyScale, h * .73f)
            quadraticTo(centerX - unit * .25f * bodyScale, h * .73f, centerX - unit * .22f * bodyScale, h * .68f)
            close()
        }
        drawPath(bell, color)
        drawCircle(color, unit * .07f, Offset(centerX, h * .80f))
        val waveReach = if (wideWaves) unit * .86f else unit * .38f
        drawArc(color, 128f, 104f, false, Offset(centerX - waveReach, h * .22f),
            androidx.compose.ui.geometry.Size(unit * .26f, h * .48f), style = Stroke(unit * .065f, cap = StrokeCap.Round))
        drawArc(color, 308f, 104f, false, Offset(centerX + waveReach - unit * .26f, h * .22f),
            androidx.compose.ui.geometry.Size(unit * .26f, h * .48f), style = Stroke(unit * .065f, cap = StrokeCap.Round))
    }
}
