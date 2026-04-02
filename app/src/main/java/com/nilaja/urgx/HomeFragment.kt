package com.nilaja.urgx

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment

class HomeFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_home, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val prefs = requireContext()
            .getSharedPreferences("urgx_contacts", Context.MODE_PRIVATE)

        var count = 0
        for (i in 1..3) {
            if (!prefs.getString("contact_${i}_phone", null).isNullOrEmpty()) count++
        }

        view.findViewById<TextView>(R.id.tvContactCount).text = count.toString()
    }
}