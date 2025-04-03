package aritra.seal.finalyearapp

import android.app.Activity
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import aritra.seal.finalyearapp.databinding.ActivityTestBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class TestActivity : AppCompatActivity() {
    private lateinit var binding: ActivityTestBinding
    private lateinit var screamDetector: ScreamDetector
    private var mediaPlayer: MediaPlayer? = null
    private var isPlaying = false
    private var selectedAudioUri: Uri? = null
    private var tempAudioFile: File? = null

    private val TAG = "AudioFileTestActivity"

    // File picker launcher
    private val audioPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                selectedAudioUri = uri
                val fileName = getFileNameFromUri(uri) ?: "selected_audio"
                binding.tvSelectedFile.text = "Selected: $fileName"
                binding.tvSelectedFile.visibility = View.VISIBLE
                binding.btnPlay.isEnabled = true
                binding.btnAnalyze.isEnabled = true

                // Copy the file to a temporary location for processing
                copyAudioFileToTemp(uri)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTestBinding.inflate(layoutInflater)
        setContentView(binding.root)

        screamDetector = ScreamDetector(this)

        setupUI()
        checkPermissions()
    }

    private fun setupUI() {
        binding.btnSelectAudio.setOnClickListener {
            openAudioFilePicker()
        }

        binding.btnPlay.setOnClickListener {
            if (isPlaying) {
                stopPlaying()
            } else {
                startPlaying()
            }
        }

        binding.btnAnalyze.setOnClickListener {
            analyzeAudio()
        }

        // Initially disable buttons until file is selected
        binding.btnPlay.isEnabled = false
        binding.btnAnalyze.isEnabled = false
        binding.tvSelectedFile.visibility = View.GONE
        binding.tvResult.visibility = View.GONE
        binding.progressAnalyzing.visibility = View.GONE
    }

    private fun checkPermissions() {
        // For Android 11+ check for MANAGE_EXTERNAL_STORAGE permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Settings.canDrawOverlays(this)) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
                Toast.makeText(this, "Please grant overlay permission for alerts", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to request overlay permission", e)
            }
        }
    }

    private fun openAudioFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
        }
        audioPickerLauncher.launch(intent)
    }

    private fun getFileNameFromUri(uri: Uri): String? {
        val cursor = contentResolver.query(uri, null, null, null, null)
        return cursor?.use {
            val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            it.moveToFirst()
            it.getString(nameIndex)
        }
    }

    private fun copyAudioFileToTemp(uri: Uri) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    // Create a temporary file
                    val fileName = getFileNameFromUri(uri) ?: "temp_audio.wav"
                    tempAudioFile = File(getExternalFilesDir(null), fileName)

                    FileOutputStream(tempAudioFile).use { outputStream ->
                        val buffer = ByteArray(1024)
                        var length: Int
                        while (inputStream.read(buffer).also { length = it } > 0) {
                            outputStream.write(buffer, 0, length)
                        }
                        outputStream.flush()
                    }

                    withContext(Dispatchers.Main) {
                        binding.tvStatus.text = "Audio file ready for testing"
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error copying audio file", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@TestActivity,
                        "Failed to process audio file: ${e.message}",
                        Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun startPlaying() {
        selectedAudioUri?.let { uri ->
            try {
                mediaPlayer?.release()
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(applicationContext, uri)
                    setOnCompletionListener {
                        stopPlaying()
                    }
                    prepare()
                    start()
                }
                isPlaying = true
                binding.btnPlay.text = "Stop"
                binding.tvStatus.text = "Playing audio..."
            } catch (e: Exception) {
                Log.e(TAG, "Error playing audio", e)
                Toast.makeText(this, "Failed to play audio: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun stopPlaying() {
        mediaPlayer?.apply {
            if (isPlaying) {
                stop()
            }
            release()
        }
        mediaPlayer = null
        isPlaying = false
        binding.btnPlay.text = "Play"
        binding.tvStatus.text = "Audio stopped"
    }

    private fun analyzeAudio() {
        tempAudioFile?.let { file ->
            if (!file.exists()) {
                Toast.makeText(this, "Audio file not found", Toast.LENGTH_SHORT).show()
                return
            }

            binding.progressAnalyzing.visibility = View.VISIBLE
            binding.tvStatus.text = "Analyzing audio..."
            binding.tvResult.visibility = View.GONE

            CoroutineScope(Dispatchers.Main).launch {
                try {
                    val result = withContext(Dispatchers.IO) {
                        screamDetector.detectScream(file)
                    }

                    // Display results
                    binding.progressAnalyzing.visibility = View.GONE
                    binding.tvResult.visibility = View.VISIBLE

                    if (result.isScream) {
                        binding.tvResult.text = "✅ SCREAM DETECTED!\nConfidence: ${"%.2f".format(result.confidence * 100)}%"
                        binding.tvResult.setTextColor(getColor(android.R.color.holo_red_dark))
                    } else {
                        binding.tvResult.text = "❌ No scream detected\nConfidence: ${"%.2f".format(result.confidence * 100)}%"
                        binding.tvResult.setTextColor(getColor(android.R.color.darker_gray))
                    }

                    binding.tvStatus.text = "Analysis complete"
                } catch (e: Exception) {
                    binding.progressAnalyzing.visibility = View.GONE
                    binding.tvStatus.text = "Analysis failed"
                    Log.e(TAG, "Scream detection failed", e)
                    Toast.makeText(this@TestActivity,
                        "Detection error: ${e.message}",
                        Toast.LENGTH_LONG).show()
                }
            }
        } ?: run {
            Toast.makeText(this, "Please select an audio file first", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
        mediaPlayer = null
    }
}