package com.armsone.button.state

import com.armsone.button.platform.MemberRefreshGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class MemberConsistencyTest {
    @Test
    fun threeDevicesConvergeToSameDurableSetAndBleOnlyMarksLive() {
        val ids = List(3) { UUID.randomUUID().toString() }
        val authoritative = listOf(
            PresenceUi(ids[0], "대표님", AppRole.PARENT, false),
            PresenceUi(ids[1], "김부장", AppRole.GENERAL, false),
            PresenceUi(ids[2], "아주 긴 이름을 가진 가족 구성원", AppRole.CHILD, false),
            PresenceUi(ids[1], "김부장", AppRole.GENERAL, false),
        )

        ids.forEachIndexed { ownIndex, ownID ->
            val remote = authoritative.filterNot { it.id == ownID }
            val merged = mergeDurablePresence(
                PresenceUi("current", authoritative[ownIndex].name, authoritative[ownIndex].role, true),
                remote,
                liveNearbyIDs = setOf(ids[(ownIndex + 1) % ids.size]),
            )
            assertEquals(3, merged.size)
            assertEquals(authoritative.map { it.id }.toSet(),
                merged.map { if (it.isCurrentDevice) ownID else it.id }.toSet())
            assertEquals(1, merged.count { it.isLiveNearby && !it.isCurrentDevice })
        }
    }

    @Test
    fun bleCannotAddOrDeleteDurableMemberAndLongNameIsPreserved() {
        val longName = "할머니 댁 거실에 있는 아주 긴 이름의 안드로이드 기기"
        val durableID = UUID.randomUUID().toString()
        val merged = mergeDurablePresence(
            PresenceUi("current", "엄마", AppRole.PARENT, true),
            listOf(PresenceUi(durableID, longName, AppRole.GENERAL, false)),
            liveNearbyIDs = setOf(UUID.randomUUID().toString()),
        )
        assertEquals(2, merged.size)
        assertEquals(longName, merged.last().name)
        assertEquals(longName, visibleMemberName(longName))
        assertEquals("가족", visibleMemberName("   "))
        assertEquals(11, memberNameFontSizeSp(longName))
        assertFalse(merged.last().isLiveNearby)
        assertEquals(1, presenceColumnCount(519))
        assertEquals(2, presenceColumnCount(520))
    }

    @Test
    fun concurrentAndOldSpaceResponsesAreRejected() {
        val firstSpace = UUID.randomUUID()
        val secondSpace = UUID.randomUUID()
        val gate = MemberRefreshGate()
        val oldRequest = gate.begin(firstSpace)
        val newerRequest = gate.begin(firstSpace)
        assertFalse(gate.accepts(oldRequest, firstSpace))
        assertTrue(gate.accepts(newerRequest, firstSpace))
        assertFalse(gate.accepts(newerRequest, secondSpace))
        gate.invalidate()
        assertFalse(gate.accepts(newerRequest, firstSpace))
    }

    @Test
    fun generalRoleKeepsExactWireValueAndLabel() {
        assertEquals("일반", roleLabel(AppRole.GENERAL))
        assertEquals("general", com.armsone.button.model.FamilyRole.General.rawValue)
        assertEquals(
            "일반 · 알림 켜짐 · 공간에 등록됨",
            presenceCompactStatusText(PresenceUi(UUID.randomUUID().toString(), "김부장", AppRole.GENERAL, false)),
        )
    }

    @Test
    fun authenticatedKnownSenderSurvivesIncompleteServerRosterAndServerWinsOnSameDeviceID() {
        val knownID = UUID.randomUUID().toString()
        val serverID = UUID.randomUUID().toString()
        val known = PresenceUi(knownID, "김부장", AppRole.GENERAL, false, isLiveNearby = true)
        val incompleteServer = listOf(PresenceUi(serverID, "대표 아이폰", AppRole.PARENT, false))
        val merged = reconcileKnownMembers(listOf(known), incompleteServer)
        assertEquals(setOf(knownID, serverID), merged.map { it.id }.toSet())
        assertEquals("김부장", merged.first { it.id == knownID }.name)

        val reconciled = reconcileKnownMembers(
            merged,
            listOf(PresenceUi(knownID, "김 부장님", AppRole.PARENT, false, notificationsMuted = true)),
        )
        val latest = reconciled.single { it.id == knownID }
        assertEquals("김 부장님", latest.name)
        assertEquals(AppRole.PARENT, latest.role)
        assertTrue(latest.notificationsMuted)
    }

    @Test
    fun connectThenDisconnectKeepsDurableCardAndUpdatesLiveStatus() {
        val memberID = UUID.randomUUID().toString()
        val durable = listOf(PresenceUi(memberID, "김부장", AppRole.GENERAL, false, isLiveNearby = true))
        assertEquals("근처 연결됨", presenceLiveText(durable.single()))

        val disconnected = presenceAfterTransportStatus(durable, TransportUiStatus.SEARCHING)
        assertEquals(1, disconnected.size)
        assertEquals(memberID, disconnected.single().id)
        assertEquals("공간에 등록됨", presenceLiveText(disconnected.single()))

        val reconnected = disconnected.map { it.copy(isLiveNearby = true) }
        val peerOnlyDisconnected = presenceAfterPeerDisconnect(reconnected, memberID)
        assertEquals("공간에 등록됨", presenceLiveText(peerOnlyDisconnected.single()))
    }

    @Test
    fun muteAndServerRefreshRecomposeStatusWithoutCrossingSpaceIdentity() {
        val memberID = UUID.randomUUID().toString()
        val oldSpaceMember = PresenceUi(memberID, "김부장", AppRole.GENERAL, false, notificationsMuted = false)
        val refreshed = reconcileKnownMembers(
            listOf(oldSpaceMember),
            listOf(oldSpaceMember.copy(notificationsMuted = true)),
        ).single()
        assertEquals("일반 · 알림 꺼짐 · 공간에 등록됨", presenceCompactStatusText(refreshed))

        val otherID = UUID.randomUUID().toString()
        val unchanged = presenceAfterPeerDisconnect(listOf(refreshed), otherID).single()
        assertEquals(memberID, unchanged.id)
        assertTrue(unchanged.notificationsMuted)

        val activeSpace = UUID.randomUUID()
        assertTrue(isMemberUpdateForActiveSpace(activeSpace.toString(), activeSpace))
        assertFalse(isMemberUpdateForActiveSpace(activeSpace.toString(), UUID.randomUUID()))
    }

    @Test
    fun visibleRefreshUsesFakeClockAndStopsWhileBackgrounded() {
        val policy = VisibleRefreshPolicy(intervalMillis = 30_000)
        assertTrue(policy.resume(nowMillis = 1_000))
        assertFalse(policy.due(nowMillis = 30_999))
        assertTrue(policy.due(nowMillis = 31_000))
        policy.pause()
        assertFalse(policy.due(nowMillis = 61_000))
        assertTrue(policy.resume(nowMillis = 70_000))
    }

    @Test
    fun homeRefreshCoalescesPullAndPeriodicAndRejectsStaleSpaceCompletion() {
        val firstSpace = UUID.randomUUID().toString()
        val secondSpace = UUID.randomUUID().toString()
        val gate = HomeRefreshGate()
        val first = requireNotNull(gate.begin(firstSpace))
        assertEquals(null, gate.begin(firstSpace))
        assertFalse(gate.finish(first, secondSpace))
        gate.cancel()
        val second = requireNotNull(gate.begin(secondSpace))
        assertFalse(gate.finish(first, firstSpace))
        assertTrue(gate.finish(second, secondSpace))
    }
}
