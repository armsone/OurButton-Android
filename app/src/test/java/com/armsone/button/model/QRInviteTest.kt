package com.armsone.button.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class QRInviteTest {
    private val secret = "0123456789abcdef0123456789abcdef"

    @Test
    fun roundTrip() {
        val space = FamilySpace(name = "우리 가족", secret = secret)
        val invite = QRInvite(space)
        val parsed = QRInvite.parse(invite.urlString)
        assertEquals(invite, parsed)
        assertEquals(space.id, parsed.space.id)
        assertEquals(space.name, parsed.space.name)
        assertEquals(space.secret, parsed.space.secret)
    }

    @Test
    fun roundTripWithKoreanAndSpaces() {
        val space = FamilySpace(name = "안방 초인종 팀", secret = secret)
        assertEquals("안방 초인종 팀", QRInvite.parse(QRInvite(space).urlString).spaceName)
    }

    @Test
    fun parseTrimsWhitespace() {
        val invite = QRInvite(FamilySpace(name = "가족", secret = secret))
        assertEquals(invite, QRInvite.parse("  ${invite.urlString}\n"))
    }

    @Test
    fun rejectsWrongSchemeAndRandomText() {
        val wrongScheme = "https://invite/v1?space=${UUID.randomUUID()}&name=family&secret=$secret"
        assertTrue(assertThrows(QRInviteError::class.java) { QRInvite.parse(wrongScheme) } is QRInviteError.NotAnInvite)
        assertTrue(assertThrows(QRInviteError::class.java) { QRInvite.parse("hello world") } is QRInviteError.NotAnInvite)
    }

    @Test
    fun rejectsUnsupportedVersion() {
        val url = "buttonapp://invite/v9?space=${UUID.randomUUID()}&name=family&secret=$secret"
        val error = assertThrows(QRInviteError::class.java) { QRInvite.parse(url) }
        assertTrue(error is QRInviteError.UnsupportedVersion)
        assertEquals("v9", (error as QRInviteError.UnsupportedVersion).version)
    }

    @Test
    fun rejectsInvalidSpaceIDNameAndSecret() {
        val id = UUID.randomUUID()
        assertTrue(assertThrows(QRInviteError::class.java) {
            QRInvite.parse("buttonapp://invite/v1?space=bad&name=family&secret=$secret")
        } is QRInviteError.InvalidSpaceID)
        assertTrue(assertThrows(QRInviteError::class.java) {
            QRInvite.parse("buttonapp://invite/v1?space=$id&name=%20&secret=$secret")
        } is QRInviteError.InvalidName)
        val longName = "a".repeat(QRInvite.MAX_NAME_LENGTH + 1)
        assertTrue(assertThrows(QRInviteError::class.java) {
            QRInvite.parse("buttonapp://invite/v1?space=$id&name=$longName&secret=$secret")
        } is QRInviteError.InvalidName)
        listOf("", "abc", "z".repeat(32), secret.uppercase()).forEach { badSecret ->
            assertTrue(assertThrows(QRInviteError::class.java) {
                QRInvite.parse("buttonapp://invite/v1?space=$id&name=family&secret=$badSecret")
            } is QRInviteError.InvalidSecret)
        }
    }

    @Test
    fun generatedSecretIsValid() {
        repeat(20) { assertTrue(QRInvite.isValidSecret(FamilySpace.makeSecret())) }
    }
}
