package com.conduit.android.connection

import android.content.Context
import android.os.Build
import java.util.UUID

/** Stable per-install identity for this device, persisted in SharedPreferences. */
object DeviceIdentity {
    private const val PREFS = "tether_identity"
    private const val KEY_ID = "device_id"

    fun id(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_ID, it).apply()
        }
    }

    fun name(): String {
        val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val model = Build.MODEL
        return if (model.startsWith(manufacturer, ignoreCase = true)) model else "$manufacturer $model"
    }

    fun info(context: Context, appVersion: String) = DeviceInfo(
        id = id(context),
        name = name(),
        platform = "android",
        appVersion = appVersion,
    )
}
