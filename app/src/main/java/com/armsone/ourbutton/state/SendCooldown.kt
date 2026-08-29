package com.armsone.ourbutton.state

/**
 * Single source of truth for the shared send cooldown. Every send entry point
 * (quiet alert, ding-dong, voice message, widget deep actions) must consult this
 * gate; the window begins only after a successful send attempt.
 */
class SendCooldown(
    private val durationMillis: Long = DEFAULT_DURATION_MILLIS,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var expiresAtMillis = 0L

    val isActive: Boolean
        get() = now() < expiresAtMillis

    fun beginIfAvailable(): Boolean {
        if (isActive) return false
        expiresAtMillis = now() + durationMillis
        return true
    }

    /** Whole seconds left, rounded up so the UI never shows 0 while still blocked. */
    fun remainingSeconds(): Int {
        val remaining = expiresAtMillis - now()
        return if (remaining <= 0) 0 else ((remaining + 999) / 1_000).toInt()
    }

    fun reset() {
        expiresAtMillis = 0L
    }

    companion object {
        const val DEFAULT_DURATION_MILLIS = 10_000L
    }
}
