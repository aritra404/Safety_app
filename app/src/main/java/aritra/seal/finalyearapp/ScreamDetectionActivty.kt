package aritra.seal.finalyearapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScreamDetectionActivity : AppCompatActivity() {

    private val TAG = "ScreamDetectionActivity"
    private val REQUEST_RECORD_AUDIO_PERMISSION = 200

    private lateinit var recordButton: Button
    private lateinit var statusTextView: TextView
    private lateinit var resultTextView: TextView

    private var mediaRecorder: MediaRecorder? = null
    private var isRecording = false
    private lateinit var outputFile: File

    private lateinit var screamDetector: ScreamDetector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scream_detection)
        val testButton = findViewById<Button>(R.id.btn_test_scream)
        testButton.setOnClickListener {
            // Create intent to launch ScreamTestActivity
            val intent = Intent(this, TestActivity::class.java)
            startActivity(intent)
        }

        recordButton = findViewById(R.id.recordButton)
        statusTextView = findViewById(R.id.statusTextView)
        resultTextView = findViewById(R.id.resultTextView)

        screamDetector = ScreamDetector(this)

        // Check for recording permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_RECORD_AUDIO_PERMISSION
            )
        }

        recordButton.setOnClickListener {
            if (isRecording) {
                stopRecording()
                analyzeAudio()
            } else {
                startRecording()
            }
        }
    }

    private fun startRecording() {
        try {
            // Create output directory if it doesn't exist
            val outputDir = File(getExternalFilesDir(null), "AudioRecordings")
            if (!outputDir.exists()) {
                outputDir.mkdirs()
            }

            // Create output file
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            outputFile = File(outputDir, "AUDIO_$timeStamp.3gp")

            // Initialize MediaRecorder
            mediaRecorder = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            mediaRecorder?.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                setOutputFile(outputFile.absolutePath)
                prepare()
                start()
            }

            isRecording = true
            recordButton.text = "Stop Recording"
            statusTextView.text = "Recording..."
            resultTextView.text = ""

        } catch (e: IOException) {
            Log.e(TAG, "Failed to start recording", e)
            statusTextView.text = "Error: Failed to start recording"
        }
    }

    private fun stopRecording() {
        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
            mediaRecorder = null
            isRecording = false
            recordButton.text = "Start Recording"
            statusTextView.text = "Processing audio..."

        } catch (e: IOException) {
            Log.e(TAG, "Failed to stop recording", e)
            statusTextView.text = "Error: Failed to stop recording"
        }
    }

    private fun analyzeAudio() {
        lifecycleScope.launch {
            statusTextView.text = "Analyzing audio..."

            try {
                val result = screamDetector.detectScream(outputFile)

                if (result.errorMessage != null) {
                    statusTextView.text = result.errorMessage
                    return@launch
                }

                statusTextView.text = "Analysis complete"

                val resultText = if (result.isScream) {
                    "SCREAM DETECTED (${(result.confidence * 100).toInt()}% confidence)"
                } else {
                    "No scream detected (${(result.confidence * 100).toInt()}% confidence)"
                }

                resultTextView.text = resultText

                // If a scream is detected with high confidence, you could trigger an alert here
                if (result.isScream && result.confidence > 0.7f) {
                    // alertNearbyOfficer()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error analyzing audio", e)
                statusTextView.text = "Error: ${e.message}"
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                statusTextView.text = "Permission to record audio denied"
                recordButton.isEnabled = false
            }
        }
    }
}