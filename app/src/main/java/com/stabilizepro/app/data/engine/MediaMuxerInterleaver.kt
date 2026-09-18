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
        // Pré-validação obrigatória: arquivo de vídeo base precisa existir e ter > 1024 bytes
        if (!videoOnlyFile.exists() || videoOnlyFile.length() <= 1024L) {
            Log.e(TAG, "videoOnlyFile inválido ou vazio (${videoOnlyFile.length()} bytes). Interleave abortado.")
            return false
        }

        // Limpeza defensiva de saída prévia
        try {
            if (outputFile.exists()) {
                outputFile.delete()
            }
        } catch (ignored: Exception) {}

        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null

        var formatChanged = false
        var muxerStarted = false
        var samplesWritten = 0L
        var totalBytesWritten = 0L
        var eosReceived = false
        var primaryError: Throwable? = null

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
                    formatChanged = true
                    Log.i(TAG, "[FORMAT_CHANGED] Video track detectado no arquivo de entrada: $mime")
                    break
                }
            }

            if (videoTrackIn < 0 || videoFormat == null) {
                Log.e(TAG, "Nenhum track de vídeo válido encontrado em ${videoOnlyFile.name}")
                return false
            }

            var audioTrackIn = -1
            var audioFormat: MediaFormat? = null

            try {
                if (audioSourceUri.scheme == "content") {
                    audioExtractor.setDataSource(context, audioSourceUri, null)
                } else {
                    val path = audioSourceUri.path ?: ""
                    if (File(path).exists()) {
                        audioExtractor.setDataSource(path)
                    } else {
                        audioExtractor.setDataSource(context, audioSourceUri, null)
                    }
                }
                for (i in 0 until audioExtractor.trackCount) {
                    val format = audioExtractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("audio/")) {
                        audioTrackIn = i
                        audioFormat = format
                        Log.i(TAG, "[FORMAT_CHANGED] Audio track detectado na fonte original: $mime")
                        break
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Aviso ao carregar fonte de áudio: ${e.message}")
            }

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxerVideoTrack = muxer.addTrack(videoFormat)
            Log.i(TAG, "[TRACK_ADDED] Video track adicionado ao MediaMuxer (index: $muxerVideoTrack)")

            val muxerAudioTrack = if (audioTrackIn >= 0 && audioFormat != null) {
                try {
                    val aTrack = muxer.addTrack(audioFormat)
                    Log.i(TAG, "[TRACK_ADDED] Audio track adicionado ao MediaMuxer (index: $aTrack)")
                    aTrack
                } catch (e: Exception) {
                    Log.w(TAG, "Falha ao adicionar audio track ao muxer: ${e.message}")
                    -1
                }
            } else {
                -1
            }

            muxer.start()
            muxerStarted = true
            Log.i(TAG, "MediaMuxer iniciado (vídeo=$muxerVideoTrack, áudio=$muxerAudioTrack)")

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
                        Log.i(TAG, "[EOS_RECEIVED] Audio track atingiu EOS")
                    } else {
                        bufferInfo.presentationTimeUs = audioTime
                        bufferInfo.flags = audioExtractor.sampleFlags
                        muxer.writeSampleData(muxerAudioTrack, buffer, bufferInfo)
                        samplesWritten++
                        totalBytesWritten += bufferInfo.size
                        audioExtractor.advance()
                    }
                } else if (!videoEos) {
                    // Write Video Sample
                    bufferInfo.offset = 0
                    bufferInfo.size = videoExtractor.readSampleData(buffer, 0)
                    if (bufferInfo.size < 0) {
                        videoEos = true
                        Log.i(TAG, "[EOS_RECEIVED] Video track atingiu EOS")
                    } else {
                        bufferInfo.presentationTimeUs = videoTime
                        bufferInfo.flags = videoExtractor.sampleFlags
                        muxer.writeSampleData(muxerVideoTrack, buffer, bufferInfo)
                        samplesWritten++
                        totalBytesWritten += bufferInfo.size
                        if (samplesWritten % 60 == 0L) {
                            Log.d(TAG, "[SAMPLE_WRITTEN] Interleaved sample #$samplesWritten (${bufferInfo.size}B)")
                        }
                        videoExtractor.advance()
                    }
                }
            }

            eosReceived = true
            Log.d(TAG, "Interleaving complete. samplesWritten=$samplesWritten, totalBytes=$totalBytesWritten, fileSize=${outputFile.length()}")
            return (outputFile.exists() && outputFile.length() > 1024L && samplesWritten > 0L)
        } catch (e: Throwable) {
            primaryError = e
            Log.e(TAG, "Erro durante interleaving de áudio e vídeo: ${e.message}", e)
            return false
        } finally {
            try { videoExtractor.release() } catch (ignored: Exception) {}
            try { audioExtractor.release() } catch (ignored: Exception) {}

            // Shutdown estrito do muxer
            try {
                if (muxerStarted && samplesWritten > 0L) {
                    Log.i(TAG, "[MUXER_STOP] Finalizando MediaMuxer no interleaver ($samplesWritten samples)")
                    muxer?.stop()
                } else if (!muxerStarted) {
                    Log.w(TAG, "[MUXER_NEVER_STARTED] MediaMuxer no interleaver nunca foi iniciado")
                } else {
                    Log.w(TAG, "[MUXER_STOP] MediaMuxer iniciado porém 0 samples foram escritos. Evitando stop().")
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Erro ao parar MediaMuxer no interleaver: ${e.message}")
                if (primaryError == null) primaryError = e
            }

            try {
                muxer?.release()
            } catch (e: Throwable) {
                Log.w(TAG, "Erro ao liberar MediaMuxer no interleaver: ${e.message}")
            }

            // Excluir saída se ela for inválida ou vazia para nunca deixar arquivo de 0 bytes no disco
            if (!outputFile.exists() || outputFile.length() <= 1024L || samplesWritten == 0L) {
                try {
                    if (outputFile.exists()) {
                        outputFile.delete()
                        Log.w(TAG, "Arquivo de saída temporário de 0 bytes/corrompido excluído com sucesso.")
                    }
                } catch (ignored: Exception) {}
            }
        }
    }
}
