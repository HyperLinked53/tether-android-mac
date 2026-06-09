package com.conduit.android.ui.theme

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Persists the user's theme and connection preferences across restarts. */
object ThemeStore {
    private lateinit var prefs: SharedPreferences

    private val _mode = MutableStateFlow("system")
    /** "system" | "light" | "dark" */
    val mode: StateFlow<String> get() = _mode

    private val _disconnectOption = MutableStateFlow("never")
    /** "never" | "1h" | "5h" | "12h" | "custom" */
    val disconnectOption: StateFlow<String> get() = _disconnectOption

    private val _disconnectCustomHours = MutableStateFlow(2)
    val disconnectCustomHours: StateFlow<Int> get() = _disconnectCustomHours

    fun init(context: Context) {
        prefs = context.getSharedPreferences("tether_prefs", Context.MODE_PRIVATE)
        _mode.value = prefs.getString("theme_mode", "system") ?: "system"
        _disconnectOption.value = prefs.getString("disconnect_option", "never") ?: "never"
        _disconnectCustomHours.value = prefs.getInt("disconnect_custom_hours", 2)
    }

    fun setMode(mode: String) {
        _mode.value = mode
        prefs.edit().putString("theme_mode", mode).apply()
    }

    fun setDisconnectOption(option: String) {
        _disconnectOption.value = option
        prefs.edit().putString("disconnect_option", option).apply()
    }

    fun setDisconnectCustomHours(hours: Int) {
        _disconnectCustomHours.value = hours
        prefs.edit().putInt("disconnect_custom_hours", hours).apply()
    }

    /** Returns the auto-disconnect delay in milliseconds, or null if never. */
    fun disconnectDelayMs(): Long? = when (_disconnectOption.value) {
        "1h"     -> 3_600_000L
        "5h"     -> 18_000_000L
        "12h"    -> 43_200_000L
        "custom" -> _disconnectCustomHours.value.toLong().coerceAtLeast(1) * 3_600_000L
        else     -> null
    }
}
