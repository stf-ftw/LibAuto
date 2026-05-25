package com.example.androidautodisplay

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.SystemClock
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.nio.ByteBuffer
import kotlin.concurrent.thread

object AaProjectionSink : SurfaceHolder.Callback {
    private const val VIDEO_MIME = "video/avc"
    private const val INPUT_TIMEOUT_US = 0L
    private const val MAX_AUDIO_BUFFER_DURATION_MS = 500
    private const val MIN_AUDIO_QUEUE_BYTES = 32 * 1024
    private const val MAX_VIDEO_QUEUE_FRAMES = 6
    private const val SLOW_AUDIO_WRITE_MS = 250L

    private val lock = Any()
    private val audioQueueLock = Object()
    private val videoQueueLock = Object()

    private var surfaceHolder: SurfaceHolder? = null
    private var surface: Surface? = null
    private var videoCodec: MediaCodec? = null
    private var audioTrack: AudioTrack? = null
    private var videoWorker: Thread? = null
    private val videoQueue = ArrayDeque<VideoFrame>()
    @Volatile
    private var videoWorkerRunning = false
    private var audioWorker: Thread? = null
    private val audioQueue = ArrayDeque<ByteArray>()
    private var queuedAudioBytes = 0
    private var audioInFrames = 0L
    private var audioInBytes = 0L
    private var audioDroppedFrames = 0L
    private var audioDroppedBytes = 0L
    private var audioWrittenBytes = 0L
    private var audioWriteShorts = 0L
    private var lastAudioStatsLogMs = 0L
    @Volatile
    private var audioWorkerRunning = false

    private var configuredVideoWidth = 0
    private var configuredVideoHeight = 0
    private var configuredAudioSampleRate = 0
    private var configuredAudioChannels = 0

    private data class VideoFrame(
        val data: ByteArray,
        val ptsUs: Long
    )

    fun bindSurfaceView(surfaceView: SurfaceView) {
        synchronized(lock) {
            surfaceHolder?.removeCallback(this)
            surfaceHolder = surfaceView.holder.also { holder ->
                holder.addCallback(this)
                val currentSurface = holder.surface
                if (currentSurface != null && currentSurface.isValid) {
                    surface = currentSurface
                    ensureVideoCodecLocked()
                }
            }
        }
    }

    fun release() {
        synchronized(lock) {
            surfaceHolder?.removeCallback(this)
            surfaceHolder = null
            surface = null
            stopVideoLocked()
            stopAudioLocked()
        }
    }

    fun resetSession() {
        synchronized(lock) {
            stopVideoLocked()
            stopAudioLocked()
        }
    }

    @JvmStatic
    fun nativeConfigureVideo(width: Int, height: Int) {
        synchronized(lock) {
            configuredVideoWidth = width
            configuredVideoHeight = height
            ensureVideoCodecLocked()
        }
    }

    @JvmStatic
    fun nativeStopVideo() {
        synchronized(lock) {
            stopVideoLocked()
        }
    }

    @JvmStatic
    fun nativePushVideo(data: ByteArray, ptsUs: Long) {
        val frame = VideoFrame(data, ptsUs)
        synchronized(videoQueueLock) {
            if (!videoWorkerRunning) {
                return
            }
            if (videoQueue.size >= MAX_VIDEO_QUEUE_FRAMES) {
                if (isIdrFrame(frame.data)) {
                    videoQueue.clear()
                } else {
                    return
                }
            }
            videoQueue.addLast(frame)
            videoQueueLock.notifyAll()
        }
    }

    @JvmStatic
    fun nativeConfigureAudio(sampleRate: Int, channelCount: Int) {
        synchronized(lock) {
            configuredAudioSampleRate = sampleRate
            configuredAudioChannels = channelCount
            ensureAudioTrackLocked()
        }
    }

    @JvmStatic
    fun nativeStopAudio() {
        synchronized(lock) {
            stopAudioLocked()
        }
    }

    @JvmStatic
    fun nativePushAudio(data: ByteArray, ptsUs: Long) {
        val frame = data
        synchronized(audioQueueLock) {
            if (!audioWorkerRunning) {
                return
            }
            val maxQueueBytes = maxAudioQueueBytesLocked()
            if (queuedAudioBytes + frame.size > maxQueueBytes) {
                while (audioQueue.isNotEmpty() && queuedAudioBytes + frame.size > maxQueueBytes) {
                    val dropped = audioQueue.removeFirst()
                    queuedAudioBytes -= dropped.size
                    audioDroppedFrames++
                    audioDroppedBytes += dropped.size
                }
                reportAudioStatsLocked("drop")
            }
            audioQueue.addLast(frame)
            queuedAudioBytes += frame.size
            audioInFrames++
            audioInBytes += frame.size
            maybeReportAudioStatsLocked("enqueue")
            audioQueueLock.notifyAll()
        }
    }

    private fun startAudioWorkerLocked() {
        if (audioWorkerRunning) {
            return
        }
        audioWorkerRunning = true
        audioWorker = thread(name = "aa-audio-playback", start = true) {
            try {
                while (audioWorkerRunning) {
                    val chunk = synchronized(audioQueueLock) {
                        while (audioWorkerRunning && audioQueue.isEmpty()) {
                            audioQueueLock.wait()
                        }
                        if (!audioWorkerRunning) {
                            return@thread
                        }
                        audioQueue.removeFirst().also { queuedAudioBytes -= it.size }
                    }
                    val track = synchronized(lock) { audioTrack } ?: continue
                    writeAudioFully(track, chunk)
                }
            } catch (_: InterruptedException) {
            } catch (ex: Throwable) {
                AasdkNative.nativeReportProjectionStats("audio worker stopped: ${ex.javaClass.simpleName}: ${ex.message}")
                synchronized(lock) {
                    stopAudioLocked()
                }
            }
        }
    }

    private fun startVideoWorkerLocked() {
        if (videoWorkerRunning) {
            return
        }
        videoWorkerRunning = true
        videoWorker = thread(name = "aa-video-decode", start = true) {
            try {
                while (videoWorkerRunning) {
                    val frame = synchronized(videoQueueLock) {
                        while (videoWorkerRunning && videoQueue.isEmpty()) {
                            videoQueueLock.wait()
                        }
                        if (!videoWorkerRunning) {
                            return@thread
                        }
                        videoQueue.removeLast().also { videoQueue.clear() }
                    }
                    val codec = synchronized(lock) { videoCodec } ?: continue
                    var inputIndex = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
                    if (inputIndex < 0) {
                        drainVideoCodec(codec)
                        inputIndex = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
                    }
                    if (inputIndex < 0) {
                        continue
                    }
                    val inputBuffer = codec.getInputBuffer(inputIndex) ?: continue
                    queueVideoBuffer(codec, inputIndex, inputBuffer, frame.data, frame.ptsUs)
                }
            } catch (_: InterruptedException) {
            } catch (ex: Throwable) {
                AasdkNative.nativeReportProjectionStats("video worker stopped: ${ex.javaClass.simpleName}: ${ex.message}")
                synchronized(lock) {
                    stopVideoLocked()
                }
            }
        }
    }

    private fun stopVideoWorkerLocked() {
        videoWorkerRunning = false
        synchronized(videoQueueLock) {
            videoQueue.clear()
            videoQueueLock.notifyAll()
        }
        videoWorker?.interrupt()
        videoWorker = null
    }

    private fun stopAudioWorkerLocked() {
        audioWorkerRunning = false
        synchronized(audioQueueLock) {
            audioQueue.clear()
            queuedAudioBytes = 0
            audioQueueLock.notifyAll()
        }
        audioWorker?.interrupt()
        audioWorker = null
    }

    private fun writeAudioFully(track: AudioTrack, data: ByteArray) {
        var offset = 0
        while (offset < data.size && audioWorkerRunning) {
            val beforeMs = SystemClock.elapsedRealtime()
            val written = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                track.write(data, offset, data.size - offset, AudioTrack.WRITE_BLOCKING)
            } else {
                track.write(data, offset, data.size - offset)
            }
            val writeMs = SystemClock.elapsedRealtime() - beforeMs
            if (written <= 0) {
                synchronized(audioQueueLock) {
                    audioWriteShorts++
                    reportAudioStatsLocked("write_error_$written")
                }
                break
            }
            offset += written
            synchronized(audioQueueLock) {
                audioWrittenBytes += written.toLong()
                if (written < data.size - (offset - written)) {
                    audioWriteShorts++
                }
                if (writeMs > SLOW_AUDIO_WRITE_MS) {
                    reportAudioStatsLocked("slow_write_${writeMs}ms")
                } else {
                    maybeReportAudioStatsLocked("write")
                }
            }
        }
    }

    private fun maybeReportAudioStatsLocked(reason: String) {
        val nowMs = SystemClock.elapsedRealtime()
        if (nowMs - lastAudioStatsLogMs >= 5_000L) {
            lastAudioStatsLogMs = nowMs
            reportAudioStatsLocked(reason)
        }
    }

    private fun reportAudioStatsLocked(reason: String) {
        val playbackHead = audioTrack?.playbackHeadPosition ?: -1
        AasdkNative.nativeReportProjectionStats(
            "audio reason=$reason inFrames=$audioInFrames inBytes=$audioInBytes " +
                "writtenBytes=$audioWrittenBytes queuedBytes=$queuedAudioBytes queuedFrames=${audioQueue.size} " +
                "droppedFrames=$audioDroppedFrames droppedBytes=$audioDroppedBytes shortWrites=$audioWriteShorts " +
                "playbackHead=$playbackHead sr=$configuredAudioSampleRate ch=$configuredAudioChannels"
        )
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        synchronized(lock) {
            surface = holder.surface
            ensureVideoCodecLocked()
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        synchronized(lock) {
            surface = holder.surface
            ensureVideoCodecLocked()
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        synchronized(lock) {
            surface = null
            stopVideoLocked()
        }
    }

    private fun queueVideoBuffer(
        codec: MediaCodec,
        inputIndex: Int,
        inputBuffer: ByteBuffer,
        data: ByteArray,
        ptsUs: Long
    ) {
        val normalized = normalizeAvcBuffer(data)
        inputBuffer.clear()
        inputBuffer.put(normalized)
        codec.queueInputBuffer(
            inputIndex,
            0,
            normalized.size,
            if (ptsUs > 0) ptsUs else SystemClock.elapsedRealtimeNanos() / 1000L,
            0
        )
        drainVideoCodec(codec)
    }

    private fun normalizeAvcBuffer(data: ByteArray): ByteArray {
        if (data.size >= 4 &&
            data[0] == 0.toByte() &&
            data[1] == 0.toByte() &&
            ((data[2] == 1.toByte()) ||
                (data[2] == 0.toByte() && data[3] == 1.toByte()))
        ) {
            return data
        }
        val prefix = byteArrayOf(0x00, 0x00, 0x00, 0x01)
        return prefix + data
    }

    private fun isIdrFrame(data: ByteArray): Boolean {
        var index = 0
        while (index + 4 < data.size) {
            val start = when {
                data[index] == 0.toByte() &&
                    data[index + 1] == 0.toByte() &&
                    data[index + 2] == 1.toByte() -> index + 3
                index + 5 < data.size &&
                    data[index] == 0.toByte() &&
                    data[index + 1] == 0.toByte() &&
                    data[index + 2] == 0.toByte() &&
                    data[index + 3] == 1.toByte() -> index + 4
                else -> {
                    index += 1
                    continue
                }
            }
            val nalType = data[start].toInt() and 0x1F
            if (nalType == 5) {
                return true
            }
            index = start + 1
        }
        return false
    }

    private fun drainVideoCodec(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            when (val outputIndex = codec.dequeueOutputBuffer(info, 0)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    AasdkNative.nativeReportProjectionStats("video output format ${codec.outputFormat}")
                    continue
                }
                in 0..Int.MAX_VALUE -> codec.releaseOutputBuffer(outputIndex, true)
            }
        }
    }

    private fun ensureVideoCodecLocked() {
        val activeSurface = surface ?: return
        if (!activeSurface.isValid) {
            return
        }
        if (configuredVideoWidth <= 0 || configuredVideoHeight <= 0) {
            return
        }
        if (videoCodec != null) {
            return
        }
        val codec = try {
            MediaCodec.createDecoderByType(VIDEO_MIME)
        } catch (ex: Exception) {
            AasdkNative.nativeReportProjectionStats("video codec create failed: ${ex.message}")
            return
        }
        val format = MediaFormat.createVideoFormat(
            VIDEO_MIME,
            configuredVideoWidth,
            configuredVideoHeight
        ).apply {
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, configuredVideoWidth * configuredVideoHeight)
            setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
        try {
            codec.configure(format, activeSurface, null, 0)
            codec.start()
        } catch (ex: Exception) {
            AasdkNative.nativeReportProjectionStats(
                "video codec start failed ${configuredVideoWidth}x${configuredVideoHeight}: ${ex.message}"
            )
            codec.release()
            return
        }
        videoCodec = codec
        AasdkNative.nativeReportProjectionStats("video codec started ${configuredVideoWidth}x${configuredVideoHeight}")
        startVideoWorkerLocked()
    }

    private fun ensureAudioTrackLocked() {
        if (configuredAudioSampleRate <= 0 || configuredAudioChannels <= 0) {
            return
        }
        if (audioTrack != null) {
            return
        }
        val channelMask = if (configuredAudioChannels > 1) {
            AudioFormat.CHANNEL_OUT_STEREO
        } else {
            AudioFormat.CHANNEL_OUT_MONO
        }
        val minBuffer = AudioTrack.getMinBufferSize(
            configuredAudioSampleRate,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val track = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(configuredAudioSampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(channelMask)
                .build(),
            minBuffer.coerceAtLeast(configuredAudioSampleRate),
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        track.play()
        audioTrack = track
        synchronized(audioQueueLock) {
            audioInFrames = 0L
            audioInBytes = 0L
            audioDroppedFrames = 0L
            audioDroppedBytes = 0L
            audioWrittenBytes = 0L
            audioWriteShorts = 0L
            lastAudioStatsLogMs = 0L
            reportAudioStatsLocked("start")
        }
        startAudioWorkerLocked()
    }

    private fun maxAudioQueueBytesLocked(): Int {
        if (configuredAudioSampleRate <= 0 || configuredAudioChannels <= 0) {
            return MIN_AUDIO_QUEUE_BYTES
        }
        val bytesPerMillisecond =
            (configuredAudioSampleRate * configuredAudioChannels * 2) / 1000
        return (bytesPerMillisecond * MAX_AUDIO_BUFFER_DURATION_MS)
            .coerceAtLeast(MIN_AUDIO_QUEUE_BYTES)
    }

    private fun stopVideoLocked() {
        stopVideoWorkerLocked()
        videoCodec?.runCatching {
            stop()
            release()
        }
        videoCodec = null
    }

    private fun stopAudioLocked() {
        stopAudioWorkerLocked()
        audioTrack?.runCatching {
            stop()
            release()
        }
        audioTrack = null
    }
}
