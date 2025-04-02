package aritra.seal.finalyearapp

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

class AudioFeatureExtractor(private val context: Context) {

    companion object {
        private const val SAMPLE_RATE = 22050
        private const val N_FFT = 2048
        private const val HOP_LENGTH = 512
        private const val N_MFCC = 13
        private const val N_CHROMA = 12
        private const val N_MELS = 128
        private const val MAX_ABS_VAL = 32768f // For 16-bit PCM conversion

        // Pre-computed Mel filter bank
        private val MEL_FB = createMelFilterBank()

        private fun createMelFilterBank(): Array<FloatArray> {
            val nFilters = 40
            val minHz = 0.0
            val maxHz = SAMPLE_RATE / 2.0
            val minMel = hzToMel(minHz)
            val maxMel = hzToMel(maxHz)

            val binFreqs = DoubleArray(N_FFT / 2 + 1) { i ->
                i * SAMPLE_RATE / N_FFT.toDouble()
            }

            val melPoints = DoubleArray(nFilters + 2) { i ->
                minMel + i * (maxMel - minMel) / (nFilters + 1)
            }

            return Array(nFilters) { filterIdx ->
                FloatArray(N_FFT / 2 + 1) { binIdx ->
                    val freq = binFreqs[binIdx]
                    val mel = hzToMel(freq)

                    when {
                        mel < melPoints[filterIdx] -> 0f
                        mel <= melPoints[filterIdx + 1] ->
                            ((mel - melPoints[filterIdx]) /
                                    (melPoints[filterIdx + 1] - melPoints[filterIdx])).toFloat()
                        mel <= melPoints[filterIdx + 2] ->
                            ((melPoints[filterIdx + 2] - mel) /
                                    (melPoints[filterIdx + 2] - melPoints[filterIdx + 1])).toFloat()
                        else -> 0f
                    }
                }
            }
        }

        private fun hzToMel(hz: Double): Double = 2595 * log10(1 + hz / 700.0)
        private fun log10(x: Double): Double = ln(x) / ln(10.0)
    }

    fun extractFeatures(audioFile: File): FloatArray {
        val audioData = try {
            readAudioFile(audioFile)
        } catch (e: Exception) {
            throw FeatureExtractionError("Failed to read audio file: ${e.message}")
        }

        if (audioData.isEmpty()) {
            throw FeatureExtractionError("No audio data found")
        }

        return try {
            val features = mutableListOf<Float>()

            // MFCC (13 coefficients)
            val mfccFeatures = computeMFCC(audioData)
            features.addAll(mfccFeatures.toList())

            // Chroma (12 features)
            val chromaFeatures = computeChroma(audioData)
            features.addAll(chromaFeatures.toList())

            // Mel Spectrogram (128 features)
            val melFeatures = computeMelSpectrogram(audioData)
            features.addAll(melFeatures.toList())

            features.toFloatArray()
        } catch (e: Exception) {
            throw FeatureExtractionError("Feature extraction failed: ${e.message}")
        }
    }

    private fun readAudioFile(file: File): FloatArray {
        if (!file.exists() || file.length() == 0L) {
            throw IOException("Audio file is empty or doesn't exist")
        }

        val audioData = when (file.extension.lowercase()) {
            "wav" -> readWavFile(file)
            "mp3", "3gp", "aac" -> readMediaFile(file)
            else -> throw IllegalArgumentException("Unsupported format: ${file.extension}")
        }

        // Check for silent audio
        if (audioData.all { abs(it) < 0.001f }) {
            throw IOException("Audio is silent or too quiet")
        }

        return audioData
    }

    private fun readWavFile(file: File): FloatArray {
        val inputStream = FileInputStream(file)
        val bytes = inputStream.readBytes()
        inputStream.close()

        // Skip WAV header (typically 44 bytes)
        val offset = 44
        if (bytes.size <= offset) {
            throw IOException("WAV file is too small or corrupted")
        }

        val buffer = ByteBuffer.wrap(bytes, offset, bytes.size - offset)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()

        return FloatArray(buffer.remaining()) { i ->
            buffer.get(i).toFloat() / MAX_ABS_VAL
        }
    }

    private fun readMediaFile(file: File): FloatArray {
        val extractor = MediaExtractor().apply {
            setDataSource(file.absolutePath)
        }

        if (extractor.trackCount == 0) {
            extractor.release()
            throw IOException("No audio tracks found in file")
        }

        val format = extractor.getTrackFormat(0)
        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)

        val maxBufferSize = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
        } else {
            1024 * 1024 // 1MB default if not specified
        }

        val buffer = ByteBuffer.allocate(maxBufferSize)

        extractor.selectTrack(0)

        val audioData = mutableListOf<Float>()
        var samplesRead = 0

        while (true) {
            val readSize = extractor.readSampleData(buffer, 0)
            if (readSize < 0) break

            buffer.rewind()
            val shortBuffer = buffer.asShortBuffer()

            for (i in 0 until readSize / 2) {
                if (shortBuffer.hasRemaining()) {
                    audioData.add(shortBuffer.get().toFloat() / MAX_ABS_VAL)
                    samplesRead++
                }
            }

            extractor.advance()
        }

        extractor.release()

        if (samplesRead == 0) {
            throw IOException("No audio samples could be read from file")
        }

        return if (sampleRate != SAMPLE_RATE) {
            resampleAudio(audioData.toFloatArray(), sampleRate, SAMPLE_RATE)
        } else {
            audioData.toFloatArray()
        }
    }

    private fun resampleAudio(audioData: FloatArray, sourceRate: Int, targetRate: Int): FloatArray {
        val ratio = sourceRate.toFloat() / targetRate.toFloat()
        val newLength = (audioData.size / ratio).toInt()
        val resampled = FloatArray(newLength)

        for (i in 0 until newLength) {
            val srcPos = i * ratio
            val posInt = srcPos.toInt()
            val alpha = srcPos - posInt

            resampled[i] = when {
                posInt + 1 < audioData.size ->
                    audioData[posInt] * (1 - alpha) + audioData[posInt + 1] * alpha
                posInt < audioData.size ->
                    audioData[posInt]
                else ->
                    0f
            }
        }

        return resampled
    }

    private fun computeMFCC(audioData: FloatArray): FloatArray {
        require(audioData.isNotEmpty()) { "Empty audio data" }

        // Process in frames for longer audio files
        val numFrames = max(1, (audioData.size - N_FFT) / HOP_LENGTH + 1)
        val frameResults = Array(numFrames) { frameIndex ->
            val startIdx = frameIndex * HOP_LENGTH
            val frameData = audioData.copyOfRange(
                startIdx,
                min(startIdx + N_FFT, audioData.size)
            ).copyOf(N_FFT) // Zero-pad if needed

            val spectrogram = computeSpectrogramFrame(frameData)
            val melSpectrogram = applyMelFilterBank(spectrogram)
            val logMelSpectrogram = melSpectrogram.map { ln(max(1e-10f, it)) }
            val mfccs = dct(logMelSpectrogram.toFloatArray())

            mfccs.copyOfRange(1, N_MFCC + 1)
        }

        // Average the MFCC frames
        return averageFrames(frameResults)
    }

    private fun computeChroma(audioData: FloatArray): FloatArray {
        // Process in frames for longer audio files
        val numFrames = max(1, (audioData.size - N_FFT) / HOP_LENGTH + 1)
        val frameResults = Array(numFrames) { frameIndex ->
            val startIdx = frameIndex * HOP_LENGTH
            val frameData = audioData.copyOfRange(
                startIdx,
                min(startIdx + N_FFT, audioData.size)
            ).copyOf(N_FFT) // Zero-pad if needed

            val spectrogram = computeSpectrogramFrame(frameData)
            val chroma = FloatArray(N_CHROMA) { 0f }

            for (i in spectrogram.indices) {
                val freq = i * SAMPLE_RATE / N_FFT.toFloat()
                if (freq > 0) {
                    val pitch = (12 * log2(freq.toDouble() / 440.0) + 69).toInt()
                    val chromaIdx = ((pitch % 12) + 12) % 12 // Ensure positive index
                    if (chromaIdx in 0..11) {
                        chroma[chromaIdx] += spectrogram[i]
                    }
                }
            }

            val maxVal = chroma.maxOrNull() ?: 1f
            if (maxVal > 0) {
                for (i in chroma.indices) {
                    chroma[i] /= maxVal
                }
            }

            chroma
        }

        // Average the chroma frames
        return averageFrames(frameResults)
    }

    private fun computeMelSpectrogram(audioData: FloatArray): FloatArray {
        // Process in frames for longer audio files
        val numFrames = max(1, (audioData.size - N_FFT) / HOP_LENGTH + 1)
        val frameResults = Array(numFrames) { frameIndex ->
            val startIdx = frameIndex * HOP_LENGTH
            val frameData = audioData.copyOfRange(
                startIdx,
                min(startIdx + N_FFT, audioData.size)
            ).copyOf(N_FFT) // Zero-pad if needed

            val spectrogram = computeSpectrogramFrame(frameData)
            applyMelFilterBank(spectrogram)
        }

        // Average the mel spectrogram frames
        return averageFrames(frameResults)
    }

    private fun averageFrames(frames: Array<FloatArray>): FloatArray {
        if (frames.isEmpty()) return FloatArray(0)

        val result = FloatArray(frames[0].size)
        for (i in result.indices) {
            var sum = 0f
            for (frame in frames) {
                sum += frame[i]
            }
            result[i] = sum / frames.size
        }
        return result
    }

    private fun computeSpectrogramFrame(frameData: FloatArray): FloatArray {
        val window = hanningWindow(N_FFT)
        val spectrogram = FloatArray(N_FFT / 2 + 1)

        for (i in 0 until N_FFT / 2 + 1) {
            var real = 0f
            var imag = 0f
            for (n in 0 until N_FFT) {
                val sample = frameData[n] * window[n]
                val angle = -2 * PI.toFloat() * i * n / N_FFT
                real += sample * cos(angle)
                imag += sample * sin(angle)
            }
            spectrogram[i] = sqrt(real * real + imag * imag)
        }

        return spectrogram
    }

    private fun applyMelFilterBank(spectrum: FloatArray): FloatArray {
        return FloatArray(MEL_FB.size) { i ->
            var sum = 0f
            for (j in spectrum.indices) {
                sum += MEL_FB[i][j] * spectrum[j]
            }
            sum
        }
    }

    private fun dct(values: FloatArray): FloatArray {
        val n = values.size
        return FloatArray(n) { k ->
            var sum = 0f
            for (i in values.indices) {
                sum += values[i] * cos(PI.toFloat() * k * (2 * i + 1) / (2 * n))
            }
            sum * sqrt(2f / n)
        }
    }

    private fun hanningWindow(size: Int): FloatArray {
        return FloatArray(size) { i ->
            0.5f * (1 - cos(2 * PI.toFloat() * i / (size - 1)))
        }
    }

    private fun log2(x: Double): Double = ln(x) / ln(2.0)

    class FeatureExtractionError(message: String) : Exception(message)
}