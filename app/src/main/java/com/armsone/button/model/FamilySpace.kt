package com.armsone.button.model

import java.security.SecureRandom
import java.time.Instant
import java.util.UUID

data class FamilySpace(
    val id: UUID = UUID.randomUUID(),
    val name: String,
    val secret: String = makeSecret(),
    val createdAt: Instant = Instant.now(),
) {
    companion object {
        private val random = SecureRandom()

        fun makeSecret(): String = ByteArray(16)
            .also(random::nextBytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}
