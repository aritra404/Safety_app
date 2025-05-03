package aritra.seal.finalyearapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.telephony.SmsManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import java.util.Locale

class HelpDetectionService : Service() {
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var recognizerIntent: Intent
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var wakeLock: PowerManager.WakeLock? = null

    private val NOTIFICATION_ID = 1001
    private val CHANNEL_ID = "HelpDetectionChannel"
    private val EMERGENCY_CHANNEL_ID = "emergency_channel"

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        setupSpeechRecognizer()

        // Acquire wake lock to keep CPU running
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "HelpDetector::BackgroundServiceWakeLock"
        )
        wakeLock?.acquire(10*60*1000L) // 10 minutes
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        startListening()

        // Return START_STICKY to ensure service restarts if killed
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun setupSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // Request max results
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            // Ensure partial results are delivered
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }

        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: android.os.Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                // Restart listening when speech ends
                startListening()
            }

            override fun onError(error: Int) {
                // Log the error
                when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> logError("Audio recording error")
                    SpeechRecognizer.ERROR_CLIENT -> logError("Client side error")
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> logError("Insufficient permissions")
                    SpeechRecognizer.ERROR_NETWORK -> logError("Network error")
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> logError("Network timeout")
                    SpeechRecognizer.ERROR_NO_MATCH -> logError("No recognition match")
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> logError("RecognitionService busy")
                    SpeechRecognizer.ERROR_SERVER -> logError("Server error")
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> logError("No speech input")
                    else -> logError("Unknown error: $error")
                }

                // Restart listening after a short delay
                android.os.Handler(mainLooper).postDelayed({
                    startListening()
                }, 1000)
            }

            override fun onResults(results: android.os.Bundle?) {
                processRecognitionResults(results)
            }

            override fun onPartialResults(partialResults: android.os.Bundle?) {
                processRecognitionResults(partialResults)
            }

            override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
        })
    }

    private fun logError(message: String) {
        android.util.Log.e("HelpDetectionService", message)
    }

    private fun processRecognitionResults(results: android.os.Bundle?) {
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (matches != null) {
            for (result in matches) {
                android.util.Log.d("HelpDetectionService", "Recognized: $result")
                if (result.lowercase().contains("help")) {
                    // Word "help" detected
                    android.util.Log.i("HelpDetectionService", "HELP DETECTED")

                    // Show notification
                    showEmergencyNotification()

                    // Get the guardian's number
                    val sharedPref = getSharedPreferences("HelpDetectorPrefs", Context.MODE_PRIVATE)
                    val guardianNumber = sharedPref.getString("guardianNumber", "")

                    if (!guardianNumber.isNullOrEmpty()) {
                        android.util.Log.i("HelpDetectionService", "Guardian number: $guardianNumber")

                        // Launch activity to handle the emergency actions
                        val emergencyIntent = Intent(this, EmergencyHandlerActivity::class.java).apply {
                            putExtra("GUARDIAN_NUMBER", guardianNumber)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        startActivity(emergencyIntent)

                        // Also try to handle it directly in service for redundancy
                        handleEmergencyInBackground(guardianNumber)
                    } else {
                        showToast("Guardian number not set!")
                    }

                    break
                }
            }
        }
    }

    private fun handleEmergencyInBackground(guardianNumber: String) {
        // Acquire temporary wake lock for these critical operations
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val tempWakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "HelpDetector::EmergencyWakeLock"
        )
        tempWakeLock.acquire(60*1000L) // 1 minute

        try {
            // Try to send SMS directly
            sendEmergencySMS(guardianNumber)

            // Log attempt to make a call
            android.util.Log.i("HelpDetectionService", "Attempting to call guardian from service")

            // Try direct call
            val callIntent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$guardianNumber")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }

            // Try with a delay
            android.os.Handler(mainLooper).postDelayed({
                try {
                    startActivity(callIntent)
                } catch (e: Exception) {
                    android.util.Log.e("HelpDetectionService", "Call failed: ${e.message}")
                }

                // Release wake lock after attempt
                if (tempWakeLock.isHeld) {
                    tempWakeLock.release()
                }
            }, 2000)
        } catch (e: Exception) {
            android.util.Log.e("HelpDetectionService", "Emergency handling failed: ${e.message}")
            if (tempWakeLock.isHeld) {
                tempWakeLock.release()
            }
        }
    }

    private fun sendEmergencySMS(phoneNumber: String) {
        if (ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.SEND_SMS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED) {

            val message = "EMERGENCY: I need help! This is an urgent alert from my safety app."

            try {
                val smsManager = SmsManager.getDefault()
                smsManager.sendTextMessage(phoneNumber, null, message, null, null)
                android.util.Log.i("HelpDetectionService", "Emergency SMS sent")

                // Try to get location for a follow-up SMS
                getCurrentLocation { location ->
                    if (location != null) {
                        val locationUrl = "https://maps.google.com/?q=${location.latitude},${location.longitude}"
                        val locationMessage = "My current location is: $locationUrl"
                        try {
                            smsManager.sendTextMessage(phoneNumber, null, locationMessage, null, null)
                            android.util.Log.i("HelpDetectionService", "Location SMS sent")
                        } catch (e: Exception) {
                            android.util.Log.e("HelpDetectionService", "Location SMS failed: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("HelpDetectionService", "Failed to send SMS: ${e.message}")
            }
        }
    }

    private fun showEmergencyNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Create intent to open the emergency activity
        val intent = Intent(this, EmergencyHandlerActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Create emergency notification
        val notification = NotificationCompat.Builder(this, EMERGENCY_CHANNEL_ID)
            .setContentTitle("EMERGENCY ALERT")
            .setContentText("Help detected! Contacting guardian...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVibrate(longArrayOf(0, 500, 500, 500))
            .setContentIntent(pendingIntent)
            .setFullScreenIntent(pendingIntent, true) // Make it a heads-up notification
            .setAutoCancel(true)
            .build()

        notificationManager.notify(1002, notification)

        // Add vibration
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(android.os.VibrationEffect.createOneShot(1000, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(1000)
        }

        showToast("HELP detected! Alerting guardian...")
    }

    private fun showToast(message: String) {
        // Using the main thread for UI operations
        android.os.Handler(mainLooper).post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun getCurrentLocation(callback: (Location?) -> Unit) {
        if (ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED) {

            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                callback(location)
            }.addOnFailureListener {
                callback(null)
            }
        } else {
            callback(null)
        }
    }

    private fun startListening() {
        if (::speechRecognizer.isInitialized) {
            try {
                speechRecognizer.startListening(recognizerIntent)
            } catch (e: Exception) {
                android.util.Log.e("HelpDetectionService", "Failed to start listening: ${e.message}")
                // Restart recognition if there's an error
                android.os.Handler(mainLooper).postDelayed({
                    startListening()
                }, 1000)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Help Detection Service"
            val descriptionText = "Running in background to detect help calls"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }

            // Create a separate high priority channel for emergency alerts
            val emergencyChannel = NotificationChannel(
                EMERGENCY_CHANNEL_ID,
                "Emergency Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when help is detected"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 500, 500)
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
            notificationManager.createNotificationChannel(emergencyChannel)
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity2::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Help Detection Active")
            .setContentText("Listening for help commands")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::speechRecognizer.isInitialized) {
            speechRecognizer.destroy()
        }

        // Release wake lock if still held
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
    }
}