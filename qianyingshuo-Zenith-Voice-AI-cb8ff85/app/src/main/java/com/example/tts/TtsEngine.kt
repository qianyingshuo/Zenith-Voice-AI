package com.example.tts

import java.io.File

interface TtsEngine {
    enum class EngineType {
        AZURE,
        GEMINI
    }

    suspend fun synthesizeAndGetFile(
        text: String,
        apiKey: String,
        param: String, // Azure: region, Gemini: voice name (e.g. Kore, Puck)
        voiceName: String, // Preferred voice profile
        outputFile: File
    ): Boolean
}
