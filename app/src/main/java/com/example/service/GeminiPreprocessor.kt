package com.example.service

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object GeminiPreprocessor {
    private const val TAG = "GeminiPreprocessor"

    suspend fun preprocessText(
        text: String,
        apiKey: String,
        okHttpClient: OkHttpClient
    ): String = withContext(Dispatchers.IO) {
        if (apiKey.isEmpty()) {
            Log.d(TAG, "Gemini API key is empty. Skipping preprocessing.")
            return@withContext text
        }

        // Check if there is potential work to do: contains a table structure, a web link, or an image format
        val hasTable = text.contains("|") || text.contains("---")
        val hasImg = text.contains("![") || text.contains(".jpg") || text.contains(".png") || text.contains("<img")
        val hasLink = text.contains("http://") || text.contains("https://") || text.contains("www.")
        
        if (!hasTable && !hasImg && !hasLink) {
            Log.d(TAG, "Text has no tables, images, or links. Skipping preprocessing.")
            return@withContext text
        }

        Log.d(TAG, "Starting Gemini text preprocessing for tables and media links...")

        // We use the recommended 'gemini-3.5-flash' model for basic text and processing tasks
        val modelName = "gemini-3.5-flash"
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey"

        val systemInstruction = """
            你是一个在后台运行的高端语音合成前置处理助手。你的任务是将大模型（Gemini）输出的回复文本进行智能规整和清洗，使其非常适合文本转语音（TTS）引擎朗读。

            【清洗与重组规范】：
            1. **表格结构处理（核心）**：
               - 检测文本中所有的 Markdown、HTML 或文本对齐格式的表格。
               - **绝对不要**输出原始表格字符（例如 `|`、`---`、对齐空格等）。
               - 必须通读表格的行和列，理解其行文主题与逻辑，将其转换成一连串连贯、流畅、口语化的汉字文字描述。
               - 例如有如下表格：
                 | 日期 | 天气 |
                 | 周一 | 晴天 |
                 | 周二 | 小雨 |
                 你可以替换为：“具体内容如下。在周一，天气是晴天；在周二，天气是小雨。” 使得语音助手朗读时自然通顺。
               - 用你精心重写的口语化描述，精确且完整地替换原表格对应的位置。

            2. **多媒体与链接处理**：
               - 检测文本中包含的所有图片引用（如 Markdown 格式 `![描述](url)` 或 HTML `<img>` 等）、独立的视频链接（特别是 `youtube.com`、`youtu.be`、bilibili等视频分享链接，以及各种非学术引用类的独立跳转链接）。
               - **删除**这些多媒体链接和图片的原始 URL，绝对不应该把长串的“http-s 冒号斜杠斜杠...”让语音助手机械地读出来。
               - 仔细统计你删除的图片、视频、独立外部网页链接的数量（区分图片、视频及通用独立网页链接）。
               - 忽略普通的学术/文章参考文献引用标识（如角标 `[1]`、`[2]` 或 `[source]` 链接，这些不需要特殊过滤和提醒，直接保留或读成普通数字即可）。
               - **如果原文本中包含多媒体（图片、视频、独立多媒体跳转链接等）**：
                 - 在你处理好表格并去除长链接的整篇文本的**最后，单独另起一行**，附加一句自然的、口语化的听众友好提示，提醒用户返回手机查看对应的内容。
                 - 提示词范例：“（提示：此回复中包含1张图片和一个视频链接，建议您稍后返回应用查看详情）”
                 - 如果没有删除任何这类多媒体/链接内容，则千万**不要**添加此提示。

            3. **输出限制 (极度重要)**：
               - 仅仅输出转换并规整后的最终纯文本。
               - 绝对不能在开头或结尾添加任何解释性的废话（严禁输出类似：“好的，我帮您清洗了文本：”、“以下是转换后的内容”或“*处理完毕*”等）。
               - 必须保留原文的核心意思，不要缩减或改写与表格、链接无关的正常文字部分。
        """.trimIndent()

        try {
            val contentPart = JSONObject().put("text", text)
            val contentsArray = JSONArray().put(JSONObject().put("parts", JSONArray().put(contentPart)))

            val systemInstructionPart = JSONObject().put("text", systemInstruction)
            val systemInstructionObj = JSONObject().put("parts", JSONArray().put(systemInstructionPart))

            val payloadObj = JSONObject()
                .put("contents", contentsArray)
                .put("systemInstruction", systemInstructionObj)

            val requestBody = payloadObj.toString().toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            // Setup temporary OkHttpClient with 60s timeout to respect gemini-api skill rules
            val client = okHttpClient.newBuilder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Gemini preprocessing failed: Code ${response.code}, Message: ${response.message}")
                    return@withContext text
                }

                val responseStr = response.body?.string() ?: ""
                if (responseStr.isEmpty()) {
                    return@withContext text
                }

                val parsed = JSONObject(responseStr)
                val candidates = parsed.optJSONArray("candidates")
                val candidateObj = candidates?.optJSONObject(0)
                val contentObj = candidateObj?.optJSONObject("content")
                val partsArray = contentObj?.optJSONArray("parts")
                val partObj = partsArray?.optJSONObject(0)
                val processedText = partObj?.optString("text") ?: ""

                if (processedText.isNotEmpty()) {
                    Log.d(TAG, "Preprocessing success. Replaced formatting successfully.")
                    return@withContext processedText.trim()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error performing Gemini preprocessing: ${e.message}", e)
        }

        // Graceful fallback to original text
        return@withContext text
    }
}
