package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chat_sessions")
data class ChatSession(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val startTime: Long = System.currentTimeMillis(),
    val gdriveFolderId: String? = null,
    val isSynced: Boolean = false,
    val lastSyncTime: Long = 0L
)

@Entity(tableName = "chat_messages")
data class ChatMessage(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val sessionId: Int,
    val sender: String, // "USER" or "GEMINI"
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val localAudioPath: String? = null,
    val gdriveFileId: String? = null,
    val gdriveAudioFileId: String? = null,
    val isSynced: Boolean = false
)
