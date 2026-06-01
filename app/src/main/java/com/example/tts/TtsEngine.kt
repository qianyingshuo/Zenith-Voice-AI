package com.example.tts

import java.io.File

interface TtsEngine {
    enum class EngineType {
        AZURE,
        GEMINI;

        companion object {
            fun fromStringSafely(value: String?): EngineType {
                if (value == null) return AZURE
                return try {
                    valueOf(value.uppercase().trim())
                } catch (e: Exception) {
                    AZURE
                }
            }
        }
    }

    suspend fun synthesizeAndGetFile(
        text: String,
        apiKey: String,
        param: String, // Azure: region, Gemini: voice name (e.g. Kore, Puck)
        voiceName: String, // Preferred voice profile
        outputFile: File
    ): Boolean
}
