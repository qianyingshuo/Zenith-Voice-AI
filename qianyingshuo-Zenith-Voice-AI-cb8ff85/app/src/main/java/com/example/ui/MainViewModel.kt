package com.example.ui

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.ChatMessage
import com.example.data.ChatRepository
import com.example.data.ChatSession
import com.example.tts.TtsEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MainViewModel"
        private const val PREFS_FILE = "secure_credentials"
    }

    private val prefs: SharedPreferences = application.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
    private val database = AppDatabase.getDatabase(application)
    val repository = ChatRepository(application, database.chatDao())

    // --- State Observables ---
    val allSessions: StateFlow<List<ChatSession>> = repository.allSessions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _activeSessionId = MutableStateFlow(prefs.getInt("active_session_id", -1))
    val activeSessionId: StateFlow<Int> = _activeSessionId.asStateFlow()

    val activeSessionMessages: StateFlow<List<ChatMessage>> = activeSessionId
        .flatMapLatest { sessionId ->
            if (sessionId != -1) {
                repository.getMessagesForSession(sessionId)
            } else {
                flowOf(emptyList())
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // --- Configuration States ---
    private val _engineType = MutableStateFlow(TtsEngine.EngineType.valueOf(prefs.getString("engine_type", TtsEngine.EngineType.AZURE.name) ?: TtsEngine.EngineType.AZURE.name))
    val engineType = _engineType.asStateFlow()

    private val _apiKeyAzure = MutableStateFlow(prefs.getString("api_key_azure", "") ?: "")
    val apiKeyAzure = _apiKeyAzure.asStateFlow()

    private val _regionAzure = MutableStateFlow(prefs.getString("region_azure", "") ?: "")
    val regionAzure = _regionAzure.asStateFlow()

    private val _voiceAzure = MutableStateFlow(prefs.getString("voice_profile", "zh-CN-YunxiNeural") ?: "zh-CN-YunxiNeural")
    val voiceAzure = _voiceAzure.asStateFlow()

    private val _apiKeyGemini = MutableStateFlow(prefs.getString("api_key_gemini", "") ?: "")
    val apiKeyGemini = _apiKeyGemini.asStateFlow()

    private val _voiceGemini = MutableStateFlow(prefs.getString("voice_profile_gemini", "Kore") ?: "Kore")
    val voiceGemini = _voiceGemini.asStateFlow()

    private val _isSnifferEnabled = MutableStateFlow(prefs.getBoolean("sniffer_service_enabled", true))
    val isSnifferEnabled = _isSnifferEnabled.asStateFlow()

    // --- Google Drive States ---
    private val _isDriveAutoSync = MutableStateFlow(prefs.getBoolean("drive_auto_sync", false))
    val isDriveAutoSync = _isDriveAutoSync.asStateFlow()

    private val _gdriveOAuthToken = MutableStateFlow(prefs.getString("gdrive_oauth_token", "") ?: "")
    val gdriveOAuthToken = _gdriveOAuthToken.asStateFlow()

    private val _gdriveUserEmail = MutableStateFlow(prefs.getString("gdrive_user_email", "") ?: "")
    val gdriveUserEmail = _gdriveUserEmail.asStateFlow()

    private val _syncMessage = MutableStateFlow("")
    val syncMessage = _syncMessage.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing = _isSyncing.asStateFlow()

    init {
        // Auto select first session as active if available and none selected
        viewModelScope.launch {
            allSessions.firstOrNull()?.firstOrNull()?.let { firstSession ->
                if (activeSessionId.value == -1) {
                    setActiveSessionId(firstSession.id)
                }
            }
        }
    }

    // --- State Setters ---
    fun setEngineType(type: TtsEngine.EngineType) {
        _engineType.value = type
        prefs.edit().putString("engine_type", type.name).apply()
    }

    fun setCredentialsAzure(key: String, region: String, voice: String) {
        _apiKeyAzure.value = key
        _regionAzure.value = region
        _voiceAzure.value = voice
        prefs.edit()
            .putString("api_key_azure", key)
            .putString("region_azure", region)
            .putString("voice_profile", voice)
            .apply()
    }

    fun setCredentialsGemini(key: String, voice: String) {
        _apiKeyGemini.value = key
        _voiceGemini.value = voice
        prefs.edit()
            .putString("api_key_gemini", key)
            .putString("voice_profile_gemini", voice)
            .apply()
    }

    fun setSnifferEnabled(enabled: Boolean) {
        _isSnifferEnabled.value = enabled
        prefs.edit().putBoolean("sniffer_service_enabled", enabled).apply()
    }

    fun setDriveAutoSync(enabled: Boolean) {
        _isDriveAutoSync.value = enabled
        prefs.edit().putBoolean("drive_auto_sync", enabled).apply()
    }

    fun saveGoogleDriveToken(token: String, email: String) {
        _gdriveOAuthToken.value = token
        _gdriveUserEmail.value = email
        prefs.edit()
            .putString("gdrive_oauth_token", token)
            .putString("gdrive_user_email", email)
            .apply()
    }

    fun disconnectGoogleDrive() {
        _gdriveOAuthToken.value = ""
        _gdriveUserEmail.value = ""
        prefs.edit()
            .putString("gdrive_oauth_token", "")
            .putString("gdrive_user_email", "")
            .putBoolean("drive_auto_sync", false)
            .apply()
        _isDriveAutoSync.value = false
    }

    fun setActiveSessionId(sessionId: Int) {
        _activeSessionId.value = sessionId
        prefs.edit().putInt("active_session_id", sessionId).apply()
    }

    // --- Action Handlers ---
    fun createNewSession(title: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val trimmedTitle = title.trim().ifEmpty { "新对话会话" }
            val newId = repository.createNewSession(trimmedTitle)
            setActiveSessionId(newId)
        }
    }

    fun deleteSession(sessionId: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteSession(sessionId)
            if (activeSessionId.value == sessionId) {
                _activeSessionId.value = -1
                prefs.edit().putInt("active_session_id", -1).apply()
            }
        }
    }

    fun triggerManualBackup(sessionId: Int) {
        val token = gdriveOAuthToken.value
        if (token.isEmpty()) {
            _syncMessage.value = "授权过期，请先请求授权"
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            _isSyncing.value = true
            _syncMessage.value = "开始同步..."
            try {
                val success = repository.backupSessionToGoogleDrive(sessionId, token) { stepMsg ->
                    _syncMessage.value = stepMsg
                }
                if (success) {
                    Log.d(TAG, "Google Drive sync success")
                } else {
                    _syncMessage.value = "同步出错，请检查网络或授权"
                }
            } catch (e: Exception) {
                _syncMessage.value = "同步中断: ${e.message}"
            } finally {
                _isSyncing.value = false
            }
        }
    }
}
