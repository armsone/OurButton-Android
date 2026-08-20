package com.armsone.button.transport

import com.armsone.button.model.CallEvent
import com.armsone.button.model.FamilySpace

sealed class TransportStatus {
    data object Idle : TransportStatus()
    data object Searching : TransportStatus()
    data class Connected(val peerCount: Int) : TransportStatus()
    data object Demo : TransportStatus()

    val description: String
        get() = when (this) {
            Idle -> "꺼짐"
            Searching -> "가족 기기를 찾는 중…"
            is Connected -> "근처 기기 ${peerCount}대와 연결됨"
            Demo -> "데모 모드"
        }

    val isConnected: Boolean
        get() = this is Connected || this === Demo
}

sealed class TransportError(message: String) : IllegalStateException(message) {
    data object NoPeers : TransportError(
        "연결된 가족 기기가 없어요. 상대 기기에서 앱이 켜져 있는지 확인해 주세요.",
    )

    class SendFailed(val reason: String) : TransportError("전송에 실패했어요. ($reason)")
}

interface CallTransport {
    val status: TransportStatus
    var onEvent: ((CallEvent) -> Unit)?
    var onStatusChange: ((TransportStatus) -> Unit)?

    fun start(space: FamilySpace, displayName: String)
    fun stop()
    @Throws(TransportError::class)
    fun send(event: CallEvent)
}
