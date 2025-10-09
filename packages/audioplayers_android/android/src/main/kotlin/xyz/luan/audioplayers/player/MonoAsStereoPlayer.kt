package xyz.luan.audioplayers.player

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import java.io.IOException

/**
 * Decodes audio to PCM and applies per-channel volume control, enabling
 * proper stereo panning even for mono audio sources.
 *
 * @param context Android context for accessing assets and resources
 * @param onLog Optional callback for logging messages (for debugging and monitoring)
 * @param onError Optional callback for error reporting (for user feedback)
 * @param cacheConvertedSound Lambda that returns whether PCM caching should be enabled
 */
class MonoAsStereoPlayer(
    private val context: Context,
    private val onLog: ((String) -> Unit)? = null,
    private val onError: ((String, String) -> Unit)? = null,
    private val cacheConvertedSound: () -> Boolean = { false },
) {

    private data class DecodedAudioData(
        val pcmData: ByteArray,
        val sampleRate: Int,
        val channelCount: Int,
    )

    // LRU cache with 5MB size limit for decoded PCM data
    private val pcmCache = object : LinkedHashMap<String, DecodedAudioData>(
        8,  // Initial capacity
        0.75f,  // Load factor
        true,  // Access-order (LRU)
    ) {
        override fun removeEldestEntry(eldest: Map.Entry<String, DecodedAudioData>): Boolean {
            val totalBytes = values.sumOf { it.pcmData.size }
            return totalBytes > 5 * 1024 * 1024  // 5MB limit
        }
    }

    /**
     * Determines if stereo conversion is needed for the given volume settings
     */
    fun needsStereoConversion(leftVolume: Float, rightVolume: Float): Boolean {
        return leftVolume != rightVolume
    }

    /**
     * Plays audio with specified left/right channel volumes using AudioTrack
     */
    fun play(
        audioPath: String,
        leftVolume: Float,
        rightVolume: Float,
        audioAttributes: AudioAttributes,
    ) {
        // Validate volume range
        require(leftVolume in 0f..1f) { "leftVolume must be in range [0.0, 1.0], got $leftVolume" }
        require(rightVolume in 0f..1f) { "rightVolume must be in range [0.0, 1.0], got $rightVolume" }

        val mainHandler = Handler(Looper.getMainLooper())
        onLog?.invoke("MonoAsStereoPlayer: Starting stereo playback for $audioPath (L:$leftVolume R:$rightVolume)")

        Thread {
            try {
                // Use cache if enabled, otherwise decode directly
                val decodedData = if (cacheConvertedSound()) {
                    synchronized(pcmCache) {
                        pcmCache.getOrPut(audioPath) {
                            mainHandler.post {
                                onLog?.invoke("MonoAsStereoPlayer: Decoding and caching $audioPath")
                            }
                            decodeAudioToPCM(audioPath)
                        }.also {
                            mainHandler.post {
                                onLog?.invoke("MonoAsStereoPlayer: Cache ${if (pcmCache.containsKey(audioPath)) "hit" else "miss"} for $audioPath")
                            }
                        }
                    }
                } else {
                    decodeAudioToPCM(audioPath)
                }
                playAudio(decodedData, leftVolume, rightVolume, audioAttributes, mainHandler, {})
            } catch (e: Exception) {
                val errorMsg = "Failed to play stereo audio: ${e.message}"
                mainHandler.post {
                    onError?.invoke("StereoConversionError", errorMsg)
                    onLog?.invoke("MonoAsStereoPlayer error: $errorMsg")
                }
                e.printStackTrace()
            }
        }.start()
    }

    /**
     * Decodes audio file to PCM data using MediaExtractor and MediaCodec
     */
    private fun decodeAudioToPCM(audioPath: String): DecodedAudioData {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null

        try {
            // Handle asset:// URLs
            if (audioPath.startsWith("asset://")) {
                val assetPath = audioPath.substring(8)
                context.assets.openFd("flutter_assets/$assetPath").use { afd ->
                    extractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                }
            } else {
                extractor.setDataSource(audioPath)
            }

            // Find audio track
            var audioTrackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val trackFormat = extractor.getTrackFormat(i)
                val mime = trackFormat.getString(MediaFormat.KEY_MIME)
                if (mime?.startsWith("audio/") == true) {
                    audioTrackIndex = i
                    format = trackFormat
                    break
                }
            }

            if (audioTrackIndex < 0 || format == null) {
                throw IOException("No audio track found in $audioPath")
            }

            // Get audio properties
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            // Select track and create decoder
            extractor.selectTrack(audioTrackIndex)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            // Decode audio using dynamic buffer to handle any file size
            val pcmChunks = mutableListOf<ByteArray>()
            var totalSize = 0
            val bufferInfo = MediaCodec.BufferInfo()
            var isEOS = false

            while (!isEOS) {
                // Feed input
                val inputBufIndex = codec.dequeueInputBuffer(0)
                if (inputBufIndex >= 0) {
                    val inputBuf = codec.getInputBuffer(inputBufIndex)!!
                    val sampleSize = extractor.readSampleData(inputBuf, 0)

                    if (sampleSize < 0) {
                        codec.queueInputBuffer(inputBufIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    } else {
                        val presentationTime = extractor.sampleTime
                        codec.queueInputBuffer(inputBufIndex, 0, sampleSize, presentationTime, 0)
                        extractor.advance()
                    }
                }

                // Get output
                val outputBufIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
                if (outputBufIndex >= 0) {
                    val outputBuf = codec.getOutputBuffer(outputBufIndex)!!

                    if (bufferInfo.size > 0) {
                        val chunk = ByteArray(bufferInfo.size)
                        outputBuf.get(chunk)
                        pcmChunks.add(chunk)
                        totalSize += chunk.size
                    }

                    codec.releaseOutputBuffer(outputBufIndex, false)

                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        isEOS = true
                    }
                }
            }

            // Concatenate all PCM chunks into final array
            val pcmData = ByteArray(totalSize)
            var offset = 0
            for (chunk in pcmChunks) {
                System.arraycopy(chunk, 0, pcmData, offset, chunk.size)
                offset += chunk.size
            }

            return DecodedAudioData(pcmData, sampleRate, channelCount)

        } finally {
            codec?.stop()
            codec?.release()
            extractor.release()
        }
    }

    /**
     * Converts mono 16-bit PCM data to stereo based on left/right channel volumes
     */
    private fun convertMonoToStereo(monoData: ByteArray, leftVol: Float, rightVol: Float): ByteArray {
        val stereoData = ByteArray(monoData.size * 2)

        for (i in monoData.indices step 2) {
            // Read mono sample (16-bit little-endian)
            val monoSample = ((monoData[i + 1].toInt() shl 8) or (monoData[i].toInt() and 0xFF)).toShort()

            // Apply volume and create stereo samples
            val leftSample = (monoSample * leftVol).toInt().toShort()
            val rightSample = (monoSample * rightVol).toInt().toShort()

            // Write stereo samples (left first, then right)
            val stereoIndex = i * 2
            stereoData[stereoIndex] = (leftSample.toInt() and 0xFF).toByte()
            stereoData[stereoIndex + 1] = ((leftSample.toInt() shr 8) and 0xFF).toByte()
            stereoData[stereoIndex + 2] = (rightSample.toInt() and 0xFF).toByte()
            stereoData[stereoIndex + 3] = ((rightSample.toInt() shr 8) and 0xFF).toByte()
        }

        return stereoData
    }

    /**
     * Applies volumes to stereo 16-bit PCM data
     */
    private fun applyStereoVolumes(stereoData: ByteArray, leftVol: Float, rightVol: Float): ByteArray {
        val newStereoData = ByteArray(stereoData.size)

        for (i in stereoData.indices step 4) {
            // Read stereo samples (16-bit little-endian)
            val leftSample = ((stereoData[i + 1].toInt() shl 8) or (stereoData[i].toInt() and 0xFF)).toShort()
            val rightSample = ((stereoData[i + 3].toInt() shl 8) or (stereoData[i + 2].toInt() and 0xFF)).toShort()

            // Apply new volumes
            val newLeftSample = (leftSample * leftVol).toInt().toShort()
            val newRightSample = (rightSample * rightVol).toInt().toShort()

            // Write new stereo samples
            newStereoData[i] = (newLeftSample.toInt() and 0xFF).toByte()
            newStereoData[i + 1] = ((newLeftSample.toInt() shr 8) and 0xFF).toByte()
            newStereoData[i + 2] = (newRightSample.toInt() and 0xFF).toByte()
            newStereoData[i + 3] = ((newRightSample.toInt() shr 8) and 0xFF).toByte()
        }

        return newStereoData
    }

    /**
     * Plays audio using AudioTrack with stereo channel control and marker-based completion
     */
    private fun playAudio(
        data: DecodedAudioData,
        leftVolume: Float,
        rightVolume: Float,
        audioAttributes: AudioAttributes,
        mainHandler: Handler,
        onComplete: (() -> Unit)?,
    ) {
        // Convert to stereo with proper channel volumes
        val stereoData = if (data.channelCount == 1) {
            convertMonoToStereo(data.pcmData, leftVolume, rightVolume)
        } else {
            applyStereoVolumes(data.pcmData, leftVolume, rightVolume)
        }

        // Calculate buffer size
        val minBufferSize = AudioTrack.getMinBufferSize(
            data.sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        )

        if (minBufferSize == AudioTrack.ERROR_BAD_VALUE || minBufferSize == AudioTrack.ERROR) {
            throw IllegalStateException("Invalid audio parameters for AudioTrack")
        }

        val bufferSize = maxOf(minBufferSize, stereoData.size)

        // Create AudioTrack
        var audioTrack: AudioTrack = AudioTrack.Builder()
            .setAudioAttributes(audioAttributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(data.sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        if (audioTrack.state != AudioTrack.STATE_INITIALIZED) {
            // Try MODE_STREAM as fallback
            audioTrack.release()
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(data.sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            if (audioTrack.state != AudioTrack.STATE_INITIALIZED) {
                throw IllegalStateException("AudioTrack failed to initialize")
            }
        }

        // Calculate total frames for marker and expected duration
        val bytesPerFrame = 4 // 2 channels * 2 bytes per sample
        val totalFrames = stereoData.size / bytesPerFrame
        val durationMs = (totalFrames * 1000L) / data.sampleRate
        val timeoutMs = durationMs + 5000 // Duration + 5 second safety margin

        // Track whether cleanup has been performed to prevent double-release
        var cleanedUp = false

        // Set up marker listener that fires when playback reaches the end
        audioTrack.setNotificationMarkerPosition(totalFrames)
        audioTrack.setPlaybackPositionUpdateListener(
            object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(track: AudioTrack?) {
                    synchronized(audioTrack) {
                        if (cleanedUp) return
                        cleanedUp = true
                    }

                    // Marker reached - playback completed normally
                    track?.stop()
                    track?.release()

                    // Invoke completion callback on main thread
                    if (onComplete != null) {
                        mainHandler.post {
                            onComplete.invoke()
                        }
                    }
                }

                override fun onPeriodicNotification(track: AudioTrack?) {
                    // Not used
                }
            },
            mainHandler, // Listener callbacks run on main handler
        )

        // Write data and start playback
        audioTrack.write(stereoData, 0, stereoData.size)
        audioTrack.play()

        // Safety timeout: ensure AudioTrack is released even if marker never fires
        mainHandler.postDelayed(
            {
                synchronized(audioTrack) {
                    if (cleanedUp) return@postDelayed
                    cleanedUp = true
                }

                // Timeout reached - force cleanup
                audioTrack.stop()
                audioTrack.release()

                // Still invoke completion callback since audio likely finished
                if (onComplete != null) {
                    mainHandler.post {
                        onComplete.invoke()
                    }
                }
            },
            timeoutMs,
        )

    }
}
