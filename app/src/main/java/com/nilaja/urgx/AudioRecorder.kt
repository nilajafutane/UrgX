package com.nilaja.urgx

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File

class AudioRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private val handler = Handler(Looper.getMainLooper())
    private val RECORD_DURATION = 30000L  // 30 seconds

    // ── Start silent recording ─────────────────────────────────────────
    fun startRecording(onComplete: (File?) -> Unit) {
        try {
            // Create output file in app's private storage
            outputFile = File(context.filesDir, "sos_audio_${System.currentTimeMillis()}.3gp")

            recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            recorder?.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                setOutputFile(outputFile!!.absolutePath)
                setMaxDuration(RECORD_DURATION.toInt())

                prepare()
                start()
                Log.d("UrgX", "Recording started: ${outputFile!!.absolutePath}")
            }

            // Auto-stop after 30 seconds
            handler.postDelayed({
                stopRecording { file ->
                    onComplete(file)
                }
            }, RECORD_DURATION)

        } catch (e: Exception) {
            Log.e("UrgX", "Recording failed: ${e.message}")
            onComplete(null)
        }
    }

    // ── Stop and return the file ───────────────────────────────────────
    fun stopRecording(onComplete: (File?) -> Unit) {
        try {
            recorder?.apply {
                stop()
                release()
            }
            recorder = null
            Log.d("UrgX", "Recording stopped. File: ${outputFile?.absolutePath}")
            onComplete(outputFile)
        } catch (e: Exception) {
            Log.e("UrgX", "Stop recording failed: ${e.message}")
            recorder = null
            onComplete(null)
        }
    }

    fun isRecording() = recorder != null

    fun cleanup() {
        handler.removeCallbacksAndMessages(null)
        recorder?.release()
        recorder = null
    }
}