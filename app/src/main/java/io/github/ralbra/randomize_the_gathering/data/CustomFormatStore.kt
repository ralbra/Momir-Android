package io.github.ralbra.randomize_the_gathering.data

import android.content.Context
import androidx.core.content.edit
import io.github.ralbra.randomize_the_gathering.model.CustomFormat

/** The saved custom formats, in the order they were created. */
class CustomFormatStore(context: Context) {
    private val prefs = context.getSharedPreferences("momir_custom_formats", Context.MODE_PRIVATE)

    fun load(): List<CustomFormat> = CustomFormatCodec.decode(prefs.getString(KEY, null))

    /** Adds [format], or replaces the saved one with the same id. Returns the new list. */
    fun save(format: CustomFormat): List<CustomFormat> {
        val list = load().toMutableList()
        val index = list.indexOfFirst { it.id == format.id }
        if (index >= 0) list[index] = format else list.add(format)
        write(list)
        return list
    }

    fun delete(id: String): List<CustomFormat> {
        val list = load().filter { it.id != id }
        write(list)
        return list
    }

    private fun write(list: List<CustomFormat>) {
        prefs.edit { putString(KEY, CustomFormatCodec.encode(list)) }
    }

    private companion object {
        const val KEY = "formats"
    }
}
