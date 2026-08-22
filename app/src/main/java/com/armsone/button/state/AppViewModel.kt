package com.armsone.button.state

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.armsone.button.data.CallHistoryEntry
import com.armsone.button.data.CallHistoryStore
import com.armsone.button.data.PendingVoiceStore
import com.armsone.button.model.CallEvent
import com.armsone.button.model.FamilySpace
import com.armsone.button.model.QRInvite
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
enum class AppRole { PARENT, CHILD }
enum class AppRoute { WELCOME, CREATE_SPACE, JOIN_SPACE, SETTINGS }
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
)

data class InviteUi(val spaceId: String, val spaceName: String, val secret: String) {
    val url: String
        get() = QRInvite(UUID.fromString(spaceId), spaceName, secret).urlString
}

data class SavedRoomUi(
    val invite: InviteUi,
    val displayName: String,
    val role: AppRole?,
)

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
    val selectedTargetID: String? = null,
    val callActivity: CallActivityUi? = null,
    val callHistory: List<CallHistoryEntry> = emptyList(),
    val errorMessage: String? = null,
    val quietHoldTriggered: Boolean = false,
    val quietHoldRemainingSeconds: Int = 0,
    val sendCooldownRemainingSeconds: Int = 0,
    val notificationStatus: String = "허용 필요",
    val pushStatus: String = "요청하지 않음",
    val serverStatus: String = "구성되지 않음 (오프라인)",
)

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
        kind: IncomingKind,
        voice: ByteArray? = null,
        targetID: String? = null,
        onError: (String) -> Unit = {},
        onSent: (String) -> Unit = {},
    ) = Unit
    fun acknowledge(eventId: String, targetID: String? = null) = Unit
    fun requestNotificationPermission(onStatus: (String) -> Unit) = Unit
    fun enableRemoteNotifications(onStatus: (String) -> Unit) = Unit
    fun openNotificationSettings() = Unit
    fun openMicrophoneSettings() = Unit
    fun share(text: String) = Unit
    fun notificationStatus(): String = "허용 필요"
    fun pushStatus(): String = "요청하지 않음"
    fun serverStatus(): String = "구성되지 않음 (오프라인)"
    fun onForeground() = Unit
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
    private val _uiState = MutableStateFlow(loadState())
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()

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
            val room = SavedRoomUi(invite, member, null)
            it.copy(phase = AppPhase.ROLE_SELECTION, route = AppRoute.WELCOME,
            spaceName = invite.spaceName, displayName = member, invite = invite,
            role = null, rooms = it.rooms.filterNot { saved -> saved.invite.spaceId == invite.spaceId } + room,
            transportStatus = TransportUiStatus.SEARCHING,
            callHistory = historyFor(invite.spaceId))
        }
        persist()
    }

    fun selectRole(role: AppRole) {
        if (role == AppRole.PARENT) {
            cooldownJob?.cancel()
            sendCooldown.reset()
        }
        localMemberIDs.clear()
        remoteMemberIDs.clear()
        _uiState.update {
            val own = PresenceUi("current", it.displayName.ifEmpty { "이 기기" }, role, true)
            it.copy(
                phase = AppPhase.HOME,
                role = role,
                members = listOf(own),
                rooms = it.rooms.map { room ->
                    if (room.invite.spaceId == it.invite?.spaceId) {
                        room.copy(displayName = it.displayName, role = role)
                    } else room
                },
                sendCooldownRemainingSeconds = if (role == AppRole.PARENT) 0 else it.sendCooldownRemainingSeconds,
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
        hardware.beginVoiceRecording(15, { state ->
            _uiState.update { it.copy(voiceState = state) }
        }) { data -> finishVoice(data) }
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

    private fun finishVoice(data: ByteArray?) {
        voiceLimitJob?.cancel()
        if (data == null || data.isEmpty()) {
            pendingVoiceData = null
            _uiState.update { it.copy(voiceState = VoiceState.IDLE) }
            return
        }
        pendingVoiceData = data
        _uiState.update { it.copy(voiceState = VoiceState.READY) }
    }

    fun confirmVoiceSend() {
        val data = pendingVoiceData ?: return
        pendingVoiceData = null
        sendCall(IncomingKind.VOICE_MESSAGE, data)
        _uiState.update { it.copy(voiceState = VoiceState.SENT) }
        viewModelScope.launch {
            delay(2_000)
            _uiState.update { it.copy(voiceState = VoiceState.IDLE) }
        }
    }

    fun discardVoice() {
        pendingVoiceData = null
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
            hardware.acknowledge(event.id, event.senderID)
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
        localMemberIDs.clear()
        remoteMemberIDs.clear()
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
        if (room.invite.spaceId == _uiState.value.invite?.spaceId) return
        cancelTransientWork()
        localMemberIDs.clear()
        remoteMemberIDs.clear()
        val own = room.role?.let { listOf(PresenceUi("current", room.displayName, it, true)) }.orEmpty()
        _uiState.update {
            it.copy(
                phase = if (room.role == null) AppPhase.ROLE_SELECTION else AppPhase.HOME,
                route = AppRoute.WELCOME,
                role = room.role,
                spaceName = room.invite.spaceName,
                displayName = room.displayName,
                invite = room.invite,
                members = own,
                selectedTargetID = null,
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
        val leaving = currentSpaceID()
        if (leaving != null) historyStore.clear(leaving)
        pendingVoiceStore.clear()
        val remaining = _uiState.value.rooms.filterNot { it.invite.spaceId == leaving?.toString() }
        if (remaining.isEmpty()) {
            prefs.edit().clear().apply()
            _uiState.value = AppUiState()
        } else {
            _uiState.update { it.copy(rooms = remaining) }
            switchRoom(remaining.first().invite.spaceId)
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

    fun updateTransport(status: TransportUiStatus, connectedCount: Int = 0) = _uiState.update {
        if (it.isDemoMode) it else it.copy(transportStatus = status, connectedCount = connectedCount)
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
        if (member.isCurrentDevice) return
        _uiState.update {
            it.copy(selectedTargetID = if (it.selectedTargetID == member.id) null else member.id)
        }
    }

    fun updateRemoteMember(id: String, name: String, role: AppRole?) {
        if (id == "current") return
        localMemberIDs += id
        _uiState.update { state ->
            val own = state.members.filter { it.isCurrentDevice }
            val remote = state.members.filterNot { it.isCurrentDevice || it.id == id } +
                PresenceUi(id, name.ifBlank { "가족" }, role, false)
            state.copy(members = own + remote.sortedBy { it.name })
        }
        memberExpiryJobs.remove(id)?.cancel()
        memberExpiryJobs[id] = viewModelScope.launch {
            delay(600_000)
            localMemberIDs -= id
            _uiState.update {
                it.copy(
                    members = if (id in remoteMemberIDs) it.members
                        else it.members.filterNot { member -> member.id == id },
                    selectedTargetID = it.selectedTargetID?.takeUnless { selected ->
                        selected == id && id !in remoteMemberIDs
                    },
                )
            }
            memberExpiryJobs.remove(id)
        }
    }

    fun replaceRemoteMembers(members: List<PresenceUi>) {
        remoteMemberIDs.clear()
        remoteMemberIDs += members.map { it.id }
        _uiState.update { state ->
            val byID = state.members.associateByTo(linkedMapOf()) { it.id }
            byID.entries.removeAll { (id, member) ->
                !member.isCurrentDevice && id !in localMemberIDs && id !in remoteMemberIDs
            }
            members.forEach { member ->
                val id = member.id
                val existing = byID[id]
                byID[id] = member.copy(name = member.name.ifBlank { "가족" })
                    .let { remote -> if (existing != null && id in localMemberIDs) {
                        remote.copy(name = existing.name, role = existing.role)
                    } else remote }
            }
            val visible = byID.values.sortedWith(compareByDescending<PresenceUi> { it.isCurrentDevice }
                .thenBy { it.name })
            state.copy(
                members = visible,
                selectedTargetID = state.selectedTargetID?.takeIf { selected ->
                    visible.any { it.id == selected && !it.isCurrentDevice }
                },
            )
        }
    }

    fun onForeground() {
        if (_uiState.value.fixtureId != null) return
        hardware.onForeground()
        _uiState.update {
            it.copy(
                notificationStatus = hardware.notificationStatus(),
                pushStatus = hardware.pushStatus(),
                serverStatus = hardware.serverStatus(),
                callHistory = historyForCurrentSpace(),
                voiceState = if (it.voiceState == VoiceState.DENIED) VoiceState.IDLE else it.voiceState,
            )
        }
        pendingVoiceStore.take()?.let { eventID ->
            val activeSpaceID = currentSpaceID()
            historyStore.entries.firstOrNull {
                it.id == eventID &&
                    it.spaceID == activeSpaceID &&
                    it.direction == CallHistoryEntry.Direction.RECEIVED &&
                    Instant.now().epochSecond - it.date.epochSecond <= 600
            }?.let(historyStore::voiceData)?.let(hardware::playVoice)
        }
    }

    fun onBackground() = cancelTransientWork()

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
                selectedTargetID = FIXTURE_CHILD_ID)
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
                selectedTargetID = FIXTURE_CHILD_ID)
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

    private fun loadState(): AppUiState = runCatching {
        val json = JSONObject(prefs.getString("state", null) ?: return AppUiState())
        val spaceName = json.optString("spaceName")
        if (spaceName.isBlank()) return AppUiState()
        val legacyRole = json.optString("role").takeIf { it.isNotBlank() }?.let(AppRole::valueOf)
        val legacyInvite = InviteUi(json.getString("spaceId"), spaceName, json.getString("secret"))
        val legacyDisplayName = json.optString("displayName")
        val rooms = buildList {
            val array = json.optJSONArray("rooms")
            if (array != null) for (index in 0 until array.length()) {
                val room = array.optJSONObject(index) ?: continue
                val roomRole = room.optString("role").takeIf { it.isNotBlank() }?.let(AppRole::valueOf)
                add(SavedRoomUi(
                    InviteUi(room.getString("spaceId"), room.getString("spaceName"), room.getString("secret")),
                    room.optString("displayName"), roomRole,
                ))
            }
            if (none { it.invite.spaceId == legacyInvite.spaceId }) {
                add(SavedRoomUi(legacyInvite, legacyDisplayName, legacyRole))
            }
        }
        val active = rooms.firstOrNull { it.invite.spaceId == legacyInvite.spaceId } ?: rooms.first()
        val role = active.role
        val invite = active.invite
        val displayName = active.displayName
        AppUiState(phase = if (role == null) AppPhase.ROLE_SELECTION else AppPhase.HOME,
            role = role, spaceName = spaceName, displayName = displayName, invite = invite,
            rooms = rooms,
            isDemoMode = json.optBoolean("demo"),
            transportStatus = if (json.optBoolean("demo")) TransportUiStatus.DEMO else TransportUiStatus.SEARCHING,
            members = if (role == null) emptyList() else listOf(PresenceUi("current", displayName, role, true)),
            callHistory = historyFor(invite.spaceId))
    }.getOrElse { AppUiState() }

    private fun persist() {
        val s = _uiState.value
        if (s.fixtureId != null) return
        val invite = s.invite ?: return
        val json = JSONObject().put("spaceName", s.spaceName).put("displayName", s.displayName)
            .put("spaceId", invite.spaceId).put("secret", invite.secret)
            .put("role", s.role?.name.orEmpty()).put("demo", s.isDemoMode)
            .put("rooms", JSONArray().also { array ->
                s.rooms.forEach { room ->
                    array.put(JSONObject()
                        .put("spaceName", room.invite.spaceName)
                        .put("spaceId", room.invite.spaceId)
                        .put("secret", room.invite.secret)
                        .put("displayName", room.displayName)
                        .put("role", room.role?.name.orEmpty()))
                }
            })
        prefs.edit().putString("state", json.toString()).apply()
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

    private fun sendCall(kind: IncomingKind, voice: ByteArray? = null) {
        if (_uiState.value.role == AppRole.CHILD) {
            if (!sendCooldown.beginIfAvailable()) {
                showError("잠시만요. ${sendCooldown.remainingSeconds()}초 뒤에 다시 보낼 수 있어요.")
                return
            }
            startCooldownUpdates()
        }
        val state = _uiState.value
        val target = state.members.firstOrNull { it.id == state.selectedTargetID && !it.isCurrentDevice }
        val intendedRecipientCount = if (target == null) state.members.count { !it.isCurrentDevice } else 1
        if (state.isDemoMode) {
            val eventID = UUID.randomUUID().toString()
            if (target == null) demoEcho(eventID, kind, voice)
            recordSentCall(eventID, kind, voice, target, intendedRecipientCount)
        } else {
            hardware.send(
                kind = kind,
                voice = voice,
                targetID = target?.id,
                onError = ::showError,
                onSent = { eventID -> recordSentCall(eventID, kind, voice, target, intendedRecipientCount) },
            )
        }
    }

    private fun recordSentCall(
        eventID: String,
        kind: IncomingKind,
        voice: ByteArray?,
        target: PresenceUi?,
        intendedRecipientCount: Int,
    ) {
        val parsedID = runCatching { UUID.fromString(eventID) }.getOrNull() ?: return
        pruneSentCallIDs()
        sentCallIDs[eventID] = System.currentTimeMillis()
        val destination = target?.name?.let { "${it}에게" } ?: "모두에게"
        val title = when (kind) {
            IncomingKind.QUIET_ALERT -> "톡톡"
            IncomingKind.SIREN -> "사이렌 호출"
            IncomingKind.DING_DONG -> "띵동"
            IncomingKind.VOICE_MESSAGE -> "소리"
        }
        currentSpaceID()?.let { spaceID ->
            historyStore.recordSent(
                id = parsedID,
                spaceID = spaceID,
                kind = kind.toCallKind(),
                targetName = target?.name,
                date = Instant.now(),
                voiceData = voice,
                intendedRecipientCount = intendedRecipientCount,
            )
        }
        _uiState.update {
            val particle = if (kind == IncomingKind.VOICE_MESSAGE) "를" else "을"
            it.copy(callActivity = CallActivityUi(
                CallActivityKind.SENT,
                "$destination $title$particle 보냈어요.",
            ), callHistory = historyForCurrentSpace())
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

}

private const val FIXTURE_CHILD_ID = "87654321-4321-4321-4321-cba987654321"
