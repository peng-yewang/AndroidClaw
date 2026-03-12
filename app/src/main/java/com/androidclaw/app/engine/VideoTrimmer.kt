package com.androidclaw.app.engine

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.androidclaw.app.log.LogManager
import java.io.File

/**
 * 视频裁剪工具
 * 使用 MediaExtractor + MediaMuxer 从录屏文件中精确裁剪目标时间段
 */
object VideoTrimmer {

    /**
     * 裁剪视频文件
     * @param srcPath   源视频路径 (完整录屏临时文件)
     * @param dstPath   输出路径
     * @param startMs   裁剪开始时间 (毫秒), 相对于视频开头
     * @param endMs     裁剪结束时间 (毫秒), -1 表示到结尾
     * @return 是否成功
     */
    fun trim(srcPath: String, dstPath: String, startMs: Long, endMs: Long = -1): Boolean {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null

        return try {
            extractor.setDataSource(srcPath)

            val trackCount = extractor.trackCount
            val startUs = startMs * 1000L
            val endUs = if (endMs < 0) Long.MAX_VALUE else endMs * 1000L

            muxer = MediaMuxer(dstPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // 映射: extractor track → muxer track
            val trackMap = mutableMapOf<Int, Int>()
            for (i in 0 until trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                // 只保留音视频轨道
                if (mime.startsWith("audio/") || mime.startsWith("video/")) {
                    val muxerTrackIndex = muxer.addTrack(format)
                    trackMap[i] = muxerTrackIndex
                    extractor.selectTrack(i)
                }
            }

            if (trackMap.isEmpty()) {
                LogManager.log("视频裁剪: 未找到有效轨道", LogManager.Level.ERROR)
                return false
            }

            muxer.start()

            // Seek 到起始位置
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val bufferSize = 1024 * 1024 // 1MB
            val buffer = java.nio.ByteBuffer.allocate(bufferSize)
            val bufferInfo = android.media.MediaCodec.BufferInfo()

            var firstPresentationTimeUs = -1L

            while (true) {
                bufferInfo.offset = 0
                bufferInfo.size = extractor.readSampleData(buffer, 0)

                if (bufferInfo.size < 0) break // EOF

                val sampleTime = extractor.sampleTime
                if (sampleTime < startUs) {
                    extractor.advance()
                    continue
                }
                if (sampleTime > endUs) break

                // 记录第一帧时间，用于时间戳归零
                if (firstPresentationTimeUs < 0) {
                    firstPresentationTimeUs = sampleTime
                }

                val trackIndex = extractor.sampleTrackIndex
                val muxerTrack = trackMap[trackIndex]
                if (muxerTrack == null) {
                    extractor.advance()
                    continue
                }

                bufferInfo.presentationTimeUs = sampleTime - firstPresentationTimeUs
                bufferInfo.flags = extractor.sampleFlags

                muxer.writeSampleData(muxerTrack, buffer, bufferInfo)
                extractor.advance()
            }

            muxer.stop()
            LogManager.log("视频裁剪完成: $dstPath", LogManager.Level.SUCCESS)
            true
        } catch (e: Exception) {
            LogManager.log("视频裁剪失败: ${e.message}", LogManager.Level.ERROR)
            // 删除不完整的输出文件
            try { File(dstPath).delete() } catch (_: Exception) {}
            false
        } finally {
            extractor.release()
            try { muxer?.release() } catch (_: Exception) {}
        }
    }
}
