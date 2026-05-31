package com.example.tts

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

class GeminiTtsEngine(private val okHttpClient: OkHttpClient) : TtsEngine {

    companion object {
        private const val TAG = "GeminiTtsEngine"
    }

    override suspend fun synthesizeAndGetFile(
        text: String,
        apiKey: String,
        param: String, // Preferred voice name (e.g. Kore, Puck, Fenrir, Aoede, Charon)
        voiceName: String, // fallback / same parameter
        outputFile: File
    ): Boolean = withContext(Dispatchers.IO) {
        if (apiKey.isEmpty()) {
            Log.e(TAG, "Gemini API key is empty.")
            return@withContext false
        }

        val voice = param.ifEmpty { voiceName }.ifEmpty { "Kore" }
        // Use gemini-2.5-flash-preview-tts as defined in the gemini-api skill for TTS tasks
        val modelName = "gemini-2.5-flash-preview-tts"
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey"

        try {
            // Build request JSON programmatically and safely
            val argPart = JSONObject().put("text", text)
            val partsArray = JSONArray().put(argPart)
            val contentObj = JSONObject().put("parts", partsArray)
            val contentsArray = JSONArray().put(contentObj)

            val prebuiltVoiceConfig = JSONObject().put("voiceName", voice)
            val voiceConfig = JSONObject().put("prebuiltVoiceConfig", prebuiltVoiceConfig)
            val speechConfig = JSONObject().put("voiceConfig", voiceConfig)

            val generationConfig = JSONObject()
                .put("responseModalities", JSONArray().put("AUDIO"))
                .put("speechConfig", speechConfig)

            val payloadObj = JSONObject()
                .put("contents", contentsArray)
                .put("generationConfig", generationConfig)

            val requestBody = payloadObj.toString().toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Gemini TTS API call failed: Code ${response.code}, Message: ${response.message}")
                    Log.e(TAG, "Response Body: ${response.body?.string()}")
                    return@withContext false
                }

                val responseStr = response.body?.string() ?: ""
                if (responseStr.isEmpty()) {
                    Log.e(TAG, "Gemini TTS returned empty body")
                    return@withContext false
                }

                val parsed = JSONObject(responseStr)
                val candidates = parsed.optJSONArray("candidates")
                val candidateScreen = candidates?.optJSONObject(0)
                val contentLevel = candidateScreen?.optJSONObject("content")
                val partsLevel = contentLevel?.optJSONArray("parts")
                val partLevel = partsLevel?.optJSONObject(0)
                val inlineData = partLevel?.optJSONObject("inlineData")
                val base64Data = inlineData?.optString("data") ?: ""

                if (base64Data.isEmpty()) {
                    Log.e(TAG, "Gemini TTS did not return audio data in inlineData")
                    return@withContext false
                }

                val audioBytes = Base64.decode(base64Data, Base64.DEFAULT)
                FileOutputStream(outputFile).use { fos ->
                    fos.write(audioBytes)
                }

                Log.d(TAG, "Gemini Audio synthesized successfully to ${outputFile.absolutePath}")
                return@withContext true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in Gemini TTS synthesis: ${e.message}", e)
            return@withContext false
        }
    }
}
