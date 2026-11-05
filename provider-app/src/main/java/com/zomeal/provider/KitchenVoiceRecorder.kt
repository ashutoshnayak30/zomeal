package com.zomeal.provider

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/** Raw little-endian PCM, mono 16 kHz. Audio is memory-only and never logged. */
internal class KitchenVoiceRecorder {
    private val running = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var recorder: AudioRecord? = null

    @android.annotation.SuppressLint("MissingPermission")
    fun start(onTick: (Int) -> Unit, onResult: (ByteArray?, String?) -> Unit) {
        check(running.compareAndSet(false, true))
        Thread {
            var result: ByteArray? = null; var error: String? = null
            try {
                val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(minimum > 0)
                val audio = AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, 6400))
                recorder = audio
                check(audio.state == AudioRecord.STATE_INITIALIZED)
                if (cancelled.get()) return@Thread
                audio.startRecording()
                check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                val bytes = ByteArrayOutputStream(); val buffer = ByteArray(3200); var lastSecond = -1
                val started = android.os.SystemClock.elapsedRealtime()
                while (running.get() && bytes.size() < 1920000 && android.os.SystemClock.elapsedRealtime() - started < 60000) {
                    val n = audio.read(buffer, 0, minOf(buffer.size, 1920000 - bytes.size()), AudioRecord.READ_NON_BLOCKING)
                    if (n < 0) { if (!running.get()) break else throw IllegalStateException("Microphone unavailable") }
                    if (n > 0) bytes.write(buffer, 0, n)
                    else Thread.sleep(10)
                    val seconds = ((android.os.SystemClock.elapsedRealtime() - started) / 1000).toInt()
                    if (seconds != lastSecond) { lastSecond = seconds; main.post { if (!cancelled.get()) onTick(seconds) } }
                }
                result = bytes.toByteArray().takeIf { it.size >= 3200 && it.size % 2 == 0 }
                if (result == null) error = "Recording was too short. Please try again."
            } catch (_: Exception) { error = "Microphone unavailable. Check permission and close other recording apps." }
            finally {
                running.set(false)
                runCatching { recorder?.stop() }; runCatching { recorder?.release() }; recorder = null
                val output = result; val problem = error
                main.post { if (!cancelled.get()) onResult(output, problem) }
            }
        }.start()
    }
    fun stop() { running.set(false); runCatching { recorder?.stop() } }
    fun cancel() { cancelled.set(true); stop() }
}
