package aritra.seal.finalyearapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.material.switchmaterial.SwitchMaterial
import java.util.Locale

class MainActivity2 : AppCompatActivity() {

    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var recognizerIntent: Intent
    private lateinit var statusTextView: TextView
    private lateinit var guardianNumberTextView: TextView
    private lateinit var activeSwitch: SwitchMaterial
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private val PERMISSIONS_REQUEST_CODE = 100
    private val REQUIRED_PERMISSIONS = arrayOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.SEND_SMS,
        Manifest.permission.FOREGROUND_SERVICE
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main2)

        statusTextView = findViewById(R.id.statusTextView)
        guardianNumberTextView = findViewById(R.id.guardianNumberTextView)
        activeSwitch = findViewById(R.id.activeSwitch)
        val setGuardianButton: Button = findViewById(R.id.setGuardianButton)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // Request permissions
        if (!allPermissionsGranted()) {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, PERMISSIONS_REQUEST_CODE
            )
        }

        // Initialize speech recognizer for the activity
        setupSpeechRecognizer()

        // Setup switch listener
        activeSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                // Start foreground service for background detection
                startHelpDetectionService()
                // Also start the in-app listening
                startListening()
                statusTextView.text = "Listening for 'help'... (Running in background)"
            } else {
                // Stop foreground service
                stopHelpDetectionService()
                // Stop in-app listening
                stopListening()
                statusTextView.text = "Detection stopped"
            }
        }

        // Setup guardian number button
        setGuardianButton.setOnClickListener {
            val intent = Intent(this, GuardianSettingsActivity::class.java)
            startActivity(intent)
        }

        // Load guardian number from shared preferences
        val sharedPref = getSharedPreferences("HelpDetectorPrefs", MODE_PRIVATE)
        val guardianNumber = sharedPref.getString("guardianNumber", "")
        guardianNumberTextView.text = guardianNumber ?: "Not set"

        // Check if service is running and update switch accordingly
        updateSwitchState()
    }

    private fun updateSwitchState() {
        val serviceRunning = isServiceRunning(HelpDetectionService::class.java)
        activeSwitch.isChecked = serviceRunning
        if (serviceRunning) {
            statusTextView.text = "Listening for 'help'... (Running in background)"
        } else {
            statusTextView.text = "Detection stopped"
        }
    }

    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val activityManager = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        for (service in activityManager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }

    private fun startHelpDetectionService() {
        val serviceIntent = Intent(this, HelpDetectionService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun stopHelpDetectionService() {
        val serviceIntent = Intent(this, HelpDetectionService::class.java)
        stopService(serviceIntent)
    }

    private fun setupSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }

        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                // Restart listening when speech ends
                if (activeSwitch.isChecked) {
                    startListening()
                }
            }

            override fun onError(error: Int) {
                // Restart listening on error
                if (activeSwitch.isChecked) {
                    startListening()
                }
            }

            override fun onResults(results: Bundle?) {
                processRecognitionResults(results)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                processRecognitionResults(partialResults)
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun processRecognitionResults(results: Bundle?) {
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (matches != null) {
            for (result in matches) {
                if (result.lowercase().contains("help")) {
                    // Word "help" detected
                    Toast.makeText(this, "HELP detected! Alerting guardian...", Toast.LENGTH_LONG).show()
                    statusTextView.text = "HELP detected! Alerting guardian..."

                    // Get the guardian's number
                    val sharedPref = getSharedPreferences("HelpDetectorPrefs", MODE_PRIVATE)
                    val guardianNumber = sharedPref.getString("guardianNumber", "")

                    if (!guardianNumber.isNullOrEmpty()) {
                        getCurrentLocation { location ->
                            if (location != null) {
                                // Send location via SMS
                                sendLocationSMS(guardianNumber, location)
                                // Call guardian
                                callGuardian(guardianNumber)
                            } else {
                                // If location is not available, just call
                                callGuardian(guardianNumber)
                            }
                        }
                    } else {
                        Toast.makeText(this, "Guardian number not set!", Toast.LENGTH_SHORT).show()
                    }

                    break
                }
            }
        }
    }

    private fun getCurrentLocation(callback: (Location?) -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                callback(location)
            }.addOnFailureListener {
                callback(null)
            }
        } else {
            callback(null)
        }
    }

    private fun sendLocationSMS(phoneNumber: String, location: Location) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
            val locationUrl = "https://maps.google.com/?q=${location.latitude},${location.longitude}"
            val message = "EMERGENCY: I need help! My current location is: $locationUrl"

            try {
                val smsManager = android.telephony.SmsManager.getDefault()
                smsManager.sendTextMessage(phoneNumber, null, message, null, null)
                Toast.makeText(this, "Location sent to guardian", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Failed to send SMS: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun callGuardian(phoneNumber: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            val callIntent = Intent(Intent.ACTION_CALL)
            callIntent.data = Uri.parse("tel:$phoneNumber")
            startActivity(callIntent)
        }
    }

    private fun startListening() {
        if (allPermissionsGranted()) {
            speechRecognizer.startListening(recognizerIntent)
        } else {
            Toast.makeText(this, "Required permissions not granted", Toast.LENGTH_SHORT).show()
            activeSwitch.isChecked = false
        }
    }

    private fun stopListening() {
        speechRecognizer.stopListening()
    }

    private fun allPermissionsGranted(): Boolean {
        return REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            if (allPermissionsGranted()) {
                Toast.makeText(this, "All permissions granted", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Required permissions not granted", Toast.LENGTH_SHORT).show()
                activeSwitch.isChecked = false
            }
        }
    }

    // Check for service when returning to the app
    override fun onResume() {
        super.onResume()
        updateSwitchState()

        // Refresh guardian number display
        val sharedPref = getSharedPreferences("HelpDetectorPrefs", MODE_PRIVATE)
        val guardianNumber = sharedPref.getString("guardianNumber", "")
        guardianNumberTextView.text = guardianNumber ?: "Not set"
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer.destroy()

        // Don't stop service here so it keeps running in the background
    }
}