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
}

