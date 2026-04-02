package com.nilaja.urgx

import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

class CloudinaryUploader {

    // ── Paste your Cloudinary credentials here ────────────────────────
    private val CLOUD_NAME = "dz6s4vfcy"   // ← replace karo
    private val API_KEY    = "348959835444416"       // ← replace karo
    private val API_SECRET = "NN-_U40QYAYo7EHwPnnn0aOnfFE"    // ← replace karo

    private val client = OkHttpClient()

    fun uploadAudio(
        audioFile: File,
        onSuccess: (downloadUrl: String) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        if (!audioFile.exists()) {
            onFailure(Exception("Audio file not found"))
            return
        }

        try {
            val timestamp = (System.currentTimeMillis() / 1000).toString()
            val signature = generateSignature(timestamp)

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    audioFile.name,
                    audioFile.asRequestBody("audio/3gpp".toMediaType())
                )
                .addFormDataPart("api_key", API_KEY)
                .addFormDataPart("timestamp", timestamp)
                .addFormDataPart("signature", signature)
                .addFormDataPart("folder", "sos_audio")
                .addFormDataPart("resource_type", "video") // Cloudinary uses "video" for audio
                .build()

            val request = Request.Builder()
                .url("https://api.cloudinary.com/v1_1/$CLOUD_NAME/video/upload")
                .post(requestBody)
                .build()

            Log.d("UrgX", "Uploading audio to Cloudinary...")

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e("UrgX", "Cloudinary upload failed: ${e.message}")
                    onFailure(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val body = response.body?.string()
                    if (response.isSuccessful && body != null) {
                        val json = JSONObject(body)
                        val url = json.getString("secure_url")
                        Log.d("UrgX", "Upload success! URL: $url")
                        onSuccess(url)
                    } else {
                        Log.e("UrgX", "Upload error: $body")
                        onFailure(Exception("Upload failed: $body"))
                    }
                }
            })

        } catch (e: Exception) {
            Log.e("UrgX", "Upload exception: ${e.message}")
            onFailure(e)
        }
    }

    private fun generateSignature(timestamp: String): String {
        val data = "folder=sos_audio&timestamp=$timestamp$API_SECRET"
        val digest = MessageDigest.getInstance("SHA-1")
        val hash = digest.digest(data.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }
}