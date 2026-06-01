package com.example.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.data.AppDatabase
import com.example.data.ChatRepository
import com.example.tts.TtsEngine
import com.example.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import okhttp3.OkHttpClient

class TtsPlaybackService : Service(), AudioManager.OnAudioFocusChangeListener {

    companion object {
        const val ACTION_PLAY_SPEECH = "com.example.service.ACTION_PLAY_SPEECH"
        const val ACTION_STOP_SPEECH = "com.example.service.ACTION_STOP_SPEECH"
        const val EXTRA_SPEECH_TEXT = "com.example.service.EXTRA_SPEECH_TEXT"
        const val EXTRA_IS_USER = "com.example.service.EXTRA_IS_USER"
        const val EXTRA_START_AS_FOREGROUND = "com.example.service.EXTRA_START_AS_FOREGROUND"

        private const val NOTIFICATION_CHANNEL_ID = "TtsPlaybackServiceChannel"
        private const val NOTIFICATION_ID = 2026
        private const val TAG = "TtsPlaybackService"
    }

    private lateinit var audioManager: AudioManager
    private lateinit var repository: ChatRepository
    private val okHttpClient = OkHttpClient()
    private var mediaPlayer: MediaPlayer? = null
    private var focusRequest: AudioFocusRequest? = null
    private var mediaSession: android.media.session.MediaSession? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    override fun onCreate() {
        super.onCreate()
        AppLogger.init(this)
        AppLogger.i(TAG, "TtsPlaybackService: Sound engine playing back background service started.")
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        
        val database = AppDatabase.getDatabase(applicationContext)
        repository = ChatRepository(applicationContext, database.chatDao())

        try {
            mediaSession = android.media.session.MediaSession(this, "GeminiTtsBridgeSession").apply {
                isActive = true
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to initialize MediaSession", e)
        }

        setupLocks()
        setupNotificationChannel()
    }

    private fun setupLocks() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AudioBridge::WakeLock")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to initialize wakeLock", e)
        }

        try {
            val wifiManager = getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "AudioBridge::WifiLock")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to initialize wifiLock", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        
        // Always promote immediately to foreground state to satisfy background launch rules
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    buildOngoingNotification("无障碍语音桥接器已启动，等待接收聊天内容"),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                @Suppress("DEPRECATION")
                startForeground(NOTIFICATION_ID, buildOngoingNotification("无障碍语音桥接器已启动，等待接收聊天内容"))
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to start foreground natively inside startCommand", e)
        }

        if (action == ACTION_PLAY_SPEECH) {
            val text = intent?.getStringExtra(EXTRA_SPEECH_TEXT) ?: ""
            val isUser = intent?.getBooleanExtra(EXTRA_IS_USER, false) == true
            if (text.isNotEmpty()) {
                processAndPlay(text, isUser)
            }
        } else if (action == ACTION_STOP_SPEECH) {
            stopPlaying()
        }
        return START_STICKY
    }

    private fun processAndPlay(text: String, isUser: Boolean) {
        serviceScope.launch {
            acquireWakeLocks()
            
            // Get user credentials from shared preferences
            val prefs = getSharedPreferences("secure_credentials", Context.MODE_PRIVATE)
            val engineTypeStr = prefs.getString("engine_type", TtsEngine.EngineType.AZURE.name)
            val engineType = TtsEngine.EngineType.fromStringSafely(engineTypeStr)
            
            val azureKey = prefs.getString("api_key_azure", "") ?: ""
            val azureRegion = prefs.getString("region_azure", "") ?: ""
            val azureVoice = prefs.getString("voice_profile", "zh-CN-YunxiNeural") ?: "zh-CN-YunxiNeural"

            val geminiKey = prefs.getString("api_key_gemini", "") ?: ""
            val geminiVoice = prefs.getString("voice_profile_gemini", "Kore") ?: "Kore"

            val activeSessionId = prefs.getInt("active_session_id", -1)
            var targetSessionId = activeSessionId
            if (targetSessionId == -1) {
                // Auto create a session if none is selected
                targetSessionId = repository.createNewSession("智能捕获对话")
                prefs.edit().putInt("active_session_id", targetSessionId).apply()
            }

            // Determine key & config matching selected TTS engine
            val apiKey = if (engineType == TtsEngine.EngineType.AZURE) azureKey else geminiKey
            val voiceParam = if (engineType == TtsEngine.EngineType.AZURE) azureRegion else geminiVoice
            val voiceProfile = if (engineType == TtsEngine.EngineType.AZURE) azureVoice else geminiVoice

            var processedText = text
            if (!isUser) {
                updateNotificationText("正在预处理表格和多媒体内容...")
                processedText = GeminiPreprocessor.preprocessText(text, geminiKey, okHttpClient)
            }

            updateNotificationText(if (isUser) "捕获输入: $processedText" else "合成语音中: $processedText")

            repository.addMessageAndGenerateTts(
                sessionId = targetSessionId,
                sender = if (isUser) "USER" else "GEMINI",
                text = processedText,
                engineType = engineType,
                apiKey = apiKey,
                voiceParam = voiceParam,
                voiceProfile = voiceProfile
            ) { success ->
                if (success && !isUser) {
                    serviceScope.launch {
                        // Retrieve the most recent message for this session that has audio
                        val database = AppDatabase.getDatabase(applicationContext)
                        val messages = database.chatDao().getMessagesForSessionSync(targetSessionId)
                        val msgWithAudio = messages.lastOrNull { !it.localAudioPath.isNullOrEmpty() }
                        if (msgWithAudio != null && msgWithAudio.localAudioPath != null) {
                            playLocalAudioFile(File(msgWithAudio.localAudioPath))
                        } else {
                            releaseWakeLocks()
                        }
                    }
                } else {
                    releaseWakeLocks()
                }

                // Call automatic Google Drive backup if configured and OAuth token is available
                val driveAutoSync = prefs.getBoolean("drive_auto_sync", false)
                val oauthToken = prefs.getString("gdrive_oauth_token", "") ?: ""
                if (driveAutoSync && oauthToken.isNotEmpty()) {
                    serviceScope.launch {
                        repository.backupSessionToGoogleDrive(targetSessionId, oauthToken) {
                            Log.d(TAG, "Sync Status: $it")
                        }
                    }
                }
            }
        }
    }

    private fun playLocalAudioFile(file: File) {
        if (!file.exists()) {
            releaseWakeLocks()
            return
        }

        if (requestAudioFocus()) {
            try {
                mediaPlayer?.release()
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(file.absolutePath)
                    setOnCompletionListener {
                        abandonAudioFocus()
                        releaseWakeLocks()
                    }
                    setOnErrorListener { _, _, _ ->
                        abandonAudioFocus()
                        releaseWakeLocks()
                        true
                    }
                    prepare()
                    start()
                }
                updateNotificationText("正在播出语音...")
            } catch (e: Exception) {
                AppLogger.e(TAG, "MediaPlayer setup failed: ${e.message}", e)
                abandonAudioFocus()
                releaseWakeLocks()
            }
        } else {
            releaseWakeLocks()
        }
    }

    private fun stopPlaying() {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.stop()
                }
                it.release()
            }
            mediaPlayer = null
        } catch (e: Exception) {
            AppLogger.e(TAG, "MediaPlayer failed to reset correctly", e)
        } finally {
            abandonAudioFocus()
            releaseWakeLocks()
            updateNotificationText("播报暂停")
        }
    }

    private fun acquireWakeLocks() {
        try {
            wakeLock?.let { if (!it.isHeld) it.acquire(5 * 60 * 1000L) }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to acquire wake lock", e)
        }
        try {
            wifiLock?.let { if (!it.isHeld) it.acquire() }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to acquire wifi lock", e)
        }
    }

    private fun releaseWakeLocks() {
        try {
            wifiLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to release wifi lock", e)
        }
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to release wake lock", e)
        }
    }

    private fun requestAudioFocus(): Boolean {
        val playbackAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(playbackAttributes)
                .setAcceptsDelayedFocusGain(true)
                .setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener(this)
                .build()
            focusRequest = request
            audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                this,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(this)
        }
    }

    override fun onAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> stopPlaying()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Lower volume or pause temporarily
                mediaPlayer?.let {
                    if (it.isPlaying) {
                        it.pause()
                    }
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                // Regain volume or resume
                mediaPlayer?.let {
                    try {
                        it.start()
                    } catch (e: Exception) {
                        Log.e(TAG, "Resume failed", e)
                    }
                }
            }
        }
    }

    private fun updateNotificationText(text: String) {
        val displaySnippet = if (text.length > 30) text.take(30) + "..." else text
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, buildOngoingNotification(displaySnippet))
    }

    private fun buildOngoingNotification(content: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Gemini 语音播报引擎")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.presence_audio_online)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        return builder.build()
    }

    private fun setupNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "后台语音播报通道",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保障锁屏或切后台运行下的语音回放与连接"
                setSound(null, null)
                enableVibration(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        stopPlaying()
        try {
            mediaSession?.isActive = false
            mediaSession?.release()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to release MediaSession", e)
        }
        serviceJob.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
