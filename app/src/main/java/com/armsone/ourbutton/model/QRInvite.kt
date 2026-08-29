package com.armsone.ourbutton.model

import java.net.URI
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.UUID

data class QRInvite(
    val spaceID: UUID,
    val spaceName: String,
    val secret: String,
) {
    constructor(space: FamilySpace) : this(space.id, space.name, space.secret)

    val urlString: String
        get() = "$SCHEME://$HOST/$VERSION" +
            "?space=${encodeQueryValue(spaceID.toString().uppercase())}" +
            "&name=${encodeQueryValue(spaceName)}" +
            "&secret=${encodeQueryValue(secret)}"

    val space: FamilySpace
        get() = FamilySpace(id = spaceID, name = spaceName, secret = secret)

    companion object {
        const val SCHEME = "buttonapp"
        const val HOST = "invite"
        const val VERSION = "v1"
        const val MAX_NAME_LENGTH = 30

        fun parse(string: String): QRInvite {
            val uri = try {
                URI(string.trim())
            } catch (_: Exception) {
                throw QRInviteError.NotAnInvite
            }
            if (uri.scheme != SCHEME || uri.host != HOST) throw QRInviteError.NotAnInvite

            val versionPath = (uri.path ?: "").trim('/')
            if (versionPath != VERSION) throw QRInviteError.UnsupportedVersion(versionPath)

            val query = parseQuery(uri.rawQuery)
            val spaceID = try {
                UUID.fromString(query["space"])
            } catch (_: Exception) {
                throw QRInviteError.InvalidSpaceID
            }
            val name = query["name"]?.trim()
            if (name.isNullOrEmpty() || characterCount(name) > MAX_NAME_LENGTH) {
                throw QRInviteError.InvalidName
            }
            val secret = query["secret"]
            if (secret == null || !isValidSecret(secret)) throw QRInviteError.InvalidSecret
            return QRInvite(spaceID = spaceID, spaceName = name, secret = secret)
        }

        fun isValidSecret(secret: String): Boolean = SECRET_PATTERN.matches(secret)

        private val SECRET_PATTERN = Regex("[0-9a-f]{32}")

        private fun parseQuery(rawQuery: String?): Map<String, String?> {
            if (rawQuery == null) return emptyMap()
            val result = linkedMapOf<String, String?>()
            for (item in rawQuery.split('&')) {
                val separator = item.indexOf('=')
                val rawName = if (separator >= 0) item.substring(0, separator) else item
                if (rawName.isEmpty()) continue
                val name = decodeQueryValue(rawName)
                if (name !in result) {
                    result[name] = if (separator >= 0) decodeQueryValue(item.substring(separator + 1)) else null
                }
            }
            return result
        }

        private fun decodeQueryValue(value: String): String {
            val output = ByteArrayOutputStream()
            var index = 0
            while (index < value.length) {
                val character = value[index]
                if (character == '%') {
                    if (index + 2 >= value.length) throw QRInviteError.NotAnInvite
                    val byte = value.substring(index + 1, index + 3).toIntOrNull(16)
                        ?: throw QRInviteError.NotAnInvite
                    output.write(byte)
                    index += 3
                } else {
                    val codePoint = value.codePointAt(index)
                    output.write(String(Character.toChars(codePoint)).toByteArray(StandardCharsets.UTF_8))
                    index += Character.charCount(codePoint)
                }
            }
            return try {
                StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(output.toByteArray()))
                    .toString()
            } catch (_: Exception) {
                throw QRInviteError.NotAnInvite
            }
        }

        private fun encodeQueryValue(value: String): String {
            val output = StringBuilder()
            value.toByteArray(StandardCharsets.UTF_8).forEach { byte ->
                val number = byte.toInt() and 0xff
                val unreservedOrQuerySafe = number in 'a'.code..'z'.code ||
                    number in 'A'.code..'Z'.code ||
                    number in '0'.code..'9'.code ||
                    number.toChar() in "-._~!$'()*+,:;@/?"
                if (unreservedOrQuerySafe) output.append(number.toChar())
                else output.append('%').append("%02X".format(number))
            }
            return output.toString()
        }

        private fun characterCount(value: String): Int = value.codePointCount(0, value.length)
    }
}

sealed class QRInviteError(message: String) : IllegalArgumentException(message) {
    data object NotAnInvite : QRInviteError("OurButton 앱의 초대 코드가 아니에요.")
    class UnsupportedVersion(val version: String) :
        QRInviteError("이 초대는 새 버전의 앱에서 만든 것이에요. 앱을 업데이트해 주세요.")
    data object InvalidSpaceID : QRInviteError("초대 코드의 공간 정보가 올바르지 않아요.")
    data object InvalidName : QRInviteError("초대 코드의 공간 이름이 올바르지 않아요.")
    data object InvalidSecret : QRInviteError("초대 코드가 손상되었어요. 다시 스캔해 주세요.")
}
