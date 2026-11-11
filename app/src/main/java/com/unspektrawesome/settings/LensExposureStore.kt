package com.unspektrawesome.settings

import android.content.Context

class LensExposureStore(context: Context) : LensExposureStorage {
    private val preferences = context.applicationContext.getSharedPreferences(
        "lens_exposure", Context.MODE_PRIVATE,
    )

    override fun load(routeId: String): Int = preferences.getInt(routeId, 0)

    override fun save(routeId: String, compensation: Int) {
        preferences.edit().putInt(routeId, compensation).apply()
    }
}
