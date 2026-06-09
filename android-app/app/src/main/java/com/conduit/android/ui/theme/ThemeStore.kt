package com.conduit.android.ui.theme

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Persists the user's theme preference across restarts. */
object ThemeStore {
    private lateinit var prefs: SharedPreferences

    private val _mode = MutableStateFlow("system")

    /** "system" | "light" | "dark" */
    val mode: StateFlow<String> get() = _mode

    fun init(context: Context) {
        prefs  = context.getSharedPreferences("tether_prefs", Context.MODE_PRIVATE)
        _mode.value = prefs.getString("theme_mode", "system") ?: "system"
    }

    fun setMode(mode: String) {
        _mode.value = mode
        prefs.edit().putString("theme_mode", mode).apply()
    }
}
