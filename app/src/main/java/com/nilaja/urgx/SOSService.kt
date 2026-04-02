package com.nilaja.urgx

import android.app.*
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.media.AudioManager
import android.os.*
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import kotlin.math.sqrt

class SOSService : Service(), SensorEventListener {

    // ── Sensor ────────────────────────────────────────────────────────
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var vibrator: Vibrator? = null

    // ── Shake detection ───────────────────────────────────────────────
    private var shakeCount       = 0
    private var lastShakeTime    = 0L
    var SHAKE_THRESHOLD_VAR      = 10f      // changed by Settings
    private val SHAKE_WINDOW     = 2000L

    // ── Two-step trigger state ────────────────────────────────────────
    private var isArmed          = false
    private var armStartTime     = 0L
    var ARM_TIMEOUT_VAR          = 10_000L  // changed by Settings

    // ── Volume (Fix 3 — ContentObserver) ──────────────────────────────
    private lateinit var audioManager:    AudioManager
    private lateinit var volumeObserver:  ContentObserver
    private var previousVolume           = -1
    private var volPressCount            = 0
    private var firstVolPressTime        = 0L

    // ── GPS warmup (Fix 2) ────────────────────────────────────────────
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback:    LocationCallback
    var lastKnownLocation: Location?         = null   // public — AlertManager uses this

    // ── Voice ─────────────────────────────────────────────────────────
    private var voiceEnabled       = true
    private var voiceKeywordListener: VoiceKeywordListener? = null

    // ── Alert + audio ─────────────────────────────────────────────────
    private lateinit var alertManager:  AlertManager
    private lateinit var audioRecorder: AudioRecorder

    // ── SOS state ─────────────────────────────────────────────────────
    private var sosActive          = false
    private var cancelWindowOpen   = false
    private val cancelHandler      = Handler(Looper.getMainLooper())
    private val cooldownHandler    = Handler(Looper.getMainLooper())

    // ══════════════════════════════════════════════════════════════════
    companion object {
        const val CHANNEL_ID = "UrgXChannel"
        const val NOTIF_ID   = 1
        private const val TAG = "UrgX"

        var instance: SOSService? = null

        fun start(context: Context) {
            val i = Intent(context, SOSService::class.java)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            val i = Intent(context, SOSService::class.java)
            context.stopService(i)
        }

        fun updateSettings(threshold: Float, armTimeout: Long, voiceOn: Boolean) {
            instance?.apply {
                SHAKE_THRESHOLD_VAR = threshold
                ARM_TIMEOUT_VAR     = armTimeout
                voiceEnabled        = voiceOn
                if (voiceOn) voiceKeywordListener?.start()
                else         voiceKeywordListener?.stop()
            }
        }
        fun fireSafeRouteSOS(reason: String) {
            instance?.let {
                if (!it.sosActive) {
                    Log.d(TAG, "Safe route auto SOS: $reason")
                    it.fireSOS("SAFE ROUTE — $reason")
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    override fun onCreate() {
        super.onCreate()
        instance = this

        // Foreground notification (disguised as clock)
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())

        // Vibrator
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }

        // Fix 1 — SENSOR_DELAY_GAME (20ms, 3x faster than SENSOR_DELAY_UI)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        accelerometer?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }

        // Fix 2 — GPS warmup
        startLocationWarmup()

        // Fix 3 — ContentObserver for volume (faster on OPPO)
        registerVolumeObserver()

        // Alert manager and recorder
        alertManager  = AlertManager(this)
        audioRecorder = AudioRecorder(this)

        // Voice keyword listener
        voiceKeywordListener = VoiceKeywordListener(this) {
            Log.d(TAG, "VOICE TRIGGER detected")
            fireSOS("VOICE")
        }
        if (voiceEnabled) voiceKeywordListener?.start()

        Log.d(TAG, "SOSService started")
    }

    // ══════════════════════════════════════════════════════════════════
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    // ══════════════════════════════════════════════════════════════════
    // FIX 2 — GPS WARMUP
    // ══════════════════════════════════════════════════════════════════
    private fun startLocationWarmup() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            10_000L                          // update every 10 seconds
        )
            .setMinUpdateIntervalMillis(5_000L)
            .setWaitForAccurateLocation(false)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { loc ->
                    lastKnownLocation = loc
                    Log.d(TAG, "GPS warm: ${loc.latitude}, ${loc.longitude} acc=${loc.accuracy}m")
                }
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(
                request,
                locationCallback,
                Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission not granted: ${e.message}")
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // FIX 3 — VOLUME CONTENTOBSERVER (replaces BroadcastReceiver)
    // ══════════════════════════════════════════════════════════════════
    private fun registerVolumeObserver() {
        audioManager   = getSystemService(AUDIO_SERVICE) as AudioManager
        previousVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

        volumeObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {

            // Track both streams
            private var prevMusic = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            private var prevRing  = audioManager.getStreamVolume(AudioManager.STREAM_RING)
            private var lastDetectTime = 0L

            override fun onChange(selfChange: Boolean) {
                val nowMusic = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                val nowRing  = audioManager.getStreamVolume(AudioManager.STREAM_RING)
                val now      = System.currentTimeMillis()

                // Debounce — ignore duplicate events within 300ms
                if (now - lastDetectTime < 300) {
                    prevMusic = nowMusic
                    prevRing  = nowRing
                    return
                }

                val musicDown = nowMusic < prevMusic
                val ringDown  = nowRing  < prevRing

                if (musicDown || ringDown) {
                    lastDetectTime = now
                    Log.d(TAG, "Volume DOWN detected (music=$musicDown ring=$ringDown)")
                    onVolumeDownDetected()
                }

                prevMusic = nowMusic
                prevRing  = nowRing
            }
        }

        contentResolver.registerContentObserver(
            Settings.System.CONTENT_URI,
            true,
            volumeObserver
        )
        Log.d(TAG, "VolumeObserver registered — watching MUSIC + RING")
    }

    private fun onVolumeDownDetected() {
        if (!isArmed) {
            Log.d(TAG, "Volume down — but NOT armed, ignoring")
            return
        }

        val now = System.currentTimeMillis()

        // Check arm timeout
        if (now - armStartTime > ARM_TIMEOUT_VAR) {
            Log.d(TAG, "Arm timeout expired — resetting")
            resetArmedState()
            return
        }

        volPressCount++
        Log.d(TAG, "Volume down #$volPressCount while ARMED")

        if (volPressCount == 1) {
            firstVolPressTime = now
        }

        if (volPressCount >= 2) {
            if (now - firstVolPressTime <= 5000L) {
                Log.d(TAG, "SOS FIRED via: SHAKE + VOLUME")
                fireSOS("SHAKE + VOLUME")
            } else {
                // Two presses but too slow — reset count, try again
                volPressCount     = 1
                firstVolPressTime = now
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // ACCELEROMETER — SHAKE DETECTION (Fix 1 already applied above)
    // ══════════════════════════════════════════════════════════════════
    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_ACCELEROMETER) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val force = sqrt((x*x + y*y + z*z).toDouble()).toFloat() - SensorManager.GRAVITY_EARTH

        // ✅ now pehle declare karo
        val now = System.currentTimeMillis()

        if (force > SHAKE_THRESHOLD_VAR && (now - lastShakeTime) > 400) {

            // Cancel window check
            if (cancelWindowOpen && sosActive) {
                if (force < 18f) {
                    onSOSCancelled()
                    return
                }
            }

            // Normal shake counting
            if (now - lastShakeTime > SHAKE_WINDOW) {
                shakeCount = 0
            }
            shakeCount++
            lastShakeTime = now

            Log.d(TAG, "Shake #$shakeCount force=$force")

            if (shakeCount >= 3) {
                shakeCount = 0
                if (!isArmed && !sosActive) {
                    armSOS()
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ══════════════════════════════════════════════════════════════════
    // ARM STATE
    // ══════════════════════════════════════════════════════════════════
    private fun armSOS() {
        isArmed       = true
        armStartTime  = System.currentTimeMillis()
        volPressCount = 0

        // 1 short buzz — armed signal
        vibrateShort()
        Log.d(TAG, "ARMED — waiting for 2x volume down within ${ARM_TIMEOUT_VAR/1000}s")

        // Auto-disarm if no volume press in time
        Handler(Looper.getMainLooper()).postDelayed({
            if (isArmed && !sosActive) {
                Log.d(TAG, "Arm timeout — returning to IDLE")
                resetArmedState()
            }
        }, ARM_TIMEOUT_VAR)
    }

    private fun resetArmedState() {
        isArmed       = false
        volPressCount = 0
    }

    // ══════════════════════════════════════════════════════════════════
    // FIRE SOS
    // ══════════════════════════════════════════════════════════════════
    internal fun fireSOS(trigger: String) {
        if (sosActive) return  // prevent double fire

        sosActive        = true
        cancelWindowOpen = true
        isArmed          = false

        Log.d(TAG, "SOS FIRED via: $trigger")
        // Long-short-short vibration (secret pattern)
        vibrateSOSPattern()
        // Send alert (AlertManager handles GPS + SMS + audio)
        alertManager.sendSOS()

        audioRecorder.startRecording { it ->
            if (it != null) {
                Log.d(TAG, "Recording done: ${it.name} (${it.length()} bytes)")
                val uploader = CloudinaryUploader()
                uploader.uploadAudio(
                    audioFile = it,
                    onSuccess = { url ->
                        Log.d(TAG, "Cloudinary upload done: $url")
                        alertManager.sendAudioLinkToAllContacts(url)
                    },
                    onFailure = { errorMsg ->
                        Log.e(TAG, "Cloudinary upload failed: $errorMsg")
                    }
                )
            } else {
                Log.w(TAG, "Recording failed — no audio file")
            }
        }

        // Open 10-second cancel window
        cancelHandler.postDelayed({
            cancelWindowOpen = false
            Log.d(TAG, "Cancel window closed")

            // Cooldown 30s before next SOS can fire
            cooldownHandler.postDelayed({
                sosActive = false
                Log.d(TAG, "Cooldown ended — READY")
            }, 30_000L)

        }, 10_000L)
    }

    // ══════════════════════════════════════════════════════════════════
    // CANCEL (slow shake within 10s cancel window)
    // ══════════════════════════════════════════════════════════════════
    private fun onSOSCancelled() {
        cancelWindowOpen = false
        cancelHandler.removeCallbacksAndMessages(null)

        Log.d(TAG, "SOS CANCELLED — sending false alarm SMS")
        alertManager.sendFalseAlarmSMS()

        // 1 short buzz — cancelled signal
        vibrateShort()

        // Reset after cooldown
        cooldownHandler.postDelayed({
            sosActive = false
            Log.d(TAG, "Cooldown ended — READY")
        }, 30_000L)
    }

    // ══════════════════════════════════════════════════════════════════
    // VIBRATION PATTERNS
    // ══════════════════════════════════════════════════════════════════
    private fun vibrateSOSPattern() {
        // Long – short – short
        val pattern = longArrayOf(0, 600, 200, 200, 200, 200)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(pattern, -1)
        }
    }

    private fun vibrateShort() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(150)
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // NOTIFICATION (disguised as clock)
    // ══════════════════════════════════════════════════════════════════
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Clock Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Background clock sync" }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TimePulse")
            .setContentText("Clock sync active")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
    }

    // ══════════════════════════════════════════════════════════════════
    // CLEANUP
    // ══════════════════════════════════════════════════════════════════
    override fun onDestroy() {
        super.onDestroy()
        instance = null

        sensorManager.unregisterListener(this)

        // Fix 2 cleanup
        if (::fusedLocationClient.isInitialized) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }

        // Fix 3 cleanup
        if (::volumeObserver.isInitialized) {
            contentResolver.unregisterContentObserver(volumeObserver)
        }

        voiceKeywordListener?.stop()
        cancelHandler.removeCallbacksAndMessages(null)
        cooldownHandler.removeCallbacksAndMessages(null)

        Log.d(TAG, "SOSService destroyed")
    }

    override fun onBind(intent: Intent?) = null
}