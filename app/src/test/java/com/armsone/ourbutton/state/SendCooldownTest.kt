package com.armsone.ourbutton.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SendCooldownTest {
    private var nowMillis = 1_000_000L
    private val cooldown = SendCooldown(now = { nowMillis })

    @Test
    fun beginActivatesTenSecondWindow() {
        assertFalse(cooldown.isActive)
        assertEquals(0, cooldown.remainingSeconds())

        assertTrue(cooldown.beginIfAvailable())

        assertTrue(cooldown.isActive)
        assertEquals(10, cooldown.remainingSeconds())
    }

    @Test
    fun remainingSecondsRoundUpWhilePartiallyElapsed() {
        cooldown.beginIfAvailable()

        nowMillis += 1
        assertEquals(10, cooldown.remainingSeconds())

        nowMillis += 8_999
        assertEquals(1, cooldown.remainingSeconds())

        nowMillis += 999
        assertTrue(cooldown.isActive)
        assertEquals(1, cooldown.remainingSeconds())
    }

    @Test
    fun expiresExactlyAtBoundary() {
        cooldown.beginIfAvailable()

        nowMillis += SendCooldown.DEFAULT_DURATION_MILLIS - 1
        assertTrue(cooldown.isActive)

        nowMillis += 1
        assertFalse(cooldown.isActive)
        assertEquals(0, cooldown.remainingSeconds())
    }

    @Test
    fun resetClearsActiveWindow() {
        cooldown.beginIfAvailable()
        assertTrue(cooldown.isActive)

        cooldown.reset()

        assertFalse(cooldown.isActive)
        assertEquals(0, cooldown.remainingSeconds())
    }

    @Test
    fun duplicateSendIsRejectedUntilExpiryThenAllowed() {
        // Mirrors the ViewModel gate: a send only proceeds when the window is inactive.
        fun attemptSend(): Boolean {
            return cooldown.beginIfAvailable()
        }

        assertTrue(attemptSend())
        assertFalse(attemptSend())

        nowMillis += 5_000
        assertFalse(attemptSend())

        nowMillis += 5_000
        assertTrue(attemptSend())
    }

    @Test
    fun beginAfterExpiryStartsFreshWindow() {
        cooldown.beginIfAvailable()
        nowMillis += SendCooldown.DEFAULT_DURATION_MILLIS

        assertTrue(cooldown.beginIfAvailable())

        assertTrue(cooldown.isActive)
        assertEquals(10, cooldown.remainingSeconds())
    }
}
