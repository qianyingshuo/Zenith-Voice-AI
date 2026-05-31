package com.example.service

import android.app.Notification
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.example.util.AppLogger

class GeminiNotificationListener : NotificationListenerService() {

    override fun onCreate() {
        super.onCreate()
        AppLogger.init(this)
        AppLogger.i(TAG, "GeminiNotificationListener: Notification Listener Service Created.")
    }

    companion object {
        private const val TAG = "GeminiNotifListener"
        private const val PACKAGE_BARD = "com.google.android.apps.bard"
        private const val PACKAGE_GOOGLE = "com.google.android.apps.google"
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val packageName = sbn?.packageName ?: return
        if (packageName != PACKAGE_BARD && packageName != PACKAGE_GOOGLE) return

        val extras = sbn.notification?.extras ?: return
        val rawText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: ""

        if (rawText.isNotEmpty()) {
            // If the notification is from the generic Google App, ensure it's related to Gemini
            if (packageName == PACKAGE_GOOGLE) {
                val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.lowercase() ?: ""
                val subtext = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.lowercase() ?: ""
                val textLower = rawText.lowercase()
                
                val isGeminiRelated = title.contains("gemini") || 
                                     subtext.contains("gemini") || 
                                     textLower.contains("gemini")
                                     
                if (!isGeminiRelated) return
            }

            AppLogger.d(TAG, "从系统通知栏成功截获推送: $rawText")

            val playIntent = Intent(this, TtsPlaybackService::class.java).apply {
                action = TtsPlaybackService.ACTION_PLAY_SPEECH
                putExtra(TtsPlaybackService.EXTRA_SPEECH_TEXT, rawText)
                putExtra(TtsPlaybackService.EXTRA_IS_USER, false)
            }
            try {
                startService(playIntent)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed starting TtsPlaybackService via startService", e)
            }
        }
    }
}
