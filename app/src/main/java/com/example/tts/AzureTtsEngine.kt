package com.example.tts

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream

class AzureTtsEngine(private val okHttpClient: OkHttpClient) : TtsEngine {

    companion object {
        private const val TAG = "AzureTtsEngine"
    }

    override suspend fun synthesizeAndGetFile(
        text: String,
        apiKey: String,
        param: String, // represents "region" (e.g. "eastus")
        voiceName: String, // e.g. "zh-CN-YunxiNeural"
        outputFile: File
    ): Boolean = withContext(Dispatchers.IO) {
        val region = param.trim()
        if (region.isEmpty() || apiKey.isEmpty()) {
            Log.e(TAG, "Azure credentials or region is empty.")
            return@withContext false
        }

        val url = "https://$region.tts.speech.microsoft.com/cognitiveservices/v1"
        val escapedText = escapeXml(text)
        
        val ssml = """
            <speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' 
                   xmlns:mstts='https://www.w3.org/2001/mstts' xml:lang='zh-CN'>
                <voice name='$voiceName'>
                    <mstts:express-as style='chat' styledegree='1.2'>
                        $escapedText
                    </mstts:express-as>
                </voice>
            </speak>
        """.trimIndent()

        val requestBody = ssml.toRequestBody("application/ssml+xml".toMediaType())

        val request = Request.Builder()
            .url(url)
            .addHeader("Ocp-Apim-Subscription-Key", apiKey)
            .addHeader("Content-Type", "application/ssml+xml")
            .addHeader("X-Microsoft-OutputFormat", "audio-16khz-128kbitrate-mono-mp3")
            .addHeader("User-Agent", "GeminiAudioSniffer")
            .post(requestBody)
            .build()

        try {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Azure TTS API call failed: Code ${response.code}, Message: ${response.message}")
                    Log.e(TAG, "Response Body: ${response.body?.string()}")
                    return@withContext false
                }

                val body = response.body
                if (body == null) {
                    Log.e(TAG, "Azure TTS returned empty body")
                    return@withContext false
                }

                body.byteStream().use { inputStream ->
                    FileOutputStream(outputFile).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                Log.d(TAG, "Azure audio synthesized successfully to ${outputFile.absolutePath}")
                return@withContext true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in Azure TTS synthesis: ${e.message}", e)
            return@withContext false
        }
    }

    private fun escapeXml(input: String): String {
        return input.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
