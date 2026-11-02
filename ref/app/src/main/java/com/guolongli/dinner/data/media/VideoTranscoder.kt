package com.guolongli.dinner.data.media

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import com.guolongli.dinner.core.log.Logger
import java.io.File
import java.nio.ByteBuffer

/**
 * 视频压缩：把长边压到 [MAX_LONG_EDGE]，视频轨重新编码，音频轨原样搬运。
 *
 * 为什么用 Surface 而不是 ByteBuffer：解码器直接渲染到编码器的输入 Surface，
 * 全程不碰 YUV 平面布局，绕开了「解码器输出格式与编码器输入格式不一致导致绿屏」
 * 这个最常见的坑。代价是不能逐帧做像素处理——这里也不需要。
 *
 * 原则：压缩是「尽力而为」。任何一步失败、输出为空、或输出读不出时长，都会删掉
 * 半成品并保留原文件。宁可这次没压成，也不能把一段坏视频传上去。
 */
class VideoTranscoder {

    /** 成功返回 true，输出文件可用；失败返回 false，调用方必须继续用原文件。 */
    fun transcode(input: File, output: File): Boolean = runCatching {
        doTranscode(input, output)
    }.getOrElse { error ->
        Logger.w("视频压缩失败，保留原文件：${error.message}")
        output.delete()
        false
    }

    private fun doTranscode(input: File, output: File): Boolean {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var surface: android.view.Surface? = null

        try {
            extractor.setDataSource(input.absolutePath)

            val videoTrack = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index)
                    .getString(MediaFormat.KEY_MIME)
                    ?.startsWith("video/") == true
            } ?: return false
            val audioTrack = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index)
                    .getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            }

            val videoFormat = extractor.getTrackFormat(videoTrack)
            val srcMime = videoFormat.getString(MediaFormat.KEY_MIME) ?: return false
            val srcWidth = videoFormat.getInteger(MediaFormat.KEY_WIDTH)
            val srcHeight = videoFormat.getInteger(MediaFormat.KEY_HEIGHT)

            // 已经比目标分辨率小：没必要压，省一次有损重编码。
            val longEdge = maxOf(srcWidth, srcHeight)
            if (longEdge <= MAX_LONG_EDGE) return false

            val scale = MAX_LONG_EDGE.toFloat() / longEdge.toFloat()
            val outWidth = ((srcWidth * scale).toInt() / 2 * 2).coerceAtLeast(2)
            val outHeight = ((srcHeight * scale).toInt() / 2 * 2).coerceAtLeast(2)

            // 先把音频样本读进内存：mp4 的轨道要在写入前全部 addTrack，
            // 而视频轨的最终格式要等编码器真正吐出数据才知道。
            val audioSamples = audioTrack?.let { readAudioSamples(extractor, it) }.orEmpty()

            val videoEncoder = MediaCodec.createEncoderByType(MIME_AVC)
            encoder = videoEncoder
            val encoderFormat = MediaFormat.createVideoFormat(MIME_AVC, outWidth, outHeight).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
                )
                setInteger(MediaFormat.KEY_BIT_RATE, bitrateFor(outWidth, outHeight))
                setInteger(MediaFormat.KEY_FRAME_RATE, TARGET_FRAME_RATE)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SECONDS)
            }
            videoEncoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = videoEncoder.createInputSurface()
            videoEncoder.start()

            val videoDecoder = MediaCodec.createDecoderByType(srcMime)
            decoder = videoDecoder
            videoDecoder.configure(videoFormat, surface, null, 0)
            videoDecoder.start()

            val mediaMuxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mediaMuxer

            val audioFormat = audioTrack?.let { extractor.getTrackFormat(it) }
            val audioOutTrack = audioFormat?.let { mediaMuxer.addTrack(it) } ?: -1
            var videoOutTrack = -1
            var muxerStarted = false
            var audioWritten = false
            var wroteVideo = false

            val info = MediaCodec.BufferInfo()
            extractor.selectTrack(videoTrack)

            var inputDone = false
            var outputDone = false
            var frames = 0

            while (!outputDone) {
                // 1. 喂解码器
                if (!inputDone) {
                    val inIndex = videoDecoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buffer = videoDecoder.getInputBuffer(inIndex) ?: ByteBuffer.allocate(0)
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            videoDecoder.queueInputBuffer(
                                inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            videoDecoder.queueInputBuffer(
                                inIndex, 0, size, extractor.sampleTime, 0,
                            )
                            extractor.advance()
                        }
                    }
                }

                // 2. 取解码结果（渲染到编码器 Surface，缓冲区本身直接还给解码器）
                val decIndex = videoDecoder.dequeueOutputBuffer(info, TIMEOUT_US)
                if (decIndex >= 0) {
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    videoDecoder.releaseOutputBuffer(decIndex, true)
                    if (eos) videoEncoder.signalEndOfInputStream()
                }

                // 3. 取编码结果写入 muxer
                while (true) {
                    val encIndex = videoEncoder.dequeueOutputBuffer(info, 0)
                    if (encIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        break
                    }
                    if (encIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        videoOutTrack = mediaMuxer.addTrack(videoEncoder.outputFormat)
                        mediaMuxer.start()
                        muxerStarted = true
                        if (!audioWritten && audioOutTrack >= 0) {
                            writeAudio(mediaMuxer, audioOutTrack, audioSamples)
                            audioWritten = true
                        }
                        continue
                    }
                    if (encIndex < 0) continue

                    val encoded = videoEncoder.getOutputBuffer(encIndex)
                    if (encoded != null && info.size > 0 && muxerStarted) {
                        encoded.position(info.offset)
                        encoded.limit(info.offset + info.size)
                        mediaMuxer.writeSampleData(videoOutTrack, encoded, info)
                        frames += 1
                        wroteVideo = true
                    }
                    videoEncoder.releaseOutputBuffer(encIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                        break
                    }
                }
            }

            if (!wroteVideo || frames == 0) {
                Logger.w("视频压缩没有产出任何帧，保留原文件")
                output.delete()
                return false
            }
            return true
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { surface?.release() }
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
        }
    }

    private data class AudioSample(val data: ByteArray, val presentationTimeUs: Long, val flags: Int)

    /** 把音频轨整段读进内存后原样搬运，不重编码：语音/环境声没必要二次有损压缩。 */
    private fun readAudioSamples(extractor: MediaExtractor, trackIndex: Int): List<AudioSample> {
        val samples = mutableListOf<AudioSample>()
        extractor.selectTrack(trackIndex)
        val buffer = ByteBuffer.allocate(AUDIO_SAMPLE_BUFFER_BYTES)
        var total = 0L
        while (true) {
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            total += size
            if (total > MAX_AUDIO_BYTES) {
                // 音轨过大就不压了：把几十兆塞进内存不值当。
                extractor.unselectTrack(trackIndex)
                return emptyList()
            }
            val data = ByteArray(size)
            buffer.position(0)
            buffer.get(data, 0, size)
            samples += AudioSample(data, extractor.sampleTime, extractor.sampleFlags)
            if (!extractor.advance()) break
        }
        extractor.unselectTrack(trackIndex)
        return samples
    }

    private fun writeAudio(
        muxer: MediaMuxer,
        trackIndex: Int,
        samples: List<AudioSample>,
    ) {
        val info = MediaCodec.BufferInfo()
        samples.forEach { sample ->
            val buffer = ByteBuffer.wrap(sample.data)
            info.set(0, sample.data.size, sample.presentationTimeUs, sample.flags)
            runCatching { muxer.writeSampleData(trackIndex, buffer, info) }
        }
    }

    /** 码率按分辨率给：720p 约 3.5 Mbps，再按像素数线性调整，够手机看。 */
    private fun bitrateFor(width: Int, height: Int): Int {
        val pixels = width.toLong() * height.toLong()
        val estimated = pixels * BITS_PER_PIXEL
        return estimated.coerceIn(MIN_BITRATE, MAX_BITRATE).toInt()
    }

    companion object {
        /** 压缩后的长边上限。1080p 的原片会降到这个档。 */
        const val MAX_LONG_EDGE = 1280

        private const val MIME_AVC = "video/avc"
        private const val TIMEOUT_US = 10_000L
        private const val TARGET_FRAME_RATE = 30
        private const val I_FRAME_INTERVAL_SECONDS = 1
        private const val BITS_PER_PIXEL = 4L
        private const val MIN_BITRATE = 1_500_000L
        private const val MAX_BITRATE = 6_000_000L
        private const val AUDIO_SAMPLE_BUFFER_BYTES = 256 * 1024
        private const val MAX_AUDIO_BYTES = 64L * 1024 * 1024

        /**
         * 校验转码产物是否真的可用：能读出时长、有视频轨、体积大于 0。
         *
         * 只信「文件存在」是不够的——muxer 中途失败也会留下一个非空但读不出来的文件。
         */
        fun isValidVideo(file: File): Boolean {
            if (!file.exists() || file.length() == 0L) return false
            val retriever = MediaMetadataRetriever()
            return try {
                retriever.setDataSource(file.absolutePath)
                val duration = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
                val hasVideo = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
                duration > 0L && hasVideo
            } catch (error: Throwable) {
                Logger.w("视频校验失败：${error.message}")
                false
            } finally {
                runCatching { retriever.release() }
            }
        }
    }
}
