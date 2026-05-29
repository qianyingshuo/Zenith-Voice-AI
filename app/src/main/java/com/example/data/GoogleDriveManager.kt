package com.example.data

import android.util.Log
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GoogleDriveManager(private val okHttpClient: OkHttpClient) {

    companion object {
        private const val TAG = "GoogleDriveManager"
        private const val BACKUP_PARENT_FOLDER_NAME = "Gemini Audio Sniffer Backups"
    }

    // Standard helper to format timestamp
    private fun formatTimestamp(time: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())
        return sdf.format(Date(time))
    }

    // Check or create parent folder on Google Drive
    suspend fun getOrCreateParentFolder(accessToken: String): String? {
        val searchUrl = "https://www.googleapis.com/drive/v3/files" +
                "?q=name='$BACKUP_PARENT_FOLDER_NAME' and mimeType='application/vnd.google-apps.folder' and trashed=false" +
                "&fields=files(id)"

        val searchRequest = Request.Builder()
            .url(searchUrl)
            .addHeader("Authorization", "Bearer $accessToken")
            .get()
            .build()

        try {
            okHttpClient.newCall(searchRequest).execute().use { response ->
                if (response.isSuccessful) {
                    val responseStr = response.body?.string() ?: ""
                    val root = JSONObject(responseStr)
                    val files = root.optJSONArray("files")
                    if (files != null && files.length() > 0) {
                        return files.getJSONObject(0).getString("id")
                    }
                } else {
                    Log.e(TAG, "Search parent folder returned code ${response.code}")
                }
            }

            // Folder does not exist, create it
            val createUrl = "https://www.googleapis.com/drive/v3/files"
            val bodyJson = JSONObject().apply {
                put("name", BACKUP_PARENT_FOLDER_NAME)
                put("mimeType", "application/vnd.google-apps.folder")
            }
            val requestBody = bodyJson.toString().toRequestBody("application/json".toMediaType())

            val createRequest = Request.Builder()
                .url(createUrl)
                .addHeader("Authorization", "Bearer $accessToken")
                .post(requestBody)
                .build()

            okHttpClient.newCall(createRequest).execute().use { response ->
                if (response.isSuccessful) {
                    val responseStr = response.body?.string() ?: ""
                    val root = JSONObject(responseStr)
                    return root.getString("id")
                } else {
                    Log.e(TAG, "Create parent folder failed: Code ${response.code}, Message: ${response.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error matching or building parent directory, details: ${e.message}", e)
        }
        return null
    }

    // Check or create custom session subfolder in back-up parent
    suspend fun getOrCreateSessionFolder(accessToken: String, parentFolderId: String, session: ChatSession): String? {
        // If session already has a saved folder ID, return it
        if (!session.gdriveFolderId.isNullOrEmpty()) {
            return session.gdriveFolderId
        }

        // Search folders with name matching the session
        val folderName = "${session.title} (${formatTimestamp(session.startTime)})"
        val searchUrl = "https://www.googleapis.com/drive/v3/files" +
                "?q=name='$folderName' and mimeType='application/vnd.google-apps.folder' and '$parentFolderId' in parents and trashed=false" +
                "&fields=files(id)"

        val searchRequest = Request.Builder()
            .url(searchUrl)
            .addHeader("Authorization", "Bearer $accessToken")
            .get()
            .build()

        try {
            okHttpClient.newCall(searchRequest).execute().use { response ->
                if (response.isSuccessful) {
                    val responseStr = response.body?.string() ?: ""
                    val root = JSONObject(responseStr)
                    val files = root.optJSONArray("files")
                    if (files != null && files.length() > 0) {
                        return files.getJSONObject(0).getString("id")
                    }
                }
            }

            // Folder does not exist, create it inside BACKUP_PARENT_FOLDER
            val createUrl = "https://www.googleapis.com/drive/v3/files"
            val bodyJson = JSONObject().apply {
                put("name", folderName)
                put("mimeType", "application/vnd.google-apps.folder")
                put("parents", JSONArray().put(parentFolderId))
            }
            val requestBody = bodyJson.toString().toRequestBody("application/json".toMediaType())

            val createRequest = Request.Builder()
                .url(createUrl)
                .addHeader("Authorization", "Bearer $accessToken")
                .post(requestBody)
                .build()

            okHttpClient.newCall(createRequest).execute().use { response ->
                if (response.isSuccessful) {
                    val responseStr = response.body?.string() ?: ""
                    val root = JSONObject(responseStr)
                    return root.getString("id")
                } else {
                    Log.e(TAG, "Create session folder failed: Code ${response.code}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error identifying or creating session subfolders, details: ${e.message}", e)
        }
        return null
    }

    // Upload/Update text log file (Automatic Version Control)
    suspend fun uploadOrUpdateTextHistory(
        accessToken: String,
        sessionFolderId: String,
        historyContent: String,
        existingFileId: String? = null
    ): String? {
        val filename = "chat_history_log.txt"

        try {
            var fileId = existingFileId

            // If no existing file ID is provided, query Google Drive inside this folder
            if (fileId.isNullOrEmpty()) {
                val queryUrl = "https://www.googleapis.com/drive/v3/files" +
                        "?q=name='$filename' and '$sessionFolderId' in parents and trashed=false" +
                        "&fields=files(id)"
                val queryRequest = Request.Builder()
                    .url(queryUrl)
                    .addHeader("Authorization", "Bearer $accessToken")
                    .get()
                    .build()

                okHttpClient.newCall(queryRequest).execute().use { response ->
                    if (response.isSuccessful) {
                        val responseStr = response.body?.string() ?: ""
                        val files = JSONObject(responseStr).optJSONArray("files")
                        if (files != null && files.length() > 0) {
                            fileId = files.getJSONObject(0).getString("id")
                        }
                    }
                }
            }

            val textBytes = historyContent.toByteArray(Charsets.UTF_8)

            if (!fileId.isNullOrEmpty()) {
                // UPDATE (overwrite file content - Version Control)
                val updateUrl = "https://www.googleapis.com/upload/drive/v3/files/$fileId?uploadType=media"
                val requestBody = textBytes.toRequestBody("text/plain".toMediaType())

                val request = Request.Builder()
                    .url(updateUrl)
                    .addHeader("Authorization", "Bearer $accessToken")
                    .patch(requestBody)
                    .build()

                okHttpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Log.i(TAG, "Synced text history updated successfully. FileID: $fileId")
                        return fileId
                    } else {
                        Log.e(TAG, "Sync update history failed with code ${response.code}")
                    }
                }
            } else {
                // CREATE new log file (Multipart file creation)
                val metadataJson = JSONObject().apply {
                    put("name", filename)
                    put("parents", JSONArray().put(sessionFolderId))
                    put("mimeType", "text/plain")
                }

                val multipartBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addPart(
                        Headers.headersOf("Content-Type", "application/json; charset=UTF-8"),
                        metadataJson.toString().toRequestBody("application/json; charset=UTF-8".toMediaType())
                    )
                    .addPart(
                        Headers.headersOf("Content-Type", "text/plain"),
                        textBytes.toRequestBody("text/plain".toMediaType())
                    )
                    .build()

                val request = Request.Builder()
                    .url("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart")
                    .addHeader("Authorization", "Bearer $accessToken")
                    .post(multipartBody)
                    .build()

                okHttpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val responseStr = response.body?.string() ?: ""
                        val root = JSONObject(responseStr)
                        val newId = root.getString("id")
                        Log.i(TAG, "Synced text history created. FileID: $newId")
                        return newId
                    } else {
                        Log.e(TAG, "Sync file create failed with code ${response.code}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during backup sync, info: ${e.message}", e)
        }
        return null
    }

    // Upload audio recording file (binary content)
    suspend fun uploadBinaryAudio(
        accessToken: String,
        sessionFolderId: String,
        audioFile: File,
        messageId: Int
    ): String? {
        if (!audioFile.exists() || audioFile.length() == 0L) {
            Log.e(TAG, "Audio file does not exist or empty: ${audioFile.absolutePath}")
            return null
        }

        val filename = "speech_msg_$messageId.mp3"
        val fileBytes = audioFile.readBytes()

        val metadataJson = JSONObject().apply {
            put("name", filename)
            put("parents", JSONArray().put(sessionFolderId))
            put("mimeType", "audio/mpeg")
        }

        val multipartBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addPart(
                Headers.headersOf("Content-Type", "application/json; charset=UTF-8"),
                metadataJson.toString().toRequestBody("application/json; charset=UTF-8".toMediaType())
            )
            .addPart(
                Headers.headersOf("Content-Type", "audio/mpeg"),
                fileBytes.toRequestBody("audio/mpeg".toMediaType())
            )
            .build()

        val request = Request.Builder()
            .url("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart")
            .addHeader("Authorization", "Bearer $accessToken")
            .post(multipartBody)
            .build()

        try {
            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val responseStr = response.body?.string() ?: ""
                    val root = JSONObject(responseStr)
                    return root.getString("id")
                } else {
                    Log.e(TAG, "Upload audio failed with code ${response.code}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during audio binary sync, info: ${e.message}", e)
        }
        return null
    }
}
