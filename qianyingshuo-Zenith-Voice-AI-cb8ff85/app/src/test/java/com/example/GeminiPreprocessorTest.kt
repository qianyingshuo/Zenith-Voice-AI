package com.example

import com.example.service.GeminiPreprocessor
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GeminiPreprocessorTest {

    private val okHttpClient = OkHttpClient()

    @Test
    fun `when apiKey is empty, preprocessText returns the original text immediately`() = runBlocking {
        val originalText = "| Day | Weather |\n| Mon | Sunny |"
        val result = GeminiPreprocessor.preprocessText(originalText, "", okHttpClient)
        assertEquals(originalText, result)
    }

    @Test
    fun `when text has no tables, images, or links, preprocessText returns original text`() = runBlocking {
        val originalText = "Hello world! This is a simple message from the sniffer service."
        val result = GeminiPreprocessor.preprocessText(originalText, "mock-api-key", okHttpClient)
        assertEquals(originalText, result)
    }

    @Test
    fun `when networking fails or gets 403, preprocessText gracefully falls back to original text`() = runBlocking {
        val originalText = "Here are some tables | col1 | col2 |"
        val result = GeminiPreprocessor.preprocessText(originalText, "invalid-bad-api-key", okHttpClient)
        // Since API key is bad, the request will fail, but the helper must not crash and fallback gracefully to the original text.
        assertEquals(originalText, result)
    }
}
