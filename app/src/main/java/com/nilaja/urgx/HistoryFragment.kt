package com.nilaja.urgx

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import org.json.JSONArray

class HistoryFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_history, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val listView = view.findViewById<ListView>(R.id.listHistory)
        loadHistory(listView)

        view.findViewById<Button>(R.id.btnClearHistory).setOnClickListener {
            requireContext()
                .getSharedPreferences("urgx_history", Context.MODE_PRIVATE)
                .edit().clear().apply()
            loadHistory(listView)
            Toast.makeText(requireContext(), "History cleared", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadHistory(listView: ListView) {
        val prefs = requireContext()
            .getSharedPreferences("urgx_history", Context.MODE_PRIVATE)
        val json = prefs.getString("history_log", "[]")
        val array = JSONArray(json)

        if (array.length() == 0) {
            listView.adapter = ArrayAdapter(
                requireContext(),
                android.R.layout.simple_list_item_1,
                listOf("No alerts yet")
            )
            return
        }

        val items = mutableListOf<Map<String, String>>()
        for (i in array.length() - 1 downTo 0) {
            val obj = array.getJSONObject(i)
            items.add(
                mapOf(
                    "type"   to obj.optString("type", "SOS sent"),
                    "status" to obj.optString("status", "Delivered"),
                    "time"   to obj.optString("time", ""),
                    "detail" to obj.optString("detail", "")
                )
            )
        }

        listView.adapter = object : ArrayAdapter<Map<String, String>>(
            requireContext(), R.layout.item_history, items
        ) {
            override fun getView(pos: Int, conv: View?, parent: ViewGroup): View {
                val v = conv ?: LayoutInflater.from(context)
                    .inflate(R.layout.item_history, parent, false)
                val item = items[pos]
                v.findViewById<TextView>(R.id.tvHistoryType).text  = item["type"]
                v.findViewById<TextView>(R.id.tvHistoryStatus).text = item["status"]
                v.findViewById<TextView>(R.id.tvHistoryTime).text   = item["time"]
                v.findViewById<TextView>(R.id.tvHistoryDetail).text = item["detail"]

                // Color status
                val statusView = v.findViewById<TextView>(R.id.tvHistoryStatus)
                statusView.setTextColor(
                    if (item["status"] == "Delivered")
                        0xFF00D26A.toInt() else 0xFF888888.toInt()
                )
                return v
            }
        }
    }
}