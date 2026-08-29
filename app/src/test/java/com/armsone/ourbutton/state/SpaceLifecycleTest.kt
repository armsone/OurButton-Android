package com.armsone.ourbutton.state

import com.armsone.ourbutton.model.FamilyRole
import com.armsone.ourbutton.model.FamilySpace
import com.armsone.ourbutton.model.CallEvent
import com.armsone.ourbutton.model.QRInvite
import com.armsone.ourbutton.push.NotificationMuteSyncStatus
import com.armsone.ourbutton.push.shouldSuppressAppOwnedAlert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class SpaceLifecycleTest {
    private val secret1 = "0123456789abcdef0123456789abcdef"
    private val secret2 = "fedcba9876543210fedcba9876543210"

    @Test
    fun createSpacePersistsLocallyWithoutPushToken() {
        val spaceId = UUID.randomUUID()
        val invite = InviteUi(spaceId.toString(), "우리 가족", secret1)
        val room = SavedRoomUi(invite, "엄마", AppRole.PARENT)
        val state = AppUiState(
            phase = AppPhase.HOME,
            role = AppRole.PARENT,
            spaceName = "우리 가족",
            displayName = "엄마",
            invite = invite,
            rooms = listOf(room),
            members = listOf(PresenceUi("current", "엄마", AppRole.PARENT, true)),
        )

        val encoded = AppStateCoder.encode(state)
        assertNotNull(encoded)

        val decoded = AppStateCoder.decode(encoded)
        assertEquals(AppPhase.HOME, decoded.phase)
        assertEquals(AppRole.PARENT, decoded.role)
        assertEquals("우리 가족", decoded.spaceName)
        assertEquals("엄마", decoded.displayName)
        assertEquals(invite, decoded.invite)
        assertEquals(1, decoded.rooms.size)
        assertEquals(room, decoded.rooms.first())
        assertEquals(1, decoded.members.size)
        assertTrue(decoded.members.first().isCurrentDevice)
    }

    @Test
    fun joinSpacePersistsLocallyWithoutPushToken() {
        val spaceId = UUID.randomUUID()
        val invite = QRInvite(FamilySpace(id = spaceId, name = "초대 공간", secret = secret1))
        val inviteUi = InviteUi(invite.spaceID.toString(), invite.spaceName, invite.secret)
        val room = SavedRoomUi(inviteUi, "첫째", AppRole.CHILD)
        val state = AppUiState(
            phase = AppPhase.HOME,
            role = AppRole.CHILD,
            spaceName = "초대 공간",
            displayName = "첫째",
            invite = inviteUi,
            rooms = listOf(room),
            members = listOf(PresenceUi("current", "첫째", AppRole.CHILD, true)),
        )

        val encoded = AppStateCoder.encode(state)
        assertNotNull(encoded)

        val decoded = AppStateCoder.decode(encoded)
        assertEquals(AppPhase.HOME, decoded.phase)
        assertEquals(AppRole.CHILD, decoded.role)
        assertEquals("초대 공간", decoded.spaceName)
        assertEquals("첫째", decoded.displayName)
        assertEquals(inviteUi, decoded.invite)
        assertEquals(1, decoded.rooms.size)
        assertEquals(room, decoded.rooms.first())
    }

    @Test
    fun multiSpaceCreationPreservesExistingRooms() {
        val invite1 = InviteUi(UUID.randomUUID().toString(), "공간 1", secret1)
        val room1 = SavedRoomUi(invite1, "엄마", AppRole.PARENT)

        val invite2 = InviteUi(UUID.randomUUID().toString(), "공간 2", secret2)
        val room2 = SavedRoomUi(invite2, "엄마", AppRole.PARENT)

        val stateWithTwoRooms = AppUiState(
            phase = AppPhase.HOME,
            role = AppRole.PARENT,
            spaceName = "공간 2",
            displayName = "엄마",
            invite = invite2,
            rooms = listOf(room1, room2),
            members = listOf(PresenceUi("current", "엄마", AppRole.PARENT, true)),
        )

        val encoded = AppStateCoder.encode(stateWithTwoRooms)
        val decoded = AppStateCoder.decode(encoded)

        assertEquals(2, decoded.rooms.size)
        assertEquals("공간 1", decoded.rooms[0].invite.spaceName)
        assertEquals("공간 2", decoded.rooms[1].invite.spaceName)
        assertEquals("공간 2", decoded.spaceName)
        assertEquals(invite2, decoded.invite)
    }

    @Test
    fun twoSpaceSwitchingAndRelaunchPersistence() {
        val spaceId1 = UUID.randomUUID().toString()
        val spaceId2 = UUID.randomUUID().toString()

        val invite1 = InviteUi(spaceId1, "집 1", secret1)
        val room1 = SavedRoomUi(invite1, "엄마", AppRole.PARENT)

        val invite2 = InviteUi(spaceId2, "집 2", secret2)
        val room2 = SavedRoomUi(invite2, "할머니", AppRole.CHILD)

        val rooms = listOf(room1, room2)

        // Initially active space is room 1
        val initial = AppUiState(
            phase = AppPhase.HOME,
            role = room1.role,
            spaceName = room1.invite.spaceName,
            displayName = room1.displayName,
            invite = room1.invite,
            rooms = rooms,
        )
        val initialEncoded = AppStateCoder.encode(initial)
        val initialDecoded = AppStateCoder.decode(initialEncoded)
        assertEquals(spaceId1, initialDecoded.invite?.spaceId)
        assertEquals(AppRole.PARENT, initialDecoded.role)

        // Switch to room 2
        val switched = initial.copy(
            phase = AppPhase.HOME,
            role = room2.role,
            spaceName = room2.invite.spaceName,
            displayName = room2.displayName,
            invite = room2.invite,
            rooms = rooms,
            members = listOf(PresenceUi("current", room2.displayName, room2.role, true)),
        )

        val switchedEncoded = AppStateCoder.encode(switched)
        assertNotNull(switchedEncoded)

        // Simulate relaunch: decode persisted JSON
        val relaunched = AppStateCoder.decode(switchedEncoded)
        assertEquals(spaceId2, relaunched.invite?.spaceId)
        assertEquals("집 2", relaunched.spaceName)
        assertEquals("할머니", relaunched.displayName)
        assertEquals(AppRole.CHILD, relaunched.role)
        assertEquals(2, relaunched.rooms.size)
        assertEquals(spaceId1, relaunched.rooms[0].invite.spaceId)
        assertEquals(spaceId2, relaunched.rooms[1].invite.spaceId)
    }

    @Test
    fun leavingOneSpacePreservesRemainingSpace() {
        val invite1 = InviteUi(UUID.randomUUID().toString(), "공간 A", secret1)
        val room1 = SavedRoomUi(invite1, "엄마", AppRole.PARENT)

        val invite2 = InviteUi(UUID.randomUUID().toString(), "공간 B", secret2)
        val room2 = SavedRoomUi(invite2, "엄마", AppRole.PARENT)

        val remainingRooms = listOf(room2)
        val afterLeave = AppUiState(
            phase = AppPhase.HOME,
            role = room2.role,
            spaceName = room2.invite.spaceName,
            displayName = room2.displayName,
            invite = room2.invite,
            rooms = remainingRooms,
            members = listOf(PresenceUi("current", room2.displayName, room2.role, true)),
        )

        val encoded = AppStateCoder.encode(afterLeave)
        val decoded = AppStateCoder.decode(encoded)

        assertEquals(1, decoded.rooms.size)
        assertEquals("공간 B", decoded.spaceName)
        assertEquals(invite2, decoded.invite)
    }

    @Test
    fun symmetricMergedMemberListWithCurrentDeviceOnTop() {
        val currentDevice = PresenceUi("current", "엄마", AppRole.PARENT, true)
        val remoteMember1 = PresenceUi(UUID.randomUUID().toString(), "첫째", AppRole.CHILD, false)
        val remoteMember2 = PresenceUi(UUID.randomUUID().toString(), "둘째", AppRole.CHILD, false)

        val backendMembers = listOf(remoteMember2, remoteMember1)

        val byID = linkedMapOf("current" to currentDevice)
        backendMembers.forEach { member -> byID[member.id] = member }

        val visible = byID.values.sortedWith(
            compareByDescending<PresenceUi> { it.isCurrentDevice }.thenBy { it.name },
        )

        assertEquals(3, visible.size)
        assertTrue(visible[0].isCurrentDevice)
        assertEquals("current", visible[0].id)
        assertEquals("둘째", visible[1].name)
        assertEquals("첫째", visible[2].name)
    }

    @Test
    fun oneDamagedSavedRoomDoesNotEraseOtherSpacesOrActiveSelection() {
        val activeID = UUID.randomUUID().toString()
        val otherID = UUID.randomUUID().toString()
        val raw = """{
            "spaceName":"활성 공간",
            "displayName":"엄마",
            "spaceId":"$activeID",
            "secret":"$secret1",
            "role":"PARENT",
            "rooms":[
              {"spaceName":"다른 공간","spaceId":"$otherID","secret":"$secret2","displayName":"엄마","role":"PARENT"},
              {"spaceName":"손상","spaceId":"not-a-uuid","secret":"bad","displayName":"x","role":"CHILD"},
              {"spaceName":"활성 공간","spaceId":"$activeID","secret":"$secret1","displayName":"엄마","role":"PARENT"}
            ]
        }""".trimIndent()

        val decoded = AppStateCoder.decode(raw)

        assertEquals(activeID, decoded.invite?.spaceId)
        assertEquals(2, decoded.rooms.size)
        assertEquals(setOf(activeID, otherID), decoded.rooms.map { it.invite.spaceId }.toSet())
    }

    @Test
    fun outboundVoiceKeepsCapturedSpaceAfterAnotherRoomBecomesActive() {
        val first = FamilySpace(UUID.randomUUID(), "집 1", secret1)
        val second = FamilySpace(UUID.randomUUID(), "집 2", secret2)
        val captured = OutboundContext(first, "엄마", FamilyRole.Parent)

        // The UI may switch to `second` while the first request is still in flight.
        val event = captured.makeEvent(CallEvent.Kind.VoiceMessage, byteArrayOf(1, 2, 3))

        assertEquals(first.id, event.spaceID)
        assertTrue(event.spaceID != second.id)
        assertEquals(FamilyRole.Parent, event.senderRole)
        assertTrue(event.voiceData!!.contentEquals(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun currentDeviceMuteIsActionableAndNeverLooksServerConfirmedWhilePending() {
        val pending = PresenceUi(
            id = "current",
            name = "엄마",
            role = AppRole.PARENT,
            isCurrentDevice = true,
            notificationsMuted = true,
            notificationMuteSyncStatus = NotificationMuteSyncStatus.SYNCING,
        )
        assertEquals("알림 꺼짐", presenceStatusText(pending))
        assertTrue(presenceAccessibilityDescription(pending).contains("누르면 알림을 켜요"))
        assertTrue(presenceAccessibilityDescription(pending).contains("동기화 중"))

        val failed = pending.copy(notificationMuteSyncStatus = NotificationMuteSyncStatus.ERROR)
        assertTrue(presenceAccessibilityDescription(failed).contains("동기화 필요"))
    }

    @Test
    fun remoteMemberShowsExactServerMuteStatus() {
        val remote = PresenceUi(
            id = UUID.randomUUID().toString(),
            name = "첫째",
            role = AppRole.CHILD,
            isCurrentDevice = false,
            notificationsMuted = true,
        )
        assertEquals("알림 꺼짐", presenceStatusText(remote))
        assertTrue(presenceAccessibilityDescription(remote).contains("알림 꺼짐"))
    }

    @Test
    fun muteSuppressesOnlyAppOwnedIncomingCallAndVoiceAlerts() {
        listOf(
            CallEvent.Kind.QuietAlert,
            CallEvent.Kind.Siren,
            CallEvent.Kind.DingDong,
            CallEvent.Kind.VoiceMessage,
        ).forEach { kind -> assertTrue(shouldSuppressAppOwnedAlert(true, kind)) }
        assertTrue(!shouldSuppressAppOwnedAlert(true, CallEvent.Kind.Acknowledge))
        assertTrue(!shouldSuppressAppOwnedAlert(true, CallEvent.Kind.Presence))
        assertTrue(!shouldSuppressAppOwnedAlert(false, CallEvent.Kind.DingDong))
    }

    @Test
    fun recoverableMuteSyncFailureStaysInlineAndNeverCreatesBlockingError() {
        val spaceID = UUID.randomUUID()
        val invite = InviteUi(spaceID.toString(), "우리 가족", secret1)
        val state = AppUiState(
            phase = AppPhase.HOME,
            role = AppRole.PARENT,
            invite = invite,
            rooms = listOf(SavedRoomUi(invite, "엄마", AppRole.PARENT)),
            members = listOf(PresenceUi("current", "엄마", AppRole.PARENT, true)),
            errorMessage = null,
        )
        val failed = appStateWithNotificationMute(
            state,
            spaceID,
            com.armsone.ourbutton.push.SpaceNotificationMuteState(
                muted = true,
                syncStatus = NotificationMuteSyncStatus.ERROR,
                errorMessage = "offline",
            ),
        )

        assertNull(failed.errorMessage)
        assertEquals(NotificationMuteSyncStatus.ERROR, failed.members.single().notificationMuteSyncStatus)
        assertEquals(NotificationMuteSyncStatus.ERROR, failed.rooms.single().notificationMuteSyncStatus)
        assertEquals("알림 꺼짐", presenceStatusText(failed.members.single()))
    }

    @Test
    fun spaceSelectionHubShowsAllSpacesCurrentStateAndInlineSyncNeed() {
        val current = SavedRoomUi(
            InviteUi(UUID.randomUUID().toString(), "우리 집", secret1),
            "엄마",
            AppRole.PARENT,
            notificationsMuted = true,
            notificationMuteSyncStatus = NotificationMuteSyncStatus.ERROR,
        )
        val other = SavedRoomUi(
            InviteUi(UUID.randomUUID().toString(), "할머니 집", secret2),
            "엄마",
            AppRole.CHILD,
        )
        val state = AppUiState(rooms = listOf(current, other), invite = current.invite)

        assertEquals(2, state.rooms.size)
        assertEquals("사용 중 · 알림 꺼짐 · 동기화 필요", spaceSelectionSubtitle(current, true))
        assertEquals("알림 켜짐 · 이 공간으로 이동", spaceSelectionSubtitle(other, false))
    }
}
