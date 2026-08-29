package com.armsone.ourbutton.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportContractsTest {
    @Test
    fun statusDescriptionsAndConnectivityMatchIos() {
        assertEquals("꺼짐", TransportStatus.Idle.description)
        assertEquals("가족 기기를 찾는 중…", TransportStatus.Searching.description)
        assertEquals("근처 기기 2대와 연결됨", TransportStatus.Connected(2).description)
        assertEquals("데모 모드", TransportStatus.Demo.description)
        assertFalse(TransportStatus.Idle.isConnected)
        assertFalse(TransportStatus.Searching.isConnected)
        assertTrue(TransportStatus.Connected(0).isConnected)
        assertTrue(TransportStatus.Demo.isConnected)
    }

    @Test
    fun transportErrorsKeepIosUserFacingText() {
        assertEquals(
            "연결된 가족 기기가 없어요. 상대 기기에서 앱이 켜져 있는지 확인해 주세요.",
            TransportError.NoPeers.message,
        )
        assertEquals("전송에 실패했어요. (radio)", TransportError.SendFailed("radio").message)
    }
}
