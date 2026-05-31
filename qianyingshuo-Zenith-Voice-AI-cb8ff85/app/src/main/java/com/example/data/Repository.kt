package com.example.data

import android.content.Context
import android.util.Log
import com.example.tts.AzureTtsEngine
import com.example.tts.GeminiTtsEngine
import com.example.tts.TtsEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class ChatRepository(private val context: Context, private val chatDao: ChatDao) {

    companion object {
        private const val TAG = "ChatRepository"
    }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val azureTts = AzureTtsEngine(okHttpClient)
    private val geminiTts = GeminiTtsEngine(okHttpClient)
    private val googleDriveManager = GoogleDriveManager(okHttpClient)

    // Flow emitters
    val allSessions: Flow<List<ChatSession>> = chatDao.getAllSessions()

    fun getMessagesForSession(sessionId: Int): Flow<List<ChatMessage>> =
        chatDao.getMessagesForSession(sessionId)

    suspend fun getSessionById(sessionId: Int): ChatSession? =
        chatDao.getSessionById(sessionId)

    suspend fun createNewSession(title: String): Int = withContext(Dispatchers.IO) {
        val session = ChatSession(title = title)
        chatDao.insertSession(session).toInt()
    }

    suspend fun deleteSession(sessionId: Int) = withContext(Dispatchers.IO) {
        // Delete audio files
        val messages = chatDao.getMessagesForSessionSync(sessionId)
        for (msg in messages) {
            msg.localAudioPath?.let { path ->
                val f = File(path)
                if (f.exists()) f.delete()
            }
        }
        chatDao.deleteSessionById(sessionId)
    }

    // Process a sniffer message and generate text to speech audio saving it locally
    suspend fun addMessageAndGenerateTts(
        sessionId: Int,
        sender: String,
        text: String,
        engineType: TtsEngine.EngineType,
        apiKey: String,
        voiceParam: String, // Azure: region, Gemini: voice name (e.g. Kore, Puck)
        voiceProfile: String, // Chosen voice configuration
        onComplete: (Boolean) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        val message = ChatMessage(
            sessionId = sessionId,
            sender = sender,
            text = text
        )
        val msgId = chatDao.insertMessage(message).toInt()

        // If the sender is Gemini, generate TTS. If user, we just save text.
        if (sender == "GEMINI") {
            val audioDir = File(context.filesDir, "speech_logs")
            if (!audioDir.exists()) {
                audioDir.mkdirs()
            }
            val audioFile = File(audioDir, "speech_$msgId.mp3")

            val success = when (engineType) {
                TtsEngine.EngineType.AZURE -> {
                    azureTts.synthesizeAndGetFile(text, apiKey, voiceParam, voiceProfile, audioFile)
                }
                TtsEngine.EngineType.GEMINI -> {
                    geminiTts.synthesizeAndGetFile(text, apiKey, voiceParam, voiceProfile, audioFile)
                }
            }

            if (success && audioFile.exists()) {
                val updatedMsg = chatDao.getMessagesForSessionSync(sessionId).find { it.id == msgId }
                if (updatedMsg != null) {
                    chatDao.updateMessage(updatedMsg.copy(localAudioPath = audioFile.absolutePath))
                }
                withContext(Dispatchers.Main) { onComplete(true) }
            } else {
                Log.e(TAG, "Audio synthesis failed for message $msgId")
                withContext(Dispatchers.Main) { onComplete(false) }
            }
        } else {
            withContext(Dispatchers.Main) { onComplete(true) }
        }
        return@withContext msgId
    }

    // Backup a complete session to Google Drive, implementing automated version control
    suspend fun backupSessionToGoogleDrive(
        sessionId: Int,
        accessToken: String,
        onProgress: (String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val session = chatDao.getSessionById(sessionId) ?: return@withContext false
        val messages = chatDao.getMessagesForSessionSync(sessionId)

        if (messages.isEmpty()) {
            onProgress("日志内容为空，无需备份")
            return@withContext true
        }

        onProgress("正在连接 Google Drive 云空间")
        val mainParentId = googleDriveManager.getOrCreateParentFolder(accessToken)
        if (mainParentId.isNullOrEmpty()) {
            onProgress("建立备份主目录失败")
            return@withContext false
        }

        onProgress("创建会话专属文件夹")
        val sessionFolderId = googleDriveManager.getOrCreateSessionFolder(accessToken, mainParentId, session)
        if (sessionFolderId.isNullOrEmpty()) {
            onProgress("创建会话云盘文件夹失败")
            return@withContext false
        }

        // Save session folder ID to local database
        if (session.gdriveFolderId != sessionFolderId) {
            chatDao.updateSession(session.copy(gdriveFolderId = sessionFolderId))
        }

        onProgress("整理文字历史，开始进行版本增量上传...")
        // Synthesize full text log for version control
        val sb = java.lang.StringBuilder()
        sb.append("=========================================\n")
        sb.append("会话主题: ${session.title}\n")
        sb.append("备份时间: ${java.util.Date()}\n")
        sb.append("=========================================\n\n")

        for (msg in messages) {
            val senderLabel = if (msg.sender == "USER") "用户 [USER]: " else "大模型 [GEMINI]: "
            val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            val timeStr = sdf.format(Date(msg.timestamp))
            sb.append("[$timeStr] $senderLabel\n")
            sb.append("${msg.text}\n")
            sb.append("-----------------------------------------\n")
        }

        val textHistory = sb.toString()
        val textFileId = googleDriveManager.uploadOrUpdateTextHistory(accessToken, sessionFolderId, textHistory)
        if (textFileId.isNullOrEmpty()) {
            onProgress("上传文本历史日志失败")
            return@withContext false
        }

        // Upload any unsynced audio files
        onProgress("开始检索待备份的本地离线语音...")
        var audioUploadSuccessCount = 0
        for (msg in messages) {
            if (msg.sender == "GEMINI" && !msg.localAudioPath.isNullOrEmpty() && msg.gdriveAudioFileId.isNullOrEmpty()) {
                val f = File(msg.localAudioPath)
                if (f.exists()) {
                    onProgress("同步语音: msg_${msg.id}.mp3")
                    val audioId = googleDriveManager.uploadBinaryAudio(accessToken, sessionFolderId, f, msg.id)
                    if (audioId != null) {
                        chatDao.updateMessage(msg.copy(gdriveAudioFileId = audioId, isSynced = true))
                        audioUploadSuccessCount++
                    }
                }
            }
        }

        // Mark session as synced
        chatDao.updateSession(session.copy(isSynced = true, lastSyncTime = System.currentTimeMillis()))
        
        onProgress("备份完成！云端日志已覆盖(版本已控制)，新增同步语音 $audioUploadSuccessCount 条")
        return@withContext true
    }
}
