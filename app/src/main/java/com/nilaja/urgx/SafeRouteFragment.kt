package com.nilaja.urgx

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment

class SafeRouteFragment : Fragment() {

    private lateinit var safeRouteManager: SafeRouteManager
    private lateinit var tvStatus:    TextView
    private lateinit var tvDestStatus:TextView
    private lateinit var etDest:      EditText
    private lateinit var btnStart:    Button
    private lateinit var btnCheckIn:  Button

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_safe_route, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvStatus    = view.findViewById(R.id.tvStatus)
        tvDestStatus= view.findViewById(R.id.tvDestStatus)
        etDest      = view.findViewById(R.id.etDestination)
        btnStart    = view.findViewById(R.id.btnStartRoute)
        btnCheckIn  = view.findViewById(R.id.btnCheckIn)

        // Init SafeRouteManager
        safeRouteManager = SafeRouteManager(requireContext()) { reason ->
            // This runs when auto SOS fires
            requireActivity().runOnUiThread {
                Toast.makeText(
                    requireContext(),
                    "Auto SOS: $reason",
                    Toast.LENGTH_LONG
                ).show()
                updateUI(false)
            }
            // Fire actual SOS
            SOSService.fireSafeRouteSOS(reason)
        }

        // Start button
        btnStart.setOnClickListener {
            val destName = etDest.text.toString().trim()
            if (destName.isEmpty()) {
                Toast.makeText(requireContext(),
                    "Please enter destination", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            btnStart.isEnabled = false
            btnStart.text = "Getting location..."

            // ✅ Direct location request — SOSService pe depend mat karo
            val fusedClient = com.google.android.gms.location.LocationServices
                .getFusedLocationProviderClient(requireContext())

            try {
                fusedClient.lastLocation.addOnSuccessListener { location ->
                    if (location != null) {
                        // Got location — start route
                        safeRouteManager.start(
                            destinationLat  = 0.0,
                            destinationLng  = 0.0,
                            destinationName = destName,
                            currentLocation = location
                        )
                        updateUI(true, destName)
                        Toast.makeText(requireContext(),
                            "Safe route started to $destName ✓",
                            Toast.LENGTH_SHORT).show()
                    } else {
                        // lastLocation null — request fresh
                        val request = com.google.android.gms.location.LocationRequest.Builder(
                            com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY,
                            1000L
                        ).setMaxUpdates(1).build()

                        fusedClient.requestLocationUpdates(
                            request,
                            object : com.google.android.gms.location.LocationCallback() {
                                override fun onLocationResult(
                                    result: com.google.android.gms.location.LocationResult
                                ) {
                                    fusedClient.removeLocationUpdates(this)
                                    val loc = result.lastLocation
                                    requireActivity().runOnUiThread {
                                        safeRouteManager.start(
                                            destinationLat  = 0.0,
                                            destinationLng  = 0.0,
                                            destinationName = destName,
                                            currentLocation = loc
                                        )
                                        updateUI(true, destName)
                                        Toast.makeText(requireContext(),
                                            "Safe route started to $destName ✓",
                                            Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            android.os.Looper.getMainLooper()
                        )
                    }
                }.addOnFailureListener {
                    requireActivity().runOnUiThread {
                        btnStart.isEnabled = true
                        btnStart.text = "Start Safe Route"
                        Toast.makeText(requireContext(),
                            "Location failed — check GPS permission",
                            Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: SecurityException) {
                btnStart.isEnabled = true
                btnStart.text = "Start Safe Route"
                Toast.makeText(requireContext(),
                    "Location permission required",
                    Toast.LENGTH_SHORT).show()
            }
        }

        // Check in button — arrived safely
        btnCheckIn.setOnClickListener {
            safeRouteManager.stop()
            updateUI(false)
            Toast.makeText(requireContext(),
                "Checked in safely!", Toast.LENGTH_SHORT).show()
        }

        // Restore UI if route was active
        val prefs = requireContext()
            .getSharedPreferences("urgx_settings", android.content.Context.MODE_PRIVATE)
        val wasActive = prefs.getBoolean("safe_route_active", false)
        val savedDest = prefs.getString("safe_route_dest", "") ?: ""
        if (wasActive && savedDest.isNotEmpty()) {
            updateUI(true, savedDest)
        }
    }

    private fun updateUI(active: Boolean, destName: String = "") {
        if (active) {
            tvStatus.text     = "● Active"
            tvStatus.setTextColor(android.graphics.Color.parseColor("#00D26A"))
            tvDestStatus.text = "Monitoring route to $destName"
            tvDestStatus.setTextColor(android.graphics.Color.parseColor("#00D26A"))
            btnStart.visibility   = View.GONE
            btnCheckIn.visibility = View.VISIBLE
            etDest.isEnabled      = false
            btnStart.isEnabled = true
            btnStart.text = "Start Safe Route"
        } else {
            tvStatus.text     = "● Inactive"
            tvStatus.setTextColor(android.graphics.Color.parseColor("#555577"))
            tvDestStatus.text = "No active route"
            tvDestStatus.setTextColor(android.graphics.Color.parseColor("#333355"))
            btnStart.visibility   = View.VISIBLE
            btnCheckIn.visibility = View.GONE
            etDest.isEnabled      = true
            etDest.text.clear()
            btnStart.isEnabled = true
            btnStart.text = "Start Safe Route"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Don't stop manager here — it should keep running in background
    }
}