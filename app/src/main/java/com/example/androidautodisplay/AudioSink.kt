package com.example.androidautodisplay

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin

class AudioSink {
    private var track: AudioTrack? = null
    @Volatile
    private var running = false
    private var thread: Thread? = null

    fun startTestTone() {
        if (running) return
        running = true
        val sampleRate = 44100
        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        val format = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()

        val audioTrack = AudioTrack(
            attributes,
            format,
            bufferSize,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        track = audioTrack
        audioTrack.play()

        thread = Thread {
            val frequency = 440.0
            val twopi = 2.0 * PI
            var phase = 0.0
            val buffer = ShortArray(bufferSize)
            while (running) {
                for (i in buffer.indices) {
                    buffer[i] = (sin(phase) * Short.MAX_VALUE * 0.25).toInt().toShort()
                    phase += twopi * frequency / sampleRate
                    if (phase > twopi) phase -= twopi
                }
                audioTrack.write(buffer, 0, buffer.size)
            }
            audioTrack.stop()
            audioTrack.release()
        }.also { it.start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
        track = null
    }
}
