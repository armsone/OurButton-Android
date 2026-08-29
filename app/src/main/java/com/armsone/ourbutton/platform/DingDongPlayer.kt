package com.armsone.ourbutton.platform

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/** Synthesizes the iOS E5→C5 chime and five-second 600↔1200 Hz siren. */
class DingDongPlayer {
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var track: AudioTrack? = null
    private var playback: Future<*>? = null

    fun play() = playSamples(makeDingDongSamples())

    fun playSiren() = playSamples(makeSirenSamples())

    @Synchronized
    fun stop() {
        playback?.cancel(true)
        playback = null
        track?.runCatching { pause() }
        track?.runCatching { flush() }
        track?.runCatching { release() }
        track = null
    }

    fun close() {
        stop()
        executor.shutdownNow()
    }

    @Synchronized
    private fun playSamples(samples: ShortArray) {
        stop()
        playback = executor.submit {
            val localTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(samples.size * Short.SIZE_BYTES)
                .build()
            synchronized(this) { track = localTrack }
            try {
                localTrack.write(samples, 0, samples.size)
                localTrack.play()
                val end = System.nanoTime() + samples.size * 1_000_000_000L / SAMPLE_RATE
                while (!Thread.currentThread().isInterrupted && System.nanoTime() < end) {
                    Thread.sleep(20)
                }
            } catch (_: Exception) {
                // Sound is an enhancement; audio focus/device failures must not block calls.
            } finally {
                synchronized(this) {
                    if (track === localTrack) track = null
                }
                localTrack.runCatching { stop() }
                localTrack.runCatching { release() }
            }
        }
    }

    private fun makeDingDongSamples(): ShortArray {
        val tones = listOf(659.25 to 0.45, 523.25 to 0.70)
        val toneGap = (0.06 * SAMPLE_RATE).toInt()
        val repetitionGap = (0.28 * SAMPLE_RATE).toInt()
        val toneFrames = tones.map { (_, duration) -> (duration * SAMPLE_RATE).toInt() }
        val single = toneFrames.sum() + toneGap
        val samples = ShortArray(single * 3 + repetitionGap * 2)
        var offset = 0
        repeat(3) { repetition ->
            tones.forEachIndexed { index, (frequency, duration) ->
                repeat(toneFrames[index]) { frame ->
                    val time = frame.toDouble() / SAMPLE_RATE
                    val envelope = exp(-4.0 * time / duration)
                    val fundamental = sin(2 * PI * frequency * time)
                    val overtone = 0.3 * sin(2 * PI * frequency * 2 * time)
                    samples[offset + frame] =
                        (Short.MAX_VALUE * 0.55 * envelope * (fundamental + overtone))
                            .toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }
                offset += toneFrames[index]
                if (index == 0) offset += toneGap
            }
            if (repetition < 2) offset += repetitionGap
        }
        return samples
    }

    private fun makeSirenSamples(): ShortArray {
        val samples = ShortArray(5 * SAMPLE_RATE)
        var phase = 0.0
        samples.indices.forEach { frame ->
            val time = frame.toDouble() / SAMPLE_RATE
            val sweep = 0.5 * (1 - cos(2 * PI * time))
            val frequency = 600.0 + 600.0 * sweep
            phase += 2 * PI * frequency / SAMPLE_RATE
            samples[frame] = (Short.MAX_VALUE * 0.5 * sin(phase)).toInt().toShort()
        }
        return samples
    }

    private companion object { const val SAMPLE_RATE = 44_100 }
}
