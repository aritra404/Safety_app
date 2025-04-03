package aritra.seal.finalyearapp

import android.app.VoiceInteractor
import android.content.Context
import java.io.File
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.TimeUnit

class ScreamDetector(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // Update this with your server's address
    private val serverUrl = "https://26cf-34-48-7-183.ngrok-free.app"

    /**
     * Send audio to the server for scream detection
     * @param audioFile The audio file to analyze
     * @return true if a scream is detected, false otherwise
     */
    suspend fun detectScream(audioFile: File): ScreamDetectionResult = withContext(Dispatchers.IO) {
        try {
            val request = createMultipartRequest("$serverUrl/predict", audioFile)

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Unexpected response: ${response.code}")
                }

                val responseBody = response.body?.string() ?: throw IOException("Empty response body")
                val jsonResponse = JSONObject(responseBody)

                return@withContext ScreamDetectionResult(
                    isScream = jsonResponse.getBoolean("is_scream"),
                    confidence = jsonResponse.getDouble("confidence").toFloat(),
                    errorMessage = null
                )
            }
        } catch (e: Exception) {
            return@withContext ScreamDetectionResult(
                isScream = false,
                confidence = 0f,
                errorMessage = "Error: ${e.message}"
            )
        }
    }

    /**
     * Send audio to the server for feature extraction only
     * @param audioFile The audio file to extract features from
     * @return Array of extracted features
     */
    suspend fun extractFeatures(audioFile: File): FloatArray = withContext(Dispatchers.IO) {
        try {
            val request = createMultipartRequest("$serverUrl/extract_features", audioFile)

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Unexpected response: ${response.code}")
                }

                val responseBody = response.body?.string() ?: throw IOException("Empty response body")
                val jsonResponse = JSONObject(responseBody)

                val featuresJson = jsonResponse.getJSONArray("features")
                return@withContext FloatArray(featuresJson.length()) { i ->
                    featuresJson.getDouble(i).toFloat()
                }
            }
        } catch (e: Exception) {
            throw IOException("Feature extraction failed: ${e.message}", e)
        }
    }

    private fun createMultipartRequest(url: String, audioFile: File): Request {
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "audio",
                audioFile.name,
                audioFile.asRequestBody("audio/*".toMediaTypeOrNull())
            )
            .build()

        return Request.Builder()
            .url(url)
            .post(requestBody)
            .build()
    }

    data class ScreamDetectionResult(
        val isScream: Boolean,
        val confidence: Float,
        val errorMessage: String?
    )
}