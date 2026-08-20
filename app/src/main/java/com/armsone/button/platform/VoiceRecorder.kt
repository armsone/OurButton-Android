package com.armsone.button.platform

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File

/** Press/hold AAC-in-MPEG-4 recorder with a permission-result race guard. */
class VoiceRecorder(private val context: Context) {
    enum class PermissionStatus { GRANTED, DENIED, NOT_DETERMINED }

    sealed interface State {
        data object Idle : State
        data object Denied : State
        data object RequestingPermission : State
        data class Recording(val startedElapsedMillis: Long) : State
        data class Recorded(val durationSeconds: Double) : State
    }

    var state: State = State.Idle
        private set(value) {
            field = value
            onStateChange?.invoke(value)
        }
    var recordedData: ByteArray? = null
        private set
    var onStateChange: ((State) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val outputFile = File(context.cacheDir, "voice-message.m4a")
    private var recorder: MediaRecorder? = null
    private var held = false
    private var permissionRequestGeneration = 0
    private var startedElapsedMillis = 0L
    private val autoStop: Runnable = Runnable { endPressHold() }

    fun preparePermission(status: PermissionStatus) = onMain {
        if (recorder != null || held) return@onMain
        state = when (status) {
            PermissionStatus.GRANTED -> State.Idle
            PermissionStatus.DENIED -> State.Denied
            PermissionStatus.NOT_DETERMINED -> State.Idle
        }
    }

    /**
     * [requestPermission] must launch the UI permission request and eventually invoke its
     * callback. If the press ends first, the callback cannot start a stale recording.
     */
    fun beginPressHold(
        permission: PermissionStatus,
        requestPermission: (((Boolean) -> Unit) -> Unit)? = null,
    ) = onMain {
        if (held) return@onMain
        held = true
        when (permission) {
            PermissionStatus.GRANTED -> startRecordingInternal()
            PermissionStatus.DENIED -> {
                held = false
                state = State.Denied
            }
            PermissionStatus.NOT_DETERMINED -> {
                state = State.RequestingPermission
                val generation = ++permissionRequestGeneration
                if (requestPermission == null) {
                    held = false
                    state = State.Denied
                } else {
                    requestPermission { granted ->
                        onMain { permissionResolved(granted, generation) }
                    }
                }
            }
        }
    }

    fun endPressHold(): Unit = onMain {
        if (!held) return@onMain
        held = false
        main.removeCallbacks(autoStop)
        if (state is State.Recording) finishRecording()
        // RequestingPermission is deliberately left until its own callback normalizes state.
    }

    fun reset() = onMain {
        held = false
        permissionRequestGeneration += 1
        main.removeCallbacks(autoStop)
        releaseRecorder(stopFirst = true)
        recordedData = null
        state = State.Idle
    }

    private fun permissionResolved(granted: Boolean, generation: Int) {
        if (generation != permissionRequestGeneration || state !is State.RequestingPermission) return
        if (!granted) {
            held = false
            state = State.Denied
        } else if (!held) {
            state = State.Idle
        } else {
            startRecordingInternal()
        }
    }

    @Suppress("DEPRECATION")
    private fun startRecordingInternal() {
        try {
            outputFile.delete()
            val local = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
            local.setAudioSource(MediaRecorder.AudioSource.MIC)
            local.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            local.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            local.setAudioSamplingRate(22_050)
            local.setAudioChannels(1)
            local.setAudioEncodingBitRate(64_000)
            local.setOutputFile(outputFile.absolutePath)
            local.setMaxDuration(MAX_DURATION_MILLIS.toInt())
            local.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) endPressHold()
            }
            local.prepare()
            local.start()
            recorder = local
            recordedData = null
            startedElapsedMillis = SystemClock.elapsedRealtime()
            state = State.Recording(startedElapsedMillis)
            main.postDelayed(autoStop, MAX_DURATION_MILLIS)
        } catch (_: Exception) {
            releaseRecorder(stopFirst = false)
            held = false
            state = State.Idle
        }
    }

    private fun finishRecording() {
        val duration = ((SystemClock.elapsedRealtime() - startedElapsedMillis) / 1000.0)
            .coerceIn(0.0, MAX_DURATION_SECONDS)
        releaseRecorder(stopFirst = true)
        recordedData = runCatching { outputFile.readBytes() }.getOrNull()?.takeIf { it.isNotEmpty() }
        state = if (recordedData != null) State.Recorded(duration) else State.Idle
    }

    private fun releaseRecorder(stopFirst: Boolean) {
        recorder?.let { local ->
            if (stopFirst) runCatching { local.stop() }
            runCatching { local.reset() }
            runCatching { local.release() }
        }
        recorder = null
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    companion object {
        const val MAX_DURATION_SECONDS = 15.0
        private const val MAX_DURATION_MILLIS = 15_000L
    }
}

class VoiceMessagePlayer(private val context: Context) {
    private var player: MediaPlayer? = null

    fun play(data: ByteArray) {
        stop()
        val file = File(context.cacheDir, "received-voice-message.m4a")
        runCatching {
            file.writeBytes(data)
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener { stop() }
                prepare()
                start()
            }
        }.onFailure { stop() }
    }

    fun stop() {
        player?.runCatching { stop() }
        player?.runCatching { release() }
        player = null
    }
}
