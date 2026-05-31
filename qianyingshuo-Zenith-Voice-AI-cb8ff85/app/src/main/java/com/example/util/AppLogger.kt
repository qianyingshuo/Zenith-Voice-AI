package com.example.util

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLogger {
    private const val TAG = "AppLogger"
    private const val FILE_NAME = "app_debug_logs.txt"
    private const val MAX_FILE_SIZE = 1024 * 1024 // 1 MB limit

    private var logFile: File? = null
    private var isHandlerSetup = false

    private val timeFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    /**
     * Initializes the logger with Context.
     */
    fun init(context: Context) {
        if (logFile == null) {
            val appCtx = context.applicationContext
            logFile = File(appCtx.filesDir, FILE_NAME)
            i(TAG, "LOGGER_INITIALIZED: Logs file stored at ${logFile?.absolutePath}")
            cleanupIfTooLarge()
            setupUncaughtExceptionHandler()
        }
    }

    /**
     * Ensures the log file doesn't blow up in size.
     */
    private fun cleanupIfTooLarge() {
        val file = logFile ?: return
        if (file.exists() && file.length() > MAX_FILE_SIZE) {
            Log.w(TAG, "Log file exceeds maximum size. Auto-rotating / clearing...")
            try {
                file.delete()
                file.createNewFile()
                i(TAG, "Log file reset due to file size restriction (MAX 1MB).")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to recreate log file during size rotation", e)
            }
        }
    }

    /**
     * Set up a global crash interceptor.
     */
    private fun setupUncaughtExceptionHandler() {
        if (isHandlerSetup) return
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // Mark crashes beautifully in the log
            val errorMsg = "CRITICAL METRIC: Fatal Crash detected in Application!\n" +
                    "Thread: ${thread.name} (ID: ${thread.id})\n" +
                    "Trace message: ${throwable.message}"
            e("FATAL_EXCEPTION", errorMsg, throwable)
            
            // Allow disk writer to finish
            try {
                Thread.sleep(400)
            } catch (e: InterruptedException) {
                // Ignore
            }
            // Delegate the crash back to system standard handlers so we do not mask normal behavior
            defaultHandler?.uncaughtException(thread, throwable)
        }
        isHandlerSetup = true
    }

    @Synchronized
    private fun writeToFile(level: String, tag: String, message: String, throwable: Throwable? = null) {
        val file = logFile ?: return
        try {
            if (!file.exists()) {
                file.createNewFile()
            }
            
            val timestamp = timeFormatter.format(Date())
            BufferedWriter(FileWriter(file, true)).use { writer ->
                writer.write("$timestamp [$level] $tag: $message\n")
                if (throwable != null) {
                    val sw = StringWriter()
                    val pw = PrintWriter(sw)
                    throwable.printStackTrace(pw)
                    writer.write("--- EXCEPTION STACK TRACE ---\n")
                    writer.write(sw.toString())
                    writer.write("-----------------------------\n")
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed writing logs to local storage file", e)
        }
    }

    /**
     * Debug Log
     */
    fun d(tag: String, message: String) {
        Log.d(tag, message)
        writeToFile("D", tag, message)
    }

    /**
     * Info Log
     */
    fun i(tag: String, message: String) {
        Log.i(tag, message)
        writeToFile("I", tag, message)
    }

    /**
     * Warning Log
     */
    fun w(tag: String, message: String) {
        Log.w(tag, message)
        writeToFile("W", tag, message)
    }

    /**
     * Error Log with optional Exception
     */
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        writeToFile("E", tag, message, throwable)
    }

    /**
     * Read logs from disk, returns up to [maxLines] lines.
     */
    @Synchronized
    fun readLogs(maxLines: Int = 150): List<String> {
        val file = logFile ?: return listOf("Logger not initialized.")
        if (!file.exists()) return listOf("Logging storage is empty.")

        val list = mutableListOf<String>()
        try {
            BufferedReader(FileReader(file)).use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    list.add(line)
                    line = reader.readLine()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read logs from storage", e)
            return listOf("Error loading logs: ${e.message}")
        }

        // Return the last lines only
        return if (list.size > maxLines) {
            list.subList(list.size - maxLines, list.size)
        } else {
            list
        }
    }

    /**
     * Clear all recorded log events
     */
    @Synchronized
    fun clearLogs() {
        val file = logFile ?: return
        try {
            if (file.exists()) {
                file.delete()
            }
            file.createNewFile()
            i(TAG, "Logs cleared by developer.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed resetting logger data store", e)
        }
    }

    /**
     * Obtain a URI authorized for sharing.
     */
    fun getLogFileUri(context: Context): Uri? {
        val file = logFile ?: return null
        if (!file.exists()) {
            try {
                file.createNewFile()
            } catch (e: Exception) {
                return null
            }
        }
        val authority = "${context.packageName}.fileprovider"
        return try {
            FileProvider.getUriForFile(context, authority, file)
        } catch (e: Exception) {
            Log.e(TAG, "FileProvider URI acquisition failed", e)
            null
        }
    }
}
