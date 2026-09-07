package com.stabilizepro.app.data.engine

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
import java.nio.ByteBuffer

/**
 * Utility to extract and remux the original audio track into the stabilized MP4 container
 * using MediaExtractor and MediaMuxer.
 */
object AudioMuxerHelper {
    private const val TAG = "AudioMuxerHelper"
    private const val DEFAULT_BUFFER_SIZE = 256 * 1024 // 256 KB

    fun findAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) {
                return i
            }
        }
        return -1
    }

    /**
     * Copies all audio samples from [inputUri] into the opened [muxer] on track index [audioTrackIndex].
     */
    fun copyAudioTrack(
        context: Context,
        inputUri: Uri,
        muxer: MediaMuxer,
        audioTrackIndex: Int
    ) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, inputUri, null)
            val trackIndex = findAudioTrack(extractor)
            if (trackIndex < 0) {
                Log.w(TAG, "No audio track found in source video")
                return
            }

            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val bufferSize = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(DEFAULT_BUFFER_SIZE)
            } else {
                DEFAULT_BUFFER_SIZE
            }

            val buffer = ByteBuffer.allocateDirect(bufferSize)
            val bufferInfo = MediaCodec.BufferInfo()

            while (true) {
                bufferInfo.offset = 0
                bufferInfo.size = extractor.readSampleData(buffer, 0)
                if (bufferInfo.size < 0) {
                    break
                }
                bufferInfo.presentationTimeUs = extractor.sampleTime
                bufferInfo.flags = extractor.sampleFlags

                muxer.writeSampleData(audioTrackIndex, buffer, bufferInfo)
                extractor.advance()
            }
            Log.d(TAG, "Audio track remuxed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy audio track", e)
        } finally {
            try {
                extractor.release()
            } catch (ignored: Exception) {}
        }
    }
}
