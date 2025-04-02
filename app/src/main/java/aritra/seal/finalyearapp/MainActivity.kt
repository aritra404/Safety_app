package aritra.seal.finalyearapp

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import aritra.seal.finalyearapp.databinding.ActivityMainBinding
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var mediaRecorder: MediaRecorder? = null
    private var isRecording = false
    private var tflite: Interpreter? = null
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var featureExtractor: AudioFeatureExtractor
    private var audioFile: File? = null

    companion object {
        private const val REQUEST_RECORD_AUDIO_PERMISSION = 200
        private const val TAG = "MainActivity"
        private const val MIN_RECORDING_TIME_MS = 500 // Minimum recording time in milliseconds
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize components
        featureExtractor = AudioFeatureExtractor(this)
        initializeModel()

        binding.recordButton.setOnClickListener {
            if (!isRecording) {
                startRecording()
            } else {
                stopRecording()
            }
        }
    }

    private fun initializeModel() {
        try {
            tflite = Interpreter(loadModelFile())
            // Log model input/output details for debugging
            val inputTensor = tflite?.getInputTensor(0)
            val outputTensor = tflite?.getOutputTensor(0)
            Log.d(TAG, "Model loaded successfully")
            Log.d(TAG, "Input shape: ${inputTensor?.shape()?.joinToString()}")
            Log.d(TAG, "Output shape: ${outputTensor?.shape()?.joinToString()}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model", e)
            Toast.makeText(this, "Failed to load model: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun loadModelFile(): MappedByteBuffer {
        val fileDescriptor = assets.openFd("scream_detection_model.tflite")
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    private fun startRecording() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_RECORD_AUDIO_PERMISSION
            )
            return
        }

        try {
            // Clean up previous recorder if exists
            mediaRecorder?.release()

            // Create directory if it doesn't exist
            val recordingDir = getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            recordingDir?.mkdirs()

            audioFile = File(
                recordingDir,
                "recording_${System.currentTimeMillis()}.3gp"
            )

            mediaRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                setOutputFile(audioFile?.absolutePath)
                setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                setAudioSamplingRate(8000) // AMR_NB supports 8000Hz
                setAudioEncodingBitRate(12200) // Standard for AMR_NB
                prepare()
                start()
            }

            isRecording = true
            binding.recordButton.text = "Stop Recording"
            binding.statusText.text = "Recording..."
            binding.alertText.text = ""
            binding.resultText.text = ""
        } catch (e: IOException) {
            Log.e(TAG, "Recording setup failed", e)
            Toast.makeText(this, "Recording failed: ${e.message}", Toast.LENGTH_SHORT).show()
            resetRecordingState()
        } catch (e: Exception) {
            Log.e(TAG, "Recording setup error", e)
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            resetRecordingState()
        }
    }

    private fun stopRecording() {
        val recordingStartTime = System.currentTimeMillis()

        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Recording error - likely too short", e)
            binding.statusText.text = "Recording too short - try again"
            resetRecordingState()
            return
        } catch (e: Exception) {
            Log.e(TAG, "Recording error", e)
            binding.statusText.text = "Recording failed"
            resetRecordingState()
            return
        } finally {
            mediaRecorder = null
            isRecording = false
            binding.recordButton.text = "Start Recording"
        }

        // Verify recording exists and has content
        if (audioFile == null || audioFile?.length() ?: 0L == 0L) {
            binding.statusText.text = "Empty recording - try again"
            return
        }

        binding.statusText.text = "Analyzing..."
        processRecording()
    }

    private fun resetRecordingState() {
        mediaRecorder?.release()
        mediaRecorder = null
        isRecording = false
        binding.recordButton.text = "Start Recording"
    }

    private fun predict(features: FloatArray): FloatArray {
        // Log feature dimensions for debugging
        Log.d(TAG, "Feature length: ${features.size}")

        // Get model input shape and verify
        val inputShape = tflite?.getInputTensor(0)?.shape()
        Log.d(TAG, "Model input shape: ${inputShape?.joinToString()}")

        // Check if we need to reshape input
        val inputSize = inputShape?.getOrNull(1) ?: 153

        // If model expects different number of features, handle it
        val finalFeatures = if (features.size != inputSize) {
            Log.w(TAG, "Feature count mismatch: got ${features.size}, model expects $inputSize")
            // Option 1: Truncate or pad
            features.copyOf(inputSize)
        } else {
            features
        }

        // Prepare input and output tensors
        val inputArray = Array(1) { finalFeatures }
        val output = Array(1) { FloatArray(2) }

        try {
            tflite?.run(inputArray, output)
            return output[0]
        } catch (e: Exception) {
            Log.e(TAG, "TFLite inference error", e)
            throw e
        }
    }

    private fun processRecording() {
        executor.execute {
            try {
                audioFile?.let { file ->
                    // 1. Validate file first
                    if (!file.exists() || file.length() == 0L) {
                        throw IOException("Recording file is empty")
                    }

                    // 2. Extract features with validation
                    val features = try {
                        featureExtractor.extractFeatures(file)
                    } catch (e: Exception) {
                        Log.e(TAG, "Feature extraction failed", e)
                        throw Exception("Feature extraction failed: ${e.message}")
                    }

                    // 3. Validate features
                    if (features.isEmpty()) {
                        throw Exception("No features extracted")
                    }

                    if (features.any { it.isNaN() || it.isInfinite() }) {
                        Log.e(TAG, "Invalid features: found NaN or infinite values")
                        throw Exception("Invalid features extracted")
                    }

                    // 4. Make prediction
                    val result = predict(features)

                    runOnUiThread {
                        displayResult(result)
                    }
                } ?: run {
                    throw Exception("No audio file available")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Audio processing failed", e)
                runOnUiThread {
                    binding.statusText.text = when {
                        e.message?.contains("Feature extraction") == true -> "Audio analysis failed"
                        e.message?.contains("Recording file") == true -> "Invalid recording"
                        e.message?.contains("inference error") == true -> "Model inference error"
                        else -> "Processing error: ${e.message}"
                    }
                }
            }
        }
    }

    private fun displayResult(result: FloatArray) {
        if (result.size < 2) {
            binding.statusText.text = "Invalid prediction result"
            return
        }

        val (normalProb, screamProb) = result
        binding.resultText.text = "Normal: ${"%.2f".format(normalProb * 100)}%, " +
                "Scream: ${"%.2f".format(screamProb * 100)}%"
        binding.statusText.text = "Analysis complete"

        if (screamProb > 0.7) {
            binding.alertText.text = "⚠️ Scream detected! Alerting authorities..."
        } else {
            binding.alertText.text = "No scream detected"
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_RECORD_AUDIO_PERMISSION -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    startRecording()
                } else {
                    Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tflite?.close()
        mediaRecorder?.release()
        executor.shutdown()
    }
}