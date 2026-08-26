package com.armsone.button.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectUpdatePolicyTest {
    @Test fun semanticVersionComparisonRequiresANewerProductVersion() {
        assertTrue(compareVersions("2.0.1", "2.0.0") > 0)
        assertTrue(compareVersions("0.4.0", "0.3.6") > 0)
        assertEquals(0, compareVersions("2.0", "2.0.0"))
        assertTrue(compareVersions("1.9.9", "2.0.0") < 0)
    }

    @Test fun spaceHubUpdateStatesAreTruthfulAndActionable() {
        val idle = spaceHubUpdatePresentation(DirectUpdateState())
        assertEquals("최신 버전 확인", idle.buttonLabel)
        assertEquals(SpaceHubUpdateAction.CHECK, idle.action)

        val checking = spaceHubUpdatePresentation(DirectUpdateState(phase = UpdatePhase.CHECKING))
        assertEquals("확인 중…", checking.buttonLabel)
        assertTrue(!checking.enabled)
        assertTrue(checking.showProgress)

        val current = spaceHubUpdatePresentation(DirectUpdateState(phase = UpdatePhase.CURRENT))
        assertEquals("최신 버전이에요", current.buttonLabel)
        assertTrue(!current.enabled)
        assertEquals(SpaceHubUpdateAction.NONE, current.action)

        val available = spaceHubUpdatePresentation(
            DirectUpdateState(phase = UpdatePhase.AVAILABLE, version = "2.1.0"),
        )
        assertEquals("새 버전 2.1.0이 있어요.", available.statusText)
        assertEquals("버전 2.1.0 다운로드", available.buttonLabel)
        assertEquals(SpaceHubUpdateAction.DOWNLOAD, available.action)

        val ready = spaceHubUpdatePresentation(DirectUpdateState(phase = UpdatePhase.READY))
        assertEquals(SpaceHubUpdateAction.INSTALL, ready.action)
        assertTrue(ready.statusText!!.contains("Android 설치 화면"))

        val error = spaceHubUpdatePresentation(DirectUpdateState(phase = UpdatePhase.ERROR))
        assertEquals("다시 확인", error.buttonLabel)
        assertEquals("확인하지 못했어요. 연결을 확인하고 다시 시도해 주세요.", error.statusText)
    }
}
