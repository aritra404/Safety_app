package aritra.seal.finalyearapp


import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telephony.SmsManager
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices

class EmergencyHandlerActivity : AppCompatActivity() {
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var guardianNumber: String? = null
    private var locationSent = false
    private var callMade = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_emergency_handler)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // Get guardian number from intent
        guardianNumber = intent.getStringExtra("GUARDIAN_NUMBER")

        // Setup UI
        findViewById<TextView>(R.id.emergencyStatusText).text = "Emergency Detected!\nContacting guardian..."

        // Add cancel button functionality
        findViewById<Button>(R.id.cancelEmergencyButton).setOnClickListener {
            finish()
        }

        // Handle the emergency
        if (!guardianNumber.isNullOrEmpty()) {
            handleEmergency(guardianNumber!!)
        } else {
            Toast.makeText(this, "Guardian number not set!", Toast.LENGTH_LONG).show()
            findViewById<TextView>(R.id.emergencyStatusText).text = "ERROR: Guardian number not set!"
        }
    }

    private fun handleEmergency(phoneNumber: String) {
        // First send SMS
        sendEmergencySMS(phoneNumber)

        // Then make call after a short delay
        Handler(Looper.getMainLooper()).postDelayed({
            makeEmergencyCall(phoneNumber)
        }, 2000)
    }

    private fun sendEmergencySMS(phoneNumber: String) {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.SEND_SMS
            ) == PackageManager.PERMISSION_GRANTED) {

            val message = "EMERGENCY: I need help! This is an urgent alert from my safety app."

            try {
                val smsManager = SmsManager.getDefault()
                smsManager.sendTextMessage(phoneNumber, null, message, null, null)
                Log.i("EmergencyHandler", "Emergency SMS sent")
                locationSent = true
                updateStatus("SMS sent to guardian")

                // Get location for a follow-up SMS
                getCurrentLocation { location ->
                    if (location != null) {
                        val locationUrl = "https://maps.google.com/?q=${location.latitude},${location.longitude}"
                        val locationMessage = "My current location is: $locationUrl"
                        try {
                            smsManager.sendTextMessage(phoneNumber, null, locationMessage, null, null)
                            Log.i("EmergencyHandler", "Location SMS sent")
                            updateStatus("Location SMS sent to guardian")
                        } catch (e: Exception) {
                            Log.e("EmergencyHandler", "Location SMS failed: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("EmergencyHandler", "Failed to send SMS: ${e.message}")
                updateStatus("Failed to send SMS: ${e.message}")
            }
        } else {
            requestSmsPermission()
        }
    }

    private fun makeEmergencyCall(phoneNumber: String) {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CALL_PHONE
            ) == PackageManager.PERMISSION_GRANTED) {

            try {
                val callIntent = Intent(Intent.ACTION_CALL)
                callIntent.data = Uri.parse("tel:$phoneNumber")
                startActivity(callIntent)
                callMade = true
                Log.i("EmergencyHandler", "Emergency call initiated")
                updateStatus("Calling guardian...")
            } catch (e: Exception) {
                Log.e("EmergencyHandler", "Failed to make call: ${e.message}")
                updateStatus("Failed to call guardian: ${e.message}")
            }
        } else {
            requestCallPermission()
        }
    }

    private fun requestSmsPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.SEND_SMS),
            100
        )
    }

    private fun requestCallPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.CALL_PHONE),
            101
        )
    }

    private fun getCurrentLocation(callback: (Location?) -> Unit) {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED) {

            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                callback(location)
            }.addOnFailureListener {
                callback(null)
            }
        } else {
            callback(null)
        }
    }

    private fun updateStatus(message: String) {
        runOnUiThread {
            val currentText = findViewById<TextView>(R.id.emergencyStatusText).text.toString()
            findViewById<TextView>(R.id.emergencyStatusText).text = "$currentText\n$message"
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            100 -> {
                // SMS permission result
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    guardianNumber?.let { sendEmergencySMS(it) }
                }
            }
            101 -> {
                // Call permission result
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    guardianNumber?.let { makeEmergencyCall(it) }
                }
            }
        }
    }

    // Keep screen on during emergency
    override fun onResume() {
        super.onResume()
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onPause() {
        super.onPause()
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}