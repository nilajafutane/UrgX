package com.nilaja.urgx

import android.content.ContentValues.TAG
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

class VoiceKeywordListener(
    private val context: Context,
    private val onKeywordDetected: () -> Unit
) {

    private var speechRecognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isListening = false
    private var isDestroyed = false

    // Keywords that trigger SOS — any of these will fire
    private val TRIGGER_WORDS = listOf("help", "bachao", "emergency", "danger")

    fun start() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w("UrgX", "Speech recognition not available on this device")
            return
        }
        isDestroyed = false
        Log.d("UrgX", "Voice listener starting...")
        startListening()
    }

    private fun startListening() {
        if (isDestroyed) return

        handler.post {
            try {
                speechRecognizer?.destroy()
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
                speechRecognizer?.setRecognitionListener(object : RecognitionListener {

                    override fun onReadyForSpeech(params: Bundle?) {
                        isListening = true
                        Log.d("UrgX", "Voice: ready for speech")
                    }

                    override fun onResults(results: Bundle?) {
                        isListening = false
                        val matches = results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?: return

                        Log.d("UrgX", "Voice heard: $matches")

                        // Check if any result contains a trigger word
                        val triggered = matches.any { result ->
                            TRIGGER_WORDS.any { keyword ->
                                result.lowercase().contains(keyword)
                            }
                        }

                        if (triggered) {
                            Log.d("UrgX", "VOICE TRIGGER DETECTED")
                            onKeywordDetected()
                        } else {
                            // Keep listening
                            restartAfterDelay()
                        }
                    }

                    override fun onError(error: Int) {
                        isListening = false
                        val msg = when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH -> "no match"
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "timeout"
                            SpeechRecognizer.ERROR_AUDIO -> "audio error"
                            SpeechRecognizer.ERROR_NETWORK -> "network error"
                            else -> "error code $error"
                        }
                        Log.d("UrgX", "Voice error: $msg — restarting")
                        restartAfterDelay()
                    }

                    override fun onPartialResults(partial: Bundle?) {
                        val partialMatches = partial
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?: return

                        Log.d(TAG, "Voice partial: $partialMatches")

                        val triggered = partialMatches.any { result ->
                            TRIGGER_WORDS.any { keyword ->
                                result.lowercase().contains(keyword)
                            }
                        }

                        if (triggered) {
                            Log.d(TAG, "VOICE TRIGGER on partial!")
                            // Stop listening first then fire
                            speechRecognizer?.stopListening()
                            onKeywordDetected()
                        }
                    }

                    // Required overrides — not needed
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                    )
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)

                    // ✅ Silence ke baad turant process kare — was 3000ms
                    putExtra(
                        RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                        1000      // 1 second silence = done listening
                    )
                    putExtra(
                        RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                        800
                    )
                    // ✅ Minimum speech length — short words like "Help" bhi capture ho
                    putExtra(
                        RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                        500
                    )
                }

                speechRecognizer?.startListening(intent)

            } catch (e: Exception) {
                Log.e("UrgX", "Voice start error: ${e.message}")
                restartAfterDelay()
            }
        }
    }

    private fun restartAfterDelay() {
        if (isDestroyed) return
        // Restart listening after 1 second gap
        handler.postDelayed({ startListening() }, 1000L)
    }

    fun stop() {
        isDestroyed = true
        isListening = false
        handler.removeCallbacksAndMessages(null)
        handler.post {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
            speechRecognizer = null
            Log.d("UrgX", "Voice listener stopped")
        }
    }
}