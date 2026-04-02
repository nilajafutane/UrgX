package com.nilaja.urgx

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray
import org.json.JSONObject

class ContactsFragment : Fragment() {

    private val contacts = mutableListOf<Pair<String, String>>() // name, phone
    private lateinit var adapter: ContactAdapter

    // Contact picker
    private val contactPicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                val (name, phone) = getContactDetails(uri)
                if (phone.isNotEmpty()) {
                    // Check for duplicate
                    if (contacts.any { it.second == phone }) {
                        Toast.makeText(requireContext(),
                            "This contact is already added", Toast.LENGTH_SHORT).show()
                        return@let
                    }
                    contacts.add(Pair(name, phone))
                    adapter.notifyItemInserted(contacts.size - 1)
                    saveAllContacts()
                    Toast.makeText(requireContext(),
                        "$name added!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(),
                        "Contact has no phone number", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_contacts, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Load saved contacts
        loadContacts()

        // Setup RecyclerView
        adapter = ContactAdapter(contacts) { position ->
            val removed = contacts[position]
            contacts.removeAt(position)
            adapter.notifyItemRemoved(position)
            adapter.notifyItemRangeChanged(position, contacts.size)
            saveAllContacts()
            Toast.makeText(requireContext(),
                "${removed.first} removed", Toast.LENGTH_SHORT).show()
        }

        view.findViewById<RecyclerView>(R.id.rvContacts).apply {
            layoutManager = LinearLayoutManager(requireContext())
            this.adapter = this@ContactsFragment.adapter
        }

        // Add contact button
        view.findViewById<Button>(R.id.btnAddContact).setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK,
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
            contactPicker.launch(intent)
        }
    }

    private fun getContactDetails(uri: Uri): Pair<String, String> {
        var name  = ""
        var phone = ""
        requireContext().contentResolver.query(
            uri,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null, null, null
        )?.use {
            if (it.moveToFirst()) {
                name  = it.getString(0) ?: ""
                phone = it.getString(1)?.replace("\\s".toRegex(), "") ?: ""
            }
        }
        return Pair(name, phone)
    }

    private fun saveAllContacts() {
        val prefs = requireContext()
            .getSharedPreferences("urgx_contacts", Context.MODE_PRIVATE)
        val array = JSONArray()
        contacts.forEachIndexed { index, (name, phone) ->
            array.put(JSONObject().apply {
                put("name", name)
                put("phone", phone)
            })
            // Keep old format keys for AlertManager compatibility
            prefs.edit()
                .putString("contact_${index + 1}", phone)
                .apply()
        }
        // Clear old keys beyond current count
        val editor = prefs.edit()
        for (i in contacts.size + 1..20) {
            editor.remove("contact_$i")
        }
        editor.putString("contacts_json", array.toString())
        editor.apply()
    }

    private fun loadContacts() {
        val prefs = requireContext()
            .getSharedPreferences("urgx_contacts", Context.MODE_PRIVATE)
        val json = prefs.getString("contacts_json", "[]")
        val array = JSONArray(json)
        contacts.clear()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            contacts.add(Pair(
                obj.optString("name", ""),
                obj.optString("phone", "")
            ))
        }
    }

    // ── RecyclerView Adapter ───────────────────────────────────────────
    inner class ContactAdapter(
        private val items: List<Pair<String, String>>,
        private val onDelete: (Int) -> Unit
    ) : RecyclerView.Adapter<ContactAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val initial:   TextView = view.findViewById(R.id.tvInitial)
            val name:      TextView = view.findViewById(R.id.tvContactName)
            val phone:     TextView = view.findViewById(R.id.tvContactPhone)
            val deleteBtn: TextView = view.findViewById(R.id.btnDelete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_contact, parent, false)
        )

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val (name, phone) = items[position]
            holder.name.text  = name
            holder.phone.text = phone
            holder.initial.text = if (name.isNotEmpty())
                name.first().uppercaseChar().toString() else "#"

            // Cycle avatar colors
            val colors = listOf(
                0xFFFF6B2B.toInt(), 0xFF48CAE4.toInt(), 0xFF7CB518.toInt(),
                0xFFC77DFF.toInt(), 0xFFFF2D55.toInt(), 0xFF00D26A.toInt()
            )
            holder.initial.setBackgroundColor(colors[position % colors.size])
            holder.deleteBtn.setOnClickListener { onDelete(position) }
        }
    }
}