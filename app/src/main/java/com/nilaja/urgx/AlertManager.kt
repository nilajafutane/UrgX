package com.nilaja.urgx

import android.content.Context
import android.location.Location
import android.os.Looper
import android.telephony.SmsManager
import android.util.Log
import com.google.android.gms.location.*
import org.json.JSONArray

class AlertManager(private val context: Context) {

    private val TAG = "UrgX"
    private val fusedClient = LocationServices.getFusedLocationProviderClient(context)

    // ══════════════════════════════════════════════════════════════════
    // MAIN ENTRY POINT — called when SOS fires
    // ══════════════════════════════════════════════════════════════════
    fun sendSOS() {
        Log.d(TAG, "AlertManager: sendSOS() called")

        // ── Fix 2: use GPS already warmed up by SOSService ────────────
        val warmLocation = SOSService.instance?.lastKnownLocation

        if (warmLocation != null) {
            Log.d(TAG, "GPS warm — using cached location: " +
                    "${warmLocation.latitude}, ${warmLocation.longitude} " +
                    "acc=${warmLocation.accuracy}m")
            // Send SMS immediately — no waiting
            sendSMSToAllContacts(warmLocation)
        } else {
            // Warm location not available — request fresh
            Log.d(TAG, "Warm location null — requesting fresh GPS fix")
            requestFreshLocation()
        }

        // Audio recording starts in parallel — regardless of GPS status
        // (AudioRecorder is called from SOSService directly)
    }

    // ══════════════════════════════════════════════════════════════════
    // FRESH GPS REQUEST (fallback when warm location is null)
    // ══════════════════════════════════════════════════════════════════
    private fun requestFreshLocation() {
        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            1000L
        )
            .setMaxUpdates(1)
            .setWaitForAccurateLocation(false)
            .build()

        try {
            fusedClient.requestLocationUpdates(
                request,
                object : LocationCallback() {
                    override fun onLocationResult(result: LocationResult) {
                        fusedClient.removeLocationUpdates(this)
                        val loc = result.lastLocation
                        Log.d(TAG, "Fresh GPS fix: ${loc?.latitude}, ${loc?.longitude}")
                        sendSMSToAllContacts(loc)
                    }
                },
                Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission missing: ${e.message}")
            sendSMSToAllContacts(null)  // send SMS without location
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // SEND SOS SMS TO ALL CONTACTS
    // ══════════════════════════════════════════════════════════════════
    fun sendSMSToAllContacts(location: Location?) {
        val message = buildSOSMessage(location)
        Log.d(TAG, "SOS SMS message: $message")

        val contacts = getContacts()

        if (contacts.isEmpty()) {
            Log.w(TAG, "No contacts saved — SMS not sent!")
            return
        }

        contacts.forEach { number ->
            sendSMS(number, message)
            Log.d(TAG, "SOS SMS sent to: $number")
        }

        // Log this SOS event to history
        logAlert("SOS", "SENT", location?.let {
            "${it.latitude},${it.longitude}"
        } ?: "no location")
    }

    // ══════════════════════════════════════════════════════════════════
    // SEND AUDIO LINK SMS (called after Cloudinary upload completes)
    // ══════════════════════════════════════════════════════════════════
    fun sendAudioLinkToAllContacts(audioUrl: String) {
        val message = "UrgX follow-up: Voice recording — $audioUrl"
        Log.d(TAG, "Audio link SMS: $message")

        val contacts = getContacts()
        contacts.forEach { number ->
            sendSMS(number, message)
            Log.d(TAG, "Audio link SMS sent to: $number")
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // SEND FALSE ALARM SMS (called when slow-shake cancel detected)
    // ══════════════════════════════════════════════════════════════════
    fun sendFalseAlarmSMS() {
        val message = "False alarm — I am safe. Please ignore the previous message. — UrgX"
        Log.d(TAG, "False alarm SMS: $message")

        val contacts = getContacts()

        if (contacts.isEmpty()) {
            Log.w(TAG, "No contacts — false alarm SMS not sent")
            return
        }

        contacts.forEach { number ->
            sendSMS(number, message)
            Log.d(TAG, "False alarm SMS sent to: $number")
        }

        logAlert("FALSE_ALARM", "SENT", "User cancelled SOS")
    }

    // ══════════════════════════════════════════════════════════════════
    // BUILD SOS MESSAGE TEXT
    // ══════════════════════════════════════════════════════════════════
    private fun buildSOSMessage(location: Location?): String {
        return if (location != null) {
            val mapsLink =
                "https://maps.google.com/?q=${location.latitude},${location.longitude}"
            "URGENT: I need help! My location: $mapsLink — Sent via UrgX"
        } else {
            "URGENT: I need help! (Location unavailable) — Sent via UrgX"
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // SEND SINGLE SMS
    // ══════════════════════════════════════════════════════════════════
    private fun sendSMS(phoneNumber: String, message: String) {
        try {
            val smsManager = if (android.os.Build.VERSION.SDK_INT
                >= android.os.Build.VERSION_CODES.S
            ) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }

            // Split if message exceeds 160 chars
            val parts = smsManager.divideMessage(message)
            smsManager.sendMultipartTextMessage(
                phoneNumber, null, parts, null, null
            )
            Log.d(TAG, "SMS dispatched to $phoneNumber")

        } catch (e: Exception) {
            Log.e(TAG, "SMS failed to $phoneNumber: ${e.message}")
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // READ CONTACTS from SharedPreferences (contacts_json key)
    // ══════════════════════════════════════════════════════════════════
    private fun getContacts(): List<String> {
        val prefs = context.getSharedPreferences("urgx_contacts", Context.MODE_PRIVATE)
        val numbers = mutableListOf<String>()

        // Method 1: JSON array format (used by ContactsFragment)
        val contactsJson = prefs.getString("contacts_json", null)
        if (!contactsJson.isNullOrEmpty()) {
            try {
                val arr = JSONArray(contactsJson)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val phone = obj.optString("phone", "")
                    if (phone.isNotEmpty()) numbers.add(phone)
                }
                Log.d(TAG, "Contacts from JSON: ${numbers.size} contacts")
                return numbers
            } catch (e: Exception) {
                Log.e(TAG, "JSON parse error: ${e.message}")
            }
        }

        // Method 2: Legacy individual keys (contact_1, contact_2, ...)
        for (i in 1..10) {
            val number = prefs.getString("contact_$i", null) ?: continue
            numbers.add(number)
        }

        Log.d(TAG, "Contacts from legacy keys: ${numbers.size} contacts")
        return numbers
    }

    // ══════════════════════════════════════════════════════════════════
    // LOG EVENT TO HISTORY
    // ══════════════════════════════════════════════════════════════════
    fun logAlert(type: String, status: String, detail: String) {
        try {
            val prefs = context.getSharedPreferences("urgx_history", Context.MODE_PRIVATE)
            val existingJson = prefs.getString("history_json", "[]") ?: "[]"
            val arr = JSONArray(existingJson)

            val entry = org.json.JSONObject().apply {
                put("type", type)
                put("status", status)
                put("detail", detail)
                put("timestamp", System.currentTimeMillis())
                put("time_readable",
                    java.text.SimpleDateFormat(
                        "dd MMM yyyy, hh:mm a",
                        java.util.Locale.getDefault()
                    ).format(java.util.Date()))
            }

            // Add to front of array (newest first)
            val newArr = JSONArray()
            newArr.put(entry)
            for (i in 0 until arr.length()) newArr.put(arr.get(i))

            prefs.edit().putString("history_json", newArr.toString()).apply()
            Log.d(TAG, "Event logged: $type — $status — $detail")

        } catch (e: Exception) {
            Log.e(TAG, "History log failed: ${e.message}")
        }
    }
}