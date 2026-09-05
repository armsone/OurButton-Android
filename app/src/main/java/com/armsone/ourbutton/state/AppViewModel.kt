package com.armsone.ourbutton.state

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.armsone.ourbutton.data.AdminClient
import com.armsone.ourbutton.data.AdminSpace
import com.armsone.ourbutton.data.AdminRequestException
import com.armsone.ourbutton.data.BackendConfiguration
import com.armsone.ourbutton.data.BackendException
import com.armsone.ourbutton.data.HttpBackendClient
import com.armsone.ourbutton.push.DeviceIdentity
import com.armsone.ourbutton.data.CallHistoryEntry
import com.armsone.ourbutton.data.CallHistoryStore
import com.armsone.ourbutton.data.PendingVoiceStore
import com.armsone.ourbutton.model.CallEvent
import com.armsone.ourbutton.model.FamilyRole
import com.armsone.ourbutton.model.FamilySpace
import com.armsone.ourbutton.model.QRInvite
import com.armsone.ourbutton.push.NotificationMuteSyncStatus
import com.armsone.ourbutton.push.SpaceNotificationMuteState
import com.armsone.ourbutton.push.SpaceNotificationMuteStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.json.JSONArray
import java.time.Instant
import java.util.UUID

enum class AppPhase { SETUP, ROLE_SELECTION, HOME }
enum class AppRole { PARENT, CHILD, GENERAL }
enum class AppRoute { WELCOME, SPACE_SELECTION, CREATE_SPACE, JOIN_SPACE, SETTINGS }
enum class TransportUiStatus { IDLE, SEARCHING, CONNECTED, DEMO }
enum class IncomingKind { QUIET_ALERT, SIREN, DING_DONG, VOICE_MESSAGE }
enum class VoiceState { IDLE, REQUESTING_PERMISSION, DENIED, RECORDING, READY, SENT }
enum class CallActivityKind { SENT, ACKNOWLEDGED }

data class CallActivityUi(val kind: CallActivityKind, val message: String)

data class PresenceUi(
    val id: String,
    val name: String,
    val role: AppRole?,
    val isCurrentDevice: Boolean,
    val notificationsMuted: Boolean = false,
    val notificationMuteSyncStatus: NotificationMuteSyncStatus = NotificationMuteSyncStatus.SYNCED,
    val isLiveNearby: Boolean = false,
)

fun roleLabel(role: AppRole?): String = when (role) {
        AppRole.PARENT -> "부모"
        AppRole.CHILD -> "자녀"
        AppRole.GENERAL -> "일반"
        null -> "가족"
}

fun presenceNotificationText(member: PresenceUi): String =
    if (member.notificationsMuted) "알림 꺼짐" else "알림 켜짐"

/** Kept for state tests and older callers; visual layout now renders each status separately. */
fun presenceStatusText(member: PresenceUi): String = presenceNotificationText(member)

fun presenceLiveText(member: PresenceUi): String = when {
    member.isCurrentDevice -> "이 기기"
    member.isLiveNearby -> "근처 연결됨"
    else -> "공간에 등록됨"
}

fun presenceAfterTransportStatus(
    members: List<PresenceUi>,
    status: TransportUiStatus,
): List<PresenceUi> = if (status == TransportUiStatus.CONNECTED) members else {
    members.map { member ->
        if (member.isCurrentDevice) member else member.copy(isLiveNearby = false)
    }
}

fun presenceAfterPeerDisconnect(members: List<PresenceUi>, memberID: String): List<PresenceUi> =
    members.map { member ->
        if (!member.isCurrentDevice && member.id == memberID) member.copy(isLiveNearby = false)
        else member
    }

fun isMemberUpdateForActiveSpace(activeSpaceID: String?, updateSpaceID: UUID): Boolean =
    activeSpaceID == updateSpaceID.toString()

fun mergeDurablePresence(
    currentDevice: PresenceUi?,
    authoritativeMembers: List<PresenceUi>,
    liveNearbyIDs: Set<String>,
): List<PresenceUi> {
    val byDeviceID = linkedMapOf<String, PresenceUi>()
    authoritativeMembers.forEach { member ->
        if (!member.isCurrentDevice && member.id != "current") {
            byDeviceID[member.id] = member.copy(
                name = visibleMemberName(member.name),
                isLiveNearby = member.id in liveNearbyIDs,
            )
        }
    }
    return listOfNotNull(currentDevice) + byDeviceID.values.sortedBy { it.name }
}

fun presenceColumnCount(availableWidthDp: Int): Int = if (availableWidthDp >= 520) 2 else 1

fun visibleMemberName(name: String): String = name.trim().ifBlank { "가족" }

fun memberNameFontSizeSp(name: String): Int = when {
    visibleMemberName(name).codePointCount(0, visibleMemberName(name).length) <= 12 -> 15
    visibleMemberName(name).codePointCount(0, visibleMemberName(name).length) <= 16 -> 13
    else -> 11
}

fun presenceCompactStatusText(member: PresenceUi): String = buildList {
    add(roleLabel(member.role))
    add(presenceNotificationText(member))
    add(presenceLiveText(member))
    if (member.isCurrentDevice) when (member.notificationMuteSyncStatus) {
        NotificationMuteSyncStatus.SYNCED -> Unit
        NotificationMuteSyncStatus.SYNCING -> add("동기화 중")
        NotificationMuteSyncStatus.ERROR -> add("동기화 필요")
    }
}.joinToString(" · ")

fun reconcileKnownMembers(
    knownMembers: List<PresenceUi>,
    authoritativeMembers: List<PresenceUi>,
): List<PresenceUi> {
    val byID = linkedMapOf<String, PresenceUi>()
    (knownMembers + authoritativeMembers).forEach { member ->
        if (!member.isCurrentDevice && member.id != "current" &&
            runCatching { UUID.fromString(member.id) }.isSuccess
        ) {
            byID[member.id] = member.copy(name = visibleMemberName(member.name), isCurrentDevice = false)
        }
    }
    return byID.values.sortedBy { it.name }
}

fun presenceAccessibilityDescription(member: PresenceUi): String {
    val notification = presenceNotificationText(member)
    val sync = if (!member.isCurrentDevice) "" else when (member.notificationMuteSyncStatus) {
        NotificationMuteSyncStatus.SYNCED -> ""
        NotificationMuteSyncStatus.SYNCING -> ", 동기화 중"
        NotificationMuteSyncStatus.ERROR -> ", 동기화 필요"
    }
    return if (member.isCurrentDevice) {
        "${visibleMemberName(member.name)}, 역할 ${roleLabel(member.role)}, 현재 기기, $notification$sync, 누르면 알림을 ${if (member.notificationsMuted) "켜요" else "꺼요"}"
    } else {
        "${visibleMemberName(member.name)}, 역할 ${roleLabel(member.role)}, $notification, ${presenceLiveText(member)}, 함께 받을 사람으로 선택하거나 해제하세요"
    }
}

data class InviteUi(val spaceId: String, val spaceName: String, val secret: String) {
    val url: String
        get() = QRInvite(UUID.fromString(spaceId), spaceName, secret).urlString
}

data class SavedRoomUi(
    val invite: InviteUi,
    val displayName: String,
    val role: AppRole?,
    val notificationsMuted: Boolean = false,
    val notificationMuteSyncStatus: NotificationMuteSyncStatus = NotificationMuteSyncStatus.SYNCED,
)

fun spaceSelectionSubtitle(room: SavedRoomUi, current: Boolean): String {
    val mute = if (room.notificationsMuted) "알림 꺼짐" else "알림 켜짐"
    val sync = when (room.notificationMuteSyncStatus) {
        NotificationMuteSyncStatus.SYNCED -> ""
        NotificationMuteSyncStatus.SYNCING -> " · 동기화 중"
        NotificationMuteSyncStatus.ERROR -> " · 동기화 필요"
    }
    return if (current) "사용 중 · $mute$sync" else "$mute$sync · 이 공간으로 이동"
}

fun appStateWithNotificationMute(
    state: AppUiState,
    spaceID: UUID,
    mute: SpaceNotificationMuteState,
): AppUiState = state.copy(
    members = if (state.invite?.spaceId != spaceID.toString()) state.members else {
        state.members.map { member ->
            if (!member.isCurrentDevice) member else member.copy(
                notificationsMuted = mute.muted,
                notificationMuteSyncStatus = mute.syncStatus,
            )
        }
    },
    rooms = state.rooms.map { room ->
        if (room.invite.spaceId != spaceID.toString()) room else room.copy(
            notificationsMuted = mute.muted,
            notificationMuteSyncStatus = mute.syncStatus,
        )
    },
)

/** Immutable send credentials captured from one UI state so a later room switch cannot reroute it. */
data class OutboundContext(
    val space: FamilySpace,
    val senderName: String,
    val senderRole: FamilyRole,
) {
    fun makeEvent(
        kind: CallEvent.Kind,
        voice: ByteArray? = null,
        targetID: UUID? = null,
        targetIDs: List<UUID>? = null,
    ): CallEvent = CallEvent(
        kind = kind,
        spaceID = space.id,
        senderName = senderName.ifBlank { "가족" },
        senderID = null,
        senderRole = senderRole,
        targetID = targetID,
        targetIDs = targetIDs,
        voiceData = voice,
    )
}

data class IncomingUi(
    val id: String = UUID.randomUUID().toString(),
    val senderName: String,
    val senderID: String? = null,
    val kind: IncomingKind,
    val sentAt: Instant = Instant.now(),
    val timeLabel: String = "오후 3:00",
    val voiceData: ByteArray? = null,
    val hasVoice: Boolean = kind == IncomingKind.VOICE_MESSAGE,
)

data class AppUiState(
    val fixtureId: String? = null,
    val phase: AppPhase = AppPhase.SETUP,
    val route: AppRoute = AppRoute.WELCOME,
    val role: AppRole? = null,
    val spaceName: String = "",
    val displayName: String = "",
    val transportStatus: TransportUiStatus = TransportUiStatus.IDLE,
    val connectedCount: Int = 0,
    val members: List<PresenceUi> = emptyList(),
    val isDemoMode: Boolean = false,
    val invite: InviteUi? = null,
    val rooms: List<SavedRoomUi> = emptyList(),
    val showInvite: Boolean = false,
    val showVoice: Boolean = false,
    val voiceState: VoiceState = VoiceState.IDLE,
    val incoming: IncomingUi? = null,
    val selectedTargetIDs: Set<String> = emptySet(),
    val callActivity: CallActivityUi? = null,
    val callHistory: List<CallHistoryEntry> = emptyList(),
    val errorMessage: String? = null,
    val quietHoldTriggered: Boolean = false,
    val quietHoldRemainingSeconds: Int = 0,
    val sendCooldownRemainingSeconds: Int = 0,
    val notificationStatus: String = "허용 필요",
    val pushStatus: String = "요청하지 않음",
    val serverStatus: String = "구성되지 않음 (오프라인)",
    val isRefreshing: Boolean = false,
    val adminLoggedIn: Boolean = false,
    val adminBusy: Boolean = false,
    val adminSpaces: List<AdminSpace> = emptyList(),
    val adminError: String? = null,
)

class VisibleRefreshPolicy(private val intervalMillis: Long = 30_000L) {
    private var visible = false
    private var lastRefreshMillis: Long? = null

    fun resume(nowMillis: Long): Boolean {
        visible = true
        lastRefreshMillis = nowMillis
        return true
    }

    fun pause() { visible = false }

    fun due(nowMillis: Long): Boolean {
        val last = lastRefreshMillis ?: return false
        if (!visible || nowMillis - last < intervalMillis) return false
        lastRefreshMillis = nowMillis
        return true
    }
}

data class HomeRefreshToken(val spaceID: String, val generation: Long)

class HomeRefreshGate {
    private var generation = 0L
    private var active: HomeRefreshToken? = null

    fun begin(spaceID: String): HomeRefreshToken? {
        if (active?.spaceID == spaceID) return null
        return HomeRefreshToken(spaceID, ++generation).also { active = it }
    }

    fun finish(token: HomeRefreshToken, activeSpaceID: String?): Boolean {
        if (active != token || token.spaceID != activeSpaceID) return false
        active = null
        return true
    }

    fun cancel() {
        generation += 1
        active = null
    }
}

/** Platform-owned work stays behind this boundary. */
interface AppHardwareGateway {
    fun startQrScanner(onCode: (String) -> Unit) = Unit
    fun stopQrScanner() = Unit
    fun beginVoiceRecording(
        maxSeconds: Int,
        onState: (VoiceState) -> Unit,
        onFinished: (ByteArray?) -> Unit,
    ) = Unit
    fun stopVoiceRecording() = Unit
    fun playDingDong() = Unit
    fun playSiren() = Unit
    fun playVoice(data: ByteArray) = Unit
    fun stopPlayback() = Unit
    fun send(
        context: OutboundContext,
        kind: IncomingKind,
        voice: ByteArray? = null,
        targetIDs: Set<String> = emptySet(),
        onError: (String) -> Unit = {},
        onSent: (String, Boolean) -> Unit = { _, _ -> },
    ) = Unit
    fun acknowledge(context: OutboundContext, eventId: String, targetID: String? = null) = Unit
    fun requestNotificationPermission(onStatus: (String) -> Unit) = Unit
    fun enableRemoteNotifications(onStatus: (String) -> Unit) = Unit
    fun openNotificationSettings() = Unit
    fun openMicrophoneSettings() = Unit
    fun share(text: String) = Unit
    fun notificationStatus(): String = "허용 필요"
    fun pushStatus(): String = "요청하지 않음"
    fun serverStatus(): String = "구성되지 않음 (오프라인)"
    fun onForeground() = Unit
    fun refreshHome(onFinished: () -> Unit = {}) = onFinished()
    fun queueNotificationMuteSync(
        spaceID: UUID,
        onStatus: (SpaceNotificationMuteState) -> Unit = {},
    ) = Unit
}

object NoOpHardwareGateway : AppHardwareGateway

class AppViewModel(
    application: Application,
    private var hardware: AppHardwareGateway,
    private val historyStore: CallHistoryStore = CallHistoryStore(application),
) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, NoOpHardwareGateway)
    private val prefs = application.getSharedPreferences("button_state", Context.MODE_PRIVATE)
    private val pendingVoiceStore = PendingVoiceStore(application)
    private val notificationMuteStore = SpaceNotificationMuteStore(application)
    private val knownMemberStore = KnownMemberStore(application)
    private val _uiState = MutableStateFlow(loadState())
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()

    private val adminClient = AdminClient(BackendConfiguration.load(application))
    private val metadataClient = HttpBackendClient(BackendConfiguration.load(application))
    private var adminToken: String? = null
    private var roomSyncJob: Job? = null

    fun adminLogin(username: String, password: String) {
        if (_uiState.value.adminBusy || username.isBlank() || password.isBlank()) return
        _uiState.update { it.copy(adminBusy = true, adminError = null) }
        viewModelScope.launch {
            try {
                adminToken = adminClient.login(username.trim(), password)
                _uiState.update { it.copy(adminLoggedIn = true) }
                val rooms = adminClient.spaces(adminToken!!)
                _uiState.update { it.copy(adminSpaces = rooms) }
            } catch (error: Exception) { adminFailure(error) }
            finally { _uiState.update { it.copy(adminBusy = false) } }
        }
    }

    private fun adminFailure(error: Exception) {
        if (error is AdminRequestException && error.status == 401) {
            adminToken = null
            _uiState.update { it.copy(adminLoggedIn = false, adminSpaces = emptyList()) }
        }
        _uiState.update { it.copy(adminError = error.message ?: "관리자 요청을 처리하지 못했어요.") }
    }

    private fun adminRequest(action: suspend (String) -> Unit) {
        val token = adminToken ?: return
        if (_uiState.value.adminBusy) return
        _uiState.update { it.copy(adminBusy = true, adminError = null) }
        viewModelScope.launch {
            try {
                action(token)
                val rooms = adminClient.spaces(token)
                _uiState.update { it.copy(adminSpaces = rooms) }
                syncServerRooms()
            } catch (error: Exception) { adminFailure(error) }
            finally { _uiState.update { it.copy(adminBusy = false) } }
        }
    }

    fun refreshAdminSpaces() = adminRequest { }
    fun renameAdminSpace(spaceID: String, name: String) = adminRequest { adminClient.rename(it, spaceID, name.trim()) }
    fun changeAdminRole(spaceID: String, deviceID: String, role: String) = adminRequest { adminClient.role(it, spaceID, deviceID, role) }
    fun deleteAdminSpace(spaceID: String) = adminRequest { adminClient.delete(it, spaceID) }
    fun adminLogout() {
        val token = adminToken
        adminToken = null
        _uiState.update { it.copy(adminLoggedIn = false, adminSpaces = emptyList(), adminError = null) }
        if (token != null) viewModelScope.launch { runCatching { adminClient.logout(token) } }
    }

    private fun syncServerRooms() {
        if (roomSyncJob?.isActive == true || _uiState.value.fixtureId != null) return
        val rooms = _uiState.value.rooms.toList()
        roomSyncJob = viewModelScope.launch {
            val ownID = DeviceIdentity.loadOrCreate(getApplication())
            for (room in rooms) {
                val space = runCatching { FamilySpace(UUID.fromString(room.invite.spaceId), room.invite.spaceName, room.invite.secret) }.getOrNull() ?: continue
                try {
                    val snapshot = metadataClient.fetchSpaceSnapshot(space)
                    val ownRole = snapshot.members.firstOrNull { it.deviceID == ownID }?.role?.let { role ->
                        when (role) { FamilyRole.Parent -> AppRole.PARENT; FamilyRole.Child -> AppRole.CHILD; FamilyRole.General -> AppRole.GENERAL }
                    }
                    _uiState.update { state ->
                        val current = state.invite?.spaceId == room.invite.spaceId
                        val name = snapshot.name ?: room.invite.spaceName
                        state.copy(
                            rooms = state.rooms.map { saved -> if (saved.invite.spaceId == room.invite.spaceId) saved.copy(invite = saved.invite.copy(spaceName = name), role = ownRole ?: saved.role) else saved },
                            invite = if (current) state.invite?.copy(spaceName = name) else state.invite,
                            spaceName = if (current) name else state.spaceName,
                            role = if (current) ownRole ?: state.role else state.role,
                            members = if (current && ownRole != null) state.members.map { if (it.isCurrentDevice) it.copy(role = ownRole) else it } else state.members,
                        )
                    }
                    persist()
                } catch (error: Exception) {
                    if (error is BackendException.HttpError && error.code == 410) invalidateDeletedRoom(room.invite.spaceId)
                }
            }
        }
    }

    private fun invalidateDeletedRoom(spaceID: String) {
        val state = _uiState.value
        val remaining = state.rooms.filterNot { it.invite.spaceId == spaceID }
        if (remaining.size == state.rooms.size) return
        if (state.invite?.spaceId == spaceID) {
            cancelTransientWork()
            val next = remaining.firstOrNull()
            _uiState.value = state.copy(
                rooms = remaining, invite = next?.invite, spaceName = next?.invite?.spaceName.orEmpty(),
                displayName = next?.displayName.orEmpty(), role = next?.role,
                phase = if (next == null) AppPhase.SETUP else if (next.role == null) AppPhase.ROLE_SELECTION else AppPhase.HOME,
                route = AppRoute.WELCOME, members = emptyList(), selectedTargetIDs = emptySet(),
                callHistory = next?.let { historyFor(it.invite.spaceId) }.orEmpty(),
                showInvite = false, showVoice = false, incoming = null,
                errorMessage = "관리자가 이 공간을 삭제했어요.",
            )
        } else _uiState.update { it.copy(rooms = remaining, errorMessage = "관리자가 저장된 공간을 삭제했어요.") }
        if (remaining.isEmpty()) prefs.edit().remove("state").apply() else persist()
    }

    private val sendCooldown = SendCooldown()
    private var cooldownJob: Job? = null
    private var quietHoldJob: Job? = null
    private var incomingDismissJob: Job? = null
    private var voiceLimitJob: Job? = null
    private val memberExpiryJobs = mutableMapOf<String, Job>()
    private val localMemberIDs = mutableSetOf<String>()
    private val remoteMemberIDs = mutableSetOf<String>()
    private val sentCallIDs = mutableMapOf<String, Long>()
    private var suppressNextQuietTap = false
    private var pendingVoiceData: ByteArray? = null
    private var pendingVoiceSpaceID: UUID? = null
    private var voiceSendPending = false
    private var visibleRefreshJob: Job? = null
    private var refreshTimeoutJob: Job? = null
    private val homeRefreshGate = HomeRefreshGate()

    fun attachHardware(gateway: AppHardwareGateway) {
        hardware = gateway
    }

    fun navigate(route: AppRoute) = _uiState.update { it.copy(route = route) }
    fun back() = _uiState.update { it.copy(route = AppRoute.WELCOME) }

    fun createSpace(spaceName: String, memberName: String) {
        val name = prefixCodePoints(spaceName.trim(), 30)
        val member = prefixCodePoints(memberName.trim(), 20)
        if (name.isEmpty() || member.isEmpty()) return
        val space = FamilySpace(name = name)
        val invite = InviteUi(space.id.toString(), space.name, space.secret)
        _uiState.update {
            val room = SavedRoomUi(invite, member, null)
            it.copy(phase = AppPhase.ROLE_SELECTION, route = AppRoute.WELCOME,
                spaceName = name, displayName = member, invite = invite,
                role = null, rooms = it.rooms.filterNot { saved -> saved.invite.spaceId == invite.spaceId } + room,
                transportStatus = TransportUiStatus.SEARCHING,
                callHistory = historyFor(invite.spaceId))
        }
        persist()
    }

    fun parseInvite(raw: String): Result<InviteUi> = runCatching {
        val invite = QRInvite.parse(raw)
        InviteUi(invite.spaceID.toString(), invite.spaceName, invite.secret)
    }

    fun join(invite: InviteUi, memberName: String) {
        val member = prefixCodePoints(memberName.trim(), 20)
        if (member.isEmpty()) return
        _uiState.update {
            val knownRole = it.rooms.firstOrNull { saved ->
                saved.invite.spaceId == invite.spaceId && saved.invite.secret == invite.secret
            }?.role
            val room = roomWithMute(SavedRoomUi(invite, member, knownRole))
            val own = knownRole?.let { role -> currentPresence(invite.spaceId, member, role) }
            val visibleMembers = own?.let { membersForSpace(invite.spaceId, it) }.orEmpty()
            it.copy(
                phase = if (knownRole == null) AppPhase.ROLE_SELECTION else AppPhase.HOME,
                route = AppRoute.WELCOME,
                spaceName = invite.spaceName,
                displayName = member,
                invite = invite,
                role = knownRole,
                rooms = it.rooms.filterNot { saved -> saved.invite.spaceId == invite.spaceId } + room,
                members = visibleMembers,
                selectedTargetIDs = emptySet(),
                transportStatus = TransportUiStatus.SEARCHING,
                callHistory = historyFor(invite.spaceId),
            )
        }
        persist()
    }

    fun selectRole(role: AppRole) {
        if (role != AppRole.CHILD) {
            cooldownJob?.cancel()
            sendCooldown.reset()
        }
        clearLivePresenceTracking()
        _uiState.update {
            val own = currentPresence(
                it.invite?.spaceId,
                it.displayName.ifEmpty { "이 기기" },
                role,
            )
            it.copy(
                phase = AppPhase.HOME,
                role = role,
                members = membersForSpace(it.invite?.spaceId, own),
                rooms = it.rooms.map { room ->
                    if (room.invite.spaceId == it.invite?.spaceId) {
                        room.copy(displayName = it.displayName, role = role)
                    } else room
                },
                sendCooldownRemainingSeconds = if (role != AppRole.CHILD) 0 else it.sendCooldownRemainingSeconds,
            )
        }
        persist()
    }

    fun showInvite(show: Boolean) = _uiState.update { it.copy(showInvite = show) }
    fun showVoice(show: Boolean) {
        _uiState.update { it.copy(showVoice = show, voiceState = VoiceState.IDLE) }
    }
    fun clearCallActivity() = _uiState.update { it.copy(callActivity = null) }
    fun clearError() = _uiState.update { it.copy(errorMessage = null) }

    fun sendDingDong() {
        sendCall(IncomingKind.DING_DONG)
    }

    fun beginQuietHold() {
        quietHoldJob?.cancel()
        _uiState.update { it.copy(quietHoldTriggered = false, quietHoldRemainingSeconds = 5) }
        quietHoldJob = viewModelScope.launch {
            for (remaining in 4 downTo 1) {
                delay(1_000)
                _uiState.update { it.copy(quietHoldRemainingSeconds = remaining) }
            }
            delay(1_000)
            suppressNextQuietTap = true
            sendCall(IncomingKind.SIREN)
            _uiState.update { it.copy(quietHoldTriggered = true, quietHoldRemainingSeconds = 0) }
        }
    }

    fun endQuietHold() {
        quietHoldJob?.cancel()
        quietHoldJob = null
        _uiState.update { it.copy(quietHoldRemainingSeconds = 0) }
    }

    fun sendQuietTap() {
        if (suppressNextQuietTap) {
            suppressNextQuietTap = false
            _uiState.update { it.copy(quietHoldTriggered = false) }
            return
        }
        sendCall(IncomingKind.QUIET_ALERT)
    }

    fun beginVoiceHold() {
        if (_uiState.value.voiceState == VoiceState.DENIED) {
            hardware.openMicrophoneSettings()
            return
        }
        val recordingSpaceID = currentSpaceID() ?: run {
            showError("먼저 가족 공간을 선택해 주세요.")
            return
        }
        pendingVoiceSpaceID = recordingSpaceID
        hardware.beginVoiceRecording(15, { state ->
            _uiState.update { it.copy(voiceState = state) }
        }) { data -> finishVoice(data, recordingSpaceID) }
        voiceLimitJob?.cancel()
        voiceLimitJob = viewModelScope.launch {
            delay(15_000)
            hardware.stopVoiceRecording()
        }
    }

    fun endVoiceHold() {
        if (_uiState.value.voiceState != VoiceState.RECORDING &&
            _uiState.value.voiceState != VoiceState.REQUESTING_PERMISSION
        ) return
        voiceLimitJob?.cancel()
        hardware.stopVoiceRecording()
    }

    fun setMicrophoneDenied(denied: Boolean) = _uiState.update {
        it.copy(voiceState = if (denied) VoiceState.DENIED else VoiceState.IDLE)
    }

    private fun finishVoice(data: ByteArray?, recordingSpaceID: UUID) {
        voiceLimitJob?.cancel()
        if (recordingSpaceID != currentSpaceID() || pendingVoiceSpaceID != recordingSpaceID) return
        if (data == null || data.isEmpty()) {
            pendingVoiceData = null
            pendingVoiceSpaceID = null
            _uiState.update { it.copy(voiceState = VoiceState.IDLE) }
            return
        }
        pendingVoiceData = data
        _uiState.update { it.copy(voiceState = VoiceState.READY) }
    }

    fun confirmVoiceSend() {
        val data = pendingVoiceData ?: return
        val voiceSpaceID = pendingVoiceSpaceID ?: return
        if (voiceSendPending || voiceSpaceID != currentSpaceID()) return
        voiceSendPending = true
        sendCall(
            IncomingKind.VOICE_MESSAGE,
            data,
            onSuccess = {
                if (currentSpaceID() != voiceSpaceID) return@sendCall
                voiceSendPending = false
                pendingVoiceData = null
                pendingVoiceSpaceID = null
                _uiState.update { it.copy(voiceState = VoiceState.SENT) }
                viewModelScope.launch {
                    delay(2_000)
                    _uiState.update { it.copy(voiceState = VoiceState.IDLE) }
                }
            },
            onFailure = {
                if (currentSpaceID() != voiceSpaceID) return@sendCall
                voiceSendPending = false
                _uiState.update { it.copy(voiceState = VoiceState.READY) }
            },
        )
    }

    fun discardVoice() {
        pendingVoiceData = null
        pendingVoiceSpaceID = null
        voiceSendPending = false
        _uiState.update { it.copy(voiceState = VoiceState.IDLE) }
    }

    fun presentIncoming(event: IncomingUi) {
        incomingDismissJob?.cancel()
        recordIncoming(event)
        _uiState.update { it.copy(incoming = event) }
        incomingDismissJob = viewModelScope.launch {
            delay(3_000)
            dismissIncoming()
        }
    }

    /** Records a delivered call without presenting foreground UI (for background push/BLE delivery). */
    fun recordIncoming(event: IncomingUi) {
        val spaceID = currentSpaceID()
        val eventID = runCatching { UUID.fromString(event.id) }.getOrNull()
        if (spaceID != null && eventID != null) {
            historyStore.recordReceived(
                id = eventID,
                spaceID = spaceID,
                kind = event.kind.toCallKind(),
                senderName = event.senderName,
                date = event.sentAt,
                voiceData = event.voiceData,
            )
        }
        _uiState.update { it.copy(callHistory = historyForCurrentSpace()) }
    }

    fun dismissIncoming() {
        incomingDismissJob?.cancel()
        hardware.stopPlayback()
        _uiState.update { it.copy(incoming = null) }
    }

    fun acknowledgeIncoming() {
        val event = _uiState.value.incoming ?: return
        hardware.stopPlayback()
        if (_uiState.value.isDemoMode) {
            viewModelScope.launch {
                delay(1_200)
                presentAcknowledge(senderName(), event.id, null)
            }
        } else {
            outboundContext()?.let { hardware.acknowledge(it, event.id, event.senderID) }
        }
        _uiState.update { it.copy(incoming = null) }
    }

    fun toggleDemo(enabled: Boolean) {
        _uiState.update { it.copy(isDemoMode = enabled,
            transportStatus = if (enabled) TransportUiStatus.DEMO else TransportUiStatus.SEARCHING) }
        persist()
    }

    fun chooseRoleAgain() {
        cancelTransientWork()
        pendingVoiceData = null
        pendingVoiceSpaceID = null
        voiceSendPending = false
        clearLivePresenceTracking()
        _uiState.update {
            it.copy(phase = AppPhase.ROLE_SELECTION, role = null, route = AppRoute.WELCOME,
                rooms = it.rooms.map { room ->
                    if (room.invite.spaceId == it.invite?.spaceId) room.copy(role = null) else room
                })
        }
        persist()
    }

    fun switchRoom(spaceID: String) {
        val room = _uiState.value.rooms.firstOrNull { it.invite.spaceId == spaceID } ?: return
        if (room.invite.spaceId == _uiState.value.invite?.spaceId) {
            _uiState.update { it.copy(route = AppRoute.WELCOME) }
            return
        }
        cancelTransientWork()
        clearLivePresenceTracking()
        val own = room.role?.let { currentPresence(room.invite.spaceId, room.displayName, it) }
        val visibleMembers = own?.let { membersForSpace(room.invite.spaceId, it) }.orEmpty()
        _uiState.update {
            it.copy(
                phase = if (room.role == null) AppPhase.ROLE_SELECTION else AppPhase.HOME,
                route = AppRoute.WELCOME,
                role = room.role,
                spaceName = room.invite.spaceName,
                displayName = room.displayName,
                invite = room.invite,
                members = visibleMembers,
                selectedTargetIDs = emptySet(),
                callHistory = historyFor(room.invite.spaceId),
                transportStatus = if (it.isDemoMode) TransportUiStatus.DEMO else TransportUiStatus.SEARCHING,
            )
        }
        persist()
    }

    fun leaveSpace() {
        cancelTransientWork()
        cooldownJob?.cancel()
        cooldownJob = null
        sendCooldown.reset()
        memberExpiryJobs.values.forEach(Job::cancel)
        memberExpiryJobs.clear()
        localMemberIDs.clear()
        remoteMemberIDs.clear()
        pendingVoiceData = null
        pendingVoiceSpaceID = null
        voiceSendPending = false
        val leaving = currentSpaceID()
        if (leaving != null) {
            pendingVoiceStore.clear(leaving)
            historyStore.clear(leaving)
            knownMemberStore.clear(leaving)
        }
        val remaining = _uiState.value.rooms.filterNot { it.invite.spaceId == leaving?.toString() }
        if (remaining.isEmpty()) {
            prefs.edit().clear().apply()
            _uiState.value = AppUiState()
        } else {
            val nextRoom = remaining.first()
            val own = nextRoom.role?.let {
                currentPresence(nextRoom.invite.spaceId, nextRoom.displayName, it)
            }
            val visibleMembers = own?.let { membersForSpace(nextRoom.invite.spaceId, it) }.orEmpty()
            _uiState.update {
                it.copy(
                    phase = if (nextRoom.role == null) AppPhase.ROLE_SELECTION else AppPhase.HOME,
                    route = AppRoute.WELCOME,
                    role = nextRoom.role,
                    spaceName = nextRoom.invite.spaceName,
                    displayName = nextRoom.displayName,
                    invite = nextRoom.invite,
                    rooms = remaining,
                    members = visibleMembers,
                    selectedTargetIDs = emptySet(),
                    callHistory = historyFor(nextRoom.invite.spaceId),
                    transportStatus = if (it.isDemoMode) TransportUiStatus.DEMO else TransportUiStatus.SEARCHING,
                )
            }
            persist()
        }
    }

    fun playDingDong() = hardware.playDingDong()
    fun playVoice() = _uiState.value.incoming?.voiceData?.let(hardware::playVoice)
    fun replayVoice(entry: CallHistoryEntry) = historyStore.voiceData(entry)?.let(hardware::playVoice)
    fun startQrScanner(onCode: (String) -> Unit) = hardware.startQrScanner(onCode)
    fun stopQrScanner() = hardware.stopQrScanner()
    fun shareInvite() = _uiState.value.invite?.let { hardware.share(it.url) }
    fun openNotificationSettings() = hardware.openNotificationSettings()
    fun openMicrophoneSettings() = hardware.openMicrophoneSettings()
    fun requestNotificationPermission() = hardware.requestNotificationPermission { status ->
        _uiState.update { it.copy(notificationStatus = status) }
    }
    fun enableRemoteNotifications() = hardware.enableRemoteNotifications { status ->
        _uiState.update { it.copy(pushStatus = status) }
    }
    fun showError(message: String) = _uiState.update { it.copy(errorMessage = message) }

    fun updateTransport(status: TransportUiStatus, connectedCount: Int = 0) {
        if (status != TransportUiStatus.CONNECTED) {
            memberExpiryJobs.values.forEach(Job::cancel)
            memberExpiryJobs.clear()
            localMemberIDs.clear()
        }
        _uiState.update {
            if (it.isDemoMode) it else it.copy(
                transportStatus = status,
                connectedCount = connectedCount,
                members = presenceAfterTransportStatus(it.members, status),
            )
        }
    }

    fun presentAcknowledge(senderName: String, ackFor: String?, senderID: String? = null) {
        val originalID = ackFor ?: return
        pruneSentCallIDs()
        val wasRecorded = runCatching { UUID.fromString(originalID) }.getOrNull()
            ?.let { eventID ->
                historyStore.markAcknowledged(
                    eventID, senderName, senderID?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                )
            }
            ?: false
        if (!sentCallIDs.containsKey(originalID) && !wasRecorded) return
        _uiState.update {
            it.copy(callActivity = CallActivityUi(
                CallActivityKind.ACKNOWLEDGED,
                "${koreanSubject(senderName)} 호출을 확인했어요.",
            ), callHistory = historyForCurrentSpace())
        }
    }

    fun toggleRecipient(member: PresenceUi) {
        if (member.isCurrentDevice) {
            toggleCurrentDeviceNotifications()
            return
        }
        _uiState.update {
            it.copy(selectedTargetIDs = if (member.id in it.selectedTargetIDs) {
                it.selectedTargetIDs - member.id
            } else {
                it.selectedTargetIDs + member.id
            })
        }
    }

    private fun toggleCurrentDeviceNotifications() {
        val spaceID = currentSpaceID() ?: return
        val current = notificationMuteStore.state(spaceID)
        val pending = notificationMuteStore.setDesired(spaceID, !current.muted)
        if (pending.muted) dismissIncoming()
        updateCurrentMuteState(spaceID, pending)
        hardware.queueNotificationMuteSync(spaceID) { latest ->
            updateCurrentMuteState(spaceID, latest)
        }
    }

    private fun updateCurrentMuteState(spaceID: UUID, mute: SpaceNotificationMuteState) {
        _uiState.update { state -> appStateWithNotificationMute(state, spaceID, mute) }
    }

    fun updateNotificationMuteStatus(spaceID: UUID, mute: SpaceNotificationMuteState) =
        updateCurrentMuteState(spaceID, mute)

    fun updateRemoteMember(id: String, name: String, role: AppRole?) {
        val spaceID = currentSpaceID() ?: return
        if (id == "current" || runCatching { UUID.fromString(id) }.isFailure) return
        localMemberIDs += id
        val authenticated = PresenceUi(
            id = id,
            name = visibleMemberName(name),
            role = role,
            isCurrentDevice = false,
            isLiveNearby = true,
        )
        knownMemberStore.upsert(spaceID, authenticated)
        _uiState.update { state ->
            val byID = state.members.associateByTo(linkedMapOf()) { it.id }
            val existing = byID[id]
            byID[id] = authenticated.copy(
                notificationsMuted = existing?.notificationsMuted ?: false,
                role = role ?: existing?.role,
            )
            state.copy(members = byID.values.sortedWith(
                compareByDescending<PresenceUi> { it.isCurrentDevice }.thenBy { it.name },
            ))
        }
        memberExpiryJobs.remove(id)?.cancel()
        memberExpiryJobs[id] = viewModelScope.launch {
            delay(600_000)
            localMemberIDs -= id
            _uiState.update {
                it.copy(members = it.members.map { member ->
                    if (member.id == id && !member.isCurrentDevice) member.copy(isLiveNearby = false) else member
                })
            }
            memberExpiryJobs.remove(id)
        }
    }

    fun markRemoteMemberDisconnected(spaceID: UUID, id: String) {
        if (!isMemberUpdateForActiveSpace(_uiState.value.invite?.spaceId, spaceID)) return
        localMemberIDs -= id
        memberExpiryJobs.remove(id)?.cancel()
        _uiState.update { state ->
            state.copy(members = presenceAfterPeerDisconnect(state.members, id))
        }
    }

    fun replaceRemoteMembers(members: List<PresenceUi>) {
        remoteMemberIDs.clear()
        remoteMemberIDs += members.map { it.id }
        _uiState.update { state ->
            val spaceID = currentSpaceID()
            val durable = if (spaceID == null) members else knownMemberStore.reconcile(spaceID, members)
            val visible = mergeDurablePresence(
                state.members.firstOrNull { it.isCurrentDevice }, durable, localMemberIDs,
            )
            state.copy(
                members = visible,
                selectedTargetIDs = state.selectedTargetIDs.filterTo(mutableSetOf()) { selected ->
                    visible.any { it.id == selected && !it.isCurrentDevice }
                },
            )
        }
    }

    fun onForeground() {
        if (_uiState.value.fixtureId != null) return
        hardware.onForeground()
        visibleRefreshJob?.cancel()
        refreshHome()
        visibleRefreshJob = viewModelScope.launch {
            while (true) {
                delay(30_000)
                refreshHome()
            }
        }
        _uiState.update {
            it.copy(
                notificationStatus = hardware.notificationStatus(),
                pushStatus = hardware.pushStatus(),
                serverStatus = hardware.serverStatus(),
                callHistory = historyForCurrentSpace(),
                voiceState = if (it.voiceState == VoiceState.DENIED) VoiceState.IDLE else it.voiceState,
            )
        }
        val activeSpaceID = currentSpaceID()
        if (activeSpaceID != null && !notificationMuteStore.isMuted(activeSpaceID)) {
            pendingVoiceStore.take(activeSpaceID, historyStore.entries)?.let { eventID ->
            historyStore.entries.firstOrNull {
                it.id == eventID &&
                    it.spaceID == activeSpaceID &&
                    it.direction == CallHistoryEntry.Direction.RECEIVED &&
                    Instant.now().epochSecond - it.date.epochSecond <= 600
            }?.let(historyStore::voiceData)?.let(hardware::playVoice)
            }
        }
    }

    fun onBackground() {
        visibleRefreshJob?.cancel()
        visibleRefreshJob = null
        refreshTimeoutJob?.cancel()
        refreshTimeoutJob = null
        homeRefreshGate.cancel()
        _uiState.update { it.copy(isRefreshing = false) }
        cancelTransientWork()
    }

    fun refreshHome() {
        syncServerRooms()
        val spaceID = _uiState.value.invite?.spaceId ?: return
        if (_uiState.value.phase != AppPhase.HOME) return
        val token = homeRefreshGate.begin(spaceID) ?: return
        _uiState.update { it.copy(isRefreshing = true) }
        refreshTimeoutJob?.cancel()
        refreshTimeoutJob = viewModelScope.launch {
            delay(10_000)
            finishHomeRefresh(token)
        }
        hardware.refreshHome { finishHomeRefresh(token) }
    }

    private fun finishHomeRefresh(token: HomeRefreshToken) {
        if (!homeRefreshGate.finish(token, _uiState.value.invite?.spaceId)) return
        refreshTimeoutJob?.cancel()
        refreshTimeoutJob = null
        _uiState.update { it.copy(isRefreshing = false) }
    }

    fun recordAccessibleVoice() {
        if (_uiState.value.voiceState == VoiceState.RECORDING) {
            endVoiceHold()
            return
        }
        beginVoiceHold()
        voiceLimitJob?.cancel()
        voiceLimitJob = viewModelScope.launch {
            delay(1_000)
            hardware.stopVoiceRecording()
        }
    }

    fun applyFixture(id: String?) {
        if (id.isNullOrBlank()) return
        val base = fixtureBase()
        val fixture = when (id) {
            "setup_welcome" -> AppUiState()
            "setup_create" -> AppUiState(route = AppRoute.CREATE_SPACE)
            "setup_join" -> AppUiState(route = AppRoute.JOIN_SPACE)
            "setup_join_invalid" -> AppUiState(route = AppRoute.JOIN_SPACE)
            "setup_join_confirmed" -> AppUiState(route = AppRoute.JOIN_SPACE)
            "role_selection" -> base.copy(phase = AppPhase.ROLE_SELECTION)
            "parent_home" -> base.copy(phase = AppPhase.HOME, role = AppRole.PARENT)
            "parent_home_targeted" -> base.copy(phase = AppPhase.HOME, role = AppRole.PARENT,
                selectedTargetIDs = setOf(FIXTURE_CHILD_ID))
            "parent_home_sent" -> base.copy(phase = AppPhase.HOME, role = AppRole.PARENT,
                callActivity = CallActivityUi(CallActivityKind.SENT, "첫째에게 띵동 호출을 보냈어요."))
            "parent_home_ack" -> base.copy(phase = AppPhase.HOME, role = AppRole.PARENT,
                callActivity = CallActivityUi(CallActivityKind.ACKNOWLEDGED, "첫째가 호출을 확인했어요."))
            "parent_home_history" -> base.copy(phase = AppPhase.HOME, role = AppRole.PARENT,
                callHistory = fixtureHistory())
            "parent_home_demo" -> base.copy(phase = AppPhase.HOME, role = AppRole.PARENT,
                isDemoMode = true, transportStatus = TransportUiStatus.DEMO)
            "parent_home_idle" -> base.copy(phase = AppPhase.HOME, role = AppRole.PARENT,
                transportStatus = TransportUiStatus.IDLE, connectedCount = 0)
            "parent_home_searching" -> base.copy(phase = AppPhase.HOME, role = AppRole.PARENT,
                transportStatus = TransportUiStatus.SEARCHING, connectedCount = 0)
            "parent_home_voice_recording" -> base.copy(phase = AppPhase.HOME, role = AppRole.PARENT,
                voiceState = VoiceState.RECORDING)
            "parent_home_voice_ready" -> base.copy(phase = AppPhase.HOME, role = AppRole.PARENT,
                voiceState = VoiceState.READY)
            "parent_home_voice_sent" -> base.copy(phase = AppPhase.HOME, role = AppRole.PARENT,
                voiceState = VoiceState.SENT)
            "parent_home_siren_countdown" -> base.copy(phase = AppPhase.HOME, role = AppRole.PARENT,
                quietHoldRemainingSeconds = 3)
            "child_home" -> base.copy(phase = AppPhase.HOME, role = AppRole.CHILD)
            "child_home_targeted" -> base.copy(phase = AppPhase.HOME, role = AppRole.CHILD,
                selectedTargetIDs = setOf(FIXTURE_CHILD_ID))
            "child_home_sent" -> base.copy(phase = AppPhase.HOME, role = AppRole.CHILD,
                callActivity = CallActivityUi(CallActivityKind.SENT, "모두에게 조용한 호출을 보냈어요."))
            "child_home_ack" -> base.copy(phase = AppPhase.HOME, role = AppRole.CHILD,
                callActivity = CallActivityUi(CallActivityKind.ACKNOWLEDGED, "첫째가 호출을 확인했어요."))
            "child_home_history" -> base.copy(phase = AppPhase.HOME, role = AppRole.CHILD,
                callHistory = fixtureHistory())
            "child_home_demo" -> base.copy(phase = AppPhase.HOME, role = AppRole.CHILD,
                isDemoMode = true, transportStatus = TransportUiStatus.DEMO)
            "child_home_siren_countdown" -> base.copy(phase = AppPhase.HOME, role = AppRole.CHILD,
                quietHoldRemainingSeconds = 3)
            "invite_qr" -> base.copy(phase = AppPhase.HOME, role = AppRole.PARENT, showInvite = true)
            "voice_idle" -> base.copy(phase = AppPhase.HOME, role = AppRole.PARENT, showVoice = true)
            "voice_requesting" -> base.copy(phase = AppPhase.HOME, role = AppRole.PARENT, showVoice = true, voiceState = VoiceState.REQUESTING_PERMISSION)
            "voice_recording" -> base.copy(phase = AppPhase.HOME, role = AppRole.PARENT, showVoice = true, voiceState = VoiceState.RECORDING)
            "voice_ready" -> base.copy(phase = AppPhase.HOME, role = AppRole.PARENT, showVoice = true, voiceState = VoiceState.READY)
            "voice_denied" -> base.copy(phase = AppPhase.HOME, role = AppRole.PARENT, showVoice = true, voiceState = VoiceState.DENIED)
            "voice_sent" -> base.copy(phase = AppPhase.HOME, role = AppRole.PARENT, showVoice = true, voiceState = VoiceState.SENT)
            "incoming_quiet" -> base.copy(phase = AppPhase.HOME, role = AppRole.CHILD, incoming = IncomingUi(senderName = "엄마", kind = IncomingKind.QUIET_ALERT))
            "incoming_dingdong" -> base.copy(phase = AppPhase.HOME, role = AppRole.CHILD, incoming = IncomingUi(senderName = "엄마", kind = IncomingKind.DING_DONG))
            "incoming_voice" -> base.copy(phase = AppPhase.HOME, role = AppRole.CHILD, incoming = IncomingUi(senderName = "엄마", kind = IncomingKind.VOICE_MESSAGE))
            "incoming_siren" -> base.copy(phase = AppPhase.HOME, role = AppRole.CHILD, incoming = IncomingUi(senderName = "엄마", kind = IncomingKind.SIREN))
            "settings" -> base.copy(phase = AppPhase.HOME, role = AppRole.PARENT, route = AppRoute.SETTINGS)
            "settings_notification_denied" -> base.copy(phase = AppPhase.HOME, role = AppRole.PARENT,
                route = AppRoute.SETTINGS, notificationStatus = "차단됨")
            "settings_notification_allowed" -> base.copy(phase = AppPhase.HOME, role = AppRole.PARENT,
                route = AppRoute.SETTINGS, notificationStatus = "허용됨")
            "settings_remote_configured" -> base.copy(phase = AppPhase.HOME, role = AppRole.PARENT,
                route = AppRoute.SETTINGS, pushStatus = "등록됨", serverStatus = "https://button.example.com")
            "global_error" -> base.copy(phase = AppPhase.HOME, role = AppRole.PARENT, errorMessage = "호출을 보내지 못했어요.")
            else -> _uiState.value
        }
        _uiState.value = fixture.copy(fixtureId = id)
    }

    private fun senderName() = _uiState.value.displayName.ifBlank { "가족" }

    private fun fixtureBase(): AppUiState {
        val invite = InviteUi("12345678-1234-1234-1234-123456789abc", "우리 가족", "0123456789abcdef0123456789abcdef")
        return AppUiState(spaceName = "우리 가족", displayName = "엄마", invite = invite,
            transportStatus = TransportUiStatus.CONNECTED, connectedCount = 1,
            members = listOf(
                PresenceUi("current", "엄마", AppRole.PARENT, true),
                PresenceUi(FIXTURE_CHILD_ID, "첫째", AppRole.CHILD, false),
            ))
    }

    private fun fixtureHistory(): List<CallHistoryEntry> = listOf(
        CallHistoryEntry(
            id = UUID.fromString("33333333-3333-3333-3333-333333333333"),
            spaceID = UUID.fromString("12345678-1234-1234-1234-123456789abc"),
            kind = CallEvent.Kind.Siren,
            direction = CallHistoryEntry.Direction.RECEIVED,
            counterpartName = "첫째",
            date = Instant.parse("2026-08-21T06:05:00Z"),
        ),
        CallHistoryEntry(
            id = UUID.fromString("11111111-1111-1111-1111-111111111111"),
            spaceID = UUID.fromString("12345678-1234-1234-1234-123456789abc"),
            kind = CallEvent.Kind.DingDong,
            direction = CallHistoryEntry.Direction.SENT,
            counterpartName = "첫째",
            date = Instant.parse("2026-08-21T06:00:00Z"),
            acknowledgedBy = listOf("첫째"),
        ),
        CallHistoryEntry(
            id = UUID.fromString("22222222-2222-2222-2222-222222222222"),
            spaceID = UUID.fromString("12345678-1234-1234-1234-123456789abc"),
            kind = CallEvent.Kind.QuietAlert,
            direction = CallHistoryEntry.Direction.RECEIVED,
            counterpartName = "첫째",
            date = Instant.parse("2026-08-21T05:55:00Z"),
        ),
    )

    private fun loadState(): AppUiState {
        val state = AppStateCoder.decode(prefs.getString("state", null))
        val invite = state.invite ?: return state
        val members = state.members.map { member ->
            if (!member.isCurrentDevice) member else currentPresence(
                invite.spaceId,
                member.name,
                member.role ?: state.role ?: return@map member,
            )
        }
        val rooms = state.rooms.map { room -> roomWithMute(room) }
        val own = members.firstOrNull { it.isCurrentDevice }
        return state.copy(
            callHistory = historyFor(invite.spaceId),
            members = own?.let { membersForSpace(invite.spaceId, it) }.orEmpty(),
            rooms = rooms,
        )
    }

    private fun roomWithMute(room: SavedRoomUi): SavedRoomUi {
        val mute = runCatching { UUID.fromString(room.invite.spaceId) }.getOrNull()
            ?.let(notificationMuteStore::state)
            ?: return room
        return room.copy(
            notificationsMuted = mute.muted,
            notificationMuteSyncStatus = mute.syncStatus,
        )
    }

    private fun currentPresence(spaceID: String?, name: String, role: AppRole): PresenceUi {
        val mute = spaceID?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?.let(notificationMuteStore::state)
            ?: SpaceNotificationMuteState(false, NotificationMuteSyncStatus.SYNCED)
        return PresenceUi(
            id = "current",
            name = name,
            role = role,
            isCurrentDevice = true,
            notificationsMuted = mute.muted,
            notificationMuteSyncStatus = mute.syncStatus,
            isLiveNearby = true,
        )
    }

    private fun membersForSpace(spaceID: String?, current: PresenceUi): List<PresenceUi> {
        val parsed = spaceID?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return listOf(current)
        return mergeDurablePresence(current, knownMemberStore.members(parsed), emptySet())
    }

    private fun persist() {
        val s = _uiState.value
        if (s.fixtureId != null) return
        val raw = AppStateCoder.encode(s) ?: return
        prefs.edit().putString("state", raw).apply()
    }

    private fun prefixCodePoints(value: String, maximum: Int): String {
        val count = value.codePointCount(0, value.length)
        if (count <= maximum) return value
        return value.substring(0, value.offsetByCodePoints(0, maximum))
    }

    private fun demoEcho(eventID: String, kind: IncomingKind, voice: ByteArray? = null) {
        viewModelScope.launch {
            delay(1_200)
            presentIncoming(IncomingUi(
                id = eventID,
                senderName = senderName(),
                kind = kind,
                voiceData = voice,
            ))
        }
    }

    private fun sendCall(
        kind: IncomingKind,
        voice: ByteArray? = null,
        onSuccess: () -> Unit = {},
        onFailure: () -> Unit = {},
    ) {
        if (_uiState.value.role == AppRole.CHILD) {
            if (!sendCooldown.beginIfAvailable()) {
                showError("잠시만요. ${sendCooldown.remainingSeconds()}초 뒤에 다시 보낼 수 있어요.")
                return
            }
            startCooldownUpdates()
        }
        val state = _uiState.value
        val context = outboundContext(state) ?: run {
            showError("먼저 가족 공간을 선택해 주세요.")
            onFailure()
            return
        }
        val targets = state.members.filter { it.id in state.selectedTargetIDs && !it.isCurrentDevice }
        val intendedRecipientCount = if (targets.isEmpty()) state.members.count { !it.isCurrentDevice } else targets.size
        if (state.isDemoMode) {
            val eventID = UUID.randomUUID().toString()
            if (targets.isEmpty()) demoEcho(eventID, kind, voice)
            recordSentCall(eventID, context.space.id, kind, voice, targets, intendedRecipientCount)
            onSuccess()
        } else {
            hardware.send(
                context = context,
                kind = kind,
                voice = voice,
                targetIDs = targets.mapTo(linkedSetOf()) { it.id },
                onError = { message -> showError(message); onFailure() },
                onSent = { eventID, quietlyQueued ->
                    recordSentCall(
                        eventID,
                        context.space.id,
                        kind,
                        voice,
                        targets,
                        intendedRecipientCount,
                        quietlyQueued,
                    )
                    onSuccess()
                },
            )
        }
    }

    private fun outboundContext(state: AppUiState = _uiState.value): OutboundContext? {
        val invite = state.invite ?: return null
        val role = when (state.role) {
            AppRole.PARENT -> FamilyRole.Parent
            AppRole.CHILD -> FamilyRole.Child
            AppRole.GENERAL -> FamilyRole.General
            null -> return null
        }
        val space = runCatching {
            FamilySpace(UUID.fromString(invite.spaceId), invite.spaceName, invite.secret)
        }.getOrNull() ?: return null
        return OutboundContext(space, state.displayName.ifBlank { "가족" }, role)
    }

    private fun recordSentCall(
        eventID: String,
        spaceID: UUID,
        kind: IncomingKind,
        voice: ByteArray?,
        targets: List<PresenceUi>,
        intendedRecipientCount: Int,
        quietlyQueued: Boolean = false,
    ) {
        val parsedID = runCatching { UUID.fromString(eventID) }.getOrNull() ?: return
        pruneSentCallIDs()
        sentCallIDs[eventID] = System.currentTimeMillis()
        val targetName = targets.map { it.name }.takeIf { it.isNotEmpty() }?.joinToString(", ")
        val destination = targetName?.let { "${it}에게" } ?: "모두에게"
        val title = when (kind) {
            IncomingKind.QUIET_ALERT -> "톡톡"
            IncomingKind.SIREN -> "사이렌 호출"
            IncomingKind.DING_DONG -> "띵동"
            IncomingKind.VOICE_MESSAGE -> "음성"
        }
        historyStore.recordSent(
            id = parsedID,
            spaceID = spaceID,
            kind = kind.toCallKind(),
            targetName = targetName,
            date = Instant.now(),
            voiceData = voice,
            intendedRecipientCount = intendedRecipientCount,
        )
        _uiState.update {
            val particle = "을"
            if (currentSpaceID() == spaceID) {
                it.copy(callActivity = CallActivityUi(
                    CallActivityKind.SENT,
                    if (quietlyQueued) {
                        "$destination $title$particle 전송 대기 중이에요. 상대 기기가 확인하면 기록에 표시해요."
                    } else "$destination $title$particle 보냈어요.",
                ), callHistory = historyForCurrentSpace())
            } else {
                it.copy(callHistory = historyForCurrentSpace())
            }
        }
    }

    private fun koreanSubject(name: String): String {
        val last = name.lastOrNull() ?: return "가족이"
        val particle = if (last in '\uAC00'..'\uD7A3' && (last.code - 0xAC00) % 28 != 0) "이" else "가"
        return "$name$particle"
    }

    private fun startCooldownUpdates() {
        cooldownJob?.cancel()
        cooldownJob = viewModelScope.launch {
            while (true) {
                val remaining = sendCooldown.remainingSeconds()
                _uiState.update { it.copy(sendCooldownRemainingSeconds = remaining) }
                if (remaining <= 0) break
                delay(250)
            }
        }
    }

    private fun IncomingKind.toCallKind(): CallEvent.Kind = when (this) {
        IncomingKind.QUIET_ALERT -> CallEvent.Kind.QuietAlert
        IncomingKind.SIREN -> CallEvent.Kind.Siren
        IncomingKind.DING_DONG -> CallEvent.Kind.DingDong
        IncomingKind.VOICE_MESSAGE -> CallEvent.Kind.VoiceMessage
    }

    private fun currentSpaceID(): UUID? = _uiState.value.invite?.spaceId
        ?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    private fun historyForCurrentSpace(): List<CallHistoryEntry> = currentSpaceID()?.let { spaceID ->
        historyStore.entries.filter { it.spaceID == spaceID }
    }.orEmpty()

    private fun historyFor(spaceID: String): List<CallHistoryEntry> = runCatching { UUID.fromString(spaceID) }
        .getOrNull()
        ?.let { parsed -> historyStore.entries.filter { it.spaceID == parsed } }
        .orEmpty()

    private fun pruneSentCallIDs() {
        val cutoff = System.currentTimeMillis() - 600_000
        sentCallIDs.entries.removeAll { it.value < cutoff }
    }

    private fun cancelTransientWork() {
        quietHoldJob?.cancel()
        quietHoldJob = null
        voiceLimitJob?.cancel()
        voiceLimitJob = null
        incomingDismissJob?.cancel()
        incomingDismissJob = null
        pendingVoiceSpaceID = null
        voiceSendPending = false
        pendingVoiceData = null
        hardware.stopVoiceRecording()
        hardware.stopPlayback()
        _uiState.update {
            it.copy(
                incoming = null,
                quietHoldTriggered = false,
                quietHoldRemainingSeconds = 0,
                showVoice = false,
            )
        }
    }

    private fun clearLivePresenceTracking() {
        memberExpiryJobs.values.forEach(Job::cancel)
        memberExpiryJobs.clear()
        localMemberIDs.clear()
        remoteMemberIDs.clear()
    }

    override fun onCleared() {
        visibleRefreshJob?.cancel()
        refreshTimeoutJob?.cancel()
        super.onCleared()
    }
}

object AppStateCoder {
    fun encode(state: AppUiState): String? {
        if (state.fixtureId != null) return null
        val invite = state.invite ?: return null
        val json = JSONObject()
            .put("spaceName", state.spaceName)
            .put("displayName", state.displayName)
            .put("spaceId", invite.spaceId)
            .put("secret", invite.secret)
            .put("role", state.role?.name.orEmpty())
            .put("demo", state.isDemoMode)
            .put("rooms", JSONArray().also { array ->
                state.rooms.forEach { room ->
                    array.put(JSONObject()
                        .put("spaceName", room.invite.spaceName)
                        .put("spaceId", room.invite.spaceId)
                        .put("secret", room.invite.secret)
                        .put("displayName", room.displayName)
                        .put("role", room.role?.name.orEmpty()))
                }
            })
        return json.toString()
    }

    fun decode(rawJson: String?): AppUiState {
        if (rawJson.isNullOrBlank()) return AppUiState()
        return runCatching {
            val json = JSONObject(rawJson)
            val spaceName = json.optString("spaceName")
            if (spaceName.isBlank()) return AppUiState()
            val legacyRole = json.optString("role").takeIf { it.isNotBlank() }
                ?.let { runCatching { AppRole.valueOf(it) }.getOrNull() }
            val legacyInvite = storedInvite(
                json.getString("spaceId"),
                spaceName,
                json.getString("secret"),
            ) ?: return AppUiState()
            val legacyDisplayName = json.optString("displayName")
            val decodedRooms = buildList {
                val array = json.optJSONArray("rooms")
                if (array != null) for (index in 0 until array.length()) {
                    val room = array.optJSONObject(index) ?: continue
                    storedRoom(room)?.let(::add)
                }
                if (none {
                        it.invite.spaceId == legacyInvite.spaceId &&
                            it.invite.secret == legacyInvite.secret
                    }
                ) {
                    add(SavedRoomUi(legacyInvite, legacyDisplayName, legacyRole))
                }
            }
            val rooms = decodedRooms.groupBy { it.invite.spaceId }.values.map { candidates ->
                candidates.firstOrNull {
                    it.invite.spaceId == legacyInvite.spaceId && it.invite.secret == legacyInvite.secret
                } ?: candidates.first()
            }
            val active = rooms.firstOrNull {
                it.invite.spaceId == legacyInvite.spaceId && it.invite.secret == legacyInvite.secret
            } ?: rooms.first()
            val role = active.role
            val invite = active.invite
            val displayName = active.displayName
            AppUiState(
                phase = if (role == null) AppPhase.ROLE_SELECTION else AppPhase.HOME,
                role = role,
                spaceName = invite.spaceName,
                displayName = displayName,
                invite = invite,
                rooms = rooms,
                isDemoMode = json.optBoolean("demo"),
                transportStatus = if (json.optBoolean("demo")) TransportUiStatus.DEMO else TransportUiStatus.SEARCHING,
                members = if (role == null) emptyList() else listOf(PresenceUi("current", displayName, role, true)),
            )
        }.getOrElse { AppUiState() }
    }

    private fun storedRoom(json: JSONObject): SavedRoomUi? = runCatching {
        val invite = storedInvite(
            json.getString("spaceId"),
            json.getString("spaceName"),
            json.getString("secret"),
        ) ?: return null
        val role = json.optString("role").takeIf { it.isNotBlank() }
            ?.let { AppRole.valueOf(it) }
        SavedRoomUi(invite, json.optString("displayName"), role)
    }.getOrNull()

    private fun storedInvite(spaceID: String, name: String, secret: String): InviteUi? {
        val normalizedName = name.trim()
        if (normalizedName.isEmpty() || normalizedName.codePointCount(0, normalizedName.length) > QRInvite.MAX_NAME_LENGTH) {
            return null
        }
        val id = runCatching { UUID.fromString(spaceID) }.getOrNull() ?: return null
        if (!QRInvite.isValidSecret(secret)) return null
        return InviteUi(id.toString(), normalizedName, secret)
    }
}

private const val FIXTURE_CHILD_ID = "87654321-4321-4321-4321-cba987654321"
