package com.armsone.ourbutton.platform

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
import androidx.lifecycle.Observer
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.armsone.ourbutton.R
import com.armsone.ourbutton.data.BackendConfiguration
import com.armsone.ourbutton.data.BackendSendReceipt
import com.armsone.ourbutton.data.CallHistoryStore
import com.armsone.ourbutton.data.HttpBackendClient
import com.armsone.ourbutton.data.OutboundEventStore
import com.armsone.ourbutton.data.PendingVoiceStore
import com.armsone.ourbutton.model.CallEvent
import com.armsone.ourbutton.model.FamilyRole
import com.armsone.ourbutton.model.FamilySpace
import com.armsone.ourbutton.push.DeviceIdentity
import com.armsone.ourbutton.push.FirebasePushTokenProvider
import com.armsone.ourbutton.push.PushMembership
import com.armsone.ourbutton.push.PushRegistrationManager
import com.armsone.ourbutton.push.PushStore
import com.armsone.ourbutton.push.RemoteEventRouter
import com.armsone.ourbutton.push.NotificationMuteSyncStatus
import com.armsone.ourbutton.push.SpaceNotificationMuteState
import com.armsone.ourbutton.push.SpaceNotificationMuteStore
import com.armsone.ourbutton.push.SpaceNotificationMuteSync
import com.armsone.ourbutton.push.shouldSuppressAppOwnedAlert
import com.armsone.ourbutton.state.AppHardwareGateway
import com.armsone.ourbutton.state.AppPhase
import com.armsone.ourbutton.state.AppRole
import com.armsone.ourbutton.state.AppUiState
import com.armsone.ourbutton.state.IncomingKind
import com.armsone.ourbutton.state.IncomingUi
import com.armsone.ourbutton.state.OutboundContext
import com.armsone.ourbutton.state.PresenceUi
import com.armsone.ourbutton.state.TransportUiStatus
import com.armsone.ourbutton.state.VoiceState
import com.armsone.ourbutton.transport.AndroidBleTransport
import com.armsone.ourbutton.transport.TransportStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
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
    var onPresenceLost: (UUID, String) -> Unit = { _, _ -> }
    var onMembers: (List<PresenceUi>) -> Unit = {}
    var onNotificationMuteStatus: (UUID, SpaceNotificationMuteState) -> Unit = { _, _ -> }

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
    private val outboundEventStore = OutboundEventStore(activity)
    private val backendConfiguration = BackendConfiguration.load(activity)
    private val backend = HttpBackendClient(backendConfiguration)
    private val prefs = activity.getSharedPreferences("button_hardware", Context.MODE_PRIVATE)
    private val inboxPrefs = activity.getSharedPreferences("button_inbox", Context.MODE_PRIVATE)
    private val deviceID: UUID = DeviceIdentity.loadOrCreate(activity)
    private val pushTokens = FirebasePushTokenProvider(activity)
    private val pushRegistration = PushRegistrationManager(activity, backend, pushTokens)
    private val notificationMuteStore = SpaceNotificationMuteStore(activity)

    private var activeSpace: FamilySpace? = null
    private var activeName = ""
    private var activeRole: AppRole? = null
    private var activeKey: String? = null
    private var presenceJob: Job? = null
    private val memberRefreshGate = MemberRefreshGate()
    private var lastMemberRefreshRequestMillis = 0L
    private val inboxPollRunning = AtomicBoolean(false)
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
            if (status is TransportStatus.Connected &&
                activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            ) activeSpace?.let {
                refreshRemoteMembers(it, force = false)
                pollInbox(it)
                retryPendingOutbox(it)
            }
        }
        transport.onEvent = ::accept
        transport.onPeerDisconnected = { spaceID, memberID ->
            if (activeSpace?.id == spaceID) {
                activity.runOnUiThread { onPresenceLost(spaceID, memberID.toString()) }
            }
        }
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
                AppRole.GENERAL -> FamilyRole.General
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
            scope.launch {
                val syncResult = pushRegistration.syncMemberships()
                if (syncResult.isSuccess) {
                    activeSpace?.let {
                        refreshRemoteMembers(it, force = true)
                        pollInbox(it)
                        retryPendingOutbox(it)
                    }
                }
            }
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
        refreshRemoteMembers(space, force = true)
        pollInbox(space)
        retryPendingOutbox(space)
        ensureBluetoothPermissions()
        if (hasBluetoothPermissions()) transport.start(space, activeName)
        presenceJob?.cancel()
        presenceJob = scope.launch {
            while (isActive && activeKey == key) {
                val event = makeEvent(CallEvent.Kind.Presence)
                runCatching { transport.send(event) }
                delay(8_000)
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
        context: OutboundContext,
        kind: IncomingKind,
        voice: ByteArray?,
        targetIDs: Set<String>,
        onError: (String) -> Unit,
        onSent: (String, Boolean) -> Unit,
    ) {
        val callKind = when (kind) {
            IncomingKind.QUIET_ALERT -> CallEvent.Kind.QuietAlert
            IncomingKind.SIREN -> CallEvent.Kind.Siren
            IncomingKind.DING_DONG -> CallEvent.Kind.DingDong
            IncomingKind.VOICE_MESSAGE -> CallEvent.Kind.VoiceMessage
        }
        val event = runCatching {
            val targets = targetIDs.map(UUID::fromString)
            context.makeEvent(
                callKind,
                voice,
                targetID = when (targets.size) {
                    0 -> null
                    1 -> targets.first()
                    else -> CallEvent.MULTI_TARGET_SENTINEL
                },
                targetIDs = targets.takeIf { it.size > 1 },
            ).also { it.senderID = deviceID }
        }.getOrElse {
            onError("전송에 실패했어요. (${it.message ?: "알 수 없는 오류"})")
            return
        }
        val reported = AtomicBoolean(false)
        fun reportSent(quietlyQueued: Boolean = false) {
            if (reported.compareAndSet(false, true)) {
                activity.runOnUiThread { onSent(event.id.toString(), quietlyQueued) }
            }
        }
        val stored = runCatching { outboundEventStore.put(event) }
        if (stored.isFailure) {
            onError("전송 내용을 안전하게 보관하지 못했어요. 저장 공간을 확인해 주세요.")
            return
        }
        // Storage is confirmed, but delivery is not. ACK evidence will advance history later.
        reportSent(quietlyQueued = true)
        runCatching { transport.send(event) }
        if (!backendConfiguration.isConfigured) {
            return
        }
        scope.launch {
            val remote = sendRemoteWithRetry(event, context.space.secret)
            if (remote.isSuccess) {
                outboundEventStore.remove(event.id)
            }
        }
    }

    override fun acknowledge(context: OutboundContext, eventId: String, targetID: String?) {
        val original = runCatching { UUID.fromString(eventId) }.getOrNull() ?: return
        val target = targetID?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val event = runCatching {
            context.makeEvent(CallEvent.Kind.Acknowledge, targetID = target).also {
                it.senderID = deviceID
                it.ackFor = original
            }
        }.getOrNull()
            ?: return
        runCatching { transport.send(event) }
        if (backendConfiguration.isConfigured) scope.launch {
            sendRemoteWithRetry(event, context.space.secret)
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
        PushStore(activity).memberships.forEach { membership ->
            if (notificationMuteStore.state(membership.space.id).syncStatus !=
                NotificationMuteSyncStatus.SYNCED
            ) {
                queueNotificationMuteSync(membership.space.id)
            }
        }
    }

    override fun refreshHome(onFinished: () -> Unit) {
        val space = activeSpace
        if (space == null) {
            activity.runOnUiThread(onFinished)
            return
        }
        transport.refreshConnections()
        scope.launch {
            pushRegistration.syncMemberships()
            fetchAndPublishRemoteMembers(space, force = true)
            pollInbox(space)
            retryPendingOutbox(space)
            activity.runOnUiThread(onFinished)
        }
    }

    override fun queueNotificationMuteSync(
        spaceID: UUID,
        onStatus: (SpaceNotificationMuteState) -> Unit,
    ) {
        val requested = notificationMuteStore.state(spaceID).muted
        if (requested) {
            sound.stop()
            voicePlayer.stop()
            flash.stop()
            notifications.clearDeliveredCalls(spaceID)
        }
        val requestID = SpaceNotificationMuteSync.enqueue(activity, spaceID, requested)
        val work = WorkManager.getInstance(activity).getWorkInfoByIdLiveData(requestID)
        lateinit var observer: Observer<WorkInfo?>
        observer = Observer { info ->
            val latest = notificationMuteStore.state(spaceID)
            activity.runOnUiThread {
                onStatus(latest)
                onNotificationMuteStatus(spaceID, latest)
            }
            if (info?.state?.isFinished == true) work.removeObserver(observer)
        }
        work.observe(activity, observer)
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
        val memberRole = when (event.senderRole) {
            FamilyRole.Parent -> AppRole.PARENT
            FamilyRole.Child -> AppRole.CHILD
            FamilyRole.General -> AppRole.GENERAL
            null -> null
        }
        // Only an authenticated stable sender ID may become a durable member. Event IDs are
        // per-message and must never become identity aliases.
        event.senderID?.let { senderID ->
            activity.runOnUiThread { onPresence(senderID.toString(), event.senderName, memberRole) }
        }

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

        if (shouldSuppressAppOwnedAlert(notificationMuteStore.isMuted(event.spaceID), event.kind)) return

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
                    if (event.kind == CallEvent.Kind.VoiceMessage) {
                        pendingVoiceStore.record(event.id, event.spaceID)
                    }
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
                AppRole.GENERAL -> FamilyRole.General
                null -> null
            },
            targetID = targetID,
            targetIDs = targetIDs,
            voiceData = voice,
        )
    }

    private suspend fun sendRemoteWithRetry(
        event: CallEvent,
        spaceSecret: String,
    ): Result<BackendSendReceipt> {
        var lastError: Throwable? = null
        repeat(3) { attempt ->
            val outcome = runCatching { backend.send(event, spaceSecret) }
            if (outcome.isSuccess) return Result.success(outcome.getOrThrow())
            val error = outcome.exceptionOrNull() ?: return Result.failure(
                IllegalStateException("알 수 없는 오류"),
            )
            lastError = error
            val retryable = error is IOException ||
                (error is com.armsone.ourbutton.data.BackendException.HttpError &&
                    (error.code == 429 || error.code >= 500))
            if (!retryable || attempt == 2) return Result.failure(error)
            delay(if (attempt == 0) 500 else 1_500)
        }
        return Result.failure(lastError ?: IllegalStateException("알 수 없는 오류"))
    }

    private fun refreshRemoteMembers(space: FamilySpace, force: Boolean = false) {
        scope.launch { fetchAndPublishRemoteMembers(space, force) }
    }

    private suspend fun fetchAndPublishRemoteMembers(space: FamilySpace, force: Boolean): Boolean {
        if (!backendConfiguration.isConfigured) return false
        val now = System.currentTimeMillis()
        synchronized(memberRefreshGate) {
            if (!force && now - lastMemberRefreshRequestMillis < 5_000) return false
            lastMemberRefreshRequestMillis = now
        }
        val request = memberRefreshGate.begin(space.id)
        val members = runCatching { backend.fetchMembers(space) }.getOrNull() ?: return false
        if (!memberRefreshGate.accepts(request, activeSpace?.id)) return false
        val visible = members.filter { it.deviceID != deviceID }.map { member ->
            PresenceUi(
                id = member.deviceID.toString(),
                name = member.name,
                role = when (member.role) {
                    FamilyRole.Parent -> AppRole.PARENT
                    FamilyRole.Child -> AppRole.CHILD
                    FamilyRole.General -> AppRole.GENERAL
                },
                isCurrentDevice = false,
                notificationsMuted = member.notificationsMuted,
            )
        }
        withContext(Dispatchers.Main) { onMembers(visible) }
        return true
    }

    private fun retryPendingOutbox(space: FamilySpace) {
        if (!backendConfiguration.isConfigured) return
        scope.launch {
            outboundEventStore.events(space.id).take(20).forEach { event ->
                if (activeSpace?.id != space.id) return@launch
                if (sendRemoteWithRetry(event, space.secret).isSuccess) {
                    outboundEventStore.remove(event.id)
                } else return@launch
            }
        }
    }

    private fun pollInbox(space: FamilySpace) {
        if (!backendConfiguration.isConfigured || !inboxPollRunning.compareAndSet(false, true)) return
        scope.launch {
            try {
                var cursor = inboxPrefs.getString("cursor:${space.id}", null)
                repeat(3) {
                    if (activeSpace?.id != space.id) return@launch
                    val page = runCatching { backend.fetchInbox(space, deviceID, cursor) }
                        .getOrNull() ?: return@launch
                    for (event in page.events) {
                        if (activeSpace?.id != space.id || event.spaceID != space.id) return@launch
                        withContext(Dispatchers.Main) { accept(event, relayOverBluetooth = false) }
                        val acknowledged = runCatching {
                            backend.acknowledgeInbox(space, deviceID, event.id)
                        }.isSuccess
                        if (!acknowledged) return@launch
                    }
                    cursor = page.cursor
                    inboxPrefs.edit().apply {
                        if (cursor == null) remove("cursor:${space.id}")
                        else putString("cursor:${space.id}", cursor)
                    }.apply()
                    if (!page.hasMore) return@launch
                }
            } finally {
                inboxPollRunning.set(false)
            }
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
        memberRefreshGate.invalidate()
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
