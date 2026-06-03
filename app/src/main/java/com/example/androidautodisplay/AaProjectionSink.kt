package com.example.androidautodisplay

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.nio.ByteBuffer
import kotlin.concurrent.thread

object AaProjectionSink : SurfaceHolder.Callback {
    private const val VIDEO_MIME = "video/avc"
    private const val INPUT_TIMEOUT_US = 0L
    private const val MAX_AUDIO_BUFFER_DURATION_MS = 1000
    private const val TARGET_MEDIA_AUDIO_BUFFER_MS = 320
    private const val TARGET_PROMPT_AUDIO_BUFFER_MS = 120
    private const val MIN_AUDIO_QUEUE_BYTES = 48 * 1024
    private const val MAX_VIDEO_QUEUE_FRAMES = 8
    private const val SLOW_AUDIO_WRITE_MS = 80L
    private const val AUDIO_WRITE_CHUNK_DURATION_MS = 20
    private const val AUDIO_STREAM_MEDIA = 0
    private const val AUDIO_STREAM_SPEECH = 1
    private const val AUDIO_STREAM_SYSTEM = 2

    private val lock = Any()
    private val videoQueueLock = Object()

    private var surfaceHolder: SurfaceHolder? = null
    private var surface: Surface? = null
    private var videoCodec: MediaCodec? = null
    private var videoWorker: Thread? = null
    private val videoQueue = ArrayDeque<VideoFrame>()
    private var videoNeedsKeyFrame = true
    private var videoConfigQueuedForKeyFrame = false
    private var videoRecoveryDrops = 0L
    private var videoPacketLogCount = 0
    private var lastVideoConfig: ByteArray? = null
    @Volatile
    private var videoWorkerRunning = false
    private val audioRenderers = arrayOf(
        AudioRenderer(AUDIO_STREAM_MEDIA, "media"),
        AudioRenderer(AUDIO_STREAM_SPEECH, "speech"),
        AudioRenderer(AUDIO_STREAM_SYSTEM, "system")
    )

    private var configuredVideoWidth = 0
    private var configuredVideoHeight = 0

    private data class VideoFrame(
        val data: ByteArray,
        val ptsUs: Long,
        val flags: Int = 0
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
        val isIdr = isIdrFrame(data)
        val isConfig = isAvcConfigFrame(data)
        val flags = if (isConfig && !isIdr) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0
        val frame = VideoFrame(data, ptsUs, flags)
        maybeLogVideoPacket(data, ptsUs, flags, isIdr, isConfig)
        synchronized(videoQueueLock) {
            if (!videoWorkerRunning) {
                return
            }
            if (isConfig) {
                lastVideoConfig = data.copyOf()
            }
            if (videoNeedsKeyFrame) {
                if (!isIdr && !isConfig) {
                    return
                }
                if (isIdr && !videoConfigQueuedForKeyFrame) {
                    lastVideoConfig?.let { config ->
                        videoQueue.addLast(VideoFrame(config, 0L, MediaCodec.BUFFER_FLAG_CODEC_CONFIG))
                    }
                }
                videoQueue.addLast(frame)
                if (isConfig) {
                    videoConfigQueuedForKeyFrame = true
                }
                if (isIdr) {
                    videoNeedsKeyFrame = false
                    videoConfigQueuedForKeyFrame = false
                }
                videoQueueLock.notifyAll()
                return
            }
            if (videoQueue.size >= MAX_VIDEO_QUEUE_FRAMES) {
                if (isIdr) {
                    videoQueue.clear()
                } else {
                    videoQueue.clear()
                    videoNeedsKeyFrame = true
                    videoConfigQueuedForKeyFrame = false
                    videoRecoveryDrops += 1
                    if (videoRecoveryDrops <= 3 || videoRecoveryDrops % 25L == 0L) {
                        AasdkNative.nativeReportProjectionStats(
                            "video queue overflow; waiting for next IDR count=$videoRecoveryDrops"
                        )
                    }
                    return
                }
            }
            videoQueue.addLast(frame)
            videoQueueLock.notifyAll()
        }
    }

    @JvmStatic
    fun nativeConfigureAudio(streamId: Int, sampleRate: Int, channelCount: Int) {
        audioRenderer(streamId)?.configure(sampleRate, channelCount)
    }

    @JvmStatic
    fun nativeStopAudio(streamId: Int) {
        audioRenderer(streamId)?.stop()
    }

    @JvmStatic
    @Suppress("UNUSED_PARAMETER")
    fun nativePushAudio(streamId: Int, data: ByteArray, _ptsUs: Long) {
        audioRenderer(streamId)?.push(data)
    }

    private fun startVideoWorkerLocked() {
        if (videoWorkerRunning) {
            return
        }
        synchronized(videoQueueLock) {
            videoQueue.clear()
            videoNeedsKeyFrame = true
            videoConfigQueuedForKeyFrame = false
            videoRecoveryDrops = 0
            videoPacketLogCount = 0
            lastVideoConfig = null
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
                        videoQueue.removeFirst()
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
                    queueVideoBuffer(codec, inputIndex, inputBuffer, frame.data, frame.ptsUs, frame.flags)
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
            videoNeedsKeyFrame = true
            videoConfigQueuedForKeyFrame = false
            videoRecoveryDrops = 0
            lastVideoConfig = null
            videoQueueLock.notifyAll()
        }
        videoWorker?.interrupt()
        videoWorker = null
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
        ptsUs: Long,
        flags: Int
    ) {
        val normalized = normalizeAvcBuffer(data)
        inputBuffer.clear()
        inputBuffer.put(normalized)
        val queuePtsUs = if ((flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            0L
        } else if (ptsUs > 0) {
            ptsUs
        } else {
            SystemClock.elapsedRealtimeNanos() / 1000L
        }
        codec.queueInputBuffer(
            inputIndex,
            0,
            normalized.size,
            queuePtsUs,
            flags
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
        return hasNalType(data, 5)
    }

    private fun isAvcConfigFrame(data: ByteArray): Boolean {
        return hasNalType(data, 7, 8)
    }

    private fun maybeLogVideoPacket(
        data: ByteArray,
        ptsUs: Long,
        flags: Int,
        isIdr: Boolean,
        isConfig: Boolean
    ) {
        if (!isConfig && !isIdr) {
            return
        }
        if (videoPacketLogCount >= 4) {
            return
        }
        videoPacketLogCount += 1
        AasdkNative.nativeReportProjectionStats(
            "video packet size=${data.size} pts=$ptsUs flags=$flags idr=$isIdr config=$isConfig nal=${describeNalTypes(data)}"
        )
    }

    private fun describeNalTypes(data: ByteArray): String {
        val types = mutableListOf<Int>()
        var index = 0
        var foundStartCode = false
        while (index + 4 < data.size && types.size < 8) {
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
            foundStartCode = true
            types += data[start].toInt() and 0x1F
            index = start + 1
        }
        if (!foundStartCode && data.isNotEmpty()) {
            types += data[0].toInt() and 0x1F
        }
        return types.joinToString(prefix = "[", postfix = "]")
    }

    private fun hasNalType(data: ByteArray, vararg wantedTypes: Int): Boolean {
        var index = 0
        var foundStartCode = false
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
            foundStartCode = true
            val nalType = data[start].toInt() and 0x1F
            if (wantedTypes.contains(nalType)) {
                return true
            }
            index = start + 1
        }
        if (!foundStartCode && data.isNotEmpty()) {
            val nalType = data[0].toInt() and 0x1F
            return wantedTypes.contains(nalType)
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
            runCatching {
                codec.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT)
            }.onFailure { ex ->
                AasdkNative.nativeReportProjectionStats("video scaling mode failed: ${ex.message}")
            }
            codec.start()
        } catch (ex: Exception) {
            AasdkNative.nativeReportProjectionStats(
                "video codec start failed ${configuredVideoWidth}x${configuredVideoHeight}: ${ex.message}"
            )
            codec.release()
            return
        }
        videoCodec = codec
        AasdkNative.nativeReportProjectionStats(
            "video codec started ${configuredVideoWidth}x${configuredVideoHeight} name=${codec.name}"
        )
        startVideoWorkerLocked()
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
        audioRenderers.forEach { it.stop() }
    }

    private fun audioRenderer(streamId: Int): AudioRenderer? {
        return audioRenderers.getOrNull(streamId).also { renderer ->
            if (renderer == null) {
                AasdkNative.nativeReportProjectionStats("audio invalid stream=$streamId")
            }
        }
    }

    private class AudioRenderer(
        private val streamId: Int,
        private val label: String
    ) {
        private val lock = Any()
        private val queueLock = Object()
        private val queue = ArrayDeque<ByteArray>()

        @Volatile
        private var running = false
        @Volatile
        private var track: AudioTrack? = null

        private var worker: Thread? = null
        private var queuedBytes = 0
        private var inFrames = 0L
        private var inBytes = 0L
        private var droppedFrames = 0L
        private var droppedBytes = 0L
        private var writtenBytes = 0L
        private var writeShorts = 0L
        private var writeCalls = 0L
        private var underruns = 0L
        private var lastWriteMs = 0L
        private var maxWriteMs = 0L
        private var prebuffering = true
        private var prebufferStartedMs = 0L
        private var lastStatsLogMs = 0L
        private var configuredSampleRate = 0
        private var configuredChannels = 0
        private var configuredTrackBufferBytes = 0

        fun configure(sampleRate: Int, channelCount: Int) {
            synchronized(lock) {
                if (track != null &&
                    configuredSampleRate == sampleRate &&
                    configuredChannels == channelCount
                ) {
                    return
                }
                stopLocked()
                configuredSampleRate = sampleRate
                configuredChannels = channelCount
                ensureTrackLocked()
            }
        }

        fun push(data: ByteArray) {
            synchronized(queueLock) {
                if (!running) {
                    return
                }
                val maxQueueBytes = maxQueueBytesLocked()
                if (queuedBytes + data.size > maxQueueBytes) {
                    while (queue.isNotEmpty() && queuedBytes + data.size > maxQueueBytes) {
                        val dropped = queue.removeFirst()
                        queuedBytes -= dropped.size
                        droppedFrames++
                        droppedBytes += dropped.size
                    }
                    reportStatsLocked("drop")
                }
                queue.addLast(data)
                queuedBytes += data.size
                inFrames++
                inBytes += data.size
                maybeReportStatsLocked("enqueue")
                queueLock.notifyAll()
            }
        }

        fun stop() {
            synchronized(lock) {
                stopLocked()
            }
        }

        private fun ensureTrackLocked() {
            if (configuredSampleRate <= 0 || configuredChannels <= 0) {
                return
            }
            val channelMask = if (configuredChannels > 1) {
                AudioFormat.CHANNEL_OUT_STEREO
            } else {
                AudioFormat.CHANNEL_OUT_MONO
            }
            val minBuffer = AudioTrack.getMinBufferSize(
                configuredSampleRate,
                channelMask,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuffer <= 0) {
                AasdkNative.nativeReportProjectionStats("audio[$label] min buffer failed=$minBuffer")
                return
            }
            val usage = if (streamId == AUDIO_STREAM_MEDIA) {
                AudioAttributes.USAGE_MEDIA
            } else {
                AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
            }
            val contentType = if (streamId == AUDIO_STREAM_MEDIA) {
                AudioAttributes.CONTENT_TYPE_MUSIC
            } else {
                AudioAttributes.CONTENT_TYPE_SPEECH
            }
            val trackBufferBytes = maxOf(
                minBuffer * 4,
                targetPrebufferBytesLocked() * 2,
                configuredSampleRate
            )
            configuredTrackBufferBytes = trackBufferBytes
            val newTrack = AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(contentType)
                    .build(),
                AudioFormat.Builder()
                    .setSampleRate(configuredSampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(channelMask)
                    .build(),
                trackBufferBytes,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )
            newTrack.play()
            track = newTrack
            synchronized(queueLock) {
                queue.clear()
                queuedBytes = 0
                inFrames = 0L
                inBytes = 0L
                droppedFrames = 0L
                droppedBytes = 0L
                writtenBytes = 0L
                writeShorts = 0L
                writeCalls = 0L
                underruns = 0L
                lastWriteMs = 0L
                maxWriteMs = 0L
                prebuffering = true
                prebufferStartedMs = 0L
                lastStatsLogMs = 0L
                reportStatsLocked("start")
            }
            startWorkerLocked()
        }

        private fun startWorkerLocked() {
            if (running) {
                return
            }
            running = true
            worker = thread(name = "aa-audio-$label", start = true) {
                runCatching {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                }.onFailure {
                    AasdkNative.nativeReportProjectionStats(
                        "audio[$label] priority failed thread=${Thread.currentThread().name}: ${it.message}"
                    )
                }
                AasdkNative.nativeReportProjectionStats(
                    "audio[$label] writer started thread=${Thread.currentThread().name}"
                )
                try {
                    while (running) {
                        val chunk = synchronized(queueLock) {
                            while (running && shouldWaitForAudioLocked()) {
                                if (queue.isEmpty() && !prebuffering) {
                                    underruns++
                                    prebuffering = shouldRebufferAfterUnderrun()
                                    prebufferStartedMs = 0L
                                    if (underruns % 500L == 0L) {
                                        reportStatsLocked("queue_underrun")
                                    }
                                }
                                queueLock.wait(20L)
                            }
                            if (!running) {
                                return@thread
                            }
                            prebuffering = false
                            prebufferStartedMs = 0L
                            queue.removeFirst().also { queuedBytes -= it.size }
                        }
                        val activeTrack = track ?: continue
                        writeFully(activeTrack, chunk)
                    }
                } catch (_: InterruptedException) {
                } catch (ex: Throwable) {
                    AasdkNative.nativeReportProjectionStats(
                        "audio[$label] worker stopped: ${ex.javaClass.simpleName}: ${ex.message}"
                    )
                    stop()
                }
            }
        }

        private fun writeFully(activeTrack: AudioTrack, data: ByteArray) {
            var offset = 0
            while (offset < data.size && running) {
                val requested = minOf(data.size - offset, audioWriteChunkBytes())
                val beforeMs = SystemClock.elapsedRealtime()
                val written = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    activeTrack.write(data, offset, requested, AudioTrack.WRITE_BLOCKING)
                } else {
                    activeTrack.write(data, offset, requested)
                }
                val writeMs = SystemClock.elapsedRealtime() - beforeMs
                if (written <= 0) {
                    synchronized(queueLock) {
                        writeShorts++
                        reportStatsLocked(if (written == 0) "write_backpressure" else "write_error_$written")
                    }
                    break
                }
                offset += written
                synchronized(queueLock) {
                    writtenBytes += written.toLong()
                    writeCalls += 1
                    lastWriteMs = writeMs
                    maxWriteMs = maxOf(maxWriteMs, writeMs)
                    if (written < requested) {
                        writeShorts++
                    }
                    if (writeMs > SLOW_AUDIO_WRITE_MS) {
                        maybeReportStatsLocked("slow_write_${writeMs}ms")
                    } else {
                        maybeReportStatsLocked("write")
                    }
                }
            }
        }

        private fun stopLocked() {
            running = false
            synchronized(queueLock) {
                queue.clear()
                queuedBytes = 0
                queueLock.notifyAll()
            }
            worker?.interrupt()
            worker = null
            track?.runCatching {
                stop()
                release()
            }
            track = null
        }

        private fun maybeReportStatsLocked(reason: String) {
            val nowMs = SystemClock.elapsedRealtime()
            if (nowMs - lastStatsLogMs >= 5_000L) {
                lastStatsLogMs = nowMs
                reportStatsLocked(reason)
            }
        }

        private fun reportStatsLocked(reason: String) {
            val activeTrack = track
            val playbackHead = activeTrack?.runCatching { playbackHeadPosition }?.getOrDefault(-1) ?: -1
            val trackUnderruns = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                activeTrack?.runCatching { underrunCount }?.getOrDefault(-1) ?: -1
            } else {
                -1
            }
            AasdkNative.nativeReportProjectionStats(
                "audio[$label] reason=$reason thread=${Thread.currentThread().name} " +
                    "inFrames=$inFrames inBytes=$inBytes writtenBytes=$writtenBytes " +
                    "queuedBytes=$queuedBytes queuedMs=${queuedDurationMsLocked()} queuedFrames=${queue.size} " +
                    "droppedFrames=$droppedFrames droppedBytes=$droppedBytes shortWrites=$writeShorts " +
                    "underruns=$underruns writeCalls=$writeCalls lastWriteMs=$lastWriteMs " +
                    "maxWriteMs=$maxWriteMs prebuffering=$prebuffering targetMs=${targetPrebufferMs()} " +
                    "trackUnderruns=$trackUnderruns trackBufferBytes=$configuredTrackBufferBytes " +
                    "playbackHead=$playbackHead sr=$configuredSampleRate ch=$configuredChannels"
            )
        }

        private fun shouldWaitForAudioLocked(): Boolean {
            if (queue.isEmpty()) {
                prebufferStartedMs = 0L
                return true
            }
            if (!prebuffering || queuedBytes >= targetPrebufferBytesLocked()) {
                prebufferStartedMs = 0L
                return false
            }
            val nowMs = SystemClock.elapsedRealtime()
            if (prebufferStartedMs == 0L) {
                prebufferStartedMs = nowMs
            }
            return nowMs - prebufferStartedMs < targetPrebufferMs()
        }

        private fun shouldRebufferAfterUnderrun(): Boolean {
            return streamId != AUDIO_STREAM_MEDIA
        }

        private fun maxQueueBytesLocked(): Int {
            if (configuredSampleRate <= 0 || configuredChannels <= 0) {
                return MIN_AUDIO_QUEUE_BYTES
            }
            val bytesPerMillisecond = (configuredSampleRate * configuredChannels * 2) / 1000
            return (bytesPerMillisecond * MAX_AUDIO_BUFFER_DURATION_MS)
                .coerceAtLeast(MIN_AUDIO_QUEUE_BYTES)
        }

        private fun targetPrebufferBytesLocked(): Int {
            if (configuredSampleRate <= 0 || configuredChannels <= 0) {
                return MIN_AUDIO_QUEUE_BYTES / 2
            }
            val bytesPerMillisecond = (configuredSampleRate * configuredChannels * 2) / 1000
            return (bytesPerMillisecond * targetPrebufferMs()).coerceAtLeast(1)
        }

        private fun audioWriteChunkBytes(): Int {
            if (configuredSampleRate <= 0 || configuredChannels <= 0) {
                return 4096
            }
            val bytesPerFrame = configuredChannels * 2
            val frames = (configuredSampleRate * AUDIO_WRITE_CHUNK_DURATION_MS) / 1000
            return (frames * bytesPerFrame).coerceAtLeast(bytesPerFrame)
        }

        private fun queuedDurationMsLocked(): Int {
            if (configuredSampleRate <= 0 || configuredChannels <= 0) {
                return 0
            }
            val bytesPerMillisecond = (configuredSampleRate * configuredChannels * 2) / 1000
            return if (bytesPerMillisecond <= 0) 0 else queuedBytes / bytesPerMillisecond
        }

        private fun targetPrebufferMs(): Int {
            return if (streamId == AUDIO_STREAM_MEDIA) {
                TARGET_MEDIA_AUDIO_BUFFER_MS
            } else {
                TARGET_PROMPT_AUDIO_BUFFER_MS
            }
        }

    }
}
