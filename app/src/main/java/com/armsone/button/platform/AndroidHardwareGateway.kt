package com.armsone.button.platform

import android.Manifest
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import com.armsone.button.R
import com.armsone.button.data.BackendConfiguration
import com.armsone.button.data.CallHistoryStore
import com.armsone.button.data.HttpBackendClient
import com.armsone.button.data.PendingVoiceStore
import com.armsone.button.model.CallEvent
import com.armsone.button.model.FamilyRole
import com.armsone.button.model.FamilySpace
import com.armsone.button.push.DeviceIdentity
import com.armsone.button.push.FirebasePushTokenProvider
import com.armsone.button.push.PushMembership
import com.armsone.button.push.PushRegistrationManager
import com.armsone.button.push.RemoteEventRouter
import com.armsone.button.state.AppHardwareGateway
import com.armsone.button.state.AppPhase
import com.armsone.button.state.AppRole
import com.armsone.button.state.AppUiState
import com.armsone.button.state.IncomingKind
import com.armsone.button.state.IncomingUi
import com.armsone.button.state.PresenceUi
import com.armsone.button.state.TransportUiStatus
import com.armsone.button.state.VoiceState
import com.armsone.button.transport.AndroidBleTransport
import com.armsone.button.transport.TransportStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Connects the Compose state machine to Android hardware and the iOS-compatible transports. */
class AndroidHardwareGateway(private val activity: ComponentActivity) : AppHardwareGateway {
    var onIncoming: (IncomingUi) -> Unit = {}
    var onAcknowledge: (String, String?, String?) -> Unit = { _, _, _ -> }
    var onTransportStatus: (TransportUiStatus, Int) -> Unit = { _, _ -> }
    var onPresence: (String, String, AppRole?) -> Unit = { _, _, _ -> }
    var onMembers: (List<PresenceUi>) -> Unit = {}

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val transport = AndroidBleTransport(activity)
    private val sound = DingDongPlayer()
    private val voiceRecorder = VoiceRecorder(activity)
    private val voicePlayer = VoiceMessagePlayer(activity)
    private val flash = FlashAlertService(activity)
    private val notifications = NotificationHelper(
        activity,
        Uri.parse("android.resource://${activity.packageName}/${R.raw.dingdong3}"),
        Uri.parse("android.resource://${activity.packageName}/${R.raw.siren}"),
    )
    private val pendingVoiceStore = PendingVoiceStore(activity)
    private val backendConfiguration = BackendConfiguration.load(activity)
    private val backend = HttpBackendClient(backendConfiguration)
    private val prefs = activity.getSharedPreferences("button_hardware", Context.MODE_PRIVATE)
    private val deviceID: UUID = DeviceIdentity.loadOrCreate(activity)
    private val pushTokens = FirebasePushTokenProvider(activity)
    private val pushRegistration = PushRegistrationManager(activity, backend, pushTokens)

    private var activeSpace: FamilySpace? = null
    private var activeName = ""
    private var activeRole: AppRole? = null
    private var activeKey: String? = null
    private var presenceJob: Job? = null
    private var bluetoothPermissionRequested = false
    private var pendingNotificationStatus: ((String) -> Unit)? = null
    private var pendingVoiceState: ((VoiceState) -> Unit)? = null
    private var pendingVoiceFinished: ((ByteArray?) -> Unit)? = null
    private var pendingMicrophonePermission: ((Boolean) -> Unit)? = null
    private val seenEvents = mutableMapOf<UUID, Instant>()
    private var lastVoiceData: ByteArray? = null

    private val notificationPermissionLauncher = activity.registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        prefs.edit().putBoolean("notificationAsked", true).apply()
        pendingNotificationStatus?.invoke(if (granted) "허용됨" else "차단됨")
        pendingNotificationStatus = null
    }

    private val microphonePermissionLauncher = activity.registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        pendingMicrophonePermission?.invoke(granted)
        pendingMicrophonePermission = null
    }

    private val bluetoothPermissionLauncher = activity.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        bluetoothPermissionRequested = false
        if (grants.values.all { it }) {
            activeSpace?.let { transport.start(it, activeName.ifBlank { "가족" }) }
        } else {
            activeKey = null
            onTransportStatus(TransportUiStatus.IDLE, 0)
        }
    }

    init {
        RemoteEventRouter.attach(this) { event ->
            if (activeSpace?.id != event.spaceID ||
                !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            ) {
                false
            } else {
                activity.runOnUiThread { accept(event, relayOverBluetooth = false) }
                true
            }
        }
        transport.onStatusChange = { status ->
            val mapped = when (status) {
                TransportStatus.Idle -> TransportUiStatus.IDLE to 0
                TransportStatus.Searching -> TransportUiStatus.SEARCHING to 0
                is TransportStatus.Connected -> TransportUiStatus.CONNECTED to status.peerCount
                TransportStatus.Demo -> TransportUiStatus.DEMO to 0
            }
            activity.runOnUiThread { onTransportStatus(mapped.first, mapped.second) }
        }
        transport.onEvent = ::accept
        voiceRecorder.onStateChange = { state ->
            activity.runOnUiThread {
                when (state) {
                    VoiceRecorder.State.RequestingPermission -> pendingVoiceState?.invoke(VoiceState.REQUESTING_PERMISSION)
                    is VoiceRecorder.State.Recording -> pendingVoiceState?.invoke(VoiceState.RECORDING)
                    VoiceRecorder.State.Denied -> pendingVoiceState?.invoke(VoiceState.DENIED)
                    is VoiceRecorder.State.Recorded -> {
                        val data = voiceRecorder.recordedData
                        pendingVoiceFinished?.invoke(data)
                        pendingVoiceFinished = null
                        pendingVoiceState = null
                    }
                    VoiceRecorder.State.Idle -> Unit
                }
            }
        }
    }

    fun sync(state: AppUiState) {
        if (state.fixtureId != null) {
            stopActiveTransport()
            return
        }
        val savedMemberships = state.rooms.mapNotNull { room ->
            val roomRole = when (room.role) {
                AppRole.PARENT -> FamilyRole.Parent
                AppRole.CHILD -> FamilyRole.Child
                null -> return@mapNotNull null
            }
            val roomSpace = runCatching {
                FamilySpace(UUID.fromString(room.invite.spaceId), room.invite.spaceName, room.invite.secret)
            }.getOrNull() ?: return@mapNotNull null
            PushMembership(roomSpace, deviceID, room.displayName.ifBlank { "가족" }, roomRole)
        }
        if (savedMemberships.isEmpty()) pushRegistration.clearMembership()
        else {
            pushRegistration.updateMemberships(savedMemberships)
            scope.launch { pushRegistration.registerCurrentTokenIfAvailable() }
        }
        if (state.phase != AppPhase.HOME || state.invite == null || state.isDemoMode) {
            stopActiveTransport()
            return
        }
        activeName = state.displayName.ifBlank { "가족" }
        activeRole = state.role
        val invite = state.invite
        val key = "${invite.spaceId}|${invite.secret}"
        val space = runCatching {
            FamilySpace(UUID.fromString(invite.spaceId), invite.spaceName, invite.secret)
        }.getOrNull() ?: return
        if (state.role == null) return
        if (key == activeKey) return
        activeKey = key
        activeSpace = space
        refreshRemoteMembers(space)
        ensureBluetoothPermissions()
        if (hasBluetoothPermissions()) transport.start(space, activeName)
        presenceJob?.cancel()
        presenceJob = scope.launch {
            var ticks = 0
            while (isActive && activeKey == key) {
                val event = makeEvent(CallEvent.Kind.Presence)
                runCatching { transport.send(event) }
                delay(8_000)
                ticks += 1
                if (ticks % 8 == 0) refreshRemoteMembers(space)
            }
        }
    }

    override fun beginVoiceRecording(
        maxSeconds: Int,
        onState: (VoiceState) -> Unit,
        onFinished: (ByteArray?) -> Unit,
    ) {
        pendingVoiceState = onState
        pendingVoiceFinished = onFinished
        val granted = ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        voiceRecorder.beginPressHold(
            if (granted) VoiceRecorder.PermissionStatus.GRANTED else VoiceRecorder.PermissionStatus.NOT_DETERMINED,
        ) { callback ->
            pendingMicrophonePermission = callback
            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun stopVoiceRecording() = voiceRecorder.endPressHold()

    override fun playDingDong() = sound.play()

    override fun playSiren() = sound.playSiren()

    override fun playVoice(data: ByteArray) = voicePlayer.play(data)

    override fun stopPlayback() {
        voicePlayer.stop()
        sound.stop()
    }

    override fun send(
        kind: IncomingKind,
        voice: ByteArray?,
        targetIDs: Set<String>,
        onError: (String) -> Unit,
        onSent: (String) -> Unit,
    ) {
        val callKind = when (kind) {
            IncomingKind.QUIET_ALERT -> CallEvent.Kind.QuietAlert
            IncomingKind.SIREN -> CallEvent.Kind.Siren
            IncomingKind.DING_DONG -> CallEvent.Kind.DingDong
            IncomingKind.VOICE_MESSAGE -> CallEvent.Kind.VoiceMessage
        }
        val event = runCatching {
            val targets = targetIDs.map(UUID::fromString)
            makeEvent(
                callKind,
                voice,
                targetID = when (targets.size) {
                    0 -> null
                    1 -> targets.first()
                    else -> CallEvent.MULTI_TARGET_SENTINEL
                },
                targetIDs = targets.takeIf { it.size > 1 },
            )
        }.getOrElse {
            onError("전송에 실패했어요. (${it.message ?: "알 수 없는 오류"})")
            return
        }
        val reported = AtomicBoolean(false)
        fun reportSent() {
            if (reported.compareAndSet(false, true)) {
                activity.runOnUiThread { onSent(event.id.toString()) }
            }
        }
        val ble = runCatching { transport.send(event) }
        if (!backendConfiguration.isConfigured) {
            if (ble.isSuccess) reportSent()
            else onError(ble.exceptionOrNull()?.message ?: "전송에 실패했어요.")
            return
        }
        if (ble.isSuccess) reportSent()
        scope.launch {
            val remote = runCatching { backend.send(event, activeSpace?.secret.orEmpty()) }
            if (remote.isSuccess) {
                reportSent()
            } else if (ble.isFailure) {
                activity.runOnUiThread {
                    onError(remote.exceptionOrNull()?.message ?: ble.exceptionOrNull()?.message ?: "전송에 실패했어요.")
                }
            }
        }
    }

    override fun acknowledge(eventId: String, targetID: String?) {
        val original = runCatching { UUID.fromString(eventId) }.getOrNull() ?: return
        val target = targetID?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val event = runCatching {
            makeEvent(CallEvent.Kind.Acknowledge, targetID = target).also { it.ackFor = original }
        }.getOrNull()
            ?: return
        runCatching { transport.send(event) }
        if (backendConfiguration.isConfigured) scope.launch {
            runCatching { backend.send(event, activeSpace?.secret.orEmpty()) }
        }
    }

    override fun requestNotificationPermission(onStatus: (String) -> Unit) {
        if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
                activity,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            onStatus("허용됨")
            return
        }
        pendingNotificationStatus = onStatus
        prefs.edit().putBoolean("notificationAsked", true).apply()
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun notificationStatus(): String = if (
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
            activity,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    ) "허용됨" else if (prefs.getBoolean("notificationAsked", false)) "차단됨" else "허용 필요"

    override fun pushStatus(): String = pushRegistration.statusDescription()

    override fun serverStatus(): String =
        backendConfiguration.baseUrl?.takeIf { it.isNotBlank() } ?: "구성되지 않음 (오프라인)"

    override fun onForeground() {
        notifications.clearDeliveredCalls()
        activeSpace?.let(::refreshRemoteMembers)
    }

    override fun enableRemoteNotifications(onStatus: (String) -> Unit) {
        scope.launch {
            val result = pushRegistration.requestTokenAndRegister()
            activity.runOnUiThread {
                onStatus(
                    result.fold(
                        onSuccess = { pushRegistration.statusDescription() },
                        onFailure = { "FCM 등록 실패: ${it.message ?: "알 수 없는 오류"}" },
                    ),
                )
            }
        }
    }

    override fun openNotificationSettings() {
        activity.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
        })
    }

    override fun openMicrophoneSettings() {
        activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${activity.packageName}")
        })
    }

    override fun share(text: String) {
        activity.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }, "초대 링크 공유"))
    }

    fun close() {
        RemoteEventRouter.detach(this)
        stopActiveTransport()
        voiceRecorder.reset()
        voicePlayer.stop()
        sound.close()
        flash.stop()
        scope.cancel()
    }

    private fun accept(event: CallEvent, relayOverBluetooth: Boolean = true) {
        val space = activeSpace ?: return
        if (event.spaceID != space.id || event.senderID == deviceID) return
        val now = Instant.now()
        synchronized(seenEvents) {
            seenEvents.entries.removeAll { it.value.isBefore(now.minusSeconds(600)) }
            if (seenEvents.putIfAbsent(event.id, now) != null) return
        }
        val memberID = (event.senderID ?: event.id).toString()
        val memberRole = when (event.senderRole) {
            FamilyRole.Parent -> AppRole.PARENT
            FamilyRole.Child -> AppRole.CHILD
            null -> null
        }
        activity.runOnUiThread { onPresence(memberID, event.senderName, memberRole) }

        // 대상이 아닌 기기도 징검다리 역할은 유지하고, 사용자 알림 효과만 건너뛴다.
        if (relayOverBluetooth && event.kind != CallEvent.Kind.VoiceMessage) {
            runCatching { transport.send(event) }
        }
        if (!event.isAddressedTo(deviceID)) return

        if (event.kind == CallEvent.Kind.QuietAlert ||
            event.kind == CallEvent.Kind.Siren ||
            event.kind == CallEvent.Kind.DingDong ||
            event.kind == CallEvent.Kind.VoiceMessage
        ) {
            CallHistoryStore(activity.applicationContext).recordReceived(event)
        }

        when (event.kind) {
            CallEvent.Kind.Acknowledge -> activity.runOnUiThread {
                onAcknowledge(event.senderName, event.ackFor?.toString(), event.senderID?.toString())
            }
            CallEvent.Kind.Presence -> Unit
            CallEvent.Kind.QuietAlert, CallEvent.Kind.Siren, CallEvent.Kind.DingDong, CallEvent.Kind.VoiceMessage -> {
                flash.flash()
                val inForeground = activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                if (inForeground && event.kind == CallEvent.Kind.Siren) sound.playSiren()
                if (inForeground && event.kind == CallEvent.Kind.DingDong) sound.play()
                if (inForeground && event.kind == CallEvent.Kind.VoiceMessage) {
                    event.voiceData?.let {
                        lastVoiceData = it
                        voicePlayer.play(it)
                    }
                }
                if (inForeground) {
                    val incoming = IncomingUi(
                        id = event.id.toString(),
                        senderName = event.senderName,
                        senderID = event.senderID?.toString(),
                        kind = when (event.kind) {
                            CallEvent.Kind.QuietAlert -> IncomingKind.QUIET_ALERT
                            CallEvent.Kind.Siren -> IncomingKind.SIREN
                            CallEvent.Kind.DingDong -> IncomingKind.DING_DONG
                            else -> IncomingKind.VOICE_MESSAGE
                        },
                        sentAt = event.sentAt,
                        timeLabel = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
                            .withLocale(Locale.getDefault())
                            .withZone(ZoneId.systemDefault())
                            .format(event.sentAt),
                        voiceData = event.voiceData,
                    )
                    activity.runOnUiThread { onIncoming(incoming) }
                } else {
                    if (event.kind == CallEvent.Kind.VoiceMessage) pendingVoiceStore.record(event.id)
                    notifications.notify(event)
                }
            }
        }
    }

    private fun makeEvent(
        kind: CallEvent.Kind,
        voice: ByteArray? = null,
        targetID: UUID? = null,
        targetIDs: List<UUID>? = null,
    ): CallEvent {
        val space = activeSpace ?: throw IllegalStateException("먼저 가족 공간에 참여해 주세요.")
        return CallEvent(
            kind = kind,
            spaceID = space.id,
            senderName = activeName.ifBlank { "가족" },
            senderID = deviceID,
            senderRole = when (activeRole) {
                AppRole.PARENT -> FamilyRole.Parent
                AppRole.CHILD -> FamilyRole.Child
                null -> null
            },
            targetID = targetID,
            targetIDs = targetIDs,
            voiceData = voice,
        )
    }

    private fun refreshRemoteMembers(space: FamilySpace) {
        if (!backendConfiguration.isConfigured) return
        scope.launch {
            val members = runCatching { backend.fetchMembers(space) }.getOrNull() ?: return@launch
            if (activeSpace?.id != space.id) return@launch
            val visible = members.filter { it.deviceID != deviceID }.map { member ->
                PresenceUi(
                    id = member.deviceID.toString(),
                    name = member.name,
                    role = if (member.role == FamilyRole.Parent) AppRole.PARENT else AppRole.CHILD,
                    isCurrentDevice = false,
                )
            }
            activity.runOnUiThread { onMembers(visible) }
        }
    }

    private fun ensureBluetoothPermissions() {
        if (hasBluetoothPermissions() || bluetoothPermissionRequested) return
        bluetoothPermissionRequested = true
        val required = if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        bluetoothPermissionLauncher.launch(required)
    }

    private fun stopActiveTransport() {
        presenceJob?.cancel()
        presenceJob = null
        if (activeKey != null) transport.stop()
        activeKey = null
        activeSpace = null
        activeName = ""
        activeRole = null
        synchronized(seenEvents) { seenEvents.clear() }
    }

    private fun hasBluetoothPermissions(): Boolean = if (Build.VERSION.SDK_INT >= 31) {
        listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
        ).all { ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED }
    } else {
        ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }
}
