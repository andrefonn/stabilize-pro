package com.stabilizepro.app.data.engine

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * High-performance audio-video interleaver.
 * Combines an already-encoded video-only MP4 file with the original audio track from an input video Uri,
 * ensuring strictly monotonically increasing presentation timestamps across all tracks.
 */
object MediaMuxerInterleaver {
    private const val TAG = "MediaMuxerInterleaver"
    private const val BUFFER_SIZE = 512 * 1024 // 512 KB buffer

    fun interleave(
        context: Context,
        videoOnlyFile: File,
        audioSourceUri: Uri,
        outputFile: File
    ): Boolean {
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null

        try {
            videoExtractor.setDataSource(videoOnlyFile.absolutePath)
            var videoTrackIn = -1
            var videoFormat: MediaFormat? = null

            for (i in 0 until videoExtractor.trackCount) {
                val format = videoExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) {
                    videoTrackIn = i
                    videoFormat = format
                    break
                }
            }

            if (videoTrackIn < 0 || videoFormat == null) {
                Log.e(TAG, "No video track found in temporary video file")
                return false
            }

            var audioTrackIn = -1
            var audioFormat: MediaFormat? = null

            try {
                audioExtractor.setDataSource(context, audioSourceUri, null)
                for (i in 0 until audioExtractor.trackCount) {
                    val format = audioExtractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("audio/")) {
                        audioTrackIn = i
                        audioFormat = format
                        break
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not open audio source: ${e.message}")
            }

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxerVideoTrack = muxer.addTrack(videoFormat)
            val muxerAudioTrack = if (audioTrackIn >= 0 && audioFormat != null) {
                try {
                    muxer.addTrack(audioFormat)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to add audio track: ${e.message}")
                    -1
                }
            } else {
                -1
            }

            muxer.start()

            videoExtractor.selectTrack(videoTrackIn)
            val hasAudio = muxerAudioTrack >= 0 && audioTrackIn >= 0
            if (hasAudio) {
                audioExtractor.selectTrack(audioTrackIn)
            }

            val buffer = ByteBuffer.allocateDirect(BUFFER_SIZE)
            val bufferInfo = MediaCodec.BufferInfo()

            var videoEos = false
            var audioEos = !hasAudio

            // Interleave video and audio packets strictly by sample presentation time
            while (!videoEos || !audioEos) {
                val videoTime = if (!videoEos) videoExtractor.sampleTime else Long.MAX_VALUE
                val audioTime = if (!audioEos) audioExtractor.sampleTime else Long.MAX_VALUE

                if (!audioEos && audioTime <= videoTime) {
                    // Write Audio Sample
                    bufferInfo.offset = 0
                    bufferInfo.size = audioExtractor.readSampleData(buffer, 0)
                    if (bufferInfo.size < 0) {
                        audioEos = true
                    } else {
                        bufferInfo.presentationTimeUs = audioTime
                        bufferInfo.flags = audioExtractor.sampleFlags
                        muxer.writeSampleData(muxerAudioTrack, buffer, bufferInfo)
                        audioExtractor.advance()
                    }
                } else if (!videoEos) {
                    // Write Video Sample
                    bufferInfo.offset = 0
                    bufferInfo.size = videoExtractor.readSampleData(buffer, 0)
                    if (bufferInfo.size < 0) {
                        videoEos = true
                    } else {
                        bufferInfo.presentationTimeUs = videoTime
                        bufferInfo.flags = videoExtractor.sampleFlags
                        muxer.writeSampleData(muxerVideoTrack, buffer, bufferInfo)
                        videoExtractor.advance()
                    }
                }
            }

            Log.d(TAG, "Interleaving complete. Output file size: ${outputFile.length()} bytes")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error interleaving audio and video", e)
            return false
        } finally {
            try { videoExtractor.release() } catch (ignored: Exception) {}
            try { audioExtractor.release() } catch (ignored: Exception) {}
            try {
                muxer?.stop()
                muxer?.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping muxer in interleave: ${e.message}")
            }
        }
    }
}
