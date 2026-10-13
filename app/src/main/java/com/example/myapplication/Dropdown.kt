package com.example.myapplication

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.Filter
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout

/** A Material exposed dropdown menu holding labelled values of type [T]. */
class Dropdown<T>(private val layout: TextInputLayout, private val onChange: (T) -> Unit = {}) {
    private val field = layout.editText as MaterialAutoCompleteTextView
    private var options: List<Pair<String, T>> = emptyList()
    @Volatile private var current: T? = null

    /** The selection; null only where null is one of the options. */
    @Suppress("UNCHECKED_CAST")
    val value: T get() = current as T

    var enabled: Boolean
        get() = layout.isEnabled
        set(enabled) { layout.isEnabled = enabled }

    init {
        field.isSaveEnabled = false
        field.setOnItemClickListener { _, _, position, _ -> select(options[position].second) }
    }

    /** Replaces the options, keeping [keep] selected if it is still offered, else the first option. */
    fun setOptions(options: List<Pair<String, T>>, keep: T? = current) {
        this.options = options
        field.setAdapter(NoFilterAdapter(layout.context, options.map { it.first }))
        val selected = options.firstOrNull { it.second == keep } ?: options.first()
        current = selected.second
        field.setText(selected.first, false)
    }

    /** Selects [value] if it is one of the options; returns whether it was. */
    fun select(value: T, notify: Boolean = true): Boolean {
        val option = options.firstOrNull { it.second == value } ?: return false
        val changed = value != current
        current = value
        field.setText(option.first, false)
        if (notify && changed) onChange(value)
        return true
    }

    /** Always shows every option; the default filter would hide all but the selected text. */
    private class NoFilterAdapter(context: Context, private val items: List<String>) :
        ArrayAdapter<String>(context, android.R.layout.simple_list_item_1, items) {
        override fun getFilter() = object : Filter() {
            override fun performFiltering(constraint: CharSequence?) =
                FilterResults().apply { values = items; count = items.size }
            override fun publishResults(constraint: CharSequence?, results: FilterResults?) = notifyDataSetChanged()
        }
    }
}
