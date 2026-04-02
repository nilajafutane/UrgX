package com.nilaja.urgx

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class TimePulseActivity : AppCompatActivity() {

    private val TAG = "UrgX"

    // Long press = 3 seconds
    private val LONG_PRESS_DURATION = 3000L

    private val handler = Handler(Looper.getMainLooper())
    private var isLongPressRunning = false

    // Runnable that fires after 3 seconds hold
    private val unlockRunnable = Runnable {
        isLongPressRunning = false
        Log.d(TAG, "Long press 3s — opening UrgX config")
        openUrgX()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Full screen — no status bar, no action bar
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        supportActionBar?.hide()

        setContentView(R.layout.activity_timepulse)

        // Start SOSService silently on launch
        SOSService.start(this)
        Log.d(TAG, "TimePulse launched — SOSService started")
    }

    // ── Long press detection ──────────────────────────────────────────
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {

            MotionEvent.ACTION_DOWN -> {
                // Finger pressed — start 3 second countdown
                isLongPressRunning = true
                handler.postDelayed(unlockRunnable, LONG_PRESS_DURATION)
                Log.d(TAG, "Touch down — long press timer started")
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                // Finger lifted before 3 seconds — cancel
                if (isLongPressRunning) {
                    handler.removeCallbacks(unlockRunnable)
                    isLongPressRunning = false
                    Log.d(TAG, "Touch released — long press cancelled")
                }
            }
        }
        return true
    }

    // ── Open real UrgX interface ──────────────────────────────────────
    private fun openUrgX() {
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
        // No animation — seamless transition
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(unlockRunnable)
    }
}