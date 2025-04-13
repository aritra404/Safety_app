package aritra.seal.finalyearapp

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.*
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.telephony.SmsManager
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

class HelpDetectorService : Service() {

    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var recognizerIntent: Intent
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var isListening = false

    // Flag to prevent multiple simultaneous recreation attempts
    private val isRecreating = AtomicBoolean(false)

    // Handler for delayed tasks
    private val handler = Handler(Looper.getMainLooper())

    // Track recreation attempts to prevent infinite loops
    private var recreationAttempts = 0
    private val MAX_RECREATION_ATTEMPTS = 3

    private val NOTIFICATION_CHANNEL_ID = "HelpDetectorChannel"
    private val NOTIFICATION_ID = 1
    private val TAG = "HelpDetectorService"

    companion object {
        const val ACTION_START_LISTENING = "aritra.seal.finalyearapp.action.START_LISTENING"
        const val ACTION_STOP_LISTENING = "aritra.seal.finalyearapp.action.STOP_LISTENING"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()

        // Setup speech recognizer only if available
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            setupSpeechRecognizer()
        } else {
            Log.e(TAG, "Speech recognition is not available on this device.")
            Toast.makeText(this, "Speech recognition not available.", Toast.LENGTH_LONG).show()
            stopSelf() // Stop service if core functionality is missing
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand received action: ${intent?.action}")

        when (intent?.action) {
            ACTION_START_LISTENING -> {
                if (!isListening) { // Prevent starting multiple times
                    startForegroundServiceWithNotification()
                    resetRecreationAttempts() // Reset counter when explicitly started
                    startListening()
                } else {
                    Log.d(TAG, "Start action received but already listening.")
                }
            }
            ACTION_STOP_LISTENING -> {
                stopListeningAndService()
            }
            else -> {
                // Service restarted by system (due to START_STICKY)
                Log.d(TAG, "Service restarted by system or started without specific action.")
                stopListeningAndService() // Stop if started unexpectedly
            }
        }
        // If service is killed, restart it, but the intent might be null on restart
        return START_STICKY
    }

    private fun startForegroundServiceWithNotification() {
        Log.d(TAG, "Starting foreground service.")
        val notification = createNotification("Listening for 'help'...")
        try {
            startForeground(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting foreground service", e)
            Toast.makeText(this, "Could not start foreground service: ${e.message}", Toast.LENGTH_LONG).show()
            stopSelf() // Stop if we can't run in foreground
        }
    }

    private fun createNotification(contentText: String): Notification {
        val notificationIntent = Intent(this, MainActivity2::class.java)
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingIntentFlags)

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Help Detector Active")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true) // Avoid sound on notification updates
            .setPriority(NotificationCompat.PRIORITY_LOW) // Reduce intrusiveness
            .build()
    }

    private fun updateNotification(contentText: String) {
        val notification = createNotification(contentText)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
        Log.d(TAG, "Notification updated: $contentText")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Help Detector Service Channel",
                NotificationManager.IMPORTANCE_LOW // Low importance = no sound/vibration
            ).apply {
                description = "Channel for Help Detector foreground service"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
            Log.d(TAG, "Notification channel created.")
        }
    }

    private fun resetRecreationAttempts() {
        recreationAttempts = 0
    }

    private fun setupSpeechRecognizer() {
        try {
            // Cancel any pending recreations
            cancelPendingRecreations()

            // Create new speech recognizer
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(applicationContext)

            // Set up intent
            recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                // Parameters for stability:
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1000)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500)
                // Add this flag to try to make the recognizer more reliable
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }

            // Set the listener
            speechRecognizer.setRecognitionListener(createRecognitionListener())
            Log.d(TAG, "Speech recognizer setup complete.")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up speech recognizer", e)
            Toast.makeText(this, "Speech recognition initialization failed", Toast.LENGTH_SHORT).show()
            handler.postDelayed({ stopSelf() }, 3000) // Delayed stop to allow Toast to be seen
        }
    }

    private fun processRecognitionResults(results: Bundle?) {
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        Log.d(TAG, "Processing Results: ${matches?.joinToString()}")
        if (matches != null && matches.isNotEmpty()) {
            for (result in matches) {
                // More robust check, handling potential variations and ensuring "help" is a whole word or clearly present
                val lowerResult = result.lowercase(Locale.ROOT)
                if (lowerResult.contains("help")) { // Simple contains check
                    Log.i(TAG, "Keyword 'help' DETECTED in: '$result'")
                    Toast.makeText(this, "HELP detected! Alerting guardian...", Toast.LENGTH_LONG).show()
                    updateNotification("HELP Detected! Alerting...")

                    val sharedPref = getSharedPreferences("HelpDetectorPrefs", MODE_PRIVATE)
                    val guardianNumber = sharedPref.getString("guardianNumber", "")
                    Log.d(TAG, "Retrieved Guardian Number: '$guardianNumber'")

                    if (!guardianNumber.isNullOrEmpty()) {
                        getCurrentLocationAndTriggerAlerts(guardianNumber)
                    } else {
                        Log.w(TAG, "Guardian number not set!")
                        Toast.makeText(this, "Guardian number not set!", Toast.LENGTH_SHORT).show()
                        updateNotification("Guardian number not set!")
                    }
                    break // Exit the loop once help is detected
                }
            }
        }
    }

    private fun getCurrentLocationAndTriggerAlerts(guardianNumber: String) {
        Log.d(TAG, "Attempting to get current location.")
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Location permission not granted when trying to get location.")
            updateNotification("Location Permission Needed!")
            triggerAlerts(guardianNumber, null) // Trigger alerts without location
            return
        }
        try {
            fusedLocationClient.lastLocation
                .addOnSuccessListener { location: Location? ->
                    Log.d(TAG, "Got location: $location")
                    triggerAlerts(guardianNumber, location)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to get location", e)
                    triggerAlerts(guardianNumber, null) // Trigger alerts without location
                }
        } catch (se: SecurityException) {
            Log.e(TAG, "SecurityException while getting location", se)
            updateNotification("Location Permission Error!")
            triggerAlerts(guardianNumber, null)
        }
    }

    private fun triggerAlerts(guardianNumber: String, location: Location?) {
        Log.d(TAG, "Triggering alerts for $guardianNumber. Location: $location")
        var messageSent = false
        var callInitiated = false

        if (location != null) {
            if (sendLocationSMS(guardianNumber, location)) {
                messageSent = true
            }
        } else {
            Log.w(TAG, "Location is null, attempting generic SMS.")
            if (sendGenericHelpSMS(guardianNumber)) { // Send generic SMS if location is missing
                messageSent = true // Consider this a success for status update
            }
        }

        if (callGuardian(guardianNumber)) {
            callInitiated = true
        }

        val statusText = when {
            messageSent && callInitiated -> "SMS Sent & Calling Guardian"
            messageSent -> "SMS Sent to Guardian"
            callInitiated -> "Calling Guardian"
            else -> "Alerting failed. Check Permissions/Number."
        }
        updateNotification(statusText)
        Log.d(TAG, "Alert status: $statusText")

        // Resume listening after a delay to avoid immediate re-trigger and allow call/SMS to proceed
        handler.postDelayed({
            if (isListening) {
                Log.d(TAG, "Resuming listening after alert delay.")
                updateNotification("Listening for 'help'...")
                // Reset attempts since this is a fresh start
                resetRecreationAttempts()
                // Ensure listening truly restarts if it was stopped during alert
                safelyStartListening()
            }
        }, 10000) // 10 second delay
    }

    private fun sendLocationSMS(phoneNumber: String, location: Location): Boolean {
        Log.d(TAG, "Attempting to send location SMS to $phoneNumber")
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "SEND_SMS permission not granted.")
            Toast.makeText(this, "SMS Permission Needed!", Toast.LENGTH_SHORT).show()
            return false
        }

        val locationUrl = "http://maps.google.com/maps?q=${location.latitude},${location.longitude}" // Standard maps link
        val message = "EMERGENCY: I need help! My current location is approximately: $locationUrl"

        try {
            val smsManager = getSmsManager()
            smsManager?.sendTextMessage(phoneNumber, null, message, null, null)
            Log.i(TAG, "Location SMS sent successfully to $phoneNumber.")
            Toast.makeText(this, "Location sent to guardian", Toast.LENGTH_SHORT).show()
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send location SMS", e)
            Toast.makeText(this, "Failed to send SMS: ${e.message}", Toast.LENGTH_SHORT).show()
            return false
        }
    }

    private fun sendGenericHelpSMS(phoneNumber: String): Boolean {
        Log.d(TAG, "Attempting to send generic help SMS to $phoneNumber")
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "SEND_SMS permission not granted.")
            Toast.makeText(this, "SMS Permission Needed!", Toast.LENGTH_SHORT).show()
            return false
        }
        val message = "EMERGENCY: I need help! Unable to get current location."
        try {
            val smsManager = getSmsManager()
            smsManager?.sendTextMessage(phoneNumber, null, message, null, null)
            Log.i(TAG, "Generic help SMS sent successfully to $phoneNumber.")
            Toast.makeText(this, "Help message sent (no location)", Toast.LENGTH_SHORT).show()
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send generic SMS", e)
            Toast.makeText(this, "Failed to send generic SMS: ${e.message}", Toast.LENGTH_SHORT).show()
            return false
        }
    }

    private fun callGuardian(phoneNumber: String): Boolean {
        Log.d(TAG, "Attempting to call guardian at $phoneNumber")
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "CALL_PHONE permission not granted.")
            Toast.makeText(this, "Call Permission Needed!", Toast.LENGTH_SHORT).show()
            return false
        }

        val callIntent = Intent(Intent.ACTION_CALL).apply {
            data = Uri.parse("tel:$phoneNumber")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(callIntent)
            Log.i(TAG, "Call initiated to $phoneNumber.")
            Toast.makeText(this, "Calling guardian...", Toast.LENGTH_SHORT).show()
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initiate call", e)
            Toast.makeText(this, "Failed to initiate call: ${e.message}", Toast.LENGTH_SHORT).show()
            return false
        }
    }

    private fun startListening() {
        Log.d(TAG, "startListening called. isListening=$isListening")
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            if (::speechRecognizer.isInitialized) { // Check if initialized
                safelyStartListening()
                updateNotification("Listening for 'help'...")
            } else {
                Log.e(TAG, "Speech recognizer not initialized!")
                Toast.makeText(this,"Speech recognizer error", Toast.LENGTH_SHORT).show()
                stopListeningAndService()
            }
        } else {
            Log.e(TAG, "Record Audio permission needed to start listening.")
            Toast.makeText(this, "Record Audio permission needed.", Toast.LENGTH_LONG).show()
            updateNotification("Audio Permission Needed!")
            stopListeningAndService() // Stop service if essential permission missing at start
        }
    }

    // Cancel any pending handler callbacks to prevent overlapping operations
    private fun cancelPendingRecreations() {
        handler.removeCallbacksAndMessages(null)
    }

    // Safe start method that avoids duplicate starts
    private fun safelyStartListening() {
        if (isListening) {
            Log.d(TAG, "Already listening, no need to start again")
            return
        }

        if (!::speechRecognizer.isInitialized) {
            Log.d(TAG, "Speech recognizer not initialized, setting up...")
            setupSpeechRecognizer()
            handler.postDelayed({ startListeningAfterSetup() }, 500)
            return
        }

        try {
            speechRecognizer.startListening(recognizerIntent)
            isListening = true
            Log.d(TAG, "Started listening successfully")
            resetRecreationAttempts() // Success, reset counter
        } catch (e: Exception) {
            Log.e(TAG, "Error starting listening", e)
            isListening = false
            handleStartListeningError()
        }
    }

    private fun startListeningAfterSetup() {
        if (!::speechRecognizer.isInitialized) {
            Log.e(TAG, "Speech recognizer still not initialized after setup")
            handleStartListeningError()
            return
        }

        try {
            speechRecognizer.startListening(recognizerIntent)
            isListening = true
            Log.d(TAG, "Started listening after setup")
            resetRecreationAttempts() // Success, reset counter
        } catch (e: Exception) {
            Log.e(TAG, "Error starting listening after setup", e)
            isListening = false
            handleStartListeningError()
        }
    }

    private fun handleStartListeningError() {
        recreationAttempts++
        Log.d(TAG, "Recreation attempt #$recreationAttempts")

        if (recreationAttempts >= MAX_RECREATION_ATTEMPTS) {
            Log.e(TAG, "Too many recreation attempts ($recreationAttempts). Stopping service.")
            Toast.makeText(this, "Speech recognition unstable. Please restart the app.", Toast.LENGTH_LONG).show()
            updateNotification("Recognition failed. Please restart.")
            handler.postDelayed({ stopListeningAndService() }, 3000)
            return
        }

        // Try to recreate speech recognizer after error
        Log.d(TAG, "Will attempt to recreate speech recognizer")
        handler.postDelayed({
            recreateSpeechRecognizerSafely()
        }, 2000) // Longer delay on error
    }

    private fun recreateSpeechRecognizerSafely() {
        // Only one recreation at a time
        if (!isRecreating.compareAndSet(false, true)) {
            Log.d(TAG, "Recreation already in progress, skipping")
            return
        }

        Log.d(TAG, "Safely recreating speech recognizer")

        // Step 1: Clean up existing instance if any
        try {
            if (::speechRecognizer.isInitialized) {
                isListening = false
                speechRecognizer.cancel()
                speechRecognizer.destroy()
                Log.d(TAG, "Successfully destroyed old speech recognizer")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error while destroying old speech recognizer", e)
            // Continue with recreation anyway
        }

        // Step 2: Schedule creation after a significant delay
        handler.postDelayed({
            if (!SpeechRecognizer.isRecognitionAvailable(applicationContext)) {
                Log.e(TAG, "Speech recognition is no longer available")
                Toast.makeText(applicationContext, "Speech recognition unavailable", Toast.LENGTH_SHORT).show()
                handler.postDelayed({ stopListeningAndService() }, 2000)
                isRecreating.set(false)
                return@postDelayed
            }

            try {
                // Create a fresh speech recognizer
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(applicationContext)
                speechRecognizer.setRecognitionListener(createRecognitionListener())
                Log.d(TAG, "New speech recognizer created successfully")

                // Step 3: Try starting after another delay
                handler.postDelayed({
                    try {
                        if (isListening) {
                            speechRecognizer.startListening(recognizerIntent)
                            Log.d(TAG, "Successfully restarted listening after recreation")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start listening after recreation", e)
                        handleStartListeningError() // Will increment attempts counter
                    } finally {
                        isRecreating.set(false)
                    }
                }, 1000)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create new speech recognizer", e)
                isRecreating.set(false)
                handleStartListeningError() // Will increment attempts counter
            }
        }, 2000) // 2 seconds delay
    }

    // Extract the recognition listener creation to a separate method
    private fun createRecognitionListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { Log.d(TAG, "onReadyForSpeech") }
            override fun onBeginningOfSpeech() { Log.d(TAG, "onBeginningOfSpeech") }
            override fun onRmsChanged(rmsdB: Float) { /* Log.v(TAG, "onRmsChanged: $rmsdB") */ }
            override fun onBufferReceived(buffer: ByteArray?) { Log.d(TAG, "onBufferReceived") }
            override fun onEndOfSpeech() {
                Log.d(TAG, "onEndOfSpeech")
                if (isListening) {
                    Log.d(TAG, "onEndOfSpeech: Scheduling restart")
                    // Restart listening after a short delay
                    handler.postDelayed({
                        if (isListening) { // Double-check that we're still supposed to be listening
                            safelyStartListening()
                        }
                    }, 500) // Slightly longer delay to ensure proper cleanup
                }
            }

            override fun onError(error: Int) {
                val errorMessage = getErrorText(error)
                Log.e(TAG, "onError: $error - $errorMessage")

                if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    Toast.makeText(applicationContext, "Mic permission error. Stopping.", Toast.LENGTH_LONG).show()
                    stopListeningAndService()
                    return
                }

                // Handle client errors by recreating the recognizer
                if (error == SpeechRecognizer.ERROR_CLIENT) {
                    Log.d(TAG, "Client error detected, will recreate speech recognizer")
                    handler.postDelayed({
                        if (isListening) {
                            recreateSpeechRecognizerSafely() // Use the safer recreation method
                        }
                    }, 1500) // Longer delay before recreating
                    return
                }

                // Handle other errors with appropriate delays
                val delayMs = when (error) {
                    SpeechRecognizer.ERROR_NETWORK,
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                    SpeechRecognizer.ERROR_SERVER -> 3000L  // Even longer delay for server/network issues
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 2000L  // Increased delay for busy errors
                    else -> 1000L  // Increased general delay
                }

                if (isListening) {
                    handler.postDelayed({
                        if (isListening) {
                            Log.d(TAG, "onError: Restarting listener after delay")
                            safelyStartListening()
                        }
                    }, delayMs)
                }
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                Log.d(TAG, "onResults: ${matches?.joinToString()}")
                processRecognitionResults(results)

                // Start listening again after processing results
                if (isListening) {
                    handler.postDelayed({
                        if (isListening) {
                            safelyStartListening()
                        }
                    }, 500) // Slightly longer delay to prevent rapid-fire restarts
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                Log.v(TAG, "onPartialResults: ${matches?.joinToString()}")
                processRecognitionResults(partialResults)
            }

            override fun onEvent(eventType: Int, params: Bundle?) { Log.d(TAG, "onEvent: $eventType") }
        }
    }

    private fun stopListeningAndService() {
        Log.d(TAG, "Stopping listening and service.")
        stopListening()
        stopForeground(true) // Remove notification
        stopSelf() // Stop the service instance
    }

    private fun stopListening() {
        cancelPendingRecreations() // Cancel any pending operations

        if (::speechRecognizer.isInitialized) {
            Log.d(TAG, "Stopping speech recognizer")
            isListening = false // Set flag immediately
            try {
                speechRecognizer.stopListening() // Request stop
                speechRecognizer.cancel() // Cancel any pending operations
            } catch (e: Exception) {
                Log.e(TAG, "Error while stopping speech recognizer", e)
                // Just continue with cleanup
            }
        }
        isListening = false // Ensure flag is false regardless
        updateNotification("Detection stopped.")
    }

    private fun getSmsManager(): SmsManager? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            applicationContext.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
    }

    private fun getErrorText(errorCode: Int): String {
        return when (errorCode) {
            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
            SpeechRecognizer.ERROR_CLIENT -> "Client side error"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
            SpeechRecognizer.ERROR_NETWORK -> "Network error"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
            SpeechRecognizer.ERROR_NO_MATCH -> "No match"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
            SpeechRecognizer.ERROR_SERVER -> "Server error"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
            else -> "Unknown speech recognition error"
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        // Cancel all pending tasks first
        cancelPendingRecreations()

        stopListening() // Ensure listening is stopped

        // Clean up recognizer if initialized
        if (::speechRecognizer.isInitialized) {
            try {
                speechRecognizer.destroy() // Release recognizer resources
                Log.d(TAG,"Speech recognizer destroyed.")
            } catch (e: Exception) {
                Log.e(TAG, "Error destroying speech recognizer during service shutdown", e)
                // Nothing more to do here
            }
        }

        stopForeground(true) // Ensure notification is removed
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null // Not a bound service
    }
}