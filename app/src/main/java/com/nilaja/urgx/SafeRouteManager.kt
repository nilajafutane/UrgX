package com.nilaja.urgx

import android.content.Context
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.*

class SafeRouteManager(
    private val context: Context,
    private val onSOSTrigger: (String) -> Unit  // reason string
) {

    private val TAG = "UrgX"

    // ── State ─────────────────────────────────────────────────────────
    var isActive           = false
        private set
    private var destLat    = 0.0
    private var destLng    = 0.0
    private var destName   = ""
    private var startLat   = 0.0
    private var startLng   = 0.0

    // Thresholds
    private val STOP_TIMEOUT      = 5 * 60 * 1000L   // 5 min no movement → SOS
    private val DEVIATION_LIMIT   = 500f              // 500 meters off route → SOS
    private val MIN_MOVE_DISTANCE = 15f               // less than 15m = "not moving"
    private val CHECK_INTERVAL    = 30_000L           // check every 30 seconds

    // Tracking
    private var lastMovedTime     = 0L
    private var lastLocation:     Location? = null
    private var checkInDone       = false

    private val handler           = Handler(Looper.getMainLooper())
    private val fusedClient       = LocationServices.getFusedLocationProviderClient(context)
    private lateinit var locationCallback: LocationCallback

    // ══════════════════════════════════════════════════════════════════
    // START
    // ══════════════════════════════════════════════════════════════════
    fun start(
        destinationLat:  Double,
        destinationLng:  Double,
        destinationName: String,
        currentLocation: Location?
    ) {
        if (isActive) stop()

        destLat   = destinationLat
        destLng   = destinationLng
        destName  = destinationName
        checkInDone   = false
        lastMovedTime = System.currentTimeMillis()

        // Save start location
        currentLocation?.let {
            startLat = it.latitude
            startLng = it.longitude
        }

        isActive = true
        Log.d(TAG, "SafeRoute started → $destName ($destLat, $destLng)")

        startLocationTracking()
        schedulePeriodicCheck()

        // Save state to prefs
        saveState(true, destName)
    }

    // ══════════════════════════════════════════════════════════════════
    // STOP (user checked in safely)
    // ══════════════════════════════════════════════════════════════════
    fun stop() {
        isActive      = false
        checkInDone   = true
        handler.removeCallbacksAndMessages(null)

        if (::locationCallback.isInitialized) {
            fusedClient.removeLocationUpdates(locationCallback)
        }

        saveState(false, "")
        Log.d(TAG, "SafeRoute stopped — user checked in")
    }

    // ══════════════════════════════════════════════════════════════════
    // LOCATION TRACKING
    // ══════════════════════════════════════════════════════════════════
    private fun startLocationTracking() {
        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            CHECK_INTERVAL
        )
            .setMinUpdateIntervalMillis(CHECK_INTERVAL / 2)
            .setWaitForAccurateLocation(false)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { loc ->
                    processLocation(loc)
                }
            }
        }

        try {
            fusedClient.requestLocationUpdates(
                request, locationCallback, Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission missing: ${e.message}")
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // PROCESS LOCATION UPDATE
    // ══════════════════════════════════════════════════════════════════
    private fun processLocation(current: Location) {
        if (!isActive) return

        Log.d(TAG, "SafeRoute location: ${current.latitude}, ${current.longitude}")

        // ── Check 1: Has user arrived at destination? ─────────────────
        val distToDest = distanceBetween(
            current.latitude, current.longitude,
            destLat, destLng
        )
        Log.d(TAG, "Distance to destination: ${distToDest.toInt()}m")

        if (distToDest < 150f) {
            Log.d(TAG, "SafeRoute: User arrived at $destName")
            // Don't fire SOS — arrived safely
            // User should manually check in via button
            return
        }

        // ── Check 2: Has user stopped moving? ────────────────────────
        val last = lastLocation
        if (last != null) {
            val moved = current.distanceTo(last)

            if (moved < MIN_MOVE_DISTANCE) {
                // Not moving
                val stoppedFor = System.currentTimeMillis() - lastMovedTime
                Log.d(TAG, "Not moving — stopped for ${stoppedFor/1000}s")

                if (stoppedFor >= STOP_TIMEOUT) {
                    Log.d(TAG, "SafeRoute SOS — stopped moving for 5 min!")
                    triggerSOS("No movement detected for 5 minutes on route to $destName")
                    return
                }
            } else {
                // Moving normally — reset timer
                lastMovedTime = System.currentTimeMillis()
                Log.d(TAG, "Moving normally — distance moved: ${moved.toInt()}m")
            }
        }

        // ── Check 3: Major deviation from straight-line route? ────────
        if (startLat != 0.0 && startLng != 0.0) {
            val deviation = pointToLineDistance(
                current.latitude, current.longitude,
                startLat, startLng,
                destLat, destLng
            )
            Log.d(TAG, "Route deviation: ${deviation.toInt()}m")

            if (deviation > DEVIATION_LIMIT) {
                Log.d(TAG, "SafeRoute SOS — major deviation: ${deviation.toInt()}m!")
                triggerSOS("Deviated ${deviation.toInt()}m from route to $destName")
                return
            }
        }

        lastLocation = current
    }

    // ══════════════════════════════════════════════════════════════════
    // TRIGGER SOS
    // ══════════════════════════════════════════════════════════════════
    private fun triggerSOS(reason: String) {
        if (!isActive) return
        stop()  // stop tracking first
        onSOSTrigger(reason)
    }

    // ══════════════════════════════════════════════════════════════════
    // PERIODIC CHECK (backup — in case location updates stop)
    // ══════════════════════════════════════════════════════════════════
    private fun schedulePeriodicCheck() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (!isActive) return

                val timeSinceMove = System.currentTimeMillis() - lastMovedTime
                if (timeSinceMove >= STOP_TIMEOUT && lastLocation != null) {
                    Log.d(TAG, "Periodic check — no movement for ${timeSinceMove/60000} min")
                    triggerSOS("No movement detected for 5 minutes on route to $destName")
                    return
                }

                handler.postDelayed(this, CHECK_INTERVAL)
            }
        }, CHECK_INTERVAL)
    }

    // ══════════════════════════════════════════════════════════════════
    // MATH HELPERS
    // ══════════════════════════════════════════════════════════════════

    // Straight-line distance between two coordinates (meters)
    private fun distanceBetween(
        lat1: Double, lng1: Double,
        lat2: Double, lng2: Double
    ): Float {
        val result = FloatArray(1)
        Location.distanceBetween(lat1, lng1, lat2, lng2, result)
        return result[0]
    }

    // Perpendicular distance from point P to line (A → B) in meters
    private fun pointToLineDistance(
        pLat: Double, pLng: Double,
        aLat: Double, aLng: Double,
        bLat: Double, bLng: Double
    ): Float {
        // Convert to rough meters
        val scale = 111_000.0  // 1 degree ≈ 111km

        val px = (pLng - aLng) * scale * Math.cos(Math.toRadians(aLat))
        val py = (pLat - aLat) * scale
        val bx = (bLng - aLng) * scale * Math.cos(Math.toRadians(aLat))
        val by = (bLat - aLat) * scale

        val len2 = bx * bx + by * by
        if (len2 == 0.0) return distanceBetween(pLat, pLng, aLat, aLng)

        val t = ((px * bx + py * by) / len2).coerceIn(0.0, 1.0)
        val nearX = bx * t
        val nearY = by * t
        val dx = px - nearX
        val dy = py - nearY

        return Math.sqrt(dx * dx + dy * dy).toFloat()
    }

    // ── Save active state to prefs ────────────────────────────────────
    private fun saveState(active: Boolean, name: String) {
        context.getSharedPreferences("urgx_settings", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("safe_route_active", active)
            .putString("safe_route_dest", name)
            .apply()
    }
}