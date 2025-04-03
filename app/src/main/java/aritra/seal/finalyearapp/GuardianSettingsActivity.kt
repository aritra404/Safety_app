package aritra.seal.finalyearapp


import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class GuardianSettingsActivity : AppCompatActivity() {
    private lateinit var guardianNumberEditText: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_guardian_settings)

        guardianNumberEditText = findViewById(R.id.guardianNumberEditText)
        val saveButton: Button = findViewById(R.id.saveButton)

        // Load existing guardian number
        val sharedPref = getSharedPreferences("HelpDetectorPrefs", MODE_PRIVATE)
        val guardianNumber = sharedPref.getString("guardianNumber", "")
        guardianNumberEditText.setText(guardianNumber)

        saveButton.setOnClickListener {
            val number = guardianNumberEditText.text.toString().trim()
            if (number.isNotEmpty()) {
                // Save guardian number
                val editor = sharedPref.edit()
                editor.putString("guardianNumber", number)
                editor.apply()

                Toast.makeText(this, "Guardian number saved", Toast.LENGTH_SHORT).show()
                finish()
            } else {
                Toast.makeText(this, "Please enter a valid phone number", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
