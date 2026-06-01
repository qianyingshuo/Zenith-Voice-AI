package com.example.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.tts.TtsEngine
import com.example.util.AppLogger
import java.security.MessageDigest
import java.util.LinkedList

class GeminiTextSnifferService : AccessibilityService() {

    override fun onCreate() {
        super.onCreate()
        AppLogger.init(this)
        AppLogger.i(TAG, "GeminiTextSnifferService: Accessibility Service Created.")
    }

    companion object {
        private const val TAG = "GeminiTextSniffer"
        private const val PACKAGE_BARD = "com.google.android.apps.bard"
        private const val PACKAGE_GOOGLE = "com.google.android.apps.google"
        private const val STREAM_DEBOUNCE_DELAY_MS = 1600L
        private const val MAX_QUEUE_SIZE = 5
        var isServiceConnected = false
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var periodicDetectionRunnable: Runnable? = null
    private val deduplicationQueue = LinkedList<String>()

    private var lastCapturedUserText = ""
    private var lastCapturedGeminiText = ""
    private var isGenerating = false

    private val debounceRunnable = Runnable {
        dispatchMessage(lastCapturedGeminiText, isUser = false)
    }

    override fun onServiceConnected() {
        isServiceConnected = true
        AppLogger.i(TAG, "辅助功能拦截监听器成功绑定并启动")
        startPeriodicDetection()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: ""
        if (packageName != PACKAGE_BARD && packageName != PACKAGE_GOOGLE) return

        val rootNode = rootInActiveWindow ?: return

        // 1. Detect if Gemini is currently generating text by checking send button status
        val isSendEnabled = checkSendButtonStatus(rootNode)
        if (!isSendEnabled) {
            isGenerating = true
        }

        // 2. Scan UI to extract user prompts and Gemini answers
        var capturedUserText = ""
        var capturedGeminiText = ""

        fun traverse(node: AccessibilityNodeInfo?) {
            if (node == null) return
            try {
                val viewId = node.viewIdResourceName ?: ""
                val text = node.text?.toString()?.trim() ?: ""

                // Target user text elements
                if (viewId.contains("user_query") || viewId.contains("chat_message_text_user") || viewId.contains("query_text")) {
                    if (text.isNotEmpty()) {
                        capturedUserText = text
                    }
                }

                // Target Gemini output container elements
                if (viewId.contains("response_body") || viewId.contains("text_bubble") || viewId.contains("chat_message_text") || viewId.contains("card_text")) {
                    if (text.isNotEmpty()) {
                        capturedGeminiText = text
                    }
                }

                // Fallback heuristics: If we find a long text that changes and is inside a ScrollView, we categorize it
                if (capturedGeminiText.isEmpty() && text.length > 5 && (node.className?.contains("TextView") == true)) {
                    if (viewId.contains("body") || viewId.contains("message") || viewId.contains("response")) {
                        capturedGeminiText = text
                    }
                }

                for (i in 0 until node.childCount) {
                    val child = node.getChild(i)
                    if (child != null) {
                        traverse(child)
                        try {
                            child.recycle()
                        } catch (re: Exception) {
                            // Suppress recycle faults
                        }
                    }
                }
            } catch (e: Exception) {
                // Prevent traversal crash
            }
        }

        try {
            traverse(rootNode)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Traversing window nodes failed", e)
        } finally {
            try {
                rootNode.recycle()
            } catch (re: Exception) {
                // Suppress recycle faults
            }
        }

        // 3. Process captured user query
        if (capturedUserText.isNotEmpty() && capturedUserText != lastCapturedUserText) {
            lastCapturedUserText = capturedUserText
            dispatchMessage(capturedUserText, isUser = true)
        }

        // 4. Process captured streaming response (Heuristic stream debouncer)
        if (capturedGeminiText.isNotEmpty() && capturedGeminiText != lastCapturedGeminiText) {
            lastCapturedGeminiText = capturedGeminiText

            // Reset sliding window
            mainHandler.removeCallbacks(debounceRunnable)

            // If we detect generation completed (Send button restored), dispatch immediately
            if (isGenerating && isSendEnabled) {
                isGenerating = false
                dispatchMessage(lastCapturedGeminiText, isUser = false)
            } else {
                // Otherwise delay by 1.6s to debounce the current stream chunk
                mainHandler.postDelayed(debounceRunnable, STREAM_DEBOUNCE_DELAY_MS)
            }
        }
    }

    private fun checkSendButtonStatus(rootNode: AccessibilityNodeInfo): Boolean {
        val rootPkg = rootNode.packageName?.toString() ?: "com.google.android.apps.bard"
        val sendButtons = rootNode.findAccessibilityNodeInfosByViewId("$rootPkg:id/send_button")
        if (sendButtons.isNotEmpty()) {
            val isEnabled = sendButtons[0].isEnabled
            sendButtons.forEach { it.recycle() }
            return isEnabled
        }
        
        // Also look for descriptions or text like "send" or "stop"
        var isSendActive = true
        fun searchSendNode(node: AccessibilityNodeInfo?) {
            if (node == null) return
            try {
                val desc = node.contentDescription?.toString()?.lowercase() ?: ""
                val text = node.text?.toString()?.lowercase() ?: ""
                if (desc.contains("send") || desc.contains("stop") || text.contains("send") || text.contains("stop")) {
                    if (!node.isEnabled) {
                        isSendActive = false
                    }
                }
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i)
                    if (child != null) {
                        searchSendNode(child)
                        try {
                            child.recycle()
                        } catch (re: Exception) {
                            // Suppress recycle faults
                        }
                    }
                }
            } catch (e: Exception) {
                // Prevent node search crash
            }
        }
        try {
            searchSendNode(rootNode)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Search send node failed", e)
        }
        return isSendActive
    }

    private fun dispatchMessage(text: String, isUser: Boolean) {
        if (text.isEmpty()) return

        val textMd5 = calculateMd5(text)

        // Protect from multiple updates / duplicates
        synchronized(deduplicationQueue) {
            if (deduplicationQueue.contains(textMd5)) {
                return
            }
            if (deduplicationQueue.size >= MAX_QUEUE_SIZE) {
                deduplicationQueue.removeFirst()
            }
            deduplicationQueue.addLast(textMd5)
        }

        AppLogger.d(TAG, "Sniffed Message [isUser=$isUser]: $text")

        // Store active sniffing session configuration to preferences
        val prefs = getSharedPreferences("secure_credentials", Context.MODE_PRIVATE)
        val serviceActive = prefs.getBoolean("sniffer_service_enabled", true)
        if (!serviceActive) {
            AppLogger.d(TAG, "Sniffer disabled in preferences. Skipping.")
            return
        }

        // Delegate content playout & pipeline storage to the Playback Service
        val playIntent = Intent(this, TtsPlaybackService::class.java).apply {
            action = TtsPlaybackService.ACTION_PLAY_SPEECH
            putExtra(TtsPlaybackService.EXTRA_SPEECH_TEXT, text)
            putExtra(TtsPlaybackService.EXTRA_IS_USER, isUser)
        }
        try {
            androidx.core.content.ContextCompat.startForegroundService(this, playIntent)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed starting TtsPlaybackService via startForegroundService", e)
        }
    }

    private fun calculateMd5(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    override fun onInterrupt() {
        mainHandler.removeCallbacks(debounceRunnable)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        isServiceConnected = false
        stopPeriodicDetection()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        isServiceConnected = false
        stopPeriodicDetection()
        mainHandler.removeCallbacks(debounceRunnable)
        super.onDestroy()
    }

    private fun startPeriodicDetection() {
        periodicDetectionRunnable?.let { mainHandler.removeCallbacks(it) }
        
        periodicDetectionRunnable = object : Runnable {
            override fun run() {
                if (!isServiceConnected) return
                try {
                    performActiveDetection()
                } catch (e: Exception) {
                    Log.e(TAG, "5秒频率定时检测执行异常", e)
                }
                if (isServiceConnected) {
                    mainHandler.postDelayed(this, 5000L)
                }
            }
        }
        mainHandler.postDelayed(periodicDetectionRunnable!!, 5000L)
        AppLogger.i(TAG, "【极速高刷侦测】5秒频率定时检测轮询已启动")
    }

    private fun stopPeriodicDetection() {
        periodicDetectionRunnable?.let {
            mainHandler.removeCallbacks(it)
            periodicDetectionRunnable = null
        }
        AppLogger.i(TAG, "【极速高刷侦测】5秒频率定时检测轮询已停止")
    }

    private fun performActiveDetection() {
        val rootNode = rootInActiveWindow
        if (rootNode == null) {
            Log.i(TAG, "【探测周期 [5s]】当前处于桌面、锁屏或受安全保护界面（无法获取活跃窗口 rootInActiveWindow 为空）")
            return
        }
        
        val currentPkg = rootNode.packageName?.toString() ?: ""
        val isGeminiApp = (currentPkg == PACKAGE_BARD || currentPkg == PACKAGE_GOOGLE)
        
        if (isGeminiApp) {
            val hasTargetNodes = scanControlsPresent(rootNode)
            if (hasTargetNodes) {
                AppLogger.i(TAG, "【探测周期 [5s]】检测到前台 Gemini 应用活跃 [$currentPkg]，且对话关键控件就绪 (处于捕获范围)")
            } else {
                AppLogger.w(TAG, "【探测周期 [5s]】检测到前台 Gemini 应用活跃 [$currentPkg]，但「尚未找到」聊天气泡或关键元素 (未在对话详情页)")
            }
        } else {
            Log.i(TAG, "【探测周期 [5s]】未检测到前台 Gemini 应用。当前前台活跃包名: '$currentPkg'")
        }
        
        try {
            rootNode.recycle()
        } catch (e: Exception) {
            // Suppress recycle faults
        }
    }

    private fun scanControlsPresent(rootNode: AccessibilityNodeInfo): Boolean {
        var foundControls = false
        fun search(node: AccessibilityNodeInfo?) {
            if (node == null || foundControls) return
            try {
                val viewId = node.viewIdResourceName ?: ""
                if (viewId.contains("user_query") || viewId.contains("chat_message_text_user") || 
                    viewId.contains("query_text") || viewId.contains("response_body") || 
                    viewId.contains("text_bubble") || viewId.contains("chat_message_text") || 
                    viewId.contains("card_text")) {
                    foundControls = true
                    return
                }
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i)
                    if (child != null) {
                        search(child)
                        try { child.recycle() } catch (re: Exception) {}
                    }
                }
            } catch (e: Exception) {}
        }
        search(rootNode)
        return foundControls
    }
}
