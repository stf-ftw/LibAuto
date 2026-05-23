package com.example.androidautodisplay

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean

object MicInputBridge {
    private const val SAMPLE_RATE = 16_000
    private const val CHANNEL_COUNT = 1
    private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

    private val running = AtomicBoolean(false)
    private val permissionGranted = AtomicBoolean(false)

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var audioRecord: AudioRecord? = null

    @Volatile
    private var readerThread: Thread? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
        setPermissionGranted(hasRecordAudioPermission())
    }

    fun hasRecordAudioPermission(): Boolean {
        val context = appContext ?: return false
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun setPermissionGranted(granted: Boolean) {
        permissionGranted.set(granted)
        AasdkNative.nativeSetMicrophonePermission(granted)
        if (!granted) {
            stop()
        }
    }

    @JvmStatic
    fun nativeStart(): Boolean {
        if (!permissionGranted.get()) {
            return false
        }
        if (running.get()) {
            return true
        }
        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            ENCODING
        )
        if (minBufferSize <= 0) {
            return false
        }
        val bufferSize = maxOf(minBufferSize * 2, SAMPLE_RATE / 5)
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            ENCODING,
            bufferSize
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }
        return try {
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                record.release()
                false
            } else {
                audioRecord = record
                running.set(true)
                readerThread = Thread({
                    readLoop(bufferSize)
                }, "AA-MicInput").apply { start() }
                true
            }
        } catch (_: SecurityException) {
            record.release()
            false
        } catch (_: IllegalStateException) {
            record.release()
            false
        }
    }

    @JvmStatic
    fun nativeStop() {
        stop()
    }

    private fun stop() {
        running.set(false)
        readerThread?.interrupt()
        readerThread = null
        audioRecord?.runCatching {
            stop()
            release()
        }
        audioRecord = null
    }

    private fun readLoop(bufferSize: Int) {
        val localRecord = audioRecord ?: return
        val buffer = ByteArray(bufferSize)
        while (running.get()) {
            val read = try {
                localRecord.read(buffer, 0, buffer.size)
            } catch (_: IllegalStateException) {
                break
            }
            if (read <= 0) {
                continue
            }
            val payload = if (read == buffer.size) {
                buffer.copyOf()
            } else {
                buffer.copyOf(read)
            }
            val ptsUs = SystemClock.elapsedRealtimeNanos() / 1000L
            if (!AasdkNative.nativeOnMicrophoneFrame(payload, ptsUs)) {
                break
            }
        }
        stop()
    }
}
