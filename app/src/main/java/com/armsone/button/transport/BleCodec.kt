package com.armsone.button.transport

import com.armsone.button.model.CallEvent
import com.armsone.button.model.CallEventCoder
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.ceil
import kotlin.math.min

class BleCodecError(message: String) : IllegalArgumentException(message)

object BleCodec {
    const val HEADER_LENGTH = 9
    const val MAX_BLE_VOICE_BYTES = 64 * 1024
    private const val FRAME_VERSION = 1
    private const val NONCE_LENGTH = 12
    private const val TAG_LENGTH_BITS = 128
    private val random = SecureRandom()

    fun seal(event: CallEvent, secret: String): ByteArray {
        val nonce = ByteArray(NONCE_LENGTH).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(secret), GCMParameterSpec(TAG_LENGTH_BITS, nonce))
        return nonce + cipher.doFinal(CallEventCoder.encode(event))
    }

    fun open(combined: ByteArray, secret: String): CallEvent {
        if (combined.size < NONCE_LENGTH + TAG_LENGTH_BITS / 8) {
            throw BleCodecError("Invalid encrypted frame")
        }
        val nonce = combined.copyOfRange(0, NONCE_LENGTH)
        val ciphertextAndTag = combined.copyOfRange(NONCE_LENGTH, combined.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(secret), GCMParameterSpec(TAG_LENGTH_BITS, nonce))
        return CallEventCoder.decode(cipher.doFinal(ciphertextAndTag))
    }

    fun fragments(
        event: CallEvent,
        secret: String,
        maximumPayloadLength: Int,
    ): List<ByteArray> {
        val chunkLength = maxOf(1, maximumPayloadLength - HEADER_LENGTH)
        val combined = seal(event, secret)
        val count = ceil(combined.size.toDouble() / chunkLength.toDouble()).toInt()
        if (count <= 0 || count > UShort.MAX_VALUE.toInt()) throw BleCodecError("Invalid frame")

        val messageID = random.nextInt()
        return List(count) { index ->
            val lower = index * chunkLength
            val upper = min(combined.size, lower + chunkLength)
            ByteArray(HEADER_LENGTH + upper - lower).also { frame ->
                frame[0] = FRAME_VERSION.toByte()
                frame.writeUInt32(1, messageID)
                frame.writeUInt16(5, index)
                frame.writeUInt16(7, count)
                combined.copyInto(frame, destinationOffset = HEADER_LENGTH, startIndex = lower, endIndex = upper)
            }
        }
    }

    fun supports(event: CallEvent): Boolean =
        event.kind != CallEvent.Kind.VoiceMessage ||
            event.voiceData?.size?.let { it in 1..MAX_BLE_VOICE_BYTES } == true

    private fun key(secret: String): SecretKeySpec {
        val material = "button-ble-v1|$secret".toByteArray(Charsets.UTF_8)
        return SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(material), "AES")
    }

    private fun ByteArray.writeUInt16(offset: Int, value: Int) {
        this[offset] = (value ushr 8).toByte()
        this[offset + 1] = value.toByte()
    }

    private fun ByteArray.writeUInt32(offset: Int, value: Int) {
        this[offset] = (value ushr 24).toByte()
        this[offset + 1] = (value ushr 16).toByte()
        this[offset + 2] = (value ushr 8).toByte()
        this[offset + 3] = value.toByte()
    }
}

data class BlePeerRoutes(
    val notifyAddresses: Set<String>,
    val writeAddresses: Set<String>,
)

/**
 * A peer may connect in either BLE direction. Notify peers are served by our GATT server;
 * remaining connected peers must receive the same frame through a client characteristic write.
 */
fun blePeerRoutes(
    subscribedAddresses: Set<String>,
    connectedAddresses: Set<String>,
): BlePeerRoutes = BlePeerRoutes(
    notifyAddresses = subscribedAddresses,
    writeAddresses = connectedAddresses - subscribedAddresses,
)

class BleReassembler {
    private data class Pending(
        val count: Int,
        val createdAt: Instant,
        val chunks: MutableMap<Int, ByteArray> = mutableMapOf(),
    )

    private val pending = mutableMapOf<String, Pending>()

    fun append(fragment: ByteArray, peerID: String, now: Instant = Instant.now()): ByteArray? {
        pending.entries.removeAll { Duration.between(it.value.createdAt, now).toMillis() >= 30_000 }
        if (fragment.size < BleCodec.HEADER_LENGTH || fragment[0].toInt() != 1) return null

        val messageID = fragment.readUInt32(1)
        val index = fragment.readUInt16(5)
        val count = fragment.readUInt16(7)
        if (count <= 0 || index !in 0 until count) return null

        val key = "$peerID|$messageID"
        val value = pending[key] ?: Pending(count = count, createdAt = now)
        if (value.count != count) {
            pending.remove(key)
            return null
        }
        value.chunks[index] = fragment.copyOfRange(BleCodec.HEADER_LENGTH, fragment.size)
        pending[key] = value
        if (value.chunks.size != count) return null

        val combined = ByteArrayOutputStream()
        for (chunkIndex in 0 until count) {
            val chunk = value.chunks[chunkIndex] ?: return null
            combined.write(chunk)
        }
        pending.remove(key)
        return combined.toByteArray()
    }

    private fun ByteArray.readUInt16(offset: Int): Int =
        ((this[offset].toInt() and 0xff) shl 8) or (this[offset + 1].toInt() and 0xff)

    private fun ByteArray.readUInt32(offset: Int): Long =
        ((this[offset].toLong() and 0xff) shl 24) or
            ((this[offset + 1].toLong() and 0xff) shl 16) or
            ((this[offset + 2].toLong() and 0xff) shl 8) or
            (this[offset + 3].toLong() and 0xff)
}
