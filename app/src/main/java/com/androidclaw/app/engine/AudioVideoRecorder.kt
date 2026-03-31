package com.androidclaw.app.engine

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.os.Build
import android.view.Surface
import com.androidclaw.app.log.LogManager
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 音视频同步录制器
 *
 * 使用 MediaCodec (视频 H264 + 音频 AAC) + AudioRecord (AudioPlaybackCapture) + MediaMuxer
 * 将屏幕画面和设备内部音频同步录制到同一个 MP4 文件中。
 *
 * 优势：
 * - 输出 MP4 天然包含音视频两个轨道，时间戳对齐
 * - VideoTrimmer.trim() 裁切时自动同时处理两个轨道，无需额外操作
 * - 只录设备内部声音 (App播放的音频)，不录外部麦克风
 *
 * 要求：Android 10 (API 29) 以上
 */
class AudioVideoRecorder(private val context: Context) {

    companion object {
        private const val TAG = "AVRecorder"

        // 视频编码参数
        private const val VIDEO_MIME = MediaFormat.MIMETYPE_VIDEO_AVC  // H.264
        private const val VIDEO_FRAME_RATE = 30
        private const val VIDEO_I_FRAME_INTERVAL = 2  // 关键帧间隔 (秒)

        // 音频编码参数
        private const val AUDIO_MIME = MediaFormat.MIMETYPE_AUDIO_AAC
        private const val AUDIO_SAMPLE_RATE = 44100
        private const val AUDIO_CHANNEL_COUNT = 2   // 立体声
        private const val AUDIO_BIT_RATE = 128_000   // 128 kbps
        private const val AUDIO_CHANNEL_MASK = AudioFormat.CHANNEL_IN_STEREO
        private const val AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT

        private const val DRAIN_TIMEOUT_US = 10_000L // 10ms
    }

    // 核心组件
    private var videoEncoder: MediaCodec? = null
    private var audioEncoder: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var muxer: MediaMuxer? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var inputSurface: Surface? = null

    // 轨道索引
    private var videoTrackIndex = -1
    private var audioTrackIndex = -1

    // 状态控制
    private val isRecording = AtomicBoolean(false)
    private val isMuxerStarted = AtomicBoolean(false)
    private var formatReceivedCount = 0
    private val formatLock = Object()

    // 工作线程
    private var videoDrainThread: Thread? = null
    private var audioDrainThread: Thread? = null
    private var audioFeedThread: Thread? = null

    // 同步锁 (MediaMuxer.writeSampleData 非线程安全)
    private val muxerLock = Object()

    /**
     * 准备录制管线
     * @param projection 已授权的 MediaProjection
     * @param width 录制宽度 (必须为偶数)
     * @param height 录制高度 (必须为偶数)
     * @param density 屏幕密度
     * @param outputPath 输出 MP4 文件路径
     */
    fun prepare(
        projection: MediaProjection,
        width: Int, height: Int, density: Int,
        outputPath: String
    ) {
        log("准备音视频录制管线 (${width}x${height}, density=$density)")

        // 1. 初始化 MediaMuxer
        muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        // 2. 初始化视频编码器
        val videoBitRate = width * height * 2  // 与之前 MediaRecorder 配置一致
        val videoFormat = MediaFormat.createVideoFormat(VIDEO_MIME, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, videoBitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, VIDEO_I_FRAME_INTERVAL)
        }
        videoEncoder = MediaCodec.createEncoderByType(VIDEO_MIME).also {
            it.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = it.createInputSurface()
        }

        // 3. 创建 VirtualDisplay (绑定到视频编码器的 Surface)
        virtualDisplay = projection.createVirtualDisplay(
            "AVRecorder",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface, null, null
        )

        // 4. 初始化音频采集 (AudioPlaybackCapture - 仅内部音频)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()

                val bufferSize = AudioRecord.getMinBufferSize(
                    AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_MASK, AUDIO_ENCODING
                ).coerceAtLeast(4096)

                audioRecord = AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(captureConfig)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(AUDIO_SAMPLE_RATE)
                            .setChannelMask(AUDIO_CHANNEL_MASK)
                            .setEncoding(AUDIO_ENCODING)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize * 2)
                    .build()

                log("✅ 内部音频采集初始化成功 (AudioPlaybackCapture)")
            } catch (e: Exception) {
                log("⚠️ 内部音频采集初始化失败: ${e.message}，将仅录制视频", LogManager.Level.WARN)
                audioRecord = null
            }
        } else {
            log("⚠️ 系统版本低于 Android 10，无法录制内部音频", LogManager.Level.WARN)
        }

        // 5. 初始化音频编码器 (仅在 audioRecord 准备好时)
        if (audioRecord != null) {
            val audioFormat = MediaFormat.createAudioFormat(AUDIO_MIME, AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_COUNT).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BIT_RATE)
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            audioEncoder = MediaCodec.createEncoderByType(AUDIO_MIME).also {
                it.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
        }

        log("音视频录制管线准备完毕")
    }

    /**
     * 开始录制
     */
    fun start() {
        if (isRecording.get()) {
            log("录制已在进行中", LogManager.Level.WARN)
            return
        }

        isRecording.set(true)
        formatReceivedCount = 0
        videoTrackIndex = -1
        audioTrackIndex = -1
        isMuxerStarted.set(false)

        // 启动编码器
        videoEncoder?.start()
        audioEncoder?.start()

        // 启动音频采集
        audioRecord?.startRecording()

        // 启动视频 drain 线程 (不断从编码器取出编码后数据写入 Muxer)
        videoDrainThread = Thread({
            drainVideoEncoder()
        }, "AVRecorder-VideoDrain").also { it.start() }

        // 启动音频 feed + drain 线程
        if (audioRecord != null && audioEncoder != null) {
            audioFeedThread = Thread({
                feedAudioEncoder()
            }, "AVRecorder-AudioFeed").also { it.start() }

            audioDrainThread = Thread({
                drainAudioEncoder()
            }, "AVRecorder-AudioDrain").also { it.start() }
        } else {
            // 没有音频时，只需要视频轨道即可启动 Muxer
            // 标记 audio 格式为 "已收到" 以便 Muxer 不等待音频
            synchronized(formatLock) {
                formatReceivedCount++  // 跳过音频计数
            }
        }

        log("🎬 音视频录制已启动")
    }

    /**
     * 停止录制
     */
    fun stop() {
        if (!isRecording.getAndSet(false)) return
        log("正在停止录制...")

        // 停止音频采集
        try { audioRecord?.stop() } catch (_: Exception) {}

        // 发送 EOS 信号给视频编码器
        try { videoEncoder?.signalEndOfInputStream() } catch (_: Exception) {}

        // 等待工作线程结束
        try { audioFeedThread?.join(3000) } catch (_: Exception) {}
        try { audioDrainThread?.join(3000) } catch (_: Exception) {}
        try { videoDrainThread?.join(5000) } catch (_: Exception) {}

        // 停止 Muxer
        if (isMuxerStarted.get()) {
            try { muxer?.stop() } catch (_: Exception) {}
        }

        log("✅ 录制已停止")
    }

    /**
     * 释放所有资源
     */
    fun release() {
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null

        try { videoEncoder?.stop() } catch (_: Exception) {}
        try { videoEncoder?.release() } catch (_: Exception) {}
        videoEncoder = null

        try { audioEncoder?.stop() } catch (_: Exception) {}
        try { audioEncoder?.release() } catch (_: Exception) {}
        audioEncoder = null

        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null

        try { inputSurface?.release() } catch (_: Exception) {}
        inputSurface = null

        try { muxer?.release() } catch (_: Exception) {}
        muxer = null

        videoDrainThread = null
        audioDrainThread = null
        audioFeedThread = null
    }

    // ────────────── 内部工作线程逻辑 ──────────────

    /**
     * 持续从视频编码器取出编码后的数据，写入 Muxer
     */
    private fun drainVideoEncoder() {
        val encoder = videoEncoder ?: return
        val bufferInfo = MediaCodec.BufferInfo()

        while (isRecording.get() || true) {  // 持续到 EOS
            val outputIndex = encoder.dequeueOutputBuffer(bufferInfo, DRAIN_TIMEOUT_US)

            when {
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = encoder.outputFormat
                    synchronized(muxerLock) {
                        videoTrackIndex = muxer!!.addTrack(format)
                    }
                    log("视频轨道已添加 (trackIndex=$videoTrackIndex)")
                    onFormatReceived()
                }

                outputIndex >= 0 -> {
                    val outputBuffer = encoder.getOutputBuffer(outputIndex) ?: continue

                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        bufferInfo.size = 0
                    }

                    if (bufferInfo.size > 0 && isMuxerStarted.get()) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        synchronized(muxerLock) {
                            try {
                                muxer?.writeSampleData(videoTrackIndex, outputBuffer, bufferInfo)
                            } catch (_: Exception) {}
                        }
                    }

                    encoder.releaseOutputBuffer(outputIndex, false)

                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        log("视频编码器收到 EOS")
                        return
                    }
                }

                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!isRecording.get()) return  // 不再录制且没有更多数据
                }
            }
        }
    }

    /**
     * 持续从 AudioRecord 读取 PCM 数据，喂给音频编码器
     */
    private fun feedAudioEncoder() {
        val recorder = audioRecord ?: return
        val encoder = audioEncoder ?: return
        val bufferSize = 4096

        while (isRecording.get()) {
            val inputIndex = encoder.dequeueInputBuffer(DRAIN_TIMEOUT_US)
            if (inputIndex >= 0) {
                val inputBuffer = encoder.getInputBuffer(inputIndex) ?: continue
                inputBuffer.clear()

                val readBytes = recorder.read(inputBuffer, bufferSize)
                if (readBytes > 0) {
                    encoder.queueInputBuffer(
                        inputIndex, 0, readBytes,
                        System.nanoTime() / 1000,  // presentationTimeUs
                        0
                    )
                } else {
                    encoder.queueInputBuffer(inputIndex, 0, 0, 0, 0)
                }
            }
        }

        // 结束录制，发送 EOS
        val eosIndex = encoder.dequeueInputBuffer(DRAIN_TIMEOUT_US)
        if (eosIndex >= 0) {
            encoder.queueInputBuffer(
                eosIndex, 0, 0,
                System.nanoTime() / 1000,
                MediaCodec.BUFFER_FLAG_END_OF_STREAM
            )
        }
    }

    /**
     * 持续从音频编码器取出编码后的 AAC 数据，写入 Muxer
     */
    private fun drainAudioEncoder() {
        val encoder = audioEncoder ?: return
        val bufferInfo = MediaCodec.BufferInfo()

        while (isRecording.get() || true) {
            val outputIndex = encoder.dequeueOutputBuffer(bufferInfo, DRAIN_TIMEOUT_US)

            when {
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = encoder.outputFormat
                    synchronized(muxerLock) {
                        audioTrackIndex = muxer!!.addTrack(format)
                    }
                    log("音频轨道已添加 (trackIndex=$audioTrackIndex)")
                    onFormatReceived()
                }

                outputIndex >= 0 -> {
                    val outputBuffer = encoder.getOutputBuffer(outputIndex) ?: continue

                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        bufferInfo.size = 0
                    }

                    if (bufferInfo.size > 0 && isMuxerStarted.get()) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        synchronized(muxerLock) {
                            try {
                                muxer?.writeSampleData(audioTrackIndex, outputBuffer, bufferInfo)
                            } catch (_: Exception) {}
                        }
                    }

                    encoder.releaseOutputBuffer(outputIndex, false)

                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        log("音频编码器收到 EOS")
                        return
                    }
                }

                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!isRecording.get()) return
                }
            }
        }
    }

    /**
     * 当两路编码器都输出了 format 后，启动 Muxer
     */
    private fun onFormatReceived() {
        synchronized(formatLock) {
            formatReceivedCount++
            val expectedCount = if (audioRecord != null) 2 else 1
            if (formatReceivedCount >= expectedCount && !isMuxerStarted.get()) {
                muxer?.start()
                isMuxerStarted.set(true)
                log("✅ MediaMuxer 已启动 (${if (audioRecord != null) "音视频双轨" else "仅视频"})")
            }
        }
    }

    private fun log(msg: String, level: LogManager.Level = LogManager.Level.INFO) {
        LogManager.log("[$TAG] $msg", level)
    }
}
