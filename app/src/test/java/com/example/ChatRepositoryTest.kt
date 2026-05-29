package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.ChatDao
import com.example.data.ChatMessage
import com.example.data.ChatRepository
import com.example.data.ChatSession
import com.example.tts.TtsEngine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ChatRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: ChatDao
    private lateinit var repository: ChatRepository

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.chatDao()
        repository = ChatRepository(context, dao)
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun writeAndReadSession() = runBlocking {
        val testTitle = "测试专属会话组"
        val sessionId = repository.createNewSession(testTitle)
        assertTrue(sessionId > 0)

        val retrievedSession = repository.getSessionById(sessionId)
        assertNotNull(retrievedSession)
        assertEquals(testTitle, retrievedSession?.title)
    }

    @Test
    fun deleteSessionRemovesAssociatedMessages() = runBlocking {
        val sessionId = repository.createNewSession("待删除会话")
        
        // Add user sniffed message
        repository.addMessageAndGenerateTts(
            sessionId = sessionId,
            sender = "USER",
            text = "今天天气如何呀？",
            engineType = TtsEngine.EngineType.GEMINI,
            apiKey = "mock_key",
            voiceParam = "Kore",
            voiceProfile = "Kore"
        ) { _ -> }

        // Fetch to confirm insertion
        val initialMessages = repository.getMessagesForSession(sessionId).first()
        assertEquals(1, initialMessages.size)

        // Delete the session
        repository.deleteSession(sessionId)

        // Verify session no longer exists and messages are deleted
        val deletedSession = repository.getSessionById(sessionId)
        assertEquals(null, deletedSession)

        val remainingMessages = repository.getMessagesForSession(sessionId).first()
        assertTrue(remainingMessages.isEmpty())
    }
}
